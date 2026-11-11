package com.pilot.agent.action

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import java.util.Calendar

/** Hands event creation to the user's calendar app; nothing is saved until the user presses Save. */
object CalendarTool {
    fun handles(text: String): Boolean = listOf("添加日程", "新建日程", "创建日程", "加到日历", "日历里安排", "安排会议").any(text::contains)

    fun create(context: Context, text: String): String {
        val title = text.substringAfter("日程", "").ifBlank {
            text.substringAfter("安排", "").ifBlank { text.substringAfter("日历", "") }
        }.replace(Regex("(?:(?:今天|明天|后天)?(?:上午|下午|晚上|中午)?\\s*\\d{1,2}(?::|点)\\d{0,2}分?)"), "")
            .replace(Regex("今天|明天|后天|上午|下午|晚上|中午"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '：', ':', '，', ',', '。', '.', '！', '!')
        val time = parseTime(text) ?: return "请补充日期和开始时间，例如“添加日程 明天下午 3 点项目讨论”。"
        if (title.isBlank()) return "请补充日程标题。"
        val end = time + 60 * 60 * 1000L
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, title.take(160))
            putExtra(CalendarContract.Events.DESCRIPTION, "由 Pilot 助手发起；请确认后保存。")
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, time)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            putExtra(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            "已打开日历新建页面，确认日期和标题后点保存即可。"
        }.getOrElse { "没有找到可用的日历应用，请先安装或启用日历。" }
    }

    private fun parseTime(text: String): Long? {
        val day = Calendar.getInstance()
        when {
            text.contains("明天") -> day.add(Calendar.DAY_OF_YEAR, 1)
            text.contains("后天") -> day.add(Calendar.DAY_OF_YEAR, 2)
            !text.contains("今天") -> return null
        }
        val match = Regex("(?:(上午|下午|晚上|中午)\\s*)?(\\d{1,2})(?::|点)(\\d{1,2})?").find(text) ?: return null
        var hour = match.groupValues[2].toIntOrNull() ?: return null
        val minute = match.groupValues[3].toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        val period = match.groupValues[1]
        if (period in listOf("下午", "晚上") && hour < 12) hour += 12
        if (period == "上午" && hour == 12) hour = 0
        day.set(Calendar.HOUR_OF_DAY, hour)
        day.set(Calendar.MINUTE, minute)
        day.set(Calendar.SECOND, 0)
        day.set(Calendar.MILLISECOND, 0)
        return day.timeInMillis
    }
}
