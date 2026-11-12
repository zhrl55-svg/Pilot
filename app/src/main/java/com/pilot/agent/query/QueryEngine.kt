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

    private val trainTool = TrainQueryTool()

    private val tools: List<QueryTool> = listOf(
        NotificationQueryTool(),
        trainTool,
        StockTool(),
        NewsSearchTool(),
        WebSearchTool(),
        WebPageTool(),
    )

    /** 有没有工具能接这个需求。用来在意图门之后快速分流。 */
    fun canHandle(goal: String): Boolean =
        looksLikePendingReply(goal) || tools.any { it.matches(goal) }

    /** 上一轮查询是否在等用户补充条件。 */
    fun hasPendingQuery(): Boolean = trainTool.hasPending()

    /** 待补全车票查询是否应该接收这句话，而不是把它当成闲聊或设备操作。 */
    fun looksLikePendingReply(goal: String): Boolean = trainTool.looksLikeReply(goal)

    /** Keep long article text on screen, but speak every search headline in a concise readout. */
    fun speechSummary(answer: String): String? {
        if (answer.startsWith("12306 余票：")) {
            val trains = Regex("(?m)^\\s*\\d+\\.\\s*([^\\n]+)$").findAll(answer)
                .map { it.groupValues[1].trim() }
                .take(5)
                .toList()
            if (trains.isEmpty()) return null
            return buildString {
                append("查到 ").append(trains.size).append(" 趟。")
                trains.forEachIndexed { index, line ->
                    append("第 ").append(index + 1).append(" 趟，").append(line).append("。")
                }
                append("余票会变化，以 12306 购票页为准。")
            }
        }
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
        if (trainTool.hasPending() && trainTool.looksLikeReply(goal)) {
            Dbg.i(TAG, "继续补全 12306 查询（${goal.length} 字符）")
            return try {
                trainTool.execute(goal)
            } catch (t: Throwable) {
                Dbg.e(TAG, "12306 补全失败：${t.message}")
                null
            }
        }
        val tool = tools.firstOrNull { it.matches(goal) } ?: return null
        if (tool !== trainTool) trainTool.discardPending()
        Dbg.i(TAG, "用 ${tool.name} 处理查询（${goal.length} 字符）")
        return try {
            tool.execute(goal)
        } catch (t: Throwable) {
            Dbg.e(TAG, "${tool.name} 执行失败：${t.message}")
            null
        }
    }
}
