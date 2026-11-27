package com.pilot.agent.perception

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 感知层：把无障碍节点树压平成 [ScreenSnapshot]，并提供截图兜底。
 *
 * 为什么优先节点树而不是截图：
 * - 节点树是结构化文本，模型定位精确、token 便宜、不用传图；
 * - 截图 + VLM 每次调用都是几千 token 的图像输入，延迟和成本高一个数量级；
 * - 只有节点树拿不到信息时才退到截图（游戏、Flutter/WebView 自绘、Canvas 界面）。
 *
 * ## 帧绑定（审核 D-03）
 * 每次 [capture] 分配一个单调递增的 frameId。节点 id 只在所属帧内有效：
 * 执行层用 [lookup]（frameId + id）取节点，并在执行前做新鲜度校验
 * （节点仍在界面上、位置没动、文字没变）。最近 [MAX_FRAMES] 帧保留节点引用，
 * 其他调用方（UI 刷新、本地工具）中途重抓一帧不会让 Agent 手里的 id 指向别的节点。
 */
class ScreenReader(private val service: AccessibilityService) {

    private class Frame(
        val id: Long,
        val nodes: Map<Int, AccessibilityNodeInfo>,
        val snap: ScreenSnapshot,
    )

    /** 按 (frameId, id) 取节点的结果。 */
    sealed class NodeLookup {
        data class Live(val node: AccessibilityNodeInfo, val ui: UiNode) : NodeLookup()
        data class Stale(val reason: String) : NodeLookup()
    }

    private val frameSeq = AtomicLong(0)

    /** 最近几帧（旧 → 新），访问需持有自身锁。 */
    private val frames = ArrayDeque<Frame>()

    @Volatile
    private var snapshot: ScreenSnapshot? = null

    fun last(): ScreenSnapshot? = snapshot

    /** 最新一帧的帧号；还没抓过返回 0。 */
    fun currentFrameId(): Long = snapshot?.frameId ?: 0L

    /**
     * 兼容旧调用：按**最新一帧**取节点，不做新鲜度校验。
     * Agent 执行路径请用 [lookup]。
     */
    fun node(id: Int): AccessibilityNodeInfo? = synchronized(frames) {
        frames.lastOrNull()?.nodes?.get(id)
    }

    /**
     * 按帧取节点并校验新鲜度。
     *
     * @param frameId 节点 id 所属的帧；null 表示「最新一帧」（旧调用方的语义）。
     * @return [NodeLookup.Live] 才允许执行；[NodeLookup.Stale] 带原因，调用方应重抓屏幕重新规划。
     */
    fun lookup(frameId: Long?, id: Int): NodeLookup {
        val frame = synchronized(frames) {
            if (frameId == null || frameId == 0L) frames.lastOrNull()
            else frames.lastOrNull { it.id == frameId }
        } ?: return NodeLookup.Stale(
            if (frameId == null || frameId == 0L) "还没有读取过屏幕"
            else "节点所在的屏幕帧 #$frameId 已过期",
        )
        val node = frame.nodes[id] ?: return NodeLookup.Stale("屏幕帧 #${frame.id} 里没有节点 [$id]")
        val ui = frame.snap.node(id) ?: return NodeLookup.Stale("屏幕帧 #${frame.id} 里没有节点 [$id]")

        // refresh() 返回 false = 节点已不在窗口里（页面跳走、弹窗关闭）
        val alive = try {
            node.refresh()
        } catch (_: Throwable) {
            false
        }
        if (!alive) return NodeLookup.Stale("节点 [$id] 已从界面上消失")

        val rect = Rect()
        try {
            node.getBoundsInScreen(rect)
        } catch (_: Throwable) {
            return NodeLookup.Stale("节点 [$id] 读不到位置")
        }
        val moved = kotlin.math.abs(rect.left - ui.x1) > BOUNDS_TOLERANCE_PX ||
            kotlin.math.abs(rect.top - ui.y1) > BOUNDS_TOLERANCE_PX ||
            kotlin.math.abs(rect.right - ui.x2) > BOUNDS_TOLERANCE_PX ||
            kotlin.math.abs(rect.bottom - ui.y2) > BOUNDS_TOLERANCE_PX
        if (moved) return NodeLookup.Stale("节点 [$id] 的位置已变化（界面滚动或刷新过）")
        if (!node.isVisibleToUser) return NodeLookup.Stale("节点 [$id] 当前不可见")

        // 列表项会被复用：位置没变但内容换成了别的条目，同样不能点
        if (ui.text.isNotEmpty() && !ui.password) {
            val now = try {
                labelOf(node)
            } catch (_: Throwable) {
                ""
            }
            if (now.isNotEmpty() && now != ui.text) {
                return NodeLookup.Stale("节点 [$id] 的内容已变化")
            }
        }
        return NodeLookup.Live(node, ui)
    }

