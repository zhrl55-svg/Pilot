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
}
