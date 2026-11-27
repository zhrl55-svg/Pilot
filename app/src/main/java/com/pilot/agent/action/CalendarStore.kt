package com.pilot.agent.action

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.pilot.agent.Dbg

/**
 * 系统日历的数据层（m-20261127-103）。
 *
 * 直写 `CalendarContract.Events`（真机实测：shell uid 都能插入，普通 App 拿到
 * READ/WRITE_CALENDAR 后同样可以；唯一不能写的是 `_sync_id`，那是同步适配器专用字段）。
 *
 * 选择可写日历的规则（[pick]）：
 * 1. 只看 `visible=1` 且 `calendar_access_level >= CONTRIBUTOR(500)` 的日历；
 * 2. 优先 `IS_PRIMARY=1`（系统主日历）；
 * 3. 其余按 `_id` 升序取第一个（结果稳定、可测试）。
 * 一个都没有 → 交给调用方退回 ACTION_INSERT。
 */
object CalendarStore {

    private const val TAG = "PilotCalendar"

    /** 网易/华为日历等常见实现里 500=可添加事件，700=OWNER。 */
    const val MIN_WRITABLE_ACCESS = CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR

    data class WritableCalendar(
        val id: Long,
        val displayName: String,
        val accessLevel: Int,
        val primary: Boolean,
    )

    data class EventDraft(
        val title: String,
        val description: String,
        val startMs: Long,
        val endMs: Long,
        val timeZone: String,
    )

    fun findWritable(context: Context): WritableCalendar? = try {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE}=1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?"
        val args = arrayOf(MIN_WRITABLE_ACCESS.toString())
        val order = "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC"
        val list = mutableListOf<WritableCalendar>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            args,
            order,
        )?.use { c ->
            while (c.moveToNext()) {
                list += WritableCalendar(
                    id = c.getLong(0),
                    displayName = c.getString(1).orEmpty(),
                    accessLevel = c.getInt(2),
                    primary = c.getInt(3) == 1,
                )
            }
        }
        pick(list).also {
            Dbg.i(TAG, "可写日历 ${list.size} 个，选中 ${it?.displayName ?: "无"}(id=${it?.id})")
        }
    } catch (t: Throwable) {
        Dbg.w(TAG, "读取日历列表失败 ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /** 纯函数，单测覆盖：主日历优先，其次按 id。 */
    internal fun pick(calendars: List<WritableCalendar>): WritableCalendar? =
        calendars.filter { it.accessLevel >= MIN_WRITABLE_ACCESS }
            .sortedWith(compareByDescending<WritableCalendar> { it.primary }.thenBy { it.id })
            .firstOrNull()

    /**
     * 查重：同一日历里、标题完全相同、开始时间落在 ±[toleranceMs] 内、未删除的事件。
     * @return 已存在事件的 id；没有则 null
     */
    fun findDuplicate(
        context: Context,
        calendarId: Long,
        title: String,
        startMs: Long,
        toleranceMs: Long,
    ): Long? = try {
        val selection = "${CalendarContract.Events.CALENDAR_ID}=? AND " +
            "${CalendarContract.Events.TITLE}=? AND " +
            "${CalendarContract.Events.DTSTART} BETWEEN ? AND ? AND " +
            "${CalendarContract.Events.DELETED}=0"
        val args = arrayOf(
            calendarId.toString(),
            title,
            (startMs - toleranceMs).toString(),
            (startMs + toleranceMs).toString(),
        )
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            selection,
            args,
            null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    } catch (t: Throwable) {
        Dbg.w(TAG, "日历查重失败 ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /** 插入事件。@return 事件 id；失败 null */
    fun insert(context: Context, calendarId: Long, draft: EventDraft): Long? = try {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, draft.title)
            put(CalendarContract.Events.DESCRIPTION, draft.description)
            put(CalendarContract.Events.DTSTART, draft.startMs)
            put(CalendarContract.Events.DTEND, draft.endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, draft.timeZone)
            put(CalendarContract.Events.HAS_ALARM, 0)
        }
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?.let { ContentUris.parseId(it) }
    } catch (t: Throwable) {
        Dbg.w(TAG, "写入日历失败 ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    fun delete(context: Context, eventId: Long): Boolean = try {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        context.contentResolver.delete(uri, null, null) > 0
    } catch (t: Throwable) {
        Dbg.w(TAG, "删除日历事件失败 ${t.javaClass.simpleName}: ${t.message}")
        false
    }
}
