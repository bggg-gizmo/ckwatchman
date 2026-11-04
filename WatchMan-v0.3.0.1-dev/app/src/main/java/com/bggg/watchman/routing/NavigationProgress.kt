package com.bggg.watchman.routing

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.util.Geo

/** Geometry-projected maneuver progression independent of route vertex density. */
object NavigationProgress {
    private const val MANEUVER_PASS_TOLERANCE_METERS = 15.0

    fun upcomingStepIndex(route: Route, location: GeoPoint, previousStep: Int): Int {
        if (route.steps.isEmpty()) return 0
        val current = Geo.projectOntoRoute(location, route.points) ?: return previousStep.coerceIn(0, route.steps.lastIndex)
        var index = previousStep.coerceIn(0, route.steps.lastIndex)
        while (index < route.steps.lastIndex) {
            val maneuver = Geo.projectOntoRoute(route.steps[index].maneuver, route.points) ?: break
            if (maneuver.distanceAlongMeters >= current.distanceAlongMeters - MANEUVER_PASS_TOLERANCE_METERS) break
            index++
        }
        return index
    }

    fun distanceToUpcomingManeuver(route: Route, location: GeoPoint, stepIndex: Int): Double? {
        val step = route.steps.getOrNull(stepIndex) ?: return null
        return Geo.distanceAlongRouteAheadMeters(location, step.maneuver, route.points)
            ?: Geo.distanceMeters(location, step.maneuver).takeIf { it <= 2500.0 }
    }
}
