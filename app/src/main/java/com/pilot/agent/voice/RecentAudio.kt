package com.pilot.agent.voice

/**
 * 最近 N 毫秒音频的环形缓冲。
 *
 * 用途：唤醒词命中的那一刻，用户其实已经把唤醒词说完了。
 * 要验声纹就得回头拿**刚才**那段音频，而不是等下一句。
 */
class RecentAudio(windowMs: Int) {

    private val maxFrames = (windowMs / AudioCapture.FRAME_MS).coerceAtLeast(1)
    private val frames = ArrayDeque<ShortArray>()

    @Synchronized
    fun push(frame: ShortArray, len: Int) {
        if (len <= 0) return
        frames.addLast(frame.copyOf(len))
        while (frames.size > maxFrames) frames.removeFirst()
    }

    @Synchronized
    fun snapshot(): ShortArray {
        val total = frames.sumOf { it.size }
        val out = ShortArray(total)
        var pos = 0
        for (f in frames) {
            f.copyInto(out, pos)
            pos += f.size
        }
        return out
    }

    @Synchronized
    fun clear() = frames.clear()

    @Synchronized
    fun durationMs(): Int = frames.size * AudioCapture.FRAME_MS
}
