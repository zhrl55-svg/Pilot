package com.pilot.agent.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.provider.Settings
import android.content.ComponentName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory notification cache. Notification bodies are never persisted by Pilot. */
object NotificationInbox {
    data class Entry(val app: String, val title: String, val text: String, val at: Long)
    private val entries = ArrayDeque<Entry>()
    @Volatile var connected: Boolean = false
        internal set

    @Synchronized fun add(entry: Entry) {
        if (entry.title.isBlank() && entry.text.isBlank()) return
        entries.removeAll { it.app == entry.app && it.title == entry.title && it.text == entry.text }
        entries.addFirst(entry)
        while (entries.size > 80) entries.removeLast()
    }

    @Synchronized fun recent(limit: Int = 5): List<Entry> = entries.take(limit.coerceIn(1, 20))
    @Synchronized fun clear() = entries.clear()

    fun isPermissionEnabled(context: android.content.Context): Boolean = runCatching {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        enabled.split(':').any { ComponentName.unflattenFromString(it) == ComponentName(context, NotificationListener::class.java) }
    }.getOrDefault(false)
}

class NotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationInbox.connected = true
    }

    override fun onListenerDisconnected() {
        NotificationInbox.connected = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0 || sbn.packageName == packageName) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(100)
        val body = buildList {
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let(::add)
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let(::add)
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it.toString()) }
        }.distinct().joinToString(" ").take(800)
        val sensitive = Regex("(?i)(验证码|校验码|动态码|一次性密码|otp|verification code|security code).{0,30}\\b\\d{4,8}\\b")
            .containsMatchIn(body)
        val safeBody = if (sensitive) "包含验证码，已隐藏" else body
        NotificationInbox.add(NotificationInbox.Entry(
            app = sbn.packageName.substringAfterLast('.').take(40),
            title = title,
            text = safeBody,
            at = sbn.postTime,
        ))
    }
}
