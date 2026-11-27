package com.pilot.agent.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pilot.agent.Dbg
import com.pilot.agent.agent.AgentEvent
import com.pilot.agent.agent.AgentIntentRouter
import com.pilot.agent.agent.AgentSettings
import com.pilot.agent.agent.AssistantMode
import com.pilot.agent.agent.IntentGate
import com.pilot.agent.agent.Risk
import com.pilot.agent.assistant.AssistantFastPath
import com.pilot.agent.assistant.FastPathPlan
import com.pilot.agent.assistant.exec.FastPathTrace
import com.pilot.agent.assistant.exec.ResultFormatter
import com.pilot.agent.chat.ChatSessionStore
import com.pilot.agent.runtime.AgentRuntime
import com.pilot.agent.runtime.await
import com.pilot.agent.runtime.rethrowIfCancellation
import com.pilot.agent.tool.ToolGroup
import com.pilot.agent.tool.ToolPolicy
import com.pilot.agent.tool.ToolStatus
import com.pilot.agent.voice.VoiceInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import java.util.UUID

data class LogEntry(val id: Long, val kind: Kind, val text: String) {
    enum class Kind { USER, THINK, ACT, OBSERVE, DONE, ERROR }
}

data class ConfirmUi(val id: Long, val question: String, val risk: Risk)

private data class PendingToolConfirm(
    val id: Long,
    val deferred: CompletableDeferred<Boolean>,
)

class AgentViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "PilotVM"

        /** 用户说"你休息一会"时的回应 */
        const val DND_REPLY = "我先休息了，有事叫我啊"

        /** 唤醒时的回应 */
        const val WAKE_REPLY = "我在"
    }

    var settings by mutableStateOf(AgentSettings.load())
        private set

    val logs = mutableStateListOf<LogEntry>()

    /**
     * 聊天历史（一问一答成对）。
     *
     * 之前聊天是"每句独立请求"，模型完全不知道上一句说了什么，
     * "我叫什么""刚才那个再说一遍"这类追问全接不上。
     * 这里把历史存下来，按设置的轮数注入到请求里。
     */
    private val chatHistory = ArrayDeque<Pair<String, String>>()

    /**
     * 最近会话（按更新时间倒序），供首页「☰」菜单切换。
     *
     * 第一条可见消息出现时自动建会话，之后每次 USER/DONE/ERROR 变化都会
     * 把快照落盘；重启 App 会接着上次的会话。
     */
    var sessions by mutableStateOf<List<ChatSessionStore.Session>>(emptyList())
        private set

    /** 当前显示的会话 id；null = 还没有产生消息的新会话。 */
    var currentSessionId by mutableStateOf<String?>(null)
        private set

    /**
     * 对话区只显示"人说的话"和"AI 说的话"。
     *
     * 过程类（思考 THINK / 执行 ACT / 观察 OBSERVE）一律不显示——
     * 那些是给人调试看的，堆在对话里用户只会觉得吵。
     */
    val chatLogs: List<LogEntry>
        get() = logs.filter {
            it.kind == LogEntry.Kind.USER || it.kind == LogEntry.Kind.DONE || it.kind == LogEntry.Kind.ERROR
        }

    var running by mutableStateOf(false)
        private set

    /**
     * 刚发送、任务正在跑（区别于"完全空闲"）。
     *
     * 发送后按钮要**先变成停止**，任务做完才回纸飞机——
     * 现在 running 一变 false 立刻回纸飞机，用户按不到停止。
     */
    var justSent by mutableStateOf(false)
        private set

    /**
     * 正在播报（用于显示"停止"按钮）。
     *
     * 老板说的停止是**终止播报**——播报太长时用户想立刻闭嘴，不是中止任务。
     * 播报状态由 Speaker 维护，这里轮询同步到 Compose 状态。
     */
    var speaking by mutableStateOf(false)
        private set

    /** 当前播放的歌名（播放中或暂停中），空 = 没在放 */
    var nowPlaying by mutableStateOf<String?>(null)
        private set

    /** 音乐是否处于暂停状态（决定按钮显示"继续"还是"暂停"） */
    var musicPaused by mutableStateOf(false)
        private set

    var musicArtist by mutableStateOf("")
        private set

    var musicLyric by mutableStateOf("")
        private set

    var musicLyricsReady by mutableStateOf(false)
        private set

    var musicPositionMs by mutableStateOf(0L)
        private set

    var musicDurationMs by mutableStateOf(0L)
        private set

    var musicTrackIndex by mutableStateOf(0)
        private set

    var musicQueueSize by mutableStateOf(0)
        private set

    var musicQueue by mutableStateOf<List<com.pilot.agent.music.MusicPlayer.QueueEntry>>(emptyList())
        private set

    var musicShuffle by mutableStateOf(false)
        private set

    var musicPlaybackMode by mutableStateOf(com.pilot.agent.music.MusicPlayer.PlaybackMode.REPEAT_ALL)
        private set

    /**
     * 系统 TTS 可用音色（运行时向引擎枚举）。
     *
     * 各 ROM 的音色表完全不同（华为/小米/Pixel 各有各的），
     * 必须运行时问引擎要，写死列表会列出一堆这台机器上不存在的音色。
     */
    var systemVoices by mutableStateOf<List<com.pilot.agent.voice.SystemVoice>>(emptyList())
        private set

    /** 加载系统 TTS 音色列表（打开设置时调一次）。 */
    fun loadSystemVoices() {
        if (systemVoices.isNotEmpty() || systemVoicesLoading) return
        systemVoicesLoading = true
        viewModelScope.launch {
            // 引擎初始化是异步的（onInit 回调），要等它就绪再枚举，
            // 否则拿到的是空列表（实测：新建实例立刻枚举 → 一个都没有）。
            var list: List<com.pilot.agent.voice.SystemVoice> = emptyList()
            repeat(12) {
                list = withContext(Dispatchers.IO) { AgentRuntime.speaker.systemVoices() }
                if (list.isNotEmpty()) return@repeat
                delay(400)
            }
            systemVoices = list
            systemVoicesLoading = false
            if (list.isEmpty()) {
                Dbg.i(TAG, "系统 TTS 没有可用音色（引擎未提供 zh 音色）")
            }
        }
    }

    @Volatile
    private var systemVoicesLoading = false

    var confirm by mutableStateOf<ConfirmUi?>(null)
        private set

    var progress by mutableStateOf(0 to 0)
        private set

    var accessibilityOn by mutableStateOf(false)
        private set

    /**
     * 权限已开、但无障碍服务还没绑上时，暂存的待启动目标。
     * 进程刚被拉起时系统常常晚一点才 bind 无障碍服务，等一下就能连上，
     * 不该把用户扔到设置页（11-26 真机反馈：权限明明开着仍被提示未开启）。
     */
    private var loopRetryGoal: Pair<String, AssistantMode>? = null

    var notificationAccess by mutableStateOf(false)
        private set
    var reminderNotificationsAllowed by mutableStateOf(true)
        private set

    var skillCount by mutableStateOf(0)
        private set

    var userScripts by mutableStateOf<List<com.pilot.agent.extension.UserScript>>(emptyList())
        private set

    var mcpServers by mutableStateOf<List<com.pilot.agent.extension.McpServerSummary>>(emptyList())
        private set

    var mcpProbeText by mutableStateOf("")
        private set

    var mcpProbeRunning by mutableStateOf(false)
        private set

    /** 已学技能列表（点设置里"已学技能 N 条"时展示） */
    var skillList by mutableStateOf<List<com.pilot.agent.agent.Skill>>(emptyList())
        private set

    var showSkillList by mutableStateOf(false)
        private set

    fun openSkillList() {
        skillList = AgentRuntime.skills.all()
        skillCount = skillList.size
        showSkillList = true
    }

    fun closeSkillList() {
        showSkillList = false
    }

    fun reloadSettings() {
        settings = AgentSettings.load()
    }

    fun selectReasoningEffort(value: String) {
        val effort = value.takeIf { it in AgentSettings.REASONING_EFFORTS }
            ?: AgentSettings.DEFAULT_REASONING_EFFORT
        if (effort == settings.reasoningEffort) return
        val next = AgentSettings.load().copy(reasoningEffort = effort)
        AgentSettings.save(next)
        settings = next
    }

    fun refreshExtensions() {
        userScripts = AgentRuntime.userScripts.all()
        mcpServers = AgentRuntime.mcp.configuredServers()
    }

    fun importUserScript(uri: android.net.Uri) {
        AgentRuntime.userScripts.import(getApplication(), uri)
            .onSuccess {
                refreshExtensions()
                add(LogEntry.Kind.OBSERVE, "已导入用户脚本「${it.name}」")
            }
            .onFailure {
                add(LogEntry.Kind.ERROR, "导入用户脚本失败：${it.message}")
            }
    }

    fun deleteUserScript(id: String) {
        val deleted = AgentRuntime.userScripts.delete(id)
        refreshExtensions()
        add(
            if (deleted) LogEntry.Kind.OBSERVE else LogEntry.Kind.ERROR,
            if (deleted) "已删除用户脚本" else "没有找到要删除的用户脚本",
        )
    }

    fun clearBrowserSession() {
        viewModelScope.launch {
            com.pilot.agent.browser.BrowserSessionManager.clearSessionData(getApplication())
            add(LogEntry.Kind.OBSERVE, "浏览器 Cookie 和登录状态已清除")
        }
    }

    fun probeMcp() {
        if (mcpProbeRunning) return
        mcpProbeRunning = true
        mcpProbeText = "正在检查 MCP 运行时…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { AgentRuntime.mcp.listServers() }
            mcpProbeText = if (result.ok) {
                result.output.ifBlank { "MCP 运行时正常，当前没有服务器。" }
                    .take(4_000) + "\n耗时 ${result.durationMs}ms"
            } else {
                "MCP 检查失败（exit=${result.exitCode}）：\n" +
                    result.output.take(4_000)
            }
            mcpProbeRunning = false
        }
    }

    var toolPolicies by mutableStateOf(AgentRuntime.localTools.policies())
        private set
    var toolMemoryCount by mutableStateOf(0)
        private set
    var allFilesAccess by mutableStateOf(false)
        private set
    var showMemoryList by mutableStateOf(false)
        private set
    var memoryList by mutableStateOf<List<com.pilot.agent.tool.MemoryStore.Memory>>(emptyList())
        private set
    var showScheduledTasks by mutableStateOf(false)
        private set
    var scheduledTaskList by mutableStateOf<List<com.pilot.agent.tool.ScheduledTaskManager.Task>>(emptyList())
        private set

    fun refreshLocalToolState() {
        toolPolicies = AgentRuntime.localTools.policies()
        toolMemoryCount = AgentRuntime.localTools.memoryCount()
        allFilesAccess = AgentRuntime.localTools.allFilesAccessGranted()
    }

    fun setToolPolicy(group: ToolGroup, policy: ToolPolicy) {
        AgentRuntime.localTools.setPolicy(group, policy)
        toolPolicies = AgentRuntime.localTools.policies()
    }

    fun openMemoryList() {
        memoryList = AgentRuntime.localTools.memory.all()
        toolMemoryCount = memoryList.size
        showMemoryList = true
    }

    fun closeMemoryList() {
        showMemoryList = false
    }

    fun forgetMemory(id: Long) {
        AgentRuntime.localTools.deleteMemoryFromUi(id)
        memoryList = AgentRuntime.localTools.memory.all()
        toolMemoryCount = memoryList.size
    }

    fun openScheduledTasks() {
        scheduledTaskList = com.pilot.agent.tool.ScheduledTaskManager.allTasks(getApplication())
        showScheduledTasks = true
    }

    fun closeScheduledTasks() {
        showScheduledTasks = false
    }

    fun refreshScheduledTasks() {
        scheduledTaskList = com.pilot.agent.tool.ScheduledTaskManager.allTasks(getApplication())
    }

    /** @return true 表示成功；失败时刷新列表让 UI 显示真实状态。 */
    fun updateScheduledTask(
        id: String,
        goal: String = "",
        at: Long = 0L,
        daily: Boolean? = null,
    ): Boolean {
        val ok = com.pilot.agent.tool.ScheduledTaskManager.updateTask(
            getApplication(), id, goal, at, daily,
        ) != null
        refreshScheduledTasks()
        return ok
    }

    fun deleteScheduledTask(id: String): Boolean {
        val ok = com.pilot.agent.tool.ScheduledTaskManager.deleteTask(getApplication(), id)
        refreshScheduledTasks()
        return ok
    }

    fun createScheduledTask(goal: String, at: Long, daily: Boolean): Boolean {
        val created = com.pilot.agent.tool.ScheduledTaskManager.createTask(
            getApplication(), goal, at, daily,
        )
        refreshScheduledTasks()
        return created != null
    }

    var foreground by mutableStateOf("")
        private set

    // 端侧能力状态，给状态卡片用
    var asrReady by mutableStateOf(false)
        private set
    var llmReady by mutableStateOf(false)
        private set
    var llmNote by mutableStateOf("未加载")
        private set
    var asrNote by mutableStateOf("未加载")
        private set

    var voiceState by mutableStateOf(VoiceInput.State.IDLE)
        private set

    /** 用户主动要求免打扰（说了"你休息一会"），不是自动休眠。 */
    var dndRequested by mutableStateOf(false)
        private set

    /** 待命模式（常驻听唤醒词）是否开启 */
    var wakeOn by mutableStateOf(false)
        private set

    /** 最近一次声纹分数，显示在状态卡里 */
    var voiceprintInfo by mutableStateOf("")
        private set

    /** 是否已注册声纹模板 */
    var voiceprintEnrolled by mutableStateOf(false)
        private set

    // ---------- 声纹注册引导 ----------
    /** 注册采集进行中（UI 据此显示录音状态） */
    var enrollRunning by mutableStateOf(false)
        private set
    /** 「2/3」这样的进度 */
    var enrollProgress by mutableStateOf("")
        private set
    /** 完成结果：null=未出结果 */
    var enrollResult by mutableStateOf<Boolean?>(null)
        private set
    /** 注册口令（引导文案用） */
    /**
     * 声纹注册口令。
     *
     * ★ 必须**包含唤醒词**：声纹校验拿的是"注册音频"和"唤醒时那段音频"比，
     *   两句得是同一段话，否则主人自己喊唤醒词时分数偏低会被拒。
     * ★ 也天然覆盖打断词（先停一下 / 等我说完），注册时顺带验证这两条链路。
     */
    val enrollPhrase = "小派你好，小派小派，先停一下，等我说完你再说话"

    // ---------- 网易云扫码登录 ----------
    var qrBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
        private set
    var loginTip by mutableStateOf("")
        private set

    var tailnetState by mutableStateOf("尚未连接")
        private set

    var musicServiceState by mutableStateOf("服务未检测")
        private set

    fun testMusicService(baseUrl: String = settings.musicBaseUrl, fallbackUrl: String = settings.musicFallbackUrl) {
        viewModelScope.launch {
            musicServiceState = "正在检查音乐服务…"
            val key = withContext(Dispatchers.IO) {
                com.pilot.agent.music.NeteaseApi.qrKey(baseUrl.trim(), fallbackUrl.trim())
            }
            musicServiceState = if (key.isNullOrBlank()) "未连通：检查主/备用地址和网络"
                else "API 可用：扫码登录接口正常"
        }
    }

    fun connectTailnet() {
        viewModelScope.launch {
            tailnetState = "正在启动嵌入式 Tailnet…"
            val app = getApplication<Application>()
            val first = withContext(Dispatchers.IO) { com.pilot.agent.music.TailscaleGateway.start(app) }
            if (!first.optBoolean("ok")) {
                tailnetState = "启动失败：${first.optString("error", "未知错误")}"
                return@launch
            }
            var status = first.optJSONObject("status")
            var authUrl = status?.optString("authUrl").orEmpty()
            if (!authUrl.isNullOrBlank() && authUrl != "null") {
                tailnetState = "请在浏览器完成 Tailnet 授权…"
                runCatching {
                    app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(authUrl))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { tailnetState = "无法打开授权页：${it.message}" }
            }
            repeat(90) {
                val result = withContext(Dispatchers.IO) { com.pilot.agent.music.TailscaleGateway.status(app) }
                status = result.optJSONObject("status")
                if (status?.optBoolean("running") == true) {
                    tailnetState = "已连接：${status?.optJSONArray("ips")?.optString(0).orEmpty()}"
                    return@launch
                }
                val nextUrl = status?.optString("authUrl").orEmpty()
                if (authUrl.isBlank() && nextUrl.isNotBlank() && nextUrl != "null") {
                    authUrl = nextUrl
                    runCatching {
                        app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(authUrl))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                tailnetState = when {
                    !result.optBoolean("ok") -> "连接中：${result.optString("error", "等待授权")}"
                    else -> "等待 Tailnet 授权…"
                }
                delay(2_000)
            }
            tailnetState = "连接超时；可重新点击连接"
        }
    }
    var neteaseNick by mutableStateOf("")
        private set

    private var loginJob: kotlinx.coroutines.Job? = null

    private var voice: VoiceInput? = null
    private var activeTaskLabel: String = ""
    private var lastNotificationStatus: String? = null
    private var lastNavNotificationPollAt = 0L
    private var cachedNavNotification: String? = null

    /** 最近一次用户说的话（用于去重） */
    private var lastUserText: String? = null
    private var lastUserTextAt = 0L
    private var lastAccessibilityPromptAt = 0L

    /** 上一条「这个动作要读屏幕，要我带你去开权限吗」正在等用户回答（不默认跳页面）。 */
    private var pendingA11yGuide = false

    /** loop 不存在时，C 类命令的「带路」目标由 ViewModel 自己记着。 */
    private var pendingViewGuide: com.pilot.agent.action.CommandTarget? = null
    @Volatile
    private var pendingToolConfirm: PendingToolConfirm? = null
    private var dispatchJob: kotlinx.coroutines.Job? = null
    private var seq = 0L

    init {
        // 先恢复最近会话，再启动状态轮询；聊天区一有内容 LazyColumn 会自动滚到底。
        sessions = ChatSessionStore.loadAll()
        ChatSessionStore.activeSessionId()
            ?.let { id -> sessions.firstOrNull { it.id == id } }
            ?.let { restoreSession(it) }

        viewModelScope.launch {
            AgentRuntime.events.collect { handle(it) }
        }
        // ★ 常驻轮询播报状态 + 语音状态：
        //   VoiceInput 是单例，onState 回调只在 ensureVoice() 时挂；
        //   启动流程没走到那步（或 VM 重建）时 UI 就永远收不到状态变化，
        //   导致"在听"不显示（实测踩到）。这里直接读单例状态，不依赖回调。
        viewModelScope.launch {
            while (true) {
                syncSpeaking()
                val st = AgentRuntime.voiceInput?.state
                if (st != null && st != voiceState) {
                    voiceState = st
                    Dbg.i(TAG, "语音状态 → $st")
                }
                if (st == VoiceInput.State.IDLE || st == VoiceInput.State.UNAVAILABLE) wakeOn = false
                // 免打扰标记（用户主动要求的休眠才显示提示）
                val dnd = AgentRuntime.voiceInput?.dndRequested == true
                if (dnd != dndRequested) dndRequested = dnd
                // 音乐播放状态（给顶部播放条用）
                val mp = com.pilot.agent.music.MusicPlayer.get(getApplication())
                val title = mp.nowPlaying()
                if (title != nowPlaying) nowPlaying = title
                if (mp.paused != musicPaused) musicPaused = mp.paused
                if (mp.currentArtist() != musicArtist) musicArtist = mp.currentArtist()
                val lyric = mp.currentLyric().orEmpty()
                if (lyric != musicLyric) musicLyric = lyric
                if (mp.lyricsReady() != musicLyricsReady) musicLyricsReady = mp.lyricsReady()
                musicPositionMs = mp.positionMs()
                musicDurationMs = mp.durationMs()
                if (mp.currentIndex() != musicTrackIndex) musicTrackIndex = mp.currentIndex()
                if (mp.queueSize() != musicQueueSize) musicQueueSize = mp.queueSize()
                val queue = mp.queueSnapshot()
                if (queue != musicQueue) musicQueue = queue
                if (mp.shuffleEnabled != musicShuffle) musicShuffle = mp.shuffleEnabled
                if (mp.playbackMode != musicPlaybackMode) musicPlaybackMode = mp.playbackMode
                publishNotificationStatus()
                // P2：100ms × 十余个 MusicPlayer Binder 同步调用 = 持续 10Hz 压力；
                // 300ms 对播放条 UI 足够跟手，负载降 2/3。
                delay(300)
            }
        }
        viewModelScope.launch {
            AgentRuntime.foreground.collect { foreground = it }
        }
        refreshStatus()
        // 后台预热 ASR：模型加载 2.5 秒，别等用户按麦才付这个时间。
        // 只加载识别模型，不碰麦克风；LLM 300MB 太大，等真正需要时再加载。
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (AgentRuntime.asr.abiSupported()) AgentRuntime.asr.ensureReady()
            }
            refreshStatus()
        }
        // ★ 进 App 就开麦：常驻听唤醒词（KWS）+ 唤醒时验声纹。
        //   不这么做的话，麦克风要等用户手动点"待命"才开，体验上是"喊它没反应"。
        //   设置里关掉待命（wakeEnabled=false）就不自动开。
        viewModelScope.launch {
            val on = AgentSettings.load().wakeEnabled
            if (!on) return@launch
            ensureVoice()
            val v = voice ?: return@launch
            if (v.isWakeEnabled) {
                wakeOn = true
                com.pilot.agent.service.PilotForegroundService.startForWake(getApplication())
                return@launch
            }
            // 等 ASR 预热那一步先跑完，避免和模型加载抢线程
            val serviceStarted = com.pilot.agent.service.PilotForegroundService.startForWake(getApplication())
            val ok = serviceStarted && withContext(Dispatchers.IO) { v.startWake() }
            if (!ok && serviceStarted) com.pilot.agent.service.PilotForegroundService.stopForWake(getApplication())
            wakeOn = ok
            if (ok) add(LogEntry.Kind.OBSERVE, "麦克风已常驻，喊「${AgentSettings.load().wakeWordList().first()}」唤醒")
            else if (!serviceStarted) add(LogEntry.Kind.ERROR, "语音待命未启动：请授予麦克风权限后重试")
            refreshStatus()
        }
    }

    /** 同步播报状态（Speaker 是普通对象，状态变化不会自动触发 Compose 重组）。 */
    private fun syncSpeaking() {
        val s = AgentRuntime.speaker.isSpeaking
        if (s != speaking) {
            speaking = s
            Dbg.i(TAG, "播报状态 → $s")
        }
    }

    /** 由真实语音/任务状态驱动常驻通知，只有内容变化时才更新。 */
    private fun publishNotificationStatus() {
        val status = resolvedStatus()
        com.pilot.agent.ui.PilotStatusOverlay.updateStatus(
            getApplication(), overlayLabel(), shouldShowStatusOverlay(),
        )
        if (status == lastNotificationStatus) return
        lastNotificationStatus = status
        com.pilot.agent.service.PilotForegroundService.updateWakeStatus(getApplication(), status)
    }

    fun statusForOverlay(): String = overlayLabel()

    fun shouldShowStatusOverlay(): Boolean = wakeOn || running || AgentRuntime.speaker.isSpeaking

    /**
     * 悬浮球只反映语音/任务状态。
     *
     * 导航状态（正在规划/正在导航）由导航界面自己显示，不再占用悬浮球——
     * 否则导航时悬浮球永远停在「正在导航」，看不到真正的语音状态
     * （正在聆听/正在回答/休眠）。
     */
    private fun overlayLabel(): String = when {
        running -> "正在执行任务"
        AgentRuntime.speaker.isSpeaking -> "正在回答"
        voiceState == VoiceInput.State.LISTENING -> "正在聆听"
        voiceState == VoiceInput.State.RECOGNIZING -> "正在识别"
        !wakeOn -> if (voiceState == VoiceInput.State.UNAVAILABLE) "语音不可用" else "语音已关闭"
        voiceState == VoiceInput.State.ASLEEP -> "语音休眠中"
        else -> "正在待命"
    }

    private fun resolvedStatus(): String {
        val navStatus = navigationNotificationStatus()
        return when {
            navStatus != null -> navStatus
            running -> "正在执行：${activeTaskLabel.ifBlank { "任务" }.take(36)}"
            AgentRuntime.speaker.isSpeaking -> "正在回答"
            voiceState == VoiceInput.State.LISTENING -> "正在聆听"
            voiceState == VoiceInput.State.RECOGNIZING -> "正在识别"
            !wakeOn -> if (voiceState == VoiceInput.State.UNAVAILABLE) "语音入口不可用" else "语音待命已关闭"
            voiceState == VoiceInput.State.ASLEEP -> "语音休眠中，说唤醒词可恢复"
            voiceState == VoiceInput.State.IDLE || voiceState == VoiceInput.State.UNAVAILABLE -> "语音入口不可用"
            else -> "正在待命，说「${AgentSettings.load().wakeWordList().firstOrNull() ?: "小派"}」唤醒"
        }
    }

    /** 导航服务状态低频读取，距离按百米取整、ETA 按分钟取整，避免通知随定位抖动刷新。 */
    private fun navigationNotificationStatus(): String? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNavNotificationPollAt < 1_500L) return cachedNavNotification
        lastNavNotificationPollAt = now
        cachedNavNotification = runCatching {
            val state = org.json.JSONObject(com.pilot.agent.navigation.NavigationClient.stateJson())
            val destination = state.optString("destination").trim()
            when (state.optString("state")) {
                "ROUTING" -> "正在规划路线${destination.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}"
                "NAVIGATING" -> {
                    val distance = state.optInt("remainMeters", -1)
                    val seconds = state.optInt("remainSeconds", -1)
                    val maneuverMeters = state.optInt("nextManeuverMeters", -1)
                    val maneuver = state.optString("nextInstruction").trim()
                    val maneuverText = when {
                        maneuverMeters < 0 || maneuver.isBlank() -> ""
                        else -> " · ${maneuverMeters.coerceAtLeast(0)}米后${maneuver.take(20)}"
                    }
                    val etaText = if (seconds >= 0) " · 约${((seconds + 59) / 60).coerceAtLeast(1)}分钟到达" else ""
                    val remainText = if (maneuverText.isBlank() && distance >= 0) " · 剩余约${((distance + 99) / 100) * 100}米" else ""
                    "导航至${destination.ifBlank { "目的地" }}$maneuverText$remainText$etaText"
                }
                "ARRIVED" -> "已到达${destination.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}"
                "FAILED" -> "导航未完成，请查看 Pilot"
                else -> null
            }
        }.getOrNull()
        return cachedNavNotification
    }

    fun refreshStatus() {
        // ★ 运行时已绑定也算就绪。EMUI 上 Settings.Secure 偶尔读不到/被裁剪，
        //   会出现「服务 Bound 了但界面说未开启」的假阴性（11-26 真机反馈）。
        accessibilityOn = AgentRuntime.connected ||
            AgentSettings.isAccessibilityEnabled(getApplication())
        notificationAccess = com.pilot.agent.service.NotificationInbox.isPermissionEnabled(getApplication())
        reminderNotificationsAllowed = android.os.Build.VERSION.SDK_INT < 33 ||
            getApplication<Application>().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (reminderNotificationsAllowed) com.pilot.agent.action.ReminderManager.rescheduleAll(getApplication())
        skillCount = AgentRuntime.skills.all().size
        refreshExtensions()
        // ★ cookie 存在但 neteaseNick 为空（App 重启后）时，查一次昵称。
        //   原来昵称只在扫码成功那一刻赋值，重启后设置页就一直显示"未登录"（老板反馈）。
        val cookie = AgentSettings.load().neteaseCookie
        if (cookie.isNotBlank() && neteaseNick.isBlank()) {
            neteaseNick = "已登录"   // 先给个兜底，别一直显示"未登录"
            viewModelScope.launch {
                val nick = withContext(Dispatchers.IO) {
                    com.pilot.agent.music.NeteaseApi.loginStatus(
                        AgentSettings.load().musicBaseUrl, cookie,
                    )
                }
                neteaseNick = nick ?: "已登录"
            }
        } else if (cookie.isBlank()) {
            neteaseNick = ""
        }

        val asr = AgentRuntime.asr
        asrReady = asr.ready
        asrNote = when {
            asr.ready -> "就绪 ${asr.statsText()}"
            !asr.abiSupported() -> "需 arm64"
            !asr.modelPresent() -> "缺模型"
            else -> "未加载"
        }
        llmReady = AgentRuntime.localLlm.ready
        voiceprintEnrolled = AgentRuntime.voiceprint.hasTemplate()
        val llm = AgentRuntime.localLlm
        llmNote = when {
            llm.ready -> "就绪"
            !llm.abiSupported() -> "需 arm64"
            !llm.modelPresent() -> "缺模型"
            else -> "待命"   // 懒加载，第一次用到才占那 300MB
        }
    }

    /** 第一次点麦克风时才建，避免启动就抢麦克风 */
    private fun ensureVoice() {
        if (voice != null) return
        voice = AgentRuntime.ensureVoiceInput()
        voice?.let { v ->
            // 单例复用：把回调换成当前 ViewModel 的
            v.onState = {
                voiceState = it
                dndRequested = v.dndRequested
            }
            v.onText = { text ->
                // ★ 去重：同一条内容短时间内重复到达只显示一次。
                //   长按录音松手时，VAD 会 flush 出一句；麦克风还在录，
                //   紧接着又切出一段内容相近的 → 用户看到"显示了两遍"（实测）。
                val now = System.currentTimeMillis()
                val dup = lastUserText == text.trim() && now - lastUserTextAt < 3000L
                if (dup) {
                    add(LogEntry.Kind.OBSERVE, "（重复内容已忽略）")
                } else {
                    lastUserText = text.trim()
                    lastUserTextAt = now
                }
                if (!dup) {
                    // ★ 「你休息一会 / 我先忙一会 / 别打扰我」→ 进免打扰：
                    //   退出聆听状态，直到用户再喊唤醒词或长按输入框。
                    if (IntentGate.isDoNotDisturb(text)) {
                        // 回一句再睡：让用户知道听到了、并且知道怎么叫醒它
                        add(LogEntry.Kind.OBSERVE, "进入免打扰（休眠）")
                        // 先睡再回：enterDoNotDisturb 会把 vad 清掉、停止聆听，
                        //   之后播报这句不会又被自己当成新指令听进去。
                        voice?.enterDoNotDisturb()
                        AgentRuntime.speaker.speak(DND_REPLY)
                    } else {
                        run(text)
                    }
                }
            }
            v.onError = { msg -> add(LogEntry.Kind.ERROR, msg) }
            v.onWake = { word ->
                add(LogEntry.Kind.OBSERVE, "唤醒「$word」，说吧")
                // 回应一声"我在"：让用户确认它醒了（原来只有一声 beep，不够明确）。
                // 用 speakQuiet：不占麦克风闸门，用户紧接着说的指令不会被丢掉。
                AgentRuntime.speaker.speakQuiet(WAKE_REPLY)
            }
            v.onVoiceprint = { score, pass ->
                voiceprintInfo = "%.2f".format(score) + if (pass) " 通过" else " 拒绝"
                add(
                    LogEntry.Kind.OBSERVE,
                    "声纹 ${"%.2f".format(score)} ${if (pass) "通过" else "不匹配，这句话会被丢弃"}",
                )
            }
        }
        if (voice != null) return
        // 兜底：理论上走不到（ensureVoiceInput 一定返回实例）
        voice = VoiceInput(
            context = getApplication(),
            asr = AgentRuntime.asr,
            wake = AgentRuntime.wakeDetector,
            gate = AgentRuntime.voiceprint,
            onState = { voiceState = it },
            onText = { text ->
                // 所有入口统一经过 run()，由当前模式决定助理或 Agent 链路。
                run(text)
            },
            onError = { msg -> add(LogEntry.Kind.ERROR, msg) },
            onWake = { word ->
                add(LogEntry.Kind.OBSERVE, "唤醒「$word」，说吧")
                AgentRuntime.speaker.speakQuiet(WAKE_REPLY)
            },
            onVoiceprint = { score, pass ->
                voiceprintInfo = "%.2f".format(score) + if (pass) " 通过" else " 拒绝"
                add(
                    LogEntry.Kind.OBSERVE,
                    "声纹 ${"%.2f".format(score)} ${if (pass) "通过" else "不匹配，这句话会被丢弃"}",
                )
            },
        ).also { v ->
            v.onEnrollSegment = { index, total -> enrollProgress = "$index/$total" }
            // 首句自动注册成功（清除声纹后说第一句话）
            v.onEnrolled = {
                voiceprintEnrolled = true
                add(LogEntry.Kind.OBSERVE, "声纹已记住（用你说的第一句话注册的），之后只认这个声音")
                refreshStatus()
            }
            v.onEnrollDone = { ok ->
                enrollRunning = false
                enrollResult = ok
                add(
                    LogEntry.Kind.OBSERVE,
                    if (ok) "声纹注册完成（3 句已合并入库）" else "声纹注册失败：${AgentRuntime.voiceprint.lastError ?: "样本不足"}",
                )
                refreshStatus()
            }
        }
        voice?.warmUp()
        refreshStatus()
    }

    /**
     * P1：VoiceInput 是进程级单例，回调默认指向本 ViewModel。
     *
     * Activity 销毁后 viewModelScope 已经取消。若单例还保留旧的 onText，
     * 前台服务仍能唤醒、麦克风也还在录，但指令会落进已取消的协程里静默丢失。
     * 这里把回调切换成运行时级后台执行（复用广播入口的那条链），
     * 保证「界面关了，语音照样能干活」。
     */
    override fun onCleared() {
        val v = voice ?: AgentRuntime.voiceInput
        v?.let { input ->
            input.onText = { text ->
                Dbg.i(TAG, "后台语音指令：$text")
                com.pilot.agent.service.runBackgroundGoal(
                    getApplication(),
                    text,
                    "voice-background",
                )
            }
            // 其余回调都指向已销毁的 ViewModel，全部换成空实现/日志，
            // 防止 Compose 状态在销毁后被无意义地写入。
            input.onState = { }
            input.onError = { msg -> Dbg.w(TAG, "后台语音错误：$msg") }
            input.onWake = { word -> Dbg.i(TAG, "后台唤醒「$word」") }
            input.onVoiceprint = { _, _ -> }
            input.onEnrollSegment = { _, _ -> }
            input.onEnrolled = { }
            input.onEnrollDone = { }
        }
        voice = null
        super.onCleared()
    }

    /**
     * 扫码登录网易云。
     *
     * 不登录只能播 30 秒试听，VIP 歌直接 `url: null`——实测「晴天」就是这样。
     * cookie 存本地（SharedPreferences），不落到服务器上。
     */
    fun startQrLogin() {
        loginJob?.cancel()
        qrBitmap = null
        loginTip = "正在获取二维码…"
        loginJob = viewModelScope.launch {
            val preferredBase = settings.musicBaseUrl
            com.pilot.agent.Dbg.i("PilotMusic", "扫码登录：创建固定 API 会话，base=$preferredBase")
            val session = withContext(Dispatchers.IO) {
                com.pilot.agent.music.NeteaseApi.qrSession(preferredBase, settings.musicFallbackUrl)
            }
            if (session == null) {
                loginTip = "连接不到音乐服务，请检查主/备用地址"
                com.pilot.agent.Dbg.w("PilotMusic", "扫码登录：qrKey 为空")
                return@launch
            }
            val base = session.baseUrl
            val key = session.key
            val img = withContext(Dispatchers.IO) { com.pilot.agent.music.NeteaseApi.qrImage(base, key) }
            if (img == null) {
                loginTip = "二维码生成失败"
                com.pilot.agent.Dbg.w("PilotMusic", "扫码登录：qrImage 为空")
                return@launch
            }
            qrBitmap = decodeQr(img)
            loginTip = "用网易云 App 扫码（3 分钟内有效）"
            com.pilot.agent.Dbg.i("PilotMusic", "扫码登录：二维码就绪，开始轮询")

            // 轮询到成功或过期，最多 3 分钟（腾讯/网易二维码普遍 2~3 分钟）
            repeat(90) {
                delay(2000)
                val (state, cookie) = withContext(Dispatchers.IO) {
                    com.pilot.agent.music.NeteaseApi.qrCheck(base, key)
                }
                com.pilot.agent.Dbg.i("PilotMusic", "扫码状态 $state")
                when (state) {
                    com.pilot.agent.music.NeteaseApi.QrState.SUCCESS -> {
                        val c = cookie
                        if (c.isNullOrBlank()) {
                            loginTip = "登录成功但没拿到 cookie"
                            return@launch
                        }
                        settings = settings.copy(neteaseCookie = c)
                        AgentSettings.save(settings)
                        val nick = withContext(Dispatchers.IO) {
                            com.pilot.agent.music.NeteaseApi.loginStatus(base, c)
                        }
                        neteaseNick = nick ?: "已登录"
                        loginTip = "登录成功：${neteaseNick}"
                        qrBitmap = null
                        refreshStatus()
                        add(LogEntry.Kind.OBSERVE, "网易云登录成功：${neteaseNick}")
                        return@launch
                    }

                    com.pilot.agent.music.NeteaseApi.QrState.EXPIRED -> {
                        loginTip = "二维码过期，重新获取"
                        qrBitmap = null
                        return@launch
                    }

                    com.pilot.agent.music.NeteaseApi.QrState.WAITING_CONFIRM -> loginTip = "已扫描，请在手机上确认"
                    com.pilot.agent.music.NeteaseApi.QrState.NETWORK_ERROR ->
                        loginTip = "网络短暂中断，正在重试…"

                    com.pilot.agent.music.NeteaseApi.QrState.ERROR -> loginTip = "等待服务响应…"

                    else -> Unit // WAITING_SCAN，继续等
                }
            }
            loginTip = "等待超时"
            qrBitmap = null
        }
    }

    fun cancelQrLogin() {
        loginJob?.cancel()
        qrBitmap = null
        loginTip = ""
    }

    private fun decodeQr(dataUrl: String): android.graphics.Bitmap? = runCatching {
        val pure = dataUrl.substringAfter("base64,", dataUrl)
        val bytes = android.util.Base64.decode(pure, android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /** 退出网易云登录 */
    fun logoutNetease() {
        settings = settings.copy(neteaseCookie = "")
        AgentSettings.save(settings)
        neteaseNick = ""
        loginTip = ""
        add(LogEntry.Kind.OBSERVE, "已退出网易云登录")
        refreshStatus()
    }

    /** 待命开关：常驻听唤醒词 */
    fun toggleWake() {
        ensureVoice()
        val v = voice ?: return
        if (v.isWakeEnabled) {
            v.stop()
            wakeOn = false
            settings = AgentSettings.load().copy(wakeEnabled = false)
            AgentSettings.save(settings)
            com.pilot.agent.service.PilotForegroundService.stopForWake(getApplication())
            lastNotificationStatus = null
            add(LogEntry.Kind.OBSERVE, "已退出待命")
        } else {
            val serviceStarted = com.pilot.agent.service.PilotForegroundService.startForWake(getApplication())
            wakeOn = serviceStarted && v.startWake()
            if (!wakeOn && serviceStarted) com.pilot.agent.service.PilotForegroundService.stopForWake(getApplication())
            if (wakeOn) {
                settings = AgentSettings.load().copy(wakeEnabled = true)
                AgentSettings.save(settings)
                add(LogEntry.Kind.OBSERVE, "已进入待命，说「${settings.wakeWordList().first()}」唤醒")
            } else if (!serviceStarted) {
                add(LogEntry.Kind.ERROR, "语音待命未启动：请授予麦克风权限后重试")
            }
        }
        refreshStatus()
    }

    /** 用刚才说的那段音频注册声纹（快捷路径，引导式注册用 startEnrollGuide） */
    fun enrollVoiceprint() {
        ensureVoice()
        val ok = voice?.enrollVoiceprint() == true
        add(
            LogEntry.Kind.OBSERVE,
            if (ok) "声纹已注册，之后只认这个声音" else "声纹注册失败：${AgentRuntime.voiceprint.lastError ?: "录音不足"}",
        )
        refreshStatus()
    }

    /**
     * 引导式声纹注册：弹窗提示口令 → 用户连说 3 句 → 自动合并入库。
     * 旧实现要用户先随便说一句话再点按钮，音频全凭运气；现在采集会话
     * 专管样本，VAD 分段直接计数，不足 3 句可以取消重来。
     */
    fun startEnrollGuide() {
        if (!AgentRuntime.voiceprint.engineAvailable) {
            enrollRunning = false
            enrollResult = false
            enrollProgress = ""
            add(LogEntry.Kind.ERROR, "声纹引擎暂不可用：sherpa 的声纹提取在真机上会崩，已暂停注册。唤醒与打断仍按\u201c声纹不可用则放行\u201d工作。")
            return
        }
        ensureVoice()
        enrollProgress = "0/3"
        enrollResult = null
        enrollRunning = voice?.startEnroll() == true
        if (!enrollRunning) {
            enrollResult = false
            add(LogEntry.Kind.ERROR, "声纹注册启动失败：麦克风不可用")
        }
    }

    fun cancelEnrollGuide() {
        voice?.cancelEnroll()
        enrollRunning = false
        enrollProgress = ""
        enrollResult = null
    }

    fun finishEnrollDialog() {
        cancelEnrollGuide()
    }

    // ---------- 沙箱挂载目录（SAF） ----------

    /** 当前挂载列表（name → 宿主路径）。 */
    fun sandboxMounts(): List<Pair<String, String>> =
        com.pilot.agent.sandbox.SandboxStores.mountedFolders(getApplication())
            .entries.value.map { it.name to (it.resolvedHostPath ?: "(路径未解析)") }

    /** 挂一个 SAF 目录；挂载表更新后会重建后续 shell 的 PRoot bind 参数。 */
    fun mountSandboxFolder(treeUri: android.net.Uri, displayName: String) {
        val store = com.pilot.agent.sandbox.SandboxStores.mountedFolders(getApplication())
        store.addFromTreeUri(displayName, treeUri)
            .onSuccess { entry ->
                add(
                    LogEntry.Kind.OBSERVE,
                    "已挂载「${entry.name}」→ /var/minis/mounts/${entry.name}，热壳已自动重启",
                )
            }
            .onFailure { add(LogEntry.Kind.ERROR, "挂载失败：${it.message}") }
    }

    /**
     * 手动添加挂载目录（直接给 POSIX 路径）。
     *
     * ★ 为什么要有这条：SAF 的 OpenDocumentTree 在部分 ROM（华为）上
     *   不弹选择器或返回空 uri，"添加挂载目录"点了没反应（实测）。
     *   而 proot bind mount 真正需要的只是宿主 POSIX 路径，
     *   所以直接让用户填路径更稳。
     */
    fun mountSandboxPath(path: String, displayName: String) {
        val p = path.trim()
        if (!p.startsWith("/")) {
            add(LogEntry.Kind.ERROR, "路径要以 / 开头，例如 /storage/emulated/0/Download")
            return
        }
        val store = com.pilot.agent.sandbox.SandboxStores.mountedFolders(getApplication())
        store.addFromHostPath(displayName, p).onSuccess { entry ->
            add(
                LogEntry.Kind.OBSERVE,
                "已挂载「${entry.name}」→ ${entry.resolvedHostPath}，热壳已自动重启",
            )
        }.onFailure {
            add(LogEntry.Kind.ERROR, "挂载失败：${it.message}")
        }
    }

    fun unmountSandboxFolder(name: String) {
        com.pilot.agent.sandbox.SandboxStores.mountedFolders(getApplication()).removeByName(name)
        add(LogEntry.Kind.OBSERVE, "已移除挂载「$name」")
    }

    fun clearVoiceprint() {
        voiceprintEnrolled = false
        enrollProgress = ""
        enrollResult = null
        enrollRunning = false
        AgentRuntime.voiceprint.clearTemplate()
        voiceprintInfo = ""
        add(LogEntry.Kind.OBSERVE, "声纹已清除，下次说唤醒词时用第一句话重新注册")
        refreshStatus()
    }

    /** 按住说话：按下开始录音（不切换待命状态）。 */
    /**
     * 按住说话：按下开始录音。
     * @return 是否真的开始录了（上一句还在识别时会返回 false，UI 好撤销"正在听"）
     */
    fun startVoiceHold(): Boolean {
        ensureVoice()
        val v = voice ?: return false
        // ★ 开始语音输入前暂停音乐：麦克风要录人声，
        //   放着歌的话 ASR 会把歌当人声（还容易自激），人也听不清自己说话。
        //   暂停而不是停止——听完还能接着放。
        pauseMusicForVoice()
        if (v.state == VoiceInput.State.LISTENING || v.state == VoiceInput.State.RECOGNIZING) {
            add(LogEntry.Kind.OBSERVE, "上一句还在处理，等一下再按住说")
            return false
        }
        v.startListening(resumeWake = wakeOn)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AgentRuntime.localLlm.ensureReady() }
        }
        return true
    }

    /** 按住说话：松手结束录音并送识别（识别完由 onText 自动执行）。 */
    fun finishVoiceHold() {
        voice?.finishListening()
        resumeMusicAfterVoice()
    }

    /** 语音输入前暂停音乐。 */
    private fun pauseMusicForVoice() {
        val mp = com.pilot.agent.music.MusicPlayer.get(getApplication())
        if (mp.isPlaying) {
            mp.pause()
            musicPausedByVoice = true
        }
    }

    /** 语音输入结束后恢复音乐（只在"是语音暂停的"时才恢复）。 */
    private fun resumeMusicAfterVoice() {
        if (!musicPausedByVoice) return
        musicPausedByVoice = false
        com.pilot.agent.music.MusicPlayer.get(getApplication()).resume()
    }

    @Volatile
    private var musicPausedByVoice = false

    fun toggleVoice() {
        ensureVoice()
        val v = voice ?: return
        when (v.state) {
            VoiceInput.State.LISTENING -> v.finishListening()
            VoiceInput.State.RECOGNIZING -> Unit
            else -> {
                // 待命中点按钮：直接听一句，听完回到待命
                v.startListening(resumeWake = wakeOn)
                // 说话这段时间正好够后台把 LLM 加载完（实测 900ms）
                viewModelScope.launch {
                    withContext(Dispatchers.IO) { AgentRuntime.localLlm.ensureReady() }
                    refreshStatus()
                }
            }
        }
    }

    /**
     * 闲聊/提问走这里：**交给模型回答**，不碰屏幕。
     *
     * ★ 老板 11-10：「你是谁这样的问题不要走规则，模型交给模型回答」
     *   所以顺序是：云端模型（能力强）→ 端侧小模型（离线兜底）。
     *   之前只有端侧 0.3B，问「你能做什么」会答得干巴巴甚至答非所问。
     *
     * 端侧小模型不知道当前时间，prompt 里必须注入，否则问「今天几号」
     * 会答成占位符。
     */
    private fun chat(text: String) {
        viewModelScope.launch {
            add(LogEntry.Kind.THINK, "这句不是操作指令，交给模型回答")
            val reply = withContext(Dispatchers.IO) {
                val now = java.text.SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA)
                    .format(java.util.Date())
                // 1) 云端模型优先
                val cloud = cloudChat(text, now)
                if (!cloud.isNullOrBlank()) return@withContext cloud
                // 2) 端侧小模型兜底（离线 / 没配 key）
                localChat(text, now)
            }
            if (reply.isNullOrBlank()) {
                add(LogEntry.Kind.OBSERVE, "模型不可用，这句没法回答。")
            } else {
                val clean = reply.trim().take(200)
                // 存进历史，供下一轮当上下文
                chatHistory.addLast(text to clean)
                while (chatHistory.size > 40) chatHistory.removeFirst()
                add(LogEntry.Kind.DONE, clean)
                AgentRuntime.speaker.speak(clean)
            }
            refreshStatus()
        }
    }

    /** 云端模型回答（OpenAI 兼容接口）。 */
    private suspend fun cloudChat(text: String, now: String): String? {
        val s = AgentSettings.load()
        if (!s.cloudReady) return null
        return try {
            val memoryContext = AgentRuntime.localTools.memory.promptContext(text, 5)
            val memoryPrompt = if (memoryContext.isBlank()) "" else {
                "\n与本轮相关的用户长期记忆（仅用于回答当前问题）：\n$memoryContext"
            }
            val body = org.json.JSONObject().apply {
                put("model", s.model)
                put("stream", false)
                // 自动路由后思考强度始终生效，首页状态行显示的就是它。
                put(
                    "reasoning_effort",
                    s.reasoningEffort.takeIf { it in AgentSettings.REASONING_EFFORTS }
                        ?: AgentSettings.DEFAULT_REASONING_EFFORT,
                )
                put("messages", org.json.JSONArray().apply {
                    put(org.json.JSONObject().apply {
                        put("role", "system")
                        put(
                            "content",
                            "你是手机助手 Pilot，运行在这台安卓手机上。现在是 $now。" +
                                "用一到两句话回答，简短口语化，不要用列表。" +
                                "你能开关应用、查天气、股票和火车余票、播音乐、做导航，" +
                                "也能在明确请求下查看设备状态、读取指定文件和管理本机长期记忆。" +
                                IntentGate.SELF_KNOWLEDGE +
                                memoryPrompt +
                                "下面是最近的对话，接住其中的指代和上下文。",
                        )
                    })
                    // 注入最近 N 轮上下文（问答交替）
                    val turns = s.chatContextTurns.coerceIn(0, 20)
                    if (turns > 0) {
                        chatHistory.takeLast(turns).forEach { (q, a) ->
                            put(org.json.JSONObject().apply {
                                put("role", "user")
                                put("content", q)
                            })
                            put(org.json.JSONObject().apply {
                                put("role", "assistant")
                                put("content", a)
                            })
                        }
                    }
                    put(org.json.JSONObject().apply {
                        put("role", "user")
                        put("content", text)
                    })
                })
            }.toString()
            val req = okhttp3.Request.Builder()
                .url(s.baseUrl.trimEnd('/') + "/chat/completions")
                .addHeader("Authorization", "Bearer ${s.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(okhttp3.RequestBody.create("application/json".toMediaType(), body))
                .build()
            com.pilot.agent.agent.CloudHttp.client.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) {
                    Dbg.w(TAG, "云端对话失败 http=${resp.code}")
                    return null
                }
                val json = org.json.JSONObject(resp.body?.string().orEmpty())
                json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
            }
        } catch (t: Throwable) {
            rethrowIfCancellation(t)
            Dbg.w(TAG, "云端对话异常：${t.message}")
            null
        }
    }

    /** 端侧小模型回答（离线兜底）。 */
    private suspend fun localChat(text: String, now: String): String? {
        val llm = AgentRuntime.localLlm
        if (!llm.ensureReady()) return null
        val noThink = if (llm.wantsNoThink) "/no_think" else ""
        val memoryContext = AgentRuntime.localTools.memory.promptContext(text, 5)
        val memoryPrompt = if (memoryContext.isBlank()) "" else "相关长期记忆：\n$memoryContext\n"
        return llm.generate(
            "system\n你是手机助手 Pilot，运行在这台安卓手机上，现在是 $now。" +
                "能开关应用、查天气股票和火车余票、播音乐、跑命令、做导航。" +
                IntentGate.SELF_KNOWLEDGE +
                memoryPrompt +
                "回答简短口语化，不超过 25 个字。$noThink\n" +
                "user\n$text\nassistant\n"
        )
    }

    /**
     * 自动路由的第一步：规则选能力 → 确定性抽参（必要时端侧模型补值）→ 本机执行。
     *
     * 有规则、参数能抽全的指令直接本机执行，不调云端、不进 Agent；
     * 没有规则、补不出参数或没有执行器时返回 false，由调用方交给 Agent。
     *
     * 决策与执行都放到后台：确定性路径不碰模型，需要补值的路径会先跑 0.3B。
     */
    private fun tryRunFastPath(
        text: String,
        allowConfirmation: Boolean,
    ): Boolean {
        if (!AssistantFastPath.isEnabled()) return false
        val plan = AssistantFastPath.plan(getApplication(), text, "ui") ?: return false

        // 需要端侧模型补值：先认领，后台补；补不出来就交给 Agent。
        if (plan.invocation == null) {
            add(LogEntry.Kind.THINK, "端侧理解参数…")
            running = true
            justSent = true
            activeTaskLabel = "本机能力"
            publishNotificationStatus()
            viewModelScope.launch {
                val completed = runCatching {
                    AssistantFastPath.complete(getApplication(), plan)
                }.getOrNull()
                val invocation = completed?.invocation
                if (completed == null || invocation == null) {
                    running = false
                    justSent = false
                    activeTaskLabel = ""
                    publishNotificationStatus()
                    // 端侧没补出来 —— 自动路由交给 Agent，不再回退遗留匹配器。
                    dispatchToAgent(text)
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    executeFastPath(completed, allowConfirmation)
                }
                finishFastPath(completed, result)
            }
            return true
        }

        val invocation = plan.invocation ?: return false
        add(LogEntry.Kind.THINK, "调用本机能力…")
        running = true
        justSent = true
        activeTaskLabel = "本机能力"
        publishNotificationStatus()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                executeFastPath(plan, allowConfirmation)
            }
            finishFastPath(plan, result)
        }
        return true
    }

    /**
     * P1：执行快路径调用。
     *
     * 破坏性能力（`plan.needsConfirmation` = HIGH 风险或路由强制确认）即使所在
     * 工具组被用户设成 ALLOW，也必须在界面上再问一次——否则改一个开关就能让
     * 覆盖文件 / 删除这类操作静默执行。组策略是 ASK 时仍由
     * [com.pilot.agent.tool.LocalToolRuntime] 统一询问，不会重复弹两次。
     */
    private suspend fun executeFastPath(
        plan: FastPathPlan,
        allowConfirmation: Boolean,
    ): com.pilot.agent.tool.ToolResult {
        val invocation = plan.invocation
            ?: return com.pilot.agent.tool.ToolResult.error("没有可执行的调用。")
        val policyAllows = AgentRuntime.localTools.policyFor(invocation.group) == ToolPolicy.ALLOW
        if (plan.needsConfirmation && policyAllows) {
            val ok = allowConfirmation &&
                askToolConfirmation(invocation.confirmation, invocation.risk())
            if (!ok) return com.pilot.agent.tool.ToolResult.denied("已取消 ${invocation.summary}。")
        }
        return AgentRuntime.localTools.execute(invocation) { question, risk ->
            if (allowConfirmation) askToolConfirmation(question, risk) else false
        }
    }

    /** 快路径执行的收尾：恢复状态、回执、播报、刷新。 */
    private fun finishFastPath(plan: FastPathPlan, result: com.pilot.agent.tool.ToolResult) {
        running = false
        justSent = false
        activeTaskLabel = ""
        publishNotificationStatus()
        val reply = ResultFormatter.format(plan.capability, plan.action, plan.args, result)
        val kind = if (result.status == ToolStatus.SUCCESS) LogEntry.Kind.DONE
        else LogEntry.Kind.ERROR
        add(kind, reply.text)
        FastPathTrace.handled(
            getApplication(),
            plan.capability,
            plan.action,
            result.status.name,
            reply.text.length,
        )
        AgentRuntime.speaker.speak(reply.spoken)
        refreshStatus()
    }

    private suspend fun askToolConfirmation(question: String, risk: Risk): Boolean {
        val id = System.nanoTime()
        val deferred = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            pendingToolConfirm = PendingToolConfirm(id, deferred)
            confirm = ConfirmUi(id, question, risk)
            AgentRuntime.speaker.speak(question)
        }
        val answer = withTimeoutOrNull(60_000L) { deferred.await() } ?: false
        withContext(Dispatchers.Main) {
            val current = pendingToolConfirm
            if (current?.id == id) pendingToolConfirm = null
            if (confirm?.id == id) confirm = null
        }
        return answer
    }

    fun run(goal: String) {
        val g = goal.trim()
        if (g.isEmpty() || running) return
        reloadSettings()
        // 展示层复用遗留匹配器的等价文案（SSH 等命令），但不再执行它。
        val displayTool = AgentRuntime.localTools.match(g)
        // 语音回调、文字发送和本地工具都从这里进入。统一在同一处追加，
        // 避免操作指令被上游和 run() 各写一次而在聊天框显示两遍。
        addUserMessageIfNeeded(AgentRuntime.localTools.displayTextFor(g, displayTool))
        dispatchRequest(g)
    }

    /**
     * 自动路由：快路径有规则就本机直执行；没有规则直接交给 Agent，
     * 由模型决定回答还是执行。不再让遗留匹配器插在中间。
     */
    private fun dispatchRequest(g: String) {
        // 1) 无障碍「带路」的肯定回答（先问，不默认跳设置页；m-20261127-105）。
        if (pendingA11yGuide) {
            pendingA11yGuide = false
            if (com.pilot.agent.action.CommandSemantics.isAffirmative(g)) {
                add(LogEntry.Kind.OBSERVE, "已打开系统设置的无障碍页，请找到 Pilot 并开启。")
                openAccessibilitySettings()
                return
            }
        }
        // 2) 设置页带路：loop 不存在时由 ViewModel 自己记着（m-20261127-104）。
        pendingViewGuide?.let { guide ->
            pendingViewGuide = null
            if (com.pilot.agent.action.CommandSemantics.isAffirmative(g)) {
                launchGuide(guide)
                return
            }
        }
        // 3) 无障碍缺失或服务未连上时，不需要屏幕的命令直接在本机完成。
        if (tryRunFastPath(g, allowConfirmation = true)) return
        if (!screenReady() && com.pilot.agent.action.ScreenRequirements.requirement(g) ==
            com.pilot.agent.action.ScreenRequirement.NONE
        ) {
            if (runScreenless(g)) return
        }
        if (tryRunLocalTool(g)) return
        dispatchToAgent(g)
    }

    /** 读屏能力是否就绪（权限 + 服务已绑定）。 */
    private fun screenReady(): Boolean = accessibilityOn && AgentRuntime.loop != null

    private fun launchGuide(guide: com.pilot.agent.action.CommandTarget) {
        running = true
        justSent = true
        activeTaskLabel = "打开${guide.label}设置页"
        publishNotificationStatus()
        viewModelScope.launch {
            val outcome = com.pilot.agent.action.VerifiedLauncher.launch(
                getApplication(),
                com.pilot.agent.action.CommandSemantics.guideIntent(guide),
                expectPackage = null,
                timeoutMs = 4_000L,
            )
            add(LogEntry.Kind.OBSERVE, com.pilot.agent.action.CommandSemantics.guideOpenedText(guide, outcome))
            running = false
            justSent = false
            activeTaskLabel = ""
            publishNotificationStatus()
        }
    }

    /**
     * 不需要屏幕的本地能力（m-20261127-105）：日程 / 提醒 / 待办 / B 类系统命令 / 查询 / 音乐。
     * @return true = 已经接管（无论成功失败都会给回执）
     */
    private fun runScreenless(goal: String): Boolean {
        val app = getApplication<Application>()
        val g = goal.trim()
        val requirements = com.pilot.agent.action.ScreenRequirements
        when {
            com.pilot.agent.action.CalendarTool.handlesCancel(g) ->
                answerScreenless { com.pilot.agent.action.CalendarTool.cancelLastCreated(app) }
            com.pilot.agent.action.CalendarTool.handles(g) ->
                answerScreenless { com.pilot.agent.action.CalendarTool.create(app, g) }
            com.pilot.agent.action.ReminderManager.handles(g) ->
                answerScreenless { com.pilot.agent.action.ReminderManager.handle(app, g) }
            com.pilot.agent.action.PersonalTodoStore.handles(g) ->
                answerScreenless { com.pilot.agent.action.PersonalTodoStore.handle(g) }
            com.pilot.agent.query.QueryEngine.canHandle(g) ->
                answerScreenless {
                    com.pilot.agent.query.QueryEngine.handle(g)
                        ?: "没能查到「$g」的结果，可能是网络不可用或暂时没有相关内容。"
                }
            requirements.isMusicCommand(g) ->
                answerScreenless { playMusicScreenless(g) }
            else -> {
                val cmd = com.pilot.agent.action.CommandSemantics.classify(g)
                if (cmd == null || cmd.kind != com.pilot.agent.action.CommandKind.RESULT) return false
                answerScreenless {
                    when (val r = com.pilot.agent.action.SystemCommandRunner.run(
                        com.pilot.agent.action.SystemOps(app), cmd, g,
                    )) {
                        null -> null
                        is com.pilot.agent.action.SystemRunOutcome.Done -> r.message
                        is com.pilot.agent.action.SystemRunOutcome.NeedsGuide -> {
                            pendingViewGuide = r.target
                            r.message
                        }
                    }
                }
            }
        }
        return true
    }

    /** 统一的「无界面本机执行」收尾：走同一条 DONE + 播报路径。 */
    private fun answerScreenless(block: suspend () -> String?) {
        running = true
        justSent = true
        activeTaskLabel = "本机执行"
        publishNotificationStatus()
        viewModelScope.launch {
            val message = try {
                block()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Dbg.w(TAG, "本机执行失败：${t.message}")
                "执行失败：${t.javaClass.simpleName}"
            }
            running = false
            justSent = false
            activeTaskLabel = ""
            publishNotificationStatus()
            dispatchJob = null
            val clean = message?.trim().orEmpty().ifEmpty { "没有完成。" }
            add(LogEntry.Kind.DONE, clean)
            AgentRuntime.speaker.speak(clean)
            refreshStatus()
        }
    }

    /** 无障碍未连上时的最小音乐播放：搜索/热歌 → 直接拉起播放器。 */
    private suspend fun playMusicScreenless(goal: String): String {
        val s = AgentSettings.load()
        val query = goal
            .replace(Regex("^(帮我|给我|请)?(播放|放首|放一下|来首|来一首|放歌|听歌)"), "")
            .replace(Regex("(这首歌|的歌|歌曲|音乐|吧|呗)$"), "")
            .trim()
        val songs = withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                com.pilot.agent.music.NeteaseApi.chartSongs(s.musicBaseUrl, 3778678L, s.neteaseCookie, 20)
            } else {
                com.pilot.agent.music.NeteaseApi.search(s.musicBaseUrl, query, 5, s.neteaseCookie)
            }
        }
        if (songs.isEmpty()) return "没搜到「${query.ifBlank { "热门" }}」，检查音乐服务或网络后再说一次。"
        val tracks = songs.map { song ->
            com.pilot.agent.music.MusicPlayer.Track(
                title = song.name,
                artist = song.artist,
                resolver = {
                    com.pilot.agent.music.NeteaseApi.playUrl(s.musicBaseUrl, song.id, s.neteaseCookie)
                        ?.let { com.pilot.agent.music.MusicPlayer.Source.Remote(it) }
                },
                lyricProvider = {
                    com.pilot.agent.music.NeteaseApi.lyrics(s.musicBaseUrl, song.id, s.neteaseCookie)
                },
            )
        }
        val started = withContext(Dispatchers.Main.immediate) {
            com.pilot.agent.music.MusicPlayer.get(getApplication()).playQueue(tracks)
        }
        return if (started) "正在播放：${songs.first().display}" else "音乐播放失败，检查音乐服务后再说一次。"
    }

    /**
     * 快路径没有规则、但本地工具匹配器认得的指令（文件复制/移动/删除/分享/打开/列目录、
     * SSH、网络/定位/蓝牙/使用时长查询、记忆删除等）在这里兜底执行。
     *
     * 这些能力早就注册在 [com.pilot.agent.tool.LocalToolRuntime]，但自动路由只走
     * 快路径或 Agent，导致它们永远执行不到。匹配器自带讨论/疑问句防护，
     * 写/删类操作仍会经过工具策略确认。
     */
    private fun tryRunLocalTool(text: String): Boolean {
        val invocation = AgentRuntime.localTools.match(text) ?: return false
        add(LogEntry.Kind.THINK, "调用本机工具…")
        running = true
        justSent = true
        activeTaskLabel = invocation.summary
        publishNotificationStatus()
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    AgentRuntime.localTools.execute(invocation) { question, risk ->
                        askToolConfirmation(question, risk)
                    }
                }
            }.getOrElse { error ->
                Dbg.e(TAG, "本地工具执行失败：${invocation.name}", error)
                com.pilot.agent.tool.ToolResult.error(
                    "工具执行失败：${error.message?.take(120) ?: error.javaClass.simpleName}",
                )
            }
            running = false
            justSent = false
            activeTaskLabel = ""
            publishNotificationStatus()
            val kind = if (result.status == ToolStatus.SUCCESS) LogEntry.Kind.DONE
            else LogEntry.Kind.ERROR
            add(kind, result.message.ifBlank { result.spoken })
            AgentRuntime.speaker.speak(result.spoken)
            refreshStatus()
        }
        return true
    }

    private fun dispatchToAgent(g: String) {
        val kind = IntentGate.classify(g)
        Dbg.i(TAG, "dispatch intent=${kind.name.lowercase()} route=auto->agent")
        routeAgentRequest(g, kind)
    }

    /**
     * Agent 模式先让模型决定回答还是执行。关键词分类只作为云端不可用、
     * 请求失败或响应无法解析时的安全兜底。
     */
    private fun routeAgentRequest(
        text: String,
        fallbackKind: IntentGate.Kind,
    ) {
        if (running) return
        // 强查询（新闻/资讯/网页搜索，且未指定 App、未要求“给我看”）优先走查询通道：
        // 规则判定优先于模型的执行判定，避免被当成「打开浏览器去搜」。
        if (IntentGate.isStrongQuery(text)) {
            runStrongQuery(text)
            return
        }
        val current = AgentSettings.load()
        if (!current.cloudReady) {
            Dbg.i(TAG, "agent router fallback: cloud unavailable")
            fallbackAgentDispatch(text, fallbackKind)
            return
        }

        running = true
        justSent = true
        activeTaskLabel = "Agent 判断目标"
        publishNotificationStatus()
        add(LogEntry.Kind.THINK, "Agent 正在理解目标并选择回答或执行…")
        dispatchJob = viewModelScope.launch {
            val now = java.text.SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date())
            val memory = AgentRuntime.localTools.memory.promptContext(text, 5)
            val decision = try {
                AgentIntentRouter { AgentSettings.load() }
                    .decide(text, chatHistory.toList(), memory, now)
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                Dbg.w(TAG, "agent router unavailable: ${t.message}")
                null
            }

            running = false
            justSent = false
            activeTaskLabel = ""
            publishNotificationStatus()
            dispatchJob = null

            if (decision == null) {
                fallbackAgentDispatch(text, fallbackKind)
                return@launch
            }
            if (decision.execute) {
                if (decision.thought.isNotBlank()) {
                    add(LogEntry.Kind.THINK, "Agent 判断：${decision.thought}")
                }
                Dbg.i(TAG, "agent router -> agent thought=${decision.thought}")
                startAgent(text, AssistantMode.AGENT)
                return@launch
            }

            val answer = decision.answer?.trim().orEmpty()
            if (answer.isBlank()) {
                fallbackAgentDispatch(text, fallbackKind)
                return@launch
            }
            Dbg.i(TAG, "agent router -> reply chars=${answer.length} thought=${decision.thought}")
            finishDirectAnswer(text, answer)
        }
    }

    /**
     * 强查询直达：不问模型「要不要执行」，直接走 QueryEngine（新闻→news_search，一般网页→web_search）。
     * 查不到时如实说明，不转去打开浏览器执行。
     */
    private fun runStrongQuery(text: String) {
        if (running) return
        running = true
        justSent = true
        activeTaskLabel = "查询"
        publishNotificationStatus()
        add(LogEntry.Kind.THINK, "正在查询…")
        dispatchJob = viewModelScope.launch {
            val answer = try {
                com.pilot.agent.query.QueryEngine.handle(text)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Dbg.w(TAG, "强查询失败：${t.message}")
                null
            }
            running = false
            justSent = false
            activeTaskLabel = ""
            publishNotificationStatus()
            dispatchJob = null
            val clean = answer?.trim()?.takeIf { it.isNotBlank() }
                ?: "没能查到「$text」的最新结果，可能是网络不可用或暂时没有相关内容。"
            finishDirectAnswer(text, clean)
        }
    }

    private fun fallbackAgentDispatch(
        text: String,
        kind: IntentGate.Kind,
    ) {
        when (kind) {
            IntentGate.Kind.CHAT -> chat(text)
            IntentGate.Kind.QUERY, IntentGate.Kind.TASK ->
                startAgent(text, AssistantMode.AGENT)
        }
    }

    private fun finishDirectAnswer(question: String, answer: String) {
        val clean = answer.trim().take(1_200)
        chatHistory.addLast(question to clean)
        while (chatHistory.size > 40) chatHistory.removeFirst()
        add(LogEntry.Kind.DONE, clean)
        AgentRuntime.speaker.speak(clean)
        refreshStatus()
    }

    private fun startAgent(g: String, effectiveMode: AssistantMode) {
        if (running) return
        reloadSettings()
        refreshStatus()
        if (!accessibilityOn &&
            com.pilot.agent.action.ScreenRequirements.needsScreen(g)
        ) {
            // 只有真要读屏的动作才拦（m-20261127-105）。先问要不要带路，**不默认跳设置页**。
            Dbg.w(TAG, "该动作需要读屏，无障碍未开启：goalChars=${g.length}")
            val prompt = "这个动作要读屏幕，需要先开启 Pilot 系统操作权限。要我现在带你到设置页吗？回复「好」就行。"
            add(LogEntry.Kind.DONE, prompt)
            pendingA11yGuide = true
            val now = System.currentTimeMillis()
            if (now - lastAccessibilityPromptAt > 5_000L) {
                lastAccessibilityPromptAt = now
                AgentRuntime.speaker.speak(prompt)
            }
            refreshStatus()
            return
        }
        if (!accessibilityOn) {
            // 不需要屏幕但本机没法直接处理（不该走到这里）：如实说明，不弹设置页。
            Dbg.w(TAG, "无障碍未开启，且本机无法直接执行：goalChars=${g.length}")
            add(LogEntry.Kind.ERROR, "这个请求需要读屏幕才能继续，先开启 Pilot 系统操作权限再说一次。")
            refreshStatus()
            return
        }
        if (AgentRuntime.loop == null) {
            // ★ 权限开着但没有 loop = 服务还没绑定（进程刚拉起/被系统回收后最常见），
            //   原来跟"权限未开"共用一条提示，导致用户明明开了权限还被叫去开权限。
            //   先静默等一次重连，连不上再给准确的提示和恢复办法。
            Dbg.w(TAG, "操作权限已开，但无障碍服务尚未连接，等 1.2s 重连：goalChars=${g.length}")
            if (loopRetryGoal == null) {
                loopRetryGoal = g to effectiveMode
                viewModelScope.launch {
                    delay(1_200)
                    val pending = loopRetryGoal
                    loopRetryGoal = null
                    when {
                        pending == null -> Unit
                        AgentRuntime.loop != null -> startAgent(pending.first, pending.second)
                        else -> {
                            Dbg.e(TAG, "重试后无障碍服务仍未连接，提示重新开启服务")
                            val prompt = "操作服务还没连上。请到 设置→无障碍→Pilot 关闭再打开，或重启 Pilot 后再说一次。"
                            add(LogEntry.Kind.ERROR, prompt)
                            val now = System.currentTimeMillis()
                            if (now - lastAccessibilityPromptAt > 5_000L) {
                                lastAccessibilityPromptAt = now
                                AgentRuntime.speaker.speak(prompt)
                            }
                            openAccessibilitySettings()
                            refreshStatus()
                        }
                    }
                }
            }
            return
        }
        running = true
        activeTaskLabel = g
        publishNotificationStatus()
        justSent = true
        progress = 0 to settings.maxTurns
        AgentRuntime.loop?.start(
            AgentRuntime.scope,
            g,
            executionMode = effectiveMode,
            chatHistory = chatHistory.toList(),
        )
    }

    private fun openAccessibilitySettings() {
        val app = getApplication<Application>()
        val component = ComponentName(app, com.pilot.agent.service.AgentAccessibilityService::class.java)
        val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val openedDetail = runCatching {
            if (detail.resolveActivity(app.packageManager) != null) {
                app.startActivity(detail)
                true
            } else {
                false
            }
        }.getOrElse {
            Dbg.w(TAG, "权限详情页不可用，回退到无障碍总设置：${it.javaClass.simpleName}: ${it.message}")
            false
        }
        if (!openedDetail) {
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                Dbg.w(TAG, "打开无障碍设置失败：${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    /**
     * 停止按钮：**同时**停播报和停任务。
     *
     * 老板原话：「停止按钮在任务过程中同时也要停任务」。
     * 所以这个按钮是一站式刹车：
     * - 正在念话 → 闭嘴；
     * - 任务在跑 → 中止；
     * - 两个都在发生 → 两个都停。
     */
    /** 预听音色（不改变当前设置）；[rate] 传设置页滑杆当前值，未保存也生效。 */
    fun previewVoice(engine: String, voiceId: String, rate: Float? = null) {
        AgentRuntime.speaker.previewVoice(engine, voiceId, rate = rate)
        add(LogEntry.Kind.OBSERVE, "正在试听该音色…")
    }

    /** 音乐：暂停 / 继续 */
    fun toggleMusic() {
        val mp = com.pilot.agent.music.MusicPlayer.get(getApplication())
        if (mp.isPlaying) mp.pause() else mp.resume()
        nowPlaying = mp.nowPlaying()
        musicPaused = mp.paused
    }

    fun previousMusic() {
        val mp = com.pilot.agent.music.MusicPlayer.get(getApplication())
        mp.previous()
        syncMusicState(mp)
    }

    fun nextMusic() {
        val mp = com.pilot.agent.music.MusicPlayer.get(getApplication())
        mp.next()
        syncMusicState(mp)
    }

    fun playMusicAt(index: Int) {
        val mp = com.pilot.agent.music.MusicPlayer.get(getApplication())
        mp.playAt(index)
        syncMusicState(mp)
    }

    fun toggleMusicShuffle() {
        val mp = com.pilot.agent.music.MusicPlayer.get(getApplication())
        mp.toggleShuffle()
        syncMusicState(mp)
    }

    fun cycleMusicPlaybackMode() {
        val mp = com.pilot.agent.music.MusicPlayer.get(getApplication())
        mp.cyclePlaybackMode()
        syncMusicState(mp)
    }

    private fun syncMusicState(mp: com.pilot.agent.music.MusicPlayer) {
        nowPlaying = mp.nowPlaying()
        musicPaused = mp.paused
        musicArtist = mp.currentArtist()
        musicLyric = mp.currentLyric().orEmpty()
        musicLyricsReady = mp.lyricsReady()
        musicPositionMs = mp.positionMs()
        musicDurationMs = mp.durationMs()
        musicTrackIndex = mp.currentIndex()
        musicQueueSize = mp.queueSize()
        musicQueue = mp.queueSnapshot()
        musicShuffle = mp.shuffleEnabled
        musicPlaybackMode = mp.playbackMode
    }

    /** 音乐：停止 */
    fun stopMusic() {
        com.pilot.agent.music.MusicPlayer.get(getApplication()).stop()
        nowPlaying = null
        musicPaused = false
        musicArtist = ""
        musicLyric = ""
        musicLyricsReady = false
        musicPositionMs = 0L
        musicDurationMs = 0L
        musicTrackIndex = 0
        musicQueueSize = 0
        musicQueue = emptyList()
        musicShuffle = false
        musicPlaybackMode = com.pilot.agent.music.MusicPlayer.PlaybackMode.REPEAT_ALL
    }

    fun stopSpeaking() {
        var did = false
        if (AgentRuntime.speaker.isSpeaking) {
            AgentRuntime.speaker.stop()
            did = true
        }
        if (running || justSent) {
            dispatchJob?.cancel()
            dispatchJob = null
            AgentRuntime.loop?.cancel()
            running = false
            justSent = false
            did = true
        }
        activeTaskLabel = ""
        publishNotificationStatus()
        speaking = false
        add(LogEntry.Kind.OBSERVE, if (did) "已停止播报与任务" else "已停止")
        refreshStatus()
    }

    fun stop() {
        dispatchJob?.cancel()
        dispatchJob = null
        AgentRuntime.loop?.cancel()
        pendingToolConfirm?.deferred?.complete(false)
        pendingToolConfirm = null
        running = false
        justSent = false
        confirm = null
        add(LogEntry.Kind.ERROR, "已手动中止")
    }

    fun answerConfirm(approved: Boolean) {
        val c = confirm ?: return
        confirm = null
        val local = pendingToolConfirm
        if (local != null && local.id == c.id) {
            pendingToolConfirm = null
            local.deferred.complete(approved)
        } else {
            AgentRuntime.loop?.confirm(c.id, approved)
        }
    }

    fun updateSettings(next: AgentSettings) {
        // 音色变了要立刻生效（不用重启 App）：
        // 系统 TTS 是 setVoice，MiniMax 是下一次请求的 voice_id。
        val voiceChanged = next.systemTtsVoice != settings.systemTtsVoice ||
            next.ttsVoiceId != settings.ttsVoiceId
        AgentSettings.save(next)
        settings = next
        if (voiceChanged) {
            AgentRuntime.speaker.applyVoice(next.systemTtsVoice)
        }
    }

    fun clearSkills() {
        AgentRuntime.skills.clear()
        refreshStatus()
        add(LogEntry.Kind.OBSERVE, "技能库已清空")
    }

    /** Clear visible conversation and its in-memory context together. */
    fun clearChat() {
        logs.clear()
        chatHistory.clear()
        val id = currentSessionId
        if (id != null) {
            // 清屏 = 连最近会话里的记录一起删，避免清完还能从「☰」里翻出来。
            sessions = sessions.filterNot { it.id == id }
            ChatSessionStore.saveAll(sessions)
        }
        currentSessionId = null
        ChatSessionStore.saveActiveSessionId(null)
    }

    /** 新建会话：当前会话已经随消息落盘，这里只清界面和上下文。 */
    fun newSession() {
        if (running || justSent) return
        logs.clear()
        chatHistory.clear()
        currentSessionId = null
        ChatSessionStore.saveActiveSessionId(null)
    }

    /** 打开最近会话：回放聊天记录，并把上下文问答接回去。 */
    fun openSession(id: String) {
        if (running || justSent) return
        if (id == currentSessionId) return
        val session = sessions.firstOrNull { it.id == id } ?: return
        restoreSession(session)
    }

    private fun restoreSession(session: ChatSessionStore.Session) {
        logs.clear()
        chatHistory.clear()
        session.messages.takeLast(200).forEach { message ->
            val kind = runCatching { LogEntry.Kind.valueOf(message.kind) }.getOrNull()
                ?: return@forEach
            if (kind != LogEntry.Kind.USER && kind != LogEntry.Kind.DONE && kind != LogEntry.Kind.ERROR) {
                return@forEach
            }
            logs += LogEntry(seq++, kind, message.text)
        }
        session.history.takeLast(40).forEach { (q, a) -> chatHistory.addLast(q to a) }
        currentSessionId = session.id
        ChatSessionStore.saveActiveSessionId(session.id)
    }

    /**
     * 把当前可见对话 + 上下文问答写进最近会话。
     *
     * 只在 USER/DONE/ERROR 变化时调用：THINK/ACT/OBSERVE 是过程日志，
     * 既不上屏也不该触发落盘（一次 agent 任务会刷很多条）。
     */
    private fun syncCurrentSession() {
        val visible = chatLogs.map { ChatSessionStore.Message(it.kind.name, it.text) }
        if (visible.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = sessions.firstOrNull { it.id == currentSessionId }
        val title = visible.firstOrNull { it.kind == LogEntry.Kind.USER.name }
            ?.text?.replace('\n', ' ')?.trim()?.take(24)?.ifBlank { null }
            ?: existing?.title
            ?: "新会话"
        val session = if (existing == null) {
            ChatSessionStore.Session(
                id = UUID.randomUUID().toString(),
                title = title,
                createdAt = now,
                updatedAt = now,
                messages = visible,
                history = chatHistory.toList(),
            )
        } else {
            existing.copy(
                title = title,
                updatedAt = now,
                messages = visible,
                history = chatHistory.toList(),
            )
        }
        sessions = (sessions.filterNot { it.id == session.id } + session)
            .sortedByDescending { it.updatedAt }
        currentSessionId = session.id
        ChatSessionStore.saveAll(sessions)
        ChatSessionStore.saveActiveSessionId(session.id)
    }

    private fun handle(ev: AgentEvent) {
        syncSpeaking()
        when (ev) {
            is AgentEvent.Thinking -> add(LogEntry.Kind.THINK, ev.text)
            is AgentEvent.Acting -> add(LogEntry.Kind.ACT, ev.text)
            is AgentEvent.Observe -> add(LogEntry.Kind.OBSERVE, ev.text)
            is AgentEvent.NeedConfirm -> {
                confirm = ConfirmUi(ev.requestId, ev.question, ev.risk)
                AgentRuntime.speaker.speak(ev.question)
            }
            is AgentEvent.Finished -> {
                val text = polish(ev.summary)
                // P1：任务完成也要写进多轮上下文。旧实现只 add(DONE)，
                // chatHistory 一直是空的，「刚才那个再说一遍」「我叫什么」全接不上。
                // 必须在 add(DONE) 之前写：add() 会触发 syncCurrentSession 落盘。
                val goal = activeTaskLabel.trim()
                if (goal.isNotBlank() && goal != "本机能力") {
                    chatHistory.addLast(goal to text)
                    while (chatHistory.size > 40) chatHistory.removeFirst()
                }
                add(LogEntry.Kind.DONE, text)
                running = false
                justSent = false
                activeTaskLabel = ""
                publishNotificationStatus()
                // 搜索类结果只念标题摘要，长正文留在屏幕上；普通回答完整播报。
                val spoken = com.pilot.agent.query.QueryEngine.speechSummary(text)
                if (spoken != null) AgentRuntime.speaker.speak(spoken)
                else if (text.startsWith("最近的通知")) AgentRuntime.speaker.speakLocal(text)
                else AgentRuntime.speaker.speak(text)
                refreshStatus()
            }
            is AgentEvent.Failed -> {
                add(LogEntry.Kind.ERROR, ev.reason)
                running = false
                justSent = false
                activeTaskLabel = ""
                publishNotificationStatus()
                confirm = null
            }
            is AgentEvent.Progress -> progress = ev.step to ev.maxSteps
        }
    }

    /**
     * 润色完成话术。
     *
     * 三个真问题：
     * 1. `已打开 设置 设置`——planner 拼 appLabel + label，同一个词出现两次；
     * 2. `已开启 蓝牙 已完成`——机械后缀，念出来很生硬；
     * 3. 空泛的「已完成」，用户不知道完成了什么。
     */
    private fun polish(text: String): String {
        var t = text.trim()
        if (t.isEmpty()) return t
        // 相邻重复词去掉（空格分隔的）
        val parts = t.split(' ').filter { it.isNotBlank() }
        val dedup = mutableListOf<String>()
        for (p in parts) if (dedup.lastOrNull() != p) dedup += p
        t = dedup.joinToString(" ")
        // 去掉机械后缀
        t = t.removeSuffix(" 已完成").removeSuffix("已完成").trim()
        // ★ 去掉括号里的补充说明："音量 86% 调到 100%（+14%，已到最大）" → "音量 86% 调到 100%"
        //   只删**含中文或百分比/加号**的那组（+14%、已到最大这种），
        //   "型号 (X100)"这种正经括号内容要留着。
        t = t.replace(Regex("[（(][^（()）]*(?:[\u4e00-\u9fa5]|[+±]|%)[^（()）]*[)）]\\s*$"), "").trim()
        if (t == "完成" || t.isEmpty()) return "好了"
        return t
    }

    private fun add(kind: LogEntry.Kind, text: String) {
        logs += LogEntry(seq++, kind, text)
        if (logs.size > 200) logs.removeAt(0)
        if (kind == LogEntry.Kind.USER || kind == LogEntry.Kind.DONE || kind == LogEntry.Kind.ERROR) {
            syncCurrentSession()
        }
    }

    /**
     * 用户发言只保留一条。
     *
     * 语音识别会先把结果交给界面，意图分流后 [run] 又负责执行同一个目标；
     * 两处都直接调用 [add] 时，操作类指令就会在聊天里出现两次。
     */
    private fun addUserMessageIfNeeded(text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty()) return
        val last = logs.lastOrNull()
        if (last?.kind == LogEntry.Kind.USER && last.text.trim() == normalized) return
        add(LogEntry.Kind.USER, normalized)
    }
}
