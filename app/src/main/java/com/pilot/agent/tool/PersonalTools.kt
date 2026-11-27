package com.pilot.agent.tool

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.pilot.agent.action.PersonalTodoStore
import com.pilot.agent.action.ReminderManager
import com.pilot.agent.assistant.routing.ChineseTimeParser
import com.pilot.agent.query.NotificationQueryTool
import com.pilot.agent.query.QueryResult
import java.time.ZoneId

/**
 * 本机私人能力：提醒、待办、日历新建、通知摘要（审核 P0-7 本地优先 / 产品 §2.4 T2）。
 *
 * 原来这些只在 AgentLoop.runInner 里预路由，而 AgentLoop 在云端意图路由**之后**、
 * 且要求无障碍已连接：“添加待办 买牛奶”这种最私人的话要先发给云端、还要先开无障碍。
 * 现在它们由 [LocalToolRuntime.match] 在任何云端路由之前接住，全程不出设备。
 *
 * 时间统一用 [ChineseTimeParser]（认“8点半”“下午三点”“中午1点”），
 * 不再用 ReminderManager.parse 那套只认数字钟点、把“8点半”当 8:00 的旧解析（审核 R-02）。
 */
internal class PersonalTools(private val appContext: Context) {

    fun match(text: String): ToolInvocation? {
        if (LegacyIntentGuard.isDiscussion(text)) return null
        return matchReminder(text)
            ?: matchTodo(text)
            ?: matchCalendar(text)
            ?: matchNotifications(text)
    }

    // ── 提醒 ─────────────────────────────────────────────────────────
    private fun matchReminder(text: String): ToolInvocation? {
        if (listOf("我的提醒", "查看提醒", "有哪些提醒", "提醒列表").any(text::contains)) {
            return ToolInvocation(
                name = "list_reminders",
                group = ToolGroup.READ,
                summary = "查看提醒",
                confirmation = "允许查看提醒吗？",
            ) { ctx, _ -> ToolResult.success(ReminderManager.listText(ctx)) }
        }
        Regex("(?:取消|删除|删掉)(?:这个|那个)?提醒[：:，,\\s]*(.*)").find(text)?.let { m ->
            val keyword = m.groupValues[1].trim(' ', '：', ':', '，', ',', '。')
            return ToolInvocation(
                name = "cancel_reminder",
                group = ToolGroup.DELETE,
                summary = "取消提醒",
                confirmation = if (keyword.isBlank()) "要取消哪条提醒？" else "确认取消包含“$keyword”的提醒吗？",
            ) { ctx, _ ->
                val message = ReminderManager.cancel(ctx, keyword)
                if (message.startsWith("已取消")) ToolResult.success(message) else ToolResult.unavailable(message)
            }
        }
        if (!ReminderManager.handles(text)) return null
        val wakeUp = listOf("叫我起床", "叫醒我", "喊我起床").any(text::contains)
        return ToolInvocation(
            name = "create_reminder",
            group = ToolGroup.WRITE,
            summary = "创建提醒",
            confirmation = "允许创建这个提醒吗？",
            explicitAuthorized = true,
        ) { ctx, _ -> createReminder(ctx, text, wakeUp) }
    }

    private fun createReminder(ctx: Context, text: String, wakeUp: Boolean): ToolResult {
        val parsed = ChineseTimeParser.parse(text)
            ?: return ToolResult.unavailable(
                "我还不能确定提醒时间。可以说“30 分钟后提醒我喝水”或“明天早上 8 点半提醒我开会”。",
            )
        if (parsed.repeat != ChineseTimeParser.RepeatMode.ONCE) {
            return ToolResult.unavailable("提醒目前只支持一次性的。每天重复的事可以说“每天 8 点…”创建定时任务。")
        }
        if (parsed.ambiguous || parsed.allDay) {
            val hhmm = parsed.timeHhMm()
            return ToolResult.unavailable(
                "时间还不够明确（我理解成了 ${parsed.dateIso()} $hhmm），请说清楚上午还是下午，例如“下午 3 点提醒我…”。",
            )
        }
        val at = parsed.dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val title = reminderTitle(text, wakeUp)
        val message = ReminderManager.createFromParsed(ctx, title, at)
            ?: return ToolResult.unavailable("提醒内容没听清，请再说一次要提醒什么。")
        if (!message.startsWith("已设提醒")) return ToolResult.error(message)
        return if (wakeUp) {
            ToolResult.success(
                "$message\n注意：这是通知提醒，静音或勿扰时可能不会响。要可靠叫醒，请说“定一个 ${parsed.timeHhMm()} 的闹钟”。",
                "$message 这是通知提醒，静音时可能不响，要叫醒的话建议定个闹钟。",
            )
        } else {
            ToolResult.success(message)
        }
    }

