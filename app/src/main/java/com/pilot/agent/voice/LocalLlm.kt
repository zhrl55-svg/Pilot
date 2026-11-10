package com.pilot.agent.voice

import android.content.Context
import android.os.Build
import com.pilot.agent.Dbg
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.alibaba.mnnllm.android.llm.LlmSession
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 进程内本地大模型（MNN-LLM + Qwen3-0.6B 4bit）。
 *
 * 模型跑在自己进程里，不经过 127.0.0.1、不依赖别的 App 活着——这点比走 MNN Chat 的 HTTP
 * 方案强得多（它有俩死穴：退后台端口就没了、队列卡死要重启进程）。
 *
 * ## 模型放哪
 * `filesDir/models/mnn-llm/`，5 个文件（直接从 MNN Chat 下载的那份拷过来，不用转换）：
 * ```
 * config.json       502B    运行参数
 * llm_config.json   3.2KB   结构 + jinja 模板
 * llm.mnn           931KB   计算图
 * llm.mnn.weight    164MB   int4 权重
 * tokenizer.mtok    1.6MB   词表
 * ```
 *
 * ## 线程与内存
 * 加载约 2 秒、常驻约 300MB，必须后台线程初始化且只初始化一次（单例）。
 */
class LocalLlm private constructor(private val context: Context) {

    private val lock = ReentrantLock()
    private var session: LlmSession? = null
    private var initTried = false
    private var initFailed = false

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var lastLoadMs: Long = -1
        private set

    @Volatile
    var lastGenerateMs: Long = -1
        private set

    @Volatile
    var lastError: String? = null
        private set

    /**
     * 模型目录。
     *
     * 优先用 `mnn-llm-0.3b`（Pocket-0.3B，权重 164MB，解码约 90 t/s），
     * 没有就退回 `mnn-llm`（Qwen3-0.6B，335MB，约 60 t/s）。
     *
     * 不做「设置里选」是因为实测差别只在速度和稳定性，用户没必要关心——
     * 装了快的就用快的。要对比时把 0.3b 目录改名即可。
     */
    fun modelDir(): File {
        val fast = File(context.filesDir, "models/mnn-llm-0.3b")
        if (File(fast, "llm.mnn.weight").isFile) return fast
        return File(context.filesDir, "models/mnn-llm")
    }

    fun modelPresent(): Boolean {
        val d = modelDir()
        return File(d, "llm.mnn").exists() &&
            File(d, "llm.mnn.weight").exists() &&
            File(d, "tokenizer.mtok").exists() &&
            File(d, "config.json").exists()
    }

    /** .so 只有 arm64-v8a，x86 模拟器上加载会抛 UnsatisfiedLinkError */
    /**
     * MNN 的 .so 只有 arm64-v8a（官方只发这一份），x86_64 模拟器上 dlopen 会直接
     * "library not found"——APK 里同时有 x86_64 库时 PackageManager 会定
     * primaryCpuAbi=x86_64，不会走 ARM 翻译层。
     * 所以端侧 LLM 必须在 arm64 真机上验证。
     */
    fun abiSupported(): Boolean = isNativeArm()

    /**
     * 模型是否带思考模式，需要在 prompt 末尾加 `/no_think` 关掉。
     *
     * Qwen3 的 jinja 模板里有 enable_thinking，但 native 侧不套 jinja，于是默认思考：
     * 一句自我介绍要写 150+ token、23 秒才出结果。`/no_think` 是官方软开关，实测 23s → 0.6s。
     */
    val wantsNoThink: Boolean
        get() = runCatching {
            File(modelDir(), "llm_config.json").readText().contains("enable_thinking")
        }.getOrDefault(false)

