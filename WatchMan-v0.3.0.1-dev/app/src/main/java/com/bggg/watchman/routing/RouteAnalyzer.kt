package com.bggg.watchman.routing

import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.Route
import com.bggg.watchman.util.Geo

/** Deterministic route/camera analysis kept outside Android UI code for testing and updates. */
object RouteAnalyzer {
    const val DEFAULT_CORRIDOR_METERS = 180.0
    fun countKnownCameras(route: Route, cameras: Collection<CameraPoi>, corridorMeters: Double = DEFAULT_CORRIDOR_METERS): Int {
        if (route.points.isEmpty() || cameras.isEmpty()) return 0
        val proximity = RouteProximityIndex(route, corridorMeters)
        return cameras.count { camera ->
            if (!isCameraOrSensorForRouting(camera)) return@count false
            proximity.isWithin(camera.point) || overlapsRequestedEndpoint(camera, route, corridorMeters)
        }
    }

    /** Retain observed endpoint-area devices after road snapping. This is accounting, NOT an
     * unavoidability certificate: a geocoded facility point is not a proven vehicle entrance.
     * Legacy routes without endpoint metadata keep their previous geometric accounting.
     */
    fun overlapsRequestedEndpoint(camera: CameraPoi, route: Route, corridorMeters: Double = DEFAULT_CORRIDOR_METERS): Boolean {
        val origin = route.requestedOrigin ?: return false
        return Geo.distanceMeters(origin, camera.point) <= corridorMeters ||
            Geo.distanceMeters(route.destination.point, camera.point) <= corridorMeters
    }

    /**
     * Preserve the user-validated SafeTOC traffic-device population in encounter analysis.
     * The corrective release requirements supersede the earlier bare-signal exclusion: do not
     * reduce encounter counts by suppressing TRAFFIC records instead of routing around them.
     * Toll-only and unknown infrastructure still need relevant device evidence.
     */
    fun isCameraOrSensorForRouting(camera: CameraPoi): Boolean {
        return when (camera.type) {
            CameraType.ALPR,
            CameraType.FLOCK,
            CameraType.RED_LIGHT,
            CameraType.SPEED,
            CameraType.HIGHWAY,
            CameraType.ACOUSTIC,
            CameraType.RF_SENSOR,
            CameraType.SURVEILLANCE,
            CameraType.SENSOR,
            CameraType.TRAFFIC -> true
            CameraType.TOLL,
            CameraType.OTHER -> hasDeviceEvidence(camera)
        }
    }

    private fun hasDeviceEvidence(camera: CameraPoi): Boolean {
        if (!camera.manufacturer.isNullOrBlank() || !camera.operator.isNullOrBlank()) return true
        val evidenceKeys = listOf(
            "camera", "surveillance", "enforcement", "monitoring", "sensor", "detector",
            "manufacturer", "brand", "model", "product", "device", "operator"
        )
        val evidenceValues = listOf(
            "camera", "cctv", "alpr", "anpr", "lpr", "sensor", "monitor", "enforcement",
            "raven", "shotspotter", "soundthinking", "signaltrace", "eoc plus"
        )
        return camera.tags.any { (key, value) ->
            val lowerKey = key.lowercase()
            val lowerValue = value.lowercase()
            evidenceKeys.any { it in lowerKey } || evidenceValues.any { it in lowerValue }
        }
    }
}
