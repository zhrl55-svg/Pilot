package com.pilot.navigation

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * navigation 模块的文件日志。
 *
 * ## 为什么不用 android.util.Log 就完事
 * **EMUI / HarmonyOS 会丢弃第三方应用的 logcat 输出**——`Log.i` 在
 * `adb logcat` 里完全看不到（P40 Pro / HarmonyOS 4.0 实测）。
 * 所以关键路径必须写文件。app 模块有 `Dbg` 做这件事，但 navigation 是
 * **独立模块**，不能依赖 app 的代码（模块要能单独复用），所以自己实现一份。
 *
 * 读法：
 *     adb shell "run-as com.pilot.agent.debug tail -50 files/nav.log"
 */
object NLog {

    private const val TAG = "PilotNav"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val MAX_MSG = 2000

    @Volatile
    var enabled = true

    private var logFile: File? = null

    /** 记日志目录（服务 onCreate 时调）。 */
    fun attach(filesDir: File) {
        runCatching {
            val dir = File(filesDir, "nav")
            dir.mkdirs()
            logFile = File(dir, "nav.log")
        }
    }

    fun i(tag: String, msg: String) = write('I', tag, msg)

    fun w(tag: String, msg: String) = write('W', tag, msg)

    fun e(tag: String, msg: String, tr: Throwable? = null) =
        write('E', tag, if (tr == null) msg else "$msg\n${Log.getStackTraceString(tr)}")

    private fun write(level: Char, tag: String, raw: String) {
        val msg = if (raw.length > MAX_MSG) raw.take(MAX_MSG) + "…" else raw
        when (level) {
            'E' -> Log.e(tag, msg)
            'W' -> Log.w(tag, msg)
            else -> Log.i(tag, msg)
        }
        if (!enabled) return
        runCatching {
            val f = logFile ?: return@runCatching
            if (f.length() > MAX_BYTES) {
                // 太大了就截断留尾巴
                val all = f.readBytes()
                val keep = all.copyOfRange(maxOf(0, all.size - (MAX_BYTES / 2).toInt()), all.size)
                f.writeBytes(keep)
            }
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date())
            f.appendText("$ts $level/$tag: $msg\n")
        }
    }
}
