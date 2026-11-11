package com.pilot.agent.agent

import kotlinx.serialization.Serializable

/**
 * 模型输出的一个原子动作。
 *
 * 用「单一 data class + action 字符串」而不是 sealed class：
 * 序列化/反序列化更宽容（模型经常多吐或少吐字段），也方便直接塞进 OpenAI tools 的 JSON Schema。
 */
@Serializable
data class AgentAction(
    val action: String,
    val id: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val text: String? = null,
    val target: String? = null,
    val ms: Int? = null,
    val summary: String? = null,
) {
    fun describe(): String = when (action) {
        "tap" -> "点击坐标 ($x,$y)"
        "tap_node" -> "点击节点 [$id]"
        "long_press" -> "长按节点 [$id]"
        "swipe" -> "滑动 ($x,$y) → ($x2,$y2)"
        // 输入值可能包含密码、验证码、收件人或私人消息；描述会进入日志、历史与模型上下文，不能回显原文。
        "input" -> "在 [$id] 输入内容（已隐藏）"
        "open" -> "打开应用 ${target.orEmpty()}"
        "systemop" -> "系统直调 ${summary ?: target.orEmpty()}"
        "shortcut" -> "直达 ${summary ?: target.orEmpty()}"
        "component" -> "直达 ${summary ?: target.orEmpty()}"
        "key" -> "按键 ${target.orEmpty()}"
        "scroll" -> "滚动 [$id] ${target ?: "forward"}"
        "wait" -> "等待 ${ms ?: 500}ms"
        "app_func" -> "调用 ${target.orEmpty()} 的工具 ${text.orEmpty()}"
        "done" -> "完成：${summary.orEmpty()}"
        "ask" -> "需要确认：${summary.orEmpty()}"
        else -> action
    }

    companion object {
        /** 给云端模型看的工具清单，等价于 OpenAI function calling 的 tools。 */
        val TOOL_SCHEMA: String = """
        [
          {"name":"tap_node","description":"点击一个可交互节点，优先用这个而不是 tap","parameters":{"type":"object","properties":{"id":{"type":"integer","description":"节点 id"}},"required":["id"]}},
          {"name":"tap","description":"按屏幕坐标点击，仅在节点不可点击时使用","parameters":{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}},"required":["x","y"]}},
          {"name":"long_press","description":"长按节点","parameters":{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}},
          {"name":"input","description":"在输入框节点里填文字","parameters":{"type":"object","properties":{"id":{"type":"integer"},"text":{"type":"string"}},"required":["id","text"]}},
          {"name":"swipe","description":"滑动屏幕，用于翻页/滚动","parameters":{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"x2":{"type":"integer"},"y2":{"type":"integer"},"ms":{"type":"integer"}},"required":["x","y","x2","y2"]}},
          {"name":"scroll","description":"对可滚动容器做一次滚动，比 swipe 更稳定","parameters":{"type":"object","properties":{"id":{"type":"integer"},"target":{"type":"string","enum":["forward","backward"]}},"required":["id"]}},
          {"name":"open","description":"按包名或应用名打开一个 App","parameters":{"type":"object","properties":{"target":{"type":"string"}},"required":["target"]}},
          {"name":"key","description":"系统按键","parameters":{"type":"object","properties":{"target":{"type":"string","enum":["back","home","recents","notifications"]}},"required":["target"]}},
          {"name":"wait","description":"等待界面加载","parameters":{"type":"object","properties":{"ms":{"type":"integer"}},"required":["ms"]}},
          {"name":"done","description":"任务完成，给出结果说明","parameters":{"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"]}},
          {"name":"ask","description":"信息不足或动作有风险，向用户提问/请求确认","parameters":{"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"]}}
        ]
        """.trimIndent()
    }
}

enum class Risk { NONE, MONEY, IRREVERSIBLE, EXTERNAL, SENSITIVE }

/**
 * 可执行的动作名白名单。
 *
 * 端侧小模型会**编**动作名（实测吐出 `set_volume`），而 execute() 的 else 分支只会默默返回 false，
 * 于是每步都"执行失败但不算无效动作"，一路烧到 maxTurns。
 * 各处（解析、执行、无效动作判定）统一以这份清单为准。
 */
val KNOWN_ACTIONS: Set<String> = setOf(
    "tap", "tap_node", "long_press", "input", "swipe", "scroll",
    "open", "shortcut", "component", "key", "systemop",
    "done", "wait", "ask", "unable", "app_func",
)
