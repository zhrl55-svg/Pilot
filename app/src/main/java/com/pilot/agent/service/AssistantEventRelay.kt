package com.pilot.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.pilot.agent.Dbg
import com.pilot.agent.agent.AgentEvent
import com.pilot.agent.assistant.PrivacyGate
import com.pilot.agent.query.QueryEngine
import com.pilot.agent.runtime.AgentRuntime
import com.pilot.agent.ui.ConfirmChannel
import com.pilot.agent.ui.ConfirmOutcome
import com.pilot.agent.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch

/**
 * 运行时级的 AgentEvent 常驻订阅者（审核 V-09 / arch D3）。
 *
 * 原来只有 AgentViewModel 订阅 [AgentRuntime.events]；界面被销毁后，
 * AgentLoop 的 Finished/Failed/NeedConfirm 全部被丢弃（SharedFlow 无订阅者）。
 *
 * - NeedConfirm：**始终**由这里统一交给 [ConfirmChannel]（界面弹窗 / 悬浮卡 / 通知 / 语音），
 *   再把结果回填给 AgentLoop。ViewModel 不再自己处理 NeedConfirm，避免双重应答。
 * - Finished/Failed：界面不在时由这里播报 + 发通知；锁屏时只播报不含内容的提示。
 */
object AssistantEventRelay {

    private const val TAG = "PilotEventRelay"
    private const val CHANNEL_ID = "pilot_results"
    private const val NOTIF_ID = 1301

    private val started = AtomicBoolean(false)
    private val uiCount = AtomicInteger(0)

    val isUiAttached: Boolean get() = uiCount.get() > 0

    fun uiAttached() {
        uiCount.incrementAndGet()
    }

    fun uiDetached() {
        if (uiCount.decrementAndGet() < 0) uiCount.set(0)
    }

    /** 幂等。ViewModel、前台服务、后台助理都会调用。 */
    fun ensureStarted(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        AgentRuntime.scope.launch {
            AgentRuntime.events.collect { ev ->
                try {
                    handle(app, ev)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Dbg.w(TAG, "处理事件失败 ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
        Dbg.i(TAG, "事件常驻订阅已启动")
    }

    private fun handle(app: Context, ev: AgentEvent) {
        when (ev) {
            is AgentEvent.NeedConfirm -> {
                // 不能在 collect 里挂起等待，否则后续事件会被堵住。
                AgentRuntime.scope.launch {
                    val request = ConfirmChannel.fromQuestion(ev.question, ev.risk, "agent")
                    val outcome = ConfirmChannel.request(app, request)
                    Dbg.i(TAG, "AgentLoop 确认 id=${ev.requestId} → $outcome")
                    AgentRuntime.loop?.confirm(ev.requestId, outcome == ConfirmOutcome.APPROVED)
                    if (outcome == ConfirmOutcome.TIMEOUT) {
                        AgentRuntime.events.tryEmit(AgentEvent.Observe("确认等待超时，没有执行该操作（不是你拒绝的）。"))
                    } else if (outcome == ConfirmOutcome.NO_CHANNEL) {
                        AgentRuntime.events.tryEmit(AgentEvent.Observe("当前没有可以确认的界面，已放弃该操作。"))
                    }
                }
            }
            is AgentEvent.Finished -> if (!isUiAttached) deliver(app, ev.summary, ok = true)
            is AgentEvent.Failed -> if (!isUiAttached) deliver(app, ev.reason, ok = false)
            else -> Unit
        }
    }

    /**
     * 界面不在时的结果送达：TTS + 通知。
     * 锁屏时只念“有结果了”，内容放在锁屏隐藏的通知里（VISIBILITY_PRIVATE）。
     */
    fun deliver(app: Context, text: String, ok: Boolean, personal: Boolean = true) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val locked = PrivacyGate.isDeviceLocked(app)
        val spoken = when {
            locked && personal -> if (ok) PrivacyGate.LOCKED_RESULT_REPLY else "任务没有完成，解锁手机后可以在 Pilot 里查看原因。"
            ok -> QueryEngine.speechSummary(clean) ?: clean
            else -> "没有完成：$clean"
        }
        runCatching {
            if (personal) AgentRuntime.speaker.speakLocal(spoken) else AgentRuntime.speaker.speak(spoken)
        }.onFailure { Dbg.w(TAG, "播报失败 ${it.message}") }
        notifyResult(app, clean, ok)
    }

    private fun notifyResult(app: Context, text: String, ok: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "任务结果", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "界面关闭时，后台语音任务的结果"
                        setShowBadge(true)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                app,
                4,
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val title = if (ok) "Pilot 已完成" else "Pilot 没有完成任务"
            val publicVersion = Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle(title)
                .setContentText("解锁后查看详情")
                .build()
            val notification = Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle(title)
                .setContentText(text.take(80))
                .setStyle(Notification.BigTextStyle().bigText(text.take(1_000)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .build()
            nm.notify(NOTIF_ID, notification)
        }.onFailure { Dbg.w(TAG, "结果通知失败 ${it.javaClass.simpleName}") }
    }
}
