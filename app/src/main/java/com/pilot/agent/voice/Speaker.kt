package com.pilot.agent.voice

import android.content.Context
import com.pilot.agent.Dbg
import com.pilot.agent.agent.AgentSettings

/**
 * 播报路由：按设置选系统 TTS 还是 MiniMax。
 *
 * 引擎按需创建——SystemTts 构造时就会拉起系统 TTS 引擎（有开销），
 * 只用 MiniMax 的用户不该付这个成本，反之亦然。
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext

    private var system: SystemTts? = null
    private var minimax: MiniMaxTts? = null

    @Volatile
    var lastError: String? = null
        private set

    /**
     * 导航播报（**不可打断**）。
     *
     * ## 为什么和普通播报不一样
     * 导航播报极短——「左转」「前方 200 米右转」「已到达」，
     * 而且**说完就没机会补了**（车已经开过路口）。
     * 所以它有两个特权：
     * 1. **能打断普通播报**（普通播报晚点说无所谓）
     * 2. **自己播的时候不许别人插嘴**（普通播报、甚至下一条导航播报都要排队）
     *
     * ## 实现
     * [navSpeaking] 是一个独占标记：播报期间所有 [speak] 调用直接丢弃，
     * 下一条导航播报排队等（不会丢，只是延后）。
     * 不能简单用 stop() 互斥——那样两条连续导航提示（「左转」「随后右转」）
     * 会互相打断，用户只听到半句。
     */
    fun speakNav(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        synchronized(navLock) {
            // 正在播导航 → 排队（最多排 2 条，再多就是刷屏了）
            if (navSpeaking) {
                if (navQueue.size < 2) navQueue.add(t)
                Dbg.i(TAG, "导航播报正在播，排队：$t（队列 ${navQueue.size}）")
                return
            }
            navSpeaking = true
        }
        // 打断当前普通播报（导航优先）
        stop()
        speakInternal(t, isNav = true)
    }

    /** 导航播报独占锁。 */
    private val navLock = Any()
    /** 当前是否在播导航提示。 */
    @Volatile private var navSpeaking = false
    /** 排队等播的导航提示。 */
    private val navQueue = ArrayDeque<String>()

    /** 一条导航播报播完了：播队列里的下一条，或解锁。 */
    private fun onNavSpoken() {
        val next: String?
        synchronized(navLock) {
            next = navQueue.removeFirstOrNull()
            if (next == null) navSpeaking = false
        }
        if (next != null) {
            Dbg.i(TAG, "导航播报出队：$next")
            speakInternal(next, isNav = true)
        }
    }

    fun speak(text: String) = speakInternal(text, isNav = false)

    /**
     * 实际播报。
     * @param isNav true = 导航播报（独占、播完释放锁）
     */
    private fun speakInternal(text: String, isNav: Boolean) {
        val t = text.trim()
        if (t.isEmpty()) {
            if (isNav) onNavSpoken()
            return
        }
        // ★ 导航播报期间，普通播报**直接丢弃**——
        //   导航提示短且说完就没机会补（车已经过路口了），
        //   普通播报（「已帮你打开设置」）晚点说完全无所谓。
        if (!isNav && navSpeaking) {
            Dbg.i(TAG, "导航播报中，丢弃普通播报：$t")
            return
        }

        val s = AgentSettings.load()
        val engine: SpeechOutput = when (s.ttsEngine) {
            AgentSettings.TTS_OFF -> {
                if (isNav) onNavSpoken()
                return
            }
            AgentSettings.TTS_MINIMAX -> {
                if (s.apiKey.isBlank()) {
                    // ★ 没配 key 就直接用系统 TTS（华为智慧语音等），不能一声不吭。
                    //   云端播报是「锦上添花」，系统 TTS 才是「必须能出声」的底线。
                    Dbg.i(TAG, "MiniMax 没配 key，用系统 TTS 播报")
                    system ?: SystemTts(appContext).also { system = it }
                } else {
                    minimax ?: MiniMaxTts(appContext).also { minimax = it }
                }
            }
            else -> system ?: SystemTts(appContext).also { system = it }
        }

        // 播报只说重点。UI 上的日志可以很长，念出来超过 10 秒就没人听了。
        val spoken = if (t.length > 60) t.take(60) + "……" else t

        // ★ 播报前抢一下音频焦点（TRANSIENT_MAY_DUCK）：系统会把音乐压低，
        //   而不是直接停掉——「播放一会自动就停了」的另一半原因。
        // ★ 同时占住麦克风闸门：TTS 自己的声音会被麦克风录进去，
        //   不占闸的话 ASR 会把播报内容听成用户指令，形成自问自答循环。
        val speechFocus = AudioFocusManager.beginSpeech(appContext)
        val gate = AudioGate.hold("tts")
        engine.speak(
            spoken,
            onDone = {
                runCatching { gate.close() }
                runCatching { speechFocus.close() }
                if (isNav) onNavSpoken()
            },
            onError = { msg ->
                lastError = msg
                Dbg.w(TAG, "播报失败：$msg")
                if (s.ttsEngine == AgentSettings.TTS_MINIMAX) {
                    Dbg.i(TAG, "云端播报失败，降级系统 TTS")
                    // ★ 降级时**不要**先关闸门和焦点——系统 TTS 马上要出声，
                    //   关早了它就跟音乐抢流（会断歌），还会被麦克风录进去。
                    //   让降级播报自己负责收尾（这里传一套新的 gate/focus）。
                    val sys = system ?: SystemTts(appContext).also { system = it }
                    val sysFocus = AudioFocusManager.beginSpeech(appContext)
                    val sysGate = AudioGate.hold("tts-fallback")
                    sys.speak(
                        spoken,
                        onDone = {
                            runCatching { sysGate.close() }
                            runCatching { sysFocus.close() }
                            runCatching { gate.close() }
                            runCatching { speechFocus.close() }
                        },
                        onError = { m2 ->
                            Dbg.w(TAG, "系统 TTS 兜底也失败：$m2")
                            runCatching { sysGate.close() }
                            runCatching { sysFocus.close() }
                            runCatching { gate.close() }
                            runCatching { speechFocus.close() }
                        },
                    )
                } else {
                    runCatching { gate.close() }
                    runCatching { speechFocus.close() }
                    if (isNav) onNavSpoken()
                }
            },
        )
    }

    /**
     * 抢占式播报（保留旧接口，导航场景用 [speakNav]）。
     *
     * @param interrupt true = 打断当前正在播的话
     */
    fun speakPriority(text: String, interrupt: Boolean) {
        if (interrupt) speakNav(text) else speak(text)
    }

    fun stop() {
        system?.stop()
        minimax?.stop()
    }

    fun release() {
        system?.release()
        minimax?.release()
        system = null
        minimax = null
    }

    /** 设置页展示用 */
    fun statusText(): String = when (AgentSettings.load().ttsEngine) {
        AgentSettings.TTS_OFF -> "已关闭"
        AgentSettings.TTS_MINIMAX ->
            if (AgentSettings.load().apiKey.isBlank()) "系统 TTS（云端未配 Key）"
            else minimax?.lastError?.let { "MiniMax 异常" } ?: "MiniMax 云端"
        else -> system?.lastError?.let { "系统 TTS 异常" } ?: "系统 TTS"
    }

    private companion object {
        const val TAG = "PilotTts"
    }
}
