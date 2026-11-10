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
        StockTool(),
    )

    /** 有没有工具能接这个需求。用来在意图门之后快速分流。 */
    fun canHandle(goal: String): Boolean = tools.any { it.matches(goal) }

    /**
     * 执行查询。
     * @return 播报文本；null = 没有工具接 / 执行失败
     */
    suspend fun handle(goal: String): String? {
        val tool = tools.firstOrNull { it.matches(goal) } ?: return null
        Dbg.i(TAG, "用 ${tool.name} 处理：$goal")
        return try {
            tool.execute(goal)
        } catch (t: Throwable) {
            Dbg.e(TAG, "${tool.name} 执行失败：${t.message}")
            null
        }
    }
}
