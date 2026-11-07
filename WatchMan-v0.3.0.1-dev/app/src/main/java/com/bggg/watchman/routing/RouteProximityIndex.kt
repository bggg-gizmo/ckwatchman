package com.bggg.watchman.routing

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.util.Geo
import kotlin.math.cos
import kotlin.math.floor

/**
 * Exact local segment checks backed by a coarse geographic grid. The grid only rejects distant
 * segments; every candidate segment is still evaluated with Geo.pointToSegmentMeters.
 */
class RouteProximityIndex(
    route: Route,
    private val corridorMeters: Double,
    private val checkActive: () -> Unit = {}
) {
    private val points = route.points
    private val cells = HashMap<Long, MutableList<Int>>()
    private val exactSegments = ArrayList<Int>()
    private var segmentReferences = 0

    val indexedCellCount: Int get() = cells.size
    val indexedSegmentReferences: Int get() = segmentReferences

    init {
        require(corridorMeters.isFinite() && corridorMeters >= 0.0)
        points.forEachIndexed { index, point ->
            if (index % 128 == 0) checkActive()
            require(point.lat.isFinite() && point.lat in -90.0..90.0 &&
                point.lon.isFinite() && point.lon in -180.0..180.0)
        }
        if (points.size >= 2) {
            for (segmentIndex in 0 until points.lastIndex) {
                if (segmentIndex % 64 == 0) checkActive()
                indexSegment(segmentIndex)
            }
        }
    }

    fun isWithin(point: GeoPoint): Boolean {
        checkActive()
        require(point.lat.isFinite() && point.lat in -90.0..90.0 &&
            point.lon.isFinite() && point.lon in -180.0..180.0)
        if (points.isEmpty()) return false
        if (points.size == 1) return Geo.distanceMeters(point, points.first()) <= corridorMeters
        // Each segment has its own world copy. Unwrapping a whole winding route relative to
        // its first point can otherwise hide a later segment from a canonical query longitude.
        for (shift in -1..1) {
            val candidates = cells[cellKey(cellIndex(point.lat), cellIndex(point.lon + shift * 360.0))]
                ?: continue
            for ((index, segment) in candidates.withIndex()) {
                if (index % 64 == 0) checkActive()
                if (withinSegment(point, segment)) return true
            }
        }
        for ((index, segment) in exactSegments.withIndex()) {
            if (index % 64 == 0) checkActive()
            if (withinSegment(point, segment)) return true
        }
        return false
    }

    private fun withinSegment(point: GeoPoint, segment: Int): Boolean {
        val a = points[segment]
        val b = GeoPoint(points[segment + 1].lat, nearReference(points[segment + 1].lon, a.lon))
        val query = GeoPoint(point.lat, nearReference(point.lon, (a.lon + b.lon) / 2.0))
        return Geo.pointToSegmentMeters(query, a, b) <= corridorMeters
    }

    /** All exact hits, not only the nearest segment. Parallel roads, bends and repeated
     * approaches must all be represented when assessing affected-road metadata.
     */
    fun withinSegments(point: GeoPoint): List<Int> {
        checkActive()
        require(point.lat.isFinite() && point.lat in -90.0..90.0 && point.lon.isFinite() && point.lon in -180.0..180.0)
        val result = HashSet<Int>()
        for (shift in -1..1) {
            val candidates = cells[cellKey(cellIndex(point.lat), cellIndex(point.lon+shift*360.0))] ?: continue
            for ((i, segment) in candidates.withIndex()) {
                if (i % 64 == 0) checkActive()
                if (withinSegment(point, segment)) result += segment
            }
        }
        for ((i, segment) in exactSegments.withIndex()) {
            if (i % 64 == 0) checkActive()
            if (withinSegment(point, segment)) result += segment
        }
        checkActive()
        return result.sorted()
    }

    private fun indexSegment(segmentIndex: Int) {
        val a = points[segmentIndex]
        val b = points[segmentIndex + 1]
        val aLon = a.lon
        val bLon = nearReference(b.lon, aLon)
        // Same Earth radius as Geo's exact segment metric, conservatively padded for rounding.
        // Longitude distance uses QUERY latitude, so its scale must cover the entire latitude
        // halo, not just the segment midpoint. Never clamp polar scale and silently lose hits.
        val latPad = Math.toDegrees(corridorMeters / EARTH_RADIUS_METERS) + 1e-10
        val largestLatitude = maxOf(kotlin.math.abs(a.lat), kotlin.math.abs(b.lat)) + latPad
        if (largestLatitude >= 89.5) {
            exactSegments += segmentIndex
            return
        }
        val lonPad = latPad / cos(Math.toRadians(largestLatitude))
        val southCell = cellIndex(minOf(a.lat, b.lat) - latPad)
        val northCell = cellIndex(maxOf(a.lat, b.lat) + latPad)
        val westCell = cellIndex(minOf(aLon, bLon) - lonPad)
        val eastCell = cellIndex(maxOf(aLon, bLon) + lonPad)
        val rowCount = northCell.toLong() - southCell.toLong() + 1L
        val columnCount = eastCell.toLong() - westCell.toLong() + 1L

        val references = rowCount * columnCount
        if (references <= MAX_CELLS_PER_SEGMENT && segmentReferences + references <= MAX_INDEXED_REFERENCES &&
            cells.size.toLong() + references <= MAX_INDEXED_CELLS) {
            for (row in southCell..northCell) {
                for (column in westCell..eastCell) add(row, column, segmentIndex)
            }
            return
        }

        // Exact fallback caps index memory without sampling holes or silently omitting a road.
        // Cancellation/deadline checks above bound work for pathological geometry.
        exactSegments += segmentIndex
    }

    private fun add(row: Int, column: Int, segmentIndex: Int) {
        val bucket = cells.getOrPut(cellKey(row, column)) { ArrayList() }
        if (bucket.lastOrNull() != segmentIndex) {
            bucket += segmentIndex
            segmentReferences++
        }
    }

    private fun nearReference(lon: Double, reference: Double): Double {
        var adjusted = lon
        while (adjusted - reference > 180.0) adjusted -= 360.0
        while (adjusted - reference < -180.0) adjusted += 360.0
        return adjusted
    }

    private fun cellIndex(value: Double): Int = floor(value / CELL_DEGREES).toInt()

    private fun cellKey(row: Int, column: Int): Long =
        (row.toLong() shl 32) xor (column.toLong() and 0xffffffffL)

    companion object {
        const val CELL_DEGREES = 0.05
        const val MAX_CELLS_PER_SEGMENT = 256L
        const val MAX_INDEXED_REFERENCES = 500_000L
        const val MAX_INDEXED_CELLS = 65_536L
        private const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
