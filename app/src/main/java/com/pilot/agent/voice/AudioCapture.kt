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
 *
 * ## 出错自愈（审核 V-01 / R2）
 * 以前 `read()` 返回负数（来电、别的 App 抢麦、`ERROR_DEAD_OBJECT`、音频服务重启）后
 * 只是 `break`，`running` 仍为 true、AudioRecord 不释放、也不回调 —— 上层以为"已在录"，
 * 不会重建，唤醒永久失效（"僵尸麦克风"）。现在：
 * 1. 读错误 → 本轮 AudioRecord 由采集线程自己 stop+release，[isHealthy] 置 false，
 *    回调 [onError]（每次故障回调一次，不刷屏）；
 * 2. 采集线程按指数退避（[RETRY_INITIAL_MS] 起，翻倍，封顶 [RETRY_MAX_MS]）重建 AudioRecord，
 *    直到成功或被 [stop]；成功后回调 [onRecovered]；
 * 3. 重试期间 [isRunning] 仍为 true（会话还在），避免上层再并行创建第二个 AudioRecord。
 *
 * ## 线程约定
 * - [start] / [stop] 加锁，可从任意线程调用；
 * - AudioRecord 的 `release()` **只在采集线程里做**（read 返回之后），避免和 native read 并发；
 *   [stop] 只调 `AudioRecord.stop()` 来解除 read 阻塞。
 * - [onFrame] 在采集线程同步回调，传入的 ShortArray **会被复用**（下一帧覆盖），
 *   调用方需要保留数据时必须自己复制（[RecentAudio.push]、[EnergyVad.feed] 都会复制）。
 */
