package com.pilot.agent.agent

import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream

/**
 * P2：响应体读取上限。服务端异常或恶意大响应直接 string() 进堆会造成 OOM。
 */
object HttpBodies {
    const val DEFAULT_LIMIT = 4L * 1024 * 1024

    /**
     * 读取至多 [limit] 字节；超限截断并返回已读部分（调用方解析会自然失败）。
     *
     * ★ 必须走 byteStream()，不能用 okio 的 source().buffer()——本项目锁的 okio 版本上
     *   后者拿不到字节（真机实测：音乐 API HTTP 200 但 body 读成空串，搜索全空）。
     */
    fun readCapped(body: ResponseBody?, limit: Long = DEFAULT_LIMIT): String {
        if (body == null) return ""
        return runCatching {
            body.byteStream().use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0L
                while (total < limit) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                out.toString(Charsets.UTF_8.name())
            }
        }.getOrElse { "" }
    }
}
