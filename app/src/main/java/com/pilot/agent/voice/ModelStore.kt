package com.pilot.agent.voice

import android.content.Context
import com.pilot.agent.Dbg
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 模型导入：外部私有目录 → 应用私有目录。
 *
 * ## 为什么需要这一层
 * 模型 554MB，不可能打进 APK。理论上有两条路把文件放进去：
 * 1. `adb push` 到 `/data/local/tmp` 再 `run-as cp` —— **HarmonyOS / 部分国产 ROM 上不生效**，
 *    `run-as` 的通配符和重定向行为跟 AOSP 不一致，实测拷贝静默失败（目录存在但空）。
 * 2. 放到应用自己的外部私有目录 —— App 自己有读写权限，不需要任何运行时权限，
 *    用数据线 MTP 也能直接拷进去。
 *
 * 所以走第 2 条：外部目录负责「收」，App 启动时自己「搬」。
 *
 * ## 体积校验
 * 下到一半的模型拿去 init 会 native abort（Kotlin catch 不到）。所以按体积下限判断完整性。
 */
object ModelStore {

    private const val TAG = "PilotVoice"

    private val latch = CountDownLatch(1)

    @Volatile
    var lastReport: String = "未导入"
        private set

    /** 期待的文件与最小体积（字节）。低于这个值认为是半包。 */
    private val EXPECTED: Map<String, Map<String, Long>> = mapOf(
        "asr-sensevoice" to mapOf(
            "model.int8.onnx" to 200L * 1024 * 1024,
            "tokens.txt" to 100L * 1024,
        ),
        "mnn-llm" to mapOf(
            "llm.mnn.weight" to 300L * 1024 * 1024,
            "llm.mnn" to 500L * 1024,
            "tokenizer.mtok" to 1024L * 1024,
            "config.json" to 100L,
            // 漏了这个 MNN 会加载失败，而且报错信息很含糊（只说 Module load failed）
            "llm_config.json" to 500L,
        ),
        "voiceprint" to mapOf(
            // 首选：sherpa 官方 3D-Speaker 模型（带 framework=3d-speaker metadata）
            "campplus_sv_zh_16k.onnx" to 20L * 1024 * 1024,
            // 备用：原始 WeSpeaker resnet34（缺 metadata，sherpa 下会崩，仅留档）
            "cnceleb_resnet34_LM.onnx" to 20L * 1024 * 1024,
        ),
        // Pocket-0.3B：权重 164MB，比 0.6B 少一半，解码约快 50%
        "mnn-llm-0.3b" to mapOf(
            "llm.mnn.weight" to 150L * 1024 * 1024,
            "llm.mnn" to 500L * 1024,
            "tokenizer.mtok" to 1024L * 1024,
            "config.json" to 100L,
            "llm_config.json" to 500L,
        ),
    )

    fun externalModelsDir(ctx: Context): File? =
        ctx.getExternalFilesDir(null)?.let { File(it, "models") }

    fun internalModelsDir(ctx: Context): File = File(ctx.filesDir, "models")

    /**
     * 等导入结束。ASR/LLM 加载模型前先调这个，避免和导入线程抢文件
     * （读到半个 onnx 会 native abort，比慢几秒严重得多）。
     */
    fun awaitReady(maxMs: Long = 60_000): Boolean {
        return try {
            latch.await(maxMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * 执行导入。阻塞，调用方放后台线程。
     * @return 拷贝的文件数
     */
    fun ensure(ctx: Context): Int {
        var copied = 0
        try {
            val src = externalModelsDir(ctx)
            if (src == null || !src.exists()) {
                lastReport = "外部目录无模型"
                return 0
            }
            val dst = internalModelsDir(ctx)

            for ((subDir, files) in EXPECTED) {
                val from = File(src, subDir)
                if (!from.isDirectory) continue
                val to = File(dst, subDir)
                to.mkdirs()

                for ((name, minSize) in files) {
                    val inFile = File(from, name)
                    if (!inFile.isFile) continue
                    if (inFile.length() < minSize) {
                        Dbg.w(TAG, "跳过 $subDir/$name：体积 ${inFile.length()} 低于下限 $minSize（半包）")
                        continue
                    }
                    val outFile = File(to, name)
                    if (outFile.isFile && outFile.length() == inFile.length()) continue

                    runCatching {
                        inFile.inputStream().use { i ->
                            outFile.outputStream().use { o -> i.copyTo(o, 64 * 1024) }
                        }
                        copied++
                        Dbg.i(TAG, "导入 $subDir/$name (${inFile.length() / 1024 / 1024}MB)")
                    }.onFailure {
                        Dbg.e(TAG, "导入失败 $subDir/$name: ${it.message}")
                        outFile.delete()
                    }
                }
            }
            lastReport = if (copied > 0) "导入 $copied 个文件" else "已是最新"
            Dbg.i(TAG, "ModelStore 完成：$lastReport")
            return copied
        } finally {
            latch.countDown()
        }
    }

    /** 诊断用：哪些文件缺、哪些是半包 */
    fun diagnose(ctx: Context): String {
        val dst = internalModelsDir(ctx)
        val sb = StringBuilder()
        for ((subDir, files) in EXPECTED) {
            for ((name, minSize) in files) {
                val f = File(File(dst, subDir), name)
                val state = when {
                    !f.exists() -> "缺失"
                    f.length() < minSize -> "半包(${f.length() / 1024 / 1024}MB)"
                    else -> "ok(${f.length() / 1024 / 1024}MB)"
                }
                if (sb.isNotEmpty()) sb.append(" · ")
                sb.append(name).append('=').append(state)
            }
        }
        return sb.toString()
    }
}
