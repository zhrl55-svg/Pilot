package com.pilot.agent.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.pilot.agent.Dbg
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * 声纹门禁：确认"喊唤醒词的是不是主人"。
 *
 * ## 它解决什么
 * 唤醒词是公开的——旁人也能喊醒你的手机。声纹让 agent 只对主人响应，
 * 同时也能挡住 TTS 自己的回声（那是机器声，和主人 embedding 差得远）。
 *
 * ## 定位要说清楚
 * 这是**降低误触的软门禁，不是身份认证**。阈值调高了会把自己挡在外面
 * （车内/环境变化会让模板分数掉到 0.1 以下），所以策略是「宁可放过，不可错杀」：
 * 没注册模板、模型没就绪、音频不足，一律放行。
 *
 * ★ 但"放行"不等于"确认是主人"：VoiceInput 会把每句的校验结论（[SpeakerAuth]）交给上层，
 *   读通知/个人数据等敏感能力只认 VERIFIED 或"手机已解锁"（见 [PersonalDataGate]）。
 * ★ 注册必须由用户在界面里**显式发起**（[enrollMany] / [enroll]），不再有"首句自动注册"（审核 S6/V-07）。
 *
 * ## 模型
 * `filesDir/models/voiceprint/cnceleb_resnet34_LM.onnx`（25MB，WeSpeaker CN-Celeb）
 * 输出 256 维 embedding，比对用余弦相似度。
 */
class VoiceprintGate(private val context: Context) {

    private var extractor: SpeakerEmbeddingExtractor? = null
    private var initTried = false
    private var initFailed = false

    @Volatile
    var ready = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastScore: Float = -1f
        private set

    /**
     * 声纹模型文件（按优先级找）。
     *
     * ★ 优先 `campplus_sv_zh_16k.onnx`：这是 sherpa 官方打包的 3D-Speaker 模型，
     *   metadata 里带 `framework=3d-speaker`，extractor 能正确初始化。
     *   而原始的 WeSpeaker `cnceleb_resnet34_LM.onnx` **没有 sherpa 需要的 metadata**，
     *   native 构造会静默失败返回空指针，随后 `createStream` 直接 SIGSEGV（实测崩了 5 次才定位）。
     */
    fun modelFile(): File {
        val dir = File(context.filesDir, "models/voiceprint")
        for (name in MODEL_CANDIDATES) {
            val f = File(dir, name)
            if (f.isFile && f.length() > 1_000_000) return f
        }
        return File(dir, MODEL_CANDIDATES.first())
    }

    private fun templateFile(): File = File(context.filesDir, "voiceprint_template.bin")

    fun hasTemplate(): Boolean = templateFile().isFile && templateFile().length() > 256

    fun abiSupported(): Boolean = isSupportedAbi()

    /**
     * 声纹提取引擎是否可用。
     *
     * 曾经因为模型缺 metadata 导致 native 空指针崩溃，临时关成 false；
     * 换成 sherpa 官方 3D-Speaker 模型（带 `framework=3d-speaker`）后已恢复。
     */
    val engineAvailable: Boolean = true

    @Synchronized
    fun ensureReady(): Boolean {
        if (ready) return true
        if (initTried && initFailed) return false
        initTried = true

        if (!abiSupported()) {
            initFailed = true
            lastError = "当前 ABI 不支持声纹模型"
            return false
        }
        val model = modelFile()
        if (!model.isFile) {
            initFailed = true
            lastError = "声纹模型缺失：${model.absolutePath}"
            Dbg.w(TAG, lastError!!)
            return false
        }
        return try {
            val t0 = System.currentTimeMillis()
            val cfg = SpeakerEmbeddingExtractorConfig(
                model = model.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )
            // 模型在 filesDir，必须传 null（传 context.assets 会 native abort）
            Dbg.i(TAG, "声纹：开始构造 extractor model=${model.length()}B")
            extractor = SpeakerEmbeddingExtractor(null, cfg)
            Dbg.i(TAG, "声纹：extractor 构造完成")
            ready = true
            initFailed = false
            lastError = null
            // ★ 不要调 extractor.dim()：这个 JNI 调用在 P40 上会 **native abort**（进程直接消失，
            //   Kotlin 抓不到）。维度用模型固定值 256，反正后面按 embedding 实际长度用。
            Dbg.i(TAG, "声纹模型就绪 ${System.currentTimeMillis() - t0}ms")
            true
        } catch (t: Throwable) {
            initFailed = true
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Dbg.e(TAG, "声纹模型加载失败 $lastError")
            false
        }
    }

