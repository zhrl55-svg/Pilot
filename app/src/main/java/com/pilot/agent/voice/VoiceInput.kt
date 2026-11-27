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

    /**
     * 麦克风故障状态回调（审核 V-01）：非 null = 采集中断（被抢麦 / 来电 / 音频服务重启），
     * 正在按退避自动重试；null = 已恢复。在主线程回调。
     *
     * 与 [onError] 的区别：onError 是一次性的错误文案，这个是可持续展示的状态，
     * 界面/悬浮窗应据此显示"麦克风不可用"，而不是继续显示"待命"。
     */
    var onMicStatus: (String?) -> Unit = {}

    /** 当前麦克风故障描述；null = 正常。 */
    @Volatile
    var micError: String? = null
        private set

    /**
     * 最近一句被识别的话的说话人校验结论（审核 S6）。
     *
     * 在主线程、**紧接着 onText 之前**写入，所以在 onText 回调里读到的就是这一句的结论。
     * 路由层据此决定敏感能力：读通知/个人数据请用 [Speaker.speakPersonal]（会同时检查锁屏），
     * 外发、拨号、终端、资金类在非 [SpeakerAuth.VERIFIED] 时应强制界面确认。
     */
    @Volatile
    var lastSpeakerAuth: SpeakerAuth = SpeakerAuth.NOT_ENROLLED
        private set

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

    /** 每次新建 AudioCapture 自增；用来丢弃旧采集实例迟到的错误/恢复回调。 */
    @Volatile
    private var captureGen = 0

    /** 唤醒词检测前的能量预门控（只在采集线程使用，审核 V-08） */
    private val kwsGate = KwsEnergyGate(onClose = { wake.reset() })

    /** KWS 自动重载（失败后按退避恢复，审核 V-06） */
    private val kwsReloading = AtomicBoolean(false)

    @Volatile
    private var lastKwsReloadCheck = 0L

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

    /** 这次 LISTEN 是什么时候开始的（看门狗算真实时长用） */
    @Volatile
    private var listenStartedAt: Long = 0L

    /**
     * 连续对话的聆听窗口是否已经向 UI 公布（state = LISTENING）。
     *
     * ★ 和 mode 分开：唤醒命中到声纹出结果之间，mode 已经是 LISTEN（要收用户的话），
     *   但**声纹还没过**，按老板的规则这时候不算聆听态——输入框不能亮。
     */
    @Volatile
    private var convListen = false

    /** 本次聆听窗口的起点（uptimeMillis） */
    @Volatile
    private var convListenSince = 0L

    /** 本窗口内最后一次「通过声纹」的语音（0 = 还没听到过） */
    @Volatile
    private var lastVerifiedAt = 0L

    /** 最后一次 VAD 判定开口的时刻（算"说完话多久"用） */
    @Volatile
    private var lastSpeechAt = 0L

    /**
     * 5 秒没人接话时是直接回休眠（[true]，仅用于"喊了唤醒词却没人说话"的误唤醒），
     * 还是回免唤醒活跃期（[false]，播报结束/打断后的连续对话）。
     */
    @Volatile
    private var convSleepIfSilent = false

    /**
     * 这次休眠是不是用户主动要求的（说了"你休息一会"）。
     *
     * 自动休眠是常态，不用提示；主动免打扰要告诉用户"怎么叫醒我"。
     */
    @Volatile
    var dndRequested: Boolean = false
        private set

    private val sleepTick = Runnable {
        if (mode == Mode.ACTIVE && SystemClock.uptimeMillis() >= activeUntil) {
            Dbg.i(TAG, "活跃期结束，进入休眠（仍可唤醒）")
            mode = Mode.WAKE
            wake.reset()
            vad?.reset()
            dndRequested = false   // 自动休眠，不是用户主动要的免打扰
            emitState(State.ASLEEP)
        }
    }

    private val ring: RecentAudio by lazy {
        // ★ 至少保留 3 秒：唤醒词命中时"小派"已经说完，窗口里只剩尾音+车内噪声，
        //   1.5 秒的片段实测声纹只有 0.18~0.26。这里真正兑现"拉长到 3 秒"。
        RecentAudio(maxOf(AgentSettings.load().voiceprintWindowMs, WAKE_VERIFY_WINDOW_MS))
    }

    val isListening: Boolean get() = capture?.isRunning == true

    val isWakeMode: Boolean get() = mode == Mode.WAKE

    /** Wake listening remains enabled while a just-woken conversation is active. */
    val isWakeEnabled: Boolean get() = resumeToWake && mode != Mode.OFF

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
        // ★ 再加上导航候选的序号词：播报候选时麦克风被静音，ASR 听不到，
        //   只能靠常驻 KWS 听见「第二个」来打断播报、直接选第 2 条。
        val extra = if (st.interruptEnabled) st.interruptWordList() else emptyList()
        val allWords = (words + extra + NAV_ORDINAL_WORDS).distinct()
        if (!wake.load(allWords)) {
            onError(wake.lastError ?: "唤醒词加载失败")
            return false
        }
        if (!ensureCapture()) return false
        mode = Mode.WAKE
        resumeToWake = true
        dndRequested = false
        ring.clear()
        emitState(State.WAKE_WAIT)
        Dbg.i(TAG, "进入待命，KWS 词表=$allWords")
        return true
    }

    /**
     * 进入休眠（用户主动版的"免打扰"）。
     *
     * ★ 老板的判断：这就是"等待一段时间没语音自动休眠"的同一个逻辑，
     *   只是触发方式不同——一个是超时自动睡，一个是用户说
     *   「你休息一会」「我先忙一会」「别打扰我」主动让它歇着。
     *   所以直接复用休眠状态（ASLEEP），不另造一个 DND 模式。
     *
     * 行为：不再聆听、不再识别指令，只保留唤醒词检测。
     * 退出方式：再喊一次唤醒词，或长按输入框。
     */
    fun enterDoNotDisturb() {
        main.removeCallbacks(sleepTick)
        main.removeCallbacks(conversationTick)
        main.removeCallbacks(convEnterRetry)
        convListen = false
        activeUntil = 0L
        mode = Mode.WAKE          // 只跑唤醒词，和休眠完全一样
        wake.reset()
        wakeAuthorized = false
        dndRequested = true
        vad?.reset()
        vad?.flush()
        emitState(State.ASLEEP)
        Dbg.i(TAG, "进入免打扰（休眠），等唤醒词或长按输入框恢复")
    }

    /** 自检：模拟识别到一句话（走 onText 链路）。 */
    fun debugSay(text: String) {
        main.post { onText(text) }
    }

    /** 直接听一整句（点按钮） */
    fun startListening(resumeWake: Boolean = false) {
        if (!ensureCapture()) return
        if (capture?.isRecovering == true) {
            // 采集会话还在但麦克风正被占用、重试中：别假装在听
            onError("麦克风暂时不可用（可能被通话或其他应用占用），正在自动重试，请稍后再试")
            return
        }
        if ("music" in AudioGate.activeHolders()) {
            AudioFocusManager.pauseMusicForVoice()
        }
        resumeToWake = resumeWake
        dndRequested = false
        wakeAuthorized = true
        vad?.reset()
        mode = Mode.LISTEN
        emitState(State.LISTENING)
        armListenWatchdog()
        // 通知导航界面「正在录音」（麦克风图标动起来）
        runCatching { VoiceState.write(context, VoiceState.RECORDING) }
    }

    /**
     * 「正在听」超时兜底。
     *
     * 点按钮进入 LISTEN 后，如果麦克风一直被放音闸门挡住（TTS 还在播）、
     * 或者 AudioRecord 悄悄挂了，VAD 永远切不出一句话，mode 就会一直停在
     * LISTEN——界面表现为麦克风/悬浮球一直「正在聆听」，再说也没反应。
     * 20 秒还没有结果就强制收状态。
     *
     * ★ 唤醒进入的 LISTEN 也必须走这里（原来只有 startListening 才 arm）：
     *   误唤醒后用户一句话没说，VAD 永远切不出句子，就永远没人收回状态
     *   ——界面一直「正在聆听」、也不休眠（11-12 真机日志：23:32:19 唤醒后
     *   停在 LISTEN 直到日志结束，中间没有任何"进入活跃期/休眠"）。
     */
    private val listenWatchdog: Runnable = Runnable {
        if (mode != Mode.LISTEN) return@Runnable
        // 声纹注册有自己的超时（enrollWatchdog），别抢它的会话
        if (enrolling) return@Runnable

        val elapsed = SystemClock.uptimeMillis() - listenStartedAt
        // ★ 硬上限：**不看** VAD 怎么说，到点必收。
        //   原来只看"没在说话"才超时，可环境噪声（路噪/风扇）会让 VAD 长期
        //   inSpeech=true，看门狗每 5 秒无条件续期 → 永不超时、永不休眠。
        if (elapsed >= LISTEN_HARD_CAP_MS) {
            Dbg.w(TAG, "聆听硬上限（${elapsed}ms）到了，强制回待命")
            vad?.reset()
            runCatching { VoiceState.write(context, VoiceState.IDLE) }
            // ★ skipActive：整段聆听**一句话都没说**，说明多半是误唤醒，
            //   不该再给"免唤醒活跃期"，直接回只等唤醒词的休眠态。
            backToIdleOrWake(skipActive = true)
            return@Runnable
        }
        if (busy.get() || vad?.isInSpeech == true) {
            main.postDelayed(listenWatchdog, 5_000L)
            return@Runnable
        }
        Dbg.w(TAG, "聆听 ${elapsed}ms 没识别到一句话，自动回待命（不进活跃期）")
        runCatching { VoiceState.write(context, VoiceState.IDLE) }
        backToIdleOrWake(skipActive = true)
    }

    private fun armListenWatchdog() {
        listenStartedAt = SystemClock.uptimeMillis()
        main.removeCallbacks(listenWatchdog)
        main.postDelayed(listenWatchdog, LISTEN_TIMEOUT_MS)
    }

    // ------------------------------------------------------------ 聆听态窗口

    /**
     * 播报结束后自动进入聆听态（免唤醒连续对话）。
     *
     * 触发点：Speaker 一条播报播完（AgentRuntime 注入 [Speaker.onSpeechDone]），
     * 由这里统一判断该不该听——VoiceInput 状态可能已经因为停止/免打扰变了。
     */
    fun enterConversationListening(reason: String = "播报结束") {
        main.post {
            convEnterAttempts = 0
            tryEnterConversationListening(reason)
        }
    }

    private val convEnterRetry = Runnable {
        tryEnterConversationListening("等待回声保护结束")
    }

    /** 回声保护期内重试几次（只在主线程读写）。放音一直占着闸门时不能无限重试。 */
    private var convEnterAttempts = 0

    private fun tryEnterConversationListening(reason: String) {
        if (!resumeToWake || dndRequested || enrolling) return
        if (mode == Mode.OFF) return
        // ★ 只从“待命/活跃期”进入：已经在聆听（含唤醒声纹校验中）、
        //   或者用户正长按录音时，别插一脚改状态。
        if (mode != Mode.ACTIVE && mode != Mode.WAKE) return
        if (busy.get() || state == State.RECOGNIZING) return
        // ★ 播报尾音还在回声保护窗口里时先不进：onFrame 在这段时间会把 VAD 清掉，
        //   进了也听不到人说话，还会把用户紧接着说的前半句吞掉。
        val now = SystemClock.uptimeMillis()
        val sinceRelease = if (AudioGate.lastReleaseAt > 0L) now - AudioGate.lastReleaseAt else Long.MAX_VALUE
        if (AudioGate.isMuted || sinceRelease < ECHO_GUARD_MS) {
            if (++convEnterAttempts > 4) {
                convEnterAttempts = 0
                Dbg.i(TAG, "播报结束后放音仍占用麦克风，这次不自动进入聆听")
                return
            }
            val wait = if (AudioGate.isMuted) ECHO_GUARD_MS else ECHO_GUARD_MS - sinceRelease
            main.removeCallbacks(convEnterRetry)
            main.postDelayed(convEnterRetry, wait + 50L)
            return
        }
        convEnterAttempts = 0
        mode = Mode.LISTEN
        vad?.reset()
        wakeAuthorized = true
        // 现在开始由聆听窗口接管，活跃期计时作废（否则它到点会把 mode 切回 WAKE）
        main.removeCallbacks(sleepTick)
        activeUntil = 0L
        armListenWatchdog()
        emitState(State.LISTENING)
        armConversationWindow()
        Dbg.i(TAG, "进入聆听态（$reason），${CONV_IDLE_MS}ms 没听到有效语音自动收起")
    }

    /** 开启聆听窗口计时：5 秒内没有通过声纹的话 → 收起。 */
    private fun armConversationWindow(sleepIfSilent: Boolean = false) {
        convListen = true
        convSleepIfSilent = sleepIfSilent
        convListenSince = SystemClock.uptimeMillis()
        lastVerifiedAt = 0L
        lastSpeechAt = convListenSince
        main.removeCallbacks(conversationTick)
        main.postDelayed(conversationTick, CONV_TICK_MS)
    }

    /**
     * 聆听窗口的自动收尾。
     *
     * - 连续 [CONV_IDLE_MS] 没有开口、也没有一句「通过声纹」的话 → 收起（误唤醒/没人接话）；
     * - 听到过之后，用户说完话超过 [CONV_AFTER_SPEECH_MS] 没有新的开口 → 收起。
     *
     * ★ 用户在说话时一律续期（[lastSpeechAt] 更新），否则半句话会被 5 秒一刀掐掉；
     *   车内路噪让 VAD 长期 inSpeech 的极端情况由 [LISTEN_HARD_CAP_MS] 兜底。
     */
    private val conversationTick: Runnable = Runnable {
        if (!convListen || mode != Mode.LISTEN) {
            convListen = false
            return@Runnable
        }
        val now = SystemClock.uptimeMillis()
        if (vad?.isInSpeech == true) lastSpeechAt = now
        // 识别中由识别流程自己收尾，别在这里抢
        if (busy.get() || state == State.RECOGNIZING) {
            main.postDelayed(conversationTick, CONV_TICK_MS)
            return@Runnable
        }
        // ★ 正在说话时不收：路噪把 VAD 长期判成开口的风险由 LISTEN_HARD_CAP_MS 兜底，
        //   但用户在说话却被 5 秒一刀切掉（16:50 真机日志：VAD 开口后 3 秒被掐）更难看。
        val anchor = if (lastVerifiedAt > 0L) {
            maxOf(lastVerifiedAt, lastSpeechAt)
        } else {
            maxOf(convListenSince, lastSpeechAt)
        }
        val limit = if (lastVerifiedAt > 0L) CONV_AFTER_SPEECH_MS else CONV_IDLE_MS
        if (now - anchor < limit) {
            main.postDelayed(conversationTick, CONV_TICK_MS)
            return@Runnable
        }
        Dbg.i(TAG, "聆听态 ${now - anchor}ms 没有新的有效语音，自动收起")
        convListen = false
        runCatching { VoiceState.write(context, VoiceState.IDLE) }
        // 一句话都没说且是"喊了唤醒词没人接" → 当误唤醒回休眠；
        // 其余（播报结束/打断）回活跃期，仍可免唤醒继续聊。
        backToIdleOrWake(skipActive = convSleepIfSilent && lastVerifiedAt == 0L)
    }

    /**
     * 唤醒后声纹不通过 → 若用户其实没说话，把这次聆听收回来。
     *
     * ★ 为什么要"延迟 + 有条件"：
     *   声纹是**乐观放行、事后否决**的——唤醒瞬间用户可能已经在说指令。
     *   验声纹只要 ~90ms，这时候立刻退出聆听会把那半句吞掉。
     *   所以先等一小会儿：如果 VAD 已经开口 / 正在识别，就交给原来的
     *   「结果出来再丢弃」路径；只有**一直没人说话**才收回状态。
     *
     * ★ 为什么必须有这一步：原来只在 `recognize()` 里丢结果，
     *   可用户被误唤醒后压根没说话 → 没有结果可丢 → 状态永远停在
     *   LISTENING，界面一直「正在聆听」也不休眠（11-12 真机日志：
     *   「声纹 0.21 → 拒绝」后面既没回待命也没进活跃期）。
     */
    private fun rejectWakeIfSilent() {
        if (enrolling) return
        // ★ 这里只看 mode：声纹没通过时 state 还停在原来的待命/活跃态，
        //   聆听态（state = LISTENING）本来就没有公布过——正是老板要的
        //   "声纹没过关就不存在聆听态"。
        if (mode != Mode.LISTEN) {
            Dbg.i(TAG, "声纹拒绝，但状态已离开聆听（mode=$mode state=$state），不收回")
            return
        }
        if (busy.get() || vad?.isInSpeech == true) {
            Dbg.i(TAG, "声纹拒绝，但用户已经开口，交给识别结果丢弃")
            return
        }
        Dbg.i(TAG, "声纹拒绝且没人说话，收回这次聆听")
        main.removeCallbacks(listenWatchdog)
        vad?.reset()
        // ★ skipActive=true：这次唤醒已判定不是主人，回只等唤醒词的休眠态，
        //   不给"免唤醒活跃期"（否则环境音会被当成对话继续喂 ASR）。
        backToIdleOrWake(skipActive = true)
    }

    /**
     * 提前结束这一句（用户松手 / 点了"停"）。
     *
     * ★ 原来只调 vad.flush()，而 flush() 在"根本没检测到说话"时
     *   `if (!inSpeech) return` 什么都不做 → 语音层永远停在 LISTENING，
     *   状态不回收，UI 的"正在听"就一直转（老板反馈）。
     *   现在：不管有没有声音，都保证回到待命/活跃。
     */
    private val finishGuard = Runnable {
        if (mode == Mode.LISTEN && !busy.get()) {
            Dbg.i(TAG, "松手但没检测到说话，直接回待命")
            backToIdleOrWake()
        }
    }

    fun finishListening() {
        main.removeCallbacks(finishGuard)
        vad?.flush()
        // 没说出话（或 flush 没触发 onSegment）时，主动收状态。
        // 如果 flush 真的切出了一句，recognize() 的 finally 会自己回收，
        // 这里只在"还停在 LISTEN 且没在识别"时才补一刀。
        main.postDelayed(finishGuard, 250)
    }

    fun stop() {
        main.removeCallbacks(sleepTick)
        main.removeCallbacks(listenWatchdog)
        main.removeCallbacks(conversationTick)
        main.removeCallbacks(convEnterRetry)
        convListen = false
        activeUntil = 0L
        // A recognition worker may still be finishing. Do not let its finally block
        // restore WAKE after the user explicitly stopped standby.
        resumeToWake = false
        dndRequested = false
        mode = Mode.OFF
        vad?.flush()
        vad = null
        capture?.stop()
        capture = null
        kwsGate.reset()
        ring.clear()
        if (micError != null) {
            micError = null
            runCatching { onMicStatus(null) }
        }
        runCatching { VoiceState.write(context, VoiceState.IDLE) }
        if (!busy.get()) emitState(State.IDLE)
        AudioFocusManager.resumeMusicAfterVoice()
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
                    // 注册模式：句子攒样本，不走识别。
                    // ★ 音频线程写、主线程清空/读取 → 加锁（审核 C3 / V-05）
                    val n = synchronized(enrollSamples) {
                        enrollSamples += pcm
                        enrollSamples.size
                    }
                    lastEnrollSampleAt = SystemClock.uptimeMillis()
                    main.removeCallbacks(enrollWatchdog)
                    main.postDelayed(enrollWatchdog, ENROLL_IDLE_TIMEOUT_MS)
                    Dbg.i(TAG, "注册样本 $n/$ENROLL_TARGET (${pcm.size * 1000 / AudioCapture.SAMPLE_RATE}ms)")
                    main.post {
                        onEnrollSegment(n, ENROLL_TARGET)
                        if (n >= ENROLL_TARGET) finishEnroll()
                    }
                } else recognize(pcm)
            },
        )
        vad = v
        val gen = ++captureGen
        val c = AudioCapture(
            onFrame = { frame, len -> onFrame(frame, len) },
            onError = { msg -> main.post { onCaptureError(gen, msg) } },
            onRecovered = { main.post { onCaptureRecovered(gen) } },
        )
        capture = c
        kwsGate.reset()
        if (!c.start()) {
            capture = null
            vad = null
            emitState(State.IDLE)
            return false
        }
        return true
    }

    /**
     * 采集故障（主线程）。
     *
     * ★ 以前这里直接 mode=OFF、stop 掉采集——等于放弃，之后唤醒永久失效。
     *   现在 AudioCapture 自己按退避重建 AudioRecord：
     *   - 待命/活跃期：保持 WAKE，等恢复（界面应按 [micError] 显示"麦克风不可用"）；
     *   - 正在听一句：这句作废；若本来要回待命就回 WAKE 继续等恢复，否则（单次点按）收掉采集；
     *   - 声纹注册中：取消注册并通知失败。
     */
    private fun onCaptureError(gen: Int, msg: String) {
        // 旧实例的迟到回调 / 启动失败（capture 已为 null，不会自动重试）：只报告，不动状态
        if (gen != captureGen || capture == null) {
            onError(msg)
            return
        }
        micError = msg
        runCatching { onMicStatus(msg) }
        onError("麦克风中断：$msg。正在自动重试，恢复后会继续待命。")
        if (enrolling) {
            Dbg.w(TAG, "注册中麦克风中断，取消注册：$msg")
            cancelEnroll()
            runCatching { onEnrollDone(false) }
            return
        }
        if (mode == Mode.LISTEN || state == State.RECOGNIZING || state == State.LISTENING) {
            Dbg.w(TAG, "聆听中麦克风中断，这句作废：$msg")
            main.removeCallbacks(listenWatchdog)
            vad?.reset()
            runCatching { VoiceState.write(context, VoiceState.IDLE) }
            if (resumeToWake) {
                backToIdleOrWake(skipActive = true)
            } else {
                mode = Mode.OFF
                capture?.stop()
                capture = null
                vad = null
                emitState(State.IDLE)
            }
        }
    }

    /** 采集自动恢复（主线程）。 */
    private fun onCaptureRecovered(gen: Int) {
        if (gen != captureGen) return
        Dbg.i(TAG, "麦克风已恢复，继续${if (mode == Mode.OFF) "（当前未在待命）" else "待命"}")
        micError = null
        runCatching { onMicStatus(null) }
        wake.reset()
        vad?.reset()
        vad?.resetNoiseFloor()
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

    /**
     * 历史回调：原"首句自动注册成功"。
     *
     * ★ 首句自动注册已删除（审核 S6 / V-07：第一个开口的人会成为主人）。
     *   保留属性只为兼容界面层的赋值，**现在不会再被调用**；注册结果请看 [onEnrollDone]。
     */
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
        synchronized(enrollSamples) { enrollSamples.clear() }
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
        synchronized(enrollSamples) { enrollSamples.clear() }
        resumeToWake = enrollResumeWake
        backToIdleOrWake()
    }

    private fun finishEnroll(): Boolean {
        // 可能被连续两段样本各 post 一次：只处理第一次
        if (!enrolling) return false
        enrolling = false
        silenceThresholdMs = NORMAL_SILENCE_MS
        main.removeCallbacks(enrollWatchdog)
        resumeToWake = enrollResumeWake
        // ★ 每句单独提 embedding 再求平均（gate.enrollMany），不再把三段音频拼成一条：
        //   拼接会跨过静音/换气/句间停顿，模型学到的是不连续特征；分句平均能把
        //   各句里随机的噪声方向抵消一部分，车内噪声下模板更稳。
        //   每段限 3 秒，避免推理时间过长卡到 ANR。
        val cap = AudioCapture.SAMPLE_RATE * 3
        val pieces = synchronized(enrollSamples) {
            val list = enrollSamples.map { if (it.size > cap) it.copyOf(cap) else it }
            enrollSamples.clear()
            list
        }
        val ok = pieces.size >= ENROLL_TARGET
        if (ok) {
            // 推理挪到后台线程；完成再回主线程通知 UI 并收状态（状态迁移统一在主线程）
            worker.execute {
                val saved = gate.enrollMany(pieces)
                Dbg.i(TAG, "声纹注册 ${pieces.size} 段平均 saved=$saved")
                main.post {
                    onEnrollDone(saved)
                    backToIdleOrWake()
                }
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
        // ★ 能量预门控（V-08）：安静帧不进 KWS 省电；放音中（要听打断词）和聆听中强制喂。
        val hit = kwsGate.process(
            frame,
            len,
            forceOpen = AudioGate.isMuted || mode == Mode.LISTEN,
        ) { f, n -> wake.feed(f, n) }
        if (hit != null) handleHit(hit)
        maybeReloadKws()

        // ★★ 放音期间**连 VAD 都不喂**，并且把已积累的段丢掉。
        //   原来只在 recognize() 入口拦 isMuted，但 VAD 一直在攒播报声，
        //   播报结束后整段被切出来 → ASR 把播报内容误识别成新指令 →
        //   又触发一轮播报，形成自激循环（日志里连着 6 轮"莫名被唤醒"）。
        //   注意：KWS 上面照常跑，播报中的语音打断不受影响。
        val echoGuard = AudioGate.lastReleaseAt > 0L &&
            SystemClock.uptimeMillis() - AudioGate.lastReleaseAt < ECHO_GUARD_MS
        if (AudioGate.isMuted || echoGuard) {
            vad?.reset()
            vad?.resetNoiseFloor()
        } else if (mode == Mode.LISTEN || mode == Mode.ACTIVE) {
            vad?.feed(frame, len)
        }
    }

    /** KWS 失败后按退避自动重载（加载模型较重，交给 worker；采集线程只做判断）。 */
    private fun maybeReloadKws() {
        if (!wake.needsReload) return
        val now = SystemClock.uptimeMillis()
        if (now - lastKwsReloadCheck < KWS_RELOAD_CHECK_MS) return
        lastKwsReloadCheck = now
        if (!kwsReloading.compareAndSet(false, true)) return
        val submitted = runCatching {
            worker.execute {
                try {
                    val ok = wake.reloadIfNeeded()
                    Dbg.i(TAG, "KWS 自动重载 ${if (ok) "成功" else "未成功：${wake.lastError}"}")
                } finally {
                    kwsReloading.set(false)
                }
            }
        }.isSuccess
        if (!submitted) kwsReloading.set(false)
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
        // ★ 导航搜索面板的「第N个」：播报中麦克风是静音的（防回声），
        //   只能靠 KWS 听见序号。命中后立刻停播，并把序号写成识别结果，
        //   导航面板轮询到就直接选第 N 条开始导航。
        val ordinal = ordinalOf(word)
        if (ordinal != null) {
            val count = runCatching { VoiceState.readNavSearch(context) }.getOrDefault(0)
            if (AudioGate.isMuted && ordinal <= count) {
                Dbg.i(TAG, "导航候选打断：$word（候选 $count 个）")
                runCatching { onInterrupt(word) }
                runCatching { VoiceState.write(context, VoiceState.IDLE, word) }
            } else if (logThrottled("ordinal:$word", 10_000L)) {
                // 序号词常驻词表（导航播报要能打断），无效命中很常见，日志限流避免刷屏
                Dbg.i(TAG, "序号词「$word」当前无效（播报中=${AudioGate.isMuted} 候选=$count），忽略")
            }
            return
        }
        val st = AgentSettings.load()
        val isInterruptWord = st.interruptWordList().any { word.contains(it) || it.contains(word) }
        val isWakeWord = st.wakeWordList().any { word.contains(it) || it.contains(word) }
        val speaking = AudioGate.isMuted

        // ★ 打断词（停一下 / 不说了 / 安静…）**只在播报中**才有意义。
        //   非播报时命中一律静默忽略：既不能唤醒、也不能调 backToIdleOrWake()
        //   ——那个调用会把 WAKE 切成 ACTIVE，活跃期免唤醒监听，
        //   于是环境音被当成对话，看起来就是"莫名被唤醒"（实测踩到）。
        if (isInterruptWord && !isWakeWord) {
            if (speaking && st.interruptEnabled) {
                tryInterrupt(word, isInterruptWord = true)
            } else if (logThrottled("interrupt:$word", 3_000L)) {
                Dbg.i(TAG, "打断词「$word」在非播报状态，忽略（不唤醒）")
            }
            return
        }
        // 唤醒词在播报中也能打断（老板定的：唤醒词 + 关键词都能打断）
        if (speaking && st.interruptEnabled) {
            tryInterrupt(word, isInterruptWord = false)
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

    /** 同一 key 在 [windowMs] 内只返回 true 一次，用于抑制重复命中日志刷屏。 */
    private val logStamps = HashMap<String, Long>()
    private fun logThrottled(key: String, windowMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = logStamps[key]
        if (last != null && now - last < windowMs) return false
        logStamps[key] = now
        // 词表有限，不会无限增长；顺手清掉过期项
        if (logStamps.size > 64) logStamps.entries.removeAll { now - it.value > 60_000L }
        return true
    }

    /** 「第二个」→ 2；不是序号词返回 null。 */
    private fun ordinalOf(word: String): Int? {
        val i = NAV_ORDINAL_WORDS.indexOfFirst { word.contains(it) }
        return if (i >= 0) i + 1 else null
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
        // 播报或音乐正在占用扬声器时，先让声音让路：既避免唤醒词和音乐
        // 混在一起影响声纹，也让验声纹通过后第一句指令能立即被麦克风收到。
        if ("music" in AudioGate.activeHolders()) {
            AudioFocusManager.pauseMusicForVoice()
        }
        val proceed = {
            Dbg.i(TAG, "语音打断「$word」（打断词=$isInterruptWord）")
            runCatching { onInterrupt(word) }
            playBeep(interrupt = true)
            // 立刻进入聆听，接着说的话就是新指令
            mode = Mode.LISTEN
            vad?.reset()
            wakeAuthorized = true
            armListenWatchdog()
            emitState(State.LISTENING)
            // 打断后同样是一个聆听窗口：接着说的就是新指令，没人接话就自动收起
            armConversationWindow()
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
            Dbg.i(TAG, "打断声纹 %.3f 阈值 %.3f → %s".format(score, st.voiceprintThreshold * 0.5f, if (pass) "通过" else "拒绝"))
            if (pass) {
                main.post { proceed() }
            } else {
                main.post {
                    onVoiceprint(score, false)
                    // 不是主人就继续播放，不能让一句误唤醒把音乐永久停住。
                    AudioFocusManager.resumeMusicAfterVoice()
                }
            }
        }
    }

    private fun onWakeHit(word: String) {
        if ("music" in AudioGate.activeHolders()) {
            AudioFocusManager.pauseMusicForVoice()
        }
        // ★ mode 先切 LISTEN：用户往往喊完唤醒词马上接指令，只有这个模式喂 VAD。
        //   但**先不公布聆听态**——声纹没过关就不存在聆听态（老板定的），
        //   输入框不能亮、更不能回「我在」。通过后再由 acknowledgeWake() 公布。
        mode = Mode.LISTEN
        dndRequested = false
        vad?.reset()
        wakeAuthorized = false
        // 兜底看门狗照常 arm（声纹结果迟迟不回、首句注册推理慢时也不至于卡在 LISTEN）
        armListenWatchdog()
        Dbg.i(TAG, "唤醒候选「$word」，等声纹结果再决定是否进入聆听")

        val s = AgentSettings.load()
        // ★ 还没有模板：**不再**拿这句唤醒词自动注册（审核 S6 / V-07——第一个开口的人
        //   会被认成主人，之后真正的主人被静默拒绝）。照常回应唤醒，但这一轮的每句话都会
        //   标成 SpeakerAuth.NOT_ENROLLED，读通知/个人数据等敏感能力需要解锁或先在界面里注册。
        if (!gate.hasTemplate()) {
            if (logThrottled("not-enrolled", 10 * 60_000L)) {
                Dbg.i(TAG, "尚未注册声纹：唤醒照常响应，敏感能力需解锁或先注册声纹")
            }
            wakeAuthorized = true
            acknowledgeWake(word)
            return
        }
        if (!s.requireVoiceprint) {
            wakeAuthorized = true
            acknowledgeWake(word)
            return
        }
        // ★ 声纹取的是环形缓冲里最近 1.5 秒——但 KWS 命中时"小派"可能已经说完了，
        //   窗口里剩下的可能是后面的静默/杂音，判出来的分数没有意义（实测 0.20）。
        //   所以把窗口临时拉长到 3 秒再取，覆盖整个唤醒词。
        val snapshot = ring.snapshot()
        worker.execute {
            val score = gate.verify(snapshot)
            if (score == null) {
                Dbg.w(TAG, "声纹校验不可用（${gate.lastError}），放行")
                main.post {
                    wakeAuthorized = true
                    acknowledgeWake(word)
                }
                return@execute
            }
            // ★ 唤醒门比逐句门宽：唤醒只证明"是主人喊的"，后面每句还有完整阈值的
            //   二次校验兜底；车内噪声会把 1.5~3 秒的唤醒片段压到 0.25 左右（实测），
            //   用同一阈值会让主人喊得醒却整轮被丢弃。
            val gateThreshold = s.voiceprintThreshold * WAKE_VERIFY_SCALE
            val pass = score >= gateThreshold
            Dbg.i(
                TAG,
                "声纹 ${"%.3f".format(score)} 唤醒门 ${"%.3f".format(gateThreshold)} → ${if (pass) "通过" else "拒绝"}",
            )
            main.post { onVoiceprint(score, pass) }
            if (pass) {
                main.post {
                    wakeAuthorized = true
                    acknowledgeWake(word)
                }
            } else {
                // 保持 wakeAuthorized=false；不播提示音、不回应「我在」。
                // 给"用户已经在说指令"留出时间（声纹只要 ~90ms，
                // 但唤醒词后半句可能还在路上），再判断要不要收回聆听。
                main.postDelayed({ rejectWakeIfSilent() }, VOICEPRINT_REJECT_GRACE_MS)
            }
        }
    }

    /**
     * 声纹通过（或无需声纹）后才回应唤醒：提示音 + onWake + **公布聆听态**。
     *
     * ★ 老板要的顺序：唤醒 → 回「我在」→ 进入聆听态。所以状态在这里才发，
     *   没通过声纹的唤醒从头到尾不会让输入框亮起来。
     */
    private fun acknowledgeWake(word: String) {
        playBeep()
        main.post {
            onWake(word)
            // 声纹推理期间用户可能已经开口，甚至已经在识别——那就交给那一轮，
            // 别把 LISTENING 盖到 RECOGNIZING 上面。
            if (mode == Mode.LISTEN && !busy.get()) {
                armListenWatchdog()
                emitState(State.LISTENING)
                // 唤醒后用户 5 秒没接话 → 当误唤醒回休眠（不给 60 秒免唤醒活跃期）
                armConversationWindow(sleepIfSilent = true)
            }
        }
        Dbg.i(TAG, "唤醒「$word」，声纹通过，进入聆听")
    }

    private fun recognize(pcm: ShortArray) {
        // ★ 放音期间（音乐/TTS）麦克风录到的是音频输出，不是人声——
        //   送进 ASR 会把歌词误听成「你说完了」这类话，然后 agent 当成指令播报，
        //   音乐就被打断了（11-10 真机反馈）。这里直接丢弃，不浪费 ASR 算力。
        if (AudioGate.isMuted) {
            Dbg.i(TAG, "放音中，丢弃本段音频（${pcm.size * 1000 / AudioCapture.SAMPLE_RATE}ms）")
            main.post { backToIdleOrWake() }
            return
        }
        // ★ 放音刚结束的尾音也要丢：VAD 缓冲里那段会被识别成播放内容本身
        //   （「音量已经是最大百分之一百」），然后被当成新指令执行，形成自问自答。
        val sinceSpeak = SystemClock.uptimeMillis() - AudioGate.lastReleaseAt
        if (AudioGate.lastReleaseAt > 0L && sinceSpeak < ECHO_GUARD_MS) {
            Dbg.i(TAG, "放音刚结束 ${sinceSpeak}ms，丢弃尾音（${pcm.size * 1000 / AudioCapture.SAMPLE_RATE}ms）")
            main.post { backToIdleOrWake() }
            return
        }
        if (!busy.compareAndSet(false, true)) return
        main.removeCallbacks(finishGuard)
        main.removeCallbacks(listenWatchdog)
        // 已经切句进入识别：聆听窗口到此为止（动效随之收起）
        main.removeCallbacks(conversationTick)
        convListen = false
        emitState(State.RECOGNIZING)
        worker.execute {
            try {
                val ms = pcm.size * 1000 / AudioCapture.SAMPLE_RATE
                Dbg.i(TAG, "识别 ${ms}ms 音频")
                // P2：会话内逐句验声纹。唤醒只证明「喊醒的是主人」，这一轮里
                // 后面每句可能是另一个人说的。模型不可用 / 音频不足 0.6s 时放行，
                // 与 VoiceprintGate「宁可放过，不可错杀」的定位一致。
                var auth = SpeakerAuth.NOT_ENROLLED
                if (wakeAuthorized) {
                    val s = AgentSettings.load()
                    auth = when {
                        !s.requireVoiceprint -> SpeakerAuth.NOT_REQUIRED
                        !gate.hasTemplate() -> SpeakerAuth.NOT_ENROLLED
                        else -> SpeakerAuth.UNVERIFIED
                    }
                    if (s.requireVoiceprint && gate.hasTemplate()) {
                        val score = gate.verify(pcm)
                        // ★ 逐句门同样比设置阈值宽：车内实测每句 0.28~0.42（阈值 0.38 时
                        //   0.28/0.34 被误杀）。TTS 回声只有 0.02~0.11，降到 0.266 仍有足够间距。
                        val segmentGate = s.voiceprintThreshold * SEGMENT_VERIFY_SCALE
                        if (score == null) {
                            // 软放行，但标记为"未校验"：敏感能力不能当成主人
                            Dbg.i(TAG, "逐句声纹不可用（${gate.lastError ?: "音频不足"}），放行但标记未校验")
                            auth = SpeakerAuth.UNVERIFIED
                        } else if (score < segmentGate) {
                            Dbg.i(
                                TAG,
                                "逐句声纹 ${"%.3f".format(score)} 逐句门 ${"%.3f".format(segmentGate)} → 拒绝，忽略这句",
                            )
                            return@execute
                        } else {
                            Dbg.i(TAG, "逐句声纹 ${"%.3f".format(score)} → 通过")
                            auth = SpeakerAuth.VERIFIED
                        }
                    }
                }
                // 这一句过了声纹门 → 记下来。聆听窗口的"说完话 1 秒收尾"以它为准；
                // 被拒绝的句子根本走不到这里（上面已 return），也就不会续命。
                lastVerifiedAt = SystemClock.uptimeMillis()
                val text = asr.transcribe(pcm)
                val authorized = wakeAuthorized
                // ★ 原"首句自动注册"已删除（审核 S6 / V-07）：注册只能由用户在界面里显式发起。
                // ★ 把识别结果写给导航界面（搜索框自动填入）
                if (authorized && text != null) {
                    runCatching { VoiceState.write(context, VoiceState.IDLE, text) }
                }
                main.post {
                    when {
                        // ★ 声纹不匹配就**静默忽略**：这是"防误触发"的正常路径，
                        //   不是错误。写进对话区会刷屏（旁人说话也刷一条）。
                        !authorized -> Dbg.i(TAG, "声纹不匹配，忽略这句")
                        text == null -> onError(asr.lastError ?: "端侧识别不可用")
                        text.isBlank() -> Dbg.i(TAG, "识别结果为空")
                        isWakeEcho(text) -> Dbg.i(TAG, "丢弃唤醒词残留（${text.length} 字）")
                        else -> {
                            // 先写校验结论，onText 回调里读到的就是这一句的
                            lastSpeakerAuth = auth
                            onText(text)
                        }
                    }
                }
            } catch (t: Throwable) {
                Dbg.e(TAG, "识别异常", t)
                main.post { onError(t.message ?: "识别异常") }
            } finally {
                busy.set(false)
                // 状态迁移统一回主线程（V-05），排在 onText 之后
                main.post { backToIdleOrWake() }
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
        // ★ 唤醒时那句"我在"（轻量播报不占闸，麦克风会把它听进去）→ 丢掉，
        //   否则它会变成一条莫名其妙的用户消息。
        if (t == "我在" || t == "我在在" || t == "我在了") return true
        val words = AgentSettings.load().wakeWordList()
        if (words.any { t.contains(it) }) return true
        val chars = words.joinToString("").toSet()
        return t.length <= 2 && t.all { it in chars }
    }

    private fun backToIdleOrWake(skipActive: Boolean = false) {
        main.removeCallbacks(listenWatchdog)
        main.removeCallbacks(conversationTick)
        main.removeCallbacks(convEnterRetry)
        convListen = false
        // 任何收尾路径都要把「正在录音」清掉，否则导航麦克风图标会一直转
        runCatching { VoiceState.write(context, VoiceState.IDLE) }
        // A recognition worker can finish after the main thread has already
        // switched to do-not-disturb. Never publish ACTIVE/WAKE_WAIT over DND.
        if (dndRequested) {
            mode = Mode.WAKE
            wake.reset()
            vad?.reset()
            emitState(State.ASLEEP)
            Dbg.i(TAG, "免打扰已生效，跳过活跃期恢复")
            return
        }
        AudioFocusManager.resumeMusicAfterVoice()
        if (resumeToWake) {
            val sec = AgentSettings.load().idleSleepSeconds
            // ★ skipActive：这次聆听一句话都没说（误唤醒 / 点错了），
            //   直接回休眠，别给 25 秒免唤醒活跃期——否则环境音会被当成
            //   对话继续喂给 ASR，用户感觉"它自己醒着不睡"。
            if (sec > 0 && !skipActive) {
                // 活跃期：不用再喊唤醒词，直接说话；超时自动休眠
                mode = Mode.ACTIVE
                wake.reset()
                vad?.reset()
                resetActiveWindow()
                emitState(State.ACTIVE)
                Dbg.i(TAG, "进入活跃期 ${sec}s")
            } else {
                main.removeCallbacks(sleepTick)
                activeUntil = 0L
                mode = Mode.WAKE
                wake.reset()
                vad?.reset()
                ring.clear()
                emitState(if (sec > 0) State.ASLEEP else State.WAKE_WAIT)
                if (skipActive && sec > 0) Dbg.i(TAG, "空聆听结束，直接休眠（不进活跃期）")
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

        /**
         * 导航候选序号词（和搜索面板最多 5 条对应）。
         * 常驻在 KWS 词表里，但只有「正在播报 + 导航面板列着候选」时才生效。
         */
        val NAV_ORDINAL_WORDS = listOf("第一个", "第二个", "第三个", "第四个", "第五个")

        /** 普通指令的断句静音阈值 */
        const val NORMAL_SILENCE_MS = 800

        /** KWS 失败后多久检查一次是否该自动重载 */
        const val KWS_RELOAD_CHECK_MS = 5_000L

        /** 点按钮后最长等多久还切不出一句（防止永远停在「正在聆听」）。 */
        const val LISTEN_TIMEOUT_MS = 20_000L

        /**
         * 聆听的硬上限：到点**无条件**收状态。
         *
         * 为什么不用 [LISTEN_TIMEOUT_MS] 一个数：那个会被 VAD 的 inSpeech 续期，
         * 而环境噪声会让 VAD 长期认为"正在说话"，于是永不超时。留一个更大的
         * 绝对上限兜底——正常一句话 15 秒也切出来了（MAX_SEGMENT_MS）。
         */
        const val LISTEN_HARD_CAP_MS = 30_000L

        /**
         * 聆听态的空闲上限：进入后这么久没听到一句**通过声纹**的话，自动收起。
         *
         * 老板定的 5 秒——唤醒/播报结束后等用户接话，等不到就把动效收掉，
         * 不让人对着一个一直"在听"的输入框干等。
         */
        const val CONV_IDLE_MS = 5_000L

        /**
         * 聆听态在"说过话"之后的收尾：用户说完一句超过 1 秒没有新的开口，收起。
         * 声纹没过的句子不算（老板：没过声纹关就不存在聆听态）。
         */
        const val CONV_AFTER_SPEECH_MS = 1_000L

        /** 聆听窗口的检查间隔 */
        const val CONV_TICK_MS = 200L

        /** 声纹否决后等多久再判断"用户其实没说话"（毫秒） */
        const val VOICEPRINT_REJECT_GRACE_MS = 1_200L

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

        /**
         * 唤醒验声纹至少取多长的音频（毫秒）。
         *
         * KWS 命中时唤醒词往往已经说完，1.5 秒窗口里只剩尾音和噪声；
         * 车内实测分数只有 0.18~0.26。拉到 3 秒能把整个唤醒词覆盖进来。
         */
        const val WAKE_VERIFY_WINDOW_MS = 3_000

        /**
         * 唤醒门阈值系数。
         *
         * 唤醒只做初筛，逐句声纹仍会二次校验；
         * 车内噪声会把唤醒片段压到 0.25 左右，用全阈值会"喊得醒但整轮被丢弃"。
         */
        const val WAKE_VERIFY_SCALE = 0.7f

        /**
         * 逐句门阈值系数。
         *
         * 车内有路噪/发动机声，主人自己的每句分数会掉到 0.28~0.42，而 TTS 回声只有
         * 0.02~0.11，两者间距很大。乘 0.7（当前阈值 0.38 → 0.266）既能放行主人，
         * 仍能挡住明显的非主人声音。
         */
        const val SEGMENT_VERIFY_SCALE = 0.7f
    }
}
