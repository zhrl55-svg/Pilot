package com.pilot.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.pilot.agent.Dbg

/**
 * 常驻前台服务：**后台起 Activity 的通行证**。
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
 * 只在**真的要起 Activity 时**才拉起（startForegroundService + 立即
 * startForeground 满足 5 秒内要求），用完 stopSelf。
 */
class PilotForegroundService : Service() {

    companion object {
        private const val TAG = "PilotFgSvc"
        private const val CHANNEL_ID = "pilot_agent_fg"
        private const val NOTIF_ID = 1001
        private const val ACTION_PING = "com.pilot.agent.FG_PING"

        /**
         * 把进程提升为前台状态，返回是否成功。
         * 提升后本进程起 Activity 就不再被拦。
         */
        fun promote(context: Context): Boolean {
            return try {
                context.startForegroundService(
                    Intent(context, PilotForegroundService::class.java),
                )
                // 立刻把进程状态提到前台（必须在 5 秒内调 startForeground）
                context.startService(
                    Intent(context, PilotForegroundService::class.java).setAction(ACTION_PING),
                )
                true
            } catch (t: Throwable) {
                Dbg.w(TAG, "前台服务提升失败 ${t.message}")
                false
            }
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
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Pilot 语音助手")
            .setContentText("待命中，说「小派」唤醒")
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
        try {
            startForeground(NOTIF_ID, n)
        } catch (t: Throwable) {
            Dbg.w(TAG, "startForeground 失败 ${t.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        Dbg.i(TAG, "前台服务已启动（pid=${android.os.Process.myPid()}）")
        return START_STICKY
    }

    override fun onDestroy() {
        Dbg.i(TAG, "前台服务退出")
        super.onDestroy()
    }
}
