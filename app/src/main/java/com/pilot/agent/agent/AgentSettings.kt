package com.pilot.agent.agent

import android.content.Context
import com.pilot.agent.PilotApp

data class AgentSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val maxTurns: Int = 25,
    val preferOnDevice: Boolean = true,
    val enableLocalModel: Boolean = true,
    @Deprecated("高风险确认现在始终强制开启")
    val confirmMoneyActions: Boolean = true,
    val allowSkillReplay: Boolean = true,
    /** 播报引擎：minimax（云端，默认）/ system（系统 TTS）/ off */
    val ttsEngine: String = TTS_MINIMAX,
    /** 播报时音乐的处理：duck（降音量不断歌）/ stop（直接停） */
    val musicPolicy: String = MUSIC_DUCK,
    /** 沙箱进程空闲回收阈值（分钟），0 = 不回收。回收释放 guest 内存（~130MB）。 */
    val sandboxIdleMinutes: Int = 5,
    val ttsVoiceId: String = "male-qn-qingse",
    val ttsModel: String = "speech-02-hd",
    // ---------- 语音唤醒与声纹 ----------
    /** 待命模式：开着就一直听唤醒词，命中后自动进入聆听 */
    val wakeEnabled: Boolean = false,
    /** 唤醒词，逗号分隔同义变体，任一命中即可 */
    val wakeWords: String = DEFAULT_WAKE_WORDS,
    /** 唤醒命中时播一声短提示音（ToneGenerator），告诉用户"我在听" */
    val wakeTone: Boolean = true,
    /** 是否要求声纹匹配才响应唤醒（没注册模板时自动放行） */
    val requireVoiceprint: Boolean = true,
    /** 声纹相似度阈值，低于此值判定不是主人 */
    val voiceprintThreshold: Float = 0.30f,
    /** 喂给声纹的音频长度（毫秒），太短算不准 */
    val voiceprintWindowMs: Int = 1500,
    // ---------- 音乐（自建网易云 API 代理） ----------
    val musicBaseUrl: String = DEFAULT_MUSIC_URL,
    /** 扫码登录后拿到的 cookie，存本地不落服务器 */
    val neteaseCookie: String = "",
) {
    val cloudReady: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank()

    /** 唤醒词列表（逗号 / 中文逗号分隔，去空去重） */
    fun wakeWordList(): List<String> = wakeWords
        .split(',', '，', '、', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.minimax.cn/v1"
        const val DEFAULT_MODEL = "minimax-m3.1-flash"

        const val TTS_SYSTEM = "system"
        const val TTS_MINIMAX = "minimax"
        const val TTS_OFF = "off"

        /** 播报时音乐降音量（不断歌） */
        const val MUSIC_DUCK = "duck"
        /** 播报时直接停掉音乐 */
        const val MUSIC_STOP = "stop"

        const val DEFAULT_WAKE_WORDS = "小派,小派小派,你好小派"

        /** 自建 NeteaseCloudMusicApi 地址（部署在 buntu:3001） */
        const val DEFAULT_MUSIC_URL = "http://192.168.2.102:3001"

        private const val K_URL = "base_url"
        private const val K_KEY = "api_key"
        private const val K_MODEL = "model"
        private const val K_TURNS = "max_turns"
        private const val K_ONDEVICE = "prefer_ondevice"
        private const val K_LOCAL_MODEL = "enable_local_model"
        private const val K_CONFIRM = "confirm_money"
        private const val K_REPLAY = "allow_replay"
        private const val K_TTS_ENGINE = "tts_engine"
        private const val K_MUSIC_POLICY = "music_policy"
        private const val K_SANDBOX_IDLE = "sandbox_idle_minutes"
        private const val K_TTS_VOICE = "tts_voice_id"
        private const val K_TTS_MODEL = "tts_model"
        private const val K_WAKE_ENABLED = "wake_enabled"
        private const val K_WAKE_WORDS = "wake_words"
        private const val K_WAKE_TONE = "wake_tone"
        private const val K_REQUIRE_VP = "require_voiceprint"
        private const val K_VP_THRESHOLD = "voiceprint_threshold"
        private const val K_VP_WINDOW = "voiceprint_window_ms"
        private const val K_MUSIC_URL = "music_base_url"
        private const val K_NETEASE_COOKIE = "netease_cookie"

        fun load(): AgentSettings {
            val p = PilotApp.prefs
            return AgentSettings(
                baseUrl = p.getString(K_URL, null) ?: DEFAULT_BASE_URL,
                apiKey = p.getString(K_KEY, null) ?: "",
                model = p.getString(K_MODEL, null) ?: DEFAULT_MODEL,
                maxTurns = p.getInt(K_TURNS, 25),
                preferOnDevice = p.getBoolean(K_ONDEVICE, true),
                enableLocalModel = p.getBoolean(K_LOCAL_MODEL, true),
                confirmMoneyActions = p.getBoolean(K_CONFIRM, true),
                allowSkillReplay = p.getBoolean(K_REPLAY, true),
                ttsEngine = p.getString(K_TTS_ENGINE, null) ?: TTS_MINIMAX,
                musicPolicy = p.getString(K_MUSIC_POLICY, null) ?: MUSIC_DUCK,
                sandboxIdleMinutes = p.getInt(K_SANDBOX_IDLE, 5),
                ttsVoiceId = p.getString(K_TTS_VOICE, null) ?: "male-qn-qingse",
                ttsModel = p.getString(K_TTS_MODEL, null) ?: "speech-02-hd",
                wakeEnabled = p.getBoolean(K_WAKE_ENABLED, false),
                wakeWords = p.getString(K_WAKE_WORDS, null) ?: DEFAULT_WAKE_WORDS,
                wakeTone = p.getBoolean(K_WAKE_TONE, true),
                requireVoiceprint = p.getBoolean(K_REQUIRE_VP, true),
                voiceprintThreshold = p.getFloat(K_VP_THRESHOLD, 0.30f),
                voiceprintWindowMs = p.getInt(K_VP_WINDOW, 1500),
                musicBaseUrl = p.getString(K_MUSIC_URL, null) ?: DEFAULT_MUSIC_URL,
                neteaseCookie = p.getString(K_NETEASE_COOKIE, null) ?: "",
            )
        }

        fun save(s: AgentSettings) {
            PilotApp.prefs.edit()
                .putString(K_URL, s.baseUrl)
                .putString(K_KEY, s.apiKey)
                .putString(K_MODEL, s.model)
                .putInt(K_TURNS, s.maxTurns)
                .putBoolean(K_ONDEVICE, s.preferOnDevice)
                .putBoolean(K_LOCAL_MODEL, s.enableLocalModel)
                .putBoolean(K_CONFIRM, s.confirmMoneyActions)
                .putBoolean(K_REPLAY, s.allowSkillReplay)
                .putString(K_TTS_ENGINE, s.ttsEngine)
                .putString(K_MUSIC_POLICY, s.musicPolicy)
                .putInt(K_SANDBOX_IDLE, s.sandboxIdleMinutes)
                .putString(K_TTS_VOICE, s.ttsVoiceId)
                .putString(K_TTS_MODEL, s.ttsModel)
                .putBoolean(K_WAKE_ENABLED, s.wakeEnabled)
                .putString(K_WAKE_WORDS, s.wakeWords)
                .putBoolean(K_WAKE_TONE, s.wakeTone)
                .putBoolean(K_REQUIRE_VP, s.requireVoiceprint)
                .putFloat(K_VP_THRESHOLD, s.voiceprintThreshold)
                .putInt(K_VP_WINDOW, s.voiceprintWindowMs)
                .putString(K_MUSIC_URL, s.musicBaseUrl)
                .putString(K_NETEASE_COOKIE, s.neteaseCookie)
                .apply()
        }

        fun isAccessibilityEnabled(ctx: Context): Boolean {
            val expected = "${ctx.packageName}/${com.pilot.agent.service.AgentAccessibilityService::class.java.name}"
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
