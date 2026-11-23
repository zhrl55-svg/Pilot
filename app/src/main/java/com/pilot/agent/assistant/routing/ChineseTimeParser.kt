package com.pilot.agent.assistant.routing

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs

/**
 * Deterministic Chinese time parser for the local assistant fast path.
 *
 * The parser deliberately reports ambiguity instead of guessing when the user
 * omitted enough information (for example "明天下午" without an hour). Callers
 * can then ask for confirmation or fall back to the cloud model.
 */
object ChineseTimeParser {

    enum class RepeatMode { ONCE, DAILY, WEEKDAYS, WEEKLY }

    data class Parsed(
        val dateTime: LocalDateTime,
        val zoneId: ZoneId,
        val allDay: Boolean,
        val confidence: Double,
        val repeat: RepeatMode = RepeatMode.ONCE,
        val ambiguous: Boolean = false,
        val relative: Boolean = false,
        val matchedText: String = "",
    ) {
        fun iso(): String = dateTime.format(ISO_LOCAL_SECONDS)
        fun dateIso(): String = dateTime.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        fun timeHhMm(): String = dateTime.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    data class Duration(
        val seconds: Long,
        val matchedText: String,
    )

    private val ISO_LOCAL_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private val RELATIVE_NUMBER =
        Regex("""(?:(\d{1,4})|([零〇一二两三四五六七八九十百]+))\s*(?:个)?\s*(秒钟|秒|分钟|分|小时|钟头|天)\s*(?:以)?后""")
    private val HALF_HOUR = Regex("""(?:半\s*(?:个)?\s*小时|半个小时)\s*(?:以)?后""")
    private val HALF_HOUR_DURATION = Regex("""(?:半\s*(?:个)?\s*小时|半个小时)""")
    private val ONE_AND_HALF_HOUR = Regex(
        """(?:(?:一|1)\s*个?\s*半\s*(?:个)?\s*小时|(?:一|1)\s*个?\s*小时\s*半)""",
    )
    private val RELATIVE_DAY =
        Regex("""(大后天|后天|明天|明日|今天|今日|今晚|今早|明早|明晚)""")
    private val WEEKDAY = Regex("""(下下|下|本|这)?\s*(?:周|星期|礼拜)\s*([一二三四五六日天1-7])""")
    private val MONTH_DAY = Regex("""(?:(?:(\d{4})年)\s*)?(\d{1,2}|[一二三四五六七八九十]{1,3})\s*月\s*(\d{1,2}|[一二三四五六七八九十]{1,3})\s*[日号]?""")
    private val PERIOD = Regex("""(凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里)""")
    private val CLOCK =
        Regex("""(?:(\d{1,2})|([零〇一二两三四五六七八九十]{1,3}))\s*[点时]\s*(?:(半|一刻)|((?:\d{1,2}|[零〇一二三四五六七八九十]{1,3})\s*分?))?(?:\s*(\d{1,2})\s*分)?""")
    private val CLOCK_COLON = Regex("""\b(\d{1,2})\s*[:：]\s*(\d{1,2})\b""")
    private val EVERY_DAY = Regex("""(每天|每日|每一天|天天)""")
    private val EVERY_WEEKDAY = Regex("""(每个工作日|每工作日|工作日)""")
    private val EVERY_WEEK = Regex("""(每周|每星期|每礼拜)""")
    private val EXPLICIT_YEAR = Regex("""(\d{4})\s*年""")

    private val EN_RELATIVE_DAY = Regex(
        """\b(today|tonight|tomorrow|the\s+day\s+after\s+tomorrow|day\s+after\s+tomorrow)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val EN_WEEKDAY = Regex(
        """\b(?:(next|this)\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val EN_MONTH_DAY = Regex(
        """\b(?:(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+(\d{1,2})(?:st|nd|rd|th)?|(\d{1,2})(?:st|nd|rd|th)?\s+(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val EN_PERIOD = Regex(
        """\b(morning|afternoon|evening|night|tonight)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val EN_AM_PM = Regex("""\b(a\.?m\.?|p\.?m\.?)\b""", RegexOption.IGNORE_CASE)
    private val EN_AT_CLOCK = Regex(
        """\bat\s+(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val EN_AM_PM_CLOCK = Regex(
        """\b(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val EN_CLOCK_COLON = Regex(
        """\b(\d{1,2}):(\d{2})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val EN_EVERY_DAY = Regex("""\b(every\s+day|daily)\b""", RegexOption.IGNORE_CASE)
    private val EN_EVERY_WEEKDAY = Regex(
        """\b(every\s+weekday|weekdays|every\s+working\s+day)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val EN_EVERY_WEEK = Regex("""\b(every\s+week|weekly)\b""", RegexOption.IGNORE_CASE)
    private val EN_EVERY_WEEKDAY_NAME = Regex(
        """\bevery\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val EN_DURATION_TOKEN = Regex(
        """\b(\d{1,5}|a|an)\s*(seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h|days?|d)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val EN_HALF_HOUR = Regex(
        """\bhalf\s+(?:an?\s+)?hour\b""",
        RegexOption.IGNORE_CASE,
    )
    private val EN_RELATIVE_MARKER = Regex(
        """\b(in\s+\d|in\s+an?\b|in\s+half\b|from\s+now|later\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val DAY_WORDS = mapOf(
        "今天" to 0L, "今日" to 0L, "今晚" to 0L, "今早" to 0L,
        "明天" to 1L, "明日" to 1L, "明早" to 1L, "明晚" to 1L,
        "后天" to 2L,
        "大后天" to 3L,
    )

    private val EN_MONTHS = mapOf(
        "jan" to 1, "january" to 1,
        "feb" to 2, "february" to 2,
        "mar" to 3, "march" to 3,
        "apr" to 4, "april" to 4,
        "may" to 5,
        "jun" to 6, "june" to 6,
        "jul" to 7, "july" to 7,
        "aug" to 8, "august" to 8,
        "sep" to 9, "sept" to 9, "september" to 9,
        "oct" to 10, "october" to 10,
        "nov" to 11, "november" to 11,
        "dec" to 12, "december" to 12,
    )

    fun parse(
        input: String,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Parsed? {
        if (input.isBlank()) return null
        val text = normalize(input)

        // Relative durations are the least ambiguous form: "30分钟后",
        // "两个半小时后". They are computed before any clock/date parsing.
        parseRelativeDuration(text)?.let { d ->
            return Parsed(
                dateTime = now.plusSeconds(d.seconds),
                zoneId = zone,
                allDay = false,
                confidence = 0.99,
                relative = true,
                matchedText = d.matchedText,
            )
        }

        val repeat = when {
            EVERY_WEEKDAY.containsMatchIn(text) -> RepeatMode.WEEKDAYS
            EVERY_DAY.containsMatchIn(text) -> RepeatMode.DAILY
            EN_EVERY_WEEKDAY.containsMatchIn(text) -> RepeatMode.WEEKDAYS
            EN_EVERY_DAY.containsMatchIn(text) -> RepeatMode.DAILY
            EN_EVERY_WEEKDAY_NAME.containsMatchIn(text) -> RepeatMode.WEEKLY
            EVERY_WEEK.containsMatchIn(text) || EN_EVERY_WEEK.containsMatchIn(text) ->
                RepeatMode.WEEKLY
            else -> RepeatMode.ONCE
        }

        var date = now.toLocalDate()
        var dateSpecified = false
        var explicitPastDate = false
        var confidence = 0.72
        var ambiguous = false
        // P1：今晚/明晚/今早/明早只设日期不够，还隐含时段。
        // 否则"明晚8点"会把 period 留空，被当成明早 8 点且 isConfident=true 直接执行。
        var impliedPeriod: String? = null

        RELATIVE_DAY.find(text)?.let { m ->
            val word = m.groupValues[1]
            val delta = DAY_WORDS[word]
            if (delta != null) {
                date = now.toLocalDate().plusDays(delta)
                dateSpecified = true
                confidence = 0.96
                when (word) {
                    "今晚", "明晚" -> impliedPeriod = "晚上"
                    "今早", "明早" -> impliedPeriod = "早上"
                }
            }
        }
        EN_RELATIVE_DAY.find(text)?.let { m ->
            val word = m.groupValues[1].lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")
            val delta = when (word) {
                "today", "tonight" -> 0L
                "tomorrow" -> 1L
                "the day after tomorrow", "day after tomorrow" -> 2L
                else -> null
            }
            if (delta != null) {
                date = now.toLocalDate().plusDays(delta)
                dateSpecified = true
                confidence = 0.96
                if (word == "tonight") impliedPeriod = "晚上"
            }
        }

        var weekTarget: DayOfWeek? = null
        WEEKDAY.find(text)?.let { m ->
            val qualifier = m.groupValues[1]
            val token = m.groupValues[2]
            val target = weekdayOf(token)
            if (target != null) {
                weekTarget = target
                val mondayThisWeek = now.toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                date = when (qualifier) {
                    "下下" -> mondayThisWeek.plusWeeks(2)
                        .with(TemporalAdjusters.nextOrSame(target))
                    "下" -> mondayThisWeek.plusWeeks(1)
                        .with(TemporalAdjusters.nextOrSame(target))
                    else -> now.toLocalDate()
                        .with(TemporalAdjusters.nextOrSame(target))
                }
                dateSpecified = true
                confidence = 0.94
            }
        }
        EN_WEEKDAY.find(text)?.let { m ->
            val qualifier = m.groupValues[1].lowercase(Locale.ROOT)
            val target = weekdayOfEnglish(m.groupValues[2])
            if (target != null) {
                weekTarget = target
                val mondayThisWeek = now.toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                date = when (qualifier) {
                    "next" -> mondayThisWeek.plusWeeks(1)
                        .with(TemporalAdjusters.nextOrSame(target))
                    "this" -> mondayThisWeek.with(TemporalAdjusters.nextOrSame(target))
                    else -> now.toLocalDate().with(TemporalAdjusters.nextOrSame(target))
                }
                dateSpecified = true
                confidence = 0.94
            }
        }

        MONTH_DAY.find(text)?.let { m ->
            val year = m.groupValues[1].toIntOrNull()
                ?: EXPLICIT_YEAR.find(text)?.groupValues?.get(1)?.toIntOrNull()
            val month = chineseNumber(m.groupValues[2])
            val day = chineseNumber(m.groupValues[3])
            if (month in 1..12 && day in 1..31) {
                var candidateYear = year ?: now.year
                var candidate = runCatching {
                    LocalDate.of(candidateYear, month, day)
                }.getOrNull()
                if (candidate == null && !text.contains("月")) {
                    // A bare "3/5" is intentionally not parsed; this branch is
                    // only a guard against invalid month/day values.
                }
                if (candidate != null) {
                    if (year == null && candidate.isBefore(now.toLocalDate())) {
                        candidate = runCatching {
                            LocalDate.of(candidateYear + 1, month, day)
                        }.getOrNull()
                        explicitPastDate = true
                    }
                    if (candidate != null) {
                        date = candidate
                        dateSpecified = true
                        confidence = 0.94
                    }
                }
            }
        }
        EN_MONTH_DAY.find(text)?.let { m ->
            val monthToken = m.groupValues[1].ifBlank { m.groupValues[4] }
            val dayToken = m.groupValues[2].ifBlank { m.groupValues[3] }
            val month = EN_MONTHS[monthToken.lowercase(Locale.ROOT)]
            val day = dayToken.toIntOrNull()
            if (month != null && day != null && day in 1..31) {
                var candidate = runCatching { LocalDate.of(now.year, month, day) }.getOrNull()
                if (candidate != null && candidate.isBefore(now.toLocalDate())) {
                    candidate = runCatching { LocalDate.of(now.year + 1, month, day) }.getOrNull()
                    explicitPastDate = true
                }
                if (candidate != null) {
                    date = candidate
                    dateSpecified = true
                    confidence = 0.94
                }
            }
        }

        val period = PERIOD.find(text)?.groupValues?.get(1)
            ?: EN_PERIOD.find(text)?.groupValues?.get(1)
            ?: impliedPeriod

        var hour: Int? = null
        var minute = 0
        var timeText: MatchResult? = null
        var amPm: String? = null
        CLOCK_COLON.find(text)?.let { m ->
            val h = m.groupValues[1].toIntOrNull()
            val min = m.groupValues[2].toIntOrNull()
            if (h != null && min != null && h in 0..23 && min in 0..59) {
                hour = h
                minute = min
                timeText = m
            }
        }
        if (hour == null) {
            // Prefer forms that make the time unambiguous. This avoids
            // treating the "3" in "September 3" as 03:00.
            EN_AT_CLOCK.find(text)?.let { m ->
                val h = m.groupValues[1].toIntOrNull()
                val min = m.groupValues[2].toIntOrNull() ?: 0
                if (h != null && h in 0..23 && min in 0..59) {
                    hour = h
                    minute = min
                    amPm = m.groupValues[3].takeIf { it.isNotBlank() }
                    timeText = m
                }
            }
        }
        if (hour == null) {
            EN_AM_PM_CLOCK.find(text)?.let { m ->
                val h = m.groupValues[1].toIntOrNull()
                val min = m.groupValues[2].toIntOrNull() ?: 0
                if (h != null && h in 0..23 && min in 0..59) {
                    hour = h
                    minute = min
                    amPm = m.groupValues[3]
                    timeText = m
                }
            }
        }
        if (hour == null) {
            EN_CLOCK_COLON.find(text)?.let { m ->
                val h = m.groupValues[1].toIntOrNull()
                val min = m.groupValues[2].toIntOrNull()
                if (h != null && min != null && h in 0..23 && min in 0..59) {
                    hour = h
                    minute = min
                    timeText = m
                }
            }
        }
        if (hour == null) {
            CLOCK.find(text)?.let { m ->
                val h = chineseNumber(m.groupValues[1].ifBlank { m.groupValues[2] })
                if (h in 0..23) {
                    val minuteWord = m.groupValues[3]
                    val minuteRaw = m.groupValues[4]
                    val minute2 = m.groupValues[5]
                    minute = when {
                        minuteWord == "半" -> 30
                        minuteWord == "一刻" -> 15
                        minuteRaw.isNotBlank() -> chineseNumber(
                            minuteRaw.replace("分", "").trim(),
                        )
                        minute2.isNotBlank() -> minute2.toIntOrNull() ?: 0
                        else -> 0
                    }
                    if (minute in 0..59) {
                        hour = h
                        timeText = m
                    }
                }
            }
        }

        if (hour != null && amPm.isNullOrBlank()) {
            amPm = EN_AM_PM.find(text)?.groupValues?.get(1)
        }

        var hourValue = hour
        if (hourValue != null && !amPm.isNullOrBlank()) {
            val isPm = amPm.lowercase(Locale.ROOT).replace(".", "").startsWith("p")
            hourValue = if (isPm) {
                if (hourValue < 12) hourValue + 12 else hourValue
            } else {
                if (hourValue == 12) 0 else hourValue
            }
            if (hourValue == 24) hourValue = 0
        } else if (hourValue != null && period != null) {
            hourValue = applyPeriod(hourValue, period)
            if (hourValue == 24) {
                hourValue = 0
                date = date.plusDays(1)
            }
        } else if (hourValue != null && hourValue in 1..6) {
            // "7点" near midnight is genuinely ambiguous, but guessing is
            // still more useful than dropping it. Mark it so the confirm card
            // asks the user to check the time.
            ambiguous = true
            confidence = minOf(confidence, 0.66)
        }

        if (hourValue == null && repeat != RepeatMode.ONCE) {
            // "每天帮我…" carries a cadence but no clock time. Nine o'clock is
            // a deterministic, visible default; the confirmation card marks
            // it as ambiguous so the user can correct it before saving.
            hourValue = 9
            ambiguous = true
            confidence = minOf(confidence, 0.5)
        }

        val allDay = dateSpecified && hourValue == null
        if (!dateSpecified && hourValue == null) return null

        if (hourValue == null) {
            hourValue = 0
            ambiguous = true
            confidence = minOf(confidence, 0.55)
        }

        var result = LocalDateTime.of(date, LocalTime.of(hourValue, minute.coerceIn(0, 59)))
        if (!dateSpecified && result.isBefore(now) &&
            repeat == RepeatMode.ONCE
        ) {
            // "wake me up at 7am" normally means the next 7am, not a time in
            // the past. A date explicitly supplied by the user is never
            // shifted here; it is marked ambiguous so confirmation can catch
            // "today 7am" when it is already 10am.
            result = result.plusDays(1)
            date = result.toLocalDate()
        } else if (dateSpecified && result.isBefore(now) &&
            repeat == RepeatMode.ONCE
        ) {
            ambiguous = true
            confidence = minOf(confidence, 0.55)
        }
        if (weekTarget != null && result.isBefore(now) && repeat == RepeatMode.ONCE) {
            result = result.plusWeeks(1)
            date = result.toLocalDate()
        }
        if (explicitPastDate) confidence = minOf(confidence, 0.86)

        return Parsed(
            dateTime = result,
            zoneId = zone,
            allDay = allDay,
            confidence = confidence,
            repeat = repeat,
            ambiguous = ambiguous,
            matchedText = listOfNotNull(
                RELATIVE_DAY.find(text)?.value,
                WEEKDAY.find(text)?.value,
                MONTH_DAY.find(text)?.value,
                EN_RELATIVE_DAY.find(text)?.value,
                EN_WEEKDAY.find(text)?.value,
                EN_MONTH_DAY.find(text)?.value,
                period,
                timeText?.value,
            ).joinToString(" ").trim(),
        )
    }

    /** Parse a duration such as "5分钟", "1小时30分钟", "半小时". */
    fun parseDuration(input: String): Duration? {
        val text = normalize(input)
        if (text.isBlank()) return null
        val spans = mutableListOf<Pair<IntRange, Long>>()
        var total = 0L

        HALF_HOUR_DURATION.find(text)?.let { m ->
            spans += m.range to 30L * 60L
            total += 30L * 60L
        }
        ONE_AND_HALF_HOUR.find(text)?.let { m ->
            // Existing HALF_HOUR also matches inside "一个半小时"; replace
            // that 30-minute span with the correct 90 minutes.
            spans.removeAll { it.first.overlaps(m.range) }
            total = 0L
            spans += m.range to 90L * 60L
            total += 90L * 60L
        }
        EN_HALF_HOUR.find(text)?.let { m ->
            if (spans.none { it.first.overlaps(m.range) }) {
                spans += m.range to 30L * 60L
                total += 30L * 60L
            }
        }
        DURATION_TOKEN.findAll(text).forEach { m ->
            if (spans.any { it.first.overlaps(m.range) }) return@forEach
            val value = chineseNumber(m.groupValues[1].ifBlank { m.groupValues[2] }).toLong()
            val unit = m.groupValues[3]
            val seconds = when {
                unit.contains("秒") -> value
                unit.contains("分") -> value * 60
                unit.contains("小时") || unit.contains("钟头") -> value * 3600
                unit.contains("天") -> value * 86400
                else -> 0L
            }
            if (seconds > 0) {
                spans += m.range to seconds
                total += seconds
            }
        }
        EN_DURATION_TOKEN.findAll(text).forEach { m ->
            if (spans.any { it.first.overlaps(m.range) }) return@forEach
            val rawValue = m.groupValues[1].lowercase(Locale.ROOT)
            val value = when (rawValue) {
                "a", "an" -> 1L
                else -> rawValue.toLongOrNull() ?: return@forEach
            }
            val unit = m.groupValues[2].lowercase(Locale.ROOT)
            val seconds = when {
                unit.startsWith("s") -> value
                unit.startsWith("m") -> value * 60
                unit.startsWith("h") -> value * 3600
                unit.startsWith("d") -> value * 86400
                else -> 0L
            }
            if (seconds > 0) {
                spans += m.range to seconds
                total += seconds
            }
        }
        total = spans.sumOf { it.second }
        if (total <= 0) return null
        val start = spans.minOfOrNull { it.first.first } ?: 0
        val end = spans.maxOfOrNull { it.first.last } ?: text.lastIndex
        return Duration(total, text.substring(start, end + 1).trim())
    }

    fun parseRelativeDuration(input: String): Duration? {
        val text = normalize(input)
        if (text.contains("后")) {
            return parseDuration(text.substringBefore("后"))
        }
        if (EN_RELATIVE_MARKER.containsMatchIn(text)) {
            return parseDuration(text)
        }
        return null
    }

    /**
     * Remove the time/date phrases the parser understands. Used to derive a
     * calendar title or reminder body without relying on an LLM.
     */
    fun stripTimeExpressions(input: String): String {
        var out = normalize(input)
        listOf(
            RELATIVE_NUMBER,
            HALF_HOUR,
            EN_HALF_HOUR,
            EN_RELATIVE_DAY,
            RELATIVE_DAY,
            EN_WEEKDAY,
            WEEKDAY,
            EN_MONTH_DAY,
            MONTH_DAY,
            EN_AT_CLOCK,
            EN_AM_PM_CLOCK,
            EN_CLOCK_COLON,
            CLOCK_COLON,
            CLOCK,
            EN_EVERY_DAY,
            EN_EVERY_WEEKDAY,
            EN_EVERY_WEEK,
            EN_EVERY_WEEKDAY_NAME,
            EVERY_DAY,
            EVERY_WEEKDAY,
            EVERY_WEEK,
            EN_AM_PM,
            EN_PERIOD,
            PERIOD,
            EN_DURATION_TOKEN,
            DURATION_TOKEN,
        ).forEach { regex ->
            out = regex.replace(out, " ")
        }
        return out.replace(Regex("""\s+"""), " ").trim()
    }

    fun normalize(input: String): String {
        val nfkc = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFKC)
        return nfkc
            .replace('\u3000', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private val DURATION_TOKEN =
        Regex("""(?:(\d{1,5})|([零〇一二两三四五六七八九十百]+))\s*(?:个)?\s*(小时|钟头|分钟|分|秒钟|秒|天)""")

    private fun chineseNumber(raw: String): Int {
        if (raw.isBlank()) return -1
        raw.toIntOrNull()?.let { return it }
        val s = raw.replace('〇', '零')
        if (s == "十") return 10
        if (s == "百") return 100
        var result = 0
        var current = 0
        for (ch in s) {
            when (ch) {
                '零' -> Unit
                '一' -> current = 1
                '二', '两' -> current = 2
                '三' -> current = 3
                '四' -> current = 4
                '五' -> current = 5
                '六' -> current = 6
                '七' -> current = 7
                '八' -> current = 8
                '九' -> current = 9
                '十' -> {
                    if (current == 0) current = 1
                    result += current * 10
                    current = 0
                }
                '百' -> {
                    if (current == 0) current = 1
                    result += current * 100
                    current = 0
                }
                else -> return -1
            }
        }
        return result + current
    }

    private fun weekdayOf(token: String): DayOfWeek? = when (token) {
        "一", "1" -> DayOfWeek.MONDAY
        "二", "2" -> DayOfWeek.TUESDAY
        "三", "3" -> DayOfWeek.WEDNESDAY
        "四", "4" -> DayOfWeek.THURSDAY
        "五", "5" -> DayOfWeek.FRIDAY
        "六", "6" -> DayOfWeek.SATURDAY
        "日", "天", "7" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun weekdayOfEnglish(token: String): DayOfWeek? = when (token.lowercase(Locale.ROOT)) {
        "monday", "mon" -> DayOfWeek.MONDAY
        "tuesday", "tue", "tues" -> DayOfWeek.TUESDAY
        "wednesday", "wed" -> DayOfWeek.WEDNESDAY
        "thursday", "thu", "thur", "thurs" -> DayOfWeek.THURSDAY
        "friday", "fri" -> DayOfWeek.FRIDAY
        "saturday", "sat" -> DayOfWeek.SATURDAY
        "sunday", "sun" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun applyPeriod(hour: Int, period: String): Int {
        if (hour == 12) {
            return if (period in setOf(
                    "凌晨", "早上", "早晨", "上午", "morning",
                )
            ) {
                0
            } else {
                12
            }
        }
        return when (period) {
            "下午", "傍晚", "晚上", "夜里", "afternoon", "evening", "night", "tonight" ->
                if (hour < 12) hour + 12 else hour
            // P1：中午就是 12 点前后，10/11 点本就是上午。
            // 原来 hour<11 时 +12 会把"中午10点"变成 22:00 并非确定执行。
            "中午" -> hour
            else -> hour
        }
    }

    private fun IntRange.overlaps(other: IntRange): Boolean =
        first <= other.last && other.first <= last

    /** Confidence below this means the caller should ask before executing. */
    fun isConfident(parsed: Parsed): Boolean = parsed.confidence >= 0.8 && !parsed.ambiguous

    /** A stable local-day range for calendar "today/tomorrow" queries. */
    fun dayRange(
        day: LocalDate,
        zone: ZoneId,
    ): Pair<LocalDateTime, LocalDateTime> =
        day.atStartOfDay() to day.plusDays(1).atStartOfDay()

    fun secondsUntil(target: LocalDateTime, now: LocalDateTime): Long =
        java.time.Duration.between(now, target).seconds.coerceAtLeast(0L)

    /** Human-friendly distance used in the confirmation card. */
    fun describeDuration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        return when {
            s < 60 -> "$s 秒"
            s < 3600 -> "${s / 60} 分钟"
            s % 3600 == 0L -> "${s / 3600} 小时"
            else -> "${s / 3600} 小时 ${(s % 3600) / 60} 分钟"
        }
    }

    fun ambiguityDistanceMinutes(parsed: Parsed, now: LocalDateTime): Long =
        abs(java.time.Duration.between(now, parsed.dateTime).toMinutes())
}
