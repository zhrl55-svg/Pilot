package com.pilot.agent.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pilot.agent.Dbg
import com.pilot.agent.agent.AgentEvent
import com.pilot.agent.agent.AgentSettings
import com.pilot.agent.agent.IntentGate
import com.pilot.agent.agent.Risk
import com.pilot.agent.runtime.AgentRuntime
import com.pilot.agent.voice.VoiceInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType

data class LogEntry(val id: Long, val kind: Kind, val text: String) {
    enum class Kind { USER, THINK, ACT, OBSERVE, DONE, ERROR }
}

data class ConfirmUi(val id: Long, val question: String, val risk: Risk)

class AgentViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "PilotVM"
    }

    var settings by mutableStateOf(AgentSettings.load())
        private set

    val logs = mutableStateListOf<LogEntry>()

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

    var confirm by mutableStateOf<ConfirmUi?>(null)
        private set

    var progress by mutableStateOf(0 to 0)
        private set

    var accessibilityOn by mutableStateOf(false)
        private set

    var skillCount by mutableStateOf(0)
        private set

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
    var neteaseNick by mutableStateOf("")
        private set

    private var loginJob: kotlinx.coroutines.Job? = null

    private var voice: VoiceInput? = null
    private var seq = 0L

    init {
        viewModelScope.launch {
            AgentRuntime.events.collect { handle(it) }
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
            if (v.isWakeMode) {
                wakeOn = true
                return@launch
            }
            // 等 ASR 预热那一步先跑完，避免和模型加载抢线程
            val ok = withContext(Dispatchers.IO) { v.startWake() }
            wakeOn = ok
            if (ok) add(LogEntry.Kind.OBSERVE, "麦克风已常驻，喊「${AgentSettings.load().wakeWordList().first()}」唤醒")
            refreshStatus()
        }
    }

    fun refreshStatus() {
        accessibilityOn = AgentSettings.isAccessibilityEnabled(getApplication())
        skillCount = AgentRuntime.skills.all().size

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
            v.onState = { voiceState = it }
            v.onText = { text ->
                add(LogEntry.Kind.USER, text)
                when (IntentGate.classify(text)) {
                    IntentGate.Kind.TASK -> run(text)
                    IntentGate.Kind.QUERY -> query(text)
                    IntentGate.Kind.CHAT -> chat(text)
                }
            }
            v.onError = { msg -> add(LogEntry.Kind.ERROR, msg) }
            v.onWake = { word -> add(LogEntry.Kind.OBSERVE, "唤醒「$word」，说吧") }
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
                add(LogEntry.Kind.USER, text)
                // 意图三分类：操作 / 查询 / 闲聊，走三条完全不同的链路
                when (IntentGate.classify(text)) {
                    IntentGate.Kind.TASK -> run(text)
                    IntentGate.Kind.QUERY -> query(text)
                    IntentGate.Kind.CHAT -> chat(text)
                }
            },
            onError = { msg -> add(LogEntry.Kind.ERROR, msg) },
            onWake = { word -> add(LogEntry.Kind.OBSERVE, "唤醒「$word」，说吧") },
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
            val base = settings.musicBaseUrl
            com.pilot.agent.Dbg.i("PilotMusic", "扫码登录：请求 key，base=$base")
            val key = withContext(Dispatchers.IO) { com.pilot.agent.music.NeteaseApi.qrKey(base) }
            if (key == null) {
                loginTip = "拿不到二维码，检查音乐服务是否在运行"
                com.pilot.agent.Dbg.w("PilotMusic", "扫码登录：qrKey 为空")
                return@launch
            }
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
                        AgentSettings.save(settings.copy(neteaseCookie = c))
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
                    com.pilot.agent.music.NeteaseApi.QrState.ERROR -> {
                        loginTip = "登录失败"
                        return@launch
                    }

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
        AgentSettings.save(settings.copy(neteaseCookie = ""))
        neteaseNick = ""
        loginTip = ""
        add(LogEntry.Kind.OBSERVE, "已退出网易云登录")
        refreshStatus()
    }

    /** 待命开关：常驻听唤醒词 */
    fun toggleWake() {
        ensureVoice()
        val v = voice ?: return
        if (v.isWakeMode) {
            v.stop()
            wakeOn = false
            add(LogEntry.Kind.OBSERVE, "已退出待命")
        } else {
            wakeOn = v.startWake()
            if (wakeOn) {
                add(LogEntry.Kind.OBSERVE, "已进入待命，说「${settings.wakeWordList().first()}」唤醒")
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

    /**
     * 挂一个 SAF 目录。**重启沙箱进程后生效**——proot 的 bind mount 表在
     * boot 时快照，运行中热挂载要动 ExecutionCoordinator（minis 原版支持，
     * Pilot 的 v2 UI 先走冷生效，够用且稳）。
     */
    fun mountSandboxFolder(treeUri: android.net.Uri, displayName: String) {
        val store = com.pilot.agent.sandbox.SandboxStores.mountedFolders(getApplication())
        store.addFromTreeUri(displayName, treeUri)
            .onSuccess { add(LogEntry.Kind.OBSERVE, "已挂载「$displayName」→ /var/minis/mounts/，重启沙箱进程后生效") }
            .onFailure { add(LogEntry.Kind.ERROR, "挂载失败：${it.message}") }
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
    fun startVoiceHold() {
        ensureVoice()
        val v = voice ?: return
        if (v.state == VoiceInput.State.LISTENING || v.state == VoiceInput.State.RECOGNIZING) return
        v.startListening(resumeWake = wakeOn)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AgentRuntime.localLlm.ensureReady() }
        }
    }

    /** 按住说话：松手结束录音并送识别（识别完由 onText 自动执行）。 */
    fun finishVoiceHold() {
        voice?.finishListening()
    }

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
     * 查询类：行情、天气这类要信息的需求。走 QueryEngine，不碰屏幕。
     * 查不到就退回端侧模型答（哪怕答得不好，也比一声不吭强）。
     */
    private fun query(text: String) {
        viewModelScope.launch {
            add(LogEntry.Kind.THINK, "查一下…")
            val answer = withContext(Dispatchers.IO) {
                com.pilot.agent.query.QueryEngine.handle(text)
            }
            if (answer.isNullOrBlank()) {
                add(LogEntry.Kind.OBSERVE, "没有对应的数据源，转给端侧模型")
                chat(text)
            } else {
                add(LogEntry.Kind.DONE, answer)
                AgentRuntime.speaker.speak(answer)
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
            val body = org.json.JSONObject().apply {
                put("model", s.model)
                put("stream", false)
                put("messages", org.json.JSONArray().apply {
                    put(org.json.JSONObject().apply {
                        put("role", "system")
                        put(
                            "content",
                            "你是手机助手 Pilot，运行在这台安卓手机上。现在是 $now。" +
                                "用一到两句话回答，简短口语化，不要用列表。" +
                                "你能开关应用、查天气和股票、播音乐、跑 shell 命令、做导航。",
                        )
                    })
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
            okhttp3.OkHttpClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Dbg.w(TAG, "云端对话失败 http=${resp.code}")
                    return null
                }
                val json = org.json.JSONObject(resp.body?.string().orEmpty())
                json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
            }
        } catch (t: Throwable) {
            Dbg.w(TAG, "云端对话异常：${t.message}")
            null
        }
    }

    /** 端侧小模型回答（离线兜底）。 */
    private suspend fun localChat(text: String, now: String): String? {
        val llm = AgentRuntime.localLlm
        if (!llm.ensureReady()) return null
        val noThink = if (llm.wantsNoThink) "/no_think" else ""
        return llm.generate(
            "system\n你是手机助手 Pilot，运行在这台安卓手机上，现在是 $now。" +
                "能开关应用、查天气股票、播音乐、跑命令、做导航。" +
                "回答简短口语化，不超过 25 个字。$noThink\n" +
                "user\n$text\nassistant\n"
        )
    }

    fun run(goal: String) {
        val g = goal.trim()
        if (g.isEmpty() || running) return
        // 文本输入同样过意图门：不是操作指令就不碰屏幕
        if (!IntentGate.isTask(g)) {
            add(LogEntry.Kind.USER, g)
            add(LogEntry.Kind.OBSERVE, "这句不是操作指令（${IntentGate.reason(g)}），不执行。")
            return
        }
        refreshStatus()
        if (!accessibilityOn || AgentRuntime.loop == null) {
            add(LogEntry.Kind.ERROR, "无障碍服务还没开。打开系统设置 → 无障碍 → Pilot 系统操作，然后回来。")
            return
        }
        add(LogEntry.Kind.USER, g)
        running = true
        progress = 0 to settings.maxTurns
        AgentRuntime.loop?.start(AgentRuntime.scope, g)
    }

    fun stop() {
        AgentRuntime.loop?.cancel()
        running = false
        confirm = null
        add(LogEntry.Kind.ERROR, "已手动中止")
    }

    fun answerConfirm(approved: Boolean) {
        val c = confirm ?: return
        confirm = null
        AgentRuntime.loop?.confirm(c.id, approved)
    }

    fun updateSettings(next: AgentSettings) {
        AgentSettings.save(next)
        settings = next
    }

    fun clearSkills() {
        AgentRuntime.skills.clear()
        refreshStatus()
        add(LogEntry.Kind.OBSERVE, "技能库已清空")
    }

    private fun handle(ev: AgentEvent) {
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
                add(LogEntry.Kind.DONE, text)
                running = false
                AgentRuntime.speaker.speak(text)
                refreshStatus()
            }
            is AgentEvent.Failed -> {
                add(LogEntry.Kind.ERROR, ev.reason)
                running = false
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
        if (t == "完成" || t.isEmpty()) return "好了"
        return t
    }

    private fun add(kind: LogEntry.Kind, text: String) {
        logs += LogEntry(seq++, kind, text)
        if (logs.size > 200) logs.removeAt(0)
    }
}