    fun ensureReady(): Boolean {
        if (ready) return true
        // 同 LocalAsr：等导入线程把文件搬完
        ModelStore.awaitReady()
        lock.withLock {
            if (ready) return true
            if (initTried && initFailed) return false
            initTried = true

            if (!abiSupported()) {
                initFailed = true
                lastError = "当前 ABI（${Build.SUPPORTED_ABIS.joinToString()}）不支持端侧 LLM，需要 arm64 真机"
                Dbg.w(TAG, lastError!!)
                return false
            }
            if (!modelPresent()) {
                initFailed = true
                lastError = "模型文件不全：${modelDir().absolutePath}"
                Dbg.w(TAG, lastError!!)
                return false
            }

            val t0 = System.currentTimeMillis()
            try {
                val dir = modelDir()
                // ★ configPath 必须是 config.json 这个【文件】的路径，不是模型目录。
                //   native 拿它去 readText，再从路径反推模型目录。传目录会报
                //   "Module load failed for config: <dir>"。
                val cfg = File(dir, "config.json")

                // ★ max_new_tokens 必须写进 init 的合并配置。
                //   模型自带 config.json 没这个字段，native 默认只生成 1 个 token
                //   （实测 decode_len=1，输出停在「我」一个字）。单独调
                //   updateMaxNewTokens 也不生效，只有 init 时带上才行。
                val runtimeConfig = org.json.JSONObject(cfg.readText())
                    .put("max_new_tokens", MAX_NEW_TOKENS)
                    // 模型自带 repetition_penalty=1.0 等于不惩罚，小模型很容易复读用户原话。
                    // 1.15 明显缓解，再高开始胡言乱语。
                    .put("repetition_penalty", 1.15)
                    .put("presence_penalty", 0.3)
                    .toString()

                // keep_history=false：手机指令之间没有上下文依赖，每句独立更稳，
                // 也避免 KV cache 越滚越大拖慢 prefill
                val extra = """{"is_r1":false,"mmap_dir":"","keep_history":false}"""

                val s = LlmSession()
                if (!s.init(cfg.absolutePath, runtimeConfig, extra)) {
                    initFailed = true
                    lastError = "initNative 返回 false，加载失败"
                    Dbg.e(TAG, lastError!!)
                    return false
                }
                s.setMaxNewTokens(MAX_NEW_TOKENS)
                session = s
                lastLoadMs = System.currentTimeMillis() - t0
                ready = true
                lastError = null
                Dbg.i(TAG, "本地LLM 就绪，加载 ${lastLoadMs}ms noThink=$wantsNoThink")
                return true
            } catch (t: Throwable) {
                initFailed = true
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                Dbg.e(TAG, "本地LLM 初始化失败 $lastError")
                return false
            }
        }
    }

    /**
     * 生成。@return null = 不可用
     */
    fun generate(prompt: String, onDelta: ((String) -> Unit)? = null): String? {
        if (!ensureReady()) return null
        val s = session ?: return null
        val t0 = System.currentTimeMillis()
        var firstTokenMs = -1L
        // ★ 文本是从 onProgress 流式回调里攒出来的，不是从返回的 map 取。
        //   map 里只有 prefill_time / decode_len / decode_time 这些指标。
        val acc = StringBuilder()
        return try {
            val result = s.generate(
                prompt,
                false,
                object : GenerateProgressListener {
                    override fun onProgress(progress: String?): Boolean {
                        if (!progress.isNullOrEmpty()) {
                            if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis() - t0
                            acc.append(progress)
                            onDelta?.invoke(progress)
                        }
                        // ★ 返回值语义是「要不要【停止】」，不是「要不要继续」。
                        //   按直觉写 true 的后果：每轮只生成 1 个 token，日志还一切正常，
                        //   极难联想到是这个。
                        return false
                    }
                },
            )
            lastGenerateMs = System.currentTimeMillis() - t0
            val fromStream = acc.toString().trim()
            val fromMap = (result["text"] as? String)?.trim().orEmpty()
            val text = fromStream.ifBlank { fromMap }
            Dbg.i(TAG, "本地LLM 生成 ${lastGenerateMs}ms（首字 ${firstTokenMs}ms）→ 「${text.take(80)}」")
            text
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Dbg.e(TAG, "本地LLM 生成异常 $lastError")
            null
        }
    }

    fun release() {
        lock.withLock {
            runCatching { session?.release() }
            session = null
            ready = false
            initTried = false
            initFailed = false
        }
    }

    companion object {
        private const val TAG = "PilotVoice"

        /**
         * 单次生成上限。
         *
         * 从 256 降到 96：Pocket-0.3B 实测会很实在地把 256 token 写满（1119ms / 100 多字），
         * 而 agent 场景的输出是「一个 JSON 动作」或「一句 20 字内的回答」，96 绰绰有余。
         * 降下来能把胡言乱语的尾部直接砍掉，生成时间减半。
         */
        const val MAX_NEW_TOKENS = 96

        @Volatile
        private var instance: LocalLlm? = null

        fun get(context: Context): LocalLlm = instance ?: synchronized(this) {
            instance ?: LocalLlm(context.applicationContext).also { instance = it }
        }
    }
}