    /**
     * 从一段 16k 音频提取 embedding。
     * 加锁：worker 线程（逐句校验）和主线程（界面快捷注册）可能同时调用，
     * 且不能与 [release] 并发（native use-after-free）。
     */
    @Synchronized
    fun embed(pcm: ShortArray, sampleRate: Int = AudioCapture.SAMPLE_RATE): FloatArray? {
        if (!ensureReady()) return null
        if (sampleRate != AudioCapture.SAMPLE_RATE) return null
        // 太短的音频算出来的 embedding 不可靠
        if (pcm.size < AudioCapture.SAMPLE_RATE * 0.6f) {
            Dbg.i(TAG, "声纹跳过：音频仅 ${pcm.size * 1000 / AudioCapture.SAMPLE_RATE}ms")
            return null
        }
        val ex = extractor ?: return null
        var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
        return try {
            val samples = FloatArray(pcm.size) { pcm[it] / 32768.0f }
            Dbg.i(TAG, "声纹：createStream begin samples=${samples.size}")
            stream = ex.createStream()
            Dbg.i(TAG, "声纹：acceptWaveform begin")
            // ★ 不要调 inputFinished()：那是给流式 ASR 收尾用的，
            //   speaker extractor 不需要（nav-agent 的实现也没有），多调反而会走错分支。
            stream.acceptWaveform(samples, sampleRate)
            Dbg.i(TAG, "声纹：compute begin")
            val raw = ex.compute(stream)
            Dbg.i(TAG, "声纹：compute done dim=${raw?.size}")
            // L2 归一化：后面只比余弦，归不归一都行，归一后阈值更稳
            val norm = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
            if (norm <= 0f) null else FloatArray(raw.size) { raw[it] / norm }
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Dbg.e(TAG, "声纹提取失败 $lastError")
            null
        } finally {
            runCatching { stream?.release() }
        }
    }

    // ★ 首句自动注册由 VoiceInput.recognize() 在无模板时调用 enroll() 完成（老板要求恢复原始体验）。
    //   注意：第一个开口的人会成为"主人"——这是该策略的已知取舍。

    /** 用这段音频覆盖模板（注册 / 重新注册） */
    fun enroll(pcm: ShortArray): Boolean {
        val emb = embed(pcm) ?: return false
        return saveTemplate(emb, "单句")
    }

