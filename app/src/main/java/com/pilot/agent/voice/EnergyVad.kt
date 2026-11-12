package com.pilot.agent.voice

import com.pilot.agent.Dbg
import kotlin.math.sqrt

/**
 * 能量 VAD：自适应噪声底 + 双阈值，把连续音频切成一句一句。
 *
 * 为什么不用 silero：要 onnxruntime + 2MB 模型，而手机场景（底噪恒定、人声明显高于底噪）
 * 能量法足够，零依赖零延迟。先跑通，不够再换。
 *
 * ★ 两个"静默"别混：
 *   - [minSilenceMs]（默认 800ms）判断"这句说完了"，触发识别；
 *   - 会话级休眠是另一回事，在 VoiceInput 里。
 */
class EnergyVad(
    private val onSpeechStart: () -> Unit,
    private val onSegment: (ShortArray) -> Unit,
    private val minSilenceMs: () -> Int = { DEFAULT_SILENCE_MS },
) {

    private var noiseFloor = 120.0
    private var voicedFrames = 0
    private var silentFrames = 0
    private val preRoll = ArrayDeque<ShortArray>()
    private val segment = ArrayList<Short>()
    private var inSpeech = false

    /** 当前是否正处在一句话中间（监听超时兜底用）。 */
    @get:Synchronized
    val isInSpeech: Boolean get() = inSpeech

    @Synchronized
    fun reset() {
        voicedFrames = 0
        silentFrames = 0
        inSpeech = false
        segment.clear()
        preRoll.clear()
    }

    @Synchronized
    fun feed(frame: ShortArray, len: Int) {
        val rms = rms(frame, len)

        // 没在说话时缓慢跟踪噪声底：升得慢、降得快，避免被一次咳嗽带跑
        if (!inSpeech) {
            noiseFloor = if (rms < noiseFloor) {
                noiseFloor * 0.90 + rms * 0.10
            } else {
                noiseFloor * 0.995 + rms * 0.005
            }.coerceIn(40.0, 3000.0)
        }

        val threshold = maxOf(noiseFloor * 2.6, MIN_RMS)

        if (!inSpeech) {
            preRoll.addLast(frame.copyOf(len))
            while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()

            if (rms > threshold) {
                voicedFrames++
                if (voicedFrames >= 3) {
                    inSpeech = true
                    silentFrames = 0
                    segment.clear()
                    // 带上开口前的 300ms，别把首字吃掉
                    preRoll.forEach { segment.addAll(it.toList()) }
                    preRoll.clear()
                    onSpeechStart()
                }
            } else {
                voicedFrames = 0
            }
            return
        }

        segment.addAll(frame.take(len))

        if (rms > threshold) {
            silentFrames = 0
        } else {
            silentFrames++
        }

        val silentMs = silentFrames * AudioCapture.FRAME_MS
        val totalMs = segment.size * 1000 / AudioCapture.SAMPLE_RATE
        if (silentMs < minSilenceMs() && totalMs < MAX_SEGMENT_MS) return

        inSpeech = false
        voicedFrames = 0
        silentFrames = 0
        val pcm = segment.toShortArray()
        segment.clear()

        // 太短的是噪音（关门声、衣物摩擦），直接丢
        if (pcm.size * 1000 / AudioCapture.SAMPLE_RATE >= MIN_SPEECH_MS) {
            Dbg.i(TAG, "VAD 切出一句 ${pcm.size * 1000 / AudioCapture.SAMPLE_RATE}ms")
            onSegment(pcm)
        } else {
            Dbg.i(TAG, "VAD 丢弃过短片段 ${pcm.size * 1000 / AudioCapture.SAMPLE_RATE}ms")
        }
    }

    /** 外部强制收尾（用户手动点"说完了"） */
    @Synchronized
    fun flush() {
        if (!inSpeech) return
        inSpeech = false
        val pcm = segment.toShortArray()
        segment.clear()
        if (pcm.isNotEmpty()) onSegment(pcm)
    }

    private fun rms(buf: ShortArray, len: Int): Double {
        if (len <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until len) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / len)
    }

    companion object {
        private const val TAG = "PilotVoice"
        private const val MIN_RMS = 260.0
        private const val DEFAULT_SILENCE_MS = 800
        private const val MIN_SPEECH_MS = 300
        private const val MAX_SEGMENT_MS = 15_000
        private const val PRE_ROLL_FRAMES = 15 // 300ms
    }
}
