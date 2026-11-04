package com.bggg.watchman.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import com.bggg.watchman.MainActivity
import com.bggg.watchman.alerts.AlertEngine
import com.bggg.watchman.alerts.WatchManSpeech
import com.bggg.watchman.alerts.ProximityTonePlayer
import com.bggg.watchman.alerts.ProximityAudioOwnership
import com.bggg.watchman.alerts.ProximityEncounterSession
import com.bggg.watchman.storage.ActiveRouteStateCodec
import com.bggg.watchman.cameras.OverpassCameraProvider
import com.bggg.watchman.cameras.PublicCameraProvider
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.diagnostics.LocalDiagnostics
import com.bggg.watchman.data.Route
import com.bggg.watchman.storage.CameraDatabase
import com.bggg.watchman.storage.NationalCameraCache
import com.bggg.watchman.storage.AuthoritativeDeviceRepository
import com.bggg.watchman.history.RouteHistoryDatabase
import com.bggg.watchman.preferences.UserPreferences
import com.bggg.watchman.util.Geo
import com.bggg.watchman.util.ExecutorShutdown
import com.bggg.watchman.routing.RouteProximityIndex
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground owner for active navigation location, camera synchronization and proximity alerts.
 * The Activity remains the presentation surface; this service keeps the safety-critical loop alive
 * when the Activity pauses or the screen turns off.
 */
class NavigationForegroundService : Service(), LocationListener {
    private data class ActivityLocationSample(val location: Location, val cameras: List<CameraPoi>)

