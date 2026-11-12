package com.pilot.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.app.PendingIntent
import com.pilot.agent.runtime.AgentRuntime
import com.pilot.agent.ui.MainActivity
import com.pilot.agent.agent.AgentSettings
import com.pilot.agent.Dbg

/**
 * 前台服务有两种用途：语音待命期间托管麦克风生命周期，以及短暂提升进程以便后台打开 Activity。
 *
 * ## 为什么必须有它
 * Android 10+ 起后台 App 起 Activity 会被拦（HarmonyOS 更严：
 * `Abort background activity starts from <uid>`）。语音 agent 的常态恰恰是
 * 「App 在后台，用户喊一句『打开设置』」——没有前台服务这条路直接断掉。
 *
 * 关键在于**进程状态**：
 * - 无障碍服务 = `BOUND_FOREGROUND_SERVICE` → 依然被拦（实测 11-10）
 * - 前台服务   = `FOREGROUND_SERVICE` → 系统放行后台起 Activity
 *
 * 代价是一条常驻通知（用户可见，这是 Android 的硬要求）。
 * 通知做成低优先级+静音，用户基本无感；点击通知直接开主界面。
 *
 * ## 生命周期
 * 语音待命由前台页面启动并保持服务；用户关闭待命后停止。后台启动 Activity
 * 使用单独的 specialUse 类型，不申请麦克风能力。
 */
class PilotForegroundService : Service() {

    companion object {
        private const val TAG = "PilotFgSvc"
        private const val CHANNEL_ID = "pilot_agent_fg"
        private const val NOTIF_ID = 1001
        private const val ACTION_PING = "com.pilot.agent.FG_PING"
        private const val ACTION_WAKE = "com.pilot.agent.FG_WAKE"
        private const val ACTION_STOP_WAKE = "com.pilot.agent.FG_STOP_WAKE"
        @Volatile private var runningForWake = false

        /** 用户在前台开启麦克风后调用，满足 microphone FGS 的前台启动限制。 */
        fun startForWake(context: Context): Boolean {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Dbg.w(TAG, "未启动语音前台服务：缺少麦克风权限")
                return false
            }
            return start(context, ACTION_WAKE)
        }

        fun stopForWake(context: Context) {
            runCatching {
                context.startService(Intent(context, PilotForegroundService::class.java).setAction(ACTION_STOP_WAKE))
            }.onFailure { Dbg.w(TAG, "停止语音前台服务失败 ${it.message}") }
        }

        /** 仅更新已运行的待命通知；不会为了一条状态通知额外启动服务。 */
        fun updateWakeStatus(context: Context, status: String) {
            if (!runningForWake) return
            runCatching {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIF_ID, buildNotification(context, status, showStopAction = true))
            }.onFailure { Dbg.w(TAG, "更新待命通知失败 ${it.message}") }
        }

        private fun start(context: Context, action: String): Boolean = try {
            val intent = Intent(context, PilotForegroundService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
            true
        } catch (t: Throwable) {
            Dbg.w(TAG, "前台服务启动失败 ${t.message}")
            false
        }

        /**
         * 把进程提升为前台状态，返回是否成功。
         * 提升后本进程起 Activity 就不再被拦。
         */
        fun promote(context: Context): Boolean {
            return start(context, ACTION_PING)
        }

        private fun buildNotification(context: Context, status: String, showStopAction: Boolean): Notification {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Pilot 语音助手")
                .setContentText(status)
                .setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW)
            if (showStopAction) {
                val stop = PendingIntent.getService(
                    context, 1,
                    Intent(context, PilotForegroundService::class.java).setAction(ACTION_STOP_WAKE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                builder.addAction(android.R.drawable.ic_media_pause, "关闭待命", stop)
            }
            val publicVersion = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Pilot 语音助手")
                .setContentText("语音服务正在运行")
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .build()
            return builder.setPublicVersion(publicVersion).build()
        }

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "语音助手", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "常驻通知，用于在后台响应语音指令"
                    setShowBadge(false)
                    enableVibration(false)
                },
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        if (intent?.action == ACTION_STOP_WAKE) {
            runCatching { AgentRuntime.voiceInput?.stop() }
            runCatching { AgentSettings.save(AgentSettings.load().copy(wakeEnabled = false)) }
            runningForWake = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val wakeService = intent?.action == ACTION_WAKE || runningForWake
        runningForWake = wakeService
        val initialStatus = if (wakeService) "正在启动语音待命…" else "Pilot 正在准备打开页面…"
        try {
            val notification = buildNotification(this, initialStatus, showStopAction = wakeService)
            if (wakeService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // The transient background-activity path must not inherit the microphone type.
                startForeground(NOTIF_ID, notification, 0)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            Dbg.w(TAG, "startForeground 失败 ${t.message}")
            runningForWake = false
            stopSelf()
            return START_NOT_STICKY
        }
        Dbg.i(TAG, "前台服务已启动（wake=$wakeService pid=${android.os.Process.myPid()}）")
        // VoiceInput is process-owned; do not resurrect a notification claiming to listen
        // after Android has killed the process and its AudioRecord.
        if (!wakeService) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!runningForWake) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }, 5_000L)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (runningForWake) runCatching { AgentRuntime.voiceInput?.stop() }
        runningForWake = false
        Dbg.i(TAG, "前台服务退出")
        super.onDestroy()
    }
}
