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

    fun speak(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return

        val s = AgentSettings.load()
        val engine: SpeechOutput = when (s.ttsEngine) {
            AgentSettings.TTS_OFF -> return
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
                }
            },
        )
    }

    /**
     * 抢占式播报。
     *
     * @param interrupt true = 打断当前正在播的话（导航提示用）
     *
     * ★ 导航播报必须抢占：「前方 200 米右转」这种话晚 3 秒说出来就没意义了，
     *   而普通播报（「已经帮你打开了设置」）晚一点无所谓。
     */
    fun speakPriority(text: String, interrupt: Boolean) {
        if (interrupt) {
            // 先停掉当前正在播的，避免两句话叠在一起
            stop()
        }
        speak(text)
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
