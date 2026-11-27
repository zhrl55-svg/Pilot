package com.pilot.agent.sandbox

import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import com.pilot.agent.Dbg
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 空闲回收判定（纯函数，便于单测；审核 code R1）。
 * 有命令在跑（inFlight>0）时**永不**回收；阈值为 Long.MAX_VALUE 表示关闭回收。
 */
internal object IdlePolicy {
    fun shouldRecycle(inFlight: Int, lastActiveAt: Long, now: Long, thresholdMs: Long): Boolean =
        inFlight <= 0 && thresholdMs != Long.MAX_VALUE && now - lastActiveAt >= thresholdMs
}

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
 * startCommand 返回 [JOB_REJECTED_RECYCLING] 表示沙箱正在回收、命令未启动。
 *
 * ## 线程模型
 * 命令执行和空闲回收都跑在 [serviceScope]（SupervisorJob + IO）里，
 * 不再使用裸 Thread / runBlocking；onDestroy 时统一取消。
 */
class SandboxService : Service() {

    companion object {
        const val TAG = "PilotSandbox"
        const val ACTION_BIND = "com.pilot.agent.SANDBOX_BIND"

        /** startCommand 的拒绝返回值：沙箱正在空闲回收，命令未启动。 */
        const val JOB_REJECTED_RECYCLING = -1L

        private const val REAPER_TICK_MS = 30_000L

        /** 沙箱等宿主（主进程）回推屏幕类操作结果的上限；主进程侧自身超时更短。 */
        private const val HOST_CALL_TIMEOUT_MS = 8_000L

        /** offload 结果未知时的退出码（与 SandboxTerminal.EXIT_UNKNOWN 一致）。 */
        private const val EXIT_UNKNOWN = 124

        // 与 AgentSettings 的存储保持一致（prefs 名 "pilot"、键 "sandbox_idle_minutes"）。
        // 直接读这一项，避免在 :sandbox 进程里 AgentSettings.load() 解密主进程密钥（审核 arch）。
        private const val PREFS_NAME = "pilot"
        private const val K_SANDBOX_IDLE = "sandbox_idle_minutes"
        private const val DEFAULT_IDLE_MINUTES = 5
    }

    private val jobs = AtomicLong(1)
    private val callbacks = RemoteCallbackList<ResultCallback>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var host: IHostBridge? = null

    /** 下列三项由 [stateLock] 保护：回收决策与新命令登记必须原子，避免“刚收到命令就被回收”。 */
    private val stateLock = Any()
    private var inFlight = 0
    private var recycling = false
    @Volatile private var lastActiveAt = System.currentTimeMillis()

    /** 正在执行的 job → session（stopCommand 只停仍在跑的 job 所在会话）。 */
    private val running = ConcurrentHashMap<Long, String>()

    override fun onCreate() {
        super.onCreate()
        Dbg.i(TAG, ":sandbox 进程启动 pid=${android.os.Process.myPid()}")
        SandboxTerminal.initInService(this)
        registerHostHandlers()
        startIdleReaper()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        callbacks.kill()
        super.onDestroy()
    }

