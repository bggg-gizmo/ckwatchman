package com.bggg.watchman.alerts

import android.location.Location
import com.bggg.watchman.cameras.CameraClassifier
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.util.Geo
import java.util.Locale
import kotlin.math.roundToInt

class AlertEngine(
    private val encounterTracker: ProximityEncounterTracker = ProximityEncounterSession.tracker
) {

    data class Alert(
        val camera: CameraPoi,
        val distanceMeters: Double,
        val routeDistance: Boolean,
        val speechKey: String? = null
    )

    data class Update(
        val proximity: ProximityAudioPolicy.Selection?,
        val alert: Alert?,
        val diagnostic: ProximityDiagnostic?,
        val speechAlerts: List<Alert> = emptyList()
    )

    data class ProximityDiagnostic(
        val deviceId: String,
        val deviceType: String,
        val deviceSourceId: String,
        val distanceMeters: Double,
        val euclideanDistanceMeters: Double,
        val signedRouteDistanceMeters: Double?,
        val routeDistanceUsed: Boolean,
        val deviceDirectionDegrees: Double?,
        val travelBearingDegrees: Double?,
        val stage: ProximityAudioPolicy.Stage,
        val encounterState: ProximityEncounterTracker.State,
        val fovDecision: ProximityAudioPolicy.FovDecision,
        val cadenceMs: Long,
        val toneRendererMode: String,
        val passed: Boolean,
        val devicesInRange: Int,
        val spokenAlert: Boolean
    )

    private data class Candidate(
        val camera: CameraPoi,
        val distanceMeters: Double,
        val euclideanDistanceMeters: Double,
        val signedRouteDistanceMeters: Double?,
        val routeDistance: Boolean,
        val deviceDirectionDegrees: Double?,
        val travelBearingDegrees: Double?,
        val audioStage: ProximityAudioPolicy.Stage,
        val fovDecision: ProximityAudioPolicy.FovDecision,
        val encounter: ProximityEncounterTracker.Result
    )

    /** One scan feeds persistent encounter state, audio arbitration and one-time spoken alerts. */
    fun update(
        location: Location,
        cameras: List<CameraPoi>,
        route: Route?,
        profileInput: ProximityAudioPolicy.Profile
    ): Update {
        val profile = profileInput.normalized()
        val candidates = scan(location, cameras, route, profile)
        val observations = candidates.asSequence()
            .filter { it.encounter.toneEligible }
            .map { candidate ->
                ProximityAudioPolicy.Observation(
                    deviceId = candidate.camera.id,
                    distanceMeters = candidate.distanceMeters,
                    profile = profile,
                    priority = CameraClassifier.priority(candidate.camera.type),
                    fovDecision = candidate.fovDecision,
                    passed = candidate.encounter.state == ProximityEncounterTracker.State.PASSED
                )
            }
            .toList()
        val proximity = ProximityAudioPolicy.select(observations)
        val newAlert = candidates.asSequence()
            .filter { it.encounter.newSpokenAlert }
            .sortedWith(
                compareBy<Candidate> { it.distanceMeters }
                    .thenByDescending { CameraClassifier.priority(it.camera.type) }
                    .thenBy { it.camera.id }
            )
            .firstOrNull()
            ?.let { Alert(it.camera, it.distanceMeters, it.routeDistance) }
        val diagnosticCandidate = proximity?.let { selected ->
            candidates.firstOrNull { it.camera.id == selected.deviceId }
        } ?: candidates.minWithOrNull(
            compareBy<Candidate> { it.distanceMeters }
                .thenByDescending { CameraClassifier.priority(it.camera.type) }
                .thenBy { it.camera.id }
        )
        val diagnostic = diagnosticCandidate?.let { candidate ->
            val passed = candidate.encounter.state in setOf(
                ProximityEncounterTracker.State.PASSED,
                ProximityEncounterTracker.State.RESET_ELIGIBLE
            )
            ProximityDiagnostic(
                deviceId = candidate.camera.id,
                deviceType = candidate.camera.type.name,
                deviceSourceId = candidate.camera.sourceId,
                distanceMeters = candidate.distanceMeters,
                euclideanDistanceMeters = candidate.euclideanDistanceMeters,
                signedRouteDistanceMeters = candidate.signedRouteDistanceMeters,
                routeDistanceUsed = candidate.routeDistance,
                deviceDirectionDegrees = candidate.deviceDirectionDegrees,
                travelBearingDegrees = candidate.travelBearingDegrees,
                stage = proximity?.takeIf { it.deviceId == candidate.camera.id }?.stage ?: candidate.audioStage,
                encounterState = candidate.encounter.state,
                fovDecision = candidate.fovDecision,
                cadenceMs = proximity?.takeIf { it.deviceId == candidate.camera.id }?.pulseIntervalMs ?: 0L,
                toneRendererMode = when {
                    proximity?.deviceId != candidate.camera.id -> "SILENT"
                    proximity.continuous -> "CONTINUOUS_PCM"
                    else -> "PULSED_PCM"
                },
                passed = passed,
                devicesInRange = proximity?.devicesInRange ?: observations.size,
                spokenAlert = newAlert?.camera?.id == candidate.camera.id
            )
        }
        val speechAlerts = candidates.asSequence().filter { it.encounter.speechKey != null }
            .sortedWith(compareBy<Candidate> { it.distanceMeters }
                .thenByDescending { CameraClassifier.priority(it.camera.type) }.thenBy { it.camera.id })
            .map { Alert(it.camera, it.distanceMeters, it.routeDistance, it.encounter.speechKey) }.toList()
        return Update(proximity, newAlert, diagnostic, speechAlerts)
    }

    /** Compatibility entry point for older callers; new runtime uses independent UserPreferences. */
    fun update(location: Location, cameras: List<CameraPoi>, route: Route?, thresholdFeet: Int): Update {
        val broad = thresholdFeet / Geo.FEET_PER_METER
        val profile = ProximityAudioPolicy.Profile(
            broadAlertMeters = broad,
            proximityStartMeters = minOf(broad, 1_200.0 / Geo.FEET_PER_METER),
            visualRangeMeters = minOf(broad, 350.0 / Geo.FEET_PER_METER),
            fovFallbackMeters = minOf(broad, 120.0 / Geo.FEET_PER_METER)
        ).normalized()
        return update(location, cameras, route, profile)
    }

    fun evaluate(location: Location, cameras: List<CameraPoi>, route: Route?, thresholdFeet: Int): Alert? =
        update(location, cameras, route, thresholdFeet).alert

    private fun scan(
        location: Location,
        cameras: List<CameraPoi>,
        route: Route?,
        profile: ProximityAudioPolicy.Profile
    ): List<Candidate> {
        val me = GeoPoint(location.latitude, location.longitude)
        val routeProjection = route?.let { Geo.projectOntoRoute(me, it.points) }
        val now = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        val resetDistance = maxOf(profile.proximityStartMeters * 1.25, profile.proximityStartMeters + 75.0)
        return cameras.mapNotNull { camera ->
            if (!routeRelevant(camera, route, location)) return@mapNotNull null
            val cameraProjection = route?.let { Geo.projectOntoRoute(camera.point, it.points) }
            val signedRouteDelta = if (routeProjection != null && cameraProjection != null) {
                cameraProjection.distanceAlongMeters - routeProjection.distanceAlongMeters
            } else null
            val euclidean = Geo.distanceMeters(me, camera.point)
            // Never turn a negative/behind route delta into an ahead candidate via Euclidean fallback.
            val distance = when {
                signedRouteDelta == null -> euclidean
                signedRouteDelta < -ProximityEncounterTracker.ROUTE_PASS_MARGIN_METERS -> euclidean
                else -> signedRouteDelta.coerceAtLeast(0.0)
            }
            val cameraDirection = parseDirection(camera.direction)
            val travelBearing = location.bearing.takeIf { location.hasBearing() }?.toDouble()
            val fovDecision = ProximityAudioPolicy.fovDecision(
                distanceMeters = distance,
                profileInput = profile,
                cameraDirectionDegrees = cameraDirection,
                horizontalFovDegrees = parseHorizontalFov(camera),
                bearingFromDeviceToUserDegrees = Geo.bearingDegrees(camera.point, me),
                travelBearingDegrees = travelBearing
            )
            val audioStage = ProximityAudioPolicy.stage(distance, profile, fovDecision.active)
            val encounter = encounterTracker.update(
                ProximityEncounterTracker.Input(
                    deviceId = camera.id,
                    distanceMeters = distance,
                    withinBroadAlert = distance <= profile.broadAlertMeters,
                    signedRouteDeltaMeters = signedRouteDelta,
                    audioStage = audioStage,
                    visualRangeMeters = profile.visualRangeMeters,
                    resetDistanceMeters = resetDistance,
                    nowMillis = now
                )
            )
            if (distance > profile.broadAlertMeters &&
                encounter.state !in setOf(ProximityEncounterTracker.State.PASSED, ProximityEncounterTracker.State.RESET_ELIGIBLE)) {
                null
            } else {
                Candidate(
                    camera = camera,
                    distanceMeters = distance,
                    euclideanDistanceMeters = euclidean,
                    signedRouteDistanceMeters = signedRouteDelta,
                    routeDistance = signedRouteDelta != null && signedRouteDelta >= 0.0,
                    deviceDirectionDegrees = cameraDirection,
                    travelBearingDegrees = travelBearing,
                    audioStage = audioStage,
                    fovDecision = fovDecision,
                    encounter = encounter
                )
            }
        }
    }

    private fun routeRelevant(camera: CameraPoi, route: Route?, location: Location): Boolean {
        if (route == null || route.points.isEmpty()) return true
        if (Geo.distanceToRouteMeters(camera.point, route.points) > 145.0) return false
        val cameraDirection = parseDirection(camera.direction)
        if (cameraDirection != null && location.hasBearing() &&
            Geo.angleDelta(cameraDirection, location.bearing.toDouble()) > 100.0) return false
        return true
    }

    private fun parseHorizontalFov(camera: CameraPoi): Double? {
        val raw = listOf("camera:fov", "fov", "field_of_view", "view_angle")
            .firstNotNullOfOrNull { key -> camera.tags[key]?.takeIf { it.isNotBlank() } }
            ?: return null
        return Regex("-?\\d+(?:\\.\\d+)?").find(raw)?.value?.toDoubleOrNull()
            ?.takeIf { it in 5.0..180.0 }
    }

    private fun parseDirection(raw: String?): Double? {
        val value = raw?.trim()?.lowercase(Locale.US) ?: return null
        value.toDoubleOrNull()?.let { return ((it % 360.0) + 360.0) % 360.0 }
        return when (value.replace("-", "").replace("_", "").replace(" ", "")) {
            "n", "north" -> 0.0
            "ne", "northeast" -> 45.0
            "e", "east" -> 90.0
            "se", "southeast" -> 135.0
            "s", "south" -> 180.0
            "sw", "southwest" -> 225.0
            "w", "west" -> 270.0
            "nw", "northwest" -> 315.0
            else -> null
        }
    }

    fun reset() = encounterTracker.reset()

    fun describe(alert: Alert): String {
        val basis = if (alert.routeDistance) "ahead" else "away"
        return "${AlertSpeech.deviceName(alert.camera)} $basis · ${(alert.distanceMeters * Geo.FEET_PER_METER).roundToInt()} ft"
    }
}
