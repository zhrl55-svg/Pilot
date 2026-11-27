package com.pilot.agent.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.content.ContextCompat
import com.pilot.agent.Dbg
import com.pilot.agent.agent.Risk
import com.pilot.agent.assistant.PrivacyGate
import com.pilot.agent.runtime.AgentRuntime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 一次待用户确认的请求：动作、对象、后果都要让用户看清。 */
data class ConfirmRequest(
    val id: Long,
    val risk: Risk,
    /** 要做什么，例如“删除文件”“发送消息”。 */
    val action: String,
    /** 对谁/对什么，例如“微信 · 张三”“/sdcard/a.txt”；未知时为 null。 */
    val target: String?,
    /** 确认后会发生什么。 */
    val consequence: String,
    /** 完整问句（界面弹窗与播报用）。 */
    val question: String,
    /** ui | agent | background，用于日志。 */
    val source: String = "",
)

enum class ConfirmOutcome {
    APPROVED,
    DENIED,

    /** 超时无人应答（不是“用户拒绝”）。 */
    TIMEOUT,

    /** 没有任何可用的确认通道（无界面、无悬浮窗权限、无通知权限）。 */
    NO_CHANNEL,
}

/**
 * 统一的确认通道（审核 P0-3 / F-07 / 产品 §5.3）。
 *
 * 所有入口（界面、前台语音、后台语音、AgentLoop）需要确认时都走 [request]：
 * - 主界面在前台：界面弹窗（ViewModel 订阅 [pending] 渲染）；
 * - 主界面不在前台：悬浮确认卡（[PilotStatusOverlay.showConfirmCard]），可在任何 App 之上点按；
 * - 没有悬浮窗权限：高优先级通知 + “确认/取消”按钮；
 * - 任意时刻都可以语音说“确认/取消”（[tryVoiceAnswer]）；付款类只接受语音“取消”；
 *   锁屏时语音“确认”不生效，要求解锁后在屏幕上确认。
 *
 * 同一时刻只有一个待确认请求；新请求会把旧请求按“拒绝”结束。
 */
object ConfirmChannel {

    private const val TAG = "PilotConfirm"
    private const val ACTION_ANSWER = "com.pilot.agent.CONFIRM_ANSWER"
    private const val EXTRA_ID = "confirm_id"
    private const val EXTRA_NONCE = "confirm_nonce"
    private const val EXTRA_APPROVED = "confirm_approved"
    private const val NOTIF_CHANNEL = "pilot_confirm"
    private const val NOTIF_ID = 1201
    const val DEFAULT_TIMEOUT_MS = 55_000L

    private class Pending(
        val request: ConfirmRequest,
        val deferred: CompletableDeferred<Boolean>,
        val nonce: String,
    )

    private val seq = AtomicLong(System.currentTimeMillis())

    @Volatile
    private var current: Pending? = null

    private val _pending = MutableStateFlow<ConfirmRequest?>(null)

    /** 当前待确认请求；主界面订阅它显示弹窗。 */
    val pending: StateFlow<ConfirmRequest?> = _pending.asStateFlow()

    @Volatile
    private var receiverRegistered = false

    fun newId(): Long = seq.incrementAndGet()

    fun consequenceFor(risk: Risk): String = when (risk) {
        Risk.MONEY -> "会产生付款或转账，完成后通常无法撤回。"
        Risk.IRREVERSIBLE -> "会修改或删除内容，可能无法撤销。"
        Risk.EXTERNAL -> "内容会发送给他人或交给其他应用。"
        Risk.SENSITIVE -> "会读取或使用你的隐私数据。"
        Risk.NONE -> "确认后立即执行。"
    }

    /** 只有一句问句时（AgentLoop、ToolConfirmer）构造请求。 */
    fun fromQuestion(question: String, risk: Risk, source: String, id: Long = newId()): ConfirmRequest =
        ConfirmRequest(
            id = id,
            risk = risk,
            action = question.trim().removeSuffix("？").removeSuffix("?").take(120),
            target = null,
            consequence = consequenceFor(risk),
            question = question.trim(),
            source = source,
        )

