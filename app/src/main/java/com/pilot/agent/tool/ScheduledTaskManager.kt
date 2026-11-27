package com.pilot.agent.tool

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pilot.agent.Dbg
import com.pilot.agent.query.EncryptedJsonStore
import com.pilot.agent.query.QueryEngine
import com.pilot.agent.query.VaultUnavailableException
import com.pilot.agent.runtime.AgentRuntime
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * A deliberately small workflow layer for fixed, non-interactive requests.
 *
 * It supports one-shot and daily schedules. At fire time it only runs local
 * read tools or QueryEngine; mutating tools still require confirmation and are
 * refused because an alarm-backed run has no person available to approve them.
 */
object ScheduledTaskManager {
    private const val TAG = "PilotScheduledTask"
    private const val KEY_ITEMS = "scheduled_tool_tasks_v1"
    private const val CHANNEL = "pilot_scheduled_tools"
    /** 到点时存储读不出来：隔 15 分钟重试一次，避免每日任务链就此断掉。 */
    private const val STORE_RETRY_MS = 15L * 60_000L

    data class Task(
        val id: String,
        val goal: String,
        val at: Long,
        val daily: Boolean,
    )

    /**
     * E1：加密存储读/写失败时中止本次「读→改→写」，返回 [fallback]（如实告知没有做修改），
     * 绝不把「读失败得到的空列表」写回去覆盖用户的定时任务。
     */
    private inline fun <T> guardStore(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: VaultUnavailableException) {
            Dbg.e(TAG, "定时任务存储不可用，已中止本次操作：${e.name}")
            fallback
        }

    fun handles(text: String): Boolean =
        Regex("每天\\s*(?:早上|上午|中午|下午|晚上)?\\s*\\d{1,2}(?:[:：点时])")
            .containsMatchIn(text) ||
            Regex("\\d+\\s*(?:分钟|小时)后\\s*(?:执行|运行|查看|查询|播报)")
                .containsMatchIn(text)

    @Synchronized
    fun create(context: Context, rawText: String): String? =
        guardStore(EncryptedJsonStore.UNAVAILABLE_MESSAGE) { createUnsafe(context, rawText) }

