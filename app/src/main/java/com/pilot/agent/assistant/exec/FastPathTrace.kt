package com.pilot.agent.assistant.exec

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 快路径的诊断轨迹。
 *
 * 端侧接管时只往聊天里写一条回复，不留任何痕迹；没有这个文件时无法区分
 * 「开关没开 / 规则没命中 / 抽参失败 / 安全不过 / 工具执行失败」。
 *
 * 读法：
 * ```
 * adb shell "run-as com.pilot.agent cat files/assistant_fastpath.log"
 * ```
 */
object FastPathTrace {

    private const val FILE = "assistant_fastpath.log"
    private const val MAX_BYTES = 512L * 1024L

    @Synchronized
    fun line(context: Context, message: String) {
        runCatching {
            val file = File(context.applicationContext.filesDir, FILE)
            if (file.exists() && file.length() > MAX_BYTES) file.delete()
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            file.appendText("[$stamp] $message\n")
        }
    }

    /** 入口：把开关状态和来源写清楚，否则「没反应」无从查起。 */
    fun entered(context: Context, source: String, enabled: Boolean) {
        line(context, "enter enabled=$enabled source=$source")
    }

    fun declined(context: Context, reason: String, text: String) {
        line(context, "no-route: reason=$reason 「${text.take(40)}」")
    }

    fun handled(
        context: Context,
        capability: String,
        action: String,
        status: String,
        length: Int,
    ) {
        line(context, "handled: capability=$capability/$action status=$status len=$length")
    }
}
