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
import kotlinx.coroutines.withTimeoutOrNull

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

    /** 上次成功呼出搜索框的滑动手势（高度比例 from→to）；null 表示还没成功过 */
    @Volatile
    private var searchGesture: Pair<Float, Float>? = null

    /** 呼出搜索前是否需要先翻一屏（华为首页「智慧助手」那屏不行） */
    @Volatile
    private var needPageTurn = false

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
        val requested = pkgOrName.trim()
        if (requested == "终端" || requested.equals("Pilot 终端", ignoreCase = true) ||
            requested.equals("Pilot Terminal", ignoreCase = true)
        ) {
            val intent = Intent(service, com.pilot.agent.ui.TerminalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return startSafely(intent, service.packageName)
        }
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
        delay(600)

        // 桌面自己会报「第 4 屏，共 6 屏」，用它把翻屏上限收到真实页数——
        // 写死 8 屏会在 6 屏的桌面上白翻 2 次，每次 900ms，全是浪费。
        var limit = MAX_DESKTOP_SCREENS

        for (screen in 0 until limit) {
            val root = service.rootInActiveWindow
            val page = desktopPage(root)
            if (page != null && page.second in 2..12) limit = minOf(limit, page.second)
            val node = findIconDeep(root, needles)
            if (node != null) {
                val ok = runCatching {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        // 图标文字节点本身不可点，往上找可点的容器
                        var p = node.parent
                        var clicked = false
                        while (p != null) {
                            if (p.isClickable) { clicked = p.performAction(AccessibilityNodeInfo.ACTION_CLICK); break }
                            p = p.parent
                        }
                        clicked
                    }
                }.getOrDefault(false)
                Dbg.i(TAG, "桌面第 ${page?.first ?: screen} 屏点击「$label」-> $ok (页数上限 $limit)")
                if (ok && awaitForeground(pkg, 2500L)) return true
            }

            // 往下一屏滑。★ 必须验证「真的翻页了」：
            //   华为桌面上有整块的服务卡片/小组件，横向拖在卡片上会被卡片自己吃掉，
            //   手指滑了但屏没动——照原来的写法会以为翻过了，实际在同一屏原地滑 8 次，
            //   最后报「翻完 8 屏没找到」（实测「设置」就这么丢的）。
            val before = page?.first
            var moved = false
            for (fy in SWIPE_YS) {
                gesture({ p ->
                    p.moveTo((w * 0.85f).toFloat(), (h * fy).toFloat())
                    p.lineTo((w * 0.12f).toFloat(), (h * fy).toFloat())
                }, 300)
                delay(500)
                val now = desktopPage(service.rootInActiveWindow)?.first
                if (before != null && now != null) {
                    if (now != before) { moved = true; break }
                } else {
                    // 读不到页码（桌面版本差异）→ 当作翻动了，按老逻辑走
                    moved = true; break
                }
            }
            if (!moved) {
                Dbg.w(TAG, "桌面滑不动了（卡片吃手势或已到最后一屏），停止翻屏")
                break
            }
        }
        Dbg.w(TAG, "翻完 $limit 屏都没找到「${needles.joinToString("/")}」")
        return false
    }

    /** 桌面报的「第 N 屏，共 M 屏」→ (N, M)。读不到返回 null。 */
    private fun desktopPage(root: AccessibilityNodeInfo?): Pair<Int, Int>? {
        if (root == null) return null
        var found: String? = null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 400) {
            val n = stack.removeLast()
            val d = n.contentDescription?.toString().orEmpty()
            if (d.contains("屏") && d.contains("共")) { found = d; break }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        val m = found?.let { Regex("第\\s*(\\d+)\\s*屏[，,]?\\s*共\\s*(\\d+)\\s*屏").find(it) }
            ?: return null
        val idx = m.groupValues[1].toIntOrNull() ?: return null
        val total = m.groupValues[2].toIntOrNull() ?: return null
        return idx to total
    }

    /**
     * 桌面图标匹配：**精确匹配优先，且只认可点节点**。
     *
     * 上一版是全树 `contains` 匹配、不判可点性，实测在华为「智慧助手」那一屏
     * 点到了一个含「设置」字样的卡片，performAction 返回 true、界面纹丝不动，
     * 然后接着翻屏——白花 10 秒。桌面图标 99% 是 content-desc 精确等于 App 名，
     * 先按精确找，找不到才退到前缀匹配，再不行才 contains。
     */
    private fun findIconDeep(
        root: AccessibilityNodeInfo?,
        needles: List<String>,
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 800) {
            val n = stack.removeLast()
            val names = listOfNotNull(
                n.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                n.text?.toString()?.takeIf { it.isNotBlank() },
            )
            for (name in names) {
                val s = needles.maxOf { needle ->
                    when {
                        name.equals(needle, ignoreCase = true) -> 3
                        name.startsWith(needle, ignoreCase = true) -> 2
                        name.contains(needle, ignoreCase = true) -> 1
                        else -> 0
                    }
                }
                if (s > bestScore) { bestScore = s; best = n }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        // 只有 contains 级匹配（分值 1）时要求节点/父节点可点，否则宁可返回 null 换策略。
        val hit = best ?: return null
        return if (bestScore >= 2 || hit.isClickable || hit.parent?.isClickable == true) hit else null
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
            .onFailure {
                Dbg.w(TAG, "启动失败 ${it.javaClass.simpleName}: ${it.message}")
                // 设置页被系统拒绝（未导出）就记下来，下次别再撞墙
                if (it is SecurityException && intent.action != null) {
                    SettingsShortcuts.markDead(intent.action!!)
                }
            }
            .isSuccess
        if (!started) return false
        // 目标本来就在前台（同包内换页），回查不出差别，放行
        if (before == target) return true

        // ★ 判「启动成没成」的判据：前台到底动没动，而不是「多久之内到前台」。
        //   2026-11-11 实测（P40 / HarmonyOS 4.0）：force-stop 目标 App 后调 startActivity，
        //   目标进程 **0.6 秒**就起来了（pidof 有值），但窗口焦点要约 **5 秒**才切过去；
        //   而早先的判据是 rootInActiveWindow + 1.2 秒超时，窗口切换期 root 返回 null，
        //   null 被当成「前台没动」→ 把「冷启动慢」误判成「被系统拦截」→
        //   白走一遍桌面翻屏（10 秒，还可能翻不到）。别再按老结论下判断。
        when (awaitTransition(target, 6000L)) {
            Transition.REACHED -> return true
            // 前台换了但还没落在目标包（冷启动过渡页、系统权限弹窗挡在前面）——
            // 这是"起来了"，交给下一轮重感知去处理真实界面。
            Transition.MOVED -> {
                if (awaitForeground(target, 1500L)) return true
                Dbg.i(TAG, "前台已切换（未落在 $target），按启动成功处理")
                return true
            }
            Transition.STUCK -> Unit
        }

        // 走到这里说明 6 秒内前台确实没换过，才轮到兜底。
        // （历史上这里写的是「后台启动被拦 → 桌面翻屏」，那个结论是误判，见上面注释。）
        //
        // ★ 先做一次"回桌面再来一遍"：目标 App 的任务栈可能停在某个奇怪状态，
        //   系统没把它带到前台（实测设置页就卡过：startActivity 成功但窗口不动）。
        //   回桌面等于换一个干净的起点，比去搜索页乱点靠谱。
        Dbg.w(TAG, "启动未生效，回桌面后重试一次：$target")
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(600)
        if (runCatching { service.startActivity(intent) }.isSuccess) {
            when (awaitTransition(target, 4000L)) {
                Transition.REACHED -> return true
                Transition.MOVED -> return true
                Transition.STUCK -> Unit
            }
        }
        Dbg.w(TAG, "目标 $target 未在预算内到达前台，尝试系统搜索拉起")
        // 桌面图标上显示的是**应用 label**（设置 / 蓝牙 / 手机管家），不是包名。
        val displayLabel = runCatching {
            val info = service.packageManager.getPackageInfo(target, 0)
            service.packageManager.getApplicationLabel(info.applicationInfo ?: return@runCatching "").toString()
        }.getOrDefault(target)
        // 首选「系统搜索」：桌面下滑 → 输入应用名 → 回车，一次直达，不受图标在第几屏、
        // 是否被塞进文件夹影响（华为把一堆系统应用收进了大文件夹）。
        if (globalSearch(displayLabel, target, listOf("已安装的应用", "应用"))) return true
        // 最后手段才是翻屏点图标。
        if (launchViaLauncherGesture(target, displayLabel)) return awaitForeground(target, 2500L)
        Dbg.w(TAG, "搜索和桌面图标都没能把 $target 拉到前台")
        return false
    }

    /**
     * 全局搜索：桌面下滑 → 输入 → 点结果。
     *
     * 这一条同时覆盖「启动 App」和「找设置项」两种场景——**桌面下滑的系统搜索本来就能搜到设置项**
     * （实测输入「Settings」直接列 WLAN / 声音和振动 / 蓝牙 / 超级终端）。
     * 所以不需要先打开设置、再进设置内的搜索：一个入口解决，少两次页面跳转。
     *
     * 比翻桌面屏可靠得多：不依赖图标在第几屏、是否被塞进大文件夹，一次输入直达。
     * 不写死搜索页包名（华为 com.huawei.search，小米/OPPO 各不相同），
     * 只认「下滑之后出现的可编辑框」。
     *
     * @param query     输入的关键词
     * @param expectPkg 期望落到的包；null 表示只要求「点了某条结果」
     */
    private suspend fun globalSearch(
        query: String,
        expectPkg: String?,
        wantSections: List<String>? = null,
    ): Boolean {
        val w = service.resources.displayMetrics.widthPixels
        val h = service.resources.displayMetrics.heightPixels

        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(700)

        // 桌面下滑呼出搜索。★ 不是每一屏都能下滑出搜索：
        //   华为首页是「智慧助手」式布局，在那屏下滑没反应（实测）。所以要换屏重试。
        //   ★ 而且试错很贵（一轮三四秒），所以把**上次成功的姿势**缓存下来，
        //     第二次起直接照做——否则每次都从零试，任务整体时延会被这几秒吃掉。
        var edit: AccessibilityNodeInfo? = null
        searchGesture?.let { (from, to) ->
            if (needPageTurn) {
                turnPage(w, h)
                delay(600)
            }
            swipeDown(w, h, from, to)
            delay(800)
            edit = findNodeDeepAnyWindow { it.isEditable }
        }
        if (edit == null) {
            for (round in 0 until 3) {
                for ((from, to) in SEARCH_SWIPES) {
                    swipeDown(w, h, from, to)
                    delay(900)
                    edit = findNodeDeepAnyWindow { it.isEditable }
                    if (edit != null) {
                        searchGesture = from to to
                        needPageTurn = round > 0
                        Dbg.i(TAG, "全局搜索：记住姿势 from=$from to=$to 换屏=$needPageTurn")
                        break
                    }
                }
                if (edit != null) break
                Dbg.w(TAG, "全局搜索：这一屏下滑没反应，换屏重试（第 ${round + 1} 次）")
                turnPage(w, h)
                delay(700)
            }
        }
        if (edit == null) {
            Dbg.w(TAG, "桌面下滑没呼出搜索框，放弃全局搜索")
            return false
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }
        runCatching {
            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        Dbg.i(TAG, "全局搜索：已输入「$query」")

        // ★ 输入即搜，不要按回车。
        //   华为的桌面搜索是**边输边出联想列表**：输入框下面很快浮出建议，
        //   最上面那个分区叫「最佳匹配」，其中第一条就是要找的东西（设置项会带「设置」标签）。
        //   按回车反而跳到「完整搜索结果页」——那页顶部是「图片中找到的文字」OCR 缩略图，
        //   命中关键词的常是图片卡片，点下去停在搜索页（实测踩过两次）。
        val searchPkg = runCatching { edit.packageName?.toString() }.getOrNull()

        // 等列表出来（最多 2 秒），拿到一串候选
        // ★ 不能"一有结果就点"：列表是分批渲染的，最早出现的往往只有一条网页建议
        //   （实测「蓝牙」先只出来『蓝牙耳机怎么配对连接』，点了不动，白等 4 秒）。
        //   所以要等到出现**标题型**候选（短名字，不是一整句话）再动手。
        var list: List<AccessibilityNodeInfo> = emptyList()
        var waited = 0L
        while (waited < SUGGEST_WAIT_MS) {
            val all = candidates(query, searchPkg)
            val titleLike = all.filter { n ->
                val t = n.text?.toString().orEmpty()
                t.isNotEmpty() && t.length <= query.length + 6
            }
            if (titleLike.isNotEmpty()) { list = titleLike; break }
            if (all.isNotEmpty()) list = all // 兜底：先记着，继续等更好的
            delay(200)
            waited += 200
        }
        Dbg.i(TAG, "全局搜索：候选 ${list.size} 个 用时 ${waited}ms 首个=${list.firstOrNull()?.text}")

        // 联想没出来才退回"提交搜索"（老 ROM / 特殊桌面）
        if (list.isEmpty()) {
            val entered = runCatching {
                edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }.getOrDefault(false)
            Dbg.w(TAG, "全局搜索：联想为空，提交搜索 entered=$entered")
            if (expectPkg != null && awaitForeground(expectPkg, 3000L)) return true
            delay(800)
            list = candidates(query, searchPkg, wantSections)
        }
        if (list.isEmpty()) {
            Dbg.w(TAG, "全局搜索：没有匹配「$query」的结果")
            return false
        }

        // 逐个试：点一个、验一个。点完还停在搜索页就换下一个候选。
        // 「最佳匹配」里也可能有好几条，光靠猜不如试。
        for ((idx, cand) in list.take(3).withIndex()) {
            delay(RESULT_SETTLE_MS)
            val pageBefore = foregroundPackage()
            val clicked = runCatching { clickNodeOrParent(cand) }.getOrDefault(false)
            Dbg.i(TAG, "全局搜索：点候选#${idx + 1}「${cand.text ?: cand.contentDescription}」-> $clicked")
            if (!clicked) continue
            if (expectPkg != null) {
                if (awaitForeground(expectPkg, 4000L)) return true
            } else if (awaitLeave(pageBefore, 2500L)) {
                return true
            }
            // 点进搜索 App 内嵌的网页了（点到了网页建议）：退回去，换下一个候选
            if (looksLikeWebPage()) {
                Dbg.w(TAG, "全局搜索：候选#${idx + 1} 进了内嵌网页，回退")
                key("back")
                delay(700)
                continue
            }
            Dbg.w(TAG, "全局搜索：候选#${idx + 1} 没跳走（${foregroundPackage()}），试下一个")
        }
        Dbg.w(TAG, "全局搜索：候选都点过，没能跳走")
        return false
    }

    /** 等前台离开某个包（点结果后判断有没有真跳走）。 */
    private suspend fun awaitLeave(pkg: String?, timeoutMs: Long): Boolean {
        if (pkg == null) { delay(600); return true }
        var waited = 0L
        while (waited < timeoutMs) {
            delay(150)
            waited += 150
            val now = foregroundPackage()
            if (now != null && now != pkg) return true
        }
        return false
    }

    /**
     * 收集搜索结果候选，**按分区限定**。
     *
     * 桌面搜索的结果页是分区结构：最佳匹配 / 设置 / 应用市场 / 搜索网络结果 /
     * 资讯 / 玩机技巧 / 小视频 / 已安装的应用……每块上面有个小标题。
     * 之前的做法是"全页找文字 + 打分"，结果点到过图片缩略图、应用市场条目、内嵌网页。
     * 现在的做法很直接：**认标题、算分区，只在指定分区里取候选**。
     *
     * @param wantSections 只在这些分区里找；null 表示不限定（兜底用）
     */
    private suspend fun candidates(
        keyword: String,
        searchPkg: String?,
        wantSections: List<String>? = null,
    ): List<AccessibilityNodeInfo> = withTimeoutOrNull(4000L) {
        val roots = runCatching { service.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        val mine = roots.filter { r ->
            val p = r.packageName?.toString().orEmpty()
            !p.contains("inputmethod", true) && (searchPkg == null || p == searchPkg)
        }
        val rect = android.graphics.Rect()

        // 第一遍：把分区标题的 y 记下来（"设置"/"已安装的应用"/"应用市场"…）
        val headers = mutableListOf<Pair<String, Int>>()
        for (root in mine) {
            val st = ArrayDeque<AccessibilityNodeInfo>()
            st.add(root)
            var g = 0
            while (st.isNotEmpty() && g++ < 1200) {
                val n = st.removeLast()
                val t = n.text?.toString().orEmpty()
                if (t in SECTION_TITLES && runCatching { n.getBoundsInScreen(rect) }.isSuccess) {
                    headers += t to rect.top
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { st.add(it) }
            }
        }

        // 第二遍：取候选，并算出它属于哪个分区（最近的上方标题）
        val out = mutableListOf<Triple<AccessibilityNodeInfo, Int, Int>>() // node, score, top
        for (root in mine) {
            val st = ArrayDeque<AccessibilityNodeInfo>()
            st.add(root)
            var g = 0
            while (st.isNotEmpty() && g++ < 1200) {
                val n = st.removeLast()
                if (!n.isEditable && !underWebView(n)) {
                    val label = n.text?.toString().orEmpty()
                    val score = when {
                        label.isBlank() -> 0
                        label.equals(keyword, ignoreCase = true) -> 3
                        label.startsWith(keyword, ignoreCase = true) -> 2
                        label.contains(keyword, ignoreCase = true) && label.length <= keyword.length + 24 -> 1
                        else -> 0
                    }
                    if (score > 0 && runCatching { n.getBoundsInScreen(rect) }.isSuccess && rect.height() > 0) {
                        val section = nearestHeader(headers, rect.top)
                        val sectionOk = wantSections == null || section == null || section in wantSections
                        if (sectionOk) out += Triple(n, score, rect.top)
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { st.add(it) }
            }
        }
        out.sortedWith(compareByDescending<Triple<AccessibilityNodeInfo, Int, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
            .distinctBy { node ->
                val r = android.graphics.Rect()
                runCatching { node.getBoundsInScreen(r) }
                "${r.top}/${r.left}"
            }
            .take(5)
    } ?: emptyList()

    /** 某个 y 属于哪个分区：取它上方最近的那个标题。 */
    private fun nearestHeader(headers: List<Pair<String, Int>>, top: Int): String? =
        headers.filter { it.second < top }.maxByOrNull { it.second }?.first

    /**
     * 当前页面是不是"网页"（点错建议时会进到搜索 App 内嵌的网页里）。
     *
     * ★ 不能按包名判：华为搜索的网页是**它自己内嵌 WebView 打开的**，
     *   前台包一直是 com.huawei.search，看不到任何 browser 包（实测 dump 确认）。
     */
    private fun looksLikeWebPage(): Boolean {
        val roots = runCatching { service.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach { stack.add(it) }
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 1200) {
            val n = stack.removeLast()
            if (n.className?.toString()?.contains("WebView", ignoreCase = true) == true) return true
            val t = n.text?.toString().orEmpty()
            if (t.contains("百度一下") || t.contains("相关搜索") || t.contains("网页快照")) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return false
    }

    /** 节点是否在 WebView 里（网页搜索结果的文字都在 WebView 里，点不得）。 */
    private fun underWebView(node: AccessibilityNodeInfo): Boolean {
        var p: AccessibilityNodeInfo? = node
        var depth = 0
        while (p != null && depth++ < 6) {
            if (p.className?.toString()?.contains("WebView", ignoreCase = true) == true) return true
            p = p.parent
        }
        return false
    }

    private suspend fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var p = node.parent
        var depth = 0
        while (p != null && depth++ < 6) {
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            p = p.parent
        }
        val rect = android.graphics.Rect()
        if (!runCatching { node.getBoundsInScreen(rect) }.isSuccess) return false
        if (rect.width() <= 0 || rect.height() <= 0) return false
        return tap(rect.centerX(), rect.centerY())
    }

    /**
     * 把某个设置项拨到指定状态（开/关），并**回读校验**。
     *
     * ★ 「打开护眼模式」不等于「打开护眼模式的设置页」。
     *   用户要的是开关变成开——停在设置页等于没做。所以这里四步：
     *   搜索进入 → 找开关 → 拨到目标状态 → 重新读一遍确认。
     *   这条对所有设置项通用：能搜到的项、页面上有 Switch/CheckBox 的都能拨。
     *
     * @return 成功时的用户话术；失败返回 null（调用方据此播报失败，不许假成功）
     */
    suspend fun setSettingsItem(keyword: String, on: Boolean): String? {
        // 已经在目标设置页上（页面文字里就有这个词）→ 直接拨当前页的开关，
        // 不用再退回桌面搜一遍。「打开蓝牙」走直达进了蓝牙页之后就是这条路。
        if (!onSettingsPage(keyword)) {
            // ★ 设置项**不要走桌面全局搜索**：那个列表里混着应用市场条目、网页建议，
            //   实测点「蓝牙」直接进了华为应用市场，点「护眼方法」进了内嵌网页。
            //   设置 App 自己的搜索框结果干净（全是设置项），一步到位。
            if (!searchInSettings(keyword)) {
                Dbg.w(TAG, "设置开关：没在设置里搜到「$keyword」")
                return null
            }
        }
        // ★ 必须等页面真的落到「含这个词的设置页」再拨。
        //   实测过一次假成功：刚点完搜索结果、页面还没切过去，
        //   就读到搜索页里某个 checked=true 的开关，直接回答"已经是开启状态"，
        //   而截图里蓝牙开关明明是关的。
        if (!awaitSettingsPage(keyword, 3000L)) {
            Dbg.w(TAG, "设置开关：没进到「$keyword」的设置页（pkg=${foregroundPackage()}）")
            return null
        }
        return toggleTopSwitch(keyword, on)
    }

    /**
     * 在**设置 App 内部**搜索某一项。
     *
     * 为什么不复用桌面全局搜索：桌面搜索的建议列表混着「应用市场」「网页搜索」条目，
     * 点错了会跳应用市场或内嵌百度页（实测都踩到）。设置内的搜索只出设置项，干净。
     */
    private suspend fun searchInSettings(keyword: String): Boolean {
        val settingsPkg = "com.android.settings"
        val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!startSafely(intent, settingsPkg)) {
            Dbg.w(TAG, "设置内搜索：设置没打开")
            return false
        }
        delay(600)

        // 设置首屏顶部就有搜索框（AutoCompleteTextView，id=search_src_text）——按 isEditable 找
        val edit = findNodeDeepAnyWindow { it.isEditable } ?: run {
            Dbg.w(TAG, "设置内搜索：没找到搜索框（pkg=${foregroundPackage()}）")
            return false
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, keyword)
        }
        runCatching {
            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        Dbg.i(TAG, "设置内搜索：已输入「$keyword」")
        delay(700)

        // 结果列表：优先「最佳匹配」分区里的标题型条目
        var list: List<AccessibilityNodeInfo> = emptyList()
        var waited = 0L
        while (waited < 2500L) {
            val all = candidates(keyword, settingsPkg)
            val titleLike = all.filter { n ->
                val t = n.text?.toString().orEmpty()
                t.isNotEmpty() && t.length <= keyword.length + 8
            }
            if (titleLike.isNotEmpty()) { list = titleLike; break }
            if (all.isNotEmpty()) list = all
            delay(200)
            waited += 200
        }
        if (list.isEmpty()) {
            Dbg.w(TAG, "设置内搜索：没有「$keyword」的条目")
            return false
        }
        for ((idx, cand) in list.take(3).withIndex()) {
            delay(200)
            val ok = runCatching { clickNodeOrParent(cand) }.getOrDefault(false)
            Dbg.i(TAG, "设置内搜索：点#${idx + 1}「${cand.text}」-> $ok")
            if (!ok) continue
            // ★ 设置内部的页面跳转**不会换包名**（一直在 com.android.settings 里），
            //   所以不能用"前台包变了"判断成功。判据换成：搜索框消失 + 页面里出现关键词。
            if (awaitResultPage(keyword, 2500L)) return true
        }
        return false
    }

    /**
     * 等"从搜索结果跳到了具体设置页"。
     *
     * 判据两条：搜索结果页的输入框消失 + 当前设置页文字里含关键词。
     * 只看关键词不行——搜索结果页本身就含关键词。
     */
    private suspend fun awaitResultPage(keyword: String, timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            val pkg = foregroundPackage().orEmpty()
            if (pkg.contains("settings", true)) {
                val root = service.rootInActiveWindow
                val hasSearchBox = root != null && findNodeDeep(root) { it.isEditable } != null
                if (!hasSearchBox && onSettingsPage(keyword)) return true
            }
            delay(200)
            waited += 200
        }
        return false
    }

    /** 轮询等"包含关键词的设置页"出现。 */
    private suspend fun awaitSettingsPage(keyword: String, timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (onSettingsPage(keyword)) return true
            delay(200)
            waited += 200
        }
        return false
    }

    /** 当前前台是不是「包含这个词的设置页」。 */
    private fun onSettingsPage(keyword: String): Boolean {
        val pkg = foregroundPackage() ?: return false
        if (!pkg.contains("settings", ignoreCase = true)) return false
        var found = false
        val roots = runCatching { service.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach { stack.add(it) }
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 1200) {
            val n = stack.removeLast()
            val label = n.text?.toString().orEmpty() + n.contentDescription?.toString().orEmpty()
            if (label.contains(keyword, ignoreCase = true)) { found = true; break }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return found
    }

    /** 把当前页最靠上的开关拨到目标状态，回读确认。返回话术，失败返回 null。 */
    private suspend fun toggleTopSwitch(keyword: String, on: Boolean): String? {
        val verb = if (on) "开启" else "关闭"
        if (!onSettingsPage(keyword)) {
            Dbg.w(TAG, "设置开关：当前页不是「$keyword」（pkg=${foregroundPackage()}），不拨")
            return null
        }
        repeat(3) {
            val sw = findTopSwitch() ?: return null
            // ★ 认人不能靠"第一个开关"，也不能只靠 viewId：
            //   护眼模式页里主开关和「定时开启」共用 `android:id/switch_widget`。
            val id = sw.viewIdResourceName
            val box = android.graphics.Rect().also { runCatching { sw.getBoundsInScreen(it) } }
            if (sw.isChecked == on) {
                Dbg.i(TAG, "设置开关：目标已是 $on（checked=${sw.isChecked}），无需操作")
                return "已经是${if (on) "开启" else "关闭"}状态"
            }

            // 三种点法逐级降级：控件本身 → 可点父行 → 按坐标点。
            var reached = false
            if (runCatching { sw.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                delay(900)
                reached = readSwitch(id, box)?.isChecked == on
            }
            if (!reached) {
                val parent = sw.parent
                if (parent != null &&
                    runCatching { parent.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                ) {
                    delay(900)
                    reached = readSwitch(id, box)?.isChecked == on
                }
                Dbg.i(TAG, "设置开关：点控件未达成，改点父行 reached=$reached")
            }
            if (!reached && box.width() > 0) {
                tap(box.centerX(), box.centerY())
                delay(900)
                reached = readSwitch(id, box)?.isChecked == on
                Dbg.i(TAG, "设置开关：按坐标点 reached=$reached")
            }
            if (reached) {
                Dbg.i(TAG, "设置开关：已$verb（回读确认）")
                return "已$verb"
            }
        }
        return null
    }

    private fun isSwitchNode(n: AccessibilityNodeInfo): Boolean =
        n.className?.toString()?.contains("Switch", ignoreCase = true) == true

    /**
     * 页面里最靠上的那个开关 = 这一项的主开关。
     *
     * ★ 优先取**可点**的那个：蓝牙页有两个 Switch——整行的 `switch_bar`
     *   （`clickable=false`）和右侧真正可拨的 `switch_widget`（`clickable=true`），
     *   两者 y 相同，取到前者就怎么点都不动。
     */
    private fun findTopSwitch(): AccessibilityNodeInfo? {
        var bestClickable: AccessibilityNodeInfo? = null
        var bestClickableY = Int.MAX_VALUE
        var bestAny: AccessibilityNodeInfo? = null
        var bestAnyY = Int.MAX_VALUE
        val rect = android.graphics.Rect()
        val roots = runCatching { service.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        roots.filter { it.packageName?.toString()?.contains("inputmethod", true) != true }.forEach { stack.add(it) }
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 1200) {
            val n = stack.removeLast()
            if (isSwitchNode(n) && runCatching { n.getBoundsInScreen(rect) }.isSuccess && rect.height() > 0) {
                if (rect.top < bestAnyY) { bestAnyY = rect.top; bestAny = n }
                if (n.isClickable && rect.top < bestClickableY) { bestClickableY = rect.top; bestClickable = n }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return bestClickable ?: bestAny
    }

    /** 按 viewId + 位置回读同一个开关。 */
    private fun readSwitch(id: String?, box: android.graphics.Rect): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        val roots = runCatching { service.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        roots.filter { it.packageName?.toString()?.contains("inputmethod", true) != true }.forEach { stack.add(it) }
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 1200) {
            val n = stack.removeLast()
            if (isSwitchNode(n) && n.viewIdResourceName == id && sameBounds(n, box)) { found = n; break }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return found
    }

    private fun sameBounds(n: AccessibilityNodeInfo, box: android.graphics.Rect): Boolean {
        val r = android.graphics.Rect()
        if (!runCatching { n.getBoundsInScreen(r) }.isSuccess) return false
        return kotlin.math.abs(r.centerX() - box.centerX()) < 8 && kotlin.math.abs(r.centerY() - box.centerY()) < 8
    }

    /**
     * 设置项搜索：直接走全局搜索。
     *
     * 华为把大量设置项埋在二三级菜单里（电池优化、应用启动管理、护眼…），
     * 直达常常只落到入口页。桌面下滑的全局搜索能直接列出设置项，点一下就进去，
     * 也不用先开设置——少一次跳转，也少一个会变的菜单层级。
     */
    suspend fun searchSettings(keyword: String): Boolean {
        if (keyword.isBlank()) return false
        return globalSearch(keyword, "com.android.settings", listOf("设置"))
    }

    private suspend fun swipeDown(w: Int, h: Int, from: Float, to: Float) {
        gesture({ p ->
            p.moveTo((w * 0.5f).toFloat(), (h * from).toFloat())
            p.lineTo((w * 0.5f).toFloat(), (h * to).toFloat())
        }, 400)
    }

    /** 桌面翻到下一屏（从右往左滑）。 */
    private suspend fun turnPage(w: Int, h: Int) {
        gesture({ p ->
            p.moveTo((w * 0.85f).toFloat(), (h * 0.8f).toFloat())
            p.lineTo((w * 0.15f).toFloat(), (h * 0.8f).toFloat())
        }, 300)
    }

    /**
     * 在所有窗口里找节点，输入法窗口排最后。
     *
     * ★ `rootInActiveWindow` 只给"当前活动窗口"，而**输入法弹出来时它给的是键盘**——
     *   实测点开设置搜索框后它返回 `com.huawei.ohos.inputmethod`，
     *   于是"页面上没有输入框"这种假结论就出来了（页面明明开着）。
     */
    private fun findNodeDeepAnyWindow(
        match: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val roots = runCatching { service.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        if (roots.isEmpty()) return findNodeDeep(service.rootInActiveWindow, match)
        val ordered = roots.sortedBy {
            if (it.packageName?.toString()?.contains("inputmethod", true) == true) 1 else 0
        }
        for (r in ordered) findNodeDeep(r, match)?.let { return it }
        return null
    }

    /** 广度优先找第一个满足条件的节点（搜索页/桌面都在多层容器里）。 */
    private fun findNodeDeep(
        root: AccessibilityNodeInfo?,
        match: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 800) {
            val n = queue.removeFirst()
            if (runCatching { match(n) }.getOrDefault(false)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    /**
     * 当前前台窗口的包名。
     *
     * ★ 优先用无障碍事件流（`AgentRuntime.foreground`，由 TYPE_WINDOW_STATE_CHANGED 驱动）：
     *   `rootInActiveWindow` 在窗口切换期间会返回 **null**，拿它判断"启动成不成功"
     *   会把"冷启动慢"误判成"被系统拦截"（2026-11-11 实测：Settings 冷启动约 5 秒，
     *   期间 root 一直是 null，而进程其实 0.6 秒就起来了）。
     */
    fun foregroundPackage(): String? {
        val ev = com.pilot.agent.runtime.AgentRuntime.foreground.value
        if (ev.isNotEmpty()) return ev
        return runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
    }

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

    private enum class Transition { REACHED, MOVED, STUCK }

    /**
     * 等「前台窗口发生变化」。
     *
     * 三态的意义在于区分系统拦截和启动慢：
     * - REACHED：落在目标包，成功。
     * - MOVED：前台确实换了（哪怕还没到目标），说明 startActivity 被系统放行了，只是还在过渡。
     * - STUCK：前台一动没动，这才是真的被拦（HarmonyOS `Abort background activity starts`）。
     */
    private suspend fun awaitTransition(pkg: String, timeoutMs: Long): Transition {
        val before = foregroundPackage()
        var waited = 0L
        // 诊断用：把等待期间看到的前台包序列记下来。
        // 「被判成拦截」到底是系统真拦了，还是我们读不到窗口（rootInActiveWindow 切换期间为 null），
        // 只有这串序列能回答。别靠猜。
        val seen = LinkedHashSet<String>()
        while (waited < timeoutMs) {
            delay(150)
            waited += 150
            val now = foregroundPackage()
            seen.add(now ?: "null")
            if (now == pkg) {
                Dbg.i(TAG, "前台切到 $pkg 用时 ${waited}ms")
                return Transition.REACHED
            }
            if (now != null && now != before) {
                Dbg.i(TAG, "前台已变（$before → $now）用时 ${waited}ms，等它落到 $pkg")
                return Transition.MOVED
            }
        }
        Dbg.w(TAG, "等待 ${timeoutMs}ms 前台未变，观察到的包序列=$seen")
        return Transition.STUCK
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
         * 横滑时尝试的纵向位置（按屏幕高度比例）。
         *
         * 华为桌面首页是「智慧助手」式布局，中间整块是服务卡片/小组件，
         * 在卡片上横滑会被卡片吞掉。所以换几个高度各试一次，翻不动再判死。
         */
        private val SWIPE_YS = listOf(0.82f, 0.30f, 0.55f)

        /**
         * 桌面搜索页的分区标题。
         *
         * 华为搜索结果页固定是这几块：最佳匹配 / 设置 / 应用市场 / 搜索网络结果 /
         * 资讯 / 玩机技巧 / 小视频 / 已安装的应用。按标题切区，才知道该点哪一条。
         */
        private val SECTION_TITLES = setOf(
            "最佳匹配", "设置", "应用", "已安装的应用", "应用市场",
            "搜索网络结果", "资讯", "玩机技巧", "小视频", "网页", "视频",
            "相关搜索", "热门", "猜你想搜",
        )

        /** 输入后等联想列表浮出来（输入即搜，不用回车） */
        private const val SUGGEST_WAIT_MS = 2000L

        /** 搜索结果出现后先等一拍再点（列表还在排版，位置会动） */
        private const val RESULT_SETTLE_MS = 250L

        /** 桌面下滑呼出搜索的候选手势（高度比例 from→to） */
        private val SEARCH_SWIPES = listOf(0.22f to 0.62f, 0.55f to 0.90f)
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
