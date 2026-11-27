package com.pilot.agent.voice

import android.content.Context
import android.os.SystemClock
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
 *
 * ## 线程（审核 V-06）
 * - [feed] / [reset] 在采集线程，持有对象锁（this）；
 * - [load] 可能在主线程（改唤醒词 / 打断词）或 worker 线程（自动恢复）调用：
 *   用 [loadLock] 串行化多次 load，**先在锁外建好新 spotter/stream，再在对象锁内原子替换**，
 *   最后释放旧实例——音频线程不会在 native 释放途中 acceptWaveform（以前可能 SIGSEGV）；
 * - 失败不再"永久 failed"：按指数退避允许重试（[reloadIfNeeded]），不用重启 App。
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

    /** 最近一次加载 / 识别失败；为 true 时在 [nextRetryAt] 之前不重试。 */
    @Volatile
    private var failed = false

    @Volatile
    private var failCount = 0

    @Volatile
    private var nextRetryAt = 0L

    /** 调用过 [release]：彻底停用，不再自动恢复。 */
    @Volatile
    private var released = false

    /** 串行化 [load]（与 feed 用的对象锁分开，避免加载模型时阻塞音频线程）。 */
    private val loadLock = Any()

    private val samples = FloatArray(AudioCapture.FRAME_SAMPLES)

    @Volatile
    var labels: List<String> = emptyList()
        private set

    @Volatile
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

    fun load(words: List<String>): Boolean = synchronized(loadLock) { loadLocked(words) }

    private fun loadLocked(words: List<String>): Boolean {
        val wanted = words.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) {
            lastError = "唤醒词为空"
            return false
        }
        if (ready && wanted == loadedWith) return true
        if (failed) {
            val wait = nextRetryAt - SystemClock.uptimeMillis()
            if (wait > 0) {
                Dbg.w(TAG, "KWS 上次失败（${lastError}），${wait}ms 后才允许重试")
                return false
            }
        }
        released = false

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

        var next: KeywordSpotter? = null
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
                // 词表里除了唤醒词/打断词，还常驻了导航候选的 5 个序号词；
                // 8 条路径会挤掉一部分词，直接给到 16。
                maxActivePaths = 16,
                // ★ 这个文件必须留空，不能是模型自带的示例词表。
                //   assets 里原本那份是模型自带的示例（你好问问 / 小爱同学 / 小艺小艺 …），
                //   一旦传进来它就真的在监听：用户从没配过「你好问问」，环境音却反复命中，
                //   表现就是"没人说话它自己醒了"（11-12 真机日志：唤醒「你好问问」→
                //   声纹 0.21 拒绝 → 仍进聆听，随后一直「正在聆听」不休眠）。
                //   坑：**不能写注释行**——native 用 stof 解析每行，遇到 "# …" 直接
                //   SIGABRT（实测崩溃 7 次）；也不能传空串（要求 file / buf 至少给一个）。
                //   所以文件留成 0 字节，词表统一由下面的 createStream(keywordLines) 给。
                keywordsFile = "$ASSET_DIR/keywords.txt",
                keywordsScore = KEYWORD_SCORE,
                keywordsThreshold = KEYWORD_THRESHOLD,
                numTrailingBlanks = 1,
            )
            // 模型在 assets 里，所以这里传 context.assets（不是 null）。
            // 注意：离线 ASR 那边模型在 filesDir，必须传 null——两者相反，别抄错。
            val built = KeywordSpotter(context.assets, config)
            next = built
            // createStream 直接吃多行词表，覆盖 keywordsFile 的内容
            val nextStream = built.createStream(keywordLines.joinToString("\n"))

            // ★ 原子替换：feed() 持有同一把对象锁，替换后再没有线程引用旧实例
            val (oldStream, oldSpotter) = synchronized(this) {
                val prevStream = stream
                val prevSpotter = spotter
                spotter = built
                stream = nextStream
                labels = wanted
                loadedWith = wanted
                ready = true
                failed = false
                failCount = 0
                nextRetryAt = 0L
                lastError = null
                fedFrames = 0
                Pair(prevStream, prevSpotter)
            }
            runCatching { oldStream?.release() }
            runCatching { oldSpotter?.release() }
            Dbg.i(TAG, "KWS 就绪 words=$wanted ${System.currentTimeMillis() - t0}ms")
            true
        } catch (t: Throwable) {
            // 新实例建到一半失败：只释放新的，旧实例（若有）继续用旧词表工作，不让链路哑掉
            runCatching { next?.release() }
            synchronized(this) {
                markFailedLocked("${t.javaClass.simpleName}: ${t.message}")
                // 没有旧实例可用时，记下这次想要的词，自动恢复按它重试；
                // 有旧实例时保持 loadedWith=旧词表（ready 仍为 true，旧词照常工作）。
                if (!ready) loadedWith = wanted
            }
            Dbg.e(TAG, "KWS 初始化失败 $lastError（第 $failCount 次，${nextRetryAt - SystemClock.uptimeMillis()}ms 后可重试）")
            false
        }
    }

    /** 调用方需持有对象锁。 */
    private fun markFailedLocked(msg: String) {
        failed = true
        failCount++
        lastError = msg
        val backoff = (RETRY_BASE_MS shl (failCount - 1).coerceIn(0, 6)).coerceAtMost(RETRY_MAX_MS)
        nextRetryAt = SystemClock.uptimeMillis() + backoff
    }

    /**
     * 识别失败（native 抛错）或加载失败后，到了退避时间就按上次的词表重载。
     * 会加载模型（几百毫秒），**不要在音频线程调**，交给 worker。
     * @return true = 当前可用
     */
    fun reloadIfNeeded(): Boolean {
        if (ready) return true
        if (released) return false
        val words = loadedWith
        if (words.isEmpty()) return false
        if (SystemClock.uptimeMillis() < nextRetryAt) return false
        Dbg.i(TAG, "KWS 自动恢复：按 $words 重新加载")
        return load(words)
    }

    /** 是否处在"失败待恢复"状态（供上层决定要不要调 [reloadIfNeeded]）。 */
    val needsReload: Boolean
        get() = !ready && !released && loadedWith.isNotEmpty() && SystemClock.uptimeMillis() >= nextRetryAt

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
            var rejected: String? = null
            while (kws.isReady(input)) {
                kws.decode(input)
                val keyword = kws.getResult(input).keyword
                if (keyword.isBlank()) continue
                val label = labels.firstOrNull { keyword.contains(it) } ?: keyword
                // ★ 只认自己词表里的词。native 侧如果还残留了模型自带的示例词
                //   （你好问问 / 小爱同学 …），这里直接滤掉，不让它唤醒。
                if (labels.none { keyword.contains(it) }) {
                    rejected = label
                    break
                }
                Dbg.i(TAG, "KWS 命中「$label」")
                hit = label
                break
            }
            if (rejected != null) {
                Dbg.w(TAG, "KWS 命中词表外的「$rejected」，忽略（示例词表残留）")
                kws.reset(input)
            }
            if (hit != null) kws.reset(input)
            hit
        } catch (t: Throwable) {
            // 已持有对象锁（@Synchronized）
            markFailedLocked("${t.javaClass.simpleName}: ${t.message}")
            closeInternal()
            Dbg.e(TAG, "KWS 识别失败 $lastError（将自动重载）")
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
        synchronized(loadLock) {
            synchronized(this) {
                released = true
                closeInternal()
            }
        }
    }

    /** 调用方需持有对象锁。 */
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
        // 0.12 过于敏感：真机实测打字/环境音下「不说了/别说了/第一个」三分钟误命中 20+ 次。
        // 0.22 仍低于官方示例（0.25），保留轻声唤醒的余量，同时明显压掉误报。
        const val KEYWORD_THRESHOLD = 0.22f

        /** 失败后首次重试等待；之后翻倍 */
        const val RETRY_BASE_MS = 2_000L

        /** 重试等待上限 */
        const val RETRY_MAX_MS = 60_000L

        /** 100 帧 = 2 秒打一次心跳，太密会把日志淹掉 */
        // 心跳只用于判断"模型没认词 vs 代码没被调用"，2 秒一条纯属刷屏；
        // 30 秒（1500 帧 × 20ms）足够定位问题，日志量降 15 倍。
        const val HEARTBEAT_FRAMES = 1500L
    }
}
