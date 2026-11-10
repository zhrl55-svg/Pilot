package com.pilot.agent.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.pilot.agent.Dbg
import com.pilot.agent.perception.ScreenReader
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 执行层：所有对设备的写操作都从这里走。
 *
 * 两条路：
 * 1. 节点级操作（performAction）——首选，最快最稳，不依赖坐标。
 * 2. 手势注入（dispatchGesture）——节点点不动时兜底。
 *
 * 在自研 ROM 里还可以换掉底层的 [GestureExecutor]，直接走 INJECT_EVENTS 注入 MotionEvent，
 * 能覆盖 dispatchGesture 覆盖不到的场景（比如需要连续轨迹的拖拽、需要压力值的场景）。
 */
class DeviceActions(
    private val service: AccessibilityService,
    private val reader: ScreenReader,
) {

    /** 系统能力直调（音量、亮度、电话），不发 Intent 也不点屏幕 */
    val systemOps: SystemOps by lazy { SystemOps(service) }

    suspend fun tap(x: Int, y: Int): Boolean =
        gesture({ path -> path.moveTo(x.toFloat(), y.toFloat()) }, 60L)

    suspend fun longPress(x: Int, y: Int, holdMs: Long = 700): Boolean =
        gesture({ path -> path.moveTo(x.toFloat(), y.toFloat()) }, holdMs)

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean =
        gesture(
            { path ->
                path.moveTo(x1.toFloat(), y1.toFloat())
                path.lineTo(x2.toFloat(), y2.toFloat())
            },
            durationMs,
        )

    /** 点节点：先 performAction，失败退坐标点击。 */
    suspend fun tapNode(id: Int): Boolean {
        val node = reader.node(id) ?: return false
        val target = node.findClickableAncestor() ?: node
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val snap = reader.last()?.nodes?.firstOrNull { it.id == id } ?: return false
        return tap(snap.cx, snap.cy)
    }

    suspend fun longPressNode(id: Int): Boolean {
        val node = reader.node(id) ?: return false
        if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
        val snap = reader.last()?.nodes?.firstOrNull { it.id == id } ?: return false
        return longPress(snap.cx, snap.cy)
    }

    fun input(id: Int, text: String): Boolean {
        val node = reader.node(id) ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        // 某些自绘输入框不认 SET_TEXT，退化成「先清空再粘贴」由上层处理
        return false
    }

    fun scroll(id: Int, forward: Boolean = true): Boolean {
        val node = reader.node(id) ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return node.performAction(action)
    }

    fun key(name: String): Boolean = when (name.lowercase()) {
        "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        "home" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        "recents" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        "notifications" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        "lock" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        else -> false
    }

    fun openApp(pkgOrName: String): Boolean {
        val pkg = resolvePackage(pkgOrName) ?: return false
        val intent = service.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { service.startActivity(intent) }.isSuccess
    }

    /** 支持传包名、应用中文名、或常见别名（系统语言是英文时靠别名兜底）。 */
    fun resolvePackage(pkgOrName: String): String? {
        val pm = service.packageManager
        if (runCatching { pm.getPackageInfo(pkgOrName, 0) }.isSuccess) return pkgOrName

        val key = pkgOrName.trim().lowercase()
        ALIASES[key]?.firstOrNull { isInstalled(it) }?.let { return it }

        val apps = pm.getInstalledApplications(0)
        val labels = apps.map { it to pm.getApplicationLabel(it).toString() }
        val hit = labels.firstOrNull { it.second.equals(pkgOrName, ignoreCase = true) }?.first
            ?: labels.firstOrNull { it.second.contains(pkgOrName, ignoreCase = true) }?.first
        return hit?.packageName
    }

    fun isInstalled(pkg: String): Boolean =
        runCatching { service.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /**
     * 用 Intent 直达，不走点击。
     *
     * 先 `resolveActivity` 探一下有没有能接的 Activity——没有就直接返回 false 让上层回退点击，
     * 别发一个没人接的 Intent 然后误判成功（这个坑实测过：规则重复决策到死循环）。
     */
    fun openShortcut(actionOrUri: String): Boolean {
        val intent = if (actionOrUri.contains("://")) {
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(actionOrUri))
        } else {
            Intent(actionOrUri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startSafely(intent)
    }

    /** 启动指定组件（App 内入口，来自 [AppLaunchIndex]） */
    fun launchComponent(pkg: String, cls: String): Boolean {
        val intent = Intent().setComponent(android.content.ComponentName(pkg, cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startSafely(intent)) return true
        // 直达起不来（实测：华为系统组件要 ACCESS_INTERFACE 之类的应用签名权限，
        // 抛 SecurityException）→ 降级为打开该 App 主入口，让后续点击/规划接着干。
        val launch = service.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        Dbg.i(TAG, "组件直达失败，降级打开 $pkg 主入口")
        return startSafely(launch)
    }

    private fun startSafely(intent: Intent): Boolean {
        val pm = service.packageManager
        if (pm.resolveActivity(intent, 0) == null) {
            Dbg.w(TAG, "没有 Activity 能接这个 Intent: ${intent.action ?: intent.component}")
            return false
        }
        return runCatching { service.startActivity(intent) }
            .onFailure { Dbg.w(TAG, "启动失败 ${it.javaClass.simpleName}: ${it.message}") }
            .isSuccess
    }

    private suspend fun gesture(
        build: (Path) -> Unit,
        durationMs: Long,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().also(build)
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val description = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = runCatching {
            service.dispatchGesture(
                description,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
        }.getOrDefault(false)
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    companion object {
        private const val TAG = "Pilot"
        /**
         * 中文别名 → 候选包名。
         *
         * 解决的是真实问题：系统语言是英文时（模拟器、部分海外 ROM），Settings 的 launcher label
         * 就是 "Settings"，用户说"打开设置"按 label 永远匹配不到。别名表做兜底，
         * 每个词给多个候选包名，逐个检查是否安装。
         */
        private val ALIASES: Map<String, List<String>> = mapOf(
            "设置" to listOf("com.android.settings"),
            "settings" to listOf("com.android.settings"),
            "相机" to listOf("com.android.camera2", "com.android.camera", "com.google.android.GoogleCamera"),
            "camera" to listOf("com.android.camera2", "com.android.camera"),
            "相册" to listOf("com.google.android.apps.photos", "com.android.gallery3d"),
            "photos" to listOf("com.google.android.apps.photos", "com.android.gallery3d"),
            "时钟" to listOf("com.google.android.deskclock", "com.android.deskclock"),
            "clock" to listOf("com.google.android.deskclock", "com.android.deskclock"),
            "日历" to listOf("com.google.android.calendar", "com.android.calendar"),
            "calendar" to listOf("com.google.android.calendar", "com.android.calendar"),
            "计算器" to listOf("com.google.android.calculator", "com.android.calculator2"),
            "calculator" to listOf("com.google.android.calculator", "com.android.calculator2"),
            "浏览器" to listOf("com.android.chrome", "com.android.browser"),
            "browser" to listOf("com.android.chrome", "com.android.browser"),
            "电话" to listOf("com.google.android.dialer", "com.android.dialer"),
            "拨号" to listOf("com.google.android.dialer", "com.android.dialer"),
            "联系人" to listOf("com.google.android.contacts", "com.android.contacts"),
            "信息" to listOf("com.google.android.apps.messaging", "com.android.messaging"),
            "短信" to listOf("com.google.android.apps.messaging", "com.android.messaging"),
            "文件" to listOf("com.google.android.documentsui", "com.android.documentsui"),
            "微信" to listOf("com.tencent.mm"),
            "支付宝" to listOf("com.eg.android.AlipayGphone"),
            "淘宝" to listOf("com.taobao.taobao"),
        )
    }
}

/** 节点本身常常不可点击，真正的点击区在父容器上，往上找三层够了。 */
internal fun AccessibilityNodeInfo.findClickableAncestor(depth: Int = 3): AccessibilityNodeInfo? {
    var cur: AccessibilityNodeInfo? = this
    var d = 0
    while (cur != null && d < depth) {
        if (cur.isClickable) return cur
        cur = cur.parent
        d++
    }
    return null
}
