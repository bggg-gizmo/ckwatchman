package com.bggg.watchman.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Fifty-state completion field for the first-run national bootstrap.
 *
 * Stars occupy a nine-row staggered field like the canton of the U.S. flag. A star becomes visible
 * only when one of the 50 states is fully cached. Completed stars share a slow blue breathing glow;
 * DC and territories intentionally do not consume a state-star position.
 */
class StateStarFieldView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(76, 158, 255)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(92, 184, 255)
    }
    private val starPath = Path()
    private var completedStates = 0
    private var pulse = 0f
    private var animator: ValueAnimator? = null
    private var breathingEnabled = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        contentDescription = "No states fully cached yet"
    }

    fun setCompletedStates(count: Int) {
        completedStates = count.coerceIn(0, 50)
        contentDescription = "$completedStates of 50 states fully cached"
        if (completedStates > 0 && breathingEnabled) startBreathing() else stopBreathing()
        invalidate()
    }

    fun setBreathingEnabled(enabled: Boolean) {
        breathingEnabled = enabled
        if (enabled && completedStates > 0) startBreathing() else stopBreathing()
        invalidate()
    }

    private fun startBreathing() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 3200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopBreathing() {
        animator?.cancel()
        animator = null
        pulse = 0f
    }

    override fun onDetachedFromWindow() {
        stopBreathing()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (completedStates <= 0 || width <= 0 || height <= 0) return

        val rows = intArrayOf(6, 5, 6, 5, 6, 5, 6, 5, 6)
        val left = paddingLeft.toFloat() + dp(12f)
        val right = width - paddingRight.toFloat() - dp(12f)
        val top = paddingTop.toFloat() + dp(5f)
        val bottom = height - paddingBottom.toFloat() - dp(5f)
        val usableW = (right - left).coerceAtLeast(dp(40f))
        val usableH = (bottom - top).coerceAtLeast(dp(36f))
        val rowStep = usableH / 9f
        val sixStep = usableW / 6f
        val outer = min(rowStep * 0.33f, sixStep * 0.27f).coerceAtLeast(dp(2.5f))
        val inner = outer * 0.43f

        val glowAlpha = (70 + 105 * pulse).toInt().coerceIn(0, 255)
        val fillAlpha = (175 + 80 * pulse).toInt().coerceIn(0, 255)
        glow.alpha = glowAlpha
        fill.alpha = fillAlpha
        glow.setShadowLayer(dp(5f + 6f * pulse), 0f, 0f, Color.argb(glowAlpha, 61, 170, 255))

        var starIndex = 0
        for (row in rows.indices) {
            val count = rows[row]
            val y = top + rowStep * (row + 0.5f)
            val offset = if (count == 5) sixStep else sixStep * 0.5f
            for (column in 0 until count) {
                if (starIndex >= completedStates) return
                val x = left + offset + column * sixStep
                buildStar(x, y, outer, inner)
                canvas.drawPath(starPath, glow)
                canvas.drawPath(starPath, fill)
                starIndex++
            }
        }
    }

    private fun buildStar(cx: Float, cy: Float, outer: Float, inner: Float) {
        starPath.reset()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = -PI / 2.0 + i * PI / 5.0
            val x = cx + (cos(angle) * radius).toFloat()
            val y = cy + (sin(angle) * radius).toFloat()
            if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
        }
        starPath.close()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