    /**
     * 抓一次快照。maxNodes 默认 120——超过这个数，模型注意力会明显散掉，
     * 而且 prompt 成本线性上涨。宁可截断 + 滚动重抓。
     */
    fun capture(maxNodes: Int = 120, includePlainText: Boolean = false): ScreenSnapshot? {
        val root = pickRoot() ?: return null
        val out = ArrayList<UiNode>(maxNodes)
        val map = HashMap<Int, AccessibilityNodeInfo>(maxNodes)
        val counter = AtomicInteger(0)
        val rect = Rect()
        var truncated = false
        // 页面上下文文字（含不可点的说明文字）：只给 Guard 判断弹窗语境，见 ScreenSnapshot.contextText
        val context = StringBuilder()

        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || out.size >= maxNodes) {
                if (out.size >= maxNodes) truncated = true
                return
            }
            node.getBoundsInScreen(rect)
            val onScreen = rect.width() > 2 && rect.height() > 2 && node.isVisibleToUser
            if (onScreen) {
                if (context.length < CONTEXT_CHARS && !node.isPassword) {
                    val own = ownLabel(node)
                    if (own.isNotEmpty()) {
                        if (context.isNotEmpty()) context.append(' ')
                        context.append(own.take(80))
                    }
                }
                val interactive = node.isClickable || node.isEditable ||
                    node.isScrollable || node.isCheckable || node.isLongClickable
                if (interactive) {
                    val id = counter.getAndIncrement()
                    map[id] = node
                    out.add(
                        UiNode(
                            id = id,
                            cls = node.className?.toString()?.substringAfterLast('.') ?: "",
                            // 关键：可点击的往往是外层容器（Preference、LinearLayout），
                            // 文本躺在被过滤掉的子 TextView 里。不聚合就等于瞎了。
                            // 密码控件不把任何可见文本放进快照，避免随屏幕上下文发送给规划器。
                            text = if (node.isPassword) "[密码输入框]" else labelOf(node),
                            viewId = node.viewIdResourceName ?: "",
                            x1 = rect.left,
                            y1 = rect.top,
                            x2 = rect.right,
                            y2 = rect.bottom,
                            clickable = node.isClickable,
                            editable = node.isEditable,
                            scrollable = node.isScrollable,
                            checked = node.isChecked,
                            enabled = node.isEnabled,
                            password = node.isPassword,
                        )
                    )
                } else if (includePlainText) {
                    val own = ownLabel(node)
                    if (own.isNotEmpty()) {
                        val id = counter.getAndIncrement()
                        map[id] = node
                        out.add(
                            UiNode(
                                id = id,
                                cls = node.className?.toString()?.substringAfterLast('.') ?: "",
                                text = own,
                                viewId = node.viewIdResourceName ?: "",
                                x1 = rect.left,
                                y1 = rect.top,
                                x2 = rect.right,
                                y2 = rect.bottom,
                                enabled = node.isEnabled,
                            )
                        )
                    }
                }
            }
            for (i in 0 until node.childCount) {
                if (out.size >= maxNodes) {
                    truncated = true
                    return
                }
                visit(node.getChild(i), depth + 1)
            }
        }

        visit(root, 0)

        val frameId = frameSeq.incrementAndGet()
        val snap = ScreenSnapshot(
            pkg = root.packageName?.toString().orEmpty(),
            nodes = out,
            ts = System.currentTimeMillis(),
            truncated = truncated,
            frameId = frameId,
            contextText = context.toString(),
        )
        synchronized(frames) {
            frames.addLast(Frame(frameId, map, snap))
            while (frames.size > MAX_FRAMES) frames.removeFirst()
        }
        snapshot = snap
        return snap
    }

    /**
     * 选要读的窗口根节点。
     *
     * `rootInActiveWindow` 在输入法弹出时可能给的是**键盘窗口**（审核 D-04，DeviceActions 注释里也实测过），
     * 规划器看到的就只剩一排按键。这种情况改取当前聚焦/活动的应用窗口。
     */
    private fun pickRoot(): AccessibilityNodeInfo? {
        val active = service.rootInActiveWindow
        val pkg = active?.packageName?.toString().orEmpty()
        if (active != null && !isImePackage(pkg)) return active
        val app = try {
            val ws = service.windows
            (
                ws.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
                    ?: ws.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                )?.root
        } catch (_: Throwable) {
            null
        }
        return app ?: active
    }

    private fun isImePackage(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        if (pkg.contains("inputmethod", ignoreCase = true)) return true
        return try {
            service.windows.any { w ->
                w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    w.root?.packageName?.toString() == pkg
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** 当前聚焦的**应用窗口**（排除 SystemUI 面板、输入法、悬浮窗）。 */
    private fun focusedAppWindow(): AccessibilityWindowInfo? = try {
        val ws = service.windows
        ws.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
            ?: ws.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
    } catch (_: Throwable) {
        null
    }

    /**
     * 前台应用包名，取自聚焦的应用窗口。
     *
     * 比 TYPE_WINDOW_STATE_CHANGED 事件流更可靠：音量面板、通知栏（systemui）、输入法
     * 不是 TYPE_APPLICATION 窗口，不会把结果污染成 com.android.systemui（审核 D-07）。
     * 读不到返回 null。
     */
    fun focusedAppPackage(): String? = try {
        focusedAppWindow()?.root?.packageName?.toString()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    /**
     * 前台应用窗口指纹：包名 + 窗口 id + 窗口标题。
     * 同一个 App 内换了 Activity 时包名不变，但窗口 id / 标题通常会变——
     * 用来判断「同包内跳页」是否真的发生。读不到返回 null。
     */
    fun windowFingerprint(): String? = try {
        val w = focusedAppWindow()
        if (w == null) {
            null
        } else {
            val pkg = w.root?.packageName?.toString().orEmpty()
            "$pkg|${w.id}|${w.title ?: ""}"
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 无障碍直出截图（API 30+），不需要 MediaProjection 的授权弹窗。
     * 只在节点树信息不足时调用。
     */
    suspend fun screenshot(dir: File, maxWidth: Int = 900, quality: Int = 60): File? =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val executor = ContextCompat.getMainExecutor(service)
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            buffer.close()
                            if (bitmap == null) {
                                if (cont.isActive) cont.resume(null)
                                return
                            }
                            val scaled = downscale(bitmap, maxWidth)
                            // 固定文件名覆盖写入：本方法每轮感知都会调用，带时间戳会无限堆积磁盘。
                            // 调用方拿到的 File 在本次任务内消费完即失效，不跨轮保存。
                            val file = File(dir, "shot.jpg")
                            val ok = runCatching {
                                FileOutputStream(file).use { fos ->
                                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, fos)
                                }
                            }.isSuccess
                            if (scaled !== bitmap) scaled.recycle()
                            bitmap.recycle()
                            if (cont.isActive) cont.resume(if (ok) file else null)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun downscale(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth) return src
        val ratio = maxWidth.toFloat() / src.width
        return Bitmap.createScaledBitmap(src, maxWidth, (src.height * ratio).toInt(), true)
    }

    private fun ownLabel(node: AccessibilityNodeInfo): String {
        if (node.isPassword) return "[密码输入框]"
        return node.text?.toString()?.trim().orEmpty()
            .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
    }

    /**
     * 把一个节点子树里的文本拼成标签。
     *
     * Android 的列表项几乎都是这种结构：外层 LinearLayout 可点击但没有文本，
     * 里面两个 TextView 分别放标题和副标题。只抓 clickable 节点会得到一堆空标签，
     * 模型完全没法决策。往下找两层、拼到 60 字符封顶，足够覆盖绝大多数布局。
     */
    private fun labelOf(node: AccessibilityNodeInfo, depth: Int = 0): String {
        ownLabel(node).let { if (it.isNotEmpty()) return it }
        if (depth >= 2) return ""
        val sb = StringBuilder()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val part = labelOf(child, depth + 1)
            if (part.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(part)
            }
            if (sb.length > 60) break
        }
        return sb.toString().take(60)
    }

    companion object {
        /** 保留节点引用的帧数：Agent 规划期间别处重抓几帧，id 仍能解析回原节点。 */
        private const val MAX_FRAMES = 4

        /** 节点位置容差：超过即认为界面滚动/刷新过，节点作废。 */
        private const val BOUNDS_TOLERANCE_PX = 12

        /** 页面上下文文字上限（只给 Guard 用）。 */
        private const val CONTEXT_CHARS = 1500
    }
}
