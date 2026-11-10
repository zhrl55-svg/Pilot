package com.pilot.agent.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.pilot.agent.Dbg
import com.pilot.agent.agent.AgentSettings
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音输入：录 → 唤醒/断句 → 端侧 ASR → 文本。
 *
 * 三种模式共用一个 AudioRecord（一个麦克风只能有一个）：
 * ```
 * OFF     不录音
 * WAKE    常驻听唤醒词（KWS），命中后自动切 LISTEN
 * LISTEN  听一整句（VAD 断句），识别完回到上一个模式
 * ```
 *
 * ## 声纹的策略：乐观放行 + 事后否决
 * 唤醒词命中的瞬间用户已经开始说指令了，不可能等他 100ms 验完声纹才放行。
 * 所以先放行进入聆听，同时后台用**唤醒词那 1.5 秒音频**验声纹；
 * 验不过就在识别结果出来时丢弃并提示。响应速度不受影响，误判成本也低。
 */
class VoiceInput(
    private val context: Context,
    private val asr: LocalAsr,
    private val wake: WakeWordDetector,
    private val gate: VoiceprintGate,
    private val onState: (State) -> Unit = {},
    private val onText: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val onWake: (String) -> Unit = {},
    private val onVoiceprint: (Float, Boolean) -> Unit = { _, _ -> },
) {

    enum class State { IDLE, WAKE_WAIT, LISTENING, RECOGNIZING, UNAVAILABLE }

    private enum class Mode { OFF, WAKE, LISTEN }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-worker").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)

    private var capture: AudioCapture? = null
    private var vad: EnergyVad? = null

    @Volatile
    private var mode = Mode.OFF

    /** 这次唤醒是否通过声纹（乐观置 true，验不过再置 false） */
    @Volatile
    private var wakeAuthorized = true

    /** 识别完要不要回到待命 */
    @Volatile
    private var resumeToWake = false

    @Volatile
    var state: State = State.IDLE
        private set

    private val ring: RecentAudio by lazy {
        RecentAudio(AgentSettings.load().voiceprintWindowMs)
    }

    val isListening: Boolean get() = capture?.isRunning == true

    val isWakeMode: Boolean get() = mode == Mode.WAKE

    fun warmUp() {
        if (!asr.abiSupported()) return
        worker.execute { asr.ensureReady() }
    }

    // ------------------------------------------------------------ 启动 / 停止

    /** 待命：常驻听唤醒词 */
    fun startWake(): Boolean {
        val words = AgentSettings.load().wakeWordList()
        if (words.isEmpty()) {
            onError("唤醒词为空")
            return false
        }
        if (!wake.load(words)) {
            onError(wake.lastError ?: "唤醒词加载失败")
            return false
        }
        if (!ensureCapture()) return false
        mode = Mode.WAKE
        resumeToWake = true
        ring.clear()
        emitState(State.WAKE_WAIT)
        Dbg.i(TAG, "进入待命，唤醒词=$words")
        return true
    }

    /** 直接听一整句（点按钮） */
    fun startListening(resumeWake: Boolean = false) {
        if (!ensureCapture()) return
        resumeToWake = resumeWake
        wakeAuthorized = true
        vad?.reset()
        mode = Mode.LISTEN
        emitState(State.LISTENING)
    }

    /** 提前结束这一句（用户点了"停"） */
    fun finishListening() {
        vad?.flush()
    }

    fun stop() {
        mode = Mode.OFF
        vad?.flush()
        vad = null
        capture?.stop()
        capture = null
        ring.clear()
        if (!busy.get()) emitState(State.IDLE)
        Dbg.i(TAG, "语音停止")
    }

    private fun ensureCapture(): Boolean {
        if (isListening) return true
        if (!asr.abiSupported()) {
            emitState(State.UNAVAILABLE)
            onError("这台设备的 CPU 架构不支持端侧语音，需要 arm64 真机")
            return false
        }
        val v = EnergyVad(
            onSpeechStart = { Dbg.i(TAG, "VAD: 开口") },
            onSegment = { pcm -> recognize(pcm) },
        )
        vad = v
        val c = AudioCapture(
            onFrame = { frame, len -> onFrame(frame, len) },
            onError = { msg -> main.post { onError(msg) } },
        )
        capture = c
        if (!c.start()) {
            capture = null
            vad = null
            emitState(State.IDLE)
            return false
        }
        return true
    }

    // ------------------------------------------------------------ 音频路由

    private fun onFrame(frame: ShortArray, len: Int) {
        ring.push(frame, len)
        when (mode) {
            Mode.WAKE -> {
                val hit = wake.feed(frame, len)
                if (hit != null) onWakeHit(hit)
            }

            Mode.LISTEN -> vad?.feed(frame, len)
            Mode.OFF -> Unit
        }
    }

    private fun onWakeHit(word: String) {
        // 立刻切聆听：用户接下来的话不能丢
        mode = Mode.LISTEN
        vad?.reset()
        wakeAuthorized = true
        emitState(State.LISTENING)
        main.post { onWake(word) }
        Dbg.i(TAG, "唤醒「$word」，进入聆听")

        val s = AgentSettings.load()
        if (!s.requireVoiceprint) return
        if (!gate.hasTemplate()) {
            Dbg.i(TAG, "未注册声纹，跳过校验")
            return
        }
        val snapshot = ring.snapshot()
        worker.execute {
            val score = gate.verify(snapshot)
            if (score == null) {
                Dbg.w(TAG, "声纹校验不可用（${gate.lastError}），放行")
                return@execute
            }
            val pass = score >= s.voiceprintThreshold
            Dbg.i(
                TAG,
                "声纹 ${"%.2f".format(score)} 阈值 ${s.voiceprintThreshold} → ${if (pass) "通过" else "拒绝"}",
            )
            main.post { onVoiceprint(score, pass) }
            if (!pass) wakeAuthorized = false
        }
    }

    private fun recognize(pcm: ShortArray) {
        if (!busy.compareAndSet(false, true)) return
        emitState(State.RECOGNIZING)
        worker.execute {
            try {
                val ms = pcm.size * 1000 / AudioCapture.SAMPLE_RATE
                Dbg.i(TAG, "识别 ${ms}ms 音频")
                val text = asr.transcribe(pcm)
                val authorized = wakeAuthorized
                main.post {
                    when {
                        !authorized -> onError("声纹不匹配，已忽略这句话")
                        text == null -> onError(asr.lastError ?: "端侧识别不可用")
                        text.isBlank() -> Dbg.i(TAG, "识别结果为空")
                        isWakeEcho(text) -> Dbg.i(TAG, "丢弃唤醒词残留：$text")
                        else -> onText(text)
                    }
                }
            } catch (t: Throwable) {
                Dbg.e(TAG, "识别异常", t)
                main.post { onError(t.message ?: "识别异常") }
            } finally {
                busy.set(false)
                backToIdleOrWake()
            }
        }
    }

    /**
     * 唤醒词残留判定。
     *
     * 唤醒后 VAD 会把「小派」这两个字再切出来送一遍 ASR，实测识别成「拍」。
     * 不挡掉的话，agent 会对这个单字走一轮对话，白白浪费时间还显得很蠢。
     *
     * 判据刻意保守：只挡单字、包含完整唤醒词的、以及纯唤醒词字符组成的两字词。
     * 「返回」这种两字真指令必须放行。
     */
    private fun isWakeEcho(text: String): Boolean {
        val t = text.trim()
        if (t.length <= 1) return true
        val words = AgentSettings.load().wakeWordList()
        if (words.any { t.contains(it) }) return true
        val chars = words.joinToString("").toSet()
        return t.length <= 2 && t.all { it in chars }
    }

    private fun backToIdleOrWake() {
        if (resumeToWake) {
            mode = Mode.WAKE
            wake.reset()
            ring.clear()
            emitState(State.WAKE_WAIT)
        } else {
            mode = Mode.OFF
            capture?.stop()
            capture = null
            vad = null
            emitState(State.IDLE)
        }
    }

    /** 注册声纹：用缓冲里最近一段（用户刚说完唤醒词，正好） */
    fun enrollVoiceprint(): Boolean {
        val pcm = ring.snapshot()
        val ms = pcm.size * 1000 / AudioCapture.SAMPLE_RATE
        if (ms < 800) {
            onError("录音太短（${ms}ms），说一句完整的话再注册")
            return false
        }
        return gate.enroll(pcm)
    }

    fun release() {
        stop()
        worker.shutdownNow()
    }

    private fun emitState(s: State) {
        state = s
        main.post { onState(s) }
    }

    private companion object {
        const val TAG = "PilotVoice"
    }
}
