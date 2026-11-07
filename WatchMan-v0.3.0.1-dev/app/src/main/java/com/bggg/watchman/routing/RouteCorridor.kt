package com.bggg.watchman.routing

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.util.Geo
import kotlin.math.cos
import kotlin.math.max

/** Bounded local SQL and geometry work units; exact route data is never modified. */
object RouteCorridor {
    data class Window(
        val points: List<GeoPoint>,
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double
    ) {
        fun contains(point: GeoPoint, radiusMeters: Double): Boolean {
            val index = Geo.nearestRouteIndex(point, points)
            return Geo.distanceToRouteMeters(point, points, index) <= radiusMeters
        }
    }

    fun windows(routes: List<Route>, radiusMeters: Double, maxWindows: Int = MAX_WINDOWS): List<Window> {
        // Query-only geometry includes original endpoint disks after a provider road snap.
        // Stored/displayed routes remain exact; later analysis rejects overinclusive candidates.
        val usable = routes.map { route -> route.requestedOrigin?.let {
            listOf(it) + route.points + route.destination.point
        } ?: route.points }.filter { it.size >= 2 }
        if (usable.isEmpty()) return emptyList()
        val limit = maxWindows.coerceAtLeast(usable.size)
        val totalMeters = usable.sumOf(::pathMeters)
        val chunkMeters = max(
            MIN_WINDOW_LENGTH_METERS,
            totalMeters / (limit - usable.size + 1).coerceAtLeast(1) * WINDOW_HEADROOM
        )
        val result = ArrayList<Window>(limit)
        usable.forEach { points ->
            split(points, chunkMeters).forEach { result += bounds(it, radiusMeters) }
        }
        check(result.size <= limit) {
            "Route corridor window budget exceeded: " + result.size + " > " + limit
        }
        return result
    }

    private fun split(points: List<GeoPoint>, chunkMeters: Double): List<List<GeoPoint>> {
        val result = ArrayList<List<GeoPoint>>()
        var current = arrayListOf(points.first())
        var distance = 0.0
        for (index in 1 until points.size) {
            val point = points[index]
            distance += Geo.distanceMeters(points[index - 1], point)
            current += point
            if (distance >= chunkMeters) {
                result += current
                current = arrayListOf(point)
                distance = 0.0
            }
        }
        if (current.size >= 2) result += current
        else if (result.isNotEmpty()) result[result.lastIndex] = result.last() + current
        return result
    }

    private fun bounds(points: List<GeoPoint>, radiusMeters: Double): Window {
        val midLat = points.map { it.lat }.average()
        val latPad = radiusMeters / METERS_PER_DEGREE
        val lonPad = radiusMeters /
            (METERS_PER_DEGREE * cos(Math.toRadians(midLat)).coerceAtLeast(0.15))
        return Window(
            points,
            (points.minOf { it.lat } - latPad).coerceAtLeast(-89.9),
            (points.minOf { it.lon } - lonPad).coerceAtLeast(-179.9),
            (points.maxOf { it.lat } + latPad).coerceAtMost(89.9),
            (points.maxOf { it.lon } + lonPad).coerceAtMost(179.9)
        )
    }

    private fun pathMeters(points: List<GeoPoint>): Double =
        points.zipWithNext().sumOf { (a, b) -> Geo.distanceMeters(a, b) }

    const val MAX_WINDOWS = 192
    const val MIN_WINDOW_LENGTH_METERS = 40_000.0
    private const val WINDOW_HEADROOM = 1.15
    private const val METERS_PER_DEGREE = 111_320.0
}