    /**
     * 发起确认并挂起等待结果。可在任意线程/协程调用。
     */
    suspend fun request(
        context: Context,
        request: ConfirmRequest,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        speak: Boolean = true,
    ): ConfirmOutcome {
        val app = context.applicationContext
        val deferred = CompletableDeferred<Boolean>()
        val mine = Pending(request, deferred, UUID.randomUUID().toString())
        val previous = synchronized(this) {
            val p = current
            current = mine
            p
        }
        previous?.deferred?.complete(false)
        _pending.value = request

        val presented = try {
            withContext(Dispatchers.Main.immediate) { present(app, mine) }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                clear(app, mine)
                throw t
            }
            Dbg.w(TAG, "展示确认失败 ${t.javaClass.simpleName}")
            false
        }
        val voiceAvailable = AgentRuntime.voiceInput != null
        if (!presented && !voiceAvailable) {
            Dbg.w(TAG, "没有可用的确认通道 id=${request.id}")
            clear(app, mine)
            return ConfirmOutcome.NO_CHANNEL
        }
        if (speak) speakQuestion(app, request)
        Dbg.i(TAG, "等待确认 id=${request.id} risk=${request.risk} source=${request.source} presented=$presented")

        val answer = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            clear(app, mine)
        }
        val outcome = when (answer) {
            null -> ConfirmOutcome.TIMEOUT
            true -> ConfirmOutcome.APPROVED
            false -> ConfirmOutcome.DENIED
        }
        Dbg.i(TAG, "确认结束 id=${request.id} outcome=$outcome")
        return outcome
    }

    /** 界面弹窗、悬浮卡、通知按钮的统一应答入口。 */
    fun answer(id: Long, approved: Boolean): Boolean {
        val p = current ?: return false
        if (p.request.id != id) return false
        return p.deferred.complete(approved)
    }

    /** 停止按钮：结束当前确认（按拒绝处理）。 */
    fun cancelAll() {
        current?.deferred?.complete(false)
    }

    fun hasPending(): Boolean = current != null

    /**
     * 语音应答。只接短句（≤10 字）里的明确“确认/取消”，其余返回 false 交给正常指令链。
     * @return true = 这句话已被当作确认应答消费
     */
    fun tryVoiceAnswer(context: Context, text: String): Boolean {
        val p = current ?: return false
        val t = text.trim().replace(Regex("""[\s，,。.！!？?、~～…]"""), "")
        if (t.isEmpty() || t.length > 10) return false
        val deny = listOf("取消", "不要", "算了", "不用", "不行", "拒绝", "放弃", "停止").any(t::contains) ||
            t.startsWith("别") || t.startsWith("不") || t == "停" || t == "否"
        if (deny) {
            p.deferred.complete(false)
            AgentRuntime.speaker.speakLocal("好的，已取消。")
            return true
        }
        val approve = listOf("确认", "确定", "执行", "同意", "可以", "好的", "是的", "发吧", "继续", "没问题").any(t::contains) ||
            t == "好" || t == "是" || t == "对" || t == "行" || t == "嗯"
        if (!approve) return false
        if (p.request.risk == Risk.MONEY) {
            AgentRuntime.speaker.speakLocal("涉及付款，请在屏幕上点确认；说取消可以放弃。")
            return true
        }
        if (PrivacyGate.isDeviceLocked(context)) {
            AgentRuntime.speaker.speakLocal(PrivacyGate.LOCKED_CONFIRM_REPLY)
            return true
        }
        p.deferred.complete(true)
        AgentRuntime.speaker.speakQuiet("好的")
        return true
    }

    /** 主界面回到前台：收起悬浮卡/通知，弹窗由 ViewModel 根据 [pending] 显示。 */
    fun onAppForeground(context: Context) {
        val p = current ?: return
        val app = context.applicationContext
        mainPost {
            PilotStatusOverlay.dismissConfirmCard(app, p.request.id)
            cancelNotification(app)
        }
    }

    /** 主界面退到后台：仍在等待的请求改用悬浮卡（或通知）。 */
    fun onAppBackground(context: Context) {
        val p = current ?: return
        val app = context.applicationContext
        mainPost {
            if (current === p && !PilotStatusOverlay.isConfirmCardShowing(p.request.id)) {
                if (!PilotStatusOverlay.showConfirmCard(app, p.request) { ok -> answer(p.request.id, ok) }) {
                    postNotification(app, p)
                }
            }
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    /** 主线程调用。@return 是否有可见的确认界面 */
    private fun present(app: Context, p: Pending): Boolean {
        if (PilotStatusOverlay.isAppForeground) return true // 界面弹窗
        if (PilotStatusOverlay.showConfirmCard(app, p.request) { ok -> answer(p.request.id, ok) }) return true
        return postNotification(app, p)
    }

    private fun clear(app: Context, p: Pending) {
        val wasCurrent = synchronized(this) {
            if (current === p) {
                current = null
                true
            } else {
                false
            }
        }
        if (wasCurrent || _pending.value?.id == p.request.id) {
            if (_pending.value?.id == p.request.id) _pending.value = null
        }
        mainPost {
            PilotStatusOverlay.dismissConfirmCard(app, p.request.id)
            if (wasCurrent) cancelNotification(app)
        }
    }

    private fun speakQuestion(app: Context, request: ConfirmRequest) {
        val text = when {
            PrivacyGate.isDeviceLocked(app) ->
                "有个操作需要你确认，请解锁手机后在屏幕上确认，或者说取消。"
            request.risk == Risk.MONEY -> "${request.question} 涉及付款，请在屏幕上点确认。"
            else -> "${request.question} 可以说确认或取消。"
        }
        // 问句里可能有消息正文/联系人，只走本机 TTS。
        runCatching { AgentRuntime.speaker.speakLocal(text) }
    }

    private fun mainPost(block: () -> Unit) {
        AgentRuntime.scope.launch(Dispatchers.Main.immediate) {
            runCatching(block).onFailure { Dbg.w(TAG, "确认界面更新失败 ${it.message}") }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val p = current ?: return
            val id = intent.getLongExtra(EXTRA_ID, -1L)
            val nonce = intent.getStringExtra(EXTRA_NONCE).orEmpty()
            // nonce 防伪：只有本次通知里的按钮才能应答。
            if (id != p.request.id || nonce != p.nonce) {
                Dbg.w(TAG, "忽略不匹配的确认应答 id=$id")
                return
            }
            answer(id, intent.getBooleanExtra(EXTRA_APPROVED, false))
        }
    }

    private fun ensureReceiver(app: Context) {
        if (receiverRegistered) return
        synchronized(this) {
            if (receiverRegistered) return
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter(ACTION_ANSWER),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    private fun notificationsAllowed(app: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 没有悬浮窗权限时的兜底：高优先级通知 + 两个按钮。 */
    private fun postNotification(app: Context, p: Pending): Boolean {
        if (!notificationsAllowed(app)) return false
        return try {
            ensureReceiver(app)
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(NOTIF_CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(NOTIF_CHANNEL, "操作确认", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Pilot 执行发送、删除、付款等操作前请你确认"
                    },
                )
            }
            fun answerIntent(approved: Boolean, code: Int): PendingIntent = PendingIntent.getBroadcast(
                app,
                code,
                Intent(ACTION_ANSWER)
                    .setPackage(app.packageName)
                    .putExtra(EXTRA_ID, p.request.id)
                    .putExtra(EXTRA_NONCE, p.nonce)
                    .putExtra(EXTRA_APPROVED, approved),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = PendingIntent.getActivity(
                app,
                3,
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val r = p.request
            val body = buildString {
                append("动作：").append(r.action)
                r.target?.takeIf { it.isNotBlank() }?.let { append("\n对象：").append(it) }
                append("\n后果：").append(r.consequence)
            }
            val publicVersion = Notification.Builder(app, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Pilot 需要你确认一个操作")
                .setContentText("解锁后查看")
                .build()
            val builder = Notification.Builder(app, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(if (r.risk == Risk.MONEY) "Pilot 需要你确认（涉及付款）" else "Pilot 需要你确认")
                .setContentText(r.action)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
            val confirmAction = Notification.Action.Builder(
                Icon.createWithResource(app, android.R.drawable.ic_menu_send),
                "确认执行",
                answerIntent(true, 1),
            )
            if (Build.VERSION.SDK_INT >= 31) confirmAction.setAuthenticationRequired(true)
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(app, android.R.drawable.ic_menu_close_clear_cancel),
                    "取消",
                    answerIntent(false, 2),
                ).build(),
            )
            builder.addAction(confirmAction.build())
            nm.notify(NOTIF_ID, builder.build())
            true
        } catch (t: Throwable) {
            Dbg.w(TAG, "确认通知发送失败 ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun cancelNotification(app: Context) {
        runCatching {
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        }
    }
}
