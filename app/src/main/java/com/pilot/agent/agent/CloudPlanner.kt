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

/**
 * 云端慢思考：走 OpenAI 兼容的 /chat/completions。
 *
 * 为什么不用各家自己的 function calling：
 * 我们要对接的可能是 DeepSeek、通义、智谱、自建 vLLM，tools 的 JSON Schema 支持程度参差不齐，
 * 而「只输出一个 JSON 对象」这种约定在所有兼容接口上都成立，稳。
 */
class CloudPlanner(private val settingsProvider: () -> AgentSettings) : Planner {

    override val id: String = "cloud"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun decide(req: AgentRequest): AgentDecision = withContext(Dispatchers.IO) {
        val s = settingsProvider()
        if (!s.cloudReady) throw Planner.Unavailable("未配置云端模型 API Key")

        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                val content = call(s, req, if (attempt == 0) null else lastError?.message)
                return@withContext parse(content)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw Planner.Unavailable(lastError?.message ?: "云端规划失败")
    }

    private fun call(s: AgentSettings, req: AgentRequest, retryHint: String?): String {
        val history = req.history.takeLast(8)
        val userText = buildString {
            append("用户目标: ").append(req.goal).append('\n')
            if (history.isNotEmpty()) {
                append("已执行步骤:\n")
                history.forEachIndexed { i, h -> append(i + 1).append(". ").append(h).append('\n') }
            }
            req.skillHint?.let { append("命中历史技能（可参考）: ").append(it).append('\n') }
            if (req.snapshot.truncated) append("注意: 节点已截断，可能需要滚动。\n")
            append("当前屏幕:\n").append(req.snapshot.toPrompt())
            if (retryHint != null) append("\n上一次输出无法解析（").append(retryHint).append("），请只输出合法 JSON。")
        }

        val body = buildJsonObject {
            put("model", s.model)
            put("temperature", 0.0)
            put("max_tokens", 800)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userText)
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

        return client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${raw.take(240)}")
            val root = json.parseToJsonElement(raw).jsonObject
            root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.contentOrNull
                ?: error("响应缺少 content: ${raw.take(200)}")
        }
    }

    private fun parse(raw: String): AgentDecision {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "没有找到 JSON 对象" }
        val obj = json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        val thought = obj["thought"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val actionEl = obj["action"] ?: obj
        val action = json.decodeFromJsonElement(AgentAction.serializer(), actionEl)
        require(action.action.isNotBlank()) { "action 字段为空" }
        return AgentDecision(thought = thought, action = action)
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        val SYSTEM_PROMPT: String = """
你是手机操作助手 Pilot，代替用户在 Android 手机上完成任务。每次只输出一个动作。

严格输出 JSON，不要 markdown 代码块：
{"thought":"一句话推理","action":{"action":"tap_node","id":3}}

可用动作：
- tap_node {id}                     点击节点，首选
- tap {x,y}                         坐标点击，节点点不动时才用
- long_press {id}
- input {id,text}                   在输入框填内容
- swipe {x,y,x2,y2,ms}
- scroll {id,target:"forward"|"backward"}
- open {target}                     包名或应用名
- key {target:"back"|"home"|"recents"|"notifications"}
- wait {ms}
- done {summary}                    任务完成
- ask {summary}                     信息不足或需要用户确认

规则：
1. 优先 tap_node 并引用列表里的节点 id，不要自己推算坐标。
2. 同一动作连续失败两次就换策略，绝不重复。
3. 目标内容可能不在当前屏，先 scroll 再看。
4. 支付、转账、删除、发送消息、提交订单这类不可逆或对外动作，必须先用 ask 请用户确认。
5. 判断任务已完成就用 done，不要多余操作。
6. 只输出 JSON。
""".trimIndent()
    }
}
