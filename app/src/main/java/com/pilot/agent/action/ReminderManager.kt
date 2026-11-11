package com.pilot.agent.action

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pilot.agent.Dbg
import com.pilot.agent.query.SecretVault
import com.pilot.agent.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Local, reboot-resilient one-shot reminders; reminder text never leaves the device. */
object ReminderManager {
    private const val PREFS = "local_reminders"
    private const val KEY_ITEMS = "items"
    private const val CHANNEL = "pilot_reminders"
    private const val TAG = "PilotReminder"

    data class Reminder(val id: String, val title: String, val at: Long)

    fun handles(text: String): Boolean =
        text.contains("提醒我") || text.contains("记得提醒") ||
            text.contains("我的提醒") || text.contains("取消提醒") || text.contains("删除提醒")

    fun handle(context: Context, text: String): String {
        if (text.contains("我的提醒")) {
            val all = load(context).sortedBy { it.at }
            return if (all.isEmpty()) "现在没有待触发的提醒。" else buildString {
                append("你的提醒：")
                all.take(10).forEach { append("\n• ").append(it.title).append(" — ").append(formatTime(it.at)) }
            }
        }
        if (text.contains("取消提醒") || text.contains("删除提醒")) {
            val keyword = text.substringAfter("提醒").removePrefix("我").trim(' ', '：', ':', '，', ',', '。')
            val target = load(context).firstOrNull { keyword.isBlank() || it.title.contains(keyword) }
                ?: return "没有找到要取消的提醒。说“我的提醒”可以查看列表。"
            cancel(context, target)
            return "已取消提醒：${target.title}"
        }
        val parsed = parse(text) ?: return "我还不能确定提醒时间。请说“30 分钟后提醒我喝水”或“2 小时后提醒我开会”。"
        val (title, at) = parsed
        if (at <= System.currentTimeMillis() || at > System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30)) {
            return "提醒时间需要在现在之后、未来 30 天以内。"
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "需要先开启 Pilot 的通知权限，才能可靠提醒你。请在系统设置中允许通知，然后重新设置提醒。"
        }
        val item = Reminder(UUID.randomUUID().toString(), title, at)
        save(context, load(context) + item)
        schedule(context, item)
        return "已设提醒：${item.title}，${formatTime(item.at)}。可以说“我的提醒”查看，或“取消提醒”。"
    }

    fun rescheduleAll(context: Context) {
        load(context).forEach { item ->
            if (item.at <= System.currentTimeMillis()) remove(context, item.id) else schedule(context, item)
        }
    }

    fun fire(context: Context, id: String) {
        val item = load(context).firstOrNull { it.id == id } ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        remove(context, id)
        val open = PendingIntent.getActivity(
            context, id.hashCode(), Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "提醒", NotificationManager.IMPORTANCE_HIGH))
        }
        manager.notify(id.hashCode(), NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.pilot.agent.R.drawable.ic_pilot)
            .setContentTitle("Pilot 提醒")
            .setContentText(item.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.title))
            .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }

    private fun schedule(context: Context, item: Reminder) {
        runCatching {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_FIRE
                data = Uri.parse("pilot-reminder://${item.id}")
                putExtra(EXTRA_ID, item.id)
            }
            val pi = PendingIntent.getBroadcast(context, item.id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (Build.VERSION.SDK_INT >= 23) {
                if (Build.VERSION.SDK_INT < 31 || runCatching { alarm.canScheduleExactAlarms() }.getOrDefault(false)) {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, pi)
                } else {
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, pi)
                }
            } else alarm.setExact(AlarmManager.RTC_WAKEUP, item.at, pi)
        }.onFailure { Dbg.w(TAG, "schedule failed: ${it.javaClass.simpleName}") }
    }

    private fun cancel(context: Context, item: Reminder) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE; data = Uri.parse("pilot-reminder://${item.id}")
        }
        PendingIntent.getBroadcast(context, item.id.hashCode(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(it)
            it.cancel()
        }
        remove(context, item.id)
    }

    private fun parse(raw: String): Pair<String, Long>? {
        val text = raw.trim().replace("半小时", "30分钟")
        val patterns = listOf(
            Regex("(?:(\\d+)\\s*分钟后|(?:(\\d+)\\s*小时后)|(?:(\\d+)\\s*天后))\\s*提醒我\\s*(.+)"),
            Regex("提醒我\\s*(.+?)\\s*(?:(\\d+)\\s*分钟后|(\\d+)\\s*小时后|(\\d+)\\s*天后)"),
        )
        patterns.forEachIndexed { index, regex ->
            val m = regex.find(text) ?: return@forEachIndexed
            val title: String
            val amount: Long
            val unit: Long
            if (index == 0) {
                amount = (1..3).firstNotNullOfOrNull { m.groupValues[it].toLongOrNull() } ?: return@forEachIndexed
                val group = (1..3).first { m.groupValues[it].isNotBlank() }
                unit = when (group) { 1 -> 60_000L; 2 -> 3_600_000L; else -> 86_400_000L }
                title = m.groupValues[4]
            } else {
                title = m.groupValues[1]
                val group = (2..4).firstOrNull { m.groupValues[it].isNotBlank() } ?: return@forEachIndexed
                amount = m.groupValues[group].toLongOrNull() ?: return@forEachIndexed
                unit = when (group) { 2 -> 60_000L; 3 -> 3_600_000L; else -> 86_400_000L }
            }
            val clean = title.trim(' ', '，', ',', '。', '.', '！', '!')
            if (clean.isNotBlank() && amount in 1..720) return clean to (System.currentTimeMillis() + amount * unit)
        }
        val dateOffset = when {
            text.contains("后天") -> 2
            text.contains("明天") -> 1
            text.contains("今天") -> 0
            else -> -1
        }
        if (dateOffset >= 0) {
            val clock = Regex("(?:(上午|下午|晚上|中午)\\s*)?(\\d{1,2})(?::|点)(\\d{1,2})?分?").find(text)
            if (clock != null) {
                var hour = clock.groupValues[2].toIntOrNull() ?: return null
                val minute = clock.groupValues[3].toIntOrNull() ?: 0
                if (hour !in 0..23 || minute !in 0..59) return null
                if (clock.groupValues[1] in listOf("下午", "晚上") && hour < 12) hour += 12
                if (clock.groupValues[1] == "上午" && hour == 12) hour = 0
                val title = text.substringAfter("提醒我", text)
                    .replace(Regex("今天|明天|后天|上午|下午|晚上|中午|在|于"), " ")
                    .replace(Regex("\\s*\\d{1,2}(?::|点)\\d{0,2}分?\\s*"), " ")
                    .replace(Regex("[，,。\\s]+"), " ")
                    .trim()
                if (title.isBlank()) return null
                val cal = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, dateOffset)
                    set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                return title.take(160) to cal.timeInMillis
            }
        }
        return null
    }

    private fun load(context: Context): List<Reminder> = runCatching {
        val array = JSONArray(SecretVault.get(KEY_ITEMS).ifBlank { "[]" })
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Reminder(o.optString("id"), o.optString("title"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, items: List<Reminder>) {
        val array = JSONArray()
        items.take(100).forEach { array.put(JSONObject().put("id", it.id).put("title", it.title).put("at", it.at)) }
        SecretVault.put(KEY_ITEMS, array.toString())
    }

    private fun remove(context: Context, id: String) = save(context, load(context).filterNot { it.id == id })
    private fun formatTime(time: Long): String = SimpleDateFormat("MM月dd日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(time))

    const val ACTION_FIRE = "com.pilot.agent.REMINDER_FIRE"
    const val ACTION_BOOT = "com.pilot.agent.REMINDER_BOOT"
    const val EXTRA_ID = "reminder_id"
}

class ReminderReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderManager.ACTION_FIRE -> intent.getStringExtra(ReminderManager.EXTRA_ID)?.let { ReminderManager.fire(context, it) }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, ReminderManager.ACTION_BOOT -> ReminderManager.rescheduleAll(context)
        }
    }
}