class AudioCapture(
    /** 每帧回调，在采集线程里调用，不要在这里做重活；数组会被复用，见类注释 */
    private val onFrame: (ShortArray, Int) -> Unit,
    /** 采集故障（启动失败 / 读错误）。可能在任意线程回调，调用方自行切线程。 */
    private val onError: (String) -> Unit = {},
    /** 故障后自动重建成功。在采集线程回调。 */
    private val onRecovered: () -> Unit = {},
) {

    /** 一次 start→stop 的采集会话。线程和 AudioRecord 都归它所有。 */
    private class Session {
        @Volatile
        var stopped = false

        @Volatile
        var record: AudioRecord? = null

        @Volatile
        var thread: Thread? = null
    }

    @Volatile
    private var session: Session? = null

    @Volatile
    var lastError: String? = null
        private set

    /** 当前是否在正常出帧（false = 未启动 / 故障重试中）。 */
    @Volatile
    var isHealthy: Boolean = false
        private set

    /** 是否处在"故障后自动重建"阶段。 */
    @Volatile
    var isRecovering: Boolean = false
        private set

    /**
     * 是否持续收到全零帧（Android 10+ 麦克风被其他前台应用"静默"时的表现）。
     *
     * 只做状态暴露和日志，不当成错误回调：个别机型的硬件降噪在极安静环境下也可能
     * 输出全零，误报"麦克风被占用"比漏报更打扰用户（需要真机验证后再决定是否上报）。
     */
    @Volatile
    var isSilenced: Boolean = false
        private set

    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        val rec = openRecord(report = true) ?: return false
        val s = Session()
        s.record = rec
        session = s
        isHealthy = true
        isRecovering = false
        isSilenced = false
        Dbg.i(TAG, "AudioCapture 启动 ${SAMPLE_RATE}Hz")
        val t = Thread({ runSession(s) }, "audio-capture")
        t.isDaemon = true
        s.thread = t
        t.start()
        return true
    }

    @Synchronized
    fun stop() {
        val s = session
        session = null
        isHealthy = false
        isRecovering = false
        isSilenced = false
        if (s == null) return
        s.stopped = true
        // 只 stop（解除 read 阻塞），release 交给采集线程，避免和 native read 并发。
        synchronized(s) {
            s.record?.let { runCatching { it.stop() } }
        }
        val t = s.thread
        if (t != null && t !== Thread.currentThread()) {
            t.interrupt() // 打断退避等待
            runCatching { t.join(1_000) }
            if (t.isAlive) Dbg.w(TAG, "AudioCapture 采集线程 1s 内未退出，交由其自行释放")
        }
        Dbg.i(TAG, "AudioCapture 停止")
    }

    /** 会话是否存在（含故障重试阶段）。上层据此判断"要不要新建采集"。 */
    val isRunning: Boolean
        get() = session?.let { s ->
            val th = s.thread
            !s.stopped && (th == null || th.isAlive || th.state == Thread.State.NEW)
        } == true

    // ------------------------------------------------------------ 内部

    @SuppressLint("MissingPermission")
    private fun openRecord(report: Boolean): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            note("设备不支持 16kHz 录音（minBuffer=$minBuf）", report)
            return null
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
            note("AudioRecord 创建失败：${t.message}", report)
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            note("AudioRecord 初始化失败 state=${rec.state}", report)
            runCatching { rec.release() }
            return null
        }
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            note("AudioRecord 开始录音失败：${t.message}", report)
            runCatching { rec.release() }
            return null
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            // 麦克风正被其他应用（通话、录音）独占时常见
            note("AudioRecord 未进入录音状态（麦克风可能被占用）", report)
            runCatching { rec.stop() }
            runCatching { rec.release() }
            return null
        }
        return rec
    }

    /** 采集线程主循环：读帧 → 出错则释放并按退避重建，直到 stop。 */
    private fun runSession(s: Session) {
        var backoff = RETRY_INITIAL_MS
        try {
            while (!s.stopped) {
                val rec = s.record
                if (rec == null) {
                    // —— 故障重试阶段 ——
                    if (!sleepInterruptibly(backoff) || s.stopped) break
                    val next = openRecord(report = false)
                    if (next == null) {
                        Dbg.w(TAG, "麦克风重建失败（${lastError}），${backoff}ms 后再试")
                        backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
                        continue
                    }
                    synchronized(s) {
                        if (s.stopped) {
                            runCatching { next.stop() }
                            runCatching { next.release() }
                            return
                        }
                        s.record = next
                    }
                    backoff = RETRY_INITIAL_MS
                    isRecovering = false
                    isHealthy = true
                    lastError = null
                    Dbg.i(TAG, "麦克风已恢复采集")
                    runCatching { onRecovered() }
                    continue
                }

                val err = readLoop(s, rec)
                synchronized(s) {
                    s.record = null
                    runCatching { rec.stop() }
                    runCatching { rec.release() }
                }
                if (s.stopped || err == null) break
                isHealthy = false
                isRecovering = true
                isSilenced = false
                note(err, report = true)
            }
        } finally {
            synchronized(s) {
                s.record?.let { r ->
                    runCatching { r.stop() }
                    runCatching { r.release() }
                }
                s.record = null
            }
            if (session === s) {
                isHealthy = false
                isRecovering = false
            }
        }
    }

    /** @return null = 正常结束（被 stop）；非 null = 读错误描述 */
    private fun readLoop(s: Session, rec: AudioRecord): String? {
        val frame = ShortArray(FRAME_SAMPLES)
        var fill = 0
        var zeroReads = 0
        var zeroFrames = 0
        while (!s.stopped) {
            val n = try {
                rec.read(frame, fill, FRAME_SAMPLES - fill)
            } catch (t: Throwable) {
                return "AudioRecord.read 异常：${t.javaClass.simpleName}"
            }
            if (s.stopped) return null
            if (n < 0) return "麦克风读取中断（${readErrorName(n)}）"
            if (n == 0) {
                // 正常阻塞读不会返回 0；连续 0 说明底层已失效，避免空转烧 CPU
                if (++zeroReads >= MAX_ZERO_READS) return "麦克风读取连续返回 0"
                continue
            }
            zeroReads = 0
            fill += n
            if (fill < FRAME_SAMPLES) continue
            fill = 0

            // 静默检测（只做状态，见 isSilenced 注释）
            if (isAllZero(frame)) {
                if (++zeroFrames == SILENCED_FRAMES) {
                    isSilenced = true
                    Dbg.w(TAG, "麦克风持续 ${SILENCED_FRAMES * FRAME_MS}ms 输出全零（可能被其他应用占用/静默）")
                }
            } else {
                if (isSilenced) Dbg.i(TAG, "麦克风恢复有效输入")
                zeroFrames = 0
                isSilenced = false
            }

            try {
                onFrame(frame, FRAME_SAMPLES)
            } catch (t: Throwable) {
                // 下游一帧处理异常不能把采集线程带走
                Dbg.e(TAG, "onFrame 处理异常：${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return null
    }

    private fun isAllZero(frame: ShortArray): Boolean {
        for (v in frame) if (v.toInt() != 0) return false
        return true
    }

    private fun readErrorName(code: Int): String = when (code) {
        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
        AudioRecord.ERROR -> "ERROR"
        else -> "code=$code"
    }

    /** @return false = 被打断（stop 中），调用方应退出 */
    private fun sleepInterruptibly(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (e: InterruptedException) {
        false
    }

    private fun note(msg: String, report: Boolean) {
        lastError = msg
        if (report) {
            Dbg.e(TAG, msg)
            runCatching { onError(msg) }
        } else {
            Dbg.w(TAG, msg)
        }
    }

    companion object {
        private const val TAG = "PilotVoice"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 故障后第一次重建等待；之后翻倍 */
        const val RETRY_INITIAL_MS = 1_000L

        /** 重建等待上限：通话可能持续很久，不设次数上限，但最多 30s 试一次 */
        const val RETRY_MAX_MS = 30_000L

        /** 连续多少次 read 返回 0 视为失效 */
        private const val MAX_ZERO_READS = 50

        /** 连续多少帧全零视为"被静默"（150 帧 = 3 秒） */
        private const val SILENCED_FRAMES = 150
    }
}
