package com.bggg.watchman.geo

import com.bggg.watchman.data.GeoPoint
import kotlin.math.abs

object StateBoundaryGeometry {
    data class Ring(
        val latitudes: DoubleArray,
        val longitudes: DoubleArray,
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double,
        val crossesAntimeridian: Boolean
    ) {
        companion object {
            fun from(points: List<GeoPoint>): Ring {
                require(points.size >= 3)
                val latitudes = DoubleArray(points.size) { points[it].lat }
                val longitudes = DoubleArray(points.size) { points[it].lon }
                val minLon = longitudes.minOrNull() ?: 0.0
                val maxLon = longitudes.maxOrNull() ?: 0.0
                return Ring(
                    latitudes,
                    longitudes,
                    latitudes.minOrNull() ?: 0.0,
                    latitudes.maxOrNull() ?: 0.0,
                    minLon,
                    maxLon,
                    maxLon - minLon > 180.0
                )
            }
        }
    }

    data class Polygon(val outer: Ring, val holes: List<Ring>)

    fun contains(polygon: Polygon, point: GeoPoint): Boolean =
        contains(polygon.outer, point) && polygon.holes.none { contains(it, point) }

    fun contains(ring: Ring, point: GeoPoint): Boolean {
        if (point.lat < ring.minLat || point.lat > ring.maxLat) return false
        if (!ring.crossesAntimeridian && (point.lon < ring.minLon || point.lon > ring.maxLon)) return false
        val longitudeReference = if (ring.crossesAntimeridian) ring.longitudes.first() else point.lon
        val normalizedPointLon = nearReference(point.lon, longitudeReference)
        var inside = false
        var previous = ring.latitudes.lastIndex
        for (current in ring.latitudes.indices) {
            val aLat = ring.latitudes[current]
            val bLat = ring.latitudes[previous]
            val aLon = nearReference(ring.longitudes[current], longitudeReference)
            val bLon = nearReference(ring.longitudes[previous], longitudeReference)
            if (onSegment(point.lat, normalizedPointLon, aLat, aLon, bLat, bLon)) return true
            val crosses = (aLat > point.lat) != (bLat > point.lat)
            if (crosses) {
                val intersection = (bLon - aLon) * (point.lat - aLat) / (bLat - aLat) + aLon
                if (normalizedPointLon < intersection) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun nearReference(lon: Double, reference: Double): Double {
        var adjusted = lon
        while (adjusted - reference > 180.0) adjusted -= 360.0
        while (adjusted - reference < -180.0) adjusted += 360.0
        return adjusted
    }

    private fun onSegment(
        pointLat: Double,
        pointLon: Double,
        aLat: Double,
        aLon: Double,
        bLat: Double,
        bLon: Double
    ): Boolean {
        val cross = (pointLon - aLon) * (bLat - aLat) - (pointLat - aLat) * (bLon - aLon)
        if (abs(cross) > BOUNDARY_EPSILON) return false
        return pointLon >= minOf(aLon, bLon) - BOUNDARY_EPSILON &&
            pointLon <= maxOf(aLon, bLon) + BOUNDARY_EPSILON &&
            pointLat >= minOf(aLat, bLat) - BOUNDARY_EPSILON &&
            pointLat <= maxOf(aLat, bLat) + BOUNDARY_EPSILON
    }

    private const val BOUNDARY_EPSILON = 1e-9
}
