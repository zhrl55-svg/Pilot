package com.pilot.agent.sandbox

import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteCallbackList
import android.os.RemoteException
import com.pilot.agent.Dbg
import com.pilot.agent.PilotApp
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 沙箱独立进程服务（`android:process=":sandbox"`）。
 *
 * ## 为什么拆进程
 * proot guest 里跑 `apk add`、python、任何用户脚本都可能把 native 层搞炸
 * （SIGSEGV/SIGABRT）。合在主进程里，一个坏命令能把语音 agent 整个带走；
 * 拆开以后崩的是 :sandbox，主进程只是拿到一条失败结果。内存上 guest 的
 * RSS 也单独计量，LMK 优先回收沙箱。
 *
 * ## IPC 约定（ISandboxService.aidl）
 * startCommand 秒回 jobId，结果走 ResultCallback 异步回推——**绝不**在
 * binder 同步事务里等长命令（binder 事务超时不可控，apk add 动辄几分钟）。
 *
 * ## 生命周期
 * 首个 client bind 时建服务；**所有 client 解绑后不自杀**——ExecutionCoordinator
 * 里的热壳（PersistentShell）值钱，重启进程要重新 boot rootfs。真正回收交给
 * stopSelf 时机（后续可加空闲计时）。
 */
class SandboxService : Service() {

    companion object {
        const val TAG = "PilotSandbox"
        const val ACTION_BIND = "com.pilot.agent.SANDBOX_BIND"
    }

    private val jobs = AtomicLong(1)
    private val callbacks = RemoteCallbackList<ResultCallback>()
    private val io = Executors.newCachedThreadPool { r ->
        Thread(r, "sandbox-exec").apply { isDaemon = true }
    }
    private var host: IHostBridge? = null