    private val binder = Binder()
    private lateinit var locationManager: LocationManager
    private lateinit var db: CameraDatabase
    private lateinit var nationalCameraCache: NationalCameraCache
    private lateinit var deviceRepository: AuthoritativeDeviceRepository
    private lateinit var historyDb: RouteHistoryDatabase
    private lateinit var localDiagnostics: LocalDiagnostics
    private val alertEngine = AlertEngine()
    private val proximityTonePlayer = ProximityTonePlayer()
    private val overpass = OverpassCameraProvider()
    private val publicFeeds = PublicCameraProvider()
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coreSyncInFlight = AtomicBoolean(false)
    private val supplementalSyncInFlight = AtomicBoolean(false)
    private val activitySampleDrainScheduled = AtomicBoolean(false)
    private val restoreRouteInFlight = AtomicBoolean(false)
    private var lastSyncPoint: GeoPoint? = null
    private var lastSyncAt = 0L
    private var consecutiveSyncFailures = 0
    private val speechOwner = "service-${java.util.UUID.randomUUID()}"
    private var lastTrackPoint: GeoPoint? = null
    private var lastTrackAt = 0L
    private var analyticsTripId = -1L
    private var analyticsRoute: Route? = null
    private var analyticsRouteIndex: RouteProximityIndex? = null
    private var lastEncounterScanPoint: GeoPoint? = null
    private var lastEncounterScanAt = 0L
    private val recordedEncounterIds = HashSet<String>()
    private var lastProximityStatePersistAt = 0L
    private var lastProximityStateSignature: String? = null
    private var locationUpdatesStarted = false
    @Volatile private var serviceDestroyed = false

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        db = CameraDatabase(this)
        nationalCameraCache = NationalCameraCache(this)
        deviceRepository = AuthoritativeDeviceRepository(db, nationalCameraCache)
        historyDb = RouteHistoryDatabase(this)
        localDiagnostics = LocalDiagnostics(this)
        ProximityAudioOwnership.register(ProximityAudioOwnership.Owner.SERVICE) { proximityTonePlayer.stop() }
        ProximityEncounterSession.restoreOnce(prefs().getString(ProximityEncounterSession.PREF_STATE_KEY, null))
        if (!activityVisible) ProximityAudioOwnership.transferTo(ProximityAudioOwnership.Owner.SERVICE)
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Active navigation · acquiring GPS"))
        WatchManSpeech.acquire(this, speechOwner)
        restoreActiveRouteIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        restoreActiveRouteIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification("WatchMan navigation active"))
        WatchManSpeech.acquire(this, speechOwner)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocationUpdates() {
        if (!hasLocationPermission() || activityVisible || locationUpdatesStarted) return
        var registered = false
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            registered = requestLocationProvider(LocationManager.GPS_PROVIDER, 1000L, 2f) || registered
        }
        registered = requestLocationProvider(LocationManager.NETWORK_PROVIDER, 2500L, 10f) || registered
        locationUpdatesStarted = registered
    }

    private fun requestLocationProvider(provider: String, intervalMs: Long, movementMeters: Float): Boolean = try {
        locationManager.requestLocationUpdates(provider, intervalMs, movementMeters, this)
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun stopLocationUpdates() {
        if (!locationUpdatesStarted) return
        runCatching { locationManager.removeUpdates(this) }
        locationUpdatesStarted = false
    }

    private fun updateActivityVisibility(visible: Boolean) {
        if (visible) {
            stopLocationUpdates()
            scheduleActivitySampleDrain()
        } else {
            pendingActivitySample.set(null)
            if (activeRoute != null) startLocationUpdates()
        }
    }

    private fun scheduleActivitySampleDrain() {
        if (serviceDestroyed || !activityVisible || activeRoute == null) return
        if (!activitySampleDrainScheduled.compareAndSet(false, true)) return
        executor.execute {
            try {
                val sample = pendingActivitySample.getAndSet(null)
                if (sample != null && activityVisible && activeRoute != null) {
                    val point = GeoPoint(sample.location.latitude, sample.location.longitude)
                    persistLastFix(point)
                    recordTripAnalytics(sample.location, point, sample.cameras)
                    requestCameraSync(point, sample.cameras.size, forceSyncRequested.getAndSet(false))
                }
            } finally {
                activitySampleDrainScheduled.set(false)
                if (pendingActivitySample.get() != null && activityVisible && activeRoute != null) {
                    scheduleActivitySampleDrain()
                }
            }
        }
    }

    private fun persistLastFix(point: GeoPoint) {
        if (serviceDestroyed) return
        prefs().edit()
            .putString("serviceLastLat", point.lat.toString())
            .putString("serviceLastLon", point.lon.toString())
            .putLong("serviceLastFixAt", System.currentTimeMillis())
            .apply()
    }

    override fun onLocationChanged(location: Location) {
        if (serviceDestroyed) return
        val point = GeoPoint(location.latitude, location.longitude)
        persistLastFix(point)

        val cameras = runCatching { deviceRepository.nearby(point, CAMERA_CACHE_RADIUS_METERS).devices }.getOrDefault(emptyList())
        recordTripAnalytics(location, point, cameras)
        val preferenceState = prefs()
        if (ProximityAudioOwnership.isOwner(ProximityAudioOwnership.Owner.SERVICE)) {
            val update = alertEngine.update(
                location,
                cameras,
                activeRoute,
                UserPreferences.proximityProfile(preferenceState)
            )
            persistProximityEncounterState(update)
            proximityTonePlayer.update(
                update.proximity,
                UserPreferences.proximityToneEnabled(preferenceState),
                UserPreferences.proximityVolumePercent(preferenceState)
            )
            if (prefs().getBoolean("voice", true)) update.speechAlerts.forEach {
                WatchManSpeech.camera(it, alertEngine.describe(it))
            }
            update.alert?.let { alert ->
                val message = alertEngine.describe(alert)
                vibrate()
                updateNotification("CAMERA AHEAD · $message")
            } ?: updateNotification("Navigation active · ${cameras.size} cached devices nearby")
        } else {
            proximityTonePlayer.stop()
            updateNotification("Navigation active · ${cameras.size} cached devices nearby")
        }

        requestCameraSync(point, cameras.size, forceSyncRequested.getAndSet(false))
    }



    private fun persistProximityEncounterState(update: AlertEngine.Update) {
        val now = System.nanoTime() / 1_000_000L
        val signature = update.proximity?.let { "${it.deviceId}|${it.stage}|${it.fovBasis}" } ?: "none"
        val transition = signature != lastProximityStateSignature
        if (transition || update.alert != null || now - lastProximityStatePersistAt >= PROXIMITY_STATE_PERSIST_INTERVAL_MS) {
            prefs().edit().putString(ProximityEncounterSession.PREF_STATE_KEY, ProximityEncounterSession.encode()).apply()
            update.diagnostic?.let { diagnostic ->
                localDiagnostics.recordProximityState(
                    LocalDiagnostics.ProximityState(
                        owner = "SERVICE",
                        deviceId = diagnostic.deviceId,
                        deviceType = diagnostic.deviceType,
                        deviceSourceId = diagnostic.deviceSourceId,
                        distanceMeters = diagnostic.distanceMeters,
                        euclideanDistanceMeters = diagnostic.euclideanDistanceMeters,
                        signedRouteDistanceMeters = diagnostic.signedRouteDistanceMeters,
                        routeDistanceUsed = diagnostic.routeDistanceUsed,
                        deviceDirectionDegrees = diagnostic.deviceDirectionDegrees,
                        travelBearingDegrees = diagnostic.travelBearingDegrees,
                        stage = diagnostic.stage.name,
                        encounterState = diagnostic.encounterState.name,
                        fovActive = diagnostic.fovDecision.active,
                        fovBasis = diagnostic.fovDecision.basis.name,
                        fovSupported = diagnostic.fovDecision.supported,
                        cadenceMs = diagnostic.cadenceMs,
                        toneRendererMode = diagnostic.toneRendererMode,
                        passed = diagnostic.passed,
                        devicesInRange = diagnostic.devicesInRange,
                        spokenAlert = diagnostic.spokenAlert
                    )
                )
            }
            lastProximityStatePersistAt = now
        }
        lastProximityStateSignature = signature
    }
    @Synchronized
    private fun recordTripAnalytics(location: Location, point: GeoPoint, cameras: List<CameraPoi>) {
        if (serviceDestroyed) return
        val tripId = prefs().getLong("activeTripId", -1L)
        val route = activeRoute ?: return
        if (tripId <= 0L) return

        if (analyticsTripId != tripId) {
            analyticsTripId = tripId
            analyticsRoute = null
            analyticsRouteIndex = null
            lastTrackPoint = null
            lastTrackAt = 0L
            lastEncounterScanPoint = null
            lastEncounterScanAt = 0L
            recordedEncounterIds.clear()
        }
        if (analyticsRoute !== route) {
            analyticsRoute = route
            analyticsRouteIndex = RouteProximityIndex(route, CAMERA_ROUTE_RELEVANCE_METERS)
        }

        val now = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        val moved = lastTrackPoint?.let { Geo.distanceMeters(it, point) } ?: Double.MAX_VALUE
        if (location.accuracy <= MAX_ANALYTICS_ACCURACY_METERS &&
            (now - lastTrackAt >= TRACK_SAMPLE_INTERVAL_MS || moved >= TRACK_SAMPLE_MOVE_METERS)) {
            runCatching { historyDb.appendTrackPoint(tripId, location) }
            lastTrackAt = now
            lastTrackPoint = point
        }

        val encounterMoved = lastEncounterScanPoint?.let { Geo.distanceMeters(it, point) } ?: Double.MAX_VALUE
        if (!NavigationAnalyticsPolicy.shouldScanEncounters(now, lastEncounterScanAt, encounterMoved)) return
        lastEncounterScanAt = now
        lastEncounterScanPoint = point
        val routeIndex = analyticsRouteIndex ?: return
        cameras.asSequence()
            .filter { it.id !in recordedEncounterIds }
            .filter { Geo.distanceMeters(point, it.point) <= CAMERA_PASS_RADIUS_METERS }
            .filter { routeIndex.isWithin(it.point) }
            .forEach { camera ->
                if (!recordedEncounterIds.add(camera.id)) return@forEach
                runCatching { historyDb.recordCameraEncounter(tripId, camera, now) }
                    .onFailure { recordedEncounterIds.remove(camera.id) }
            }
    }

    @Synchronized
    private fun requestCameraSync(point: GeoPoint, cachedDeviceCount: Int, force: Boolean) {
        if (serviceDestroyed) return
        val now = System.currentTimeMillis()
        val moved = lastSyncPoint?.let { Geo.distanceMeters(it, point) } ?: Double.MAX_VALUE
        val refreshMs = NavigationRuntimePolicy.refreshIntervalMs(cachedDeviceCount, activeRoute != null, consecutiveSyncFailures)
        val moveTrigger = NavigationRuntimePolicy.movementTriggerMeters(cachedDeviceCount, activeRoute != null)
        if (!force && now - lastSyncAt < refreshMs && moved < moveTrigger) return
        if (!coreSyncInFlight.compareAndSet(false, true)) return
        lastSyncAt = now
        lastSyncPoint = point

        executor.execute {
            try {
                // SafeTOC/OSM floor: each completion is committed independently.
                overpass.syncNearbyIncremental(point, CAMERA_QUERY_RADIUS_METERS) { result ->
                    if (!serviceDestroyed) {
                        if (result.status.ok) {
                            runCatching { db.replaceSourceSnapshot(result.status.sourceId, result.cameras) }
                            consecutiveSyncFailures = 0
                        } else {
                            consecutiveSyncFailures = (consecutiveSyncFailures + 1).coerceAtMost(5)
                        }
                        persistStatus(result.status.sourceId, result.status.ok, result.status.count, result.status.error)
                    }
                }
            } finally {
                coreSyncInFlight.set(false)
            }
        }
        // Supplemental feeds are additive, non-overlapping and cannot hold the Overpass floor open.
        if (supplementalSyncInFlight.compareAndSet(false, true)) {
            executor.execute {
                try {
                    runCatching {
                        publicFeeds.syncIncremental(point, prefs()) { result ->
                            if (!serviceDestroyed) {
                                if (result.status.ok) runCatching { db.replaceSourceSnapshot(result.status.sourceId, result.cameras) }
                                persistStatus(result.status.sourceId, result.status.ok, result.status.count, result.status.error)
                            }
                        }
                    }
                } finally {
                    supplementalSyncInFlight.set(false)
                }
            }
        }
    }

    private fun persistStatus(id: String, ok: Boolean, count: Int, error: String?) {
        if (serviceDestroyed) return
        prefs().edit().putString("camera_status_$id", if (ok) "ok|$count" else "error|${error ?: "unavailable"}").apply()
    }

    private fun vibrate() {
        val p = prefs()
        if (!UserPreferences.vibrationEnabled(p)) return
        runCatching {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(VibrationEffect.createWaveform(UserPreferences.vibrationPattern(p), -1))
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "WatchMan navigation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent navigation, location and camera-alert session"
                setShowBadge(false)
            })
        }
    }

    private fun buildNotification(text: String): Notification {
        val launch = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.bggg.watchman.R.mipmap.ic_launcher)
            .setContentTitle("WatchMan")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pending)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    }


    private fun restoreActiveRouteIfNeeded() {
        if (serviceDestroyed) return
        if (activeRoute != null) {
            startLocationUpdates()
            scheduleActivitySampleDrain()
            return
        }
        if (!prefs().getBoolean("activeNav", false)) return
        if (!restoreRouteInFlight.compareAndSet(false, true)) return
        val rawState = prefs().getString(ActiveRouteStateCodec.PREF_STATE_KEY, null)
        executor.execute {
            val restoredRoute = ActiveRouteStateCodec.decode(rawState)?.route
            mainHandler.post {
                restoreRouteInFlight.set(false)
                if (serviceDestroyed) return@post
                if (activeRoute == null) activeRoute = restoredRoute
                if (activeRoute != null) {
                    startLocationUpdates()
                    scheduleActivitySampleDrain()
                } else {
                    updateNotification("Active route could not be restored")
                }
            }
        }
    }
    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun prefs() = getSharedPreferences("watchman", Context.MODE_PRIVATE)

    override fun onProviderEnabled(provider: String) { }
    override fun onProviderDisabled(provider: String) { }
    @Deprecated("Deprecated in platform") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }

    override fun onDestroy() {
        serviceDestroyed = true
        restoreRouteInFlight.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        stopLocationUpdates()
        pendingActivitySample.set(null)
        if (activeInstance === this) activeInstance = null
        ExecutorShutdown.shutdownAll(listOf(executor), EXECUTOR_SHUTDOWN_WAIT_MS)
        ProximityAudioOwnership.unregister(ProximityAudioOwnership.Owner.SERVICE)
        proximityTonePlayer.release()
        WatchManSpeech.release(speechOwner)
        runCatching { db.close() }
        runCatching { nationalCameraCache.close() }
        runCatching { historyDb.close() }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "watchman_navigation"
        const val NOTIFICATION_ID = 914
        const val CAMERA_QUERY_RADIUS_METERS = 20_000
        const val CAMERA_CACHE_RADIUS_METERS = 25_000.0
        const val TRACK_SAMPLE_INTERVAL_MS = 5_000L
        const val TRACK_SAMPLE_MOVE_METERS = 25.0
        const val MAX_ANALYTICS_ACCURACY_METERS = 120f
        const val CAMERA_PASS_RADIUS_METERS = 90.0
        const val CAMERA_ROUTE_RELEVANCE_METERS = 145.0
        const val PROXIMITY_STATE_PERSIST_INTERVAL_MS = 5_000L
        const val EXECUTOR_SHUTDOWN_WAIT_MS = 750L

        @Volatile var activeRoute: Route? = null
        @Volatile var activityVisible: Boolean = false
            private set
        @Volatile private var activeInstance: NavigationForegroundService? = null
        private val forceSyncRequested = AtomicBoolean(false)
        private val pendingActivitySample = AtomicReference<ActivityLocationSample?>(null)

        fun start(context: Context, route: Route) {
            activeRoute = route
            forceSyncRequested.set(true)
            val intent = Intent(context, NavigationForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun requestImmediateCameraSync() { forceSyncRequested.set(true) }

        fun submitActivityLocation(location: Location, cameras: List<CameraPoi>) {
            if (!activityVisible || activeRoute == null) return
            pendingActivitySample.set(ActivityLocationSample(Location(location), cameras))
            activeInstance?.scheduleActivitySampleDrain()
        }

        fun setActivityVisible(visible: Boolean) {
            activityVisible = visible
            activeInstance?.updateActivityVisibility(visible)
        }

        fun stop(context: Context) {
            activeRoute = null
            context.stopService(Intent(context, NavigationForegroundService::class.java))
        }
    }
}
