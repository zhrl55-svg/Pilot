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
import com.pilot.agent.query.QueryEngine
import com.pilot.agent.query.SecretVault
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

    data class Task(
        val id: String,
        val goal: String,
        val at: Long,
        val daily: Boolean,
    )

    fun handles(text: String): Boolean =
        Regex("每天\\s*(?:早上|上午|中午|下午|晚上)?\\s*\\d{1,2}(?:[:：点时])")
            .containsMatchIn(text) ||
            Regex("\\d+\\s*(?:分钟|小时)后\\s*(?:执行|运行|查看|查询|播报)")
                .containsMatchIn(text)

    fun create(context: Context, rawText: String): String? {
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

    fun list(context: Context): String {
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

    fun cancel(context: Context, keyword: String): String {
        val clean = keyword.trim()
        val tasks = load(context)
        val target = if (clean.isBlank()) tasks.firstOrNull() else tasks.firstOrNull {
            it.goal.contains(clean, ignoreCase = true)
        }
        if (target == null) return "没有找到要取消的定时任务。"
        cancelAlarm(context, target)
        save(context, tasks.filterNot { it.id == target.id })
        return "已取消定时任务：${target.goal}"
    }

    fun rescheduleAll(context: Context) {
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

    fun fire(context: Context, id: String) {
        val tasks = load(context)
        val task = tasks.firstOrNull { it.id == id } ?: return
        if (task.daily) {
            val next = task.copy(at = nextDailyAt(task.at, after = System.currentTimeMillis()))
            save(context, tasks.map { if (it.id == id) next else it })
            schedule(context, next)
        } else {
            save(context, tasks.filterNot { it.id == id })
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
            ?: "这个任务当前没有可用的数据源。"
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

    private fun load(context: Context): List<Task> = runCatching {
        val array = JSONArray(SecretVault.get(KEY_ITEMS).ifBlank { "[]" })
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            Task(
                id = obj.optString("id"),
                goal = obj.optString("goal"),
                at = obj.optLong("at"),
                daily = obj.optBoolean("daily"),
            )
        }
    }.getOrDefault(emptyList())

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
        SecretVault.put(KEY_ITEMS, array.toString())
    }

    private fun nextDailyAt(previous: Long, after: Long = previous): Long {
        var value = previous
        while (value <= after) value += 86_400_000L
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
