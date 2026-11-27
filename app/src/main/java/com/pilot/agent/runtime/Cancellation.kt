package com.pilot.agent.runtime

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * 协程取消相关的公共工具。
 *
 * ★ 规则：suspend 路径上的 `catch (t: Throwable)` / `runCatching {}` 都会把
 *   [CancellationException] 当成普通异常吞掉——用户点了「停止」，任务却在下一行
 *   继续点屏幕、继续发云请求。所有这类 catch 都要先调 [rethrowIfCancellation]，
 *   或者直接改用 [catchingNonCancel]。
 *
 * 注意 `TimeoutCancellationException` 也是 CancellationException 的子类：
 * 在 `withTimeout {}` 里面重新抛出它是正确行为（交给 withTimeout 处理）。
 */
fun rethrowIfCancellation(t: Throwable) {
    if (t is CancellationException) throw t
}

/**
 * 与 [runCatching] 相同，但不吞协程取消信号。
 *
 * inline：block 里可以直接调用 suspend 函数（调用方本身是 suspend 即可）。
 */
inline fun <T> catchingNonCancel(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (t: Throwable) {
    rethrowIfCancellation(t)
    Result.failure(t)
}

/**
 * OkHttp 的可取消挂起调用。
 *
 * `Call.execute()` 是阻塞调用，协程取消管不到它（最长等满 callTimeout）。
 * 这里改为 enqueue + suspendCancellableCoroutine：协程被取消时立刻 `Call.cancel()`，
 * 底层 socket 被关闭，调用方马上拿到 CancellationException。
 *
 * 调用方负责关闭返回的 [Response]（建议 `.use {}`）。
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        try {
            cancel()
        } catch (_: Throwable) {
            // cancel() 本身不应抛异常；即便抛了也不影响取消语义
        }
    }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) {
                    cont.resume(response)
                } else {
                    // 已被取消：没人会消费这个响应，立刻释放连接
                    response.close()
                }
            }
        },
    )
}
