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
import kotlinx.coroutines.delay
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

    suspend fun openApp(pkgOrName: String): Boolean {
        val pkg = resolvePackage(pkgOrName) ?: return false
        val intent = service.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startSafely(intent, pkg)) return true
        // ★ 后台启动被拦（HarmonyOS HwASInterceptor）→ 用**无障碍手势**把 App 拉到前台。
        //   语音场景下 agent 几乎总在后台，没有这条兜底就什么都做不了。
        //   手法：从屏幕底部往上滑 → 系统 App 抽屉/最近任务 → 点目标 App 图标。
        return launchViaLauncherGesture(pkg, pkgOrName)
    }

    /**
     * 无障碍手势兜底启动 App。
     *
     * 原理：华为桌面（UniHomeLauncher）把 App 摆在一屏屏的宫格里，
     * 系统 App（设置/蓝牙/电池）通常不在抽屉里而在某一屏的图标上。
     * 所以做法是：**左右翻桌面屏 → 在节点树里找 content-desc 匹配的图标 → 点它**。
     *
     * 找不到就返回 false，让上层换策略（别硬点，点了是乱操作）。
     */
    private suspend fun launchViaLauncherGesture(pkg: String, label: String): Boolean {
        Dbg.i(TAG, "后台启动被拦，改用桌面图标手势启动 $label")
        val w = service.resources.displayMetrics.widthPixels
        val h = service.resources.displayMetrics.heightPixels

        // 匹配词：用户说的名字 + 应用真实 label（"设置"→Settings）
        val appLabel = runCatching {
            val info = service.packageManager.getPackageInfo(pkg, 0)
            service.packageManager.getApplicationLabel(info.applicationInfo ?: return@runCatching "").toString()
        }.getOrDefault(label)
        val needles = listOf(label, appLabel).filter { it.isNotBlank() }.distinct()

        // 先回桌面
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(500)

        // 最多翻 MAX_SCREENS 屏（P40 实测 6 屏）
        for (screen in 0 until MAX_DESKTOP_SCREENS) {
            // 1) 当前屏找图标
            val node = service.rootInActiveWindow?.let { root ->
                findByTextDeep(root) { t -> needles.any { n -> t.contains(n, ignoreCase = true) } }
            }
            if (node != null) {
                val ok = runCatching {
                    node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                Dbg.i(TAG, "桌面第 $screen 屏点击「$label」-> $ok")
                if (ok && awaitForeground(pkg, 2000L)) return true
            }
            // 2) 往下一屏滑（从右往左）
            val swiped = gesture({ p ->
                p.moveTo((w * 0.85f).toFloat(), (h * 0.5f).toFloat())
                p.lineTo((w * 0.12f).toFloat(), (h * 0.5f).toFloat())
            }, 300)
            if (!swiped) break
            delay(600)
        }
        Dbg.w(TAG, "翻完 ${MAX_DESKTOP_SCREENS} 屏都没找到「${needles.joinToString("/")}」")
        return false
    }

    /** 深度优先找文本匹配的节点（桌面图标在多层 RecyclerView 容器里）。 */
    private fun findByTextDeep(
        root: android.view.accessibility.AccessibilityNodeInfo?,
        match: (String) -> Boolean,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (root == null) return null
        val t = root.text?.toString().orEmpty()
        val d = root.contentDescription?.toString().orEmpty()
        if (t.isNotEmpty() && match(t)) return root
        if (d.isNotEmpty() && match(d)) return root
        for (i in 0 until root.childCount) {
            findByTextDeep(root.getChild(i), match)?.let { return it }
        }
        return null
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
    suspend fun openShortcut(actionOrUri: String): Boolean {
        val intent = if (actionOrUri.contains("://")) {
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(actionOrUri))
        } else {
            Intent(actionOrUri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startSafely(intent)) return true
        // 后台被拦：Intent 里带包名的话走抽屉手势；查不到就放弃
        val pkg = intent.`package` ?: intent.component?.packageName
        val label = pkg?.let { p ->
            runCatching {
                val info = service.packageManager.getPackageInfo(p, 0)
                service.packageManager.getApplicationLabel(info.applicationInfo ?: return@runCatching "").toString()
            }.getOrNull()
        }
        return if (pkg != null && label != null) launchViaLauncherGesture(pkg, label) else false
    }

    /** 启动指定组件（App 内入口，来自 [AppLaunchIndex]） */
    suspend fun launchComponent(pkg: String, cls: String): Boolean {
        val intent = Intent().setComponent(android.content.ComponentName(pkg, cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startSafely(intent, pkg)) return true
        // 直达起不来（实测：华为系统组件要 ACCESS_INTERFACE 之类的应用签名权限，
        // 抛 SecurityException）→ 降级为打开该 App 主入口，让后续点击/规划接着干。
        val launch = service.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        Dbg.i(TAG, "组件直达失败，降级打开 $pkg 主入口")
        return startSafely(launch, pkg)
    }

    /**
     * startActivity + **焦点窗口回查**。
     *
     * ★ HarmonyOS / EMUI 的后台 Activity 拦截是**静默失败**：startActivity 不抛异常，
     * logcat 里只有 `activity start fail: Background activity start`（HANDOFF 坑 #18）。
     * 无头路径里 `runCatching{}.isSuccess` 全是假成功。所以起完回查前台包名：
     * 换过去了才算成功。expectPkg 为 null 时用 resolveActivity 的命中包推。
     */
    private suspend fun startSafely(intent: Intent, expectPkg: String? = null): Boolean {
        val pm = service.packageManager
        val hit = pm.resolveActivity(intent, 0)
        if (hit == null) {
            Dbg.w(TAG, "没有 Activity 能接这个 Intent: ${intent.action ?: intent.component}")
            return false
        }
        val target = expectPkg ?: hit.activityInfo.packageName
        val before = foregroundPackage()
        val started = runCatching { service.startActivity(intent) }
            .onFailure { Dbg.w(TAG, "启动失败 ${it.javaClass.simpleName}: ${it.message}") }
            .isSuccess
        if (!started) return false
        // 目标本来就在前台（同包内换页），回查不出差别，放行
        if (before == target) return true
        if (awaitForeground(target, 1200L)) return true

        // ★ 后台启动被拦（HarmonyOS `Abort background activity starts`）：
        //   实测四条路（11-10 P40 Pro 逐一验证）：
        //   ① 无障碍服务直接起 → 拦（BOUND_FOREGROUND_SERVICE）
        //   ② 透明代理 Activity → 代理自己也起不来（自己就是后台）
        //   ③ 前台服务提升 → 进程状态对了，但 HarmonyOS 还要
        //      allowBackgroundActivityStart（privileged 权限，自研 ROM 里给）
        //   ④ AlarmManager 系统代发 → 排到 +5s 且代理 Activity 仍被 Abort
        //   ⑤ **桌面图标无障碍点击** → ✅ 唯一在用户机上真能用的路
        //   所以真机（debug 包）走 ⑤；自研 ROM 有 privileged 权限时走 ③ 最快。
        Dbg.w(TAG, "后台 Activity 被拦，改走桌面图标点击：$target")
        // 桌面图标上显示的是**应用 label**（设置 / 蓝牙 / 手机管家），
        // 不是包名（com.android.settings）——按 label 找才点得到。
        val displayLabel = runCatching {
            val info = service.packageManager.getPackageInfo(target, 0)
            service.packageManager.getApplicationLabel(info.applicationInfo ?: return@runCatching "").toString()
        }.getOrDefault(target)
        val launched = launchViaLauncherGesture(target, displayLabel)
        if (!launched) {
            Dbg.w(TAG, "桌面图标也没找到")
            return false
        }
        return awaitForeground(target, 2500L)
    }

    /** 当前前台窗口的包名（无障碍 API，零权限）。 */
    fun foregroundPackage(): String? =
        runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()

    /** 轮询等目标包成为前台。 */
    private suspend fun awaitForeground(pkg: String, timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            delay(150)
            waited += 150
            if (foregroundPackage() == pkg) return true
        }
        return false
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

        /** 桌面最多翻几屏找图标（P40 实测 6 屏） */
        private const val MAX_DESKTOP_SCREENS = 8
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
