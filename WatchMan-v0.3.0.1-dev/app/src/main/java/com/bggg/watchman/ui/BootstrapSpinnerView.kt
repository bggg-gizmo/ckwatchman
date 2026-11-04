package com.bggg.watchman.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max

/** Single-arc self-eating spinner used on the first-run bootstrap screen. */
class BootstrapSpinnerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = 0xFF86E7E7.toInt()
    }
    private val oval = RectF()
    private var rotationDegrees = 0f
    private var sweepDegrees = 140f
    private var animator: ValueAnimator? = null

    init {
        contentDescription = "Collecting national camera and sensor data"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (72f * resources.displayMetrics.density).toInt()
        val width = resolveSize(desired, widthMeasureSpec)
        val height = resolveSize(desired, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    fun start() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                rotationDegrees = 360f * t
                val phase = if (t < 0.5f) t / 0.5f else (1f - t) / 0.5f
                sweepDegrees = 52f + phase * 220f
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = max(6f, width.coerceAtMost(height) * 0.08f)
        paint.strokeWidth = stroke
        val inset = stroke / 2f + 2f
        oval.set(inset, inset, width - inset, height - inset)
        val startAngle = -90f + rotationDegrees + (280f - sweepDegrees) / 2f
        canvas.drawArc(oval, startAngle, sweepDegrees, false, paint)
    }
}
