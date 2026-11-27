package com.pilot.agent.action

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.pilot.agent.Dbg
import com.pilot.agent.assistant.routing.ChineseTimeParser
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「添加日程」直达路径（m-20261127-103）。
 *
 * 目标：用户说一句「明天下午3点开会」，**不跳 App、不用点保存**，事件直接落进系统日历；
 * 做不到时才退回 ACTION_INSERT（打开日历新建页），并如实说明为什么。
 *
 * 顺序：权限 → 选可写日历 → 查重 → 插入；任一步失败走 [fallback]。
 * 时间解析复用 [ChineseTimeParser]（不在日历链路另写一套）。
 * 写入成功后记住事件 id，支持「取消刚才那个日程」。
 */
object CalendarTool {

    private const val TAG = "PilotCalendar"

    /** 回查等待：日历 App 冷启动可能 3 秒以上。 */
    private const val LAUNCH_VERIFY_MS = 5_000L

    /** 默认时长 1 小时。 */
    private const val DEFAULT_DURATION_MS = 60L * 60L * 1000L

    /** 查重容差：同一标题、开始时间差 ±60s 视为同一条。 */
    internal const val DUPLICATE_TOLERANCE_MS = 60_000L

    private const val ASK_FOR_TIME = "请补充日期和开始时间，例如“添加日程 明天下午 3 点项目讨论”。"
    private const val ASK_FOR_TITLE = "请补充日程标题，例如“添加日程 明天下午 3 点项目讨论”。"

    private const val PREFS = "pilot_calendar"
    private const val KEY_LAST_ID = "last_event_id"
    private const val KEY_LAST_TITLE = "last_event_title"
    private const val KEY_LAST_START = "last_event_start"

    private val CANCEL_VERB = Regex("""(取消|删除|删掉|撤掉|撤销|不要了)""")
    private val CANCEL_REFERENCE = Regex("""(刚才|刚刚|刚建|上一个|这个|那条|那一条)""")

    fun handles(text: String): Boolean =
        listOf("添加日程", "新建日程", "创建日程", "加到日历", "日历里安排", "安排会议").any(text::contains)

    /** 「取消刚才那个日程」：只认带指代词的删除，避免误删别的日程。 */
    fun handlesCancel(text: String): Boolean =
        text.contains("日程") && CANCEL_VERB.containsMatchIn(text) && CANCEL_REFERENCE.containsMatchIn(text)

    data class ParsedGoal(
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val timeZone: String,
        val allDay: Boolean,
    )

    /** 一次「建日程」的结果；文案与副作用分离，[messageFor] 是纯函数。 */
    internal sealed class CalendarResult {
        data class Created(val whenText: String, val title: String) : CalendarResult()
        data class Duplicate(val whenText: String, val title: String) : CalendarResult()
        data class Failed(
            val reason: String,
            val whenText: String,
            val title: String,
            val fallback: LaunchOutcome,
        ) : CalendarResult()
    }

    suspend fun create(context: Context, text: String): String {
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        val parsed = parseGoal(text, now, zone) ?: return if (titleOf(text).isBlank()) ASK_FOR_TITLE else ASK_FOR_TIME
        val whenText = formatWhen(parsed.startMs, now, zone)

        if (!CalendarPermissions.granted(context)) {
            val ok = CalendarPermissions.ensure(context)
            if (!ok) {
                Dbg.i(TAG, "没有日历权限，退回日历新建页")
                return messageFor(
                    CalendarResult.Failed("没有日历读写权限", whenText, parsed.title, fallback(context, parsed)),
                )
            }
        }

        val calendar = CalendarStore.findWritable(context)
            ?: return messageFor(
                CalendarResult.Failed("没有可写入的日历", whenText, parsed.title, fallback(context, parsed)),
            )

        val existing = CalendarStore.findDuplicate(
            context, calendar.id, parsed.title, parsed.startMs, DUPLICATE_TOLERANCE_MS,
        )
        if (existing != null) {
            Dbg.i(TAG, "日程已存在 id=$existing，不重复创建")
            return messageFor(CalendarResult.Duplicate(whenText, parsed.title))
        }

        val id = CalendarStore.insert(
            context,
            calendar.id,
            CalendarStore.EventDraft(
                title = parsed.title,
                description = "由 Pilot 助手创建",
                startMs = parsed.startMs,
                endMs = parsed.endMs,
                timeZone = parsed.timeZone,
            ),
        )
        if (id == null || id <= 0) {
            Dbg.w(TAG, "日历插入失败，退回日历新建页")
            return messageFor(
                CalendarResult.Failed("写入日历失败", whenText, parsed.title, fallback(context, parsed)),
            )
        }
        rememberLastCreated(context, id, parsed.title, parsed.startMs)
        Dbg.i(TAG, "日程已写入 id=$id calendar=${calendar.displayName} start=$whenText")
        return messageFor(CalendarResult.Created(whenText, parsed.title))
    }

