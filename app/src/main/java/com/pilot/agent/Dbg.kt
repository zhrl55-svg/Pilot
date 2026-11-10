package com.pilot.agent

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志。
 *
 * ## 为什么需要这一层
 * EMUI / HarmonyOS、vivo 等国产 ROM 会**丢弃第三方应用的 logcat 输出** ——
 * `Log.i` / `Log.w` 在 `adb logcat` 里完全看不到，按 pid 抓也抓不到（P40 Pro /
 * HarmonyOS 4.0 实测）。排查端侧模型加载这种问题时没有日志等于瞎。
 *
 * 所以关键路径同时写一份到 `files/pilot.log`。debug 包可以直接读：
 *
 *     adb shell "run-as com.pilot.agent.debug cat files/pilot.log"
 *     adb shell "run-as com.pilot.agent.debug tail -50 files/pilot.log"
 *
 * 模拟器上两处都能看，不影响开发。
 */
object Dbg {

    private const val TAG = "Pilot"
    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val MAX_MSG = 2000

    @Volatile
    var enabled = true

    private val file: File?
        get() = runCatching { File(PilotApp.instance.filesDir, "pilot.log") }.getOrNull()

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
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.delete()
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("$ts $level/$tag: $msg\n")
        }
    }

    fun clear() {
        runCatching { file?.delete() }
    }

    fun tail(lines: Int = 200): String = runCatching {
        val f = file
        if (f == null || !f.exists()) "（无日志）"
        else f.readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("（读日志失败）")
}
