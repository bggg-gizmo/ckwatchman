package com.bggg.watchman.ui

import com.bggg.watchman.data.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** Pure Web-Mercator route fitting shared by on-device maps and executable tests. */
object RouteViewport {
    data class Fit(val center: GeoPoint, val zoom: Int)

    fun fit(
        points: Collection<GeoPoint>,
        viewportWidthPx: Double,
        viewportHeightPx: Double,
        horizontalPaddingPx: Double,
        topInsetPx: Double,
        bottomInsetPx: Double,
        minZoom: Int = 2,
        maxZoom: Int = 20,
        tileSizePx: Double = 256.0
    ): Fit? {
        if (points.isEmpty() || viewportWidthPx <= 0.0 || viewportHeightPx <= 0.0) return null
        val normalized = normalizedBounds(points)
        val availableWidth = (viewportWidthPx - horizontalPaddingPx * 2.0).coerceAtLeast(1.0)
        val availableHeight = (viewportHeightPx - topInsetPx - bottomInsetPx - horizontalPaddingPx).coerceAtLeast(1.0)
        val visibleCenterY = (topInsetPx + (viewportHeightPx - bottomInsetPx)) / 2.0
        var chosenZoom = minZoom
        for (candidate in maxZoom downTo minZoom) {
            val worldSize = tileSizePx * (1 shl candidate)
            if (normalized.width * worldSize <= availableWidth && normalized.height * worldSize <= availableHeight) {
                chosenZoom = candidate
                break
            }
        }
        val worldSize = tileSizePx * (1 shl chosenZoom)
        val centerX = wrap01(normalized.centerX)
        val centerY = normalized.centerY + (viewportHeightPx / 2.0 - visibleCenterY) / worldSize
        return Fit(
            center = GeoPoint(latitudeFromY(centerY), centerX * 360.0 - 180.0),
            zoom = chosenZoom
        )
    }

    fun containsAll(
        points: Collection<GeoPoint>,
        fit: Fit,
        viewportWidthPx: Double,
        viewportHeightPx: Double,
        horizontalPaddingPx: Double,
        topInsetPx: Double,
        bottomInsetPx: Double,
        tileSizePx: Double = 256.0
    ): Boolean {
        val worldSize = tileSizePx * (1 shl fit.zoom)
        val centerX = normalizedX(fit.center.lon)
        val centerY = normalizedY(fit.center.lat)
        return points.all { point ->
            var dx = normalizedX(point.lon) - centerX
            if (dx > 0.5) dx -= 1.0
            if (dx < -0.5) dx += 1.0
            val screenX = viewportWidthPx / 2.0 + dx * worldSize
            val screenY = viewportHeightPx / 2.0 + (normalizedY(point.lat) - centerY) * worldSize
            screenX >= horizontalPaddingPx - 0.5 &&
                screenX <= viewportWidthPx - horizontalPaddingPx + 0.5 &&
                screenY >= topInsetPx + horizontalPaddingPx / 2.0 - 0.5 &&
                screenY <= viewportHeightPx - bottomInsetPx - horizontalPaddingPx / 2.0 + 0.5
        }
    }

    private data class Bounds(
        val minX: Double,
        val maxX: Double,
        val minY: Double,
        val maxY: Double
    ) {
        val width: Double get() = maxX - minX
        val height: Double get() = maxY - minY
        val centerX: Double get() = (minX + maxX) / 2.0
        val centerY: Double get() = (minY + maxY) / 2.0
    }

    private fun normalizedBounds(points: Collection<GeoPoint>): Bounds {
        val xs = points.map { normalizedX(it.lon) }.sorted()
        var cut = 0.0
        if (xs.size > 1) {
            var largestGap = -1.0
            for (index in xs.indices) {
                val next = if (index == xs.lastIndex) xs.first() + 1.0 else xs[index + 1]
                val gap = next - xs[index]
                if (gap > largestGap) {
                    largestGap = gap
                    cut = next
                }
            }
        }
        val unwrappedX = xs.map { if (it < cut) it + 1.0 else it }
        val ys = points.map { normalizedY(it.lat) }
        return Bounds(
            minX = unwrappedX.min(),
            maxX = unwrappedX.max(),
            minY = ys.min(),
            maxY = ys.max()
        )
    }

    private fun normalizedX(lon: Double): Double = wrap01((lon + 180.0) / 360.0)

    private fun normalizedY(lat: Double): Double {
        val clamped = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return (1.0 - ln(tan(clamped) + 1.0 / kotlin.math.cos(clamped)) / PI) / 2.0
    }

    private fun latitudeFromY(y: Double): Double {
        val mercator = PI * (1.0 - 2.0 * y)
        return Math.toDegrees(atan(sinh(mercator))).coerceIn(-85.05112878, 85.05112878)
    }

    private fun wrap01(value: Double): Double {
        val remainder = value % 1.0
        return if (remainder < 0.0) remainder + 1.0 else remainder
    }
}
