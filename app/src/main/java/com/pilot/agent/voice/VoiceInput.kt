package com.pilot.agent.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pilot.agent.Dbg
import com.pilot.navigation.VoiceState
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
    onState: (State) -> Unit = {},
    onText: (String) -> Unit = {},
    onError: (String) -> Unit = {},
    onWake: (String) -> Unit = {},
    onVoiceprint: (Float, Boolean) -> Unit = { _, _ -> },
) {

    /**
     * 回调可变。
     *
     * ★ 为什么不是构造参数定死：
     *   VoiceInput 现在是**进程级单例**（AgentRuntime 持有），
     *   因为导航界面也要能触发语音（`TriggerActivity` 收 start_voice）。
     *   而 UI 层的 ViewModel 会随 Activity 重建换新的，
     *   所以每次 attach 时把回调换掉，单例本身和麦克风状态不用重建。
     */
    var onState: (State) -> Unit = onState
    var onText: (String) -> Unit = onText
    var onError: (String) -> Unit = onError
    var onWake: (String) -> Unit = onWake
    var onVoiceprint: (Float, Boolean) -> Unit = onVoiceprint

    /**
     * 语音打断时回调（播报中听到「停一下」这类）。
     * AgentRuntime 注入成 `speaker.stop()`——VoiceInput 不直接依赖播报器。
     */
    var onInterrupt: (String) -> Unit = {}

    enum class State {
        IDLE,
        /** 休眠：只跑唤醒词，省电 */
        ASLEEP,
        /** 待命：等唤醒词（启动后默认） */
        WAKE_WAIT,
        /** 活跃期：对话结束后的一段时间内，不用喊唤醒词，直接说话即可 */
        ACTIVE,
        LISTENING,
        RECOGNIZING,
        UNAVAILABLE,
    }

    private enum class Mode { OFF, WAKE, ACTIVE, LISTEN }

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

    /** 活跃期截止时间（uptimeMillis），到点自动休眠。0 表示不计时 */
    @Volatile
    private var activeUntil: Long = 0L

    private val sleepTick = Runnable {
        if (mode == Mode.ACTIVE && SystemClock.uptimeMillis() >= activeUntil) {
            Dbg.i(TAG, "活跃期结束，进入休眠（仍可唤醒）")
            mode = Mode.WAKE
            wake.reset()
            vad?.reset()
            emitState(State.ASLEEP)
        }
    }

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
        val st = AgentSettings.load()
        val words = st.wakeWordList()
        if (words.isEmpty()) {
            onError("唤醒词为空")
            return false
        }
        // ★ 打断词和唤醒词一起喂给 KWS：KWS 常驻在跑，顺带就能听见「停一下」，
        //   不用另开一条识别链路（省算力，也避免两条链路抢麦克风）。
        val allWords = if (st.interruptEnabled) (words + st.interruptWordList()).distinct() else words
        if (!wake.load(allWords)) {
            onError(wake.lastError ?: "唤醒词加载失败")
            return false
        }
        if (!ensureCapture()) return false
        mode = Mode.WAKE
        resumeToWake = true
        ring.clear()
        emitState(State.WAKE_WAIT)
        Dbg.i(TAG, "进入待命，KWS 词表=$allWords")
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
        // 通知导航界面「正在录音」（麦克风图标动起来）
        runCatching { VoiceState.write(context, VoiceState.RECORDING) }
    }

    /** 提前结束这一句（用户点了"停"） */
    fun finishListening() {
        vad?.flush()
    }

    fun stop() {
        main.removeCallbacks(sleepTick)
        activeUntil = 0L
        mode = Mode.OFF
        vad?.flush()
        vad = null
        capture?.stop()
        capture = null
        ring.clear()
        runCatching { VoiceState.write(context, VoiceState.IDLE) }
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
            minSilenceMs = { silenceThresholdMs },
            onSpeechStart = { Dbg.i(TAG, "VAD: 开口") },
            onSegment = { pcm ->
                if (enrolling) {
                    // 注册模式：句子攒样本，不走识别
                    enrollSamples += pcm
                    lastEnrollSampleAt = SystemClock.uptimeMillis()
                    main.removeCallbacks(enrollWatchdog)
                    main.postDelayed(enrollWatchdog, ENROLL_IDLE_TIMEOUT_MS)
                    val n = enrollSamples.size
                    Dbg.i(TAG, "注册样本 $n/$ENROLL_TARGET (${pcm.size * 1000 / AudioCapture.SAMPLE_RATE}ms)")
                    main.post {
                        onEnrollSegment(n, ENROLL_TARGET)
                        if (n >= ENROLL_TARGET) finishEnroll()
                    }
                } else recognize(pcm)
            },
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

    /**
     * 声纹注册模式：开一个采集会话，VAD 切出的每一句**不进 ASR**，
     * 直接攒进样本列表，攒够 [ENROLL_TARGET] 段自动完成。
     * UI 侧（注册引导弹窗）负责提示用户念口令。
     */
    @Volatile private var enrolling = false
    private var enrollResumeWake = false
    private val enrollSamples = ArrayList<ShortArray>()

    /** 注册完成回调（在音频线程触发，UI 侧自行切主线程）。 */
    var onEnrollSegment: (index: Int, total: Int) -> Unit = { _, _ -> }
    var onEnrollDone: (Boolean) -> Unit = {}

    /** 首句自动注册成功（没有模板时，第一句唤醒词直接认下主人）。 */
    var onEnrolled: () -> Unit = {}

    /**
     * 当前 VAD 的静音阈值（毫秒）。
     *
     * 普通指令 800ms 就切句；**声纹注册时用 2500ms**——
     * 注册口令「小派你好，小派小派，先停一下，等我说完你再说话」里有好几处逗号停顿，
     * 800ms 会把它切成一堆碎片，攒不满 3 段。
     */
    @Volatile
    private var silenceThresholdMs = 800

    /** 注册看门狗：多久没有新样本就判定用户放弃了（毫秒） */
    @Volatile
    private var lastEnrollSampleAt = 0L

    private val enrollWatchdog = Runnable {
        if (!enrolling) return@Runnable
        val idle = SystemClock.uptimeMillis() - lastEnrollSampleAt
        if (idle > ENROLL_IDLE_TIMEOUT_MS) {
            Dbg.w(TAG, "注册看门狗：${idle}ms 没有新样本，自动取消")
            cancelEnroll()
        }
    }

    fun startEnroll(): Boolean {
        if (!ensureCapture()) return false
        enrolling = true
        silenceThresholdMs = ENROLL_SILENCE_MS
        lastEnrollSampleAt = SystemClock.uptimeMillis()
        main.removeCallbacks(enrollWatchdog)
        main.postDelayed(enrollWatchdog, ENROLL_IDLE_TIMEOUT_MS)
        enrollSamples.clear()
        // 注册期间不走 VAD→ASR，但**注册结束要回到原来的待命状态**，
        // 否则麦克风被关掉、待命丢失，用户还得手动再点一次（实测"注册完就没反应了"）。
        enrollResumeWake = resumeToWake || mode == Mode.WAKE || mode == Mode.ACTIVE
        resumeToWake = false
        vad?.reset()
        mode = Mode.LISTEN
        emitState(State.LISTENING)
        Dbg.i(TAG, "声纹注册开始，目标 $ENROLL_TARGET 段")
        return true
    }

    fun cancelEnroll() {
        enrolling = false
        silenceThresholdMs = NORMAL_SILENCE_MS
        main.removeCallbacks(enrollWatchdog)
        enrollSamples.clear()
        resumeToWake = enrollResumeWake
        backToIdleOrWake()
    }

    private fun finishEnroll(): Boolean {
        enrolling = false
        silenceThresholdMs = NORMAL_SILENCE_MS
        main.removeCallbacks(enrollWatchdog)
        resumeToWake = enrollResumeWake
        val ok = enrollSamples.size >= ENROLL_TARGET
        if (ok) {
            // 三段拼起来一起提 embedding：比单句稳（对不同音量/距离更宽容）
            // ★ 限制长度：三段拼起来可能十几秒，WeSpeaker 推理在主线程上会卡到系统判 ANR。
            //   只取每段前 3 秒，总长压到 9 秒内。
            val cap = AudioCapture.SAMPLE_RATE * 3
            val pieces = enrollSamples.map { if (it.size > cap) it.copyOf(cap) else it }
            val total = pieces.sumOf { it.size }
            val merged = ShortArray(total)
            var off = 0
            for (s in pieces) { s.copyInto(merged, off); off += s.size }
            enrollSamples.clear()
            // 推理挪到后台线程；完成再回主线程通知 UI
            worker.execute {
                val saved = gate.enroll(merged)
                Dbg.i(TAG, "声纹注册 merge=${total * 1000 / AudioCapture.SAMPLE_RATE}ms saved=$saved")
                main.post { onEnrollDone(saved) }
                backToIdleOrWake()
            }
            return true
        }
        backToIdleOrWake()
        return ok
    }

    /** 唤醒提示音：一声短 beep，告诉用户"我在听"。
     *  ★ 必须在切换音频模式之后播——它会被麦克风听到，
     *    但只有 ~100ms，ASR 切出来是单字，isWakeEcho 已按单字丢弃（同 TTS 回声处理）。 */
    private fun playBeep(interrupt: Boolean = false) {
        if (!interrupt && !AgentSettings.load().wakeTone) return
        runCatching {
            val tone = android.media.ToneGenerator(android.media.AudioManager.MODE_IN_COMMUNICATION, 70)
            // 打断用双短音（"bi-bi"），和唤醒的单短音区分开——用户一听就知道"它停了"
            if (interrupt) {
                tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 90)
                main.postDelayed({
                    runCatching { tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 70) }
                }, 130)
            } else {
                tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
            }
            main.postDelayed({ runCatching { tone.release() } }, 500)
        }.onFailure { Dbg.w(TAG, "提示音失败 ${it.message}") }
    }

    /** 重置活跃窗口：对话结束后还有 [AgentSettings.idleSleepSeconds] 秒可以免唤醒说话。 */
    private fun resetActiveWindow() {
        val sec = AgentSettings.load().idleSleepSeconds
        if (sec <= 0) {
            activeUntil = 0L
            return
        }
        activeUntil = SystemClock.uptimeMillis() + sec * 1000L
        main.removeCallbacks(sleepTick)
        main.postDelayed(sleepTick, sec * 1000L + 50)
    }

    private fun onFrame(frame: ShortArray, len: Int) {
        ring.push(frame, len)
        if (mode == Mode.OFF) return
        // ★ 声纹注册期间**只喂 VAD，不跑唤醒词检测**。
        //   注册口令里就含唤醒词（"我是小派的主人…"），KWS 会一直命中，
        //   而聆听状态下命中会 vad.reset()——每句都被掐掉，进度永远 0/3（实测）。
        if (enrolling) {
            vad?.feed(frame, len)
            return
        }
        // ★ 其余模式都跑唤醒词检测：
        //   播报中要能听见「停一下」打断，聆听中要能听见唤醒词重新开始。
        val hit = wake.feed(frame, len)
        if (hit != null) handleHit(hit)
        if (mode == Mode.LISTEN || mode == Mode.ACTIVE) vad?.feed(frame, len)
    }

    /**
     * KWS 命中分流：打断词 / 播报中打断 / 唤醒。
     *
     * 判据顺序很关键——**先在"正在播报"时判断能不能打断**，
     * 否则播报中说「停一下」会被当成普通唤醒，结果只是"重新开始听"，
     * 播报还在继续，用户会以为没反应。
     */
    private fun handleHit(word: String) {
        if (enrolling) {
            Dbg.i(TAG, "注册中，忽略唤醒/打断命中「$word」")
            return
        }
        val st = AgentSettings.load()
        val isInterruptWord = st.interruptWordList().any { word.contains(it) || it.contains(word) }
        val speaking = AudioGate.isMuted

        if (st.interruptEnabled && speaking && (isInterruptWord || word.isNotEmpty())) {
            tryInterrupt(word, isInterruptWord)
            return
        }
        if (isInterruptWord && !speaking) {
            // 没在播报时说「停一下」→ 结束这次对话，回待命
            Dbg.i(TAG, "收到打断词「$word」（未播报），结束本次对话")
            runCatching { onInterrupt(word) }
            backToIdleOrWake()
            return
        }
        if (mode == Mode.LISTEN || state == State.RECOGNIZING) {
            // 正在听指令又喊唤醒词：当作重新开始，丢掉半句
            Dbg.i(TAG, "聆听中命中「$word」，重置这一句")
            vad?.reset()
            return
        }
        onWakeHit(word)
    }

    /**
     * 播报中尝试打断。
     *
     * 条件按老板定的：**声纹符合 + 唤醒词或打断词**。
     * 声纹校验用的是刚录到的那段音频（含播报回声，所以阈值放宽一半；
     * 没注册模板就直接放行——「宁可放过，不可错杀」，不然用户永远打断不了）。
     */
    private fun tryInterrupt(word: String, isInterruptWord: Boolean) {
        val st = AgentSettings.load()
        val snapshot = ring.snapshot()
        val needVerify = st.requireVoiceprint && gate.hasTemplate()
        val proceed = {
            Dbg.i(TAG, "语音打断「$word」（打断词=$isInterruptWord）")
            runCatching { onInterrupt(word) }
            playBeep(interrupt = true)
            // 立刻进入聆听，接着说的话就是新指令
            mode = Mode.LISTEN
            vad?.reset()
            wakeAuthorized = true
            emitState(State.LISTENING)
            resetActiveWindow()
        }
        if (!needVerify) {
            proceed()
            return
        }
        worker.execute {
            val score = gate.verify(snapshot)
            if (score == null) {
                Dbg.w(TAG, "打断声纹不可用（${gate.lastError}），放行")
                main.post { proceed() }
                return@execute
            }
            // 播报中的录音混着扬声器声音，阈值放宽一半
            val pass = score >= st.voiceprintThreshold * 0.5f
            Dbg.i(TAG, "打断声纹 %.2f 阈值 %.2f → %s".format(score, st.voiceprintThreshold * 0.5f, if (pass) "通过" else "拒绝"))
            if (pass) {
                main.post { proceed() }
            } else {
                main.post { onVoiceprint(score, false) }
            }
        }
    }

    private fun onWakeHit(word: String) {
        // 立刻切聆听：用户接下来的话不能丢
        mode = Mode.LISTEN
        vad?.reset()
        wakeAuthorized = true
        emitState(State.LISTENING)
        main.post { onWake(word) }
        playBeep()
        Dbg.i(TAG, "唤醒「$word」，进入聆听")

        val s = AgentSettings.load()
        // ★ 还没有模板 → 这句唤醒词就是第一句，直接拿它注册。
        //   原来的注册流程要念 3 遍固定口令，太重，而且口令必须和唤醒词一致
        //   用户很难记住——改成"第一次说话就认下主人"，门槛降到零。
        if (!gate.hasTemplate()) {
            // 唤醒词那句直接注册（比后面那句干净，没有指令混在里面）
            val snapshot0 = ring.snapshot()
            worker.execute {
                val ok = gate.enrollIfAbsent(snapshot0)
                if (ok) {
                    Dbg.i(TAG, "首句注册成功（用唤醒词注册的），已认下主人")
                    main.post { onEnrolled() }
                }
            }
            return
        }
        if (!s.requireVoiceprint) return
        // ★ 声纹取的是环形缓冲里最近 1.5 秒——但 KWS 命中时"小派"可能已经说完了，
        //   窗口里剩下的可能是后面的静默/杂音，判出来的分数没有意义（实测 0.20）。
        //   所以把窗口临时拉长到 3 秒再取，覆盖整个唤醒词。
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
        // ★ 放音期间（音乐/TTS）麦克风录到的是音频输出，不是人声——
        //   送进 ASR 会把歌词误听成「你说完了」这类话，然后 agent 当成指令播报，
        //   音乐就被打断了（11-10 真机反馈）。这里直接丢弃，不浪费 ASR 算力。
        if (AudioGate.isMuted) {
            Dbg.i(TAG, "放音中，丢弃本段音频（${pcm.size * 1000 / AudioCapture.SAMPLE_RATE}ms）")
            backToIdleOrWake()
            return
        }
        // ★ 放音刚结束的尾音也要丢：VAD 缓冲里那段会被识别成播放内容本身
        //   （「音量已经是最大百分之一百」），然后被当成新指令执行，形成自问自答。
        val sinceSpeak = SystemClock.uptimeMillis() - AudioGate.lastReleaseAt
        if (AudioGate.lastReleaseAt > 0L && sinceSpeak < ECHO_GUARD_MS) {
            Dbg.i(TAG, "放音刚结束 ${sinceSpeak}ms，丢弃尾音（${pcm.size * 1000 / AudioCapture.SAMPLE_RATE}ms）")
            backToIdleOrWake()
            return
        }
        if (!busy.compareAndSet(false, true)) return
        emitState(State.RECOGNIZING)
        worker.execute {
            try {
                val ms = pcm.size * 1000 / AudioCapture.SAMPLE_RATE
                Dbg.i(TAG, "识别 ${ms}ms 音频")
                val text = asr.transcribe(pcm)
                val authorized = wakeAuthorized
                // ★ 首句注册：还没有模板时，**任何一句被识别的话**都拿来注册。
                //   之前挂在"唤醒词命中"上，用户不喊唤醒词直接说话就不会注册（实测踩到）。
                if (authorized && text != null && !gate.hasTemplate() && isRegisterable(text)) {
                    val ok = gate.enrollIfAbsent(pcm)
                    if (ok) {
                        Dbg.i(TAG, "首句注册成功，已认下主人")
                        main.post { onEnrolled() }
                    }
                }
                // ★ 把识别结果写给导航界面（搜索框自动填入）
                if (authorized && text != null) {
                    runCatching { VoiceState.write(context, VoiceState.IDLE, text) }
                }
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
     * 这句话能不能当声纹模板。
     *
     * 排除两类：
     * - **问句**（「注册了吗」「我的声纹好了没」）——那是来问状态的，不是自报身份；
     * - **太短的应答**（「嗯」「啊」「好」）——1 秒多一点的咳嗽式应答，
     *   算出来的 embedding 没有代表性。
     */
    private fun isRegisterable(text: String): Boolean {
        val t = text.trim()
        if (t.length < 4) {
            Dbg.i(TAG, "首句注册：文本太短「$t」，不注册")
            return false
        }
        if (t.contains('?') || t.contains('？') ||
            Regex("(吗|呢|没有|好了吗|注册了吗|是不是|怎么样|多少|为什么|什么)").containsMatchIn(t)
        ) {
            Dbg.i(TAG, "首句注册：像是提问「$t」，不注册")
            return false
        }
        return true
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
            val sec = AgentSettings.load().idleSleepSeconds
            if (sec > 0) {
                // 活跃期：不用再喊唤醒词，直接说话；超时自动休眠
                mode = Mode.ACTIVE
                wake.reset()
                vad?.reset()
                resetActiveWindow()
                emitState(State.ACTIVE)
                Dbg.i(TAG, "进入活跃期 ${sec}s")
            } else {
                mode = Mode.WAKE
                wake.reset()
                ring.clear()
                emitState(State.WAKE_WAIT)
            }
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

    // ------------------------------------------------------------ 自检钩子

    /** 自检：模拟 KWS 命中（不用真说话就能测打断/唤醒链路）。 */
    fun debugHit(word: String) {
        Dbg.i(TAG, "自检：模拟命中「$word」")
        handleHit(word)
    }

    /** 自检：模拟"这段对话结束"，看活跃期与休眠计时。 */
    fun debugEndTurn() {
        Dbg.i(TAG, "自检：模拟对话结束")
        backToIdleOrWake()
    }

    /** 自检：报告当前状态（模式 + 活跃期剩余）。 */
    fun debugStatus(): String {
        val left = if (activeUntil > 0L) ((activeUntil - SystemClock.uptimeMillis()) / 1000L) else -1L
        return "state=$state mode=$mode 活跃期剩余=${left}s"
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

        /** 普通指令的断句静音阈值 */
        const val NORMAL_SILENCE_MS = 800

        /** 声纹注册的断句静音阈值（口令里有逗号停顿，要切得慢） */
        const val ENROLL_SILENCE_MS = 2500

        /**
         * 注册时多久没有新样本就自动取消（用户放弃了）。
         *
         * 45 秒：一句口令约 3~5 秒，用户念 3 遍中间可能停顿思考，
         * 20 秒太短会把还在进行的注册判成"放弃"（实测第 3 段被吃掉）。
         */
        const val ENROLL_IDLE_TIMEOUT_MS = 45_000L

        /** 放音结束后多久内录到的音频一律当回声丢掉 */
        const val ECHO_GUARD_MS = 900L

        /** 声纹注册要采够的语句段数 */
        const val ENROLL_TARGET = 3
    }
}
