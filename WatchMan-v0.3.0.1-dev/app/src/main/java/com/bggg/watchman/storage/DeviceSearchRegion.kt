package com.bggg.watchman.storage

import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.routing.RoadSnapGeometry
import com.bggg.watchman.util.Geo
import java.util.Collections

/** Explicit full search-area device query, not a baseline-route corridor. Callers must cover
 * every road considered by search plus its exposure halo; this value records the declaration,
 * not a certificate of road coverage, acquisition completeness or synchronized layer epochs.
 * No coordinate rounding or meaningful-device filtering is applied by this query contract.
 */
class DeviceSearchRegion(val graphRevision: String, val evidence: String, windows: List<Window>) {
    data class Window(val south: Double, val west: Double, val north: Double, val east: Double) {
        init {
            require(listOf(south, west, north, east).all { it.isFinite() })
            require(south in -90.0..90.0 && north in south..90.0)
            require(west in -180.0..180.0 && east in west..180.0)
        }
        fun contains(point: GeoPoint) = point.lat in south..north && point.lon in west..east
        internal fun key() = listOf(south, west, north, east).joinToString(",") { java.lang.Double.toHexString(it) }
    }
    val windows: List<Window> = Collections.unmodifiableList(windows.distinct().sortedWith(
        compareBy<Window> { it.south }.thenBy { it.west }.thenBy { it.north }.thenBy { it.east }))
    val queryScope: String
    init {
        require(graphRevision.isNotBlank() && graphRevision.length <= 512)
        require(evidence.isNotBlank() && evidence.length <= 1024)
        require(windows.size in 1..16)
        queryScope = "whole-search-region-v1; graph=$graphRevision; evidence=$evidence; windows=" +
            this.windows.joinToString("|") { it.key() }
        require(queryScope.length <= 4096)
    }
    fun contains(point: GeoPoint) = windows.any { it.contains(point) }

