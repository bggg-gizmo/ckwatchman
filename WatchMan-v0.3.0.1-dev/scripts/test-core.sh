#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build-core-test"
rm -rf "$OUT" && mkdir -p "$OUT"
cat > "$OUT/CoreSmoke.kt" <<'KOT'
import com.bggg.watchman.data.*
import com.bggg.watchman.util.Geo
import com.bggg.watchman.util.ExecutorShutdown
import com.bggg.watchman.cameras.CameraClassifier
import com.bggg.watchman.alerts.AlertSpeech
import com.bggg.watchman.alerts.ProximityAudioPolicy
import com.bggg.watchman.alerts.ProximityToneWaveform
import com.bggg.watchman.alerts.ProximityEncounterTracker
import com.bggg.watchman.alerts.ProximityEncounterSession
import com.bggg.watchman.alerts.ProximityAudioOwnership
import com.bggg.watchman.routing.NavigationProgress
import com.bggg.watchman.routing.RouteScorer
import com.bggg.watchman.routing.RouteGeometry
import com.bggg.watchman.routing.RouteCorridor
import com.bggg.watchman.cameras.AcquisitionPriorityCoordinator
import com.bggg.watchman.cameras.ProgressiveCoverageOrder
import com.bggg.watchman.geo.StateBoundaryGeometry
import com.bggg.watchman.routing.RouteShaper
import com.bggg.watchman.routing.RouteAnalyzer
import com.bggg.watchman.routing.RouteProximityIndex
import com.bggg.watchman.routing.RoutePlanningPolicy
import com.bggg.watchman.routing.RouteContextPolicy
import com.bggg.watchman.service.NavigationRuntimePolicy
import com.bggg.watchman.service.NavigationAnalyticsPolicy
import com.bggg.watchman.net.OperationDeadline
import com.bggg.watchman.ui.ManeuverVisual
import com.bggg.watchman.ui.RouteViewport
import com.bggg.watchman.ui.MapDeviceDeclutter
import com.bggg.watchman.ui.MapMemoryPolicy
import com.bggg.watchman.export.ExportWorkflowContext
import com.bggg.watchman.export.DirectionsExportMode
import com.bggg.watchman.export.ExportArtifactType
import com.bggg.watchman.export.ExportWorkflowOrigin
import com.bggg.watchman.export.PreviewRouteWorkflowState
import com.bggg.watchman.routing.PendingRouteAction
import com.bggg.watchman.routing.PendingRouteActionPolicy
import com.bggg.watchman.routing.PendingRouteRequest
import com.bggg.watchman.navigation.NavigationActivationCoordinator
import com.bggg.watchman.storage.RouteIdentity

fun route(index: Int, meters: Double, seconds: Double) = Route(
    points = listOf(GeoPoint(40.0, -75.0), GeoPoint(40.0, -74.99)),
    steps = emptyList(),
    distanceMeters = meters,
    durationSeconds = seconds,
    destination = SearchResult("Synthetic destination", GeoPoint(40.0, -74.99)),
    alternativeIndex = index
)

