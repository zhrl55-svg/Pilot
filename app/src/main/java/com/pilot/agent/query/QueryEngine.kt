package com.pilot.agent.query

import com.pilot.agent.Dbg

/**
 * 查询引擎：要信息的需求走这里，不碰屏幕、不进 agent loop。
 *
 * 和操作类的分工：
 * - 「打开设置」→ 操作，走 AgentLoop
 * - 「查一下上证指数」→ 查询，走这里，2 秒内出答案
 * - 「你是谁」→ 闲聊，走端侧模型
 *
 * 新增一个数据源 = 实现一个 [QueryTool] 扔进列表，不用改任何调度逻辑。
 */
object QueryEngine {

    private const val TAG = "PilotQuery"

    private val tools: List<QueryTool> = listOf(
        NotificationQueryTool(),
        StockTool(),
        NewsSearchTool(),
        WebSearchTool(),
        WebPageTool(),
    )

    /** 有没有工具能接这个需求。用来在意图门之后快速分流。 */
    fun canHandle(goal: String): Boolean = tools.any { it.matches(goal) }

    /** Keep long article text on screen, but speak every search headline in a concise readout. */
    fun speechSummary(answer: String): String? {
        if (!answer.contains("的最新新闻（共") && !answer.startsWith("网页搜索：")) return null
        val titles = Regex("(?m)^\\s*\\d+\\.\\s*(.+)$").findAll(answer)
            .map { it.groupValues[1].trim().take(36) }
            .filter { it.isNotBlank() }
            .take(5)
            .toList()
        if (titles.isEmpty()) return null
        return buildString {
            append("找到 ").append(titles.size).append(" 条结果。")
            titles.forEachIndexed { index, title -> append("第 ").append(index + 1).append(" 条：").append(title).append("。") }
        }
    }

    /**
     * 执行查询。
     * @return 播报文本；null = 没有工具接 / 执行失败
     */
    suspend fun handle(goal: String): String? {
        val tool = tools.firstOrNull { it.matches(goal) } ?: return null
        Dbg.i(TAG, "用 ${tool.name} 处理查询（${goal.length} 字符）")
        return try {
            tool.execute(goal)
        } catch (t: Throwable) {
            Dbg.e(TAG, "${tool.name} 执行失败：${t.message}")
            null
        }
    }
}