    /**
     * 多句注册：每句单独提 embedding，再求平均并重新归一化。
     *
     * 为什么不是把几段音频拼起来再提：拼接会跨过静音、换气和句间停顿，
     * 模型学到的是不连续的特征；而分句提 embedding 再平均，能把各句里随机的
     * 噪声方向抵消一部分，噪声环境（车内）下模板更稳。
     *
     * ★ 一致性校验（V-07）：各句 embedding 两两余弦相似度必须都 ≥ [ENROLL_CONSISTENCY]，
     *   否则判定"不是同一个人在念 / 混进了播报或电视声"，不落盘，[lastError] 给出原因。
     */
    fun enrollMany(pcms: List<ShortArray>): Boolean {
        val embeddings = pcms.mapNotNull { embed(it) }
        if (embeddings.isEmpty()) {
            if (lastError == null) lastError = "注册样本太短或模型不可用"
            return false
        }
        val dim = embeddings.first().size
        val usable = embeddings.filter { it.size == dim }
        if (usable.isEmpty()) return false
        if (pcms.size >= 2 && usable.size < 2) {
            lastError = "有效样本不足（${usable.size}/${pcms.size}），请每句说完整一点再注册"
            Dbg.w(TAG, "声纹注册：$lastError")
            return false
        }
        var minSim = 1f
        for (i in usable.indices) for (j in i + 1 until usable.size) {
            val sim = cosine(usable[i], usable[j])
            if (sim < minSim) minSim = sim
        }
        if (usable.size >= 2) {
            Dbg.i(TAG, "声纹注册：样本两两最低相似度 ${"%.3f".format(minSim)}")
            if (minSim < ENROLL_CONSISTENCY) {
                lastError = "几句录音的声音差别太大（可能混入了别人或播放的声音），请在安静处由本人重新注册"
                Dbg.w(TAG, "声纹注册被拒：$lastError")
                return false
            }
        }
        val avg = FloatArray(dim)
        for (e in usable) for (i in 0 until dim) avg[i] += e[i]
        for (i in 0 until dim) avg[i] /= usable.size
        val norm = sqrt(avg.sumOf { (it * it).toDouble() }).toFloat()
        if (norm <= 0f) return false
        for (i in 0 until dim) avg[i] /= norm
        Dbg.i(TAG, "声纹：${usable.size} 句样本平均完成")
        val saved = saveTemplate(avg, "${usable.size} 句平均")
        if (saved) lastError = null
        return saved
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    private fun saveTemplate(emb: FloatArray, label: String): Boolean = runCatching {
        val buf = ByteBuffer.allocate(emb.size * 4)
        emb.forEach { buf.putFloat(it) }
        templateFile().writeBytes(buf.array())
        Dbg.i(TAG, "声纹模板已保存（$label）dim=${emb.size}")
        true
    }.getOrElse {
        Dbg.e(TAG, "声纹模板保存失败 ${it.message}")
        false
    }

    private fun loadTemplate(): FloatArray? = runCatching {
        val f = templateFile()
        if (!f.isFile) return null
        val bytes = f.readBytes()
        val buf = ByteBuffer.wrap(bytes)
        FloatArray(bytes.size / 4) { buf.float }
    }.getOrNull()

    /**
     * 比对。
     * @return 余弦相似度；模型/模板不可用时返回 null（调用方应**放行**）
     */
    fun verify(pcm: ShortArray): Float? {
        if (!hasTemplate()) return null
        if (!ensureReady()) return null
        val emb = embed(pcm) ?: return null
        val tpl = loadTemplate() ?: return null
        if (tpl.size != emb.size) {
            Dbg.w(TAG, "声纹维度不匹配 tpl=${tpl.size} now=${emb.size}，请重新注册")
            return null
        }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in emb.indices) {
            dot += emb[i] * tpl[i]
            na += emb[i] * emb[i]
            nb += tpl[i] * tpl[i]
        }
        if (na == 0.0 || nb == 0.0) return null
        val score = (dot / (sqrt(na) * sqrt(nb))).toFloat()
        lastScore = score
        return score
    }

    fun clearTemplate() {
        runCatching { templateFile().delete() }
        Dbg.i(TAG, "声纹模板已清除")
    }

    @Synchronized
    fun release() {
        runCatching { extractor?.release() }
        extractor = null
        ready = false
        initTried = false
        initFailed = false
    }

    companion object {
        private const val TAG = "PilotVoice"

        /**
         * 模型候选（按优先级）。
         *
         * `campplus_sv_zh_16k.onnx` 是 sherpa 官方打包的 3D-Speaker 模型，
         * metadata 里带 `framework=3d-speaker`，extractor 能正常初始化；
         * 原始 WeSpeaker 的 resnet34 没有 sherpa 认的 metadata，native 构造静默失败
         * → 空指针 → createStream SIGSEGV（实测崩了 5 次才定位到）。
         */
        val MODEL_CANDIDATES = listOf("campplus_sv_zh_16k.onnx", "cnceleb_resnet34_LM.onnx")

        /**
         * 唤醒门禁阈值。
         *
         * nav-agent 在 P40 上实测：TTS 回声 0.02~0.11，主人 0.39~0.52。
         * 取 0.30 卡在中间偏主人一侧——宁可让旁人喊醒一次（他下不了指令，
         * 识别阶段还有机会拦），也不能让主人喊不醒。
         */
        const val WAKE_THRESHOLD = 0.30f

        /**
         * 注册样本两两最低相似度。
         *
         * 同一人不同句子之间通常明显高于"人声 vs TTS 回声"（实测回声对主人模板 0.02~0.11）；
         * 取 0.25 只拦"明显不是同一个声音"的情况，避免把车内噪声下的主人也拒掉。
         * 需要真机校准。
         */
        const val ENROLL_CONSISTENCY = 0.25f
    }
}
