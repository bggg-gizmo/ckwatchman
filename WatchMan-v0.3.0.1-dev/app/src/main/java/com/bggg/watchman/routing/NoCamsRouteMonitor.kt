package com.bggg.watchman.routing

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.storage.RoutingDeviceSnapshot
import com.bggg.watchman.util.Geo

/** Run on the snapshot worker. A passed camera must not provoke a diversion backwards.
 * Off-route recovery stays with the existing GPS hysteresis rather than this cache event.
 */
object NoCamsRouteMonitor {
    data class Decision(val obstacleAhead: Boolean, val coverageUnknown: Boolean)

    /** Expired/missing ordinary-signal attribution is uncertainty, not proof of a new
     * encounter on the locked path. Warn/refresh rather than replacing exact guidance.
     * Actual affected-road hits and other device families still request strict diversion.
     */
    fun decision(assessment: RoadAwareExposure.Assessment): Decision {
        val verified=assessment.roadEncounters.mapTo(HashSet()) { it.device }
        val uncertain=assessment.encounters.filter { RoadAwareExposure.mayUseRoadCoverage(it) && it !in verified }
        return Decision(assessment.encounters.size>uncertain.size,uncertain.isNotEmpty())
    }

    fun remainingRoute(route: Route, position: GeoPoint, checkActive: () -> Unit = {}): Route? {
        val projection = Geo.projectOntoRoute(position, route.points, checkActive) ?: return null
        if (projection.distanceToRouteMeters > 90.0) return null
        return route.copy(points = listOf(projection.point) + route.points.drop(projection.segmentIndex + 1), requestedOrigin = position)
    }

    fun hasObstacle(remaining: Route, snapshot: RoutingDeviceSnapshot, checkActive: () -> Unit = {}): Boolean {
        val proximity = RouteProximityIndex(remaining, snapshot.corridorMeters, checkActive)
        return snapshot.entries.any { checkActive(); it.weight > 0 && (proximity.isWithin(it.device.point) ||
            RouteAnalyzer.overlapsRequestedEndpoint(it.device, remaining, snapshot.corridorMeters)) }
    }
}
