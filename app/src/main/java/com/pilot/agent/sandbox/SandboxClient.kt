package com.pilot.agent.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import com.pilot.agent.Dbg
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 主进程侧的 :sandbox 客户端。
 *
 * 生命周期策略：**不主动解绑**——沙箱热壳值钱（重绑进程要重新 boot rootfs）。
 * 服务死了（guest 崩了 native）下次调用会重连。
 *
 * ## 结果三态（审核 func X-01）
 * - [RemoteOutcome.Done]       远端执行完毕，拿到真实结果；
 * - [RemoteOutcome.NotStarted] 命令**没有**提交到远端（bind 失败 / startCommand 抛错 / 沙箱正在回收）；
 * - [RemoteOutcome.Lost]       命令**已提交**，但超时或沙箱进程中断 → 结果未知。
 * 任何情况都**不会**在主进程重跑命令；Lost 时会尽力中止远端 job。
 */
object SandboxClient {

    private const val TAG = "PilotSandbox"
    private const val BIND_TIMEOUT_MS = 4_000L

    /** 远端自身会按 timeoutMs 杀命令；客户端在此基础上多等的缓冲。 */
    private const val RESULT_GRACE_MS = 15_000L

    /** 沙箱正在空闲回收时被拒，等旧进程退出后重试一次（命令未启动，重试安全）。 */
    private const val RECYCLE_RETRY_DELAY_MS = 1_500L

    sealed class RemoteOutcome {
        data class Done(val result: SandboxTerminal.Result) : RemoteOutcome()
        data class NotStarted(val reason: String) : RemoteOutcome()
        data class Lost(val jobId: Long, val reason: String, val elapsedMs: Long) : RemoteOutcome()
    }

    @Volatile private var service: ISandboxService? = null
    @Volatile private var binding = false
    @Volatile private var consecutiveFailures = 0

    /** Application context（bind/unbind 必须用它，Receiver 的 context 不行）。 */
    @Volatile private var appContext: Context? = null

    private data class CompletedResult(
        val result: SandboxTerminal.Result,
        val completedAt: Long,
    )

    /**
     * `startCommand()` 是异步 AIDL 调用：服务可能在它返回 jobId 之前就已经
     * 执行完并回调。旧实现只在拿到 jobId 后才登记 continuation，快速失败的
     * 命令（例如 exit 127）会丢掉回调，界面一直停在“执行中”。
     *
     * pending 和 completed 必须在同一把锁下迁移：回调先到时暂存结果，等待方
     * 登记时立即取走；等待方先登记时，回调直接唤醒。
     */
    private val resultLock = Any()
    private val pending = mutableMapOf<Long, CancellableContinuation<RemoteOutcome>>()
    private val completed = mutableMapOf<Long, CompletedResult>()

    private val callback = object : ResultCallback.Stub() {
        override fun onResult(jobId: Long, data: Bundle?) {
            val out = data?.getString("out").orEmpty()
            val exit = data?.getInt("exit") ?: -1
            val ms = data?.getLong("ms") ?: 0L
            deliver(jobId, SandboxTerminal.Result(out, exit, ms))
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = ISandboxService.Stub.asInterface(binder)
            service = svc
            binding = false
            runCatching {
                svc.registerCallback(callback)
                svc.attachHost(HostBridge.impl)
            }.onFailure { Dbg.w(TAG, "注册回调/宿主通道失败：${it.javaClass.simpleName}") }
            Dbg.i(TAG, "已连接 :sandbox (status=${runCatching { svc.status() }.getOrNull()})")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            // 服务进程死了：所有在途 job 的结果未知（可能已执行一部分）
            failPending("沙箱进程中断")
            Dbg.w(TAG, ":sandbox 进程断开（guest 崩溃或系统回收）")
        }

        override fun onBindingDied(name: ComponentName?) {
            // 绑定永久失效：必须解绑，下次调用重新 bind（否则一直等不到 onServiceConnected）
            service = null
            binding = false
            appContext?.let { ctx -> runCatching { ctx.unbindService(this) } }
            failPending("沙箱绑定失效")
            Dbg.w(TAG, ":sandbox 绑定失效，已解绑，下次调用重连")
        }
    }

