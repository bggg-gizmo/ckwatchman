package com.bggg.watchman.routing

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.storage.RoutingDeviceSnapshot
import com.bggg.watchman.util.Geo
import kotlin.math.abs
import kotlin.math.cos

/** Existing 180m route-analysis metric on exact source-bound portions. No endpoint exclusion,
 * bearing exclusion or manufacturer-based suppression is introduced here. Direction affects
 * the travelled geometry, not an undocumented reduction of the validated device population.
 */
class RoadDeviceExposureIndex(val snapshot: RoutingDeviceSnapshot, val graphRevision: String,
                              private val maximumCandidates: Int = 50_000) {
    data class Geometry(val graphRevision: String, val arcId: Long, val direction: MotorcarTagPolicy.Direction,
                        val from: RoadSnapGeometry.Point, val to: RoadSnapGeometry.Point) {
        init { require(graphRevision.isNotBlank() && arcId > 0) }
    }
    private val entries = snapshot.entries.filter { it.weight > 0 }.sortedBy { it.device.point.lat }
    init { require(graphRevision.isNotBlank() && maximumCandidates > 0) }

    fun exposures(geometry: Geometry, portion: RoadArcPortion): List<NoCamsGraphSearch.Exposure> {
        require(geometry.graphRevision == graphRevision) { "Exposure geometry source changed" }
        require(geometry.arcId == portion.arcId && geometry.direction == portion.direction) { "Exposure portion has wrong original arc" }
        val start = RoadSnapGeometry.pointAt(geometry.from, geometry.to, portion.fromFraction)
        val end = RoadSnapGeometry.pointAt(geometry.from, geometry.to, portion.toFraction)
        val latPad = Math.toDegrees(snapshot.corridorMeters / 6_371_000.0)
        val south = minOf(start.latitude, end.latitude) - latPad - 1e-10
        val north = maxOf(start.latitude, end.latitude) + latPad + 1e-10
        // Conservative for Geo's query-latitude metric, including polar/dateline portions.
        val latitudeLimit = maxOf(abs(south), abs(north)).coerceAtMost(90.0)
        val lonPad = latPad / cos(Math.toRadians(latitudeLimit)).coerceAtLeast(1e-12)
        val aLon = start.longitude
        val bLon = near(end.longitude, aLon)
        val reference = (aLon + bLon) / 2.0
        val a = GeoPoint(start.latitude, aLon); val b = GeoPoint(end.latitude, bLon)
        var low = 0; var high = entries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (entries[mid].device.point.lat < south) low = mid + 1 else high = mid
        }
        val result = ArrayList<NoCamsGraphSearch.Exposure>()
        var visited = 0
        var index = low
        while (index < entries.size && entries[index].device.point.lat <= north) {
            check(++visited <= maximumCandidates) { "Exposure candidates exceed explicit budget; none may be truncated" }
            val entry = entries[index++]
            val lon = near(entry.device.point.lon, reference)
            if (lon < minOf(aLon, bLon) - lonPad - 1e-10 || lon > maxOf(aLon, bLon) + lonPad + 1e-10) continue
            if (Geo.pointToSegmentMeters(GeoPoint(entry.device.point.lat, lon), a, b) <= snapshot.corridorMeters) {
                result += NoCamsGraphSearch.Exposure(entry.logicalId, entry.weight)
            }
        }
        return result.sortedBy { it.deviceId }
    }

    private fun near(longitude: Double, reference: Double): Double {
        var value = longitude
        while (value - reference > 180.0) value -= 360.0
        while (value - reference < -180.0) value += 360.0
        return value
    }
}
