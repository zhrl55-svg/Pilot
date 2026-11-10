package com.pilot.agent.perception

import kotlinx.serialization.Serializable

/**
 * 屏幕上一个可操作/可读的节点。
 *
 * 设计要点：
 * 1. id 是本次快照内的局部索引，模型用它引用节点（比让模型输出绝对坐标可靠得多）。
 * 2. 同时保留坐标，节点点击失败时可以退化为坐标点击。
 * 3. 只保留对决策有用的字段，避免把整棵 AccessibilityNodeInfo 序列化给模型看——
 *    一棵完整的树轻松上万 token，压平之后通常 800~1500 token。
 */
@Serializable
data class UiNode(
    val id: Int,
    val cls: String = "",
    val text: String = "",
    val viewId: String = "",
    val x1: Int = 0,
    val y1: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
) {
    val cx: Int get() = (x1 + x2) / 2
    val cy: Int get() = (y1 + y2) / 2

    /** 压缩成一行给模型看，比 JSON 省一半以上 token。 */
    fun toPromptLine(): String = buildString {
        append('[').append(id).append("] ")
        append(cls.ifEmpty { "View" })
        val label = text.take(48).replace('\n', ' ')
        if (label.isNotEmpty()) append(" \"").append(label).append('"')
        if (viewId.isNotEmpty()) append(" #").append(viewId.substringAfterLast('/'))
        append(" (").append(cx).append(',').append(cy).append(')')
        append(" ").append(x2 - x1).append('x').append(y2 - y1)
        val f = buildString {
            if (clickable) append('c')
            if (editable) append('e')
            if (scrollable) append('s')
            if (checked) append('v')
            if (!enabled) append('d')
        }
        if (f.isNotEmpty()) append(" [").append(f).append(']')
    }
}

@Serializable
data class ScreenSnapshot(
    val pkg: String = "",
    val nodes: List<UiNode> = emptyList(),
    val ts: Long = 0L,
    val truncated: Boolean = false,
) {
    fun toPrompt(): String = buildString {
        append("前台应用: ").append(pkg.ifEmpty { "unknown" }).append('\n')
        append("可见节点 ").append(nodes.size).append(" 个")
        if (truncated) append("（已截断）")
        append(":\n")
        for (n in nodes) append(n.toPromptLine()).append('\n')
    }

    fun withText(vararg keywords: String): List<UiNode> =
        nodes.filter { n -> keywords.any { n.text.contains(it, ignoreCase = true) } }
}