    /**
     * 主进程主动断开并**不重连**。
     *
     * 沙箱空闲回收时用它：沙箱进程自杀 → 系统会通知主进程 binder 断开 →
     * 如果主进程还保持绑定，系统会把服务**再拉起来**（BIND_AUTO_CREATE 的语义），
     * 回收就白做了。所以回收前必须先 unbindService。
     *
     * 解绑后下次真正有命令时 [ensureBound] 会重新 bind（那时才拉进程）。
     */
    fun disconnectForIdle() {
        if (service == null && !binding) return
        val ctx = appContext
        if (ctx != null) runCatching { ctx.unbindService(connection) }
        service = null
        binding = false
        failPending("沙箱已空闲回收")
        Dbg.i(TAG, "已解除 :sandbox 绑定（空闲回收）")
    }

    /**
     * 在 :sandbox 里执行命令。**永不**在主进程执行、**永不**重跑已提交的命令。
     * 调用方协程被取消时会请求远端中止该 job 再抛出取消。
     */
    suspend fun runRemote(context: Context, command: String, timeoutMs: Long): RemoteOutcome {
        val first = runRemoteOnce(context, command, timeoutMs)
        if (first is RemoteOutcome.NotStarted && first.reason == REASON_RECYCLING) {
            // 命令未启动：等旧进程退出后重连重试一次，安全无副作用
            delay(RECYCLE_RETRY_DELAY_MS)
            return runRemoteOnce(context, command, timeoutMs)
        }
        return first
    }

    private suspend fun runRemoteOnce(context: Context, command: String, timeoutMs: Long): RemoteOutcome {
        // ensureBound 也要在 try 里：bindService 可能抛 SecurityException /
        // IllegalArgumentException（组件名写错、exported 不匹配）。
        val svc = try {
            ensureBound(context)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            consecutiveFailures++
            service = null
            binding = false
            Dbg.w(TAG, "bind 异常(${t.javaClass.simpleName})，命令未执行: ${t.message}")
            null
        } ?: return RemoteOutcome.NotStarted("无法连接沙箱进程")

        val jobId = try {
            svc.startCommand(SandboxTerminal.SESSION, command, timeoutMs)
        } catch (t: Throwable) {
            // 同步 binder 调用失败（DeadObject 等）：服务端没有返回 jobId，按未启动处理
            consecutiveFailures++
            service = null
            Dbg.w(TAG, "startCommand 异常(${t.javaClass.simpleName})，命令未提交")
            return RemoteOutcome.NotStarted("沙箱连接异常")
        }
        if (jobId == SandboxService.JOB_REJECTED_RECYCLING) {
            service = null
            binding = false
            return RemoteOutcome.NotStarted(REASON_RECYCLING)
        }
        if (jobId <= 0L) return RemoteOutcome.NotStarted("沙箱拒绝了命令")

        val startedAt = System.currentTimeMillis()
        try {
            val outcome = withTimeoutOrNull(timeoutMs + RESULT_GRACE_MS) {
                awaitResult(jobId)
            }
            val elapsed = System.currentTimeMillis() - startedAt
            if (outcome == null) {
                Dbg.w(TAG, "远端命令超时（${timeoutMs}ms+${RESULT_GRACE_MS}ms 缓冲），jobId=$jobId，结果未知，不重跑")
                stopRemoteJob(svc, jobId)
                return RemoteOutcome.Lost(
                    jobId,
                    "沙箱超过 ${(timeoutMs + RESULT_GRACE_MS) / 1000} 秒没有返回结果，已尝试中止",
                    elapsed,
                )
            }
            consecutiveFailures = 0
            return if (outcome is RemoteOutcome.Lost) outcome.copy(elapsedMs = elapsed) else outcome
        } catch (e: CancellationException) {
            // 调用方取消（用户点了停止 / 任务被抢占）：远端命令也要停，否则后台还在跑
            stopRemoteJob(svc, jobId)
            throw e
        }
    }

