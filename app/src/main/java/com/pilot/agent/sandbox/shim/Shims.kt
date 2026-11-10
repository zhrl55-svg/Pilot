package com.pilot.agent.sandbox.shim

import com.pilot.agent.Dbg
import java.io.File

/** minis 的 AppLogger 桩：内核代码里 13 处调用，全部转发到 Pilot 的文件日志。 */
object AppLogger {
    fun info(tag: String, msg: String) = Dbg.i(tag, msg)
    fun warning(tag: String, msg: String) = Dbg.w(tag, msg)
    fun error(tag: String, msg: String, tr: Throwable? = null) = Dbg.e(tag, msg, tr)
}

/** minis 诊断线程名桩（原实现就在 ResourceWatermark.kt 里，三行）。 */
object ThreadNames {
    const val PREFIX = "plt-"
    fun of(component: String): String = PREFIX + component
}

/**
 * minis 的 BuildConfig.DEV_TOOLS 桩。
 * 内核里用它开关 offload 调试与压测上限——Pilot 全部走生产档。
 */
const val DEV_TOOLS = false

/** 镜像测速（minis 首启选 npm 镜像），Pilot 暂不需要：空实现。 */
object MirrorSpeedTestViewModel {
    fun applyAllActiveMirrors(context: android.content.Context) = Unit
    fun autoDetectOnceIfNeeded(context: android.content.Context) = Unit
}

/** 只要 directorySize 一个函数，从 minis SessionStorage 摘出来。 */
object SessionStorage {
    fun directorySize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var total = 0L
        runCatching {
            dir.walkBottomUp().forEach { f ->
                if (f.isFile) total += f.length()
            }
        }
        return total
    }
}
