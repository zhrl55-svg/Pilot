package com.pilot.agent.agent

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.pilot.agent.Dbg
import com.pilot.agent.LogRedactor
import com.pilot.agent.PilotApp
import com.pilot.agent.query.SecretVault
import com.pilot.agent.query.VaultRead
import java.util.concurrent.atomic.AtomicLong

data class AgentSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val webSearchApiKey: String = "",
    val model: String = DEFAULT_MODEL,
    /**
     * 路由模式。现在固定为自动：快路径有规则的指令本机直执行，
     * 没有规则的交给 Agent（模型决定回答还是执行）。
     * 字段保留是为了兼容旧的 routes/settings schema，不再对用户暴露。
     */
    val mode: AssistantMode = AssistantMode.AGENT,
    val maxTurns: Int = DEFAULT_MAX_TURNS,
    /** Agent 云端模型的思考强度：low / medium / high / xhigh / max。 */
    val reasoningEffort: String = DEFAULT_REASONING_EFFORT,
    val preferOnDevice: Boolean = true,
    val enableLocalModel: Boolean = true,
    @Deprecated("高风险确认现在始终强制开启")
    val confirmMoneyActions: Boolean = true,
    val allowSkillReplay: Boolean = true,
    /** 播报引擎：minimax（云端，默认）/ system（系统 TTS）/ off */
    val ttsEngine: String = TTS_MINIMAX,
    /** 播报时音乐的处理：duck（降音量不断歌）/ stop（直接停） */
    val musicPolicy: String = MUSIC_DUCK,
    /** 网页打开方式：background（无头不占屏）/ overlay（悬浮小窗，默认）/ fullscreen（全屏） */
    val browserMode: String = BROWSER_MODE_OVERLAY,
    /** 沙箱进程空闲回收阈值（分钟），0 = 不回收。回收释放 guest 内存（~130MB）。 */
    val sandboxIdleMinutes: Int = 5,
    /**
     * 聊天时注入多少轮历史对话。
     *
     * 之前是 0（每句都是独立请求），导致"我叫什么""刚才那个再说一遍"这类追问全接不上。
     * 4 轮是甜点：够记住上下文，又不至于让请求体膨胀、响应变慢。
     */
    val chatContextTurns: Int = 4,
    val ttsVoiceId: String = "male-qn-qingse",
    /**
     * 系统 TTS 的音色名（TextToSpeech 的 Voice 名称）。
     *
     * 不同 ROM 的可用音色不一样（华为智慧语音、小米、Pixel 各不相同），
     * 所以这个值由运行时枚举出来后在设置页选，不是写死的列表。
     * 空 = 用引擎默认音色。
     */
    val systemTtsVoice: String = "",
    /**
     * 播报语速：0.5～2.0，1.0 = 正常。
     *
     * 系统 TTS 与 MiniMax 共用同一个值 —— 用户选哪个引擎，听感的速度应该一致。
     * 存成 Float（而不是档位字符串），设置页用滑杆连续调节；试听和正式播报都走这里。
     */
    val ttsRate: Float = DEFAULT_TTS_RATE,
    val ttsModel: String = "speech-02-hd",
    // ---------- 语音唤醒与声纹 ----------
    /** 待命模式：开着就一直听唤醒词（默认开：进 App 就开麦），命中后自动进入聆听 */
    val wakeEnabled: Boolean = true,
    /** 唤醒词，逗号分隔同义变体，任一命中即可 */
    val wakeWords: String = DEFAULT_WAKE_WORDS,
    /** 唤醒命中时播一声短提示音（ToneGenerator），告诉用户"我在听" */
    val wakeTone: Boolean = true,
    /** 是否要求声纹匹配才响应唤醒（没注册模板时自动放行） */
    val requireVoiceprint: Boolean = true,
    /** 声纹相似度阈值，低于此值判定不是主人 */
    val voiceprintThreshold: Float = 0.30f,
    /**
     * 喂给声纹的音频长度（毫秒），太短算不准。
     *
     * 默认 3000：KWS 命中时唤醒词可能已经说完，1.5 秒窗口里只剩后面的静默，
     * 算出来的分数没意义（实测 0.20，会把主人自己挡在外面）。
     */
    val voiceprintWindowMs: Int = 3000,
    /**
     * 对话结束后多久没有有效语音就休眠（秒）。0 = 不休眠，一直留在活跃期。
     *
     * 活跃期内不用再喊唤醒词，直接说话就行；超时后回到"只等唤醒词"的休眠态。
     */
    val idleSleepSeconds: Int = 60,
    /** 播报/放音过程中允许语音打断（要求声纹通过 + 唤醒词或打断词） */
    val interruptEnabled: Boolean = true,
    /** 打断词，逗号分隔；和唤醒词一起喂给 KWS，命中即触发打断判定 */
    val interruptWords: String = "停一下,停下来,不说了,别说了,安静",
    // ---------- 音乐（自建网易云 API 代理） ----------
    val musicBaseUrl: String = DEFAULT_MUSIC_URL,
    /** 备用地址；Wi‑Fi 下优先走局域网，移动网络下优先走 Tailnet。空值关闭回退。 */
    val musicFallbackUrl: String = DEFAULT_MUSIC_FALLBACK_URL,
    /** 扫码登录后拿到的 cookie，存本地不落服务器 */
    val neteaseCookie: String = "",
    /**
     * 助理快路径（端侧直执行）：规则选能力 → 确定性抽参 → 本机执行，不调云端。
     *
     * P5 真机验收（2026-11-18）通过后默认打开（决策 D3）。快路径只接管已翻
     * ROUTE 的能力，任何一步不成立都回落原链路，所以默认开不会改变失败行为。
     */
    val assistantFastPath: Boolean = true,
    /**
     * 明暗主题：auto（跟随系统）/ light（明色暖白纸感）/ dark（暗色深空聚焦）。
     *
     * 存在普通 SharedPreferences 里（非凭据），改完立刻生效，不需要重启。
     */
    val themeMode: String = THEME_AUTO,
) {
    val cloudReady: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank()

    /** 打断词列表（同一套分隔规则） */
    fun interruptWordList(): List<String> = interruptWords
        .split(',', '，', '、', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    /** 唤醒词列表（逗号 / 中文逗号分隔，去空去重） */
    fun wakeWordList(): List<String> = wakeWords
        .split(',', '，', '、', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.minimax.cn/v1"
        const val DEFAULT_MODEL = "minimax-m3.1-flash"
        const val MIN_AGENT_TURNS = 15
        const val MAX_AGENT_TURNS = 25
        const val DEFAULT_MAX_TURNS = 20
        const val DEFAULT_REASONING_EFFORT = "medium"
        val REASONING_EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

        const val TTS_SYSTEM = "system"
        const val TTS_MINIMAX = "minimax"
        const val TTS_OFF = "off"

        /** 播报语速范围与默认值（系统 TTS 与 MiniMax 都接受 0.5～2.0）。 */
        const val MIN_TTS_RATE = 0.5f
        const val MAX_TTS_RATE = 2.0f
        const val DEFAULT_TTS_RATE = 1.0f
        /** 滑杆步进：0.05 一档，避免浮点抖动。 */
        const val TTS_RATE_STEP = 0.05f

        /**
         * 轻量读取播报语速。
         *
         * 播报路径（SystemTts）每句都要用，不能走 [load]：那会经 SecretVault
         * 做 Keystore 解密并装载全部字段，放在每次 speak 前是白付的代价。
         */
        fun ttsRate(): Float =
            PilotApp.prefs.getFloat(K_TTS_RATE, DEFAULT_TTS_RATE)
                .coerceIn(MIN_TTS_RATE, MAX_TTS_RATE)

        /** 播报时音乐降音量（不断歌） */
        const val MUSIC_DUCK = "duck"
        /** 播报时直接停掉音乐 */
        const val MUSIC_STOP = "stop"

        /** 网页打开方式 */
        const val BROWSER_MODE_BACKGROUND = "background"
        const val BROWSER_MODE_OVERLAY = "overlay"
        const val BROWSER_MODE_FULLSCREEN = "fullscreen"

        const val DEFAULT_WAKE_WORDS = "小派,小派小派,你好小派"

        /** 明暗主题：跟随系统 */
        const val THEME_AUTO = "auto"
        /** 明色 · 暖白纸感 */
        const val THEME_LIGHT = "light"
        /** 暗色 · 深空聚焦 */
        const val THEME_DARK = "dark"
        val THEME_MODES = listOf(THEME_AUTO, THEME_LIGHT, THEME_DARK)

        /** 自建 NeteaseCloudMusicApi 地址（部署在 buntu:3001） */
        const val DEFAULT_MUSIC_URL = "http://100.95.180.77:3001"
        const val DEFAULT_MUSIC_FALLBACK_URL = "http://192.168.2.102:3001"

        private const val K_URL = "base_url"
        private const val K_KEY = "api_key"
        private const val K_WEB_SEARCH_KEY = "brave_search_api_key"
        private const val K_MODEL = "model"
        private const val K_MODE = "assistant_mode"
        private const val K_TURNS = "max_turns"
        private const val K_REASONING_EFFORT = "agent_reasoning_effort"
        private const val K_ONDEVICE = "prefer_ondevice"
        private const val K_LOCAL_MODEL = "enable_local_model"
        private const val K_CONFIRM = "confirm_money"
        private const val K_REPLAY = "allow_replay"
        private const val K_TTS_ENGINE = "tts_engine"
        private const val K_MUSIC_POLICY = "music_policy"
        private const val K_BROWSER_MODE = "browser_mode"
        private const val K_SANDBOX_IDLE = "sandbox_idle_minutes"
        private const val K_CHAT_CONTEXT = "chat_context_turns"
        private const val K_TTS_VOICE = "tts_voice_id"
        private const val K_SYS_TTS_VOICE = "system_tts_voice"
        private const val K_TTS_RATE = "tts_rate"
        private const val K_TTS_MODEL = "tts_model"
        private const val K_WAKE_ENABLED = "wake_enabled"
        private const val K_WAKE_WORDS = "wake_words"
        private const val K_WAKE_TONE = "wake_tone"
        private const val K_REQUIRE_VP = "require_voiceprint"
        private const val K_VP_THRESHOLD = "voiceprint_threshold"
        private const val K_VP_WINDOW = "voiceprint_window_ms"
        private const val K_IDLE_SLEEP = "idle_sleep_seconds"
        private const val K_INTERRUPT = "interrupt_enabled"
        private const val K_INTERRUPT_WORDS = "interrupt_words"
        private const val K_VOICE_MIGRATED = "voice_auto_v2"
        private const val K_MUSIC_URL = "music_base_url"
        private const val K_MUSIC_FALLBACK_URL = "music_fallback_url"
        private const val K_NETEASE_COOKIE = "netease_cookie"
        private const val K_ASSISTANT_FAST_PATH = "assistant_fast_path"
        private const val K_THEME_MODE = "theme_mode"

        /**
         * v2 迁移：把「待命」默认改成**开**（进 App 就开麦）。
         *
         * 老版本默认是 false，用户即使没动过设置，SharedPreferences 里也有 `wake_enabled=false`。
         * 直接改默认值没用（存着的旧值优先），所以用一次性迁移标记强制升到新行为；
         * 之后用户在设置里关掉，就尊重他的选择。
         */
        private fun migrateVoiceAutoV2(p: android.content.SharedPreferences) {
            if (p.getBoolean(K_VOICE_MIGRATED, false)) return
            p.edit()
                .putBoolean(K_WAKE_ENABLED, true)
                .putBoolean(K_VOICE_MIGRATED, true)
                .apply()
        }

        // ---------- 内存缓存（审核报告 code.md C9 / arch.md D4）----------
        //
        // load() 有 47 个调用点（每句播报、每步规划、每个网易云请求都会调），以前每次都要
        // 3 次 Keystore 解密。这里缓存不可变的 AgentSettings 快照：
        //   · save() 主动失效；
        //   · 本进程任何代码改了 "pilot" SharedPreferences → 监听器失效；
        //   · SecretVault 写入了这三个凭据 → 监听器失效。
        // 有凭据读失败时只缓存 [FAILED_SECRET_CACHE_MS]，Keystore 恢复后尽快重新读取。
        // SharedPreferences 本身就是进程内缓存，:sandbox 等副进程本来就看不到主进程的新值，
        // 缓存不会引入新的跨进程不一致（跨进程下发见 fix/security.md 未完成项）。
        private const val TAG = "AgentSettings"
        private const val FAILED_SECRET_CACHE_MS = 5_000L
        private val SECRET_KEYS = setOf(K_KEY, K_WEB_SEARCH_KEY, K_NETEASE_COOKIE)

        private class Snapshot(val value: AgentSettings, val validUntil: Long)

        private val cacheLock = Any()
        @Volatile
        private var cached: Snapshot? = null
        private val generation = AtomicLong()
        @Volatile
        private var listenersInstalled = false

        // 必须强引用：SharedPreferences 只弱引用监听器。
        private val prefsListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> invalidateCache() }
        private val vaultListener: (String) -> Unit = { name -> if (name in SECRET_KEYS) invalidateCache() }

        /** 让下一次 [load] 重新从磁盘读取。 */
        fun invalidateCache() {
            generation.incrementAndGet()
            cached = null
        }

        /** 有凭据当前读不出来（Keystore 异常）。设置页可据此提示「安全存储暂时不可用」。 */
        fun secretsUnavailable(): Boolean = SECRET_KEYS.any { SecretVault.isReadBlocked(it) }

        private fun installListeners() {
            if (listenersInstalled) return
            synchronized(cacheLock) {
                if (listenersInstalled) return
                runCatching { PilotApp.prefs.registerOnSharedPreferenceChangeListener(prefsListener) }
                    .onFailure { Dbg.w(TAG, "注册设置变更监听失败：${LogRedactor.error(it)}") }
                SecretVault.addChangeListener(vaultListener)
                listenersInstalled = true
            }
        }

        fun load(): AgentSettings {
            val hit = cached
            if (hit != null && SystemClock.elapsedRealtime() < hit.validUntil) return hit.value
            installListeners()
            val gen = generation.get()
            val failures = mutableSetOf<String>()
            val fresh = loadFromDisk(failures)
            if (failures.isNotEmpty()) Dbg.w(TAG, "凭据读取失败（已保留原数据，暂停覆盖）：$failures")
            synchronized(cacheLock) {
                // 读的过程中有人 save/改了设置：这份结果可能是旧的，不放进缓存。
                if (generation.get() == gen) {
                    val until = if (failures.isEmpty()) Long.MAX_VALUE
                    else SystemClock.elapsedRealtime() + FAILED_SECRET_CACHE_MS
                    cached = Snapshot(fresh, until)
                }
            }
            return fresh
        }

        private fun loadFromDisk(failures: MutableSet<String>): AgentSettings {
            val p = PilotApp.prefs
            migrateVoiceAutoV2(p)
            return AgentSettings(
                baseUrl = p.getString(K_URL, null) ?: DEFAULT_BASE_URL,
                // P0：API Key 从 Keystore 读；首次运行迁移旧版明文并清除。
                apiKey = readMigratedSecret(p, K_KEY, failures),
                webSearchApiKey = readSecret(K_WEB_SEARCH_KEY, failures),
                model = p.getString(K_MODEL, null) ?: DEFAULT_MODEL,
                // 自动路由：忽略旧版保存的助理/Agent 选择，统一按 Agent 语义执行
                // （快路径仍在前置，命中规则的确定性指令不会进 Agent）。
                mode = AssistantMode.AGENT,
                maxTurns = p.getInt(K_TURNS, DEFAULT_MAX_TURNS)
                    .coerceIn(MIN_AGENT_TURNS, MAX_AGENT_TURNS),
                reasoningEffort = p.getString(K_REASONING_EFFORT, null)
                    ?.takeIf { it in REASONING_EFFORTS }
                    ?: DEFAULT_REASONING_EFFORT,
                preferOnDevice = p.getBoolean(K_ONDEVICE, true),
                enableLocalModel = p.getBoolean(K_LOCAL_MODEL, true),
                confirmMoneyActions = p.getBoolean(K_CONFIRM, true),
                allowSkillReplay = p.getBoolean(K_REPLAY, true),
                ttsEngine = p.getString(K_TTS_ENGINE, null) ?: TTS_MINIMAX,
                musicPolicy = p.getString(K_MUSIC_POLICY, null) ?: MUSIC_DUCK,
                browserMode = p.getString(K_BROWSER_MODE, null) ?: BROWSER_MODE_OVERLAY,
                sandboxIdleMinutes = p.getInt(K_SANDBOX_IDLE, 5),
                chatContextTurns = p.getInt(K_CHAT_CONTEXT, 4),
                ttsVoiceId = p.getString(K_TTS_VOICE, null) ?: "male-qn-qingse",
                systemTtsVoice = p.getString(K_SYS_TTS_VOICE, null) ?: "",
                ttsRate = p.getFloat(K_TTS_RATE, DEFAULT_TTS_RATE)
                    .coerceIn(MIN_TTS_RATE, MAX_TTS_RATE),
                ttsModel = p.getString(K_TTS_MODEL, null) ?: "speech-02-hd",
                wakeEnabled = p.getBoolean(K_WAKE_ENABLED, true),
                wakeWords = p.getString(K_WAKE_WORDS, null) ?: DEFAULT_WAKE_WORDS,
                wakeTone = p.getBoolean(K_WAKE_TONE, true),
                requireVoiceprint = p.getBoolean(K_REQUIRE_VP, true),
                voiceprintThreshold = p.getFloat(K_VP_THRESHOLD, 0.30f),
                voiceprintWindowMs = p.getInt(K_VP_WINDOW, 3000),
                idleSleepSeconds = p.getInt(K_IDLE_SLEEP, 60),
                interruptEnabled = p.getBoolean(K_INTERRUPT, true),
                interruptWords = p.getString(K_INTERRUPT_WORDS, null)
                    ?: "停一下,停下来,不说了,别说了,安静",
                musicBaseUrl = p.getString(K_MUSIC_URL, null)
                    ?.takeUnless { it.trimEnd('/') == "http://192.168.2.102:3001" }
                    ?: DEFAULT_MUSIC_URL,
                musicFallbackUrl = p.getString(K_MUSIC_FALLBACK_URL, null)
                    ?: DEFAULT_MUSIC_FALLBACK_URL,
                neteaseCookie = readMigratedSecret(p, K_NETEASE_COOKIE, failures),
                assistantFastPath = p.getBoolean(K_ASSISTANT_FAST_PATH, true),
                themeMode = p.getString(K_THEME_MODE, null)
                    ?.takeIf { it in THEME_MODES }
                    ?: THEME_AUTO,
            )
        }

        /** 兼容旧调用点（返回 Unit）。需要把失败告诉用户的调用方请改用 [saveChecked]。 */
        fun save(s: AgentSettings) {
            saveChecked(s)
        }

        /**
         * 保存设置。
         *
         * E1：凭据（API Key / 搜索 Key / 网易云 Cookie）**先写密文并确认落盘，成功后才删旧明文**；
         * 写失败保留明文、旧密文不动。某个凭据当前读不出来（Keystore 异常）而用户也没输入新值时，
         * 绝不用空串覆盖它。
         *
         * @return null = 全部保存成功；否则是给用户看的中文说明（普通设置项已经保存）。
         */
        fun saveChecked(s: AgentSettings): String? {
            val p = PilotApp.prefs
            p.edit()
                .putString(K_URL, s.baseUrl)
                // P0：敏感凭据不写明文；旧明文在密文写入成功后由 persistSecret 清除
                .putString(K_MODEL, s.model)
                .putString(K_MODE, AssistantMode.AGENT.storageValue)
                .putInt(K_TURNS, s.maxTurns.coerceIn(MIN_AGENT_TURNS, MAX_AGENT_TURNS))
                .putString(
                    K_REASONING_EFFORT,
                    s.reasoningEffort.takeIf { it in REASONING_EFFORTS }
                        ?: DEFAULT_REASONING_EFFORT,
                )
                .putBoolean(K_ONDEVICE, s.preferOnDevice)
                .putBoolean(K_LOCAL_MODEL, s.enableLocalModel)
                .putBoolean(K_CONFIRM, s.confirmMoneyActions)
                .putBoolean(K_REPLAY, s.allowSkillReplay)
                .putString(K_TTS_ENGINE, s.ttsEngine)
                .putString(K_MUSIC_POLICY, s.musicPolicy)
                .putString(K_BROWSER_MODE, s.browserMode)
                .putInt(K_SANDBOX_IDLE, s.sandboxIdleMinutes)
                .putInt(K_CHAT_CONTEXT, s.chatContextTurns)
                .putString(K_TTS_VOICE, s.ttsVoiceId)
                .putString(K_SYS_TTS_VOICE, s.systemTtsVoice)
                .putFloat(K_TTS_RATE, s.ttsRate.coerceIn(MIN_TTS_RATE, MAX_TTS_RATE))
                .putString(K_TTS_MODEL, s.ttsModel)
                .putBoolean(K_WAKE_ENABLED, s.wakeEnabled)
                .putString(K_WAKE_WORDS, s.wakeWords)
                .putBoolean(K_WAKE_TONE, s.wakeTone)
                .putBoolean(K_REQUIRE_VP, s.requireVoiceprint)
                .putFloat(K_VP_THRESHOLD, s.voiceprintThreshold)
                .putInt(K_VP_WINDOW, s.voiceprintWindowMs)
                .putInt(K_IDLE_SLEEP, s.idleSleepSeconds)
                .putBoolean(K_INTERRUPT, s.interruptEnabled)
                .putString(K_INTERRUPT_WORDS, s.interruptWords)
                .putString(K_MUSIC_URL, s.musicBaseUrl)
                .putString(K_MUSIC_FALLBACK_URL, s.musicFallbackUrl)
                .putBoolean(K_ASSISTANT_FAST_PATH, s.assistantFastPath)
                .putString(
                    K_THEME_MODE,
                    s.themeMode.takeIf { it in THEME_MODES } ?: THEME_AUTO,
                )
                .apply()
            val problems = mutableListOf<String>()
            persistSecret(p, K_WEB_SEARCH_KEY, s.webSearchApiKey, hasLegacyPlaintext = false, label = "搜索 API Key", problems = problems)
            persistSecret(p, K_KEY, s.apiKey, hasLegacyPlaintext = true, label = "API Key", problems = problems)
            persistSecret(p, K_NETEASE_COOKIE, s.neteaseCookie, hasLegacyPlaintext = true, label = "网易云登录信息", problems = problems)
            invalidateCache()
            if (problems.isEmpty()) return null
            Dbg.w(TAG, "设置已保存，但有 ${problems.size} 项凭据未写入")
            return problems.joinToString("；") + "。"
        }

        /**
         * 只在凭据真的变化时写入；顺序固定为「写密文（commit）→ 成功后删旧明文」。
         */
        private fun persistSecret(
            p: SharedPreferences,
            key: String,
            value: String,
            hasLegacyPlaintext: Boolean,
            label: String,
            problems: MutableList<String>,
        ) {
            val legacy = if (hasLegacyPlaintext) p.getString(key, null) else null
            when (val current = SecretVault.read(key)) {
                is VaultRead.Ok -> if (current.value == value) {
                    // 没变：只顺手清掉残留的旧明文（密文可用，删明文安全）。
                    if (legacy != null) p.edit().remove(key).apply()
                    return
                }
                VaultRead.Absent -> if (value.isBlank()) {
                    // 用户清空了该凭据：旧明文一起清掉。
                    if (legacy != null) p.edit().remove(key).apply()
                    return
                }
                is VaultRead.Failed -> if (value.isBlank() || value == legacy) {
                    // 旧值读不出来、用户也没有输入新值：保持原密文与明文不动，绝不用空串覆盖。
                    problems += "$label 暂时读不出来，已保留原有数据，没有覆盖"
                    return
                }
            }
            // 走到这里 = 用户输入了新值（或需要把旧明文迁入密文）。
            // 之前读失败时，用户新输入的值是明确意图，允许覆盖（force）。
            val force = SecretVault.isReadBlocked(key)
            if (SecretVault.putChecked(key, value, force = force)) {
                if (legacy != null) p.edit().remove(key).apply()
            } else {
                problems += "$label 没能写入安全存储，原有数据保持不变"
                Dbg.w(TAG, "凭据写入安全存储失败（保留原数据）：$key")
            }
        }

        private fun readSecret(key: String, failures: MutableSet<String>): String =
            when (val r = SecretVault.read(key)) {
                is VaultRead.Ok -> r.value
                VaultRead.Absent -> ""
                is VaultRead.Failed -> {
                    failures += key
                    ""
                }
            }

        /**
         * P0 迁移：优先从 Keystore 读；没有密文时取旧版明文，**写入 Keystore 成功后**才清除明文。
         * Keystore 读失败时密文、明文都不动（有旧明文就先用着），并记入 [failures]。
         */
        private fun readMigratedSecret(
            p: SharedPreferences,
            key: String,
            failures: MutableSet<String>,
        ): String {
            val legacy = p.getString(key, null).orEmpty()
            when (val r = SecretVault.read(key)) {
                is VaultRead.Ok -> if (r.value.isNotBlank()) {
                    if (legacy.isNotEmpty()) p.edit().remove(key).apply()
                    return r.value
                }
                is VaultRead.Failed -> {
                    failures += key
                    return legacy
                }
                VaultRead.Absent -> Unit
            }
            if (legacy.isBlank()) return ""
            if (SecretVault.putChecked(key, legacy)) {
                p.edit().remove(key).apply()
            } else {
                Dbg.w(TAG, "凭据迁移到安全存储失败，保留旧值待下次重试：$key")
            }
            return legacy
        }

        fun isAccessibilityEnabled(ctx: Context): Boolean {
            val expectedPkg = ctx.packageName
            val expectedCls = com.pilot.agent.service.AgentAccessibilityService::class.java.name

            // ★ 先问 AccessibilityManager 要「当前已启用并在运行的列表」。
            //   原来只读 Settings.Secure，EMUI/HarmonyOS 上偶尔读到 null 或被裁剪的值，
            //   于是权限明明开着、服务也 Bound 了，界面仍提示"未开启"（11-26 真机反馈）。
            runCatching {
                val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE)
                    as? android.view.accessibility.AccessibilityManager
                val list = am?.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
                )
                val hit = list?.any { info ->
                    val si = info?.resolveInfo?.serviceInfo ?: return@any false
                    si.packageName == expectedPkg &&
                        (si.name == expectedCls || si.name == "$expectedPkg.service.AgentAccessibilityService")
                } == true
                if (hit) return true
            }

            // 兜底：读 Secure 键。系统可能把组件名写成 "pkg/.service.Class" 的简写，
            // 所以不能直接整串 equals，要拆开把 "." 开头的类名补全后再比。
            val enabled = runCatching {
                android.provider.Settings.Secure.getString(
                    ctx.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            }.getOrNull() ?: return false
            return enabled.split(':').any { entry ->
                val trimmed = entry.trim()
                if (trimmed.equals("$expectedPkg/$expectedCls", ignoreCase = true)) return@any true
                val pkg = trimmed.substringBefore('/', "")
                val cls = trimmed.substringAfter('/', "")
                pkg.equals(expectedPkg, ignoreCase = true) &&
                    (cls.equals(expectedCls, ignoreCase = true) ||
                        (cls.startsWith(".") && cls.equals(expectedCls.removePrefix(expectedPkg), ignoreCase = true)))
            }
        }
    }
}
