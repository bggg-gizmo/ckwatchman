package com.bggg.watchman.routing

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.storage.RoutingDeviceSnapshot
import com.bggg.watchman.util.Geo

/** Run on the snapshot worker. A passed camera must not provoke a diversion backwards.
 * Off-route recovery stays with the existing GPS hysteresis rather than this cache event.
 */
object NoCamsRouteMonitor {
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
