package com.bggg.watchman.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.bggg.watchman.data.RouteStep
import java.util.Locale

/** Native maneuver glyphs for exported turn-by-turn directions. */
object ManeuverIconRenderer {
    fun draw(canvas: Canvas, bounds: RectF, step: RouteStep) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 241, 231); style = Paint.Style.FILL }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(184, 134, 43); style = Paint.Style.STROKE; strokeWidth = 1.5f }
        canvas.drawRoundRect(bounds, 8f, 8f, bg)
        canvas.drawRoundRect(bounds, 8f, 8f, border)

        val type = step.maneuverType.lowercase(Locale.US)
        val mod = step.maneuverModifier.lowercase(Locale.US)
        val inferred = (step.instruction + " " + mod).lowercase(Locale.US)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(42, 44, 48)
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val cx = bounds.centerX(); val cy = bounds.centerY()
        when {
            type == "arrive" || inferred.contains("arrive") -> drawArrival(canvas, cx, cy, paint)
            type == "roundabout" || type == "rotary" || inferred.contains("roundabout") -> drawRoundabout(canvas, cx, cy, paint)
            type == "fork" || inferred.contains("keep left") || inferred.contains("keep right") -> drawFork(canvas, cx, cy, isRight(mod, inferred), paint)
            type == "merge" || inferred.contains("merge") -> drawMerge(canvas, cx, cy, isRight(mod, inferred), paint)
            type == "on ramp" || type == "off ramp" || inferred.contains("ramp") || inferred.contains("exit") -> drawExit(canvas, cx, cy, isRight(mod, inferred), paint)
            inferred.contains("u-turn") || inferred.contains("uturn") || mod.contains("uturn") -> drawUTurn(canvas, cx, cy, isRight(mod, inferred), paint)
            inferred.contains("left") || mod.contains("left") -> drawTurn(canvas, cx, cy, false, paint)
            inferred.contains("right") || mod.contains("right") -> drawTurn(canvas, cx, cy, true, paint)
            else -> drawStraight(canvas, cx, cy, paint)
        }
    }

    private fun isRight(mod: String, inferred: String): Boolean = mod.contains("right") || inferred.contains("right")

    private fun drawStraight(c: Canvas, x: Float, y: Float, p: Paint) {
        c.drawLine(x, y + 14f, x, y - 12f, p); arrowHead(c, x, y - 17f, -90f, p)
    }

    private fun drawTurn(c: Canvas, x: Float, y: Float, right: Boolean, p: Paint) {
        val sx = if (right) 1f else -1f
        val path = Path().apply {
            moveTo(x, y + 15f); lineTo(x, y - 3f); quadTo(x, y - 10f, x + sx * 9f, y - 10f); lineTo(x + sx * 14f, y - 10f)
        }
        c.drawPath(path, p); arrowHead(c, x + sx * 18f, y - 10f, if (right) 0f else 180f, p)
    }

    private fun drawFork(c: Canvas, x: Float, y: Float, right: Boolean, p: Paint) {
        val sx = if (right) 1f else -1f
        c.drawLine(x, y + 16f, x, y + 1f, p)
        c.drawLine(x, y + 1f, x - sx * 10f, y - 12f, p)
        val path = Path().apply { moveTo(x, y + 1f); lineTo(x + sx * 12f, y - 13f) }
        c.drawPath(path, p); arrowHead(c, x + sx * 15f, y - 17f, if (right) -48f else -132f, p)
    }

    private fun drawMerge(c: Canvas, x: Float, y: Float, right: Boolean, p: Paint) {
        val sx = if (right) 1f else -1f
        c.drawLine(x, y + 16f, x, y - 16f, p)
        c.drawLine(x + sx * 15f, y + 8f, x + sx * 3f, y - 3f, p)
        arrowHead(c, x, y - 20f, -90f, p)
    }

    private fun drawExit(c: Canvas, x: Float, y: Float, right: Boolean, p: Paint) {
        val sx = if (right) 1f else -1f
        c.drawLine(x - sx * 5f, y + 16f, x - sx * 5f, y - 16f, p)
        val path = Path().apply { moveTo(x - sx * 5f, y); quadTo(x + sx * 1f, y - 2f, x + sx * 14f, y - 13f) }
        c.drawPath(path, p); arrowHead(c, x + sx * 17f, y - 16f, if (right) -40f else -140f, p)
    }

    private fun drawUTurn(c: Canvas, x: Float, y: Float, right: Boolean, p: Paint) {
        val sx = if (right) 1f else -1f
        val oval = RectF(x - 11f, y - 16f, x + 11f, y + 6f)
        c.drawArc(oval, if (right) 90f else 90f, if (right) -245f else 245f, false, p)
        c.drawLine(x - sx * 11f, y - 1f, x - sx * 11f, y + 15f, p)
        arrowHead(c, x - sx * 11f, y + 17f, 90f, p)
    }

    private fun drawRoundabout(c: Canvas, x: Float, y: Float, p: Paint) {
        c.drawCircle(x, y, 11f, p)
        c.drawLine(x, y + 18f, x, y + 11f, p)
        c.drawLine(x + 11f, y, x + 19f, y, p)
        arrowHead(c, x + 20f, y, 0f, p)
    }

    private fun drawArrival(c: Canvas, x: Float, y: Float, p: Paint) {
        c.drawLine(x - 10f, y + 16f, x - 10f, y - 16f, p)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.color; style = Paint.Style.FILL }
        val path = Path().apply { moveTo(x - 9f, y - 14f); lineTo(x + 12f, y - 8f); lineTo(x - 9f, y - 1f); close() }
        c.drawPath(path, fill)
    }

    private fun arrowHead(c: Canvas, x: Float, y: Float, angle: Float, p: Paint) {
        c.save(); c.rotate(angle, x, y)
        val path = Path().apply { moveTo(x, y); lineTo(x - 7f, y - 5f); moveTo(x, y); lineTo(x - 7f, y + 5f) }
        c.drawPath(path, p); c.restore()
    }
}
