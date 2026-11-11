package com.pilot.agent.voice

import com.pilot.agent.Dbg

/**
 * 麦克风占用闸门：谁在放声音，谁说了算。
 *
 * ## 要解决的真机 bug（11-10 老板反馈）
 * 「pilot 放后台后，音乐播放一会儿自动就『你说完了』」
 *
 * 根因链条：
 *   音乐外放 → 麦克风（VOICE_RECOGNITION）把音乐一起录进去
 *   → 能量 VAD 判定"有人在说话" → 切一段音频送 ASR
 *   → ASR 把歌词/伴奏里的字误听成「你说完了」
 *   → agent 把这句话当用户指令，播报出来打断音乐
 *
 * 这不是 ASR 准不准的问题，是**不该在放音时录音**。
 * 所有正经音乐 App 都是这么做的（放音期间不录音）。
 *
 * ## 机制
 * 播放侧（MusicPlayer / TTS）调用 [hold] 拿一个令牌；
 * 语音层（VoiceInput）在 [isMuted] 为 true 时：
 *   - 不再把 VAD 切出的片段送 ASR（省电，也不误触发）
 *   - 录音管线本身可以继续跑（重启 AudioRecord 要 ~100ms，
 *     频繁开关反而更容易丢唤醒词），只在识别入口拦
 *
 * 令牌用引用计数：多个来源同时放音时，一个停了不能把另一个的也关掉。
 */
object AudioGate {

    private const val TAG = "PilotAudioGate"
    private val holders = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 当前是否静音（麦克风内容不可信）。 */
    @Volatile
    var isMuted: Boolean = false
        private set

    /**
     * 最后一次放音结束的时刻（uptimeMillis）。
     *
     * 为什么要它：播报**结束的瞬间**，VAD 缓冲里还有一段尾音，
     * 出来后会被切成一句话送 ASR，识别成「音量已经是最大百分之一百」这种
     * ——正好是刚才播的内容。实测直接导致 agent 自己跟自己对话三轮。
     */
    @Volatile
    var lastReleaseAt: Long = 0L
        private set

    /** 占用来源名（调试用）。 */
    fun activeHolders(): Set<String> = holders.toSet()

    /** 拿一个占用令牌。返回的 close() 幂等，重复调无害。 */
    fun hold(name: String): AutoCloseable {
        holders.add(name)
        sync()
        Dbg.i(TAG, "音频占用 +$name（持有者=${holders.size}）")
        return AutoCloseable {
            holders.remove(name)
            lastReleaseAt = android.os.SystemClock.uptimeMillis()
            sync()
            Dbg.i(TAG, "音频占用 -$name（持有者=${holders.size}）")
        }
    }

    /** 便捷用法：play { ... } 块内自动静音。 */
    inline fun <T> mutedBy(name: String, block: () -> T): T {
        val token = hold(name)
        return try {
            block()
        } finally {
            token.close()
        }
    }

    private fun sync() {
        val muted = holders.isNotEmpty()
        if (muted != isMuted) {
            isMuted = muted
            Dbg.i(TAG, if (muted) "麦克风内容不可信（放音中），不送 ASR" else "麦克风恢复可信")
        }
    }
}
