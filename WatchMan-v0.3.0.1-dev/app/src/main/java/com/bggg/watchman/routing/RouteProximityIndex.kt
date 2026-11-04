package com.bggg.watchman.routing

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.util.Geo
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/**
 * Exact local segment checks backed by a coarse geographic grid. The grid only rejects distant
 * segments; every candidate segment is still evaluated with Geo.pointToSegmentMeters.
 */
class RouteProximityIndex(
    route: Route,
    private val corridorMeters: Double
) {
    private val points = route.points
    private val workingLongitudes = continuousLongitudes(points)
    private val cells = HashMap<Long, MutableList<Int>>()

    val indexedCellCount: Int get() = cells.size
    val indexedSegmentReferences: Int get() = cells.values.sumOf { it.size }

    init {
        if (points.size >= 2) {
            for (segmentIndex in 0 until points.lastIndex) indexSegment(segmentIndex)
        }
    }

    fun isWithin(point: GeoPoint): Boolean {
        if (points.isEmpty()) return false
        if (points.size == 1) return Geo.distanceMeters(point, points.first()) <= corridorMeters
        val workingLon = nearReference(point.lon, workingLongitudes.first())
        val candidates = cells[cellKey(cellIndex(point.lat), cellIndex(workingLon))] ?: return false
        val normalizedPoint = GeoPoint(point.lat, workingLon)
        return candidates.any { segmentIndex ->
            Geo.pointToSegmentMeters(
                normalizedPoint,
                GeoPoint(points[segmentIndex].lat, workingLongitudes[segmentIndex]),
                GeoPoint(points[segmentIndex + 1].lat, workingLongitudes[segmentIndex + 1])
            ) <= corridorMeters
        }
    }

    private fun indexSegment(segmentIndex: Int) {
        val a = points[segmentIndex]
        val b = points[segmentIndex + 1]
        val aLon = workingLongitudes[segmentIndex]
        val bLon = workingLongitudes[segmentIndex + 1]
        val midLat = (a.lat + b.lat) / 2.0
        val latPad = corridorMeters / METERS_PER_LAT_DEGREE
        val lonPad = corridorMeters /
            (METERS_PER_LAT_DEGREE * cos(Math.toRadians(midLat)).coerceAtLeast(MIN_LONGITUDE_SCALE))
        val southCell = cellIndex(minOf(a.lat, b.lat) - latPad)
        val northCell = cellIndex(maxOf(a.lat, b.lat) + latPad)
        val westCell = cellIndex(minOf(aLon, bLon) - lonPad)
        val eastCell = cellIndex(maxOf(aLon, bLon) + lonPad)
        val rowCount = northCell.toLong() - southCell.toLong() + 1L
        val columnCount = eastCell.toLong() - westCell.toLong() + 1L

        if (rowCount * columnCount <= MAX_CELLS_PER_SEGMENT) {
            for (row in southCell..northCell) {
                for (column in westCell..eastCell) add(row, column, segmentIndex)
            }
            return
        }

        // Defensive handling for synthetic/corrupt extremely long segments. Sampling one grid
        // interval apart plus adjacent cells covers the straight segment without filling its whole
        // rectangular bounding box.
        val steps = max(rowCount, columnCount).coerceAtMost(MAX_LONG_SEGMENT_STEPS.toLong()).toInt()
        for (step in 0..steps) {
            val fraction = if (steps == 0) 0.0 else step / steps.toDouble()
            val row = cellIndex(a.lat + (b.lat - a.lat) * fraction)
            val column = cellIndex(aLon + (bLon - aLon) * fraction)
            for (rowOffset in -1..1) {
                for (columnOffset in -1..1) add(row + rowOffset, column + columnOffset, segmentIndex)
            }
        }
    }

    private fun add(row: Int, column: Int, segmentIndex: Int) {
        val bucket = cells.getOrPut(cellKey(row, column)) { ArrayList() }
        if (bucket.lastOrNull() != segmentIndex) bucket += segmentIndex
    }

    private fun continuousLongitudes(points: List<GeoPoint>): DoubleArray {
        if (points.isEmpty()) return DoubleArray(0)
        val out = DoubleArray(points.size)
        out[0] = points[0].lon
        for (index in 1 until points.size) {
            out[index] = nearReference(points[index].lon, out[index - 1])
        }
        return out
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
        const val MAX_LONG_SEGMENT_STEPS = 20_000
        private const val METERS_PER_LAT_DEGREE = 111_320.0
        private const val MIN_LONGITUDE_SCALE = 0.15
    }
}
