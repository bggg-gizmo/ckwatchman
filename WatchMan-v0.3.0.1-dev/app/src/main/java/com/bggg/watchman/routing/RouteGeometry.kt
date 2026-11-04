package com.bggg.watchman.routing

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.util.Geo
import kotlin.math.abs

/**
 * Compares route shapes independently of the number or spacing of encoded vertices.
 * This prevents the same road route returned by multiple OSRM endpoints from occupying
 * several alternative slots.
 */
object RouteGeometry {
    private const val SAMPLE_COUNT = 17
    private const val ENDPOINT_TOLERANCE_METERS = 120.0
    private const val AVERAGE_SEPARATION_METERS = 70.0
    private const val MAX_SAMPLE_SEPARATION_METERS = 220.0
    private const val MAX_DISTANCE_RATIO_DELTA = 0.10

    fun equivalent(a: Route, b: Route): Boolean {
        if (a.points.size < 2 || b.points.size < 2) return false
        if (Geo.distanceMeters(a.points.first(), b.points.first()) > ENDPOINT_TOLERANCE_METERS) return false
        if (Geo.distanceMeters(a.points.last(), b.points.last()) > ENDPOINT_TOLERANCE_METERS) return false

        val longer = maxOf(a.distanceMeters, b.distanceMeters).coerceAtLeast(1.0)
        if (abs(a.distanceMeters - b.distanceMeters) / longer > MAX_DISTANCE_RATIO_DELTA) return false

        val aToB = separation(sampleByDistance(a.points), b.points)
        val bToA = separation(sampleByDistance(b.points), a.points)
        return maxOf(aToB.average, bToA.average) <= AVERAGE_SEPARATION_METERS &&
            maxOf(aToB.maximum, bToA.maximum) <= MAX_SAMPLE_SEPARATION_METERS
    }

    fun deduplicate(routes: Collection<Route>): List<Route> {
        val distinct = ArrayList<Route>()
        routes.forEach { candidate ->
            val existingIndex = distinct.indexOfFirst { equivalent(it, candidate) }
            if (existingIndex < 0) {
                distinct += candidate
            } else {
                val existing = distinct[existingIndex]
                if (candidate.durationSeconds < existing.durationSeconds) distinct[existingIndex] = candidate
            }
        }
        return distinct
    }

    private data class Separation(val average: Double, val maximum: Double)

    private fun separation(samples: List<GeoPoint>, route: List<GeoPoint>): Separation {
        val distances = samples.map { point ->
            Geo.projectOntoRoute(point, route)?.distanceToRouteMeters ?: Double.MAX_VALUE
        }
        return Separation(
            average = distances.average(),
            maximum = distances.maxOrNull() ?: Double.MAX_VALUE
        )
    }

    private fun sampleByDistance(points: List<GeoPoint>): List<GeoPoint> {
        if (points.size < 2) return points
        val cumulative = DoubleArray(points.size)
        for (index in 1 until points.size) {
            cumulative[index] = cumulative[index - 1] + Geo.distanceMeters(points[index - 1], points[index])
        }
        val total = cumulative.last()
        if (total <= 0.01) return listOf(points.first(), points.last())

        return (0 until SAMPLE_COUNT).map { sampleIndex ->
            val target = total * sampleIndex / (SAMPLE_COUNT - 1).toDouble()
            var upper = cumulative.binarySearch(target)
            if (upper >= 0) return@map points[upper]
            upper = (-upper - 1).coerceIn(1, points.lastIndex)
            val lower = upper - 1
            val segment = cumulative[upper] - cumulative[lower]
            val fraction = if (segment <= 0.01) 0.0 else (target - cumulative[lower]) / segment
            GeoPoint(
                lat = points[lower].lat + (points[upper].lat - points[lower].lat) * fraction,
                lon = points[lower].lon + (points[upper].lon - points[lower].lon) * fraction
            )
        }
    }
}
