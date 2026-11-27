package com.pilot.agent.assistant.llm

import com.pilot.agent.Dbg
import com.pilot.agent.agent.AgentIntentRouter
import com.pilot.agent.agent.AgentSettings
import com.pilot.agent.agent.IntentGate
import com.pilot.agent.runtime.AgentRuntime
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * 统一的 LLM 网关（审核报告 arch D5 / code M1）。
 *
 * - [http]：进程内唯一的根 OkHttpClient。各查询工具用 [derive] 派生，
 *   共享连接池和 Dispatcher 线程池，不再各自 new 一个。
 * - [chat]：唯一的 `/chat/completions` 调用实现，**可取消**（[await] 在协程取消时
 *   cancel 掉底层 Call，不会在“停止”后继续占着网络/再重试）。
 * - [assistantPersona] / [localChatPrompt]：Pilot 人设提示词的单一来源。
 * - [intentRouter]：进程级复用的首轮意图路由器（原来 UI/后台每个请求都 new 一个，
 *   每次连带新建 OkHttpClient 和线程池）。
 *
 * 注：agent/ 下的 CloudPlanner、AgentIntentRouter 与 service/RunReceiver 的请求
 * 不在本任务修改范围内，迁移到本网关见 artifacts/fix/routing.md。
 */
object LlmGateway {

    private const val TAG = "PilotLlm"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** 进程内唯一的根 client。 */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 按域派生 client：只改超时，连接池 / Dispatcher / 线程池与根 client 共享。
     * @param callSeconds 0 = 不设整体超时
     */
    fun derive(connectSeconds: Long, readSeconds: Long, callSeconds: Long = 0L): OkHttpClient =
        http.newBuilder()
            .connectTimeout(connectSeconds, TimeUnit.SECONDS)
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .callTimeout(callSeconds, TimeUnit.SECONDS)
            .build()

    /** 进程级复用的首轮意图路由器。 */
    val intentRouter: AgentIntentRouter by lazy { AgentIntentRouter { AgentSettings.load() } }

    data class Message(val role: String, val content: String)

    sealed interface ChatResult {
        data class Ok(val content: String) : ChatResult
        data class Failed(val reason: String) : ChatResult
    }

