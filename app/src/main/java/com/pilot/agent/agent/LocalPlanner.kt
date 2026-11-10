package com.pilot.agent.agent

import com.pilot.agent.Dbg
import com.pilot.agent.perception.ScreenSnapshot
import com.pilot.agent.voice.LocalLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 端侧慢思考：Qwen3-0.6B（MNN-LLM，进程内）。
 *
 * ## 定位要说清楚
 * 0.6B 不是云端模型的替代品。它能做的是「单步决策」——从当前屏幕上挑一个动作；
 * 做不了的是长链推理和跨应用规划。所以它排在 [HeuristicPlanner] 之后、
 * [CloudPlanner] 之前：规则覆盖不了的简单指令先给它，还不行才上云。
 *
 * ## 上下文必须压到极小
 * 0.6B 在麒麟 990 上 prefill 约 160 t/s，系统提示词每多 100 字就多 0.6 秒首字延迟。
 * 实测 700 字提示词首字 3.5 秒、260 字 1.4 秒——用户会以为死机。
 * 所以这里只给：
 *   - 极简 system prompt（动作清单，无寒暄无规则长文）
 *   - 最多 [MAX_NODES] 个节点，每个只留 id + 类型 + 20 字标签
 *   - 最近 3 步动作历史（不传完整节点快照）
 */
class LocalPlanner(private val llmProvider: () -> LocalLlm) : Planner {

    override val id: String = "local"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun decide(req: AgentRequest): AgentDecision = withContext(Dispatchers.Default) {
        val llm = llmProvider()
        if (!llm.ensureReady()) {
            throw Planner.Unavailable(llm.lastError ?: "端侧模型不可用")
        }

        val prompt = buildPrompt(req, llm.wantsNoThink)
        val raw = llm.generate(prompt) ?: throw Planner.Unavailable(llm.lastError ?: "端侧生成失败")
        Dbg.i(TAG, "local raw=${raw.take(120)}")

        parse(raw) ?: AgentDecision(
            thought = "端侧模型输出无法解析",
            action = AgentAction(action = "unable", summary = "端侧模型没给出有效动作"),
        )
    }

    private fun buildPrompt(req: AgentRequest, noThink: Boolean): String {
        val history = req.history.takeLast(3).joinToString("\n") { "· $it" }
        val screen = compact(req.snapshot)
        val suffix = if (noThink) "/no_think" else ""
        return buildString {
            append("<|im_start|>system\n")
            append(SYSTEM_PROMPT)
            append("<|im_end|>\n<|im_start|>user\n")
            append("目标：").append(req.goal).append('\n')
            if (history.isNotEmpty()) append("已做：\n").append(history).append('\n')
            append("屏幕：\n").append(screen).append('\n')
            append("只输出 JSON。").append(suffix)
            append("<|im_end|>\n<|im_start|>assistant\n")
        }
    }

    /** 只留 id + 短标签，坐标和尺寸全丢掉——0.6B 用不上，还占 prefill */
    private fun compact(snap: ScreenSnapshot): String =
        snap.nodes.take(MAX_NODES).joinToString("\n") { n ->
            val tag = when {
                n.editable -> "输入"
                n.clickable -> "可点"
                else -> ""
            }
            "[${n.id}]$tag ${n.text.take(20)}"
        }

    private fun parse(raw: String): AgentDecision? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching {
            json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return null

        val actionName = obj["action"]?.jsonPrimitive?.contentOrNull
            ?: if (obj.containsKey("id")) "tap_node" else null
            ?: return null

        val action = AgentAction(
            action = actionName,
            id = obj["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            text = obj["text"]?.jsonPrimitive?.contentOrNull,
            target = obj["target"]?.jsonPrimitive?.contentOrNull,
            summary = obj["summary"]?.jsonPrimitive?.contentOrNull,
        )
        // 0.6B 经常把示例里的占位符原样吐出来（"id": N 或 "id": null）。
        // 这类输出不可执行，必须当成解析失败让上层降级，不能拿 null 去点屏幕。
        if ((actionName == "tap_node" || actionName == "long_press" || actionName == "input") &&
            action.id == null
        ) {
            return null
        }
        if (actionName == "open" && action.target.isNullOrBlank()) return null
        return AgentDecision(thought = "端侧模型决策", action = action)
    }

    companion object {
        private const val TAG = "PilotVoice"

        /**
         * 节点上限。给 15 个是因为一屏主要可点元素通常不超过这个数；
         * 给到 40 会让 prefill 翻倍且小模型注意力明显散掉。
         */
        private const val MAX_NODES = 15

        /** 极简提示词 —— 每个字都在 prefill 里花钱，实测 260 字是首字延迟 1.4 秒的临界点 */
        val SYSTEM_PROMPT: String = """
你是手机操作助手。看屏幕决定下一步，只输出一个 JSON，不要解释，不要 markdown。
动作示例（照这个格式，id 必须是屏幕列表里真实存在的数字）：
{"action":"tap_node","id":3}
{"action":"input","id":5,"text":"张三"}
{"action":"open","target":"设置"}
{"action":"key","target":"back"}
{"action":"done","summary":"已完成"}
""".trimIndent()
    }
}
