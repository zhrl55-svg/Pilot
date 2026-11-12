package com.pilot.navigation

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

/**
 * 语音按钮外框的彩色转圈。
 *
 * 之前用 scale 呼吸/跳动表示「正在听 / 正在说」，
 * 老板要求改成**外框彩色转圈**：状态一眼可见，又不会让按钮本身晃来晃去。
 *
 * 画的是贴近按钮边缘的圆弧（300°，留 60° 缺口更像在转），
 * 颜色用 sweep 渐变；录音中偏蓝紫、播报中偏橙粉。
 */
class VoiceRingDrawable(
    private val density: Float,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
    }

    private var angle = 0f
    private var colors: IntArray = RECORDING_COLORS

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1100L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidateSelf()
        }
    }

    /** true = 播报中（橙粉），false = 录音中（蓝紫）。 */
    fun setSpeaking(speaking: Boolean) {
        val next = if (speaking) SPEAKING_COLORS else RECORDING_COLORS
        if (next !== colors) {
            colors = next
            invalidateSelf()
        }
    }

    fun start() {
        if (!animator.isStarted) animator.start()
    }

    fun stop() {
        animator.cancel()
        angle = 0f
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val inset = paint.strokeWidth / 2f + density
        val rect = RectF(
            b.left + inset,
            b.top + inset,
            b.right - inset,
            b.bottom - inset,
        )
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val cx = rect.centerX()
        val cy = rect.centerY()
        paint.shader = SweepGradient(cx, cy, colors, null)
        canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.drawArc(rect, 0f, 300f, false, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        val RECORDING_COLORS = intArrayOf(
            0xFF4DDBFF.toInt(),
            0xFF5C8CFF.toInt(),
            0xFFB06BFF.toInt(),
            0xFF4DDBFF.toInt(),
        )
        val SPEAKING_COLORS = intArrayOf(
            0xFFFFB84D.toInt(),
            0xFFFF6B9E.toInt(),
            0xFFB06BFF.toInt(),
            0xFFFFB84D.toInt(),
        )
    }
}