    /**
     * 空闲回收：闲置一段时间后自杀，释放 guest 的内存（实测 :sandbox 常驻 130MB）。
     *
     * ## 修复（审核 code R1）
     * 旧实现读的是一个只赋值一次的局部变量，进程启动 N 分钟后必定被杀，
     * 不管有没有命令在跑。现在每次检查都读**实时**的 [inFlight] 和 [lastActiveAt]，
     * 有命令运行时不回收；决策和新命令登记在同一把锁下完成。
     *
     * ## 代价
     * 重启后要重新 boot rootfs（~1.5 秒，rootfs 已在磁盘不用重解包）
     * 并重建热壳（~150ms）。所以阈值不能太短——默认 5 分钟。
     */
    private fun startIdleReaper() {
        val thresholdMs = idleTimeoutMs()
        if (thresholdMs == Long.MAX_VALUE) {
            Dbg.i(TAG, "空闲回收已关闭")
            return
        }
        serviceScope.launch {
            while (isActive) {
                delay(REAPER_TICK_MS)
                val idleMs = synchronized(stateLock) {
                    val now = System.currentTimeMillis()
                    if (IdlePolicy.shouldRecycle(inFlight, lastActiveAt, now, thresholdMs)) {
                        recycling = true
                        now - lastActiveAt
                    } else {
                        -1L
                    }
                }
                if (idleMs < 0) continue
                Dbg.i(TAG, "空闲 ${idleMs / 1000}s（阈值 ${thresholdMs / 1000}s），无在途命令，回收 :sandbox 进程")
                // ★ 先通知主进程解绑：否则 BIND_AUTO_CREATE 会让系统
                //   立刻把服务再拉起来，回收白做。（快调用，不涉及屏幕操作）
                runCatching { host?.call("idleRecycle") }
                    .onFailure { Dbg.w(TAG, "通知主进程解绑失败：${it.javaClass.simpleName}") }
                // 先释放 guest 资源再自杀，别留半个 proot 在那儿
                runCatching { ExecutionCoordinator.stopCurrentCommand() }
                stopSelf()
                // onDestroy 会取消 serviceScope；这段收尾必须不可取消，否则 killProcess 永远走不到
                withContext(NonCancellable) {
                    delay(500) // 给系统一点时间走正常的销毁流程
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
                return@launch
            }
        }
    }

    /** 空闲回收阈值（设置项可调，0 = 不回收）。 */
    private fun idleTimeoutMs(): Long {
        val v = runCatching {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(K_SANDBOX_IDLE, DEFAULT_IDLE_MINUTES)
        }.getOrDefault(DEFAULT_IDLE_MINUTES)
        return if (v <= 0) Long.MAX_VALUE else v * 60_000L
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun broadcastResult(jobId: Long, r: Triple<String, Int, Long>) {
        val b = Bundle().apply {
            putString("out", r.first)
            putInt("exit", r.second)
            putLong("ms", r.third)
        }
        // RemoteCallbackList.beginBroadcast 不可并发：多个 job 同时结束时必须串行
        synchronized(callbacks) {
            val n = callbacks.beginBroadcast()
            try {
                for (i in 0 until n) {
                    try {
                        callbacks.getBroadcastItem(i).onResult(jobId, b)
                    } catch (e: RemoteException) {
                        Dbg.w(TAG, "job $jobId 结果回调失败：${e.message}")
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private val binder = object : ISandboxService.Stub() {

        override fun startCommand(session: String, command: String, timeoutMs: Long): Long {
            val jobId = synchronized(stateLock) {
                if (recycling) {
                    -1L
                } else {
                    inFlight++
                    lastActiveAt = System.currentTimeMillis()
                    jobs.getAndIncrement()
                }
            }
            if (jobId < 0) {
                Dbg.w(TAG, "沙箱正在回收，拒绝新命令")
                return JOB_REJECTED_RECYCLING
            }
            running[jobId] = session
            val timeout = SandboxTerminal.clampTimeout(timeoutMs)
            serviceScope.launch {
                val r = try {
                    SandboxTerminal.runInService(this@SandboxService, session, command, timeout)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Dbg.e(TAG, "job $jobId 异常", t)
                    Triple("沙箱异常：${t.message ?: t.javaClass.simpleName}", -2, 0L)
                } finally {
                    running.remove(jobId)
                    synchronized(stateLock) {
                        inFlight--
                        lastActiveAt = System.currentTimeMillis()
                    }
                }
                broadcastResult(jobId, r)
            }
            return jobId
        }

        override fun stopCommand(jobId: Long): Boolean {
            // 只停仍在运行的 job 所在会话的前台命令；已结束的 job 不误伤别的命令
            val session = running[jobId] ?: return false
            return try {
                if (ExecutionCoordinator.interruptForegroundCommands(session) == 0) {
                    ExecutionCoordinator.stopCurrentCommand(session)
                }
                true
            } catch (t: Throwable) {
                Dbg.w(TAG, "stopCommand($jobId) 失败：${t.javaClass.simpleName}")
                false
            }
        }

        override fun registerCallback(cb: ResultCallback?) {
            cb?.let { callbacks.register(it) }
        }

        override fun attachHost(bridge: IHostBridge?) {
            host = bridge
        }

        override fun status(): String =
            "pid=${android.os.Process.myPid()} rootfs=${SandboxTerminal.rootfsState(this@SandboxService)} " +
                "inFlight=${synchronized(stateLock) { inFlight }}"
    }

    /**
     * offload 最小集接回（开工清单 v2 第④项）：
     *   android-device  —— 机型/系统/电量，沙箱进程内直接答，不跨进程
     *   android-open    —— 打开 App，必须回主进程；走 IHostBridge.callAsync（异步 + 超时），
     *                      主进程侧经 Guard 判定并留审计，不在 binder 线程阻塞。
     * 其余 minis handler（contacts/player/weather…）按需再加。
     */
    private fun registerHostHandlers() {
        NativeOffloadServer.register("android-device") { _ ->
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
            if (app.isEmpty()) NativeOffloadResult(1, "usage: android-open <app>\n")
            else openViaHost(app)
        }
        Dbg.i(TAG, "offload handlers 已注册: ${NativeOffloadServer.registeredHandlers}")
    }

    /**
     * 在 offload worker 线程上（:sandbox 进程，非 binder 线程）等待宿主异步结果，带超时。
     * 超时 = 结果未知：如实返回，不重试（避免重复打开/重复副作用）。
     */
    private fun openViaHost(app: String): NativeOffloadResult {
        val h = host ?: return NativeOffloadResult(1, "android-open: 宿主通道不可用（主进程未连接），未执行\n")
        val latch = CountDownLatch(1)
        val box = AtomicReference<Bundle?>(null)
        val cb = object : IHostCallback.Stub() {
            override fun onHostResult(data: Bundle?) {
                box.set(data ?: Bundle())
                latch.countDown()
            }
        }
        try {
            h.callAsync("open:$app", cb)
        } catch (e: Exception) {
            return NativeOffloadResult(1, "android-open: 宿主调用失败（${e.javaClass.simpleName}），未执行\n")
        }
        val done = try {
            latch.await(HOST_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!done) {
            return NativeOffloadResult(
                EXIT_UNKNOWN,
                "android-open: 结果未知（宿主 ${HOST_CALL_TIMEOUT_MS / 1000} 秒内未回应），未重试\n",
            )
        }
        val b = box.get() ?: Bundle()
        return when {
            b.getBoolean("ok") -> NativeOffloadResult(0, "已打开 $app\n")
            b.getBoolean("unknown") -> NativeOffloadResult(
                EXIT_UNKNOWN,
                "android-open: ${b.getString("err") ?: "已发出启动请求，但未能确认是否打开"}（结果未知）\n",
            )
            else -> NativeOffloadResult(1, "android-open: ${b.getString("err") ?: "打开失败"}\n")
        }
    }
}
