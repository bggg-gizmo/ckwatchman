package com.bggg.watchman.ui

import com.bggg.watchman.cameras.CameraClassifier
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.Route
import com.bggg.watchman.routing.RouteAnalyzer
import com.bggg.watchman.util.Geo

/** Deterministic route-aware marker reduction for readable preview and PDF maps. */
object MapDeviceDeclutter {
    const val INTERACTIVE_ROUTE_LIMIT = 140
    const val PDF_ROUTE_LIMIT = 100
    const val PDF_MULTI_ROUTE_LIMIT = 160

    fun select(route: Route, devices: Collection<CameraPoi>, limit: Int): List<CameraPoi> {
        if (limit <= 0 || devices.isEmpty() || route.points.size < 2) return emptyList()
        data class Candidate(
            val device: CameraPoi,
            val along: Double,
            val offset: Double,
            val priority: Int
        )
        fun deviceKey(device: CameraPoi): String = "${device.sourceId}:${device.sourceRecordId ?: device.id}"
        val unique = LinkedHashMap<String, CameraPoi>()
        devices.forEach { device -> unique.putIfAbsent(deviceKey(device), device) }
        val candidates = unique.values.mapNotNull { device ->
            val projection = Geo.projectOntoRoute(device.point, route.points) ?: return@mapNotNull null
            val meaningful = RouteAnalyzer.isCameraOrSensorForRouting(device)
            Candidate(
                device = device,
                along = projection.distanceAlongMeters,
                offset = projection.distanceToRouteMeters,
                priority = (if (meaningful) 10_000 else 0) + CameraClassifier.priority(device.type) * 100 + device.confidence
            )
        }
        if (candidates.size <= limit) {
            return candidates.sortedWith(compareBy<Candidate> { it.along }.thenBy { it.offset }).map { it.device }
        }

        val routeLength = Geo.routeLengthMeters(route.points).coerceAtLeast(1.0)
        val binCount = limit.coerceAtMost(80).coerceAtLeast(1)
        val bestPerBin = LinkedHashMap<Int, Candidate>()
        candidates.forEach { candidate ->
            val bin = ((candidate.along / routeLength) * binCount).toInt().coerceIn(0, binCount - 1)
            val current = bestPerBin[bin]
            if (current == null ||
                candidate.priority > current.priority ||
                (candidate.priority == current.priority && candidate.offset < current.offset) ||
                (candidate.priority == current.priority && candidate.offset == current.offset && candidate.along < current.along)
            ) bestPerBin[bin] = candidate
        }
        val selected = LinkedHashMap<String, Candidate>()
        bestPerBin.values.sortedBy { it.along }.forEach { candidate -> selected[deviceKey(candidate.device)] = candidate }
        candidates.sortedWith(
            compareByDescending<Candidate> { it.priority }
                .thenBy { it.offset }
                .thenBy { it.along }
                .thenBy { it.device.id }
        ).forEach { candidate ->
            if (selected.size < limit) selected.putIfAbsent(deviceKey(candidate.device), candidate)
        }
        return selected.values.sortedBy { it.along }.map { it.device }
    }

}