package com.bggg.watchman.routing

import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.data.SearchResult
import com.bggg.watchman.storage.DeviceIdentity
import com.bggg.watchman.storage.RoutingDeviceSnapshot
import com.bggg.watchman.util.Geo

/** Online road-graph exclusions, never route-via shaping. Every returned path is checked against
 * all three observed cache layers, including its endpoints. This does not certify acquisition
 * completeness, local/offline graph readiness or unavoidable nonzero exposure. This adapter
 * supplies no access/unavoidability certificate, so it returns only zero observed encounters.
 * Endpoint proximity, backend failure and resource exhaustion never grant an exception.
 */
class NoCamsObstaclePlanner(
    private val router: (GeoPoint, SearchResult, Exclusions, Long) -> Route,
    private val readDevices: (Route) -> RoutingDeviceSnapshot,
    private val acquire: (Route, Long) -> Unit = { _, _ -> },
    private val now: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    data class Exclusions(val devices: List<CameraPoi>, val encounteredRoadPoints: List<GeoPoint>)
    data class Planned(val route: Route, val snapshot: RoutingDeviceSnapshot,
        val requests: Int, val excludedDevices: Int)

    fun plan(origin: GeoPoint, destination: SearchResult, startedAt: Long = now(),
        cancelled: () -> Boolean = { false }): Planned {
        val obstacles = LinkedHashMap<String, CameraPoi>()
        val blockedRoads = LinkedHashMap<Pair<Long, Long>, GeoPoint>()
        var baseline: Route? = null
        fun remaining(): Long {
            check(!cancelled() && !Thread.currentThread().isInterrupted) { "No Cams planning cancelled" }
            val elapsed = now() - startedAt
            check(elapsed >= 0L && elapsed < RoutePlanningPolicy.HARD_DEADLINE_MS) {
                "No Cams road search reached its deadline; no unchecked route published"
            }
            return RoutePlanningPolicy.HARD_DEADLINE_MS - elapsed
        }
        repeat(MAX_REQUESTS) { attempt ->
            val route = router(origin, destination, Exclusions(obstacles.values.toList(), blockedRoads.values.toList()), remaining())
                .copy(requestedOrigin = origin)
            remaining()
            validateRoute(route, origin, destination)
            if (baseline == null) {
                baseline = route
                // Acquisition owns its priority lease and must release it even on timeout/failure.
                acquire(route, minOf(4_000L, (remaining() - 10_000L).coerceAtLeast(0L)))
                remaining()
            }
            val initial = checkNotNull(baseline)
            check(route.distanceMeters <= maxOf(initial.distanceMeters * 3.0, initial.distanceMeters + 10_000.0) &&
                route.durationSeconds <= maxOf(initial.durationSeconds * 3.0, initial.durationSeconds + 1_800.0)) {
                "No Cams detour exceeds the explicit distance/time bounds"
            }
            fun encounters(snapshot: RoutingDeviceSnapshot): List<CameraPoi> {
                val proximity = RouteProximityIndex(route, snapshot.corridorMeters) { remaining(); Unit }
                return snapshot.entries.filter { remaining(); it.weight > 0 && (proximity.isWithin(it.device.point) ||
                    Geo.distanceMeters(origin, it.device.point) <= snapshot.corridorMeters ||
                    Geo.distanceMeters(destination.point, it.device.point) <= snapshot.corridorMeters) }.map { it.device }
            }
            var snapshot = readDevices(route)
            remaining()
            var hits = encounters(snapshot)
            if (hits.isEmpty()) {
                // Re-read after analysis: acquisition may have committed while the route was checked.
                snapshot = readDevices(route)
                remaining()
                hits = encounters(snapshot)
                if (hits.isEmpty()) return Planned(route, snapshot, attempt + 1, obstacles.size)
            }
            // Keep all known exclusions accumulated across detours. Never truncate to a few clusters.
            var changed = false
            for (camera in hits) {
                remaining()
                val key = DeviceIdentity.logicalKey(camera)
                if (obstacles[key] != camera) { obstacles[key] = camera; changed = true }
                // Exclude the encountered road, not whichever parallel road is nearest the camera.
                val projection = Geo.projectOntoRoute(camera.point, route.points) { remaining(); Unit }
                    ?: error("Encountered road could not be projected")
                remaining()
                val a = route.points[projection.segmentIndex]
                val b = route.points[projection.segmentIndex + 1]
                val length = Geo.distanceMeters(a, b)
                val inset = if (length > 0.0) minOf(0.5, 5.0 / length) else 0.0
                val fraction = projection.fraction.coerceIn(inset, 1.0 - inset)
                val point = GeoPoint(a.lat + (b.lat - a.lat) * fraction, a.lon + (b.lon - a.lon) * fraction)
                val roadKey = Math.round(point.lat * 1e7) to Math.round(point.lon * 1e7)
                if (blockedRoads.putIfAbsent(roadKey, point) == null) changed = true
            }
            check(changed) { "Road provider still crosses excluded devices; no camera-crossing fallback published" }
            check(obstacles.size <= MAX_OBSTACLES) {
                "No Cams provider exclusion budget exceeded; a larger graph backend is required"
            }
            check(blockedRoads.size <= MAX_ROAD_BLOCKS) { "No Cams encountered-road exclusion budget exceeded" }
        }
        error("No Cams road search exhausted its bounded requests; zero exposure is not established")
    }

    private fun validateRoute(route: Route, origin: GeoPoint, destination: SearchResult) {
        check(route.destination == destination && route.points.size in 2..MAX_ROUTE_POINTS && route.steps.isNotEmpty()) {
            "Incomplete road route returned"
        }
        check(route.points.all { it.lat.isFinite() && it.lat in -90.0..90.0 && it.lon.isFinite() && it.lon in -180.0..180.0 } &&
            route.distanceMeters.isFinite() && route.distanceMeters >= 0.0 &&
            route.durationSeconds.isFinite() && route.durationSeconds >= 0.0) { "Invalid road route returned" }
        check(Geo.distanceMeters(origin, route.points.first()) <= MAX_ENDPOINT_SNAP_METERS &&
            Geo.distanceMeters(destination.point, route.points.last()) <= MAX_ENDPOINT_SNAP_METERS) {
            "Road route moved an endpoint outside the allowed snap distance"
        }
    }

    companion object {
        const val MAX_OBSTACLES = 512 // Client work bound; wire geometry has separate backend budgets.
        const val MAX_ROAD_BLOCKS = 128
        const val MAX_REQUESTS = 8 // Network/fair-use bound, not a limit on inspected devices or route length.
        const val MAX_ROUTE_POINTS = 250_000
        const val MAX_ENDPOINT_SNAP_METERS = 100.0
    }
}
