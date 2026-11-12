package com.pilot.agent.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.pilot.agent.Dbg
import kotlin.math.abs
import kotlin.math.sin

/** Draggable, dismissible status pill shown only while Pilot is in the background. */
object PilotStatusOverlay {
    private const val TAG = "PilotStatusOverlay"
    private const val PREFS = "floating_status_overlay_v2"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    private var windowManager: WindowManager? = null
    private var pill: LinearLayout? = null
    private var label: TextView? = null
    private var glyph: StatusGlyphView? = null
    private var latestStatus = "正在待命"
    private var renderedStatus: String? = null
    private var dismissedUntilForeground = false

    fun onAppForeground(context: Context) {
        dismissedUntilForeground = false
        remove(context)
    }

    fun show(context: Context, status: String) {
        latestStatus = status
        if (dismissedUntilForeground || !canDraw(context)) return
        if (pill != null) {
            render()
            return
        }
        try {
            val appContext = context.applicationContext
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = appContext.resources.displayMetrics.density
            val bar = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(42, density)
                setPadding(dp(12, density), 0, dp(3, density), 0)
                elevation = dp(10, density).toFloat()
            }
            val indicator = StatusGlyphView(appContext, density).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20, density), dp(20, density)).apply {
                    marginEnd = dp(8, density)
                }
            }
            val text = TextView(appContext).apply {
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.rgb(242, 247, 255))
                textSize = 12.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = (appContext.resources.displayMetrics.widthPixels * 0.74f).toInt()
                includeFontPadding = false
                setPadding(0, 0, dp(4, density), 0)
            }
            val close = TextView(appContext).apply {
                this.text = "×"
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(179, 194, 212))
                textSize = 18f
                includeFontPadding = false
                contentDescription = "隐藏悬浮条"
                layoutParams = LinearLayout.LayoutParams(dp(32, density), dp(42, density))
            }
            bar.addView(indicator)
            bar.addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42, density)))
            bar.addView(close)
            applyBackground(bar, density, COLOR_STANDBY)

            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt(KEY_X, dp(16, density))
                y = prefs.getInt(KEY_Y, dp(56, density))
            }
            installTouchHandler(appContext, bar, wm, lp, density)
            wm.addView(bar, lp)
            windowManager = wm
            pill = bar
            label = text
            glyph = indicator
            render()
            Dbg.i(TAG, "状态悬浮条已显示")
        } catch (t: Throwable) {
            Dbg.w(TAG, "显示状态悬浮条失败 ${t.message}")
            remove(context)
        }
    }

    fun updateStatus(context: Context, status: String, visible: Boolean) {
        latestStatus = status
        if (!visible) {
            remove(context)
            return
        }
        if (pill != null) render()
    }

    fun hide(context: Context) = remove(context)

    private fun canDraw(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)

    private fun render() {
        val bar = pill ?: return
        val text = label ?: return
        val indicator = glyph ?: return
        val mode = modeFor(latestStatus)
        val tint = colorFor(mode)
        val density = bar.resources.displayMetrics.density
        if (renderedStatus != latestStatus) {
            renderedStatus = latestStatus
            val brand = "PILOT"
            text.text = SpannableString("$brand  ·  $latestStatus").apply {
                setSpan(ForegroundColorSpan(Color.rgb(179, 198, 220)), 0, brand.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), 0, brand.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text.contentDescription = "Pilot 状态：$latestStatus。点击打开 Pilot，拖动可移动，点右侧叉号隐藏。"
            text.alpha = 0.72f
            text.animate().cancel()
            text.animate().alpha(1f).setDuration(220L).start()
        }
        indicator.setVisual(mode, tint)
        applyBackground(bar, density, tint)
    }

    private fun modeFor(status: String): StatusMode = when {
        status.contains("聆听") -> StatusMode.LISTENING
        status.contains("识别") || status.contains("规划") || status.contains("执行") -> StatusMode.PROCESSING
        status.contains("回答") -> StatusMode.SPEAKING
        status.contains("导航") -> StatusMode.NAVIGATION
        status.contains("不可用") -> StatusMode.ERROR
        status.contains("休眠") -> StatusMode.SLEEPING
        status.contains("关闭") -> StatusMode.OFF
        else -> StatusMode.STANDBY
    }

    private fun colorFor(mode: StatusMode): Int = when (mode) {
        StatusMode.STANDBY -> Color.rgb(84, 220, 166)
        StatusMode.LISTENING -> Color.rgb(82, 194, 255)
        StatusMode.PROCESSING -> Color.rgb(255, 190, 94)
        StatusMode.SPEAKING -> Color.rgb(192, 151, 255)
        StatusMode.NAVIGATION -> Color.rgb(72, 218, 220)
        StatusMode.SLEEPING -> Color.rgb(162, 180, 201)
        StatusMode.ERROR -> Color.rgb(255, 112, 122)
        StatusMode.OFF -> Color.rgb(130, 146, 166)
    }

    private fun applyBackground(bar: LinearLayout, density: Float, tint: Int) {
        val background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(246, 18, 29, 44), Color.argb(238, 29, 43, 61)),
        ).apply {
            cornerRadius = dp(24, density).toFloat()
            setStroke(dp(1, density), (tint and 0x00FFFFFF) or 0xA0000000.toInt())
        }
        bar.background = background
    }

    private fun installTouchHandler(
        context: Context,
        view: LinearLayout,
        wm: WindowManager,
        lp: WindowManager.LayoutParams,
        density: Float,
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        val slop = dp(8, density)
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) dragged = true
                    if (dragged) {
                        val maxX = (context.resources.displayMetrics.widthPixels - view.width).coerceAtLeast(0)
                        val maxY = (context.resources.displayMetrics.heightPixels - view.height).coerceAtLeast(0)
                        lp.x = (startX + dx).coerceIn(0, maxX)
                        lp.y = (startY + dy).coerceIn(0, maxY)
                        runCatching { wm.updateViewLayout(view, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                    } else {
                        if (event.x >= view.width - dp(42, density)) {
                            dismissedUntilForeground = true
                            Dbg.i(TAG, "用户隐藏状态悬浮条")
                            remove(context)
                        } else {
                            remove(context)
                            runCatching {
                                context.startActivity(
                                    Intent(context, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                                )
                            }.onFailure { Dbg.w(TAG, "点悬浮条打开 Pilot 失败 ${it.message}") }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun remove(context: Context) {
        val view = pill ?: return
        val wm = windowManager ?: context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        pill = null
        label = null
        glyph = null
        renderedStatus = null
        windowManager = null
        runCatching { wm?.removeView(view) }
            .onFailure { Dbg.w(TAG, "移除状态悬浮条失败 ${it.message}") }
    }

    private fun dp(value: Int, density: Float): Int = (value * density + 0.5f).toInt()

    private enum class StatusMode { STANDBY, LISTENING, PROCESSING, SPEAKING, NAVIGATION, SLEEPING, ERROR, OFF }

    private class StatusGlyphView(context: Context, private val density: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var mode = StatusMode.STANDBY
        private var color = COLOR_STANDBY
        private var phase = 0f
        private var animator: ValueAnimator? = null

        fun setVisual(nextMode: StatusMode, nextColor: Int) {
            if (mode != nextMode) {
                mode = nextMode
                restartAnimator()
            }
            if (color != nextColor) {
                color = nextColor
                invalidate()
            }
        }

        private fun restartAnimator() {
            animator?.cancel()
            animator = null
            if (mode in setOf(StatusMode.SLEEPING, StatusMode.ERROR, StatusMode.OFF)) {
                invalidate()
                return
            }
            val duration = if (mode == StatusMode.STANDBY || mode == StatusMode.NAVIGATION) 1_800L else 950L
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val base = 3.5f * density
            paint.color = color
            paint.style = Paint.Style.FILL
            when (mode) {
                StatusMode.LISTENING -> {
                    val radius = (4f + phase * 6f) * density
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.8f * density
                    paint.alpha = ((1f - phase) * 170).toInt()
                    canvas.drawCircle(cx, cy, radius, paint)
                    paint.style = Paint.Style.FILL
                    paint.alpha = 255
                    canvas.drawCircle(cx, cy, base, paint)
                }
                StatusMode.PROCESSING -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.2f * density
                    paint.strokeCap = Paint.Cap.ROUND
                    val r = 6.5f * density
                    val oval = RectF(cx - r, cy - r, cx + r, cy + r)
                    canvas.drawArc(oval, phase * 360f, 250f, false, paint)
                    paint.style = Paint.Style.FILL
                }
                StatusMode.SPEAKING -> {
                    val barWidth = 2.2f * density
                    for (i in 0..2) {
                        val wave = sin((phase * Math.PI * 2.0) + i * 1.8).toFloat()
                        val h = (4f + (wave + 1f) * 3f) * density
                        val x = cx + (i - 1) * 5f * density
                        canvas.drawRoundRect(x - barWidth, cy - h / 2f, x + barWidth, cy + h / 2f, barWidth, barWidth, paint)
                    }
                }
                StatusMode.NAVIGATION -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.7f * density
                    canvas.drawCircle(cx, cy, 6.5f * density, paint)
                    paint.style = Paint.Style.FILL
                    canvas.save()
                    canvas.rotate(phase * 360f, cx, cy)
                    val arrow = Path().apply {
                        moveTo(cx, cy - 7f * density)
                        lineTo(cx + 3f * density, cy + 4f * density)
                        lineTo(cx, cy + 2f * density)
                        lineTo(cx - 3f * density, cy + 4f * density)
                        close()
                    }
                    canvas.drawPath(arrow, paint)
                    canvas.restore()
                }
                StatusMode.STANDBY -> {
                    paint.alpha = (110 + 45 * sin(phase * Math.PI * 2.0)).toInt()
                    canvas.drawCircle(cx, cy, (6.5f + phase * 1.2f) * density, paint)
                    paint.alpha = 255
                    canvas.drawCircle(cx, cy, base, paint)
                }
                else -> canvas.drawCircle(cx, cy, base, paint)
            }
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }

        companion object {
            private val COLOR_STANDBY = Color.rgb(84, 220, 166)
        }
    }

    private val COLOR_STANDBY = Color.rgb(84, 220, 166)
}