    /** Row budgets include duplicate source rows and overlapping windows, before merging. An
     * overflow/cancellation/read failure is unavailable data, never a successful partial result.
     * A cache reader receives its remaining budget and must bound SQL before materializing rows.
     */
    fun collect(maximumRows: Int, cancelled: () -> Boolean = { false },
        read: (Window, Int) -> List<CameraPoi>): List<CameraPoi> {
        require(maximumRows in 1..500000)
        val result = ArrayList<CameraPoi>()
        for (window in windows) {
            check(!cancelled()) { "Search-region device query cancelled" }
            val rows = read(window, maximumRows - result.size)
            check(rows.size <= maximumRows - result.size) { "Search-region device row budget exceeded; partial data refused" }
            for (row in rows) {
                check(!cancelled()) { "Search-region device query cancelled" }
                check(window.contains(row.point)) { "Device cache returned a row outside its declared search window" }
                result += row
            }
        }
        check(!cancelled()) { "Search-region device query cancelled" }
        return result
    }
    companion object {
        /** Conservative windows cover EVERY candidate segment and original endpoint disk.
         * Distance partitioning avoids loading the huge empty interior of a bent long route.
         * Shared cut vertices preserve segment continuity; endpoints add disks, not invented
         * connecting roads. Dateline windows remain deliberately overinclusive. This is
         * candidate validation, NOT evidence covering the full road-graph search area.
         */
        fun forOnlineRoute(route: Route, endpoints: List<GeoPoint>, haloMeters: Double,
            checkActive: () -> Unit = {}): DeviceSearchRegion {
            require(route.points.isNotEmpty() && haloMeters.isFinite() && haloMeters >= 0.0)
            require(endpoints.size <= 2) { "Candidate validation accepts origin and destination only" }
            val points = route.points
            fun valid(point: GeoPoint) = point.lat.isFinite() && point.lat in -90.0..90.0 &&
                point.lon.isFinite() && point.lon in -180.0..180.0
            var totalMeters = 0.0
            points.forEachIndexed { index, point ->
                if (index % 128 == 0) checkActive()
                require(valid(point))
                if (index > 0) totalMeters += Geo.distanceMeters(points[index - 1], point)
            }
            require(endpoints.all(::valid) && totalMeters.isFinite())
            val result = ArrayList<Window>(16)
            val pad = Math.toDegrees(haloMeters / 6_371_000.0) * 1.02 + 1e-10
            fun envelope(source: List<GeoPoint>, first: Int, last: Int): Window {
                var minLat = source[first].lat; var maxLat = minLat
                var minLon = source[first].lon; var maxLon = minLon
                for (index in first..last) {
                    if (index % 128 == 0) checkActive()
                    minLat = minOf(minLat, source[index].lat); maxLat = maxOf(maxLat, source[index].lat)
                    minLon = minOf(minLon, source[index].lon); maxLon = maxOf(maxLon, source[index].lon)
                }
                val south = (minLat - pad).coerceAtLeast(-90.0)
                val north = (maxLat + pad).coerceAtMost(90.0)
                val scale = kotlin.math.cos(Math.toRadians(maxOf(kotlin.math.abs(south), kotlin.math.abs(north))))
                val lonPad = if (scale <= 0.0) 180.0 else (pad / scale).coerceAtMost(180.0)
                val global = maxLon - minLon > 180.0 || minLon - lonPad < -180.0 || maxLon + lonPad > 180.0
                return Window(south, if (global) -180.0 else minLon - lonPad,
                    north, if (global) 180.0 else maxLon + lonPad)
            }
            val routeWindowLimit = 16 - endpoints.size
            val chunkMeters = maxOf(1.0, totalMeters / routeWindowLimit)
            var first = 0
            var accumulated = 0.0
            for (index in 1 until points.size) {
                if (index % 128 == 0) checkActive()
                accumulated += Geo.distanceMeters(points[index - 1], points[index])
                if (accumulated >= chunkMeters && result.size < routeWindowLimit - 1 && index < points.lastIndex) {
                    result += envelope(points, first, index)
                    first = index // Both adjacent windows retain the cut vertex.
                    accumulated = 0.0
                }
            }
            result += envelope(points, first, points.lastIndex)
            endpoints.forEach { result += envelope(listOf(it), 0, 0) }
            checkActive()
            return DeviceSearchRegion("online-candidate-v2",
                "All candidate segments in bounded distance windows plus original endpoint disks and exposure halos", result)
        }

        /** Every feasible searched prefix has original-source length <= the explicit journey
         * bound. Source-path |latitude change| <= length/R. Below either pole, integrating
         * |longitude change| <= length/(R*cos(max absolute latitude)) bounds the unwrapped
         * longitudes; queryBoxes uses that conservative rectangle, not only a spherical disk.
         * Geo's planar segment projection stays inside its endpoints' coordinate ranges.
         * Its query-latitude halo adds H/R_geo latitude and H/(R_geo*cos(queryLatitude))
         * longitude. Scaling H by R/R_geo therefore covers the planar metric with the same
         * expanded rectangle. Pole-reaching bounds use all longitudes. Exposure must retain
         * the original shortest-longitude unwrapping used by RoadDeviceExposureIndex.
         * Source lengths are nearest-mm; cumulative partial rounding adds at most another mm.
         * Two mm per generated transition bounds that error; 2m covers numeric/E7 envelopes.
         * Only use with the independently validated ORIGINAL source-length graph format.
         */
        fun forSearch(graphRevision: String, origin: RoadSnapGeometry.Point,
            maximumDistanceMm: Long, maximumGenerated: Int, exposureHaloMetres: Double): DeviceSearchRegion {
            require(maximumDistanceMm >= 0 && maximumGenerated > 0)
            require(exposureHaloMetres.isFinite() && exposureHaloMetres >= 0.0)
            val sourceRadiusHalo = exposureHaloMetres * (RoadSnapGeometry.EARTH_METRES / 6_371_000.0)
            val radius = maximumDistanceMm / 1000.0 + maximumGenerated * 0.002 + sourceRadiusHalo + 2.0
            val boxes = RoadSnapGeometry.queryBoxes(origin, radius.coerceAtMost(Math.PI * RoadSnapGeometry.EARTH_METRES))
            return DeviceSearchRegion(graphRevision,
                "Original-source bounded path and planar-halo envelope; distanceMm=$maximumDistanceMm; generated=$maximumGenerated; haloM=$exposureHaloMetres; haloRadius6371000; rounding2mm/transition+2m",
                boxes.map { Window(it.minLatE7 / 1e7, it.minLonE7 / 1e7, it.maxLatE7 / 1e7, it.maxLonE7 / 1e7) })
        }

        /** west>east explicitly crosses the antimeridian. Include both signed boundary edges. */
        fun bounds(graphRevision: String, evidence: String, south: Double, west: Double,
            north: Double, east: Double): DeviceSearchRegion {
            require(west.isFinite() && east.isFinite() && west in -180.0..180.0 && east in -180.0..180.0)
            val windows = if (west <= east) listOf(Window(south, west, north, east)) else
                listOf(Window(south, west, north, 180.0), Window(south, -180.0, north, east))
            return DeviceSearchRegion(graphRevision, evidence, windows)
        }
    }
}
