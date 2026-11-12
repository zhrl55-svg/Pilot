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
        val memory = com.pilot.agent.runtime.AgentRuntime.localTools.memory
            .promptContext(req.goal, 5)
        val now = java.text.SimpleDateFormat(
            "yyyy年M月d日 EEEE HH:mm",
            java.util.Locale.CHINA,
        ).format(java.util.Date())
        val userText = buildString {
            append("用户目标: ").append(req.goal).append('\n')
            append("当前时间: ").append(now).append('\n')
            if (memory.isNotBlank()) {
                append("相关长期记忆:\n").append(memory).append('\n')
            }
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
            if (s.mode == AssistantMode.AGENT) {
                put(
                    "reasoning_effort",
                    s.reasoningEffort.takeIf { it in AgentSettings.REASONING_EFFORTS }
                        ?: AgentSettings.DEFAULT_REASONING_EFFORT,
                )
            }
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put(
                        "content",
                        if (s.mode == AssistantMode.AGENT) {
                            SYSTEM_PROMPT
                        } else {
                            SYSTEM_PROMPT + "\n\n" + ASSISTANT_MODE_GUARD
                        },
                    )
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

        private val ASSISTANT_MODE_GUARD = """
当前是助理模式。禁止输出 browser_*、mcp_*、script_* 动作。
只使用当前屏幕可以完成的普通界面操作、本机直达能力、wait、done 或 ask。
如果用户明确要求浏览器会话、MCP 或用户脚本，输出 ask，并说明需要切换到 Agent 模式。
""".trimIndent()

        val SYSTEM_PROMPT: String = """
你是系统级手机助理 Pilot 的 Agent 执行模型。你要先理解用户目标和当前屏幕，
再自主选择下一步，不能机械复述关键词或把用户问题重新输入 Pilot。
每次只输出一个动作。

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
- browser_open {url}                在可见浏览器会话打开网页，保留登录状态
- browser_read {maxChars}           读取网页标题、网址和正文
- browser_click {target}            点网页按钮/链接；target 可写可见文字或 css:选择器
- browser_type {target,text}        填写网页输入框（密码类内容必须由用户明确提供）
- browser_scroll {target:"forward"|"backward"}
- browser_back
- browser_close
- mcp_list
- mcp_tools {server}
- mcp_call {server,tool,arguments:"{}"}  arguments 是 JSON 对象字符串
- script_list
- script_run {target}               按 id 或名称运行用户安装的脚本
- wait {ms}
- done {summary}                    任务完成
- ask {summary}                     信息不足或需要用户确认

规则：
0. 先判断用户是在问信息还是需要操作。若当前请求只需回答、无需继续操作，直接 done，
   summary 写自然、准确的回答；绝不打开或操作 Pilot 自己的聊天界面来重复提交。
1. 优先 tap_node 并引用列表里的节点 id，不要自己推算坐标。
2. 同一动作连续失败两次就换策略，绝不重复。
3. 目标内容可能不在当前屏，先 scroll 再看。
4. 支付、转账、删除、发送消息、提交订单这类不可逆或对外动作，必须先用 ask 请用户确认。
5. 判断任务已完成就用 done，不要多余操作。
6. 需要登录或动态网页时使用 browser_*；browser_read 读不到正文时先 browser_click 关闭遮挡层、处理同意按钮或等待。
7. 调用会产生外部副作用的 MCP 工具前先用 ask 确认；只读查询可直接调用。
8. 只输出 JSON。
9. 不声称已完成未执行的动作；需要实时信息时必须使用浏览器、MCP 或已有工具读取。

${IntentGate.SELF_KNOWLEDGE}
""".trimIndent()
    }
}