    private fun reminderTitle(text: String, wakeUp: Boolean): String {
        if (wakeUp) return "起床"
        val stripped = ChineseTimeParser.stripTimeExpressions(text)
            .replace(Regex("提醒我|记得提醒我?|提醒一下|请|帮我|麻烦|一下|到时候|的时候|^在|^要"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '：', ':', '，', ',', '。', '.', '！', '!', '去')
        return stripped.ifBlank { "提醒" }.take(80)
    }

    // ── 待办 ─────────────────────────────────────────────────────────
    private val addInto = Regex("把(.+?)(?:加到|加入|添加到|放进|记到)(?:我的)?待办")

    private fun matchTodo(text: String): ToolInvocation? {
        addInto.find(text)?.let { m ->
            val item = m.groupValues[1].trim(' ', '：', ':', '，', ',', '。')
            if (item.isNotBlank()) return todoWrite("添加待办$item", "添加待办")
        }
        if (listOf("添加待办", "新增待办", "记一条待办", "加个待办", "加一条待办").any(text::contains)) {
            val normalized = text.replace("加个待办", "添加待办").replace("加一条待办", "添加待办")
            return todoWrite(normalized, "添加待办")
        }
        if (text.contains("完成待办")) {
            val key = text.substringAfter("完成待办").trim(' ', '：', ':', '，', ',', '。', '.', '！', '!')
            if (key.isBlank()) {
                // 审核 R-06：不带内容时原实现会把第一条（含已完成的）标记完成。
                return ToolInvocation(
                    name = "complete_todo",
                    group = ToolGroup.READ,
                    summary = "完成待办",
                    confirmation = "完成待办",
                ) { _, _ -> ToolResult.unavailable("要完成哪一条？例如“完成待办 买牛奶”。说“我的待办”可以先看清单。") }
            }
            return todoWrite(text, "完成待办")
        }
        if (text.contains("删除待办") || text.contains("清空已完成")) {
            return ToolInvocation(
                name = "delete_todo",
                group = ToolGroup.DELETE,
                summary = if (text.contains("清空已完成")) "清理已完成待办" else "删除待办",
                confirmation = if (text.contains("清空已完成")) "确认清理所有已完成的待办吗？" else "确认删除这条待办吗？",
            ) { _, _ -> todoResult(PersonalTodoStore.handle(text)) }
        }
        if (listOf("我的待办", "查看待办", "待办清单", "有什么待办", "待办列表").any(text::contains)) {
            return ToolInvocation(
                name = "list_todos",
                group = ToolGroup.READ,
                summary = "查看待办",
                confirmation = "允许查看待办吗？",
            ) { _, _ -> todoResult(PersonalTodoStore.handle("我的待办")) }
        }
        return null
    }

    private fun todoWrite(command: String, summary: String) = ToolInvocation(
        name = if (summary == "完成待办") "complete_todo" else "add_todo",
        group = ToolGroup.WRITE,
        summary = summary,
        confirmation = "允许修改待办吗？",
        explicitAuthorized = true,
    ) { _, _ -> todoResult(PersonalTodoStore.handle(command)) }

    private fun todoResult(message: String): ToolResult = when {
        message.isBlank() -> ToolResult.unavailable("没听懂要对待办做什么。可以说“添加待办 买牛奶”或“我的待办”。")
        message.startsWith("已") || message.startsWith("你的待办") || message.startsWith("待办清单是空的") ->
            ToolResult.success(message)
        else -> ToolResult.unavailable(message)
    }

    // ── 日历（打开系统新建页，用户点保存才写入） ─────────────────────
    private val calendarTriggers = listOf("添加日程", "新建日程", "创建日程", "加到日历", "日历里安排", "安排会议", "加个日程")

    private fun matchCalendar(text: String): ToolInvocation? {
        if (calendarTriggers.none(text::contains)) return null
        return ToolInvocation(
            name = "calendar_create",
            group = ToolGroup.READ,
            summary = "新建日程",
            confirmation = "允许打开日历新建页面吗？",
        ) { ctx, _ -> createCalendarEvent(ctx, text) }
    }

    private suspend fun createCalendarEvent(ctx: Context, text: String): ToolResult {
        val parsed = ChineseTimeParser.parse(text)
            ?: return ToolResult.unavailable("请补充日期和开始时间，例如“添加日程 明天下午 3 点项目讨论”。")
        if (parsed.ambiguous) {
            return ToolResult.unavailable("时间还不够明确，请说清楚日期和上午/下午，例如“明天下午 3 点”。")
        }
        var title = ChineseTimeParser.stripTimeExpressions(text)
        calendarTriggers.filter { it != "安排会议" }.forEach { title = title.replace(it, " ") }
        title = title.replace(Regex("请|帮我|麻烦|一下|一个|^在"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '：', ':', '，', ',', '。', '.', '！', '!')
            .replace("安排会议", "会议")
        if (title.isBlank()) return ToolResult.unavailable("请补充日程标题，例如“添加日程 明天下午 3 点项目讨论”。")
        val begin = parsed.dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, title.take(160))
            putExtra(CalendarContract.Events.DESCRIPTION, "由 Pilot 助手发起；请确认后保存。")
            if (parsed.allDay) {
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            } else {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 60 * 60 * 1000L)
            }
            putExtra(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(ctx.packageManager) == null) {
            return ToolResult.error("没有找到可用的日历应用，请先安装或启用日历。")
        }
        val whenText = if (parsed.allDay) parsed.dateIso() else "${parsed.dateIso()} ${parsed.timeHhMm()}"
        return LaunchResults.launch(
            ctx,
            intent,
            label = "日历新建页面",
            okMessage = "已打开日历新建页面：$title，$whenText。确认无误后点保存才会写入日历。",
            okSpoken = "已打开日历新建页面，确认后点保存。",
        )
    }

    // ── 通知摘要（个人数据：锁屏不读、只用本机 TTS） ──────────────────
    private fun matchNotifications(text: String): ToolInvocation? {
        if (NotificationQueryTool.MATCHES.none(text::contains)) return null
        return ToolInvocation(
            name = "read_notifications",
            group = ToolGroup.READ,
            summary = "读取最近通知",
            confirmation = "允许读取最近的通知吗？",
        ) { _, _ ->
            when (val r = NotificationQueryTool().executeResult(text)) {
                is QueryResult.Answer -> ToolResult.success(r.text).copy(privateSpeech = true)
                is QueryResult.NeedMoreInfo -> ToolResult.unavailable(r.prompt)
                is QueryResult.NoResult -> ToolResult.unavailable(r.why)
                is QueryResult.Unavailable -> ToolResult.unavailable(r.why)
            }
        }
    }
}
