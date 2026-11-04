package com.bggg.watchman.export

import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.RoutePreference
import com.bggg.watchman.history.CameraEncounter
import com.bggg.watchman.history.RouteAnalyticsSnapshot
import com.bggg.watchman.history.RouteHistoryEntry
import com.bggg.watchman.util.Geo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AnalyticsDay(
    val key: String,
    val label: String,
    val trips: Int,
    val distanceMeters: Double,
    val encounters: Int
)

data class AnalyticsRouteFrequency(
    val representative: RouteHistoryEntry,
    val tripCount: Int,
    val actualDistanceMeters: Double
)

data class AnalyticsCameraFrequency(
    val cameraId: String,
    val type: CameraType,
    val name: String?,
    val point: GeoPoint,
    val source: String,
    val passCount: Int
)

data class RouteAnalyticsReport(
    val snapshot: RouteAnalyticsSnapshot,
    val totalTrips: Int,
    val completedTrips: Int,
    val totalDistanceMeters: Double,
    val totalDurationMillis: Long,
    val trackedTripCount: Int,
    val actualCameraPasses: Int,
    val knownCameraCountAtTripStart: Int,
    val routeFrequencies: List<AnalyticsRouteFrequency>,
    val cameraFrequencies: List<AnalyticsCameraFrequency>,
    val cameraTypeCounts: Map<CameraType, Int>,
    val modeCounts: Map<RoutePreference, Int>,
    val days: List<AnalyticsDay>,
    val paths: Map<Long, List<GeoPoint>>
)

object RouteAnalyticsEngine {
    fun analyze(snapshot: RouteAnalyticsSnapshot): RouteAnalyticsReport {
        val entries = snapshot.entries
        val paths = LinkedHashMap<Long, List<GeoPoint>>()
        var totalDistance = 0.0
        var tracked = 0
        entries.forEach { entry ->
            val track = snapshot.tracks[entry.id].orEmpty().map { it.point }
            val path = if (track.size >= 2) {
                tracked++
                downsample(track, 1400)
            } else {
                runCatching { downsample(entry.toRoute().points, 1400) }.getOrDefault(emptyList())
            }
            if (path.isNotEmpty()) paths[entry.id] = path
            totalDistance += if (track.size >= 2) Geo.routeLengthMeters(track) else entry.distanceMeters
        }

        val routeFrequencies = entries.groupBy { it.routeKey }.map { (_, trips) ->
            val representative = trips.maxByOrNull { it.startedAt } ?: trips.first()
            val distance = trips.sumOf { e ->
                val track = snapshot.tracks[e.id].orEmpty().map { it.point }
                if (track.size >= 2) Geo.routeLengthMeters(track) else e.distanceMeters
            }
            AnalyticsRouteFrequency(representative, trips.size, distance)
        }.sortedWith(compareByDescending<AnalyticsRouteFrequency> { it.tripCount }.thenByDescending { it.representative.startedAt })

        val cameraFrequencies = snapshot.encounters.groupBy { it.cameraId }.map { (_, items) ->
            val representative = items.maxByOrNull { it.encounteredAt } ?: items.first()
            AnalyticsCameraFrequency(
                cameraId = representative.cameraId,
                type = representative.cameraType,
                name = representative.name,
                point = representative.point,
                source = representative.source,
                passCount = items.size
            )
        }.sortedWith(compareByDescending<AnalyticsCameraFrequency> { it.passCount }.thenBy { it.type.label })

        val cameraTypeCounts = snapshot.encounters.groupingBy { it.cameraType }.eachCount().toList()
            .sortedByDescending { it.second }.toMap(LinkedHashMap())
        val modeCounts = entries.groupingBy { it.preference }.eachCount().toList()
            .sortedByDescending { it.second }.toMap(LinkedHashMap())

        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val labelFmt = SimpleDateFormat("MMM d", Locale.US)
        val encounterByDay = snapshot.encounters.groupingBy { dayFmt.format(Date(it.encounteredAt)) }.eachCount()
        val days = entries.groupBy { dayFmt.format(Date(it.startedAt)) }.map { (key, trips) ->
            val distance = trips.sumOf { e ->
                val track = snapshot.tracks[e.id].orEmpty().map { it.point }
                if (track.size >= 2) Geo.routeLengthMeters(track) else e.distanceMeters
            }
            AnalyticsDay(
                key = key,
                label = labelFmt.format(Date(trips.first().startedAt)),
                trips = trips.size,
                distanceMeters = distance,
                encounters = encounterByDay[key] ?: 0
            )
        }.sortedBy { it.key }

        val duration = entries.sumOf { e ->
            val end = e.endedAt
            if (end != null && end > e.startedAt) end - e.startedAt else (e.durationSeconds * 1000.0).toLong()
        }

        return RouteAnalyticsReport(
            snapshot = snapshot,
            totalTrips = entries.size,
            completedTrips = entries.count { it.endedAt != null },
            totalDistanceMeters = totalDistance,
            totalDurationMillis = duration,
            trackedTripCount = tracked,
            actualCameraPasses = snapshot.encounters.size,
            knownCameraCountAtTripStart = entries.sumOf { it.cameraCount },
            routeFrequencies = routeFrequencies,
            cameraFrequencies = cameraFrequencies,
            cameraTypeCounts = cameraTypeCounts,
            modeCounts = modeCounts,
            days = days,
            paths = paths
        )
    }

    private fun downsample(points: List<GeoPoint>, maxPoints: Int): List<GeoPoint> {
        if (points.size <= maxPoints) return points
        val step = points.size.toDouble() / maxPoints
        val out = ArrayList<GeoPoint>(maxPoints + 1)
        var i = 0.0
        while (i < points.size) {
            out += points[i.toInt().coerceIn(0, points.lastIndex)]
            i += step
        }
        if (out.lastOrNull() != points.last()) out += points.last()
        return out
    }
}
