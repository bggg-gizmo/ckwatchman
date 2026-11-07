package com.bggg.watchman.routing

import com.bggg.watchman.data.GeoPoint
import kotlin.math.abs

/** Exact union boundary of convex exclusion rings, with sub-millimetre vertex stitching.
 * Covered edge intervals are removed, not cameras. This reduces duplicated perimeter in
 * dense clusters without replacing a cluster by a hull that could close a camera-free gap.
 * The provider accepts exterior rings only: holes, degenerate/touching junctions or numeric
 * ambiguity retain the original rings rather than silently fill a hole or lose an exclusion.
 */
object CameraExclusionUnion {
    private data class Key(val x: Long, val y: Long)
    private data class Edge(val a: Key, val b: Key)
    private data class Interval(val start: Double, val end: Double)
    private data class Box(val west: Double, val south: Double, val east: Double, val north: Double) {
        fun overlaps(other: Box) = west <= other.east && east >= other.west && south <= other.north && north >= other.south
    }

    fun merge(rings: List<List<GeoPoint>>, cancelled: () -> Boolean = { false }): List<List<GeoPoint>> {
        require(rings.size <= NoCamsObstaclePlanner.MAX_OBSTACLES)
        if (rings.isEmpty()) return emptyList()
        val polygons = rings.map { ring ->
            require(ring.size in 4..65 && ring.first() == ring.last())
            val polygon = ring.dropLast(1)
            require(polygon.all { it.lat.isFinite() && it.lon.isFinite() })
            require(area(polygon) > 0.0) { "Exclusions must be counterclockwise convex rings" }
            require(polygon.indices.all { i -> cross(polygon[i], polygon[(i + 1) % polygon.size],
                polygon[(i + 2) % polygon.size]) >= -EPS }) { "Exclusion input is not convex" }
            polygon
        }.distinct()
        val original = polygons.map { it + it.first() }
        if (polygons.size == 1) return original
        val boxes = polygons.map { p -> Box(p.minOf { it.lon }, p.minOf { it.lat }, p.maxOf { it.lon }, p.maxOf { it.lat }) }
        val edges = ArrayList<Edge>()
        for ((owner, polygon) in polygons.withIndex()) {
            check(!cancelled()) { "Camera exclusion union cancelled or deadline reached" }
            for (i in polygon.indices) {
                check(!cancelled()) { "Camera exclusion union cancelled or deadline reached" }
                val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
                val covered = ArrayList<Interval>()
                for ((otherIndex, other) in polygons.withIndex()) {
                    if (otherIndex == owner || !boxes[owner].overlaps(boxes[otherIndex])) continue
                    val interval = clip(a, b, other) ?: continue
                    val mid = interpolate(a, b, (interval.start + interval.end) / 2.0)
                    var interior = true
                    var sameBoundary = false
                    var oppositeBoundary = false
                    for (j in other.indices) {
                        val c = other[j]; val d = other[(j + 1) % other.size]
                        if (abs(cross(c, d, mid)) <= EPS) {
                            interior = false
                            val dot = (b.lon - a.lon) * (d.lon - c.lon) + (b.lat - a.lat) * (d.lat - c.lat)
                            if (dot > 0.0) sameBoundary = true else oppositeBoundary = true
                        }
                    }
                    if (interior || oppositeBoundary || (sameBoundary && otherIndex < owner)) covered += interval
                }
                var cursor = 0.0
                fun add(start: Double, end: Double) {
                    if (end - start <= PARAM_EPS) return
                    val from = key(interpolate(a, b, start)); val to = key(interpolate(a, b, end))
                    if (from != to) edges += Edge(from, to)
                }
                for (interval in covered.sortedBy { it.start }) {
                    if (interval.start > cursor) add(cursor, interval.start)
                    cursor = maxOf(cursor, interval.end)
                }
                add(cursor, 1.0)
            }
        }
        if (edges.isEmpty()) return original
        val outgoing = edges.groupBy { it.a }
        val incoming = edges.groupBy { it.b }
        // A touching junction can have multiple valid contours; do not invent a traversal.
        if (outgoing.values.any { it.size != 1 } || incoming.values.any { it.size != 1 } || outgoing.keys != incoming.keys) return original
        val unused = edges.toMutableSet()
        val result = ArrayList<List<GeoPoint>>()
        while (unused.isNotEmpty()) {
            check(!cancelled()) { "Camera exclusion union cancelled or deadline reached" }
            val first = unused.first()
            val ring = ArrayList<GeoPoint>()
            var edge = first
            do {
                if (!unused.remove(edge) || ring.size > edges.size) return original
                ring += point(edge.a)
                edge = outgoing.getValue(edge.b).single()
            } while (edge != first)
            if (ring.size < 3 || area(ring) <= 0.0) return original // Hole rings must not be filled.
            result += ring + ring.first()
        }
        return result
    }

    /** Segment interval inside a CCW convex polygon, obtained by clipping its halfplanes. */
    private fun clip(a: GeoPoint, b: GeoPoint, polygon: List<GeoPoint>): Interval? {
        var start = 0.0; var end = 1.0
        for (i in polygon.indices) {
            val c = polygon[i]; val d = polygon[(i + 1) % polygon.size]
            val atStart = cross(c, d, a)
            val slope = (d.lon - c.lon) * (b.lat - a.lat) - (d.lat - c.lat) * (b.lon - a.lon)
            if (abs(slope) <= EPS) {
                if (atStart < -EPS) return null
            } else {
                val boundary = -atStart / slope
                if (slope > 0.0) start = maxOf(start, boundary) else end = minOf(end, boundary)
            }
            if (end - start <= PARAM_EPS) return null
        }
        return Interval(start.coerceIn(0.0, 1.0), end.coerceIn(0.0, 1.0)).takeIf { it.end - it.start > PARAM_EPS }
    }

    private fun cross(a: GeoPoint, b: GeoPoint, p: GeoPoint) =
        (b.lon - a.lon) * (p.lat - a.lat) - (b.lat - a.lat) * (p.lon - a.lon)
    private fun interpolate(a: GeoPoint, b: GeoPoint, t: Double) =
        GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    private fun key(p: GeoPoint) = Key(Math.round(p.lon * SCALE), Math.round(p.lat * SCALE))
    private fun point(k: Key) = GeoPoint(k.y / SCALE, k.x / SCALE)
    private fun area(polygon: List<GeoPoint>): Double {
        val anchor = polygon.first()
        return polygon.indices.sumOf { i ->
            val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
            (a.lon - anchor.lon) * (b.lat - anchor.lat) - (a.lat - anchor.lat) * (b.lon - anchor.lon)
        } / 2.0
    }
    private const val EPS = 1e-16
    private const val PARAM_EPS = 1e-10
    private const val SCALE = 1e9
}
