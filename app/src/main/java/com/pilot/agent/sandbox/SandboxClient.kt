package com.pilot.agent.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import com.pilot.agent.Dbg
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 主进程侧的 :sandbox 客户端。
 *
 * 生命周期策略：**不主动解绑**——沙箱热壳值钱（重绑进程要重新 boot rootfs）。
 * 服务死了（guest 崩了native）下次调用会重连，等结果超时按 null 返回，
 * 由 SandboxTerminal 降级进程内执行。
 */
object SandboxClient {

    private const val TAG = "PilotSandbox"
    private const val BIND_TIMEOUT_MS = 4_000L

    @Volatile private var service: ISandboxService? = null
    @Volatile private var binding = false
    private var deadCount = 0

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
    private val pending = mutableMapOf<Long, CancellableContinuation<SandboxTerminal.Result>>()
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
            }
            Dbg.i(TAG, "已连接 :sandbox (status=${runCatching { svc.status() }.getOrNull()})")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            // 服务进程死了：所有在途 job 立即失败（让上层降级）
            failPending("沙箱进程崩溃")
            Dbg.w(TAG, ":sandbox 进程断开（guest 崩溃或系统回收）")
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
        failPending("沙箱已回收")
        Dbg.i(TAG, "已解除 :sandbox 绑定（空闲回收）")
    }

    /** 返回 null = 远端不可用（上层降级进程内）；非 null = 远端结果（含失败）。 */
    suspend fun runRemote(context: Context, command: String, timeoutMs: Long): SandboxTerminal.Result? {
        // ensureBound 也要在 try 里：bindService 可能抛 SecurityException /
        // IllegalArgumentException（组件名写错、exported 不匹配），
        // 之前这些异常会一路冒到调用方线程，日志里什么都不留（排查过一轮）。
        val svc = try {
            ensureBound(context)
        } catch (t: Throwable) {
            deadCount++
            service = null
            Dbg.w(TAG, "bind 异常(${t.javaClass.simpleName})，降级进程内: ${t.message}")
            null
        } ?: return null
        if (deadCount >= 3) {
            SandboxTerminal.markRemoteBroken("连续 $deadCount 次连接失败")
            return null
        }
        return try {
            val jobId = svc.startCommand(SandboxTerminal.SESSION, command, timeoutMs)
            val result = withTimeoutOrNull(timeoutMs + 15_000) {
                awaitResult(jobId)
            }
            deadCount = 0
            if (result == null) {
                Dbg.w(TAG, "远端命令超时（${timeoutMs}ms+15s 缓冲），jobId=$jobId")
                null // 超时也降级：让进程内路径再试一次
            } else result
        } catch (t: Throwable) {
            deadCount++
            service = null
            Dbg.w(TAG, "远端调用异常(${t.javaClass.simpleName})，降级进程内: ${t.message}")
            null
        }
    }

    private suspend fun awaitResult(jobId: Long): SandboxTerminal.Result =
        suspendCancellableCoroutine { cont ->
            val early = synchronized(resultLock) {
                completed.remove(jobId)?.result.also { result ->
                    if (result == null) pending[jobId] = cont
                }
            }
            if (early != null) {
                cont.resume(early)
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
        if (cont != null) runCatching { cont.resume(result) }
    }

    private fun failPending(message: String) {
        val waiting = synchronized(resultLock) {
            val snapshot = pending.values.toList()
            pending.clear()
            completed.clear()
            snapshot
        }
        waiting.forEach { cont ->
            runCatching { cont.resume(SandboxTerminal.Result(message, -3, 0)) }
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
        if (!binding) {
            binding = true
            val intent = Intent(SandboxService.ACTION_BIND)
                .setComponent(ComponentName(context.packageName, SandboxService::class.java.name))
            // ★ 必须用 **Application context** bind：BroadcastReceiver 的 context
            //   调 bindService 会抛 ReceiverCallNotAllowedException
            //   （"BroadcastReceiver components are not allowed to bind to services"）。
            //   自检通道是 receiver 触发的，用它的 context 必炸——踩过一轮。
            val appCtx = context.applicationContext
            appContext = appCtx
            val ok = appCtx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            Dbg.i(TAG, "bindService :sandbox -> $ok")
            if (!ok) {
                binding = false
                deadCount++
                Dbg.w(TAG, "bindService :sandbox 返回 false")
                return null
            }
        }
        // 等 onServiceConnected（最多 BIND_TIMEOUT_MS）
        val deadline = System.currentTimeMillis() + BIND_TIMEOUT_MS
        while (service == null && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(100)
        }
        Dbg.i(TAG, "ensureBound 结果 service=${service != null}")
        return service
    }

    /** 供设置页/自检展示：当前走的远端还是本地。 */
    fun mode(): String = if (service != null) "remote(:sandbox)" else "local"

    private const val COMPLETED_RESULT_LIMIT = 64
    private const val COMPLETED_RESULT_TTL_MS = 5 * 60_000L
}
