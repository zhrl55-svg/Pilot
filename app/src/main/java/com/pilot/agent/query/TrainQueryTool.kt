package com.pilot.agent.query

import com.pilot.agent.Dbg
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 中国铁路 12306 余票查询。
 *
 * 这是一条只读链路：先解析城市和车站，再查询余票，不登录、不占座、不提交订单。
 * 12306 的网页接口并不是长期稳定的公开 API，因此站点表、查询路径和字段映射都
 * 在本模块内隔离；页面协议变化时只会让这类查询失败，不影响其他 QueryTool。
 */
class TrainQueryTool : QueryTool {

    override val name: String = "train_12306"

    @Volatile
    private var pending: TrainRequest? = null

    private val stationCatalog = AtomicReference<StationCatalog?>(null)
    private val discoveredQueryPath = AtomicReference<String?>(null)
    private val cookies = ConcurrentHashMap<String, List<Cookie>>()
    private val stateLock = Any()

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                this@TrainQueryTool.cookies[url.host] = cookies
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                this@TrainQueryTool.cookies[url.host].orEmpty()
        })
        .build()

    fun hasPending(): Boolean = pending != null

    fun discardPending() {
        synchronized(stateLock) { pending = null }
    }

    /**
     * 判断一句话是不是在补全上一轮车票查询。
     *
     * 这里只做“值得交给 TrainQueryTool 再解析一次”的粗判，真正的站名校验和错误提示
     * 在 execute 中完成。明确的设备操作和自我介绍不抢走，避免待补全状态吞掉新任务。
     */
    fun looksLikeReply(text: String): Boolean {
        if (!hasPending()) return false
        val value = text.trim()
        if (value.isEmpty()) return false
        if (CANCEL_WORDS.any(value::contains) || value.contains("重试") || value.contains("再查")) {
            return true
        }
        if (NON_REPLY_HINTS.any(value::contains)) return false
        if (parseDate(value) != null) return true
        if (ROUTE_MARKERS.any(value::contains)) return true
        if (STRONG_TRAIN_HINTS.any(value::contains) || TRAIN_MODE_WORDS.any(value::contains)) return true
        return value.length <= 20 && value.none { it in "\n\r。！？!?" }
    }

    override fun matches(goal: String): Boolean {
        val text = goal.trim()
        if (text.isBlank()) return false
        if (TASK_GUARD_HINTS.any(text::contains)) return false
        return STRONG_TRAIN_HINTS.any { text.contains(it, ignoreCase = true) } ||
            (ROUTE_MARKERS.any(text::contains) && TRAIN_MODE_WORDS.any(text::contains))
    }

    override suspend fun execute(goal: String): String? = withContext(Dispatchers.IO) {
        val text = goal.trim()
        if (text.isBlank()) return@withContext null

        if (CANCEL_WORDS.any(text::contains)) {
            synchronized(stateLock) { pending = null }
            return@withContext "已取消这次火车票查询。"
        }

        val previous = synchronized(stateLock) { pending }
        val retry = previous != null && (text.contains("重试") || text.contains("再查"))
        val parsed = if (retry) {
            previous!!
        } else {
            parseRequest(text, previous)
        }

        if (parsed.from.isBlank() || parsed.to.isBlank() || parsed.date.isBlank()) {
            synchronized(stateLock) { pending = parsed }
            return@withContext missingFieldsReply(parsed)
        }

        synchronized(stateLock) { pending = parsed }
        try {
            val catalog = loadStations()
            val originStations = catalog.resolve(parsed.from)
            val destinationStations = catalog.resolve(parsed.to)
            if (originStations.isEmpty()) {
                return@withContext "没识别到出发站“${parsed.from}”。请回复准确站名或城市，例如“北京南”或“北京”。"
            }
            if (destinationStations.isEmpty()) {
                return@withContext "没识别到到达站“${parsed.to}”。请回复准确站名或城市，例如“上海虹桥”或“上海”。"
            }

            val queryResult = queryTickets(parsed, originStations, destinationStations)
            val trains = queryResult.trains
                .filter { it.matchesFilter(parsed.filter) }
                .filter { train -> parsed.timeRange?.contains(train.startTime) ?: true }
                .filter { !parsed.availableOnly || it.hasAvailability }
                .let { list ->
                    when (parsed.sort) {
                        SortMode.ARRIVAL -> list.sortedWith(compareBy({ it.arriveDate }, { it.arriveTime }))
                        SortMode.DURATION -> list.sortedBy { it.durationMinutes }
                        SortMode.LATEST -> list.sortedByDescending { it.startTime }
                        SortMode.EARLIEST -> list.sortedBy { it.startTime }
                    }
                }
                .distinctBy { "${it.code}|${it.fromStation}|${it.toStation}|${it.startTime}" }

            if (trains.isEmpty()) {
                synchronized(stateLock) { pending = null }
                return@withContext buildString {
                    append("12306 余票：").append(parsed.fromDisplay).append(" → ")
                        .append(parsed.toDisplay).append("，").append(parsed.date).append('\n')
                    append("没有查到符合条件的车次")
                    if (parsed.filter != Filter.NONE) append("（已按").append(parsed.filter.label).append("筛选）")
                    parsed.timeRange?.let { append("（出发时间 ").append(it.label).append("）") }
                    append("。日期或筛选条件不对时可以换个条件再查。\n")
                    append("来源：中国铁路 12306；查询时间：").append(queryTime())
                }
            }

            synchronized(stateLock) { pending = null }
            formatTrains(parsed, trains, trains.size)
        } catch (failure: TrainQueryFailure) {
            Dbg.w(TAG, "12306 查询失败：${failure.reason}")
            "12306 暂时没查成：${failure.message}。查询条件已保留，可以回复“重试”。"
        } catch (failure: Throwable) {
            Dbg.e(TAG, "12306 查询异常：${failure.javaClass.simpleName}: ${failure.message}")
            "12306 暂时不可用，请稍后回复“重试”。"
        }
    }

    private fun parseRequest(text: String, previous: TrainRequest?): TrainRequest {
        val route = parseRoute(text)
        val date = parseDate(text) ?: previous?.date.orEmpty()
        val filter = parseFilter(text).takeUnless { it == Filter.NONE } ?: previous?.filter ?: Filter.NONE
        val sort = parseSort(text) ?: previous?.sort ?: SortMode.EARLIEST
        val timeRange = parseTimeRange(text) ?: previous?.timeRange
        val availableOnly = when {
            text.contains("有票") || text.contains("能买到") || text.contains("可售") -> true
            previous?.availableOnly == true -> true
            else -> false
        }

        var from = route.first ?: previous?.from.orEmpty()
        var to = route.second ?: previous?.to.orEmpty()

        if (route.first == null) parseOriginOnly(text)?.let { from = it }
        if (route.second == null) parseDestinationOnly(text)?.let { to = it }

        from = cleanPlace(from)
        to = cleanPlace(to)
        return TrainRequest(
            date = date,
            from = from,
            to = to,
            filter = filter,
            sort = sort,
            timeRange = timeRange,
            availableOnly = availableOnly,
            fromDisplay = from,
            toDisplay = to,
        )
    }

    private fun missingFieldsReply(request: TrainRequest): String = buildString {
        val missing = buildList {
            if (request.from.isBlank()) add("出发站")
            if (request.to.isBlank()) add("到达站")
            if (request.date.isBlank()) add("日期")
        }
        append("12306 车票查询还缺：").append(missing.joinToString("、")).append("。\n")
        if (request.from.isNotBlank() || request.to.isNotBlank() || request.date.isNotBlank()) {
            append("已记下")
            val known = buildList {
                if (request.from.isNotBlank()) add("出发：${request.from}")
                if (request.to.isNotBlank()) add("到达：${request.to}")
                if (request.date.isNotBlank()) add("日期：${request.date}")
                request.timeRange?.let { add("出发时间：${it.label}") }
            }
            append(" ").append(known.joinToString("，")).append("。\n")
        }
        append("请直接回复完整条件，例如“明天北京南到上海虹桥”。")
    }

    private fun parseRoute(text: String): Pair<String?, String?> {
        val routeText = cleanForRoute(text)
        val match = ROUTE_REGEX.find(routeText) ?: return null to null
        return cleanPlace(match.groupValues[1]).takeIf { it.isNotBlank() } to
            cleanPlace(match.groupValues[2]).takeIf { it.isNotBlank() }
    }

    private fun parseOriginOnly(text: String): String? {
        val match = ORIGIN_ONLY_REGEX.find(cleanForRoute(text)) ?: return null
        return cleanPlace(match.groupValues[1])
    }

    private fun parseDestinationOnly(text: String): String? {
        val match = DESTINATION_ONLY_REGEX.find(cleanForRoute(text)) ?: return null
        return cleanPlace(match.groupValues[1])
    }

    private fun cleanForRoute(input: String): String {
        var value = input
        QUERY_PREFIXES.forEach { value = value.replace(it, " ") }
        DATE_WORDS.forEach { value = value.replace(it, " ") }
        value = DATE_FORMAT_REGEXES.fold(value) { current, regex -> regex.replace(current, " ") }
        value = TIME_FILTER_REGEXES.fold(value) { current, regex -> regex.replace(current, " ") }
        value = SEARCH_SUFFIXES.fold(value) { current, suffix -> current.replace(suffix, " ") }
        QUERY_OPTION_WORDS.forEach { value = value.replace(it, " ") }
        value = value
            .replace(Regex("\\s+"), "")
            .replace(Regex("[，,。.!！?？:：]"), "")
        return value.trim()
    }

    private fun cleanPlace(raw: String): String {
        var value = raw.trim()
            .replace(Regex("\\s+"), "")
            .trim('，', ',', '。', '.', '！', '!', '？', '?', '：', ':')
        PLACE_PREFIXES.forEach { if (value.startsWith(it)) value = value.removePrefix(it) }
        PLACE_SUFFIXES.forEach { if (value.endsWith(it) && value.length > it.length) value = value.removeSuffix(it) }
        return value.trim()
    }

    private fun parseFilter(text: String): Filter = when {
        text.contains("复兴号") -> Filter.FUXING
        text.contains("高铁动车") || text.contains("动车高铁") -> Filter.HIGH_SPEED_AND_D
        text.contains("高铁") || text.contains("城际") -> Filter.HIGH_SPEED
        text.contains("动车") -> Filter.D
        else -> Filter.NONE
    }

    private fun parseSort(text: String): SortMode? = when {
        text.contains("最早到") || text.contains("先到") -> SortMode.ARRIVAL
        text.contains("最快") || text.contains("历时最短") || text.contains("时间最短") -> SortMode.DURATION
        text.contains("最早") || text.contains("先走") -> SortMode.EARLIEST
        text.contains("最晚") -> SortMode.LATEST
        else -> null
    }

    private fun parseTimeRange(text: String): TimeRange? {
        TIME_RANGE_REGEX.find(text)?.let { match ->
            val firstPeriod = match.groupValues[1]
            val secondPeriod = match.groupValues[3].ifBlank { firstPeriod }
            val from = clockMinutes(match.groupValues[2], firstPeriod) ?: return@let
            val to = clockMinutes(match.groupValues[4], secondPeriod) ?: return@let
            return TimeRange(from, to)
        }
        TIME_AFTER_REGEX.find(text)?.let { match ->
            val from = clockMinutes(match.groupValues[2], match.groupValues[1]) ?: return@let
            return TimeRange(from, null)
        }
        TIME_BEFORE_REGEX.find(text)?.let { match ->
            val to = clockMinutes(match.groupValues[2], match.groupValues[1]) ?: return@let
            return TimeRange(null, to)
        }
        return null
    }

    private fun clockMinutes(raw: String, period: String): Int? {
        val value = raw
            .replace("：", ":")
            .removeSuffix("分")
            .removeSuffix("点")
            .removeSuffix("时")
        val hourText = value.substringBefore(':').trim()
        val minuteText = if (value.contains(':')) value.substringAfter(':').trim() else ""
        var hour = chineseNumber(hourText) ?: return null
        val minute = when (minuteText) {
            "", "整" -> 0
            "半" -> 30
            "一刻" -> 15
            "三刻" -> 45
            else -> chineseNumber(minuteText) ?: return null
        }
        hour = when (period) {
            "凌晨", "早上", "早晨", "上午" -> if (hour == 12) 0 else hour
            "中午", "下午", "傍晚", "晚上", "夜里" -> if (hour < 12) hour + 12 else hour
            else -> hour
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun parseDate(raw: String): String? {
        val today = LocalDate.now(SHANGHAI)
        RELATIVE_DATES.forEach { (word, days) ->
            if (raw.contains(word)) return today.plusDays(days.toLong()).toString()
        }

        WEEKDAY_REGEX.find(raw)?.let { match ->
            val offsetWord = match.groupValues[1]
            val dayWord = match.groupValues[2]
            val target = weekday(dayWord) ?: return@let
            var days = (target.value - today.dayOfWeek.value + 7) % 7
            if (offsetWord == "下") {
                if (days == 0) days = 7 else days += 7
            }
            return today.plusDays(days.toLong()).toString()
        }

        DATE_YM_REGEX.find(raw)?.let { match ->
            val year = match.groupValues[1].toIntOrNull() ?: today.year
            val month = chineseNumber(match.groupValues[2]) ?: return@let
            val day = chineseNumber(match.groupValues[3]) ?: return@let
            return validateDate(runCatching { LocalDate.of(year, month, day) }.getOrNull())
        }
        DATE_MD_REGEX.find(raw)?.let { match ->
            val month = chineseNumber(match.groupValues[1]) ?: return@let
            val day = chineseNumber(match.groupValues[2]) ?: return@let
            return validateDate(runCatching { LocalDate.of(today.year, month, day) }.getOrNull())
        }
        DAY_ONLY_REGEX.find(raw)?.let { match ->
            val day = chineseNumber(match.groupValues[1]) ?: return@let
            val candidate = runCatching { LocalDate.of(today.year, today.monthValue, day) }.getOrNull()
            val adjusted = if (candidate != null && candidate.isBefore(today)) {
                runCatching { candidate.plusMonths(1) }.getOrNull()
            } else {
                candidate
            }
            return validateDate(adjusted)
        }
        return null
    }

    private fun validateDate(date: LocalDate?): String? {
        if (date == null) return null
        val today = LocalDate.now(SHANGHAI)
        if (date.isBefore(today) || date.isAfter(today.plusDays(MAX_SALE_DAYS))) return null
        return date.toString()
    }

    private fun weekday(raw: String): DayOfWeek? = when (raw) {
        "一" -> DayOfWeek.MONDAY
        "二" -> DayOfWeek.TUESDAY
        "三" -> DayOfWeek.WEDNESDAY
        "四" -> DayOfWeek.THURSDAY
        "五" -> DayOfWeek.FRIDAY
        "六" -> DayOfWeek.SATURDAY
        "日", "天" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun chineseNumber(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        return CHINESE_NUMBERS[raw]
    }

    private suspend fun queryTickets(
        request: TrainRequest,
        origins: List<Station>,
        destinations: List<Station>,
    ): QueryResult {
        val endpoint = prepareSession()
        val pairs = origins.take(MAX_STATIONS_PER_SIDE)
            .flatMap { from -> destinations.take(MAX_STATIONS_PER_SIDE).map { to -> from to to } }
        val responses = coroutineScope {
            pairs.map { (from, to) ->
                async(Dispatchers.IO) {
                    runCatching { queryPair(endpoint, request.date, from, to) to (from to to) }
                }
            }.awaitAll()
        }

        val failures = responses.mapNotNull { it.exceptionOrNull() }
        val trains = responses.mapNotNull { it.getOrNull() }
            .flatMap { (response, pair) -> parseTrains(response, request.date, pair.first, pair.second) }
        if (trains.isEmpty() && failures.isNotEmpty() && failures.size == responses.size) {
            val reason = failures.firstNotNullOfOrNull { it as? TrainQueryFailure }
            throw reason ?: TrainQueryFailure("12306 余票接口返回异常")
        }
        return QueryResult(trains = trains, totalBeforePageLimit = trains.size)
    }

    private suspend fun prepareSession(): String {
        discoveredQueryPath.get()?.let { return it }
        val request = Request.Builder()
            .url("$API_BASE/otn/leftTicket/init")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .get()
            .build()
        val html = executeText(request, "获取 12306 会话失败")
        val match = LEFT_TICKET_PATH_REGEX.find(html)?.groupValues?.getOrNull(1)
        val endpoint = match?.takeIf { it.isNotBlank() } ?: "leftTicket/query"
        discoveredQueryPath.set(endpoint)
        Dbg.i(TAG, "12306 查询入口=$endpoint")
        return endpoint
    }

    private fun queryPair(
        endpoint: String,
        date: String,
        from: Station,
        to: Station,
    ): String {
        val path = endpoint.removePrefix("/")
        val url = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else if (path.startsWith("otn/")) {
            "$API_BASE/$path"
        } else {
            "$API_BASE/otn/$path"
        }
        val separator = if (url.contains("?")) "&" else "?"
        val fullUrl = url + separator +
            "leftTicketDTO.train_date=${URLEncoder.encode(date, Charsets.UTF_8.name())}" +
            "&leftTicketDTO.from_station=${URLEncoder.encode(from.code, Charsets.UTF_8.name())}" +
            "&leftTicketDTO.to_station=${URLEncoder.encode(to.code, Charsets.UTF_8.name())}" +
            "&purpose_codes=ADULT"
        val request = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", "$API_BASE/otn/leftTicket/init")
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()
        val body = executeText(request, "查询 ${from.name} 到 ${to.name} 的余票失败")
        if (body.trimStart().startsWith("<")) {
            throw TrainQueryFailure("12306 返回了验证页面，请稍后再试")
        }
        return body
    }

    private fun parseTrains(
        body: String,
        queryDate: String,
        from: Station,
        to: Station,
    ): List<Train> {
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw TrainQueryFailure("12306 返回格式已变化")
        }
        val data = json.optJSONObject("data") ?: return emptyList()
        val rows = data.optJSONArray("result") ?: return emptyList()
        val stationMap = data.optJSONObject("map")
        val result = ArrayList<Train>(rows.length())
        for (index in 0 until rows.length()) {
            val fields = rows.optString(index).split('|')
            if (fields.size < 36 || fields.getOrNull(3).isNullOrBlank()) continue
            val fromCode = fields.getOrElse(6) { from.code }
            val toCode = fields.getOrElse(7) { to.code }
            val startTime = fields.getOrElse(8) { "" }
            val arriveTime = fields.getOrElse(9) { "" }
            val duration = fields.getOrElse(10) { "" }
            val startDate = fields.getOrElse(13) { queryDate }.replace("-", "")
            val arriveDate = arriveDate(startDate, startTime, duration)
            val seats = parseSeats(fields)
            result += Train(
                code = fields[3],
                trainNo = fields.getOrElse(2) { "" },
                fromStation = stationMap?.optString(fromCode).orEmpty().ifBlank { from.name },
                toStation = stationMap?.optString(toCode).orEmpty().ifBlank { to.name },
                fromCode = fromCode,
                toCode = toCode,
                startDate = displayDate(startDate),
                arriveDate = arriveDate,
                startTime = startTime,
                arriveTime = arriveTime,
                durationText = duration,
                durationMinutes = durationMinutes(duration),
                seats = seats,
                flags = parseFlags(fields.getOrElse(46) { "" }),
            )
        }
        return result
    }

    private fun parseSeats(fields: List<String>): List<Seat> {
        val prices = parsePrices(fields.getOrElse(39) { "" })
        return SEAT_FIELDS.mapNotNull { (name, index, priceCode) ->
            val tickets = fields.getOrNull(index).orEmpty().trim()
            if (tickets.isBlank() || tickets == "--" || tickets == "*") return@mapNotNull null
            Seat(name, availability(tickets), prices[priceCode])
        }
    }

    private fun parsePrices(raw: String): Map<Char, Double> {
        if (raw.isBlank()) return emptyMap()
        val result = mutableMapOf<Char, Double>()
        raw.chunked(PRICE_CHUNK).forEach { chunk ->
            if (chunk.length < 6) return@forEach
            val code = chunk[0]
            val fen = chunk.substring(1, 6).toIntOrNull() ?: return@forEach
            val price = fen / 10.0
            if (price in 0.1..9999.0) result[code] = price
        }
        return result
    }

    private fun availability(raw: String): String {
        val value = raw.trim()
        return when {
            value == "有" || value == "充足" -> "有票"
            value == "候补" -> "候补"
            value == "无" -> "无票"
            value.toIntOrNull() != null -> "${value.toInt()}张"
            else -> value
        }
    }

    private fun parseFlags(raw: String): Set<String> {
        val parts = raw.split('#')
        return buildSet {
            if (parts.getOrNull(0) == "5") add("智能动车组")
            if (parts.getOrNull(1) == "1") add("复兴号")
        }
    }

    private fun durationMinutes(raw: String): Int {
        val parts = raw.split(':')
        val hours = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minutes = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return hours * 60 + minutes
    }

    private fun arriveDate(startDate: String, startTime: String, duration: String): String {
        return runCatching {
            val date = LocalDate.parse(startDate, DATE_COMPACT)
            val time = java.time.LocalTime.parse(startTime)
            val minutes = durationMinutes(duration).toLong()
            date.atTime(time).plusMinutes(minutes).toLocalDate().toString()
        }.getOrDefault(startDate)
    }

    private fun displayDate(raw: String): String = runCatching {
        LocalDate.parse(raw, DATE_COMPACT).toString()
    }.getOrDefault(raw)

    private fun formatTrains(request: TrainRequest, trains: List<Train>, total: Int): String = buildString {
        append("12306 余票：").append(request.fromDisplay).append(" → ").append(request.toDisplay)
            .append("，").append(request.date).append('\n')
        append("查询时间：").append(queryTime())
        if (request.filter != Filter.NONE) append("；筛选：").append(request.filter.label)
        request.timeRange?.let { append("；出发时间：").append(it.label) }
        append("；查到 ").append(total).append(" 趟")
        append('\n')
        trains.take(MAX_RESULTS).forEachIndexed { index, train ->
            append('\n').append(index + 1).append(". ").append(train.code)
            append(" ").append(train.fromStation).append(" → ").append(train.toStation)
            append("  ").append(train.startTime).append("-").append(train.arriveTime)
            append("  历时").append(train.durationText)
            if (train.flags.isNotEmpty()) append("  ").append(train.flags.joinToString("/"))
            append('\n')
            if (train.seats.isEmpty()) {
                append("   余票：暂未显示可售席别")
            } else {
                append("   余票：").append(train.seats.joinToString("；") { seat ->
                    buildString {
                        append(seat.name).append(' ').append(seat.status)
                        seat.price?.let { append(" ").append(formatPrice(it)).append("元") }
                    }
                })
            }
        }
        if (trains.size > MAX_RESULTS) {
            append("\n\n其余 ").append(trains.size - MAX_RESULTS).append(" 趟未展开。")
        }
        append("\n来源：中国铁路 12306 网站；余票会实时变化，请以购票页为准。")
    }

    private fun formatPrice(price: Double): String =
        if (price == price.toLong().toDouble()) price.toLong().toString() else "%.1f".format(Locale.CHINA, price)

    private fun queryTime(): String =
        java.time.LocalDateTime.now(SHANGHAI).format(QUERY_TIME_FORMAT)

    private suspend fun loadStations(): StationCatalog {
        stationCatalog.get()?.let { return it }
        val request = Request.Builder()
            .url("$API_BASE/otn/resources/js/framework/station_name.js")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$API_BASE/index/")
            .get()
            .build()
        val body = executeText(request, "获取 12306 车站表失败")
        val raw = STATION_NAME_REGEX.find(body)?.groupValues?.getOrNull(1)
            ?: throw TrainQueryFailure("12306 车站表格式已变化")
        val stations = raw.split('@').asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { record ->
                val fields = record.split('|')
                if (fields.size < 8) return@mapNotNull null
                val name = fields[1].replace(Regex("\\s+"), "")
                val code = fields[2]
                val city = fields[7].replace(Regex("\\s+"), "")
                if (name.isBlank() || code.isBlank()) null else Station(name, code, city, fields[4])
            }
            .toList()
        if (stations.size < 100) throw TrainQueryFailure("12306 车站表不完整")
        val catalog = StationCatalog(stations)
        stationCatalog.set(catalog)
        Dbg.i(TAG, "12306 车站表加载完成：${stations.size} 站")
        return catalog
    }

    private fun executeText(request: Request, failureMessage: String): String {
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw TrainQueryFailure("$failureMessage（HTTP ${response.code}）")
                }
                return response.body?.string().orEmpty()
            }
        } catch (failure: TrainQueryFailure) {
            throw failure
        } catch (failure: Throwable) {
            throw TrainQueryFailure("$failureMessage（网络异常）")
        }
    }

    private data class TrainRequest(
        val date: String,
        val from: String,
        val to: String,
        val filter: Filter,
        val sort: SortMode,
        val timeRange: TimeRange?,
        val availableOnly: Boolean,
        val fromDisplay: String,
        val toDisplay: String,
    )

    private data class TimeRange(val fromMinutes: Int?, val toMinutes: Int?) {
        fun contains(rawTime: String): Boolean {
            val parts = rawTime.split(':')
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: return false
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: return false
            val value = hour * 60 + minute
            val start = fromMinutes
            val end = toMinutes
            return when {
                start == null && end == null -> true
                start == null -> value <= end!!
                end == null -> value >= start
                start <= end -> value in start..end
                else -> value >= start || value <= end
            }
        }

        val label: String
            get() = when {
                fromMinutes != null && toMinutes != null ->
                    "${formatMinutes(fromMinutes)}-${formatMinutes(toMinutes)}"
                fromMinutes != null -> "≥${formatMinutes(fromMinutes)}"
                else -> "≤${formatMinutes(toMinutes!!)}"
            }

        private fun formatMinutes(total: Int): String =
            "%02d:%02d".format(Locale.CHINA, total / 60, total % 60)
    }

    private data class Train(
        val code: String,
        val trainNo: String,
        val fromStation: String,
        val toStation: String,
        val fromCode: String,
        val toCode: String,
        val startDate: String,
        val arriveDate: String,
        val startTime: String,
        val arriveTime: String,
        val durationText: String,
        val durationMinutes: Int,
        val seats: List<Seat>,
        val flags: Set<String>,
    ) {
        val hasAvailability: Boolean
            get() = seats.any { it.status != "无票" }

        fun matchesFilter(filter: Filter): Boolean = when (filter) {
            Filter.NONE -> true
            Filter.HIGH_SPEED -> code.startsWith("G") || code.startsWith("C")
            Filter.HIGH_SPEED_AND_D -> code.startsWith("G") || code.startsWith("C") || code.startsWith("D")
            Filter.D -> code.startsWith("D")
            Filter.FUXING -> flags.contains("复兴号")
        }
    }

    private data class Seat(val name: String, val status: String, val price: Double?)

    private enum class Filter(val label: String) {
        NONE(""),
        HIGH_SPEED("高铁/城际"),
        HIGH_SPEED_AND_D("高铁/城际/动车"),
        D("动车"),
        FUXING("复兴号"),
    }

    private enum class SortMode { EARLIEST, LATEST, DURATION, ARRIVAL }

    private data class QueryResult(val trains: List<Train>, val totalBeforePageLimit: Int)

    private data class Station(val name: String, val code: String, val city: String, val short: String)

    private class StationCatalog(private val stations: List<Station>) {
        private val byName = stations.groupBy { normalize(it.name) }
        private val byCity = stations.groupBy { normalize(it.city) }

        fun resolve(raw: String): List<Station> {
            val value = normalize(raw)
            if (raw.trim().endsWith("站")) {
                byName[value]?.let { return it.take(1) }
            }
            val cityStations = byCity[value].orEmpty()
            if (cityStations.isNotEmpty()) {
                return cityStations.sortedWith(
                    compareByDescending<Station> { normalize(it.name) == value }
                        .thenByDescending { station ->
                            when {
                                station.name.endsWith("南") || station.name.endsWith("虹桥") -> 6
                                station.name.endsWith("北") || station.name.endsWith("朝阳") -> 5
                                station.name.endsWith("东") || station.name.endsWith("丰台") -> 4
                                station.name.endsWith("西") || station.name.endsWith("大兴") ||
                                    station.name.endsWith("浦东") -> 3
                                else -> 1
                            }
                        }
                        .thenBy { it.name },
                ).take(MAX_STATIONS_PER_SIDE)
            }
            return byName[value]?.take(1).orEmpty()
        }

        private fun normalize(raw: String): String =
            raw.replace(Regex("\\s+"), "").removeSuffix("站")
    }

    private class TrainQueryFailure(message: String) : Exception(message) {
        val reason: String get() = message ?: "unknown"
    }

    private companion object {
        const val TAG = "PilotTrain"
        const val API_BASE = "https://kyfw.12306.cn"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36 PilotAssistant/1.0"
        const val MAX_RESULTS = 5
        const val MAX_STATIONS_PER_SIDE = 3
        const val MAX_SALE_DAYS = 15L
        const val PRICE_CHUNK = 10
        const val PERIOD_TEXT = "(?:凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里)"
        const val CLOCK_TEXT =
            "(?:\\d{1,2}|[一二两三四五六七八九十]{1,3})(?:[:：点时](?:\\d{1,2}|半|一刻|三刻)?分?)?"

        val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
        val DATE_COMPACT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val QUERY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        val STRONG_TRAIN_HINTS = listOf(
            "12306", "火车票", "高铁票", "动车票", "铁路票", "余票", "车次", "列车",
        )
        val TRAIN_MODE_WORDS = listOf(
            "高铁", "动车", "城际", "火车",
        )
        val TASK_GUARD_HINTS = listOf(
            "导航", "路线", "带我去", "打开", "关闭", "播放", "暂停",
        )
        val ROUTE_MARKERS = listOf("从", "到", "至", "去", "出发", "到达")
        val CANCEL_WORDS = listOf(
            "取消车票查询", "不查车票", "不查火车", "算了", "取消查询", "取消", "不用了",
        )
        val NON_REPLY_HINTS = listOf(
            "打开", "关闭", "播放", "暂停", "导航", "提醒", "日历", "设置",
            "天气", "新闻", "股票", "行情", "你是谁", "谢谢", "你好", "早上好", "晚上好",
        )
        val QUERY_PREFIXES = listOf(
            "帮我查一下", "帮我查询", "帮我查", "请帮我", "麻烦", "查一下", "查询一下",
            "查询", "查查", "查", "看看", "看一下",
        )
        val DATE_WORDS = listOf(
            "今天", "今日", "明天", "明日", "后天", "大后天",
            "上午", "中午", "下午", "晚上",
        )
        val SEARCH_SUFFIXES = listOf(
            "火车票余票", "高铁票余票", "动车票余票", "火车余票", "车票余票",
            "火车票", "高铁票", "动车票", "铁路票", "车票", "余票", "车次", "票价",
        )
        // These words describe how to answer, not which stations to query. Strip longer
        // phrases first so a destination followed directly by "高铁最快只看有票" remains
        // a station name after parsing.
        val QUERY_OPTION_WORDS = listOf(
            "只看有票", "只看可售", "能买到", "智能动车组", "高铁动车", "动车高铁",
            "历时最短", "时间最短", "最早到", "最晚", "最快", "最早", "先到", "先走",
            "复兴号", "高铁", "动车", "城际", "火车", "有票", "可售", "只看", "筛选",
        )
        val PLACE_PREFIXES = listOf("从", "由", "在")
        val PLACE_SUFFIXES = listOf("出发", "到站", "到达", "前往", "的票", "的车", "的")
        val SEAT_FIELDS = listOf(
            Triple("商务座", 32, '9'),
            Triple("一等座", 31, 'M'),
            Triple("二等座", 30, 'O'),
            Triple("软卧", 23, '4'),
            Triple("硬卧", 28, '3'),
            Triple("软座", 24, '2'),
            Triple("硬座", 29, '1'),
            Triple("无座", 26, 'W'),
        )
        val RELATIVE_DATES = linkedMapOf(
            "大后天" to 3,
            "后天" to 2,
            "明天" to 1,
            "明日" to 1,
            "今天" to 0,
            "今日" to 0,
        )
        val CHINESE_NUMBERS = mapOf(
            "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4, "五" to 5,
            "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10,
            "十一" to 11, "十二" to 12, "十三" to 13, "十四" to 14, "十五" to 15,
            "十六" to 16, "十七" to 17, "十八" to 18, "十九" to 19, "二十" to 20,
            "二十一" to 21, "二十二" to 22, "二十三" to 23, "二十四" to 24,
            "二十五" to 25, "二十六" to 26, "二十七" to 27, "二十八" to 28,
            "二十九" to 29, "三十" to 30, "三十一" to 31,
        )

        val ROUTE_REGEX = Regex(
            "(?:从|由)?([\\p{IsHan}A-Za-z0-9·]{1,15}?)(?:到|至|去)([\\p{IsHan}A-Za-z0-9·]{1,15})",
        )
        val ORIGIN_ONLY_REGEX = Regex(
            "(?:从|由)([\\p{IsHan}A-Za-z0-9·]{1,15}?)(?:出发|走|$)",
        )
        val DESTINATION_ONLY_REGEX = Regex(
            "(?:到|去|到达)([\\p{IsHan}A-Za-z0-9·]{1,15}?)(?:的|$)",
        )
        val WEEKDAY_REGEX = Regex("(下|这|本)?(?:周|星期)([一二三四五六日天])")
        val DATE_YM_REGEX = Regex(
            "(\\d{4})[年./-]([0-9一二两三四五六七八九十]{1,2})[月./-]" +
                "([0-9一二两三四五六七八九十]{1,3})[日号]?",
        )
        val DATE_MD_REGEX = Regex(
            "([0-9一二两三四五六七八九十]{1,2})月" +
                "([0-9一二两三四五六七八九十]{1,3})[日号]?",
        )
        val DAY_ONLY_REGEX = Regex("(?:这个月)?([0-9一二两三四五六七八九十]{1,3})[日号]")
        val DATE_FORMAT_REGEXES = listOf(DATE_YM_REGEX, DATE_MD_REGEX, DAY_ONLY_REGEX)
        val TIME_RANGE_REGEX = Regex(
            "($PERIOD_TEXT)?\\s*($CLOCK_TEXT)\\s*(?:到|至|[-~—–])\\s*($PERIOD_TEXT)?\\s*($CLOCK_TEXT)",
        )
        val TIME_AFTER_REGEX = Regex(
            "($PERIOD_TEXT)?\\s*($CLOCK_TEXT)\\s*(?:以后|之后|后|起)",
        )
        val TIME_BEFORE_REGEX = Regex(
            "($PERIOD_TEXT)?\\s*($CLOCK_TEXT)\\s*(?:以前|之前|前)",
        )
        val TIME_FILTER_REGEXES = listOf(TIME_RANGE_REGEX, TIME_AFTER_REGEX, TIME_BEFORE_REGEX)
        val LEFT_TICKET_PATH_REGEX = Regex(
            "CLeftTicketUrl\\s*=\\s*['\"]([^'\"]+)['\"]",
        )
        val STATION_NAME_REGEX = Regex("var\\s+station_names\\s*=\\s*'([^']+)'")
    }
}
