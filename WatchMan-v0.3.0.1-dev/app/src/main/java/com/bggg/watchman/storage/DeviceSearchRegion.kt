package com.bggg.watchman.storage

import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.routing.RoadSnapGeometry
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
        /** Conservative envelope of EVERY returned vertex/segment and original endpoints.
         * Dateline-spanning geometry deliberately queries all longitudes instead of omitting
         * the seam. This is a bounded candidate validation query, not full search-area evidence.
         */
        fun forOnlineRoute(route: Route, endpoints: List<GeoPoint>, haloMeters: Double): DeviceSearchRegion {
            require(route.points.isNotEmpty() && haloMeters.isFinite() && haloMeters >= 0.0)
            val points = route.points + endpoints
            require(points.all { it.lat.isFinite() && it.lat in -90.0..90.0 && it.lon.isFinite() && it.lon in -180.0..180.0 })
            val pad = Math.toDegrees(haloMeters / 6_371_000.0) * 1.02
            val south = (points.minOf { it.lat } - pad).coerceAtLeast(-90.0)
            val north = (points.maxOf { it.lat } + pad).coerceAtMost(90.0)
            val scale = kotlin.math.cos(Math.toRadians(maxOf(kotlin.math.abs(south), kotlin.math.abs(north))))
            val lonPad = if (scale <= 0.0) 180.0 else (pad / scale).coerceAtMost(180.0)
            val minLon = points.minOf { it.lon }
            val maxLon = points.maxOf { it.lon }
            val global = maxLon - minLon > 180.0 || minLon - lonPad < -180.0 || maxLon + lonPad > 180.0
            return bounds("online-candidate-v1", "Complete candidate geometry plus original endpoints and exposure halo",
                south, if (global) -180.0 else minLon - lonPad, north, if (global) 180.0 else maxLon + lonPad)
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
