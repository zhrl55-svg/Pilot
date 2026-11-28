package com.pilot.agent.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.pilot.agent.Dbg
import com.pilot.agent.action.OverlayGate

/**
 * 浏览器“悬浮小窗”（「页面尽量不占主屏」第一步）。
 *
 * - 承载 [BrowserEngine] 的**同一个 WebView**：小窗与隐藏后的无头提取共用页面状态；
 * - 顶栏可拖动；「▪」最小化为气泡，「⛶」展开到全屏 [BrowserActivity]，「✕」关闭；
 * - 点击气泡恢复小窗；
 * - 没有悬浮窗权限时 [canShow] 返回 false，上层退回无头/全屏并提示。
 *
 * 所有方法须在主线程调用（WindowManager 约束）。
 */
object BrowserOverlay {

    private const val TAG = "PilotBrowserWin"

    /** 空闲多久自动收起小窗（毫秒）。老板要求：用完不要一直常驻主屏。 */
    private const val IDLE_CLOSE_MS = 15_000L

    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var column: LinearLayout? = null
    private var content: FrameLayout? = null
    private var bubble: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var minimizeBtn: View? = null
    private var appCtx: Context? = null

    /** 空闲自动关闭：小窗用完一段时间没人碰就收起，不常驻主屏。 */
    private val main = Handler(Looper.getMainLooper())
    private var idleClose: Runnable? = null

    @Volatile
    private var showing = false

    fun isShowing(): Boolean = showing