    /**
     * OpenAI 兼容 `/chat/completions`。不重试（重试由调用方按语义决定），
     * 协程取消会立刻 cancel 底层请求并向上抛 [CancellationException]。
     */
    suspend fun chat(
        settings: AgentSettings,
        messages: List<Message>,
        maxTokens: Int? = null,
        temperature: Double? = null,
        includeReasoningEffort: Boolean = true,
        timeoutMs: Long = 30_000L,
    ): ChatResult {
        if (!settings.cloudReady) return ChatResult.Failed("未配置云端模型")
        val body = JSONObject().apply {
            put("model", settings.model)
            put("stream", false)
            if (includeReasoningEffort) {
                put(
                    "reasoning_effort",
                    settings.reasoningEffort.takeIf { it in AgentSettings.REASONING_EFFORTS }
                        ?: AgentSettings.DEFAULT_REASONING_EFFORT,
                )
            }
            if (maxTokens != null) put("max_tokens", maxTokens)
            if (temperature != null) put("temperature", temperature)
            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    put(JSONObject().apply {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            })
        }.toString()
        val request = Request.Builder()
            .url(settings.baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        val client = http.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return try {
            val response = client.newCall(request).await()
            val (code, ok, raw) = response.use { resp ->
                val text = withContext(Dispatchers.IO) { resp.body?.string().orEmpty() }
                Triple(resp.code, resp.isSuccessful, text)
            }
            if (!ok) {
                Dbg.w(TAG, "chat http=$code")
                return ChatResult.Failed("HTTP $code")
            }
            val content = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
                ?.takeUnless { it == "null" }
                .orEmpty()
            if (content.isBlank()) ChatResult.Failed("模型没有返回内容") else ChatResult.Ok(content)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Dbg.w(TAG, "chat failed: ${t.javaClass.simpleName}")
            ChatResult.Failed(t.message?.take(80) ?: t.javaClass.simpleName)
        }
    }

    /** 云端闲聊的系统提示词（UI 与后台共用一份）。 */
    fun assistantPersona(now: String, memoryContext: String): String {
        val memoryPrompt = if (memoryContext.isBlank()) "" else {
            "\n与本轮相关的用户长期记忆（仅用于回答当前问题）：\n$memoryContext"
        }
        return "你是手机助手 Pilot，运行在这台安卓手机上。现在是 $now。" +
            "用一到两句话回答，简短口语化，不要用列表。" +
            "你能开关应用、查天气、股票和火车余票、播音乐、做导航，" +
            "也能在明确请求下查看设备状态、读取指定文件和管理本机长期记忆。" +
            "你没有实时联网数据时不要编造天气、股价、新闻等实时信息，直接说需要查询。" +
            IntentGate.SELF_KNOWLEDGE +
            memoryPrompt +
            "下面是最近的对话，接住其中的指代和上下文。"
    }

    /** 端侧 Qwen 模板（UI 与后台共用一份）。 */
    fun localChatPrompt(now: String, memoryContext: String, text: String, noThink: Boolean): String {
        val memoryPrompt = if (memoryContext.isBlank()) "" else "相关长期记忆：\n$memoryContext\n"
        val noThinkTag = if (noThink) "/no_think" else ""
        return "system\n你是手机助手 Pilot，运行在这台安卓手机上，现在是 $now。" +
            "能开关应用、查天气股票和火车余票、播音乐、做导航。" +
            IntentGate.SELF_KNOWLEDGE +
            memoryPrompt +
            "回答简短口语化，不超过 25 个字。不知道的实时信息不要编造。$noThinkTag\n" +
            "user\n$text\nassistant\n"
    }

    /**
     * 云端闲聊：带人设、记忆与最近 [history] 轮上下文。失败返回 null。
     */
    suspend fun cloudChat(
        settings: AgentSettings,
        text: String,
        now: String,
        memoryContext: String,
        history: List<Pair<String, String>>,
    ): String? {
        if (!settings.cloudReady) return null
        val messages = buildList {
            add(Message("system", assistantPersona(now, memoryContext)))
            val turns = settings.chatContextTurns.coerceIn(0, 20)
            if (turns > 0) {
                history.takeLast(turns).forEach { (q, a) ->
                    add(Message("user", q))
                    add(Message("assistant", a))
                }
            }
            add(Message("user", text))
        }
        return when (val r = chat(settings, messages)) {
            is ChatResult.Ok -> r.content.trim()
            is ChatResult.Failed -> {
                Dbg.w(TAG, "云端对话失败：${r.reason}")
                null
            }
        }
    }

    /** 端侧小模型回答；模型缺失/加载失败返回 null。在 IO 线程调用。 */
    fun localChat(text: String, now: String, memoryContext: String): String? {
        val llm = AgentRuntime.localLlm
        if (!llm.abiSupported() || !llm.modelPresent()) return null
        if (!llm.ensureReady()) return null
        return llm.generate(localChatPrompt(now, memoryContext, text, llm.wantsNoThink))
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /** 端侧模型是否具备可用条件（不触发加载）。 */
    fun localChatAvailable(): Boolean {
        val llm = AgentRuntime.localLlm
        return llm.abiSupported() && llm.modelPresent()
    }

    /**
     * “简单闲聊”判定：只用于 preferOnDevice 时决定能否完全不上云。
     * 条件刻意收紧：短句、没有任何动作/查询动词。拿不准就交给云端路由。
     */
    fun looksLikeSimpleSmallTalk(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 30) return false
        if (Regex("https?://", RegexOption.IGNORE_CASE).containsMatchIn(t)) return false
        return ACTION_OR_LOOKUP.none { t.contains(it) }
    }

    private val ACTION_OR_LOOKUP = listOf(
        "打开", "关闭", "关掉", "开启", "启动", "设置", "调", "发", "回复", "打电话", "拨", "播放", "放首", "放一首",
        "导航", "删除", "删掉", "提醒", "待办", "日程", "日历", "闹钟", "定时", "记住", "忘掉", "搜", "查", "找",
        "下载", "安装", "卸载", "截", "分享", "帮我", "给我", "天气", "股", "新闻", "票", "多少钱", "价格",
        "汇率", "比分", "最新", "今天", "明天", "现在几", "通知", "消息", "文件", "执行", "运行", "命令",
    )
}

/**
 * OkHttp 的可取消挂起调用：协程取消时 cancel 底层 Call。
 * 失败抛 IOException；调用方自行 close Response（建议 `use {}`）。
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        try {
            cancel()
        } catch (ignored: Throwable) {
        }
    }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
}
