package com.pilot.agent.display

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.util.DisplayMetrics
import com.pilot.agent.Dbg

/**
 * 虚拟屏执行宿主（「页面尽量不占主屏」第二步）。
 *
 * 目标：把 Agent 要操作的 App 启动到一块**独立虚拟屏**上，主屏继续显示用户当前界面。
 * 需要在 rom/README.md 描述的「平台签名 + priv-app」前提下，由系统授予：
 * - `android.permission.ADD_TRUSTED_DISPLAY`（把别的 App 的 Activity 拉上非默认显示屏，signature|privileged）
 * - 以及（预览/回读画面需要的）`android.permission.CAPTURE_VIDEO_OUTPUT`
 *
 * 普通 debug 签名手机拿不到这些权限，[isAvailable] 必须返回不可用，调用方安全退回
 * 现有「做完切回」逻辑——这只影响能力，不改变任何安全护栏。
 */
object VirtualDisplayHost {

    private const val TAG = "PilotVirtualDisplay"

    /** 需要系统签名/priv-app 才能授予的权限。 */
    private val TRUSTED_PERMS = listOf(
        "android.permission.ADD_TRUSTED_DISPLAY",
        "android.permission.CAPTURE_VIDEO_OUTPUT",
    )

    data class Capability(val available: Boolean, val reason: String)

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var imageReader: ImageReader? = null

    /** 当前虚拟屏的 displayId；未启动为 null。 */
    @Volatile
    var activeDisplayId: Int? = null
        private set

    /**
     * 能力探测（无副作用）：要求 Android 11+ 且至少持有 ADD_TRUSTED_DISPLAY（把 App 拉上虚拟屏的关键权限）。
     * 普通安装一律不可用。
     */
    fun isAvailable(context: Context): Capability {
        val granted = TRUSTED_PERMS.filter {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }.toSet()
        return decideAvailability(Build.VERSION.SDK_INT, granted)
    }

    /** 纯决策（便于单测）：缺 ADD_TRUSTED_DISPLAY 一律不可用。 */
    internal fun decideAvailability(sdk: Int, granted: Set<String>): Capability {
        if (sdk < Build.VERSION_CODES.R) {
            return Capability(false, "系统版本过低（虚拟屏执行需 Android 11+）")
        }
        return if ("android.permission.ADD_TRUSTED_DISPLAY" in granted) {
            Capability(true, "可用（${granted.joinToString()}）")
        } else {
            Capability(
                false,
                "缺少系统签名权限 ADD_TRUSTED_DISPLAY（需平台签名/priv-app），普通安装不可用",
            )
        }
    }

    /**
     * 启动虚拟屏。[width]/[height]/[dpi] 为 0 时与主屏一致。
     * @return 是否成功；成功后 [activeDisplayId] 有效。
     */
    fun start(
        context: Context,
        name: String = "pilot-virtual",
        width: Int = 0,
        height: Int = 0,
        dpi: Int = 0,
    ): Boolean {
        if (!isAvailable(context).available) return false
        if (virtualDisplay != null) return true
        val app = context.applicationContext ?: context
        return try {
            val metrics = DisplayMetrics()
            val dm = app.getSystemService(DisplayManager::class.java)
                ?: return false
            @Suppress("DEPRECATION")
            val mainDisplay = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            mainDisplay?.getRealMetrics(metrics)
            val w = width.takeIf { it > 0 } ?: metrics.widthPixels
            val h = height.takeIf { it > 0 } ?: metrics.heightPixels
            val d = dpi.takeIf { it > 0 } ?: metrics.densityDpi

            val reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
            // 有 ADD_TRUSTED_DISPLAY 时用 PUBLIC，允许把别的 App 拉上来；否则只能用 OWN_CONTENT_ONLY。
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val vd = dm.createVirtualDisplay(name, w, h, d, reader.surface, flags)
            if (vd == null) {
                reader.close()
                Dbg.w(TAG, "createVirtualDisplay 返回 null")
                return false
            }
            imageReader = reader
            virtualDisplay = vd
            activeDisplayId = vd.display?.displayId
            Dbg.i(TAG, "虚拟屏已启动 displayId=$activeDisplayId ${w}x$h@$d")
            true
        } catch (t: Throwable) {
            Dbg.w(TAG, "虚拟屏启动失败：${t.javaClass.simpleName} ${t.message}")
            stop()
            false
        }
    }

    fun stop() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null
        activeDisplayId = null
    }
}
