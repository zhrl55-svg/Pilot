package com.pilot.agent.query

/**
 * 结构化查询结果（审核 Q-01）。
 *
 * 只有 [Answer] 与 [NeedMoreInfo] 才算“给了用户答案”；[NoResult]/[Unavailable]
 * 是失败，不能当成“任务完成”——Agent 模式应改用浏览器继续，助理模式如实说明。
 */
sealed interface QueryResult {
    /** @param privateData 含个人数据（如通知）：只用本机 TTS，锁屏不读 */
    data class Answer(val text: String, val privateData: Boolean = false) : QueryResult
    data class NeedMoreInfo(val prompt: String) : QueryResult
    data class NoResult(val why: String) : QueryResult
    data class Unavailable(val why: String) : QueryResult

    companion object {
        private val FAILURE_HINTS = listOf(
            "失败", "暂时不可用", "不可用", "没有找到", "没找到", "没查到", "查不到", "拿不到", "没拿到",
            "无法", "请检查网络", "没配置", "未配置", "没有配置", "超时", "出错", "异常",
        )
        private val ASK_HINTS = listOf("请补充", "请提供", "请告诉我", "请说", "请问你要", "要查哪")

        /**
         * 旧接口（只返回 String?）的结果分类。只看开头一小段，避免长正文里偶然出现“失败”二字被误判。
         */
        fun classify(text: String?): QueryResult {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return NoResult("没有拿到结果")
            val head = t.take(40)
            if (ASK_HINTS.any { head.startsWith(it) || head.contains(it) } && t.length <= 160) return NeedMoreInfo(t)
            if (t.length <= 120 && FAILURE_HINTS.any(head::contains)) return Unavailable(t)
            if (t.length <= 80 && (t.endsWith("？") || t.endsWith("?"))) return NeedMoreInfo(t)
            return Answer(t)
        }
    }
}

/** 给用户看/听的文本；失败类返回原因。 */
val QueryResult.displayText: String
    get() = when (this) {
        is QueryResult.Answer -> text
        is QueryResult.NeedMoreInfo -> prompt
        is QueryResult.NoResult -> why
        is QueryResult.Unavailable -> why
    }

/** 是否算作“已回答”（Answer / NeedMoreInfo）。 */
val QueryResult.isAnswered: Boolean
    get() = this is QueryResult.Answer || this is QueryResult.NeedMoreInfo
