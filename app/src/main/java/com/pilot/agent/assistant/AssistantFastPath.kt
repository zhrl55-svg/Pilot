package com.pilot.agent.assistant

import android.content.Context
import com.pilot.agent.agent.AgentSettings
import com.pilot.agent.assistant.exec.CapabilityExecutor
import com.pilot.agent.assistant.exec.CapabilitySafety
import com.pilot.agent.assistant.exec.FastPathTrace
import com.pilot.agent.assistant.llm.SlotFiller
import com.pilot.agent.assistant.routing.AssistantRouter
import com.pilot.agent.assistant.routing.RouteDecision
import com.pilot.agent.assistant.routing.RouteStore
import com.pilot.agent.runtime.AgentRuntime
import com.pilot.agent.tool.ToolInvocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 一次快路径的决策结果：交给调用方去执行。 */
data class FastPathPlan(
    val decision: RouteDecision.Local,
    val userText: String,
    /**
     * 可执行的工具调用。
     *
     * null = 规则抽不全槽位，需要端侧模型补值 —— 调用方先用
     * [AssistantFastPath.complete] 补齐再执行；补不出来就回落原链路。
     */
    val invocation: ToolInvocation?,
    /** 破坏性能力：即使路由写了 NEVER 也要确认。 */
    val needsConfirmation: Boolean,
) {
    val capability: String get() = decision.capability
    val action: String get() = decision.action
    val args: JSONObject get() = decision.args
}

/**
 * 助理快路径（端侧直执行）。
 *
 * ## 它做什么
 *
 * 用户说一句**高频、明确**的指令（查电量、读文件、记一件事…）时，
 * 规则选出能力 → 确定性抽参（必要时端侧 0.3B 补自由文本槽）→ 本机执行 →
 * 出结果，**完全不调云端**。规则不命中、抽参失败、或安全不过时返回 null，
 * 调用方照旧走原链路。
 *
 * ## 它不做什么
 *
 * - 不做推理、不做多步、不生成解释性回答 —— 那些是云端的活。
 * - 不碰 Agent 模式（多步执行、浏览器、MCP、脚本）。
 * - 不吞错误：工具执行失败会把真实错误显示给用户，不伪装成功。
 *
 * ## 为什么必须是「失败即回退」
 *
 * 端侧是**可选的加速器**，不是单点。任何一步不成立（开关关、规则没命中、
 * 抽参不全、模型不可用、安全不过、没有执行器）都返回 null / 补值失败，
 * 让原链路接手。用户永远不应该因为快路径的存在而得到比之前更差的结果。
 */
object AssistantFastPath {

    private val router = AssistantRouter()

    /** 助理快路径是否开启。默认关闭 —— 新路径不能默认改变现有行为。 */
    fun isEnabled(): Boolean = AgentSettings.load().assistantFastPath

    /**
     * 决策：这句话要不要由快路径处理？
     *
     * 纯同步（路由是纯计算），不碰模型。需要模型补值时 [FastPathPlan.invocation]
     * 为 null，调用方在后台调用 [complete] 补齐。
     *
     * @param source ui | broadcast | voice，用于诊断轨迹
     * @return null = 快路径不接管，调用方走原链路
     */
    fun plan(context: Context, text: String, source: String): FastPathPlan? {
        val enabled = isEnabled()
        FastPathTrace.entered(context, source, enabled)
        if (!enabled) return null

        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        // 浏览器会话、MCP、用户脚本的意图交给 Agent 扩展，快路径不抢。
        // 不然「打开这个网页并总结正文」会被 navigate 抢走，把扩展挤掉。
        if (AgentRuntime.localTools.prefersAgentExtension(trimmed)) {
            FastPathTrace.declined(context, "extension", trimmed)
            return null
        }

        val routed = try {
            router.route(trimmed, RouteStore.get(context).snapshot())
        } catch (e: Throwable) {
            FastPathTrace.line(context, "route-error: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        val decision = when (routed) {
            is RouteDecision.Local -> routed
            is RouteDecision.Cloud -> {
                FastPathTrace.declined(context, routed.reason, trimmed)
                return null
            }
        }

        // 安全闸：值必须来自用户原话，破坏性命令不执行。
        when (val safety = CapabilitySafety.check(decision, trimmed)) {
            is CapabilitySafety.Verdict.Deny -> {
                FastPathTrace.line(
                    context,
                    "unsafe: capability=${decision.capability} reason=${safety.reason}",
                )
                return null
            }
            CapabilitySafety.Verdict.Allow -> Unit
        }

        val needsConfirmation = CapabilitySafety.requiresConfirmation(decision)

        // 规则没抽全、能力允许模型补值 → 先认领，等调用方在后台补。
        if (decision.needsModelExtraction) {
            FastPathTrace.line(
                context,
                "model-fill-needed: capability=${decision.capability} missing=${decision.missingSlots}",
            )
            return FastPathPlan(decision, trimmed, invocation = null, needsConfirmation = needsConfirmation)
        }

        val invocation = executor(context).invocation(decision, trimmed)
        if (invocation == null) {
            // 这个能力/动作本阶段没有执行器（比如按歌名播放要走检索流水线）→ 交回原链路。
            FastPathTrace.declined(context, "no-executor", trimmed)
            return null
        }
        return FastPathPlan(decision, trimmed, invocation, needsConfirmation)
    }

    /**
     * 补齐需要端侧模型填的自由文本槽。
     *
     * 在后台线程调用（内部会加载并跑 0.3B，首次约 1~3 秒）。补不出来、或补出来的
     * 值过不了安全闸，返回 null —— 调用方回落原链路。
     */
    suspend fun complete(context: Context, plan: FastPathPlan): FastPathPlan? {
        if (plan.invocation != null) return plan

        val llm = AgentRuntime.localLlm
        val filler = SlotFiller(
            available = { llm.abiSupported() && llm.modelPresent() },
            generate = { prompt ->
                val suffix = if (llm.wantsNoThink) "\n/no_think" else ""
                llm.generate(prompt + suffix)
            },
        )
        val filled = withContext(Dispatchers.Default) {
            filler.fill(
                userText = plan.userText,
                capability = plan.capability,
                args = plan.args,
                missing = plan.decision.missingSlots,
            )
        }
        if (filled == null) {
            FastPathTrace.line(context, "model-fill-failed: capability=${plan.capability}")
            return null
        }

        // 模型补出来的值同样要过安全闸：值必须来自用户原话。
        val decision = plan.decision.copy(
            args = filled,
            missingSlots = emptySet(),
            needsModelExtraction = false,
        )
        when (val safety = CapabilitySafety.check(decision, plan.userText)) {
            is CapabilitySafety.Verdict.Deny -> {
                FastPathTrace.line(
                    context,
                    "unsafe-after-fill: capability=${decision.capability} reason=${safety.reason}",
                )
                return null
            }
            CapabilitySafety.Verdict.Allow -> Unit
        }

        val invocation = executor(context).invocation(decision, plan.userText)
        if (invocation == null) {
            FastPathTrace.declined(context, "no-executor", plan.userText)
            return null
        }
        FastPathTrace.line(context, "model-fill-ok: capability=${decision.capability}")
        return FastPathPlan(
            decision = decision,
            userText = plan.userText,
            invocation = invocation,
            needsConfirmation = CapabilitySafety.requiresConfirmation(decision),
        )
    }

    private fun executor(context: Context) = CapabilityExecutor(
        context = context.applicationContext,
        tools = AgentRuntime.localTools,
        appIndexProvider = { AgentRuntime.appIndex.takeIf { it.built } },
        actionsProvider = { AgentRuntime.actions },
    )
}
