package com.bggg.watchman.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.bggg.watchman.data.RouteStep

/** Scalable native maneuver artwork; no font/Unicode-arrow dependency. */
class ManeuverSignView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var kind = ManeuverVisual.Kind.STRAIGHT
    private var signColor = Color.WHITE
    private var tileColor = Color.rgb(28, 33, 42)
    private var borderColor = Color.rgb(70, 139, 230)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setStep(step: RouteStep) {
        kind = ManeuverVisual.classify(step)
        contentDescription = step.instruction
        invalidate()
    }

    fun setPalette(foreground: Int, background: Int, border: Int) {
        signColor = foreground
        tileColor = background
        borderColor = border
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val bounds = RectF(left + size * .04f, top + size * .04f, left + size * .96f, top + size * .96f)
        fill.color = tileColor
        stroke.color = borderColor
        stroke.strokeWidth = size * .035f
        canvas.drawRoundRect(bounds, size * .16f, size * .16f, fill)
        canvas.drawRoundRect(bounds, size * .16f, size * .16f, stroke)

        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(size / 100f, size / 100f)
        stroke.color = signColor
        stroke.strokeWidth = 7f
        when (kind) {
            ManeuverVisual.Kind.ARRIVE -> drawArrival(canvas)
            ManeuverVisual.Kind.ROUNDABOUT_LEFT -> drawRoundabout(canvas, false)
            ManeuverVisual.Kind.ROUNDABOUT_RIGHT -> drawRoundabout(canvas, true)
            ManeuverVisual.Kind.U_TURN_LEFT -> drawUTurn(canvas, false)
            ManeuverVisual.Kind.U_TURN_RIGHT -> drawUTurn(canvas, true)
            ManeuverVisual.Kind.MERGE_LEFT -> drawMerge(canvas, false)
            ManeuverVisual.Kind.MERGE_RIGHT -> drawMerge(canvas, true)
            ManeuverVisual.Kind.FORK_LEFT -> drawFork(canvas, false)
            ManeuverVisual.Kind.FORK_RIGHT -> drawFork(canvas, true)
            ManeuverVisual.Kind.RAMP_LEFT -> drawRamp(canvas, false)
            ManeuverVisual.Kind.RAMP_RIGHT -> drawRamp(canvas, true)
            ManeuverVisual.Kind.SHARP_LEFT -> drawTurn(canvas, false, 38f)
            ManeuverVisual.Kind.SHARP_RIGHT -> drawTurn(canvas, true, 38f)
            ManeuverVisual.Kind.SLIGHT_LEFT -> drawTurn(canvas, false, 20f)
            ManeuverVisual.Kind.SLIGHT_RIGHT -> drawTurn(canvas, true, 20f)
            ManeuverVisual.Kind.TURN_LEFT -> drawTurn(canvas, false, 30f)
            ManeuverVisual.Kind.TURN_RIGHT -> drawTurn(canvas, true, 30f)
            ManeuverVisual.Kind.STRAIGHT -> drawStraight(canvas)
        }
        canvas.restore()
    }

    private fun drawStraight(c: Canvas) {
        c.drawLine(0f, 31f, 0f, -23f, stroke)
        arrow(c, 0f, -32f, -90f)
    }

    private fun drawTurn(c: Canvas, right: Boolean, amount: Float) {
        val side = if (right) 1f else -1f
        val path = Path().apply {
            moveTo(0f, 31f)
            lineTo(0f, -4f)
            quadTo(0f, -19f, side * 17f, -19f)
            lineTo(side * amount, -19f)
        }
        c.drawPath(path, stroke)
        arrow(c, side * (amount + 7f), -19f, if (right) 0f else 180f)
    }

    private fun drawFork(c: Canvas, right: Boolean) {
        val side = if (right) 1f else -1f
        c.drawLine(0f, 31f, 0f, 3f, stroke)
        c.drawLine(0f, 3f, -side * 24f, -27f, stroke)
        c.drawLine(0f, 3f, side * 25f, -28f, stroke)
        arrow(c, side * 30f, -34f, if (right) -50f else -130f)
    }

    private fun drawMerge(c: Canvas, right: Boolean) {
        val side = if (right) 1f else -1f
        c.drawLine(0f, 31f, 0f, -27f, stroke)
        c.drawLine(side * 30f, 18f, side * 7f, -6f, stroke)
        arrow(c, 0f, -35f, -90f)
    }

    private fun drawRamp(c: Canvas, right: Boolean) {
        val side = if (right) 1f else -1f
        c.drawLine(-side * 10f, 32f, -side * 10f, -30f, stroke)
        val path = Path().apply {
            moveTo(-side * 10f, 5f)
            quadTo(0f, 1f, side * 29f, -27f)
        }
        c.drawPath(path, stroke)
        arrow(c, side * 34f, -32f, if (right) -42f else -138f)
    }

    private fun drawUTurn(c: Canvas, right: Boolean) {
        val side = if (right) 1f else -1f
        val oval = RectF(-25f, -31f, 25f, 17f)
        c.drawArc(oval, 90f, if (right) -250f else 250f, false, stroke)
        c.drawLine(-side * 24f, 3f, -side * 24f, 31f, stroke)
        arrow(c, -side * 24f, 37f, 90f)
    }

    private fun drawRoundabout(c: Canvas, right: Boolean) {
        c.drawCircle(0f, 0f, 22f, stroke)
        c.drawLine(0f, 33f, 0f, 22f, stroke)
        val side = if (right) 1f else -1f
        c.drawLine(side * 22f, 0f, side * 34f, 0f, stroke)
        arrow(c, side * 39f, 0f, if (right) 0f else 180f)
    }

    private fun drawArrival(c: Canvas) {
        c.drawLine(-18f, 32f, -18f, -31f, stroke)
        fill.color = signColor
        val flag = Path().apply {
            moveTo(-15f, -29f)
            lineTo(27f, -17f)
            lineTo(-15f, -4f)
            close()
        }
        c.drawPath(flag, fill)
    }

    private fun arrow(c: Canvas, x: Float, y: Float, angle: Float) {
        c.save()
        c.rotate(angle, x, y)
        val head = Path().apply {
            moveTo(x, y)
            lineTo(x - 14f, y - 10f)
            moveTo(x, y)
            lineTo(x - 14f, y + 10f)
        }
        c.drawPath(head, stroke)
        c.restore()
    }
}
