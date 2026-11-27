package com.pilot.agent.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.pilot.agent.Dbg
import com.pilot.agent.agent.Risk
import kotlin.math.abs
import kotlin.math.sin

/**
 * Draggable status circle shown only while Pilot is working in the background,
 * plus the cross-app floating confirmation card (审核 P0-3：跨 App 确认通道).
 */
object PilotStatusOverlay {
    private const val TAG = "PilotStatusOverlay"
    private const val PREFS = "floating_status_overlay_v2"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    private var windowManager: WindowManager? = null
    private var statusView: StatusGlyphView? = null
    private var latestStatus = "正在待命"

    /** 主界面是否在前台（MainActivity onResume/onPause 维护）。 */
    @Volatile
    var isAppForeground: Boolean = false
        private set

    private var confirmView: View? = null
    private var confirmId: Long = -1L

    fun onAppForeground(context: Context) {
        isAppForeground = true
        remove(context)
        // 主界面接管确认：悬浮卡收起，由界面弹窗显示同一个待确认请求。
        ConfirmChannel.onAppForeground(context)
    }

    /** MainActivity.onPause 调用：界面不可见时，待确认请求改用悬浮卡。 */
    fun onAppBackground(context: Context) {
        isAppForeground = false
        ConfirmChannel.onAppBackground(context)
    }

