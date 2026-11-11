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
    /** 扫码登录后拿到的 cookie，存本地不落服务器 */
    val neteaseCookie: String = "",
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
        private const val K_IDLE_SLEEP = "idle_sleep_seconds"
        private const val K_INTERRUPT = "interrupt_enabled"
        private const val K_INTERRUPT_WORDS = "interrupt_words"
        private const val K_VOICE_MIGRATED = "voice_auto_v2"
        private const val K_MUSIC_URL = "music_base_url"
        private const val K_NETEASE_COOKIE = "netease_cookie"

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

        fun load(): AgentSettings {
            val p = PilotApp.prefs
            migrateVoiceAutoV2(p)
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
                .putInt(K_IDLE_SLEEP, s.idleSleepSeconds)
                .putBoolean(K_INTERRUPT, s.interruptEnabled)
                .putString(K_INTERRUPT_WORDS, s.interruptWords)
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