    /** 最后一次活动时间（空闲回收用）。 */
    @Volatile private var lastActiveAt = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        Dbg.i(TAG, ":sandbox 进程启动 pid=${android.os.Process.myPid()}")
        SandboxTerminal.init(this)
        registerHostHandlers()
        startIdleReaper()
    }

    /**
     * 空闲回收：闲置一段时间后自杀，释放 guest 的内存（实测 :sandbox 常驻 130MB）。
     *
     * ## 为什么需要
     * guest 里的 proot + Alpine 常驻 130MB，对一台要跑端侧模型的手机不是小数。
     * 而沙箱的使用是**突发型**的——用户偶尔喊一句「在终端里跑个 df」，
     * 大部分时间它是闲的。闲着的 130MB 不如还给系统。
     *
     * ## 代价
     * 重启后要重新 boot rootfs（~1.5 秒，rootfs 已在磁盘不用重解包）
     * 并重建热壳（~150ms）。所以阈值不能太短——默认 5 分钟，
     * 用户正在连续用的时候不会被打断。
     *
     * ## 为什么不主动 unbind
     * 主进程不主动解绑（热壳值钱）。这里只杀沙箱自己：主进程下次调用时
     * bindService 会拉起新进程，透明无感。
     */
    private fun startIdleReaper() {
        val thresholdMs = idleTimeoutMs()
        Thread {
            var lastActive = System.currentTimeMillis()
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(30_000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val idle = System.currentTimeMillis() - lastActive
                if (idle >= thresholdMs) {
                    Dbg.i(TAG, "空闲 ${idle / 1000}s（阈值 ${thresholdMs / 1000}s），回收 :sandbox 进程")
                    // ★ 先通知主进程解绑：否则 BIND_AUTO_CREATE 会让系统
                    //   立刻把服务再拉起来，回收白做。
                    runCatching { host?.call("idleRecycle") }
                    // 先释放 guest 资源再自杀，别留半个 proot 在那儿
                    runCatching { ExecutionCoordinator.stopCurrentCommand() }
                    stopSelf()
                    // 给系统一点时间走正常的销毁流程
                    try {
                        Thread.sleep(500)
                    } catch (_: InterruptedException) {
                    }
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return@Thread
                }
            }
        }.apply {
            name = "sandbox-idle-reaper"
            isDaemon = true
            start()
        }
    }

    /** 空闲回收阈值（设置项可调，0 = 不回收）。 */
    private fun idleTimeoutMs(): Long {
        val v = com.pilot.agent.agent.AgentSettings.load().sandboxIdleMinutes
        return if (v <= 0) Long.MAX_VALUE else v * 60_000L
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : ISandboxService.Stub() {

        override fun startCommand(session: String, command: String, timeoutMs: Long): Long {
            lastActiveAt = System.currentTimeMillis()
            val jobId = jobs.getAndIncrement()
            io.execute {
                val r = try {
                    kotlinx.coroutines.runBlocking {
                        SandboxTerminal.runInService(
                            this@SandboxService, session, command, timeoutMs,
                        )
                    }
                } catch (t: Throwable) {
                    Dbg.e(TAG, "job $jobId 异常", t)
                    Triple("沙箱异常：${t.message}", -2, 0L)
                }
                lastActiveAt = System.currentTimeMillis()
                val b = Bundle().apply {
                    putString("out", r.first)
                    putInt("exit", r.second)
                    putLong("ms", r.third)
                }
                val n = callbacks.beginBroadcast()
                try {
                    for (i in 0 until n) {
                        try { callbacks.getBroadcastItem(i).onResult(jobId, b) }
                        catch (_: RemoteException) { }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
            return jobId
        }

        override fun stopCommand(jobId: Long): Boolean {
            // 会话级中断：ExecutionCoordinator.stopCurrentCommand 语义（打断该会话当前命令）
            return runCatching {
                ExecutionCoordinator.stopCurrentCommand()
                true
            }.getOrDefault(false)
        }

        override fun registerCallback(cb: ResultCallback?) {
            cb?.let { callbacks.register(it) }
        }

        override fun attachHost(bridge: IHostBridge?) {
            host = bridge
        }

        override fun status(): String =
            "pid=${android.os.Process.myPid()} rootfs=${SandboxTerminal.rootfsState(this@SandboxService)}"
    }

    /**
     * offload 最小集接回（开工清单 v2 第④项）：
     *   android-device  —— 机型/系统/电量，沙箱进程内直接答，不跨进程
     *   android-open    —— 打开 App，必须回主进程（无障碍/Intent 上下文在主进程）
     * 其余 minis handler（contacts/player/weather…）按需再加：Pilot 的 agent
     * 链路本身已覆盖这些能力，容器 CLI 调宿主只是锦上添花。
     */
    private fun registerHostHandlers() {
        NativeOffloadServer.register("android-device") { req ->
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val sb = StringBuilder()
            sb.append("brand=${Build.BRAND} model=${Build.MODEL} android=${Build.VERSION.SDK_INT}\n")
            sb.append("battery=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%\n")
            runCatching {
                sb.append("volume(stream3)=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}")
                    .append("/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}\n")
            }
            NativeOffloadResult(0, sb.toString())
        }
        NativeOffloadServer.register("android-open") { req ->
            val app = req.argv.drop(1).joinToString(" ").trim()
            if (app.isEmpty()) NativeOffloadResult(1, "usage: android-open <app>")
            else try {
                val b = host?.call("open:$app")
                when {
                    b == null -> NativeOffloadResult(1, "host bridge unavailable")
                    b.getBoolean("ok") -> NativeOffloadResult(0, "opened $app\n")
                    else -> NativeOffloadResult(1, b.getString("err") ?: "open failed")
                }
            } catch (t: Throwable) {
                NativeOffloadResult(-1, "host call failed: ${t.message}")
            }
        }
        Dbg.i(TAG, "offload handlers 已注册: ${NativeOffloadServer.registeredHandlers}")
    }
}