    fun canShow(context: Context): Boolean = OverlayGate.canDraw(context)

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, onExpand: (String?) -> Unit, onClose: () -> Unit): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Dbg.w(TAG, "show 必须在主线程调用")
            return false
        }
        if (showing && root != null) {
            unminimize()
            return true
        }
        if (!canShow(context)) return false
        val app = context.applicationContext ?: context
        appCtx = app
        return try {
            val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val screenW = app.resources.displayMetrics.widthPixels
            val screenH = app.resources.displayMetrics.heightPixels
            // ★ 默认就是小窗口（老板要求：不要一上来占满屏），点标题栏放大到全屏。
            val winW = (screenW * 0.58f).toInt()
            val winH = (screenH * 0.36f).toInt()

            val frame = FrameLayout(app)
            val col = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }

            val header = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.rgb(12, 18, 28))
                setPadding(dp(app, 12), dp(app, 6), dp(app, 6), dp(app, 6))
            }
            val title = TextView(app).apply {
                text = "PILOT 小窗 · 点标题放大"
                setTextColor(Color.rgb(96, 165, 250))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            header.addView(title)
            header.addView(iconButton(app, "⛶") {
                val url = BrowserEngine.currentUrl
                hide(keepEngine = true)
                onExpand(url)
            })
            val minBtn = iconButton(app, "▪") { minimize() }
            minimizeBtn = minBtn
            header.addView(minBtn)
            header.addView(iconButton(app, "✕") {
                close()
                onClose()
            })
            // 拖动：整个顶栏作为把手；不移动的单击（tap）= 放大到全屏。
            header.setOnTouchListener(makeDragListener(onTap = {
                val url = BrowserEngine.currentUrl
                hide(keepEngine = true)
                onExpand(url)
            }))
            col.addView(header, LinearLayout.LayoutParams(winW, ViewGroup.LayoutParams.WRAP_CONTENT))

            val contentBox = FrameLayout(app).apply { setBackgroundColor(Color.rgb(8, 10, 15)) }
            content = contentBox
            col.addView(contentBox, LinearLayout.LayoutParams(winW, 0, 1f))

            frame.addView(col, FrameLayout.LayoutParams(winW, winH))

            val bubbleView = TextView(app).apply {
                text = "🌐"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(37, 99, 235))
                }
                visibility = View.GONE
                setOnClickListener { unminimize() }
            }
            bubble = bubbleView
            val b = dp(app, 56)
            frame.addView(bubbleView, FrameLayout.LayoutParams(b, b).apply {
                gravity = Gravity.CENTER
            })

            val p = WindowManager.LayoutParams(
                winW,
                winH,
                overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (screenW - winW) / 2
                y = (screenH * 0.45f).toInt()
            }
            w.addView(frame, p)
            // 任何触摸都重置空闲计时（返回 false 不消费事件，WebView 仍能正常交互）。
            frame.setOnTouchListener { _, _ -> resetIdle(); false }

            wm = w
            root = frame
            column = col
            params = p
            showing = true
            resetIdle()

            BrowserEngine.takeWebView()?.let { BrowserEngine.attachToMini(contentBox, screenW) }
            true
        } catch (t: Throwable) {
            Dbg.w(TAG, "小窗创建失败：${t.message}")
            runCatching { root?.let { wm?.removeView(it) } }
            root = null
            showing = false
            false
        }
    }

    /** 隐藏小窗但保留引擎（之后仍可无头提取）。 */
    fun hide(keepEngine: Boolean = true) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        cancelIdle()
        val r = root ?: return
        runCatching { wm?.removeView(r) }
        root = null
        column = null
        content = null
        bubble = null
        minimizeBtn = null
        params = null
        showing = false
        if (keepEngine) BrowserEngine.releaseToHolder()
    }

    fun close() {
        hide(keepEngine = false)
        BrowserEngine.close()
    }

    /** 重置空闲关闭计时：小窗显示期间每次交互都续命。 */
    private fun resetIdle() {
        cancelIdle()
        val r = Runnable {
            if (showing) {
                Dbg.i(TAG, "小窗空闲 ${IDLE_CLOSE_MS / 1000}s，自动收起")
                hide(keepEngine = true)
            }
        }
        idleClose = r
        main.postDelayed(r, IDLE_CLOSE_MS)
    }

    private fun cancelIdle() {
        idleClose?.let { main.removeCallbacks(it) }
        idleClose = null
    }

    private fun minimize() {
        val p = params ?: return
        val frame = root ?: return
        val w = wm ?: return
        val app = appCtx ?: return
        val screenW = app.resources.displayMetrics.widthPixels
        val screenH = app.resources.displayMetrics.heightPixels
        val b = dp(app, 56)
        column?.visibility = View.GONE
        bubble?.visibility = View.VISIBLE
        p.width = b
        p.height = b
        p.x = screenW - b - dp(app, 16)
        p.y = (screenH * 0.6f).toInt()
        runCatching { w.updateViewLayout(frame, p) }
    }

    private fun unminimize() {
        val p = params ?: return
        val frame = root ?: return
        val w = wm ?: return
        val app = appCtx ?: return
        val screenW = app.resources.displayMetrics.widthPixels
        val screenH = app.resources.displayMetrics.heightPixels
        column?.visibility = View.VISIBLE
        bubble?.visibility = View.GONE
        minimizeBtn?.visibility = View.VISIBLE
        p.width = (screenW * 0.58f).toInt()
        p.height = (screenH * 0.36f).toInt()
        p.x = (screenW - p.width) / 2
        p.y = (screenH * 0.5f).toInt()
        runCatching { w.updateViewLayout(frame, p) }
        content?.let { BrowserEngine.takeWebView()?.let { wv -> BrowserEngine.attachToMini(it, screenW) } }
    }

    private fun makeDragListener(onTap: (() -> Unit)? = null): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        return View.OnTouchListener { _, e ->
            val p = params ?: return@OnTouchListener false
            val frame = root ?: return@OnTouchListener false
            val w = wm ?: return@OnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - downX) > dp(frame.context, 8) ||
                        Math.abs(e.rawY - downY) > dp(frame.context, 8)
                    ) moved = true
                    p.x = startX + (e.rawX - downX).toInt()
                    p.y = startY + (e.rawY - downY).toInt()
                    runCatching { w.updateViewLayout(frame, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 没有拖动 = 单击：放大到全屏。
                    if (!moved) onTap?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun iconButton(ctx: Context, label: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
            setOnClickListener { onClick() }
        }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
