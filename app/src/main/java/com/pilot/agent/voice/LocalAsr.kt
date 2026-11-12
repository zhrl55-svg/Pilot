package com.pilot.agent.voice

import android.content.Context
import android.os.Build
import com.pilot.agent.Dbg
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 端侧语音识别：sherpa-onnx + SenseVoice-Small（int8）。
 *
 * 模型目录 `filesDir/models/asr-sensevoice/`：
 *   - model.int8.onnx   约 230MB
 *   - tokens.txt        约 309KB
 * 不进 APK——230MB 会让包体直接翻倍。
 *
 * ## 为什么用离线识别器
 * 上游 VAD 已经把整句切好，接口拿到的就是一整块 PCM，OfflineRecognizer 正好对上。
 * 要"边说边出字"得改成流式 + 部分结果，是另一套接口。
 *
 * ## ITN 必须关
 * SenseVoice 的 inverse text normalization 会把口语数字转成阿拉伯数字：
 * 「三里屯」变「3里屯」、「五道口」变「5道口」。这些是专名，不能规范化。
 */
class LocalAsr(private val context: Context) {

    private val lock = ReentrantLock()
    private var recognizer: OfflineRecognizer? = null
    private var initTried = false
    private var initFailed = false

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var lastModelLoadMs: Long = -1
        private set

    @Volatile
    var lastLatencyMs: Long = -1
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val recentLatency = ArrayDeque<Long>()

    fun modelDir(): File = File(context.filesDir, "models/asr-sensevoice")

    fun modelPresent(): Boolean {
        val d = modelDir()
        return modelFile(d).exists() && File(d, "tokens.txt").exists()
    }

    /**
     * 当前设备能不能原生跑。
     *
     * 注意不能只看 `SUPPORTED_ABIS` 里有没有 arm64：x86_64 模拟器的列表是
     * `x86_64,arm64-v8a`（靠 ARM 翻译层声称支持），真去加载 arm64 的 .so 要么走翻译
     * 慢十倍，要么直接挂。看**主 ABI** 才准。
     */
    /**
     * sherpa-onnx 的 AAR 带全 ABI（arm64-v8a / armeabi-v7a / x86_64），
     * 所以模拟器上也能原生跑，不用翻译层。
     */
    fun abiSupported(): Boolean = isSupportedAbi()

    /** 模型目录里第一个 .onnx，int8 优先。换 SenseVoice 其它精度/paraformer 不用改代码。 */
    private fun modelFile(dir: File): File {
        File(dir, "model.int8.onnx").takeIf { it.exists() }?.let { return it }
        val onnx = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx", ignoreCase = true) }
            ?.sortedBy { if (it.name.contains("int8")) 0 else 1 }
            ?.firstOrNull()
        return onnx ?: File(dir, "model.onnx")
    }

    /**
     * 加载模型。阻塞，调用方放后台线程。幂等：成功过就返回 true，失败过不再重试
     * （否则每次说话都要卡 2 秒等一次失败的加载）。
     */
    fun ensureReady(): Boolean {
        if (ready) return true
        // 等模型导入结束再碰文件：读到半个 onnx 会 native abort，比多等几秒严重得多
        ModelStore.awaitReady()
        lock.withLock {
            if (ready) return true
            if (initTried && initFailed) return false
            initTried = true

            if (!abiSupported()) {
                initFailed = true
                lastError = "当前 ABI（${Build.SUPPORTED_ABIS.joinToString()}）不支持端侧 ASR，需要 arm64 真机"
                Dbg.w(TAG, lastError!!)
                return false
            }

            val dir = modelDir()
            val model = modelFile(dir)
            val tokens = File(dir, "tokens.txt")
            if (!model.exists() || !tokens.exists()) {
                initFailed = true
                lastError = "模型缺失：${model.absolutePath}"
                Dbg.w(TAG, lastError!!)
                return false
            }

            val t0 = System.currentTimeMillis()
            try {
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = model.absolutePath,
                            language = "zh",
                            useInverseTextNormalization = false,
                        ),
                        tokens = tokens.absolutePath,
                        numThreads = NUM_THREADS,
                        provider = "cpu",
                        debug = false,
                    ),
                    decodingMethod = "greedy_search",
                )
                // ★ assetManager 必须传 null。
                //   传 context.assets 会让 sherpa 走 AssetManager 读相对路径，和 filesDir 的
                //   绝对路径对不上，接着 FATAL "Load tokens.txt failed" 直接 native abort。
                //   那是进程级 abort，Kotlin 的 try/catch 抓不到，logcat 里连 FATAL EXCEPTION
                //   都没有，只有 appDiedLocked reason=null。这个坑值得单写一行注释。
                recognizer = OfflineRecognizer(assetManager = null, config = config)
                lastModelLoadMs = System.currentTimeMillis() - t0
                ready = true
                lastError = null
                Dbg.i(TAG, "本地ASR 就绪，加载 ${lastModelLoadMs}ms，${model.name}")
                return true
            } catch (t: Throwable) {
                initFailed = true
                recognizer = null
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                Dbg.e(TAG, "本地ASR 初始化失败 $lastError")
                return false
            }
        }
    }

    /**
     * 整段识别。
     * @return null = 本地不可用（模型缺失/解码异常），由上层决定是否降级；"" = 确实没听清
     */
    fun transcribe(pcm: ShortArray, sampleRate: Int = SAMPLE_RATE): String? {
        if (!ready && !ensureReady()) return null
        if (sampleRate != SAMPLE_RATE) {
            Dbg.w(TAG, "本地ASR 采样率 $sampleRate != $SAMPLE_RATE，放弃")
            return null
        }
        val samples = FloatArray(pcm.size) { pcm[it] / 32768.0f }
        val t0 = System.currentTimeMillis()
        lock.withLock {
            val r = recognizer ?: return null
            var stream: com.k2fsa.sherpa.onnx.OfflineStream? = null
            try {
                stream = r.createStream()
                stream.acceptWaveform(samples, sampleRate)
                r.decode(stream)
                val text = r.getResult(stream).text.trim()
                val dt = System.currentTimeMillis() - t0
                lastLatencyMs = dt
                recordLatency(dt)
                // 识别正文可能包含用户随后提供的密码或验证码；日志只记录长度。
                Dbg.i(TAG, "本地ASR ${dt}ms ${"%.2f".format(pcm.size.toDouble() / sampleRate)}s → ${text.length} 字符（正文不写日志）")
                return text
            } catch (t: Throwable) {
                Dbg.e(TAG, "本地ASR 解码异常 ${t.javaClass.simpleName}: ${t.message}")
                return null
            } finally {
                runCatching { stream?.release() }
            }
        }
    }

    @Synchronized
    private fun recordLatency(ms: Long) {
        recentLatency.addLast(ms)
        while (recentLatency.size > 50) recentLatency.removeFirst()
    }

    @Synchronized
    fun statsText(): String {
        if (recentLatency.isEmpty()) return "n=0"
        val sorted = recentLatency.sorted()
        val p50 = sorted[sorted.size / 2]
        val p90 = sorted[minOf(sorted.size - 1, (sorted.size * 0.9).toInt())]
        return "p50=${p50}ms p90=${p90}ms n=${sorted.size}"
    }

    fun release() {
        lock.withLock {
            runCatching { recognizer?.release() }
            recognizer = null
            ready = false
            initTried = false
            initFailed = false
        }
    }

    companion object {
        private const val TAG = "PilotVoice"
        const val SAMPLE_RATE = 16_000

        /** 实测 2 线程比 1 线程快约 35%，4 线程只再快 8% 且明显发热 */
        private const val NUM_THREADS = 2
    }
}
