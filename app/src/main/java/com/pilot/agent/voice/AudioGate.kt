package com.pilot.agent.voice

import com.pilot.agent.Dbg
import java.util.concurrent.atomic.AtomicLong

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
 * ## 真正的引用计数（审核 V-02）
 * 以前 holders 是按**名字**存的 Set：两条播报都持有 "tts"，先结束的那条把 "tts"
 * 从集合里删掉，第二条还在播麦克风就被判"可信"，回声被识别成新指令（自问自答）。
 * 现在每次 [hold] 发一个唯一 id 令牌，只有**所有**令牌都 close 后才解除静音；
 * 名字只用于调试和 [activeHolders] 判断"音乐是否在放"。
 */
object AudioGate {

    private const val TAG = "PilotAudioGate"
    private val lock = Any()
    private val nextId = AtomicLong(1L)

    /** 令牌 id → 来源名。只在 [lock] 内修改。 */
    private val holders = LinkedHashMap<Long, String>()

    /** 开启了回声保护的令牌 id。只在 [lock] 内修改。 */
    private val echoGuardHolders = HashSet<Long>()
    private val whenUnmuted = ArrayList<() -> Unit>()

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

    /** 占用来源名（去重后；调试 / 判断音乐是否在放用）。 */
    fun activeHolders(): Set<String> = synchronized(lock) { holders.values.toSet() }

    /** 当前持有的令牌总数（调试用）。 */
    fun holderCount(): Int = synchronized(lock) { holders.size }

    /**
     * 拿一个占用令牌。返回的 close() 幂等，重复调无害；只释放**本令牌**。
     *
     * [guardEcho] 只对 TTS 这类结束时会留下尾音的放音源开启。音乐暂停时
     * 用户通常马上说指令，不能把随后 900ms 的人声当成回声丢掉。
     */
    fun hold(name: String, guardEcho: Boolean = true): AutoCloseable {
        val id = nextId.getAndIncrement()
        val count = synchronized(lock) {
            if (name in SINGLETON_SOURCES) {
                // 单实例放音源：新令牌顶替同名旧令牌（旧令牌之后 close 为空操作）
                val stale = holders.entries.filter { it.value == name }.map { it.key }
                stale.forEach {
                    holders.remove(it)
                    echoGuardHolders.remove(it)
                }
            }
            holders[id] = name
            if (guardEcho) echoGuardHolders.add(id)
            syncLocked()
            holders.size
        }
        Dbg.i(TAG, "音频占用 +$name#$id（持有者=$count）")
        return Token(id, name)
    }

    /**
     * 单实例放音源：同一时刻最多一个令牌，再次 hold 视为"刷新"。
     *
     * MusicPlayer 在每次 STATE_READY（缓冲恢复 / seek）都会重新 hold("music") 且不关旧令牌
     * （审核 M-03，music 模块本次不改）。按名字去重时这被掩盖了；改成真正引用计数后，
     * 旧令牌会永久泄漏、麦克风永远"放音中"。所以对单实例源保留"同名顶替"语义。
     * TTS 这类可能并发多条的来源**不在此列**，严格引用计数。
     */
    private val SINGLETON_SOURCES = setOf("music")

    private class Token(private val id: Long, private val name: String) : AutoCloseable {
        @Volatile
        private var closed = false

        override fun close() {
            val callbacks = ArrayList<() -> Unit>()
            val count = synchronized(lock) {
                if (closed) return@synchronized -1
                closed = true
                val removed = holders.remove(id) != null
                val guarded = echoGuardHolders.remove(id)
                // 回声保护窗口从"最后一个带回声的放音源结束"开始算；
                // 只要还有任何放音在继续，麦克风本来就不可信，不用记。
                if (removed && guarded && holders.isEmpty()) {
                    lastReleaseAt = android.os.SystemClock.uptimeMillis()
                }
                syncLocked(callbacks)
                holders.size
            }
            if (count < 0) return
            Dbg.i(TAG, "音频占用 -$name#$id（持有者=$count）")
            callbacks.forEach { runCatching(it) }
        }
    }

    /**
     * 所有放音源结束后执行一次。若当前没有占用则立即执行。
     * 用于音乐被语音助手暂停后，等 TTS 也结束再恢复。
     */
    fun whenUnmuted(action: () -> Unit) {
        val runNow = synchronized(lock) {
            if (holders.isEmpty()) true else {
                whenUnmuted += action
                false
            }
        }
        if (runNow) runCatching(action)
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

    private fun syncLocked(callbacks: MutableList<() -> Unit> = ArrayList()) {
        val muted = holders.isNotEmpty()
        if (muted != isMuted) {
            isMuted = muted
            Dbg.i(TAG, if (muted) "麦克风内容不可信（放音中），不送 ASR" else "麦克风恢复可信")
        }
        if (!muted && whenUnmuted.isNotEmpty()) {
            callbacks += whenUnmuted
            whenUnmuted.clear()
        }
    }
}
