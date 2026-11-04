package com.bggg.watchman.routing

import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.util.Geo
import kotlin.math.cos
import kotlin.math.sin

/**
 * Produces bounded OSRM via-point candidates that make the route-mode labels literal:
 * No cams requests road routes around encountered devices; Most cams requests routes through
 * known devices; Scenic requests longer side-of-corridor alternatives.
 */
object RouteShaper {
    const val MAX_VIA_POINTS = 3
    const val AROUND_ENCOUNTER_CORRIDOR_METERS = 240.0
    const val THROUGH_SEARCH_CORRIDOR_METERS = 8_000.0
    private const val ENDPOINT_EXCLUSION_METERS = 800.0
    private const val AROUND_OFFSET_METERS = 650.0
    private const val AROUND_MIN_SPACING_METERS = 1_200.0
    private const val THROUGH_MIN_SPACING_METERS = 2_000.0

    fun aroundWaypointSets(routes: List<Route>, cameras: Collection<CameraPoi>): List<List<GeoPoint>> {
        val baseline = routes.minWithOrNull(
            compareBy<Route> { RouteAnalyzer.countKnownCameras(it, cameras, AROUND_ENCOUNTER_CORRIDOR_METERS) }
                .thenBy { it.durationSeconds }
        ) ?: return emptyList()
        if (baseline.points.size < 2) return emptyList()

        val length = Geo.routeLengthMeters(baseline.points)
        val encountered = cameras.mapNotNull { camera ->
            if (!RouteAnalyzer.isCameraOrSensorForRouting(camera)) return@mapNotNull null
            val projection = Geo.projectOntoRoute(camera.point, baseline.points) ?: return@mapNotNull null
            if (projection.distanceToRouteMeters > AROUND_ENCOUNTER_CORRIDOR_METERS) return@mapNotNull null
            if (projection.distanceAlongMeters < ENDPOINT_EXCLUSION_METERS) return@mapNotNull null
            if (length - projection.distanceAlongMeters < ENDPOINT_EXCLUSION_METERS) return@mapNotNull null
            camera to projection
        }.sortedBy { it.second.distanceAlongMeters }

        val selected = ArrayList<Pair<CameraPoi, Geo.RouteProjection>>()
        encountered.forEach { candidate ->
            if (selected.size >= MAX_VIA_POINTS) return@forEach
            if (selected.none { previous ->
                    kotlin.math.abs(previous.second.distanceAlongMeters - candidate.second.distanceAlongMeters) <
                        AROUND_MIN_SPACING_METERS
                }
            ) {
                selected += candidate
            }
        }
        if (selected.isEmpty()) return emptyList()

        val left = selected.map { (_, projection) -> perpendicularWaypoint(baseline, projection, -90.0) }
        val right = selected.map { (_, projection) -> perpendicularWaypoint(baseline, projection, 90.0) }
        return listOf(left, right).filter { set -> set.all { it.lat.isFinite() && it.lon.isFinite() } }
    }

    fun throughWaypointSets(routes: List<Route>, cameras: Collection<CameraPoi>): List<List<GeoPoint>> {
        val baseline = routes.minByOrNull { it.durationSeconds } ?: return emptyList()
        if (baseline.points.size < 2) return emptyList()
        val length = Geo.routeLengthMeters(baseline.points)

        val candidates = cameras.mapNotNull { camera ->
            if (!RouteAnalyzer.isCameraOrSensorForRouting(camera)) return@mapNotNull null
            val projection = Geo.projectOntoRoute(camera.point, baseline.points) ?: return@mapNotNull null
            if (projection.distanceToRouteMeters > THROUGH_SEARCH_CORRIDOR_METERS) return@mapNotNull null
            if (projection.distanceAlongMeters < ENDPOINT_EXCLUSION_METERS) return@mapNotNull null
            if (length - projection.distanceAlongMeters < ENDPOINT_EXCLUSION_METERS) return@mapNotNull null
            Triple(camera, projection, cameraPriority(camera))
        }.sortedWith(
            compareByDescending<Triple<CameraPoi, Geo.RouteProjection, Int>> { it.third }
                .thenByDescending { it.second.distanceToRouteMeters }
                .thenBy { it.second.distanceAlongMeters }
        )

        val selected = ArrayList<Triple<CameraPoi, Geo.RouteProjection, Int>>()
        candidates.forEach { candidate ->
            if (selected.size >= MAX_VIA_POINTS) return@forEach
            if (selected.none { previous ->
                    kotlin.math.abs(previous.second.distanceAlongMeters - candidate.second.distanceAlongMeters) <
                        THROUGH_MIN_SPACING_METERS
                }
            ) {
                selected += candidate
            }
        }
        if (selected.isEmpty()) return emptyList()

        val ordered = selected.sortedBy { it.second.distanceAlongMeters }.map { it.first.point }
        val singles = selected.take(2).map { listOf(it.first.point) }
        return (listOf(ordered) + singles).distinct().take(3)
    }

    fun scenicWaypointSets(origin: GeoPoint, destination: GeoPoint): List<List<GeoPoint>> {
        val direct = Geo.distanceMeters(origin, destination)
        if (direct < 2_000.0) return emptyList()
        val midpoint = GeoPoint(
            lat = (origin.lat + destination.lat) / 2.0,
            lon = (origin.lon + destination.lon) / 2.0
        )
        val offset = (direct * 0.30).coerceIn(1_500.0, 25_000.0)
        val bearing = Geo.bearingDegrees(origin, destination)
        return listOf(
            listOf(destinationPoint(midpoint, bearing - 90.0, offset)),
            listOf(destinationPoint(midpoint, bearing + 90.0, offset))
        )
    }

    private fun perpendicularWaypoint(
        route: Route,
        projection: Geo.RouteProjection,
        bearingOffset: Double
    ): GeoPoint {
        val index = projection.segmentIndex.coerceIn(0, route.points.lastIndex - 1)
        val bearing = Geo.bearingDegrees(route.points[index], route.points[index + 1])
        return destinationPoint(projection.point, bearing + bearingOffset, AROUND_OFFSET_METERS)
    }

    private fun destinationPoint(origin: GeoPoint, bearingDegrees: Double, distanceMeters: Double): GeoPoint {
        val angularDistance = distanceMeters / 6_371_000.0
        val bearing = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(origin.lat)
        val lon1 = Math.toRadians(origin.lon)
        val lat2 = kotlin.math.asin(
            sin(lat1) * cos(angularDistance) +
                cos(lat1) * sin(angularDistance) * cos(bearing)
        )
        val lon2 = lon1 + kotlin.math.atan2(
            sin(bearing) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )
        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    private fun cameraPriority(camera: CameraPoi): Int = when (camera.type) {
        CameraType.ALPR, CameraType.FLOCK -> 10
        CameraType.RED_LIGHT, CameraType.SPEED -> 9
        CameraType.ACOUSTIC, CameraType.RF_SENSOR, CameraType.SURVEILLANCE -> 8
        CameraType.HIGHWAY -> 7
        CameraType.SENSOR -> 6
        CameraType.TRAFFIC, CameraType.TOLL -> 4
        CameraType.OTHER -> 3
    }
}
