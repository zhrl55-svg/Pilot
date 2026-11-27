package com.pilot.agent.voice

import com.pilot.agent.Dbg
import kotlin.math.sqrt

/**
 * 唤醒词检测前的能量预门控（审核 V-08 ①，降低常驻功耗）。
 *
 * 以前每 20ms 一帧无条件喂 KWS，zipformer 在完全安静的房间里也在持续解码。
 * 现在：
 * - 帧能量低于 `噪声底 × [RATIO]`（且低于绝对下限 [MIN_RMS] 时也算安静）→ 只存进预缓冲，不进 KWS；
 * - 能量一上来就"开门"：先把最近 [PRE_ROLL_FRAMES] 帧（500ms）回灌给 KWS，再接着喂当前帧，
 *   保证唤醒词开头的轻声母（"x"iao）不被吃掉；
 * - 开门后保持 [HANG_FRAMES] 帧（1.5s）——唤醒词尾音能量低、KWS 解码有延迟，不能一安静就关；
 * - 关门时调 [onClose]（上层用来 reset KWS），避免把隔着一段静音的两截声音拼成一个词。
 *
 * 只在采集线程使用，内部无锁；预缓冲是预分配的，不产生每帧垃圾。
 */
internal class KwsEnergyGate(
    private val onClose: () -> Unit = {},
) {

    private val ring = Array(PRE_ROLL_FRAMES) { ShortArray(AudioCapture.FRAME_SAMPLES) }
    private val ringLen = IntArray(PRE_ROLL_FRAMES)

    /** 下一个写入位置 */
    private var head = 0

    /** 预缓冲里的有效帧数 */
    private var count = 0

    private var noiseFloor = 120.0
    private var hang = 0

    /** 当前是否在喂 KWS */
    var isOpen: Boolean = false
        private set

    private var statFrames = 0
    private var statSkipped = 0

    /**
     * 处理一帧。
     * @param forceOpen 强制喂 KWS（播报/放音中要能听见打断词、聆听中要能听见唤醒词重来）
     * @param feed 把一帧交给 KWS，返回命中词
     * @return 命中词；null = 没命中（或这帧没喂）
     */
    fun process(
        frame: ShortArray,
        len: Int,
        forceOpen: Boolean,
        feed: (ShortArray, Int) -> String?,
    ): String? {
        val n = len.coerceAtMost(frame.size).coerceAtMost(AudioCapture.FRAME_SAMPLES)
        if (n <= 0) return null
        val rms = rms(frame, n)
        val threshold = maxOf(noiseFloor * RATIO, MIN_RMS)
        val loud = rms >= threshold

        // 噪声底：降得快、升得慢（和 EnergyVad 一致），稳态噪声（车内）几秒后被吸收进底噪
        val tracked = if (rms < noiseFloor) {
            noiseFloor * 0.90 + rms * 0.10
        } else {
            noiseFloor * 0.995 + rms * 0.005
        }
        noiseFloor = tracked.coerceIn(30.0, 3000.0)

        if (loud || forceOpen) {
            hang = HANG_FRAMES
        } else if (hang > 0) {
            hang--
        }

        stat(skipped = hang <= 0)

        if (hang > 0) {
            var hit: String? = null
            if (!isOpen) {
                isOpen = true
                hit = drainPreRoll(feed)
            }
            val now = feed(frame, n)
            return hit ?: now
        }

        if (isOpen) {
            isOpen = false
            runCatching(onClose)
        }
        push(frame, n)
        return null
    }

    /** 模式切换时清空（例如进入注册、停止采集）。 */
    fun reset() {
        head = 0
        count = 0
        hang = 0
        isOpen = false
    }

    private fun push(frame: ShortArray, n: Int) {
        System.arraycopy(frame, 0, ring[head], 0, n)
        ringLen[head] = n
        head = (head + 1) % PRE_ROLL_FRAMES
        if (count < PRE_ROLL_FRAMES) count++
    }

    private fun drainPreRoll(feed: (ShortArray, Int) -> String?): String? {
        var hit: String? = null
        val start = (head - count + PRE_ROLL_FRAMES) % PRE_ROLL_FRAMES
        for (i in 0 until count) {
            val idx = (start + i) % PRE_ROLL_FRAMES
            val h = feed(ring[idx], ringLen[idx])
            if (hit == null && h != null) hit = h
        }
        count = 0
        return hit
    }

    private fun stat(skipped: Boolean) {
        statFrames++
        if (skipped) statSkipped++
        if (statFrames >= STAT_FRAMES) {
            Dbg.i(TAG, "KWS 能量门控：过去 ${statFrames * AudioCapture.FRAME_MS / 1000}s 跳过 ${statSkipped * 100 / statFrames}% 帧（噪声底 ${noiseFloor.toInt()}）")
            statFrames = 0
            statSkipped = 0
        }
    }

    private fun rms(buf: ShortArray, len: Int): Double {
        var sum = 0.0
        for (i in 0 until len) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / len)
    }

    private companion object {
        const val TAG = "PilotVoice"

        /** 开门阈值 = 噪声底 × 1.5（审核建议值；比 VAD 的 2.6 宽松得多，宁可多喂） */
        const val RATIO = 1.5

        /** 绝对下限：极安静环境下噪声底很低，防止细微底噪也频繁开门 */
        const val MIN_RMS = 60.0

        /** 预缓冲 25 帧 = 500ms */
        const val PRE_ROLL_FRAMES = 25

        /** 开门后保持 75 帧 = 1.5s */
        const val HANG_FRAMES = 75

        /** 统计日志周期：3000 帧 = 60s */
        const val STAT_FRAMES = 3000
    }
}