    private fun createUnsafe(context: Context, rawText: String): String? {
        val text = rawText.trim()
        val dailyMatch = Regex(
            "每天\\s*(早上|上午|中午|下午|晚上)?\\s*(\\d{1,2})(?:[:：点时])(\\d{1,2})?分?\\s*" +
                "(?:执行|运行|帮我|让\\s*Pilot\\s*|查看|查询|播报)?\\s*(.+)",
            RegexOption.IGNORE_CASE,
        ).find(text)
        val relativeMatch = Regex(
            "(\\d+)\\s*(分钟|小时)后\\s*(?:执行|运行|查看|查询|播报|帮我)?\\s*(.+)",
            RegexOption.IGNORE_CASE,
        ).find(text)

        val goal: String
        val at: Long
        val daily: Boolean
        if (dailyMatch != null) {
            val period = dailyMatch.groupValues[1]
            var hour = dailyMatch.groupValues[2].toIntOrNull() ?: return null
            val minute = dailyMatch.groupValues[3].toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) return null
            if (period in listOf("下午", "晚上") && hour < 12) hour += 12
            if (period == "中午" && hour < 11) hour += 12
            if (period == "上午" && hour == 12) hour = 0
            goal = cleanGoal(dailyMatch.groupValues[4])
            if (goal.isBlank()) return null
            at = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            daily = true
        } else if (relativeMatch != null) {
            val amount = relativeMatch.groupValues[1].toLongOrNull() ?: return null
            if (amount <= 0L || amount > 24L * 30L) return null
            val unit = if (relativeMatch.groupValues[2] == "小时") 3_600_000L else 60_000L
            goal = cleanGoal(relativeMatch.groupValues[3])
            if (goal.isBlank()) return null
            at = System.currentTimeMillis() + amount * unit
            daily = false
        } else {
            return null
        }
        if (!isSafeBackgroundGoal(goal)) {
            return "这个任务需要确认或操作屏幕，暂时不能无人值守执行。定时任务目前支持设备状态和资讯查询。"
        }
        val task = Task(UUID.randomUUID().toString(), goal, at, daily)
        save(context, load(context) + task)
        schedule(context, task)
        return "已创建${if (daily) "每日" else "一次性"}任务：${task.goal}，${formatTime(task.at)}" +
            if (daily) "，之后每天同一时间执行。" else "执行。"
    }

    // ── UI 用：结构化读写 ────────────────────────────────────────────

    /**
     * P1：快路径结构化入口。
     *
     * Router/SlotExtractor 已用 ChineseTimeParser 抽出绝对时间与任务内容，
     * 这里直接建任务，不再把原话交给只认特定正则的 [create] 重解析
     * （旧实现把 `at/after/prompt` 全丢掉，再失败也会被包成 success）。
     *
     * @return null = 参数不完整；否则为回话（成功以「已创建」开头，可直接据此判定）
     */
    @Synchronized
    fun createFromParsed(context: Context, goal: String, atMillis: Long, daily: Boolean): String? =
        guardStore(EncryptedJsonStore.UNAVAILABLE_MESSAGE) { createFromParsedUnsafe(context, goal, atMillis, daily) }

    private fun createFromParsedUnsafe(context: Context, goal: String, atMillis: Long, daily: Boolean): String? {
        val text = cleanGoal(goal)
        if (text.isBlank()) return null
        if (atMillis <= System.currentTimeMillis()) return null
        if (!isSafeBackgroundGoal(text)) {
            return "这个任务需要确认或操作屏幕，暂时不能无人值守执行。定时任务目前支持设备状态和资讯查询。"
        }
        val task = Task(UUID.randomUUID().toString(), text, atMillis, daily)
        save(context, load(context) + task)
        schedule(context, task)
        return "已创建${if (daily) "每日" else "一次性"}任务：${task.goal}，${formatTime(task.at)}" +
            if (daily) "，之后每天同一时间执行。" else "执行。"
    }

    /** 按 id 精确取消。@return null = 任务不存在；存储不可用时返回不以「已」开头的说明 */
    @Synchronized
    fun cancelById(context: Context, id: String): String? =
        guardStore(EncryptedJsonStore.UNAVAILABLE_MESSAGE) { cancelByIdUnsafe(context, id) }

    private fun cancelByIdUnsafe(context: Context, id: String): String? {
        val tasks = load(context)
        val target = tasks.firstOrNull { it.id == id } ?: return null
        cancelAlarm(context, target)
        save(context, tasks.filterNot { it.id == id })
        Dbg.i(TAG, "定时任务已取消：id=${target.id} ${com.pilot.agent.LogRedactor.text("goal", target.goal)}")
        return "已取消定时任务：${target.goal}"
    }

    /** 列出全部任务（按触发时间升序）。UI 定时任务面板用。存储读不出来时返回空列表（只读，不会覆盖）。 */
    @Synchronized
    fun allTasks(context: Context): List<Task> =
        guardStore(emptyList()) { load(context).sortedBy { it.at } }

    /**
     * 改时间 / 改内容 / 切换每日。改完立即重排闹钟。
     * @return 成功返回新任务，失败返回 null（任务不存在或存储不可用）。
     */
    @Synchronized
    fun updateTask(
        context: Context,
        id: String,
        goal: String = "",
        at: Long = 0L,
        daily: Boolean? = null,
    ): Task? = guardStore<Task?>(null) { updateTaskUnsafe(context, id, goal, at, daily) }

    private fun updateTaskUnsafe(
        context: Context,
        id: String,
        goal: String,
        at: Long,
        daily: Boolean?,
    ): Task? {
        val tasks = load(context).toMutableList()
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) return null
        val old = tasks[index]
        val next = old.copy(
            goal = goal.trim().ifBlank { old.goal }.let { cleanGoal(it) },
            at = if (at > 0L) at else old.at,
            daily = daily ?: old.daily,
        )
        tasks[index] = next
        cancelAlarm(context, old)
        save(context, tasks)
        schedule(context, next)
        Dbg.i(TAG, "定时任务已更新：id=${next.id} ${com.pilot.agent.LogRedactor.text("goal", next.goal)} at=${next.at} daily=${next.daily}")
        return next
    }

    /** 删除单条任务。@return 成功返回 true（存储不可用返回 false）。 */
    @Synchronized
    fun deleteTask(context: Context, id: String): Boolean = guardStore(false) { deleteTaskUnsafe(context, id) }

    private fun deleteTaskUnsafe(context: Context, id: String): Boolean {
        val tasks = load(context)
        val target = tasks.firstOrNull { it.id == id } ?: return false
        cancelAlarm(context, target)
        save(context, tasks.filterNot { it.id == id })
        Dbg.i(TAG, "定时任务已删除：id=${target.id} ${com.pilot.agent.LogRedactor.text("goal", target.goal)}")
        return true
    }

    /**
     * 新建任务（UI 直接填表单）。
     * @param at 触发时间（epoch 毫秒）
     * @param daily true = 每天同一时间
     * @return 存储不可用时返回 null
     */
    @Synchronized
    fun createTask(context: Context, goal: String, at: Long, daily: Boolean): Task? =
        guardStore<Task?>(null) { createTaskUnsafe(context, goal, at, daily) }

    private fun createTaskUnsafe(context: Context, goal: String, at: Long, daily: Boolean): Task? {
        val text = cleanGoal(goal)
        if (text.isBlank()) return null
        val task = Task(
            id = "st_" + System.currentTimeMillis(),
            goal = text,
            at = at,
            daily = daily,
        )
        save(context, load(context) + task)
        schedule(context, task)
        Dbg.i(TAG, "定时任务已创建：id=${task.id} ${com.pilot.agent.LogRedactor.text("goal", task.goal)} at=${task.at} daily=${task.daily}")
        return task
    }

    @Synchronized
    fun list(context: Context): String =
        guardStore(EncryptedJsonStore.UNAVAILABLE_MESSAGE) { listUnsafe(context) }

    private fun listUnsafe(context: Context): String {
        val tasks = load(context).sortedBy { it.at }
        if (tasks.isEmpty()) return "现在没有定时任务。"
        return buildString {
            append("定时任务：")
            tasks.take(20).forEach { task ->
                append("\n• ").append(task.goal).append(" — ")
                append(if (task.daily) "每天 " else "")
                append(formatTime(task.at))
            }
        }
    }

    @Synchronized
    fun cancel(context: Context, keyword: String): String =
        guardStore(EncryptedJsonStore.UNAVAILABLE_MESSAGE) { cancelUnsafe(context, keyword) }

    private fun cancelUnsafe(context: Context, keyword: String): String {
        val clean = keyword.trim()
        // 空关键词不猜（旧实现会直接取消最早一条，太危险）。
        if (clean.isBlank()) return "要取消哪条定时任务？说“查看定时任务”可以先看看列表。"
        val tasks = load(context)
        val target = tasks.firstOrNull {
            it.goal.contains(clean, ignoreCase = true)
        } ?: return "没有找到要取消的定时任务。"
        cancelAlarm(context, target)
        save(context, tasks.filterNot { it.id == target.id })
        return "已取消定时任务：${target.goal}"
    }

    @Synchronized
    fun rescheduleAll(context: Context) = guardStore(Unit) {
        val now = System.currentTimeMillis()
        val tasks = load(context).mapNotNull { task ->
            if (!task.daily && task.at <= now) {
                null
            } else {
                val next = if (task.daily && task.at <= now) {
                    task.copy(at = nextDailyAt(task.at))
                } else task
                schedule(context, next)
                next
            }
        }
        save(context, tasks)
    }

    @Synchronized
    fun fire(context: Context, id: String) {
        val tasks = try {
            load(context)
        } catch (e: VaultUnavailableException) {
            // 读不出来就不知道任务内容，也无法为每日任务排下一次：稍后重试同一个 id。
            Dbg.e(TAG, "定时任务到点但存储不可读，${STORE_RETRY_MS / 60_000} 分钟后重试：${e.name}")
            schedule(context, Task(id = id, goal = "", at = System.currentTimeMillis() + STORE_RETRY_MS, daily = false))
            return
        }
        val task = tasks.firstOrNull { it.id == id } ?: return
        if (task.daily) {
            val next = task.copy(at = nextDailyAt(task.at, after = System.currentTimeMillis()))
            guardStore(Unit) { save(context, tasks.map { if (it.id == id) next else it }) }
            schedule(context, next)
        } else {
            guardStore(Unit) { save(context, tasks.filterNot { it.id == id }) }
        }
        Thread {
            val answer = runCatching { runTask(context, task.goal) }
                .getOrElse {
                    Dbg.e(TAG, "task failed: ${it.javaClass.simpleName}: ${it.message}")
                    "定时任务执行失败。"
                }
            runCatching { notify(context, task.goal, answer) }
                .onFailure { Dbg.w(TAG, "notify failed: ${it.javaClass.simpleName}") }
            AgentRuntime.speaker.speak(answer)
        }.apply { name = "scheduled-task"; isDaemon = true }.start()
    }

    private fun runTask(context: Context, goal: String): String {
        if (AgentRuntime.localTools.match(goal) != null) {
            val result = runBlocking {
                AgentRuntime.localTools.execute(goal) { _, _ -> false }
            }
            return result?.spoken ?: result?.message ?: "定时任务没有返回结果。"
        }
        return runBlocking { QueryEngine.handle(goal) }
            ?.let { QueryEngine.speechSummary(it) ?: it }
            // 审核 Q-01：查询失败不当作答案，如实报出原因。
            ?: (QueryEngine.lastFailure?.let { "定时任务这次没查到结果：$it" } ?: "这个任务当前没有可用的数据源。")
    }

    private fun isSafeBackgroundGoal(goal: String): Boolean {
        val invocation = AgentRuntime.localTools.match(goal)
        if (invocation != null) return invocation.group == ToolGroup.READ
        return true // QueryEngine only; screen tasks are not routed here.
    }

    private fun schedule(context: Context, task: Task) {
        runCatching {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = pendingIntent(context, task, PendingIntent.FLAG_UPDATE_CURRENT)
                ?: return@runCatching
            if (Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.at, pending)
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.at, pending)
            }
        }.onFailure { Dbg.w(TAG, "schedule failed: ${it.javaClass.simpleName}") }
    }

    private fun cancelAlarm(context: Context, task: Task) {
        val pi = pendingIntent(context, task, PendingIntent.FLAG_NO_CREATE) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        pi.cancel()
    }

    private fun pendingIntent(context: Context, task: Task, mode: Int): PendingIntent? {
        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            action = ACTION_FIRE
            data = Uri.parse("pilot-scheduled-tool://${task.id}")
            putExtra(EXTRA_ID, task.id)
        }
        return PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            mode or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notify(context: Context, goal: String, answer: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "定时任务", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        manager.notify(
            goal.hashCode(),
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(com.pilot.agent.R.drawable.ic_pilot)
                .setContentTitle("Pilot 定时任务")
                .setContentText(answer)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${goal}\n$answer"))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** 读失败抛 [VaultUnavailableException]（由 [guardStore] 接住）；JSON 损坏会先备份再按空处理。 */
    private fun load(context: Context): List<Task> {
        val array = EncryptedJsonStore.readArray(KEY_ITEMS)
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            Task(
                id = obj.optString("id"),
                goal = obj.optString("goal"),
                at = obj.optLong("at"),
                daily = obj.optBoolean("daily"),
            )
        }
    }

    private fun save(context: Context, tasks: List<Task>) {
        val array = JSONArray()
        tasks.take(100).forEach { task ->
            array.put(
                JSONObject()
                    .put("id", task.id)
                    .put("goal", task.goal)
                    .put("at", task.at)
                    .put("daily", task.daily)
            )
        }
        EncryptedJsonStore.writeArray(KEY_ITEMS, array)
    }

    private fun nextDailyAt(previous: Long, after: Long = previous): Long {
        // P2：固定 +86400000ms 会在夏令时切换后漂移一小时（并且逐次累积）。
        // 用本地日历按"日"递增，时分保持不变。
        val cal = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = previous }
        var value = previous
        while (value <= after) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            value = cal.timeInMillis
        }
        return value
    }

    private fun cleanGoal(raw: String): String =
        raw.trim().trim('：', ':', '，', ',', '。', '.', ' ')
            .take(240)

    private fun formatTime(time: Long): String =
        SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(time))

    const val ACTION_FIRE = "com.pilot.agent.SCHEDULED_TOOL_FIRE"
    const val EXTRA_ID = "scheduled_tool_id"
}

class ScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ScheduledTaskManager.ACTION_FIRE ->
                intent.getStringExtra(ScheduledTaskManager.EXTRA_ID)?.let {
                    ScheduledTaskManager.fire(context, it)
                }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                ScheduledTaskManager.rescheduleAll(context)
        }
    }
}