    /** 尽力中止远端 job（同步快调用，失败只记日志）。 */
    private fun stopRemoteJob(svc: ISandboxService, jobId: Long) {
        val stopped = runCatching { svc.stopCommand(jobId) }.getOrDefault(false)
        Dbg.i(TAG, "请求中止远端 job=$jobId -> $stopped")
    }

    private suspend fun awaitResult(jobId: Long): RemoteOutcome =
        suspendCancellableCoroutine { cont ->
            val early = synchronized(resultLock) {
                completed.remove(jobId)?.result.also { result ->
                    if (result == null) pending[jobId] = cont
                }
            }
            if (early != null) {
                cont.resume(RemoteOutcome.Done(early))
            } else {
                cont.invokeOnCancellation {
                    synchronized(resultLock) { pending.remove(jobId) }
                }
            }
        }

    private fun deliver(jobId: Long, result: SandboxTerminal.Result) {
        val cont = synchronized(resultLock) {
            val waiting = pending.remove(jobId)
            if (waiting == null) {
                completed[jobId] = CompletedResult(result, System.currentTimeMillis())
                pruneCompletedLocked()
            }
            waiting
        }
        if (cont != null) runCatching { cont.resume(RemoteOutcome.Done(result)) }
    }

    /** 进程中断/回收：在途 job 一律“结果未知”（已提交，可能执行了一部分）。 */
    private fun failPending(message: String) {
        val waiting = synchronized(resultLock) {
            val snapshot = pending.entries.map { it.key to it.value }
            pending.clear()
            completed.clear()
            snapshot
        }
        waiting.forEach { (jobId, cont) ->
            runCatching { cont.resume(RemoteOutcome.Lost(jobId, message, 0L)) }
        }
    }

    private fun pruneCompletedLocked() {
        val cutoff = System.currentTimeMillis() - COMPLETED_RESULT_TTL_MS
        completed.entries.removeAll { it.value.completedAt < cutoff }
        while (completed.size > COMPLETED_RESULT_LIMIT) {
            val oldest = completed.minByOrNull { it.value.completedAt } ?: break
            completed.remove(oldest.key)
        }
    }

    private suspend fun ensureBound(context: Context): ISandboxService? {
        service?.let { return it }
        val appCtx = context.applicationContext
        if (!binding) {
            binding = true
            val intent = Intent(SandboxService.ACTION_BIND)
                .setComponent(ComponentName(context.packageName, SandboxService::class.java.name))
            // ★ 必须用 **Application context** bind：BroadcastReceiver 的 context
            //   调 bindService 会抛 ReceiverCallNotAllowedException
            //   （"BroadcastReceiver components are not allowed to bind to services"）。
            //   自检通道是 receiver 触发的，用它的 context 必炸——踩过一轮。
            appContext = appCtx
            val ok = try {
                appCtx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (t: Throwable) {
                binding = false
                throw t
            }
            Dbg.i(TAG, "bindService :sandbox -> $ok")
            if (!ok) {
                binding = false
                consecutiveFailures++
                runCatching { appCtx.unbindService(connection) }
                Dbg.w(TAG, "bindService :sandbox 返回 false")
                return null
            }
        }
        // 等 onServiceConnected（最多 BIND_TIMEOUT_MS）
        val deadline = System.currentTimeMillis() + BIND_TIMEOUT_MS
        while (service == null && System.currentTimeMillis() < deadline) {
            delay(100)
        }
        val svc = service
        if (svc == null) {
            // 一直连不上：撤销这次绑定，避免 binding 标志卡死导致以后永远不再 bind
            consecutiveFailures++
            binding = false
            runCatching { appCtx.unbindService(connection) }
            Dbg.w(TAG, "ensureBound 超时（${BIND_TIMEOUT_MS}ms），连续失败 $consecutiveFailures 次")
        }
        return svc
    }

    /** 供设置页/自检展示：当前是否已连上 :sandbox（主进程不再有本地执行路径）。 */
    fun mode(): String = if (service != null) "remote(:sandbox)" else "未连接"

    private const val REASON_RECYCLING = "沙箱正在空闲回收"
    private const val COMPLETED_RESULT_LIMIT = 64
    private const val COMPLETED_RESULT_TTL_MS = 5 * 60_000L
}
