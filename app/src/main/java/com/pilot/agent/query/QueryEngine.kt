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
        GuardedWeatherTool(WeatherTool()),
        NewsSearchTool(),
        NearbySearchTool(),
        WebSearchTool(),
        WebPageTool(),
    )

    /**
     * 最近一次查询失败的原因（[handle] 失败时返回 null，原因放这里，供调用方如实说明）。
     */
    @Volatile
    var lastFailure: String? = null
        private set

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
        if (answer.startsWith("附近搜索：")) {
            val names = Regex("(?m)^\\s*\\d+\\.\\s*(.+)$").findAll(answer)
                .map { it.groupValues[1].trim().take(24) }
                .filter { it.isNotBlank() }
                .take(3)
                .toList()
            if (names.isEmpty()) return null
            return "附近找到 ${names.size} 个：${names.joinToString("，")}。完整结果在屏幕上。"
        }
        if (!answer.contains("的新闻，范围：") && !answer.startsWith("网页搜索：")) return null
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
     *
     * 审核 Q-01：失败文案（“查询失败”“没配置 Key”）不再当作答案返回——
     * 只有 [QueryResult.Answer] / [QueryResult.NeedMoreInfo] 返回文本；
     * 失败返回 null（原因见 [lastFailure]），Agent 模式据此改用浏览器继续。
     *
     * @return 播报文本；null = 没有工具接 / 没查到 / 查询不可用
     */
    suspend fun handle(goal: String): String? {
        return when (val r = handleResult(goal)) {
            is QueryResult.Answer -> r.text
            is QueryResult.NeedMoreInfo -> r.prompt
            is QueryResult.NoResult -> null
            is QueryResult.Unavailable -> null
            null -> null
        }
    }

    /**
     * 结构化查询。@return null = 没有工具接这个需求
     */
    suspend fun handleResult(goal: String): QueryResult? {
        lastFailure = null
        if (trainTool.hasPending() && trainTool.looksLikeReply(goal)) {
            Dbg.i(TAG, "继续补全 12306 查询（${goal.length} 字符）")
            return record(try {
                trainTool.executeResult(goal)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Dbg.e(TAG, "12306 补全失败：${t.message}")
                QueryResult.Unavailable("12306 查询失败，请稍后再试。")
            })
        }
        val matches = tools.filter { it.matches(goal) }
        if (matches.isEmpty()) return null
        if (matches.first() !== trainTool) trainTool.discardPending()
        // 按顺序尝试所有匹配的查询工具：news_search 没结果时继续试 web_search，
        // 而不是直接返回 null 让上层转去打开浏览器执行。
        var last: QueryResult? = null
        for (tool in matches) {
            Dbg.i(TAG, "用 ${tool.name} 处理查询（${goal.length} 字符）")
            val result = try {
                tool.executeResult(goal)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Dbg.e(TAG, "${tool.name} 执行失败：${t.message}")
                QueryResult.Unavailable("查询失败：${t.javaClass.simpleName}")
            }
            if (result.isAnswered) return record(result)
            last = result
        }
        return record(last ?: QueryResult.NoResult("没有找到相关结果"))
    }

    private fun record(result: QueryResult): QueryResult {
        when (result) {
            is QueryResult.NoResult -> lastFailure = result.why
            is QueryResult.Unavailable -> lastFailure = result.why
            else -> Unit
        }
        if (!result.isAnswered) Dbg.i(TAG, "查询未得到答案：${result.displayText.take(80)}")
        return result
    }
}

/**
 * WeatherTool 的外层保护（WeatherTool 本身被锁定不能改）。
 *
 * 1. “手机温度 / 电池温度 / 体温”不是天气（审核 Q-03 其它）。
 * 2. 用户点名了城市、但回答里根本没有这个城市 —— 说明内部回退成了定位城市，
 *    不能静默把“当前位置的天气”当成“大理的天气”报出去（审核 Q-03）。
 */
internal class GuardedWeatherTool(private val inner: QueryTool) : QueryTool {
    override val name: String get() = inner.name

    override fun matches(goal: String): Boolean {
        if (NOT_WEATHER.any { goal.contains(it, ignoreCase = true) }) return false
        return inner.matches(goal)
    }

    override suspend fun execute(goal: String): String? = inner.execute(goal)

    override suspend fun executeResult(goal: String): QueryResult {
        val result = inner.executeResult(goal)
        if (result !is QueryResult.Answer) return result
        val city = candidateCity(goal) ?: return result
        val answer = result.text
        val mentioned = answer.contains(city) || (city.length >= 3 && answer.contains(city.take(2)))
        if (mentioned) return result
        return QueryResult.NeedMoreInfo(
            "没查到「$city」的天气，我不会拿你当前所在地的天气冒充。" +
                "请换个说法，比如说完整的城市名“${city}市天气”，或者说“本地天气”。",
        )
    }

    companion object {
        private val NOT_WEATHER = listOf(
            "手机温度", "电池温度", "机身温度", "CPU温度", "cpu 温度", "处理器温度", "体温", "水温", "油温",
        )
        private val WEATHER_KEYWORD = Regex("(天气|气温|温度|下雨|下雪|刮风|空气质量|预报|冷不冷|热不热)")
        private val LEADING = Regex("^(请|麻烦|帮我|给我|你)?(查一下|查查|查询|查|看一下|看看|看|问一下|告诉我|说一下|说说|播报一下|播报|报一下)?")
        private val FILLERS = Regex(
            "(大后天|后天|明天|今天|今晚|明晚|今早|明早|现在|当前|此刻|这几天|这两天|未来几天|未来一周|未来|一周|本周|这周|下周|周末|最近|近期|" +
                "上午|下午|晚上|早上|中午|夜里|会不会|会|要不要|要|有没有|怎么样|如何|吗|呢|的)",
        )
        private val NOT_A_PLACE = setOf(
            "本地", "当地", "这里", "这边", "我这", "我这里", "我们这", "我们这里", "附近", "外面", "家里",
            "户外", "手机", "电池", "室内", "室外", "这", "那", "那边", "那里",
        )

        /** 抽出句子里点名的城市/地区；没有点名返回 null。 */
        fun candidateCity(goal: String): String? {
            val m = WEATHER_KEYWORD.find(goal) ?: return null
            var head = goal.substring(0, m.range.first).trim()
            head = LEADING.replace(head, "")
            head = FILLERS.replace(head, "")
            head = head.replace(Regex("[\\s，,。.？?！!：:、]"), "")
            head = head.removeSuffix("市").removeSuffix("县").removeSuffix("区")
            if (head.length !in 2..8) return null
            if (head in NOT_A_PLACE) return null
            if (!head.all { it in '\u4e00'..'\u9fa5' }) return null
            return head
        }
    }
}
