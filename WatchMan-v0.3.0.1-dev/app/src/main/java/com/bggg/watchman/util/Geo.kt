package com.bggg.watchman.util

import com.bggg.watchman.data.GeoPoint
import kotlin.math.*

object Geo {
    private const val EARTH_RADIUS_M = 6371000.0
    const val FEET_PER_METER = 3.280839895

    data class RouteProjection(
        val segmentIndex: Int,
        val fraction: Double,
        val distanceAlongMeters: Double,
        val distanceToRouteMeters: Double,
        val point: GeoPoint
    )

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun angleDelta(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }

    fun nearestRouteIndex(p: GeoPoint, route: List<GeoPoint>): Int {
        if (route.isEmpty()) return -1
        var best = 0
        var bestD = Double.MAX_VALUE
        route.forEachIndexed { i, rp ->
            val d = distanceMeters(p, rp)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** Continuous projection onto route geometry, independent of route vertex density. */
    fun projectOntoRoute(p: GeoPoint, route: List<GeoPoint>, checkActive: () -> Unit = {}): RouteProjection? {
        checkActive()
        if (route.isEmpty()) return null
        if (route.size == 1) {
            return RouteProjection(0, 0.0, 0.0, distanceMeters(p, route[0]), route[0])
        }
        val refLat = Math.toRadians(p.lat)
        fun xy(q: GeoPoint): Pair<Double, Double> {
            val x = Math.toRadians(q.lon - p.lon) * cos(refLat) * EARTH_RADIUS_M
            val y = Math.toRadians(q.lat - p.lat) * EARTH_RADIUS_M
            return x to y
        }
        var cumulative = 0.0
        var best: RouteProjection? = null
        for (i in 0 until route.lastIndex) {
            if (i % 128 == 0) checkActive()
            val a = route[i]
            val b = route[i + 1]
            val segmentMeters = distanceMeters(a, b)
            val (ax, ay) = xy(a)
            val (bx, by) = xy(b)
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 1e-9) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val distance = hypot(px, py)
            val projected = GeoPoint(
                a.lat + (b.lat - a.lat) * t,
                a.lon + (b.lon - a.lon) * t
            )
            val candidate = RouteProjection(i, t, cumulative + segmentMeters * t, distance, projected)
            if (best == null || candidate.distanceToRouteMeters < best.distanceToRouteMeters) best = candidate
            cumulative += segmentMeters
        }
        checkActive()
        return best
    }

    fun routeLengthMeters(route: List<GeoPoint>): Double {
        if (route.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until route.lastIndex) total += distanceMeters(route[i], route[i + 1])
        return total
    }

    fun remainingRouteDistanceMeters(p: GeoPoint, route: List<GeoPoint>): Double {
        val projection = projectOntoRoute(p, route) ?: return 0.0
        return (routeLengthMeters(route) - projection.distanceAlongMeters).coerceAtLeast(0.0)
    }

    fun distanceToRouteMeters(p: GeoPoint, route: List<GeoPoint>, aroundIndex: Int = -1): Double {
        if (route.isEmpty()) return Double.MAX_VALUE
        if (route.size == 1) return distanceMeters(p, route[0])
        if (aroundIndex < 0) return projectOntoRoute(p, route)?.distanceToRouteMeters ?: Double.MAX_VALUE
        val start = max(0, aroundIndex - 40)
        val endSegment = min(route.lastIndex - 1, aroundIndex + 100)
        var best = Double.MAX_VALUE
        for (i in start..endSegment) best = min(best, pointToSegmentMeters(p, route[i], route[i + 1]))
        return best
    }

    fun pointToSegmentMeters(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val refLat = Math.toRadians(p.lat)
        fun xy(q: GeoPoint): Pair<Double, Double> {
            val x = Math.toRadians(q.lon - p.lon) * cos(refLat) * EARTH_RADIUS_M
            val y = Math.toRadians(q.lat - p.lat) * EARTH_RADIUS_M
            return x to y
        }
        val (ax, ay) = xy(a)
        val (bx, by) = xy(b)
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 <= 1e-9) return hypot(ax, ay)
        val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }

    /** Forward distance between continuously projected positions on the route. */
    fun distanceAlongRouteAheadMeters(from: GeoPoint, target: GeoPoint, route: List<GeoPoint>): Double? {
        if (route.size < 2) return null
        val fromProjection = projectOntoRoute(from, route) ?: return null
        val targetProjection = projectOntoRoute(target, route) ?: return null
        val delta = targetProjection.distanceAlongMeters - fromProjection.distanceAlongMeters
        if (delta < -20.0) return null
        return delta.coerceAtLeast(0.0)
    }

    /** Returns an interpolated point roughly distanceMeters ahead of the projected current point. */
    fun pointAlongRouteAhead(from: GeoPoint, route: List<GeoPoint>, distanceMeters: Double): GeoPoint? {
        if (route.isEmpty()) return null
        if (route.size == 1) return route[0]
        val projection = projectOntoRoute(from, route) ?: return null
        var targetAlong = projection.distanceAlongMeters + distanceMeters.coerceAtLeast(0.0)
        var cumulative = 0.0
        for (i in 0 until route.lastIndex) {
            val a = route[i]
            val b = route[i + 1]
            val segment = distanceMeters(a, b)
            if (targetAlong <= cumulative + segment || i == route.lastIndex - 1) {
                if (segment <= 0.01) return b
                val t = ((targetAlong - cumulative) / segment).coerceIn(0.0, 1.0)
                return GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            }
            cumulative += segment
        }
        return route.last()
    }

    /** Samples existing route geometry at approximately the requested spacing. */
    fun sampleRoutePoints(route: List<GeoPoint>, spacingMeters: Double): List<GeoPoint> {
        if (route.isEmpty()) return emptyList()
        if (route.size == 1 || spacingMeters <= 0.0) return route
        val out = ArrayList<GeoPoint>()
        out += route.first()
        var since = 0.0
        for (i in 0 until route.lastIndex) {
            since += distanceMeters(route[i], route[i + 1])
            if (since >= spacingMeters) {
                out += route[i + 1]
                since = 0.0
            }
        }
        if (out.last() != route.last()) out += route.last()
        return out
    }

    fun routeBounds(route: List<GeoPoint>, paddingMeters: Double = 0.0): DoubleArray {
        require(route.isNotEmpty())
        var south = route.minOf { it.lat }
        var north = route.maxOf { it.lat }
        var west = route.minOf { it.lon }
        var east = route.maxOf { it.lon }
        if (paddingMeters > 0) {
            val mid = (south + north) / 2.0
            val dLat = paddingMeters / 111320.0
            val dLon = paddingMeters / (111320.0 * cos(Math.toRadians(mid)).coerceAtLeast(0.15))
            south -= dLat; north += dLat; west -= dLon; east += dLon
        }
        return doubleArrayOf(south, west, north, east)
    }

    fun etaText(seconds: Double): String {
        val min = max(0, (seconds / 60.0).roundToInt())
        return if (min < 60) "${min} min" else "${min / 60}h ${min % 60}m"
    }

    fun distanceText(meters: Double): String {
        val feet = meters * FEET_PER_METER
        return if (feet < 1000) "${feet.roundToInt()} ft" else String.format("%.1f mi", feet / 5280.0)
    }
}
