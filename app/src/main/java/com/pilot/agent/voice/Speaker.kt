package com.pilot.agent.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.pilot.agent.Dbg
import com.pilot.agent.agent.AgentSettings
import com.pilot.navigation.VoiceState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 播报路由：按设置选系统 TTS 还是 MiniMax。
 *
 * 引擎按需创建——SystemTts 构造时就会拉起系统 TTS 引擎（有开销），
 * 只用 MiniMax 的用户不该付这个成本，反之亦然。
 *
 * ## 一条播报 = 一个 [Turn]（审核 V-02 / V-03 / V-04）
 * - 每条播报自己持有一个 [AudioGate] 令牌和一个音频焦点，**只在这条播报彻底结束时**
 *   （播完 / 被打断 / 兜底也失败 / 看门狗超时）释放一次；
 * - 云端失败降级系统 TTS 时沿用同一个 Turn，中途不关闸、不触发"播报结束→自动聆听"；
 * - [onSpeechDone] 只在**所有**普通播报都结束后才触发，播报期间不会提前开麦；
 * - 每条播报有看门狗：引擎迟迟不回调时强制收尾，导航锁 / 闸门不会永久卡死。
 *
 * ## 个人数据（审核 V-10、product T5）
 * - [speakPersonal]：个人内容（通知正文、记忆、日程…）专用入口——锁屏时不朗读，
 *   只走系统 TTS（离线），绝不发给云端 TTS；
 * - 兜底：[speak] 收到明显是通知朗读的文本（"最近的通知…"）时自动改走 [speakPersonal]，
 *   覆盖后台路径（RunReceiver / ScheduledTaskManager）直接调 speak 的情况。
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var system: SystemTts? = null
    private var minimax: MiniMaxTts? = null

    @Volatile
    var lastError: String? = null
        private set

    /** 尚未结束的播报条数（含导航）。 */
    private val activeTurns = AtomicInteger(0)

    @Synchronized
    private fun systemEngine(): SystemTts = system ?: SystemTts(appContext).also { system = it }

    @Synchronized
    private fun minimaxEngine(): MiniMaxTts = minimax ?: MiniMaxTts(appContext).also { minimax = it }

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
                Dbg.i(TAG, "导航播报正在播，排队（${t.length} 字，队列 ${navQueue.size}）")
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

    /**
     * 是否正在播报。
     *
     * UI 要用它决定"停止"按钮是否出现——
     * 老板要的停止是**终止播报**（不是终止任务）：
     * 播报太长时用户想立刻闭嘴。
     */
    @Volatile
    var isSpeaking: Boolean = false
        private set

    /**
     * 普通播报**全部**播完（或彻底失败）后的回调。
     *
     * AgentRuntime 注入成 `VoiceInput.enterConversationListening()`：
     * AI 回完这一句自动切回聆听态，形成免唤醒的连续对话。
     * 导航播报不触发——开车时不需要它接着听你闲聊。
     * 还有别的播报在进行时不触发（播报期间不提前开麦）。
     */
    @Volatile
    var onSpeechDone: (() -> Unit)? = null
    /** 排队等播的导航提示。 */
    private val navQueue = ArrayDeque<String>()

    /**
     * 导航播报期间到达的普通播报（比如用户问「讲个故事」的回答）。
     *
     * 原来是直接丢弃——用户问完问题却一声不吭，看起来像没反应。
     * 现在只保留最新一条，等导航播报结束立刻补上；再多就只说最后一句。
     */
    private var pendingNormal: PendingNormal? = null

    private data class PendingNormal(val text: String, val forceSystem: Boolean, val maxChars: Int)

    /** 一条导航播报播完了：播队列里的下一条，或解锁。 */
    private fun onNavSpoken() {
        val next: String?
        synchronized(navLock) {
            next = navQueue.removeFirstOrNull()
            if (next == null) navSpeaking = false
        }
        if (next != null) {
            Dbg.i(TAG, "导航播报出队（${next.length} 字）")
            speakInternal(next, isNav = true)
        } else {
            // 导航说完了，把刚才被挡下的普通播报补上
            val pending = synchronized(navLock) {
                pendingNormal.also { pendingNormal = null }
            }
            if (pending != null && pending.text.isNotBlank()) {
                Dbg.i(TAG, "导航播报结束，补播排队的普通播报")
                speakInternal(pending.text, isNav = false, forceSystem = pending.forceSystem, maxChars = pending.maxChars)
            }
        }
    }

    fun speak(text: String) = speakInternal(text, isNav = false)

    /** For compact multi-item search readouts that intentionally exceed the default one-sentence limit. */
    fun speakExpanded(text: String) = speakInternal(text, isNav = false, maxChars = 500)

    /**
     * Private on-device readout for notification contents; never sends text to a cloud TTS provider.
     * 现在同样受锁屏策略约束，等价于 `speakPersonal(text, null)`。
     */
    fun speakLocal(text: String) {
        speakPersonal(text, null)
    }

    /**
     * 朗读个人数据（通知正文、记忆、日程、待办、联系人等）的唯一入口。
     *
     * - 按 [PersonalDataGate] 判定：锁屏中一律不朗读；息屏且声纹未确认也不朗读；
     *   不朗读时改播一句不含个人内容的提示（"请解锁后再问我"）；
     * - 允许时**强制系统 TTS**（离线），绝不把内容交给云端 TTS。
     *
     * @param auth 发起这次请求的语音校验结果（[VoiceInput.lastSpeakerAuth]）；
     *             界面/定时/广播发起传 null。
     * @return true = 已按原文朗读；false = 因隐私策略被拦截（已播提示语）或播报关闭
     */
    fun speakPersonal(text: String, auth: SpeakerAuth? = null): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (AgentSettings.load().ttsEngine == AgentSettings.TTS_OFF) return false
        val decision = PersonalDataGate.check(appContext, auth)
        if (!decision.allowed) {
            Dbg.i(TAG, "个人内容不朗读：$decision（auth=$auth，${t.length} 字）")
            PersonalDataGate.denialMessage(decision, auth)?.let {
                speakInternal(it, isNav = false, forceSystem = true)
            }
            return false
        }
        speakInternal(t, isNav = false, forceSystem = true)
        return true
    }

    /**
     * 轻量播报：**不占麦克风闸门**。
     *
     * 用途：唤醒时的「我在」这种极短回应。
     * 为什么要特殊对待：正常播报会占闸（AudioGate.hold("tts")），
     * 那样用户"喊完唤醒词马上接指令"的那半句会被当成放音内容丢掉。
     * 「我在」只有两个字、且是固定内容，就算被麦克风听到也不会是有效指令。
     */
    fun speakQuiet(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val s = AgentSettings.load()
        val engine: SpeechOutput = when (s.ttsEngine) {
            AgentSettings.TTS_OFF -> return
            AgentSettings.TTS_MINIMAX -> {
                if (s.apiKey.isBlank()) systemEngine() else minimaxEngine()
            }
            else -> systemEngine()
        }
        // 不占闸、不抢音频焦点：麦克风继续听用户的下一句
        engine.speak(t, onDone = {}, onError = { Dbg.w(TAG, "轻量播报失败：$it") })
    }

    /**
     * 一条播报的生命周期。闸门令牌、音频焦点、看门狗都归它，[finish] 只生效一次。
     */
    private inner class Turn(private val isNav: Boolean, private val chars: Int) {
        init {
            activeTurns.incrementAndGet()
        }

        private val finished = AtomicBoolean(false)

        // ★ 播报前抢一下音频焦点（TRANSIENT_MAY_DUCK）：系统会把音乐压低，
        //   而不是直接停掉——「播放一会自动就停了」的另一半原因。
        private val focus: AutoCloseable = AudioFocusManager.beginSpeech(appContext)

        // ★ 同时占住麦克风闸门：TTS 自己的声音会被麦克风录进去，
        //   不占闸的话 ASR 会把播报内容听成用户指令，形成自问自答循环。
        private val gate: AutoCloseable = AudioGate.hold(if (isNav) "tts-nav" else "tts")

        @Volatile
        var engine: SpeechOutput? = null

        val isFinished: Boolean get() = finished.get()

        private val watchdog = Runnable {
            if (!finished.get()) {
                Dbg.w(TAG, "播报看门狗超时（$chars 字），引擎未回调，强制收尾")
                lastError = "播报超时"
                runCatching { engine?.stop() }
                finish()
            }
        }

        fun arm(ms: Long) {
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, ms)
        }

        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            main.removeCallbacks(watchdog)
            runCatching { gate.close() }
            runCatching { focus.close() }
            var left = activeTurns.decrementAndGet()
            if (left < 0) {
                activeTurns.set(0)
                left = 0
            }
            if (left == 0) {
                isSpeaking = false
                runCatching { VoiceState.write(appContext, VoiceState.IDLE) }
            }
            if (isNav) {
                onNavSpoken()
            } else if (left == 0) {
                runCatching { onSpeechDone?.invoke() }
            }
        }
    }

    /**
     * 看门狗时长：max(4s, 字数 × 300ms × 1.5 ÷ 语速) + 引擎准备余量。
     * 云端要算上合成网络往返（连接 8s + 读 20s），系统 TTS 要算上初始化（最多 4s）。
     */
    private fun watchdogMs(chars: Int, cloud: Boolean, rate: Float): Long {
        val r = rate.coerceIn(0.5f, 3f)
        val speech = (chars * 300.0 * 1.5 / r).toLong()
        val base = maxOf(4_000L, speech)
        return base + if (cloud) 30_000L else 6_000L
    }

    /**
     * 实际播报。
     * @param isNav true = 导航播报（独占、播完释放锁）
     */
    private fun speakInternal(text: String, isNav: Boolean, forceSystem: Boolean = false, maxChars: Int = 2_000) {
        val t = text.trim()
        if (t.isEmpty()) {
            if (isNav) onNavSpoken()
            return
        }
        // ★ 隐私兜底：明显是通知朗读的文本，不论谁调的 speak，都改走个人数据入口
        //   （锁屏拦截 + 只用离线系统 TTS），防止后台路径把私信正文发给云端 TTS。
        if (!isNav && !forceSystem && PersonalDataGate.looksPersonal(t)) {
            Dbg.i(TAG, "检测到个人内容朗读，改走本地 TTS 并检查锁屏")
            speakPersonal(t, null)
            return
        }
        // ★ 导航播报期间，普通播报排队（只保留最新一条）——
        //   导航提示短且说完就没机会补（车已经过路口了），
        //   普通播报（「已帮你打开设置」）晚点说完全无所谓。
        if (!isNav && navSpeaking) {
            synchronized(navLock) { pendingNormal = PendingNormal(t, forceSystem, maxChars) }
            Dbg.i(TAG, "导航播报中，普通播报排队（${t.length} 字）")
            return
        }

        val s = AgentSettings.load()
        if (s.ttsEngine == AgentSettings.TTS_OFF) {
            if (isNav) onNavSpoken()
            return
        }
        val engine: SpeechOutput = if (forceSystem) {
            systemEngine()
        } else when (s.ttsEngine) {
            AgentSettings.TTS_MINIMAX -> {
                if (s.apiKey.isBlank()) {
                    // ★ 没配 key 就直接用系统 TTS（华为智慧语音等），不能一声不吭。
                    //   云端播报是「锦上添花」，系统 TTS 才是「必须能出声」的底线。
                    Dbg.i(TAG, "MiniMax 没配 key，用系统 TTS 播报")
                    systemEngine()
                } else {
                    minimaxEngine()
                }
            }
            else -> systemEngine()
        }

        // 播报完整回答（老板要求：长文本不能只念前 60 个字）。
        // 保留一个很宽的上限，只防异常长文本把 TTS 请求撑爆。
        val spoken = if (t.length > maxChars) t.take(maxChars) + "……" else t

        // ★ 通知导航界面「正在播报」（显示喇叭动画）
        runCatching { VoiceState.write(appContext, VoiceState.SPEAKING) }
        isSpeaking = true

        val turn = Turn(isNav, spoken.length)
        turn.engine = engine
        val cloud = engine is MiniMaxTts
        turn.arm(watchdogMs(spoken.length, cloud, s.ttsRate))
        engine.speak(
            spoken,
            onDone = { turn.finish() },
            onError = { msg ->
                lastError = msg
                Dbg.w(TAG, "播报失败：$msg")
                if (!turn.isFinished && cloud) {
                    Dbg.i(TAG, "云端播报失败，降级系统 TTS")
                    // ★ 降级沿用同一个 Turn：闸门和焦点**不关**——系统 TTS 马上要出声，
                    //   关早了它就跟音乐抢流（会断歌），还会被麦克风录进去，
                    //   也不会提前触发"播报结束→自动聆听"。
                    val sys = systemEngine()
                    turn.engine = sys
                    turn.arm(watchdogMs(spoken.length, false, s.ttsRate))
                    sys.speak(
                        spoken,
                        onDone = { turn.finish() },
                        onError = { m2 ->
                            lastError = m2
                            Dbg.w(TAG, "系统 TTS 兜底也失败：$m2")
                            turn.finish()
                        },
                    )
                } else {
                    turn.finish()
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

    /**
     * 预听一个音色（不改变当前设置）。
     *
     * @param engine TTS_MINIMAX / TTS_SYSTEM
     * @param voiceId MiniMax 是 voice_id；系统 TTS 是 Voice.name
     */
    fun previewVoice(
        engine: String,
        voiceId: String,
        sample: String = "你好，我是小派，这是音色的效果试听。",
        rate: Float? = null,
    ) {
        runCatching {
            when (engine) {
                com.pilot.agent.agent.AgentSettings.TTS_MINIMAX -> {
                    minimaxEngine().preview(sample, voiceId, rate = rate) {}
                }
                com.pilot.agent.agent.AgentSettings.TTS_SYSTEM -> {
                    systemEngine().preview(sample, voiceId, rate)
                }
            }
        }.onFailure { Dbg.w(TAG, "预听失败 ${it.message}") }
    }

    /**
     * 列出系统 TTS 可用音色。
     *
     * ★ 复用 Speaker 自己的 system 实例（和实际播报同一个引擎），
     *   不能新建一个——新建的引擎初始化还没完成，枚举出来是空的（实测）。
     */
    fun systemVoices(): List<SystemVoice> = systemEngine().availableVoices()

    /**
     * 应用系统 TTS 音色（设置里切换后立即生效）。
     *
     * 只影响系统 TTS；MiniMax 的音色是每次请求带 voice_id，下次播报就变。
     */
    fun applyVoice(voiceName: String) {
        if (voiceName.isBlank()) return
        system?.applyVoice(voiceName)
    }

    /**
     * 打断播报。引擎会把被打断的那条按"播完"回调，由各自的 [Turn] 释放闸门和焦点。
     */
    fun stop() {
        synchronized(navLock) { pendingNormal = null }
        system?.stop()
        minimax?.stop()
        isSpeaking = activeTurns.get() > 0
        runCatching { VoiceState.write(appContext, VoiceState.IDLE) }
    }

    fun release() {
        system?.release()
        minimax?.release()
        synchronized(this) {
            system = null
            minimax = null
        }
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
