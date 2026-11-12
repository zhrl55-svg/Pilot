package com.pilot.agent.agent

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class AgentIntentDecision(
    /** false = 直接回答；true = 进入设备/浏览器的 AgentLoop。 */
    val execute: Boolean,
    val answer: String? = null,
    val thought: String = "",
)

/**
 * Agent 模式的首轮模型决策。
 *
 * 这里让模型先阅读完整系统提示、近期对话和相关长期记忆，再判断用户是在提问
 * 还是需要操作设备。关键词 [IntentGate] 只负责云端不可用时的兜底，不再替 Agent
 * 模式做最终决定。
 */
class AgentIntentRouter(private val settingsProvider: () -> AgentSettings) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun decide(
        text: String,
        history: List<Pair<String, String>>,
        memoryContext: String,
        now: String,
    ): AgentIntentDecision = withContext(Dispatchers.IO) {
        val s = settingsProvider()
        if (!s.cloudReady) throw Planner.Unavailable("未配置云端模型 API Key")

        var lastError: Throwable? = null
        repeat(2) {
            try {
                val content = call(
                    s = s,
                    text = text,
                    history = history,
                    memoryContext = memoryContext,
                    now = now,
                    retryHint = lastError?.message,
                )
                return@withContext parse(content)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw Planner.Unavailable(lastError?.message ?: "Agent 首轮决策失败")
    }

    private fun call(
        s: AgentSettings,
        text: String,
        history: List<Pair<String, String>>,
        memoryContext: String,
        now: String,
        retryHint: String?,
    ): String {
        val memoryPrompt = if (memoryContext.isBlank()) {
            ""
        } else {
            "\n与本轮相关的用户长期记忆：\n$memoryContext"
        }
        val body = buildJsonObject {
            put("model", s.model)
            put("temperature", 0.0)
            put("max_tokens", 700)
            put(
                "reasoning_effort",
                s.reasoningEffort.takeIf { it in AgentSettings.REASONING_EFFORTS }
                    ?: AgentSettings.DEFAULT_REASONING_EFFORT,
            )
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT + "\n\n现在是 $now。$memoryPrompt")
                })
                history.takeLast(s.chatContextTurns.coerceIn(0, 20)).forEach { (question, answer) ->
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", question)
                    })
                    add(buildJsonObject {
                        put("role", "assistant")
                        put("content", answer)
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        text + if (retryHint.isNullOrBlank()) {
                            ""
                        } else {
                            "\n\n上一次没有按格式返回（$retryHint）。请只输出一个合法 JSON 对象。"
                        },
                    )
                })
            })
            put("response_format", buildJsonObject { put("type", "json_object") })
        }

        val request = Request.Builder()
            .url(s.baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer ${s.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        return client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${raw.take(240)}")
            val root = json.parseToJsonElement(raw).jsonObject
            root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.contentOrNull
                ?: error("响应缺少 content: ${raw.take(200)}")
        }
    }

    private fun parse(raw: String): AgentIntentDecision {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "Agent 决策没有返回 JSON 对象" }
        val obj = json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        val route = obj["route"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.lowercase()
            ?: error("Agent 决策缺少 route")
        val thought = obj["thought"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (route) {
            "reply", "answer", "chat" -> {
                val answer = obj["answer"]?.jsonPrimitive?.contentOrNull
                    ?.trim().orEmpty()
                require(answer.isNotBlank()) { "reply route 缺少 answer" }
                AgentIntentDecision(execute = false, answer = answer, thought = thought)
            }
            "agent", "execute", "query", "task" ->
                AgentIntentDecision(execute = true, thought = thought)
            else -> error("未知 Agent route: $route")
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        val SYSTEM_PROMPT = """
你是系统级手机助理 Pilot 的 Agent 调度模型。你的任务不是机械匹配关键词，而是先理解用户真正想得到什么，再由你决定直接回答还是交给设备执行层。

输出严格 JSON，不要 markdown：
{"thought":"一句话说明你的判断","route":"reply","answer":"直接回答用户的内容"}
{"thought":"一句话说明为什么需要执行","route":"agent"}

选择 reply：
- 知识问答、闲聊、观点、解释、自身能力/记忆/工具说明；
- 当前时间和已知上下文足以回答，不需要操作手机、浏览网页或读取实时数据。

选择 agent：
- 需要打开/点击/输入/设置应用，或控制手机、文件、终端、提醒、导航与媒体；
- 需要实时新闻、天气、股价、限行、票价、网页内容，或必须使用浏览器/MCP；
- 用户要求连续多步操作，或信息不足但可通过读取屏幕和工具继续推进。

规则：
1. 你必须自己判断，不要因为出现“查一下”“是什么”等词就固定走某条路径。
2. reply 要直接、自然、准确；普通问题 1 到 3 句，只有用户要求详解时才展开。
3. 绝不能声称已经完成了尚未执行的操作。
4. 不得操作 Pilot 自己的聊天框来重复提交用户问题；真正需要执行时只返回 agent，由执行层继续。
5. 有歧义且可能影响设备状态时选择 agent，让执行层读取上下文后再决定。
6. 涉及删除、支付、发送消息、提交订单等动作，执行层仍必须向用户确认。

${IntentGate.SELF_KNOWLEDGE}
""".trimIndent()
    }
}