    fun show(context: Context, status: String) {
        latestStatus = status
        if (!canDraw(context)) return
        if (statusView != null) {
            render()
            return
        }
        try {
            val appContext = context.applicationContext
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = appContext.resources.displayMetrics.density
            val indicator = StatusGlyphView(appContext, density).apply {
                isClickable = true
            }

            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lp = WindowManager.LayoutParams(
                dp(40, density),
                dp(40, density),
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
            installTouchHandler(appContext, indicator, wm, lp, density)
            wm.addView(indicator, lp)
            windowManager = wm
            statusView = indicator
            render()
            Dbg.i(TAG, "状态悬浮圆已显示")
        } catch (t: Throwable) {
            Dbg.w(TAG, "显示状态悬浮圆失败 ${t.message}")
            remove(context)
        }
    }

    fun updateStatus(context: Context, status: String, visible: Boolean) {
        latestStatus = status
        if (!visible) {
            remove(context)
            return
        }
        if (statusView != null) render()
    }

    fun hide(context: Context) = remove(context)

    fun canDraw(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)

    /**
     * 悬浮确认卡：在任何 App 之上显示“动作 / 对象 / 后果”和确认、取消两个按钮。
     * 必须在主线程调用。
     *
     * @return false = 没有悬浮窗权限或添加失败（调用方改用通知/语音）
     */
    fun showConfirmCard(context: Context, request: ConfirmRequest, onAnswer: (Boolean) -> Unit): Boolean {
        if (!canDraw(context)) return false
        dismissConfirmCard(context, null)
        return try {
            val appContext = context.applicationContext
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = appContext.resources.displayMetrics.density
            val shownAt = SystemClock.elapsedRealtime()
            // 防误触：卡片刚出现的一小段时间内“确认”不生效（付款类更久）。
            val armDelayMs = if (request.risk == Risk.MONEY) 1_500L else 700L
            var answered = false
            fun answerOnce(approved: Boolean) {
                if (answered) return
                answered = true
                onAnswer(approved)
            }

            val card = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18, density), dp(14, density), dp(18, density), dp(10, density))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(18, 24, 36))
                    cornerRadius = 18f * density
                    setStroke(dp(1, density), riskColor(request.risk))
                }
                elevation = 12f * density
            }
            card.addView(TextView(appContext).apply {
                text = if (request.risk == Risk.MONEY) "Pilot 需要你确认（涉及付款）" else "Pilot 需要你确认"
                setTextColor(riskColor(request.risk))
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            })
            fun row(label: String, value: String) {
                if (value.isBlank()) return
                card.addView(TextView(appContext).apply {
                    text = "$label：${value.take(160)}"
                    setTextColor(Color.rgb(226, 232, 240))
                    textSize = 14f
                    setPadding(0, dp(6, density), 0, 0)
                })
            }
            row("动作", request.action)
            row("对象", request.target.orEmpty())
            row("后果", request.consequence)
            card.addView(TextView(appContext).apply {
                text = if (request.risk == Risk.MONEY) {
                    "付款类操作只能点按钮确认；说“取消”可以放弃。"
                } else {
                    "也可以直接说“确认”或“取消”。"
                }
                setTextColor(Color.rgb(148, 163, 184))
                textSize = 12f
                setPadding(0, dp(8, density), 0, 0)
            })
            val buttons = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(6, density), 0, 0)
            }
            buttons.addView(Button(appContext).apply {
                text = "取消"
                isAllCaps = false
                setOnClickListener { answerOnce(false) }
            })
            buttons.addView(Button(appContext).apply {
                text = "确认执行"
                isAllCaps = false
                // 被其他窗口遮挡时不接受点击，防点击劫持。
                filterTouchesWhenObscured = true
                setOnClickListener {
                    if (SystemClock.elapsedRealtime() - shownAt < armDelayMs) return@setOnClickListener
                    answerOnce(true)
                }
            })
            card.addView(buttons)

            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                x = 0
                y = dp(48, density)
                horizontalMargin = 0.03f
            }
            wm.addView(card, lp)
            confirmView = card
            confirmId = request.id
            Dbg.i(TAG, "悬浮确认卡已显示 id=${request.id} risk=${request.risk}")
            true
        } catch (t: Throwable) {
            Dbg.w(TAG, "显示悬浮确认卡失败 ${t.message}")
            confirmView = null
            confirmId = -1L
            false
        }
    }

    /**
     * 收起悬浮确认卡。[id] 为 null 时无条件收起；否则只收起对应请求的卡。
     * 必须在主线程调用。
     */
    fun dismissConfirmCard(context: Context, id: Long?) {
        val view = confirmView ?: return
        if (id != null && id != confirmId) return
        confirmView = null
        confirmId = -1L
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { wm?.removeView(view) }
            .onFailure { Dbg.w(TAG, "移除悬浮确认卡失败 ${it.message}") }
    }

    fun isConfirmCardShowing(id: Long): Boolean = confirmView != null && confirmId == id

    private fun riskColor(risk: Risk): Int = when (risk) {
        Risk.MONEY -> Color.rgb(255, 112, 122)
        Risk.IRREVERSIBLE -> Color.rgb(255, 190, 94)
        Risk.EXTERNAL, Risk.SENSITIVE -> Color.rgb(96, 165, 250)
        Risk.NONE -> Color.rgb(84, 220, 166)
    }

    private fun render() {
        val indicator = statusView ?: return
        val mode = modeFor(latestStatus)
        val tint = colorFor(mode)
        indicator.setVisual(mode, tint)
        indicator.contentDescription = "Pilot 状态：$latestStatus。点击打开 Pilot，拖动可移动。"
    }

    private fun modeFor(status: String): StatusMode = when {
        status.contains("聆听") -> StatusMode.LISTENING
        status.contains("识别") || status.contains("规划") || status.contains("执行") -> StatusMode.PROCESSING
        status.contains("回答") -> StatusMode.SPEAKING
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
        StatusMode.SLEEPING -> Color.rgb(162, 180, 201)
        StatusMode.ERROR -> Color.rgb(255, 112, 122)
        StatusMode.OFF -> Color.rgb(130, 146, 166)
    }

    private fun installTouchHandler(
        context: Context,
        view: View,
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
                        remove(context)
                        runCatching {
                            context.startActivity(
                                Intent(context, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            )
                        }.onFailure { Dbg.w(TAG, "点状态悬浮圆打开 Pilot 失败 ${it.message}") }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun remove(context: Context) {
        val view = statusView ?: return
        val wm = windowManager ?: context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        statusView = null
        windowManager = null
        runCatching { wm?.removeView(view) }
            .onFailure { Dbg.w(TAG, "移除状态悬浮圆失败 ${it.message}") }
    }

    private fun dp(value: Int, density: Float): Int = (value * density + 0.5f).toInt()

    private enum class StatusMode { STANDBY, LISTENING, PROCESSING, SPEAKING, SLEEPING, ERROR, OFF }

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
            val duration = if (mode == StatusMode.STANDBY) 1_800L else 950L
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
            val visualScale = 2f
            val base = 3.5f * visualScale * density
            paint.color = color
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            when (mode) {
                StatusMode.LISTENING -> {
                    val radius = (4f + phase * 5.25f) * visualScale * density
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.8f * visualScale * density
                    paint.alpha = ((1f - phase) * 170).toInt()
                    canvas.drawCircle(cx, cy, radius, paint)
                    paint.style = Paint.Style.FILL
                    paint.alpha = 255
                    canvas.drawCircle(cx, cy, base, paint)
                }
                StatusMode.PROCESSING -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.2f * visualScale * density
                    paint.strokeCap = Paint.Cap.ROUND
                    val r = 6.5f * visualScale * density
                    val oval = RectF(cx - r, cy - r, cx + r, cy + r)
                    canvas.drawArc(oval, phase * 360f, 250f, false, paint)
                    paint.style = Paint.Style.FILL
                }
                StatusMode.SPEAKING -> {
                    val barWidth = 2.2f * visualScale * density
                    for (i in 0..2) {
                        val wave = sin((phase * Math.PI * 2.0) + i * 1.8).toFloat()
                        val h = (4f + (wave + 1f) * 3f) * visualScale * density
                        val x = cx + (i - 1) * 5f * visualScale * density
                        canvas.drawRoundRect(x - barWidth, cy - h / 2f, x + barWidth, cy + h / 2f, barWidth, barWidth, paint)
                    }
                }
                StatusMode.STANDBY -> {
                    paint.alpha = (110 + 45 * sin(phase * Math.PI * 2.0)).toInt()
                    canvas.drawCircle(cx, cy, (6.5f + phase * 1.2f) * visualScale * density, paint)
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
