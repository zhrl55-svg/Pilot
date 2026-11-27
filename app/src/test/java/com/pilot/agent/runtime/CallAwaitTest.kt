package com.pilot.agent.runtime

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可取消 HTTP（审核 P0-4 / m-20261127-102）。
 *
 * `Call.execute()` 对协程取消完全无感：点停止后要等 callTimeout 才返回。
 * [await] 改成 enqueue + suspendCancellableCoroutine，取消时立刻 cancel() 调用。
 *
 * 这里用本机 ServerSocket 模拟「服务器迟迟不响应」：请求挂起后取消协程，
 * 断言 1 秒内返回（真机上等待的是 10~16s 的 callTimeout）。
 */
class CallAwaitTest {

    @Test
    fun `cancel returns promptly instead of waiting for the slow server`() = runBlocking {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val serverThread = Thread({
            try {
                server.accept().use { socket ->
                    // 收完请求头就挂住，绝不在测试窗口内回响应。
                    val input = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    Thread.sleep(5_000)
                }
            } catch (_: Throwable) {
                // 测试结束关闭监听/连接属预期
            }
        }, "fake-slow-server").apply { isDaemon = true }
        serverThread.start()
        try {
            val client = OkHttpClient.Builder()
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
            val call = client.newCall(
                Request.Builder().url("http://127.0.0.1:${server.localPort}/slow").build(),
            )

            var sawCancellation = false
            val job = launch(Dispatchers.IO) {
                try {
                    call.await().use { it.body?.string() }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    sawCancellation = true
                    throw e
                }
            }
            Thread.sleep(300) // 让请求真的走到「等响应」这一步
            val start = System.currentTimeMillis()
            job.cancel()
            job.join()
            val elapsed = System.currentTimeMillis() - start

            assertTrue("取消应在 1 秒内返回，实际 ${elapsed}ms", elapsed < 1_000)
            assertTrue("协程应处于已取消状态", job.isCancelled)
            assertTrue("取消时必须抛 CancellationException，而不是被当成网络错误", sawCancellation)
        } finally {
            runCatching { server.close() }
        }
    }
}