    /** 取消刚才由 Pilot 建的日程；没有记录时如实说不知道。 */
    fun cancelLastCreated(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getLong(KEY_LAST_ID, -1L)
        if (id <= 0L) return "我不记得刚建过哪条日程，所以没有删除任何东西。"
        val title = p.getString(KEY_LAST_TITLE, "").orEmpty()
        val start = p.getLong(KEY_LAST_START, 0L)
        val whenText = if (start > 0L) formatWhen(start, LocalDateTime.now(), ZoneId.systemDefault()) else ""
        val ok = CalendarStore.delete(context, id)
        if (ok) {
            p.edit().clear().apply()
            return "已取消日程：${whenText}「$title」。"
        }
        return "删除失败：那条日程可能已经被手动删掉了。"
    }

    /**
     * 解析目标（纯函数，便于单测）。
     * @return null = 缺时间或缺标题
     */
    internal fun parseGoal(text: String, now: LocalDateTime, zone: ZoneId): ParsedGoal? {
        val parsed = ChineseTimeParser.parse(text, now, zone) ?: return null
        val title = titleOf(text, parsed.matchedText)
        if (title.isBlank()) return null
        val startMs = parsed.dateTime.atZone(parsed.zoneId).toInstant().toEpochMilli()
        val endMs = if (parsed.allDay) {
            startMs + 24L * 60L * 60L * 1000L
        } else {
            startMs + DEFAULT_DURATION_MS
        }
        return ParsedGoal(title, startMs, endMs, parsed.zoneId.id, parsed.allDay)
    }

    /** 从原话里剥掉时间与「添加日程」类动词，留下标题。 */
    internal fun titleOf(text: String, matchedTime: String = ""): String {
        var t = text
        if (matchedTime.isNotBlank()) t = t.replace(matchedTime, " ")
        return t
            .replace(Regex("""(今天|明天|后天|大后天|上午|下午|晚上|中午|凌晨|早上|傍晚)"""), " ")
            .replace(Regex("""\d{1,2}\s*[:：]\s*\d{1,2}"""), " ")
            .replace(Regex("""\d{1,2}\s*[点时]\s*(半|\d{1,2}\s*分?)?"""), " ")
            .replace(Regex("""(添加|新建|创建|安排|加到|加入|放进|日历里|日历|日程|会议|提醒我|帮我|请)"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '：', ':', '，', ',', '。', '.', '！', '!')
            .trim()
    }

    /** 「明天 15:00」这类相对日期文案（纯函数）。 */
    internal fun formatWhen(startMs: Long, now: LocalDateTime, zone: ZoneId): String {
        val start = Instant.ofEpochMilli(startMs).atZone(zone)
        val date = start.toLocalDate()
        val prefix = when (date) {
            now.toLocalDate() -> "今天"
            now.toLocalDate().plusDays(1) -> "明天"
            now.toLocalDate().plusDays(2) -> "后天"
            else -> "${date.monthValue}月${date.dayOfMonth}日"
        }
        if (start.hour == 0 && start.minute == 0 && start.second == 0) return prefix
        return "$prefix ${start.toLocalTime().format(HH_MM)}"
    }

    /** 结果 → 回执文案。纯函数，单测直接覆盖成功/重复/无权限/无可写日历等结局。 */
    internal fun messageFor(result: CalendarResult): String = when (result) {
        is CalendarResult.Created ->
            "已加到日历：${result.whenText} ${result.title}"
        is CalendarResult.Duplicate ->
            "日历里已经有${result.whenText}的「${result.title}」，没有重复创建。"
        is CalendarResult.Failed -> when (val f = result.fallback) {
            is LaunchOutcome.Ok ->
                "没能直接写入（${result.reason}）；已打开日历新建页，确认日期和标题后点保存即可。"
            is LaunchOutcome.Blocked ->
                "没能直接写入（${result.reason}）；也没能打开日历新建页（${f.reason.take(60)}），日程没有创建。"
            is LaunchOutcome.Failed ->
                "没能直接写入（${result.reason}）；也没有找到可用的日历应用。"
            LaunchOutcome.Unknown ->
                "没能直接写入（${result.reason}）；已打开日历新建页，但没能确认它到了前台（结果未知），请看一眼手机。"
        }
    }

    /** 兜底：ACTION_INSERT + 回查前台。 */
    private suspend fun fallback(context: Context, parsed: ParsedGoal, zone: ZoneId = ZoneId.systemDefault()): LaunchOutcome {
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, parsed.title.take(160))
            putExtra(CalendarContract.Events.DESCRIPTION, "由 Pilot 助手发起；请确认后保存。")
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, parsed.startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, parsed.endMs)
            putExtra(CalendarContract.Events.EVENT_TIMEZONE, parsed.timeZone)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return VerifiedLauncher.launch(context, intent, expectPackage = null, timeoutMs = LAUNCH_VERIFY_MS)
    }

    private fun rememberLastCreated(context: Context, id: Long, title: String, startMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_ID, id)
            .putString(KEY_LAST_TITLE, title)
            .putLong(KEY_LAST_START, startMs)
            .apply()
    }

    private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")
}
