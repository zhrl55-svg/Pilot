package com.pilot.agent.perception

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 感知层：把无障碍节点树压平成 [ScreenSnapshot]，并提供截图兜底。
 *
 * 为什么优先节点树而不是截图：
 * - 节点树是结构化文本，模型定位精确、token 便宜、不用传图；
 * - 截图 + VLM 每次调用都是几千 token 的图像输入，延迟和成本高一个数量级；
 * - 只有节点树拿不到信息时才退到截图（游戏、Flutter/WebView 自绘、Canvas 界面）。
 */
class ScreenReader(private val service: AccessibilityService) {

    @Volatile
    private var cache: Map<Int, AccessibilityNodeInfo> = emptyMap()

    @Volatile
    private var snapshot: ScreenSnapshot? = null

    fun last(): ScreenSnapshot? = snapshot

    fun node(id: Int): AccessibilityNodeInfo? = cache[id]

    /**
     * 抓一次快照。maxNodes 默认 120——超过这个数，模型注意力会明显散掉，
     * 而且 prompt 成本线性上涨。宁可截断 + 滚动重抓。
     */
    fun capture(maxNodes: Int = 120, includePlainText: Boolean = false): ScreenSnapshot? {
        val root = service.rootInActiveWindow ?: return null
        val out = ArrayList<UiNode>(maxNodes)
        val map = HashMap<Int, AccessibilityNodeInfo>(maxNodes)
        val counter = AtomicInteger(0)
        val rect = Rect()
        var truncated = false

        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || out.size >= maxNodes) {
                if (out.size >= maxNodes) truncated = true
                return
            }
            node.getBoundsInScreen(rect)
            val onScreen = rect.width() > 2 && rect.height() > 2 && node.isVisibleToUser
            if (onScreen) {
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

        val snap = ScreenSnapshot(
            pkg = root.packageName?.toString().orEmpty(),
            nodes = out,
            ts = System.currentTimeMillis(),
            truncated = truncated,
        )
        cache = map
        snapshot = snap
        return snap
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
                            val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
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
}