fun main() {
    val p = GeoPoint(40.0, -75.0)
    check(Geo.distanceMeters(p, p) < 0.001)
    val routeLine = listOf(GeoPoint(40.0, -75.01), GeoPoint(40.0, -74.99))
    check(Geo.distanceToRouteMeters(p, routeLine) < 1.0)
    val north = GeoPoint(40.001, -75.0)
    check(Geo.distanceToRouteMeters(north, routeLine) in 105.0..118.0)
    check(kotlin.math.abs(Geo.angleDelta(350.0, 10.0) - 20.0) < 0.001)
    val shutdownStarted = java.util.concurrent.CountDownLatch(1)
    val shutdownInterrupted = java.util.concurrent.atomic.AtomicBoolean(false)
    val shutdownExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "watchman-shutdown-fixture").apply { isDaemon = true }
    }
    shutdownExecutor.execute {
        shutdownStarted.countDown()
        try { java.util.concurrent.CountDownLatch(1).await() }
        catch (_: InterruptedException) { shutdownInterrupted.set(true) }
    }
    check(shutdownStarted.await(1, java.util.concurrent.TimeUnit.SECONDS))
    check(ExecutorShutdown.shutdownAll(listOf(shutdownExecutor), 1_000L))
    check(shutdownInterrupted.get() && shutdownExecutor.isTerminated)

    val sparseRoute = listOf(
        GeoPoint(40.0, -75.0000),
        GeoPoint(40.0, -74.9900),
        GeoPoint(40.0100, -74.9900)
    )
    val projection = Geo.projectOntoRoute(GeoPoint(40.0, -74.9950), sparseRoute)
    check(projection != null && projection.distanceToRouteMeters < 2.0)
    check(projection.distanceAlongMeters > 350.0)
    val ahead = Geo.distanceAlongRouteAheadMeters(
        GeoPoint(40.0, -74.9970),
        GeoPoint(40.0, -74.9910),
        sparseRoute
    )
    check(ahead != null && ahead > 400.0)
    check(Geo.distanceAlongRouteAheadMeters(GeoPoint(40.0, -74.9910), GeoPoint(40.0, -74.9970), sparseRoute) == null)
    val remaining = Geo.remainingRouteDistanceMeters(GeoPoint(40.0, -74.9950), sparseRoute)
    check(remaining > 1000.0)

    check(CameraClassifier.classify(mapOf("surveillance:type" to "ALPR")).type == CameraType.ALPR)
    check(CameraClassifier.classify(mapOf("manufacturer" to "Flock Safety")).type == CameraType.FLOCK)
    check(CameraClassifier.classify(mapOf("highway" to "speed_camera")).type == CameraType.SPEED)
    check(CameraClassifier.classify(mapOf("manufacturer" to "Flock Safety", "product" to "Raven Gunshot Detection")).type == CameraType.ACOUSTIC)
    check(CameraClassifier.classify(mapOf("product" to "SignalTrace EOC Plus")).type == CameraType.RF_SENSOR)
    check(CameraClassifier.classify(mapOf("manufacturer" to "Leonardo", "model" to "ELSAG MPH-900")).type == CameraType.ALPR)
    check(CameraClassifier.classify(mapOf("manufacturer" to "Motorola Solutions", "product" to "Vigilant LPR")).type == CameraType.ALPR)
    check(CameraClassifier.classify(mapOf("manufacturer" to "Rekor", "product" to "OpenALPR")).type == CameraType.ALPR)
    check(CameraClassifier.classify(mapOf("manufacturer" to "Genetec", "product" to "AutoVu")).type == CameraType.ALPR)
    check(CameraClassifier.classify(mapOf("manufacturer" to "SoundThinking", "product" to "ShotSpotter acoustic sensor")).type == CameraType.ACOUSTIC)
    check(CameraClassifier.classify(mapOf("manufacturer" to "Wavetronix", "device" to "vehicle detector")).type == CameraType.SENSOR)
    check(CameraClassifier.classify(mapOf("enforcement" to "traffic_signals")).type == CameraType.RED_LIGHT)
    check(CameraClassifier.classify(mapOf("man_made" to "monitoring_station", "monitoring:traffic" to "yes")).type == CameraType.SENSOR)
    check(CameraClassifier.classify(mapOf("highway" to "traffic_signals")).type == CameraType.TRAFFIC)
    val proximityProfile = ProximityAudioPolicy.Profile(
        broadAlertMeters = 610.0,
        proximityStartMeters = 365.0,
        visualRangeMeters = 107.0,
        fovFallbackMeters = 37.0
    )
    check(proximityProfile.isOrdered())
    check(ProximityAudioPolicy.stage(500.0, proximityProfile) == ProximityAudioPolicy.Stage.OUTSIDE)
    check(ProximityAudioPolicy.stage(350.0, proximityProfile) == ProximityAudioPolicy.Stage.CLOSE)
    check(ProximityAudioPolicy.stage(180.0, proximityProfile) == ProximityAudioPolicy.Stage.APPROACHING)
    check(ProximityAudioPolicy.stage(90.0, proximityProfile) == ProximityAudioPolicy.Stage.VISUAL_RANGE)
    check(ProximityAudioPolicy.stage(30.0, proximityProfile, fovActive = true) == ProximityAudioPolicy.Stage.FOV)
    check(ProximityAudioPolicy.stage(30.0, proximityProfile, passed = true) == ProximityAudioPolicy.Stage.PASSED)
    val approachDistances = listOf(365.0, 300.0, 220.0, 150.0, 107.0, 80.0, 50.0, 38.0)
    val approachIntervals = approachDistances.map { ProximityAudioPolicy.pulseIntervalMs(it, proximityProfile) }
    check(approachIntervals.zipWithNext().all { (farther, nearer) -> nearer <= farther })
    check(approachIntervals.first() == ProximityAudioPolicy.SLOW_INTERVAL_MS)
    check(approachIntervals.last() >= ProximityAudioPolicy.MIN_PULSE_INTERVAL_MS)

    val confirmedFov = ProximityAudioPolicy.fovDecision(80.0, proximityProfile, 0.0, 60.0, 10.0, 180.0)
    check(confirmedFov.active && confirmedFov.supported && confirmedFov.basis == ProximityAudioPolicy.FovBasis.CONFIRMED_METADATA)
    val outsideConfirmedFov = ProximityAudioPolicy.fovDecision(80.0, proximityProfile, 0.0, 60.0, 100.0, 0.0)
    check(!outsideConfirmedFov.active && outsideConfirmedFov.supported)
    val approximatedFov = ProximityAudioPolicy.fovDecision(30.0, proximityProfile, 90.0, null, 100.0, 270.0)
    check(approximatedFov.active && approximatedFov.supported && approximatedFov.basis == ProximityAudioPolicy.FovBasis.DIRECTION_APPROXIMATION)
    val fallbackFov = ProximityAudioPolicy.fovDecision(30.0, proximityProfile, null, null, null, null)
    check(fallbackFov.active && !fallbackFov.supported && fallbackFov.basis == ProximityAudioPolicy.FovBasis.DISTANCE_FALLBACK)

    val overlappingDevices = ProximityAudioPolicy.select(listOf(
        ProximityAudioPolicy.Observation("far", 300.0, proximityProfile, 100),
        ProximityAudioPolicy.Observation("near-low-priority", 90.0, proximityProfile, 50),
        ProximityAudioPolicy.Observation("near-high-priority", 90.0, proximityProfile, 102)
    ))
    check(overlappingDevices?.deviceId == "near-high-priority")
    check(overlappingDevices.stage == ProximityAudioPolicy.Stage.VISUAL_RANGE)
    check(overlappingDevices.devicesInRange == 3)
    check(ProximityAudioPolicy.select(emptyList()) == null)

    val pulseWave = ProximityToneWaveform.pulse()
    val continuousWave = ProximityToneWaveform.continuousLoop()
    val pulseFrequency = ProximityToneWaveform.estimatedFrequencyHz(pulseWave)
    val continuousFrequency = ProximityToneWaveform.estimatedFrequencyHz(continuousWave)
    check(pulseFrequency in 860.0..900.0)
    check(continuousFrequency in 860.0..900.0)
    check(kotlin.math.abs(pulseFrequency - continuousFrequency) < 15.0)
    check(ProximityToneWaveform.FREQUENCY_HZ == ProximityAudioPolicy.TONE_FREQUENCY_HZ)

    val encounter = ProximityEncounterTracker()
    fun encounterInput(
        distance: Double,
        signedRouteDelta: Double?,
        stage: ProximityAudioPolicy.Stage,
        now: Long
    ) = ProximityEncounterTracker.Input(
        deviceId = "encounter-device",
        distanceMeters = distance,
        withinBroadAlert = distance <= proximityProfile.broadAlertMeters,
        signedRouteDeltaMeters = signedRouteDelta,
        audioStage = stage,
        visualRangeMeters = proximityProfile.visualRangeMeters,
        resetDistanceMeters = 450.0,
        nowMillis = now
    )
    check(encounter.update(encounterInput(300.0, 300.0, ProximityAudioPolicy.Stage.CLOSE, 0L)).newSpokenAlert)
    check(!encounter.update(encounterInput(90.0, 90.0, ProximityAudioPolicy.Stage.VISUAL_RANGE, 1_000L)).newSpokenAlert)
    val passedEncounter = encounter.update(encounterInput(25.0, -25.0, ProximityAudioPolicy.Stage.PASSED, 2_000L))
    check(passedEncounter.state == ProximityEncounterTracker.State.PASSED && !passedEncounter.toneEligible)
    check(encounter.update(encounterInput(15.0, -5.0, ProximityAudioPolicy.Stage.FOV, 3_000L)).state == ProximityEncounterTracker.State.PASSED)
    check(encounter.update(encounterInput(465.0, -465.0, ProximityAudioPolicy.Stage.OUTSIDE, 13_000L)).state == ProximityEncounterTracker.State.RESET_ELIGIBLE)
    check(encounter.update(encounterInput(300.0, 300.0, ProximityAudioPolicy.Stage.CLOSE, 14_000L)).newSpokenAlert)

    val jitterEncounter = ProximityEncounterTracker()
    fun jitterInput(distance: Double, stage: ProximityAudioPolicy.Stage, now: Long) = ProximityEncounterTracker.Input(
        deviceId = "jitter-device",
        distanceMeters = distance,
        withinBroadAlert = true,
        signedRouteDeltaMeters = null,
        audioStage = stage,
        visualRangeMeters = proximityProfile.visualRangeMeters,
        resetDistanceMeters = 450.0,
        nowMillis = now
    )
    jitterEncounter.update(jitterInput(90.0, ProximityAudioPolicy.Stage.VISUAL_RANGE, 0L))
    check(jitterEncounter.update(jitterInput(105.0, ProximityAudioPolicy.Stage.VISUAL_RANGE, 1_000L)).state != ProximityEncounterTracker.State.PASSED)
    check(jitterEncounter.update(jitterInput(95.0, ProximityAudioPolicy.Stage.VISUAL_RANGE, 2_000L)).state != ProximityEncounterTracker.State.PASSED)
    check(jitterEncounter.update(jitterInput(110.0, ProximityAudioPolicy.Stage.VISUAL_RANGE, 3_000L)).state != ProximityEncounterTracker.State.PASSED)
    check(jitterEncounter.update(jitterInput(130.0, ProximityAudioPolicy.Stage.CLOSE, 4_000L)).state == ProximityEncounterTracker.State.PASSED)

    val codecTracker = ProximityEncounterTracker()
    val codecDeviceId = "camera|雪/alpha"
    val codecInput = ProximityEncounterTracker.Input(
        deviceId = codecDeviceId,
        distanceMeters = 90.0,
        withinBroadAlert = true,
        signedRouteDeltaMeters = 90.0,
        audioStage = ProximityAudioPolicy.Stage.VISUAL_RANGE,
        visualRangeMeters = proximityProfile.visualRangeMeters,
        resetDistanceMeters = 450.0,
        nowMillis = 2_000L
    )
    check(codecTracker.update(codecInput).newSpokenAlert)
    val encodedEncounterState = codecTracker.encode()
    val restoredTracker = ProximityEncounterTracker()
    check(restoredTracker.restore(encodedEncounterState))
    check(restoredTracker.state(codecDeviceId) == ProximityEncounterTracker.State.VISUAL_RANGE)
    check(!restoredTracker.update(codecInput.copy(nowMillis = 3_000L)).newSpokenAlert)
    check(!restoredTracker.restore("not-a-watchman-proximity-state"))

    ProximityEncounterSession.resetForTest()
    ProximityEncounterSession.restoreOnce(encodedEncounterState)
    check(ProximityEncounterSession.tracker.state(codecDeviceId) == ProximityEncounterTracker.State.VISUAL_RANGE)
    ProximityEncounterSession.restoreOnce("not-a-watchman-proximity-state")
    check(ProximityEncounterSession.tracker.state(codecDeviceId) == ProximityEncounterTracker.State.VISUAL_RANGE)
    ProximityEncounterSession.resetForTest()

    val ownershipStops = mutableListOf<String>()
    ProximityAudioOwnership.resetForTest()
    ProximityAudioOwnership.register(ProximityAudioOwnership.Owner.ACTIVITY) { ownershipStops += "stop-activity" }
    ProximityAudioOwnership.register(ProximityAudioOwnership.Owner.SERVICE) { ownershipStops += "stop-service" }
    check(ProximityAudioOwnership.transferTo(ProximityAudioOwnership.Owner.ACTIVITY).owner == ProximityAudioOwnership.Owner.ACTIVITY)
    check(ProximityAudioOwnership.transferTo(ProximityAudioOwnership.Owner.SERVICE).owner == ProximityAudioOwnership.Owner.SERVICE)
    check(ProximityAudioOwnership.transferTo(ProximityAudioOwnership.Owner.ACTIVITY).owner == ProximityAudioOwnership.Owner.ACTIVITY)
    check(ownershipStops == listOf("stop-activity", "stop-service"))
    check(ProximityAudioOwnership.isOwner(ProximityAudioOwnership.Owner.ACTIVITY))
    ProximityAudioOwnership.resetForTest()
    val signalTraceSpeech = CameraPoi("speech-rf", CameraType.RF_SENSOR, p, "EOC Plus", null, null, null, "SignalTrace")
    val ravenSpeech = CameraPoi("speech-raven", CameraType.ACOUSTIC, p, "Raven", null, null, null, "Flock Safety")
    val autoVuSpeech = CameraPoi("speech-autovu", CameraType.ALPR, p, "AutoVu", null, null, null, "Genetec")
    check(AlertSpeech.deviceName(signalTraceSpeech) == "SignalTrace EOC Plus RF sensor")
    check(AlertSpeech.deviceName(ravenSpeech) == "Flock Raven acoustic sensor")
    check(AlertSpeech.deviceName(autoVuSpeech) == "Genetec AutoVu plate reader")

    var fakeNanoTime = -5_000_000_000L
    val operationDeadline = OperationDeadline(1_000L) { fakeNanoTime }
    check(operationDeadline.remainingMillis() == 1_000L)
    check(operationDeadline.socketTimeoutMillis(250) == 250)
    fakeNanoTime += 600_000_000L
    check(operationDeadline.remainingMillis() == 400L)
    check(operationDeadline.admittedRetryDelayMillis(399L) == 399L)
    check(operationDeadline.admittedRetryDelayMillis(400L) == null)
    fakeNanoTime += 399_500_000L
    check(operationDeadline.remainingMillis() == 1L)
    fakeNanoTime += 500_000L
    check(operationDeadline.isExpired())

    check(NavigationRuntimePolicy.refreshIntervalMs(0, true, 0) == 5_000L)
    check(NavigationRuntimePolicy.refreshIntervalMs(12, true, 0) == 15_000L)
    check(NavigationRuntimePolicy.refreshIntervalMs(12, false, 0) == 45_000L)
    check(NavigationRuntimePolicy.refreshIntervalMs(12, true, 5) == 300_000L)
    check(NavigationRuntimePolicy.movementTriggerMeters(0, true) == 75.0)
    check(NavigationRuntimePolicy.movementTriggerMeters(12, true) == 200.0)
    check(NavigationRuntimePolicy.shouldRefreshDeviceSnapshot(false, true, 0L, 0.0))
    check(!NavigationRuntimePolicy.shouldRefreshDeviceSnapshot(true, true, 999L, 24.9))
    check(NavigationRuntimePolicy.shouldRefreshDeviceSnapshot(true, true, 1_000L, 0.0))
    check(NavigationRuntimePolicy.shouldRefreshDeviceSnapshot(true, true, 0L, 25.0))
    check(!NavigationRuntimePolicy.shouldRefreshDeviceSnapshot(true, false, 2_999L, 74.9))
    check(NavigationRuntimePolicy.shouldRefreshDeviceSnapshot(true, false, 3_000L, 0.0))
    check(NavigationRuntimePolicy.shouldRefreshDeviceSnapshot(true, false, 0L, 75.0))
    check(NavigationRuntimePolicy.shouldRetryHttp(429))
    check(NavigationRuntimePolicy.shouldRetryHttp(503))
    check(!NavigationRuntimePolicy.shouldRetryHttp(404))
    check(NavigationRuntimePolicy.retryDelayMs(0) == 500L)
    check(NavigationRuntimePolicy.retryDelayMs(1) == 1_500L)
    check(NavigationAnalyticsPolicy.shouldScanEncounters(10_000L, 0L, 0.0))
    check(!NavigationAnalyticsPolicy.shouldScanEncounters(10_999L, 10_000L, 9.9))
    check(NavigationAnalyticsPolicy.shouldScanEncounters(11_000L, 10_000L, 0.0))
    check(NavigationAnalyticsPolicy.shouldScanEncounters(10_100L, 10_000L, 10.0))
    check(RoutePlanningPolicy.remainingMillis(1_000L, 1_000L) == 25_000L)
    check(RoutePlanningPolicy.remainingMillis(1_000L, 26_001L) == 0L)
    check(RoutePlanningPolicy.cameraCoverageWaitMillis(RoutePreference.FEWEST_CAMERAS, 1_000L, 1_000L) == 8_000L)
    check(RoutePlanningPolicy.cameraCoverageWaitMillis(RoutePreference.MOST_CAMERAS, 1_000L, 8_000L) == 8_000L)
    check(RoutePlanningPolicy.cameraCoverageWaitMillis(RoutePreference.FEWEST_CAMERAS, 1_000L, 15_000L) == 1_000L)
    check(RoutePlanningPolicy.cameraCoverageWaitMillis(RoutePreference.FEWEST_CAMERAS, 1_000L, 15_001L) == 0L)
    check(RoutePlanningPolicy.cameraCoverageWaitMillis(RoutePreference.FASTEST, 1_000L, 1_000L) == 0L)
    check(RoutePlanningPolicy.cameraCoverageWaitMillis(RoutePreference.SHORTEST, 1_000L, 1_000L) == 0L)
    check(RoutePlanningPolicy.cameraCoverageWaitMillis(RoutePreference.SCENIC, 1_000L, 1_000L) == 0L)
    check(RoutePlanningPolicy.acceptsShapedCameraCandidate(RoutePreference.FEWEST_CAMERAS, listOf(4, 7), 3))
    check(!RoutePlanningPolicy.acceptsShapedCameraCandidate(RoutePreference.FEWEST_CAMERAS, listOf(4, 7), 4))
    check(RoutePlanningPolicy.acceptsShapedCameraCandidate(RoutePreference.MOST_CAMERAS, listOf(4, 7), 8))
    check(!RoutePlanningPolicy.acceptsShapedCameraCandidate(RoutePreference.MOST_CAMERAS, listOf(4, 7), 7))
    check(RoutePlanningPolicy.cameraFallbackMessage(RoutePreference.FEWEST_CAMERAS, 0, 0, 0)?.contains("No known") == true)
    check(RoutePlanningPolicy.cameraFallbackMessage(RoutePreference.MOST_CAMERAS, 3, 2, 0)?.contains("measurably improved") == true)
    check(RoutePlanningPolicy.cameraFallbackMessage(RoutePreference.FASTEST, 0, 0, 0) == null)
    for (mode in listOf(RoutePreference.FASTEST, RoutePreference.SHORTEST, RoutePreference.SCENIC)) {
        check(RoutePlanningPolicy.cameraSnapshotBeforePublication(mode) {
            error("Non-camera mode must publish without executing the camera loader")
        }.isEmpty())
    }
    var initialCameraLoads = 0
    for (mode in listOf(RoutePreference.FEWEST_CAMERAS, RoutePreference.MOST_CAMERAS)) {
        RoutePlanningPolicy.cameraSnapshotBeforePublication(mode) { initialCameraLoads++; emptyList() }
    }
    check(initialCameraLoads == 2)
    check(RoutePlanningPolicy.exposureLabel(RoutePlanningPolicy.EXPOSURE_PENDING) == "Camera/sensor counts pending")
    check(RoutePlanningPolicy.exposureLabel(0) == "0 known cams")
    check(RoutePlanningPolicy.exposureLabel(1) == "1 known cam")
    check(runCatching { RoutePlanningPolicy.exposureLabel(-2) }.isFailure)
    val pendingCameraWorkflow = ExportWorkflowContext(
        ExportArtifactType.DIRECTIONS_SELECTED, ExportWorkflowOrigin.PREVIEW,
        previewRoutes = listOf(PreviewRouteWorkflowState("exact-pending-route", RoutePlanningPolicy.EXPOSURE_PENDING, 0.0))
    )
    check(ExportWorkflowContext.decode(pendingCameraWorkflow.encode()) == pendingCameraWorkflow)
    check(ManeuverVisual.classify(RouteStep("Turn left", p, 40.0, "turn", "left")) == ManeuverVisual.Kind.TURN_LEFT)
    check(ManeuverVisual.classify(RouteStep("Keep right", p, 40.0, "fork", "right")) == ManeuverVisual.Kind.FORK_RIGHT)
    check(ManeuverVisual.classify(RouteStep("Take the ramp", p, 40.0, "off ramp", "slight right")) == ManeuverVisual.Kind.RAMP_RIGHT)
    check(ManeuverVisual.classify(RouteStep("Make a U-turn", p, 40.0, "turn", "uturn")) == ManeuverVisual.Kind.U_TURN_LEFT)
    check(ManeuverVisual.classify(RouteStep("Arrive", p, 0.0, "arrive")) == ManeuverVisual.Kind.ARRIVE)

    val viewportFixtures = listOf(
        "local" to listOf(GeoPoint(-30.0, -150.0), GeoPoint(-30.02, -150.03)),
        "long-intraregion" to listOf(GeoPoint(-30.0, -150.0), GeoPoint(-31.14, -150.34)),
        "interstate" to listOf(GeoPoint(-30.0, -150.0), GeoPoint(-35.0, -150.3)),
        "cross-country" to listOf(GeoPoint(-30.0, -150.0), GeoPoint(-42.0, -109.0))
    )
    viewportFixtures.forEach { (name, points) ->
        val fit = RouteViewport.fit(points, 412.0, 780.0, 28.0, 120.0, 180.0)
            ?: error("$name viewport did not produce a fit")
        check(RouteViewport.containsAll(points, fit, 412.0, 780.0, 28.0, 120.0, 180.0)) {
            "$name route endpoints escaped viewport at zoom ${fit.zoom}"
        }
    }
    val crossCountryFit = RouteViewport.fit(viewportFixtures.last().second, 412.0, 780.0, 28.0, 120.0, 180.0)!!
    check(crossCountryFit.zoom < 14)

    val declutterRoute = Route(
        points = (0..200).map { index -> GeoPoint(39.0, -122.0 + index / 200.0) },
        steps = emptyList(),
        distanceMeters = 90_000.0,
        durationSeconds = 5_000.0,
        destination = SearchResult("Declutter destination", GeoPoint(39.0, -121.0)),
        provider = "fixture",
        alternativeIndex = 0
    )
    val denseDevices = (0 until 1_000).map { index ->
        val fraction = index / 999.0
        CameraPoi(
            id = "dense-$index",
            type = CameraType.ALPR,
            point = GeoPoint(39.0 + ((index % 7) - 3) * 0.0001, -122.0 + fraction),
            name = "Dense device $index",
            road = null,
            direction = null,
            operator = null,
            manufacturer = "Fixture",
            sourceId = "fixture",
            sourceRecordId = index.toString()
        )
    }
    val decluttered = MapDeviceDeclutter.select(declutterRoute, denseDevices, MapDeviceDeclutter.INTERACTIVE_ROUTE_LIMIT)
    check(decluttered.size == MapDeviceDeclutter.INTERACTIVE_ROUTE_LIMIT)
    check(decluttered == MapDeviceDeclutter.select(declutterRoute, denseDevices, MapDeviceDeclutter.INTERACTIVE_ROUTE_LIMIT))
    val selectedAlong = decluttered.mapNotNull { Geo.projectOntoRoute(it.point, declutterRoute.points)?.distanceAlongMeters }
    check(selectedAlong.min() < declutterRoute.distanceMeters * 0.05)
    check(selectedAlong.max() > declutterRoute.distanceMeters * 0.90)

    val exactPreviewWorkflow = ExportWorkflowContext(
        artifactType = ExportArtifactType.DIRECTIONS_COMPARISON,
        origin = ExportWorkflowOrigin.PREVIEW,
        routeState = "{exact:selected-route|step=17}",
        previewRoutes = listOf(
            PreviewRouteWorkflowState("{route:one}", 12, 1.25),
            PreviewRouteWorkflowState("{route:two.with punctuation & unicode ·}", 3, 9.5)
        ),
        recommendedRouteIdentity = "route-one-sha",
        selectedRouteIdentity = "route-two-sha",
        savedRouteId = 4821L,
        analyticsSinceMillis = 1_777_777_777L,
        analyticsPeriodLabel = "Last 30 days · exact"
    )
    check(ExportWorkflowContext.decode(exactPreviewWorkflow.encode()) == exactPreviewWorkflow)
    val routedSummary=com.bggg.watchman.data.RoutedDeviceExposure(0,0,1000L,2000L,"a".repeat(64))
    val routedWorkflow=exactPreviewWorkflow.copy(previewRoutes=exactPreviewWorkflow.previewRoutes.map {
        it.copy(routedExposure=routedSummary)
    })
    check(ExportWorkflowContext.decode(routedWorkflow.encode())==routedWorkflow)
    // Existing v2 exact-route workflows remain readable without inventing road coverage.
    val legacyV2=exactPreviewWorkflow.encode().split('.').toMutableList().also { fields ->
        fields[0]="2"
        fields[4]=fields[4].split('~').joinToString("~") { it.substringBeforeLast(',') }
    }.joinToString(".")
    check(ExportWorkflowContext.decode(legacyV2)==exactPreviewWorkflow)
    check(runCatching { routedSummary.copy(conservativeEncounters=1) }.isFailure)
    check(runCatching { routedSummary.copy(validUntilMillis=1000L) }.isFailure)
    ExportWorkflowOrigin.values().forEach { origin ->
        val workflow = ExportWorkflowContext(ExportArtifactType.GENERIC, origin, routeState = "route-$origin")
        check(ExportWorkflowContext.decode(workflow.encode()) == workflow)
    }
    check(ExportArtifactType.forDirections(DirectionsExportMode.SELECTED_ROUTE) == ExportArtifactType.DIRECTIONS_SELECTED)
    check(ExportArtifactType.forDirections(DirectionsExportMode.ALL_ALTERNATIVES) == ExportArtifactType.DIRECTIONS_ALTERNATIVES)
    check(ExportArtifactType.forDirections(DirectionsExportMode.ROUTE_COMPARISON) == ExportArtifactType.DIRECTIONS_COMPARISON)
    val pendingRoute = PendingRouteRequest(
        startQuery = "Fixture origin | Suite 2",
        destinationQuery = "Fixture destination & suite",
        origin = SearchResult("Origin · exact", GeoPoint(0.5865, -2.3917)),
        destination = SearchResult("Destination · exact", GeoPoint(0.5383, -1.3792)),
        preference = RoutePreference.MOST_CAMERAS,
        stage = PendingRouteRequest.Stage.ROUTE_READY,
        postRouteAction = PendingRouteAction.EXPORT_COMPARISON,
        completedWorkflowContext = exactPreviewWorkflow.encode()
    )
    check(PendingRouteRequest.decode(pendingRoute.encode()) == pendingRoute)
    check(PendingRouteRequest.decode("broken") == null)
    check(PendingRouteAction.values().toSet() == setOf(
        PendingRouteAction.PREVIEW,
        PendingRouteAction.TURN_BY_TURN,
        PendingRouteAction.NAVIGATE,
        PendingRouteAction.EXPORT_SELECTED,
        PendingRouteAction.EXPORT_ALL,
        PendingRouteAction.EXPORT_COMPARISON
    ))
    check(PendingRouteActionPolicy.canClearImmediatelyAfterDispatch(PendingRouteAction.PREVIEW))
    check(PendingRouteActionPolicy.canClearImmediatelyAfterDispatch(PendingRouteAction.TURN_BY_TURN))
    check(PendingRouteActionPolicy.canClearImmediatelyAfterDispatch(PendingRouteAction.NAVIGATE))
    check(!PendingRouteActionPolicy.canClearImmediatelyAfterDispatch(PendingRouteAction.EXPORT_SELECTED))
    check(!PendingRouteActionPolicy.canClearImmediatelyAfterDispatch(PendingRouteAction.EXPORT_ALL))
    check(!PendingRouteActionPolicy.canClearImmediatelyAfterDispatch(PendingRouteAction.EXPORT_COMPARISON))
    val fast = RouteAnalysis(route(0, 12000.0, 900.0), knownCameraCount = 8, scenicScore = 3.0)
    val short = RouteAnalysis(route(1, 9000.0, 1100.0), knownCameraCount = 4, scenicScore = 5.0)
    val lowCam = RouteAnalysis(route(2, 11000.0, 1000.0), knownCameraCount = 1, scenicScore = 4.0)
    val scenic = RouteAnalysis(route(3, 12500.0, 1050.0), knownCameraCount = 5, scenicScore = 12.0)
    val unreasonableDetour = RouteAnalysis(route(4, 25000.0, 1200.0), knownCameraCount = 5, scenicScore = 99.0)
    val highCam = RouteAnalysis(route(5, 11500.0, 1300.0), knownCameraCount = 12, scenicScore = 1.0)
    val all = listOf(fast, short, lowCam, scenic, unreasonableDetour, highCam)
    check(RouteScorer.select(RoutePreference.FASTEST, all).route.alternativeIndex == 0)
    check(RouteScorer.select(RoutePreference.SHORTEST, all).route.alternativeIndex == 1)
    check(RouteScorer.select(RoutePreference.FEWEST_CAMERAS, all).route.alternativeIndex == 2)
    check(RouteScorer.select(RoutePreference.MOST_CAMERAS, all).route.alternativeIndex == 5)
    check(RouteScorer.select(RoutePreference.SCENIC, all).route.alternativeIndex == 3)
    check(RouteContextPolicy.visibleRoute(fast.route, lowCam.route) == lowCam.route)
    check(RouteContextPolicy.visibleRoute(fast.route, null) == fast.route)
    check(RouteContextPolicy.visibleRoute(null, null) == null)
    check(RouteContextPolicy.exactSingleIndex(listOf("route-a", "route-b", "route-c"), "route-b") == 1)
    check(RouteContextPolicy.exactSingleIndex(listOf("route-a", "route-b"), "route-c") == null)
    check(RouteContextPolicy.exactSingleIndex(listOf("route-a", "route-a"), "route-a") == null)
    check(RouteContextPolicy.exactSingleIndex(listOf("route-a"), "") == null)
    check(MapMemoryPolicy.budgetForTrimLevel(4) == null)
    check(MapMemoryPolicy.budgetForTrimLevel(5)?.tileBytes == 24 * 1024 * 1024)
    check(MapMemoryPolicy.budgetForTrimLevel(10)?.offlineRoadPoints == 125_000)
    check(MapMemoryPolicy.budgetForTrimLevel(15)?.releasePerViewIcons == true)
    check(MapMemoryPolicy.budgetForTrimLevel(20)?.tileBytes == 0)

    val actualCamera = CameraPoi(
        id = "node/1", type = CameraType.ALPR, point = GeoPoint(40.0, -74.995),
        name = null, road = null, direction = null, operator = null, manufacturer = null
    )
    val trafficDevice = CameraPoi(
        id = "node/2", type = CameraType.TRAFFIC, point = GeoPoint(40.0, -74.995),
        name = null, road = null, direction = null, operator = null, manufacturer = null,
        tags = mapOf("highway" to "traffic_signals")
    )
    check(RouteAnalyzer.isCameraOrSensorForRouting(trafficDevice))
    check(RouteAnalyzer.countKnownCameras(fast.route, listOf(actualCamera, trafficDevice)) == 2)
    check(!RouteAnalyzer.isCameraOrSensorForRouting(trafficDevice.copy(type = CameraType.TOLL, tags = emptyMap())))
    check(!RouteAnalyzer.isCameraOrSensorForRouting(trafficDevice.copy(type = CameraType.OTHER, tags = emptyMap())))
    val sameSparse = fast.route.copy(
        points = listOf(GeoPoint(40.0, -75.0), GeoPoint(40.0, -74.99))
    )
    val sameDense = fast.route.copy(
        points = listOf(
            GeoPoint(40.0, -75.0),
            GeoPoint(40.0, -74.9975),
            GeoPoint(40.0, -74.9950),
            GeoPoint(40.0, -74.9925),
            GeoPoint(40.0, -74.99)
        ),
        durationSeconds = fast.route.durationSeconds + 5.0
    )
    val differentRoad = fast.route.copy(
        points = listOf(
            GeoPoint(40.0, -75.0),
            GeoPoint(40.004, -74.995),
            GeoPoint(40.0, -74.99)
        )
    )
    check(RouteGeometry.equivalent(sameSparse, sameDense))
    check(RouteGeometry.equivalent(sameSparse, differentRoad) == false)
    check(RouteGeometry.deduplicate(listOf(sameDense, sameSparse)).size == 1)

    val shapingRoute = fast.route.copy(
        points = listOf(GeoPoint(40.0, -75.0), GeoPoint(40.0, -74.94)),
        distanceMeters = 5100.0
    )
    val shapingCamera = actualCamera.copy(point = GeoPoint(40.0, -74.985))
    val secondThroughCamera = actualCamera.copy(
        id = "node/through-2",
        type = CameraType.RF_SENSOR,
        point = GeoPoint(40.004, -74.955)
    )
    val bareSignalOnRoute = trafficDevice.copy(point = shapingCamera.point)
    val aroundSets = RouteShaper.aroundWaypointSets(listOf(shapingRoute), listOf(shapingCamera))
    check(aroundSets.size == 2)
    check(aroundSets.all { it.size in 1..RouteShaper.MAX_VIA_POINTS })
    check(aroundSets.flatten().all { Geo.distanceToRouteMeters(it, shapingRoute.points) >= 500.0 })
    check(aroundSets.flatten().all { Geo.distanceMeters(it, shapingCamera.point) >= 500.0 })
    check(RouteShaper.aroundWaypointSets(listOf(shapingRoute), listOf(bareSignalOnRoute)).size == 2)

    val throughSets = RouteShaper.throughWaypointSets(
        listOf(shapingRoute),
        listOf(shapingCamera, secondThroughCamera, bareSignalOnRoute)
    )
    check(throughSets.size in 2..3)
    check(throughSets.all { it.size in 1..RouteShaper.MAX_VIA_POINTS })
    check(throughSets.first() == listOf(shapingCamera.point, secondThroughCamera.point))
    check(RouteShaper.throughWaypointSets(listOf(shapingRoute), listOf(bareSignalOnRoute)) == listOf(listOf(bareSignalOnRoute.point)))

    val noCamGeometry = shapingRoute.copy(
        points = listOf(shapingRoute.points.first(), GeoPoint(40.012, -74.97), shapingRoute.points.last()),
        alternativeIndex = 20
    )
    val mostCamGeometry = shapingRoute.copy(
        points = listOf(
            shapingRoute.points.first(),
            shapingCamera.point,
            secondThroughCamera.point,
            shapingRoute.points.last()
        ),
        alternativeIndex = 21
    )
    val shapingDevices = listOf(shapingCamera, secondThroughCamera, bareSignalOnRoute)
    check(RouteAnalyzer.countKnownCameras(shapingRoute, shapingDevices) == 2)
    check(RouteAnalyzer.countKnownCameras(noCamGeometry, shapingDevices) == 0)
    check(RouteAnalyzer.countKnownCameras(mostCamGeometry, shapingDevices) == 3)
    check(RouteGeometry.deduplicate(listOf(shapingRoute, noCamGeometry, mostCamGeometry)).size == 3)
    check(RouteShaper.scenicWaypointSets(shapingRoute.points.first(), shapingRoute.points.last()).size == 2)

    val crossCountryPoints = (0..50000).map { index ->
        val ratio = index / 50000.0
        GeoPoint(
            -30.0 - 12.0 * ratio,
            -150.0 + 41.0 * ratio
        )
    }
    val crossCountryRoute = Route(
        points = crossCountryPoints,
        steps = emptyList(),
        distanceMeters = Geo.routeLengthMeters(crossCountryPoints),
        durationSeconds = 180_000.0,
        destination = SearchResult("Synthetic cross-region destination", crossCountryPoints.last())
    )
    val legacyIdentityRoute = route(9, 1234.5, 678.9)
    val legacyCanonical = buildString {
        append(legacyIdentityRoute.provider).append('|')
        append(legacyIdentityRoute.destination.displayName).append('|')
        append(String.format(java.util.Locale.US, "%.6f,%.6f", legacyIdentityRoute.destination.point.lat, legacyIdentityRoute.destination.point.lon))
        append('|').append(String.format(java.util.Locale.US, "%.1f|%.1f|", legacyIdentityRoute.distanceMeters, legacyIdentityRoute.durationSeconds))
        legacyIdentityRoute.points.forEach { point ->
            append(String.format(java.util.Locale.US, "%.6f,%.6f;", point.lat, point.lon))
        }
    }
    val legacyIdentity = java.security.MessageDigest.getInstance("SHA-256")
        .digest(legacyCanonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    check(RouteIdentity.sha256(legacyIdentityRoute) == legacyIdentity)
    val denseIdentity = RouteIdentity.sha256(crossCountryRoute)
    check(denseIdentity.length == 64 && denseIdentity.all { it in "0123456789abcdef" })

    val activationCoordinator = NavigationActivationCoordinator()
    val firstActivation = activationCoordinator.begin()
    var activationCommits = 0
    check(activationCoordinator.runIfCurrent(firstActivation) { activationCommits++ })
    val secondActivation = activationCoordinator.begin()
    check(!activationCoordinator.runIfCurrent(firstActivation) { activationCommits++ })
    activationCoordinator.cancel()
    check(!activationCoordinator.runIfCurrent(secondActivation) { activationCommits++ })
    val finalActivation = activationCoordinator.begin()
    check(activationCoordinator.runIfCurrent(finalActivation) { activationCommits++ })
    check(activationCommits == 2)

    val corridorWindows = RouteCorridor.windows(List(3) { crossCountryRoute }, 1_000.0)
    check(corridorWindows.isNotEmpty() && corridorWindows.size <= RouteCorridor.MAX_WINDOWS)
    check(corridorWindows.all { it.east - it.west < 5.0 && it.north - it.south < 5.0 })
    val proximityIndex = RouteProximityIndex(crossCountryRoute, 1_000.0)
    check(proximityIndex.indexedCellCount > 0)
    check(proximityIndex.indexedSegmentReferences < crossCountryPoints.size * 100)
    check(proximityIndex.isWithin(crossCountryPoints[25_000]))
    val indexedCameras = (0 until 200).map { sampleIndex ->
        val pointIndex = sampleIndex * crossCountryPoints.lastIndex / 199
        actualCamera.copy(
            id = "node/indexed-$sampleIndex",
            point = crossCountryPoints[pointIndex]
        )
    }
    check(RouteAnalyzer.countKnownCameras(crossCountryRoute, indexedCameras, 1_000.0) == indexedCameras.size)
    val priority = AcquisitionPriorityCoordinator()
    val gpsLease = priority.acquire(AcquisitionPriorityCoordinator.Work.GPS_STATE)
    check(priority.activeWork == AcquisitionPriorityCoordinator.Work.GPS_STATE)
    val routeLease = priority.acquire(AcquisitionPriorityCoordinator.Work.ROUTE_CORRIDOR)
    check(priority.activeWork == AcquisitionPriorityCoordinator.Work.ROUTE_CORRIDOR)
    gpsLease.close()
    check(priority.activeWork == AcquisitionPriorityCoordinator.Work.ROUTE_CORRIDOR)
    routeLease.close()
    check(priority.activeWork == AcquisitionPriorityCoordinator.Work.NATIONAL)
    priority.awaitNationalTurn { false }

    check(ProgressiveCoverageOrder.indices(0).isEmpty())
    check(ProgressiveCoverageOrder.indices(1) == listOf(0))
    check(ProgressiveCoverageOrder.indices(2) == listOf(0, 1))
    check(ProgressiveCoverageOrder.indices(5) == listOf(0, 4, 2, 1, 3))
    check(ProgressiveCoverageOrder.indices(8) == listOf(0, 7, 3, 1, 5, 2, 4, 6))
    check(ProgressiveCoverageOrder.indices(48).toSet().size == 48)

    val stateOuter = StateBoundaryGeometry.Ring.from(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 10.0), GeoPoint(10.0, 10.0), GeoPoint(10.0, 0.0)))
    val stateHole = StateBoundaryGeometry.Ring.from(listOf(GeoPoint(4.0, 4.0), GeoPoint(4.0, 6.0), GeoPoint(6.0, 6.0), GeoPoint(6.0, 4.0)))
    val statePolygon = StateBoundaryGeometry.Polygon(stateOuter, listOf(stateHole))
    check(StateBoundaryGeometry.contains(statePolygon, GeoPoint(2.0, 2.0)))
    check(!StateBoundaryGeometry.contains(statePolygon, GeoPoint(5.0, 5.0)))
    check(!StateBoundaryGeometry.contains(statePolygon, GeoPoint(12.0, 2.0)))
    val antimeridian = StateBoundaryGeometry.Ring.from(listOf(GeoPoint(-10.0, 170.0), GeoPoint(-10.0, -170.0), GeoPoint(10.0, -170.0), GeoPoint(10.0, 170.0)))
    check(StateBoundaryGeometry.contains(antimeridian, GeoPoint(0.0, 179.0)))
    check(!StateBoundaryGeometry.contains(antimeridian, GeoPoint(0.0, 0.0)))


    val navRoute = Route(
        points = sparseRoute,
        steps = listOf(
            RouteStep("Start", GeoPoint(40.0, -75.0000), 0.0),
            RouteStep("Continue", GeoPoint(40.0, -74.9920), 680.0),
            RouteStep("Arrive", GeoPoint(40.0100, -74.9900), 1100.0)
        ),
        distanceMeters = Geo.routeLengthMeters(sparseRoute),
        durationSeconds = 300.0,
        destination = SearchResult("Synthetic destination", sparseRoute.last())
    )
    val progressed = NavigationProgress.upcomingStepIndex(navRoute, GeoPoint(40.0, -74.9950), 0)
    check(progressed == 1)
    val toManeuver = NavigationProgress.distanceToUpcomingManeuver(navRoute, GeoPoint(40.0, -74.9950), progressed)
    check(toManeuver != null && toManeuver in 200.0..400.0)

    println("Geo + camera classification + route scoring + navigation-progress smoke tests: PASS")
}
KOT
kotlinc "$ROOT/app/src/main/java/com/bggg/watchman/data/Models.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/alerts/AlertSpeech.kt" "$ROOT/app/src/main/java/com/bggg/watchman/alerts/ProximityAudioPolicy.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/alerts/ProximityToneWaveform.kt" "$ROOT/app/src/main/java/com/bggg/watchman/alerts/ProximityEncounterTracker.kt" "$ROOT/app/src/main/java/com/bggg/watchman/alerts/ProximityEncounterSession.kt" "$ROOT/app/src/main/java/com/bggg/watchman/alerts/ProximityAudioOwnership.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/cameras/CameraClassifier.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/routing/RouteScorer.kt" "$ROOT/app/src/main/java/com/bggg/watchman/routing/RoutePlanningPolicy.kt" "$ROOT/app/src/main/java/com/bggg/watchman/routing/RouteContextPolicy.kt" "$ROOT/app/src/main/java/com/bggg/watchman/routing/PendingRouteAction.kt" "$ROOT/app/src/main/java/com/bggg/watchman/export/ExportWorkflowContext.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/routing/RouteAnalyzer.kt" "$ROOT/app/src/main/java/com/bggg/watchman/routing/RouteGeometry.kt" "$ROOT/app/src/main/java/com/bggg/watchman/routing/RouteShaper.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/routing/RouteProximityIndex.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/cameras/AcquisitionPriorityCoordinator.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/cameras/ProgressiveCoverageOrder.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/routing/RouteCorridor.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/geo/StateBoundaryGeometry.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/routing/NavigationProgress.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/net/OperationDeadline.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/service/NavigationRuntimePolicy.kt" "$ROOT/app/src/main/java/com/bggg/watchman/service/NavigationAnalyticsPolicy.kt" "$ROOT/app/src/main/java/com/bggg/watchman/ui/ManeuverVisual.kt" "$ROOT/app/src/main/java/com/bggg/watchman/ui/RouteViewport.kt" "$ROOT/app/src/main/java/com/bggg/watchman/ui/MapDeviceDeclutter.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/ui/MapMemoryPolicy.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/util/ExecutorShutdown.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/navigation/NavigationActivationCoordinator.kt" "$ROOT/app/src/main/java/com/bggg/watchman/storage/RouteIdentity.kt" \
  "$ROOT/app/src/main/java/com/bggg/watchman/util/Geo.kt" "$OUT/CoreSmoke.kt" \
  -include-runtime -d "$OUT/core-smoke.jar"
java -jar "$OUT/core-smoke.jar"
