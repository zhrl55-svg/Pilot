package com.pilot.agent.query

/**
 * 查询工具接口。
 *
 * 和「操作类」的区别：查询类只要信息，不碰屏幕、不改设备状态。
 * 走 HTTP 拿数据，结果直接播报。
 */
interface QueryTool {

    val name: String

    /** 这句需求归不归我管 */
    fun matches(goal: String): Boolean

    /** 执行查询。@return 给用户播报的文本；null 表示拿不到 */
    suspend fun execute(goal: String): String?

    /**
     * 结构化结果（审核 Q-01）。默认按 [QueryResult.classify] 对 [execute] 的文本分类；
     * 能明确区分成功/失败的工具应覆盖它。
     */
    suspend fun executeResult(goal: String): QueryResult = QueryResult.classify(execute(goal))
}
