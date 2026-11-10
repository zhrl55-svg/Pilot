package com.pilot.agent.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.pilot.agent.Dbg

/**
 * 唤醒词检测：sherpa-onnx zipformer KWS（4.6MB，打进 assets，不用推模型）。
 *
 * ## 为什么用 KWS 而不是让 ASR 自由解码
 * ASR 在长时间的环境音里会吐出大量碎片，还要每次跑完整个模型；
 * KWS 把解码约束在目标拼音路径上，常驻监听功耗低得多，误触发也少。
 *
 * ## 词表是动态生成的
 * 唤醒词改了不用改代码、不用换模型——[KwsKeywords] 用 ICU 现场把汉字转成音素行。
 * 但 ICU 转出来的音节不一定都在模型词表里（比如 ü 系列），
 * 只要有一行含未知 token，`createStream` 会直接抛
 * `Failed to create native OnlineStream`，整条链路哑掉。
 * 所以逐行校验，把坏的挑出来丢掉并打日志，别让一行坏词拖垮全部。
 */
class WakeWordDetector(private val context: Context) {

    @Volatile
    var ready = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var failed = false

    private val samples = FloatArray(AudioCapture.FRAME_SAMPLES)

    @Volatile
    var labels: List<String> = emptyList()
        private set

    private var loadedWith: List<String> = emptyList()
    private var fedFrames = 0L

    /** 模型认得的 token（tokens.txt 第一列），用来提前筛掉拼不出来的词 */
    private val vocab: Set<String> by lazy {
        runCatching {
            context.assets.open("$ASSET_DIR/tokens.txt").bufferedReader().useLines { seq ->
                seq.mapNotNull { line ->
                    line.substringBefore(' ').trim().takeIf { it.isNotEmpty() }
                }.toSet()
            }
        }.getOrDefault(emptySet())
    }

    fun load(words: List<String>): Boolean {
        val wanted = words.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) {
            lastError = "唤醒词为空"
            return false
        }
        if (ready && wanted == loadedWith) return true
        if (failed) return false

        val generated = KwsKeywords.lines(wanted)
        val keywordLines = generated.filter { line ->
            line.substringBefore('@').trim().split(' ').filter { it.isNotEmpty() }.all { it in vocab }
        }
        val dropped = wanted.filter { w -> keywordLines.none { it.endsWith("@$w") } }
        if (dropped.isNotEmpty()) {
            Dbg.w(TAG, "KWS 丢弃含未知音节的词：$dropped（可用词表 ${vocab.size} 个 token）")
        }
        if (keywordLines.isEmpty()) {
            lastError = "唤醒词转拼音失败（ICU 不可用或音节不在模型词表里）"
            Dbg.e(TAG, "KWS 跳过：$lastError")
            return false
        }

        if (ready) closeInternal()
        return try {
            val t0 = System.currentTimeMillis()
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = AudioCapture.SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$ASSET_DIR/encoder.int8.onnx",
                        decoder = "$ASSET_DIR/decoder.onnx",
                        joiner = "$ASSET_DIR/joiner.int8.onnx",
                    ),
                    tokens = "$ASSET_DIR/tokens.txt",
                    numThreads = 1,
                    debug = false,
                    provider = "cpu",
                    modelType = "zipformer2",
                ),
                maxActivePaths = 8,
                keywordsFile = "$ASSET_DIR/keywords.txt",
                keywordsScore = KEYWORD_SCORE,
                keywordsThreshold = KEYWORD_THRESHOLD,
                numTrailingBlanks = 1,
            )
            // 模型在 assets 里，所以这里传 context.assets（不是 null）。
            // 注意：离线 ASR 那边模型在 filesDir，必须传 null——两者相反，别抄错。
            val next = KeywordSpotter(context.assets, config)
            spotter = next
            // createStream 直接吃多行词表，覆盖 keywordsFile 的内容
            stream = next.createStream(keywordLines.joinToString("\n"))
            labels = wanted
            loadedWith = wanted
            ready = true
            failed = false
            lastError = null
            fedFrames = 0
            Dbg.i(TAG, "KWS 就绪 words=$wanted ${System.currentTimeMillis() - t0}ms")
            true
        } catch (t: Throwable) {
            failed = true
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            closeInternal()
            Dbg.e(TAG, "KWS 初始化失败 $lastError")
            false
        }
    }

    /**
     * 喂一帧 16k PCM16。命中返回命中的词，并立即 reset 准备下一次。
     * 返回 null = 这一帧没命中。
     */
    @Synchronized
    fun feed(frame: ShortArray, len: Int): String? {
        if (!ready) return null
        val kws = spotter ?: return null
        val input = stream ?: return null
        val n = len.coerceAtMost(frame.size).coerceAtMost(samples.size)
        if (n <= 0) return null

        return try {
            for (i in 0 until n) samples[i] = frame[i] / 32768f
            val chunk = if (n == samples.size) samples else samples.copyOf(n)
            input.acceptWaveform(chunk, AudioCapture.SAMPLE_RATE)

            // 心跳：真机上「一次都没命中」时，靠这行区分是模型不认词还是代码没被调用
            fedFrames++
            if (fedFrames % HEARTBEAT_FRAMES == 0L) {
                Dbg.i(TAG, "KWS 已喂 ${fedFrames * AudioCapture.FRAME_MS / 1000}s 音频，未命中")
            }

            var hit: String? = null
            while (kws.isReady(input)) {
                kws.decode(input)
                val keyword = kws.getResult(input).keyword
                if (keyword.isBlank()) continue
                val label = labels.firstOrNull { keyword.contains(it) } ?: keyword
                Dbg.i(TAG, "KWS 命中「$label」")
                hit = label
                break
            }
            if (hit != null) kws.reset(input)
            hit
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            ready = false
            failed = true
            closeInternal()
            Dbg.e(TAG, "KWS 识别失败 $lastError")
            null
        }
    }

    @Synchronized
    fun reset() {
        if (!ready) return
        val kws = spotter ?: return
        val input = stream ?: return
        runCatching { kws.reset(input) }
    }

    fun release() {
        failed = true
        closeInternal()
    }

    private fun closeInternal() {
        runCatching { stream?.release() }
        runCatching { spotter?.release() }
        stream = null
        spotter = null
        ready = false
    }

    private companion object {
        const val TAG = "PilotVoice"
        const val ASSET_DIR = "kws"
        const val KEYWORD_SCORE = 2.0f
        const val KEYWORD_THRESHOLD = 0.12f

        /** 100 帧 = 2 秒打一次心跳，太密会把日志淹掉 */
        const val HEARTBEAT_FRAMES = 100L
    }
}
