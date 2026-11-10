package com.pilot.agent.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.pilot.agent.Dbg

/**
 * 麦克风唯一所有者。
 *
 * 硬约束：**一个麦克风只能有一个 AudioRecord**。一旦我们持有它，系统 SpeechRecognizer
 * 就拿不到麦克风了，所以端侧 ASR 必须走自己录音 + 自己喂模型这条路，不能混用。
 *
 * 16kHz / 单声道 / PCM16，20ms 一帧（320 采样）。VAD 和 ASR 都按这个帧率喂。
 */
class AudioCapture(
    /** 每帧回调，在采集线程里调用，不要在这里做重活 */
    private val onFrame: (ShortArray, Int) -> Unit,
    private val onError: (String) -> Unit = {},
) {

    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @Volatile
    var lastError: String? = null
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            fail("设备不支持 16kHz 录音（minBuffer=$minBuf）")
            return false
        }
        val bufSize = maxOf(minBuf * 2, FRAME_SAMPLES * 2 * 8)
        val rec = try {
            // VOICE_RECOGNITION：保留系统 AEC，同时对近场人声衰减比 VOICE_COMMUNICATION 小。
            // 换 VOICE_COMMUNICATION 会把人声一起压低，实测识别率明显下降。
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufSize,
            )
        } catch (t: Throwable) {
            fail("AudioRecord 创建失败：${t.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            fail("AudioRecord 初始化失败 state=${rec.state}")
            rec.release()
            return false
        }
        record = rec
        running = true
        rec.startRecording()
        Dbg.i(TAG, "AudioCapture 启动 ${SAMPLE_RATE}Hz buf=$bufSize")

        thread = Thread {
            val frame = ShortArray(FRAME_SAMPLES)
            var fill = 0
            while (running) {
                val n = rec.read(frame, fill, FRAME_SAMPLES - fill)
                if (n < 0) {
                    Dbg.w(TAG, "AudioRecord.read 返回 $n，停止采集")
                    break
                }
                fill += n
                if (fill < FRAME_SAMPLES) continue
                onFrame(frame, FRAME_SAMPLES)
                fill = 0
            }
        }.apply {
            isDaemon = true
            name = "audio-capture"
            start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
        Dbg.i(TAG, "AudioCapture 停止")
    }

    val isRunning: Boolean get() = running

    private fun fail(msg: String) {
        lastError = msg
        Dbg.e(TAG, msg)
        onError(msg)
    }

    companion object {
        private const val TAG = "PilotVoice"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
