package com.bggg.watchman

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bggg.watchman.alerts.AlertEngine
import com.bggg.watchman.alerts.WatchManSpeech
import com.bggg.watchman.ui.LaunchPresentation
import com.bggg.watchman.alerts.ProximityTonePlayer
import com.bggg.watchman.alerts.ProximityAudioOwnership
import com.bggg.watchman.alerts.ProximityEncounterSession
import com.bggg.watchman.export.AnalyticsReportExporter
import com.bggg.watchman.export.DirectionsExportMode
import com.bggg.watchman.export.DirectionsExporter
import com.bggg.watchman.cameras.AcquisitionPriorityCoordinator
import com.bggg.watchman.export.RouteAnalyticsEngine
import com.bggg.watchman.export.RouteExportSelection
import com.bggg.watchman.export.PreparedExportManager
import com.bggg.watchman.export.ExportWorkflowContext
import com.bggg.watchman.export.ExportArtifactType
import com.bggg.watchman.export.ExportWorkflowOrigin
import com.bggg.watchman.export.PreviewRouteWorkflowState
import com.bggg.watchman.cameras.OverpassCameraProvider
import com.bggg.watchman.cameras.PublicCameraProvider
import com.bggg.watchman.cameras.RegionalOverpassCameraProvider
import com.bggg.watchman.cameras.RegionalPublicCameraProvider
import com.bggg.watchman.cameras.RouteCameraCoverageProvider
import com.bggg.watchman.cameras.NationalCameraBootstrapProvider
import com.bggg.watchman.cameras.CountyCatalogProvider
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraSourceStatus
import com.bggg.watchman.data.CameraSourceResult
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.geo.StateBoundaryLookup
import com.bggg.watchman.data.Route
import com.bggg.watchman.data.RouteAnalysis
import com.bggg.watchman.data.RoutePreference
import com.bggg.watchman.data.SearchResult
import com.bggg.watchman.diagnostics.LocalDiagnostics
import com.bggg.watchman.location.LocationController
import com.bggg.watchman.navigation.NavigationActivationCoordinator
import com.bggg.watchman.history.RouteHistoryDatabase
import com.bggg.watchman.history.RouteHistoryEntry
import com.bggg.watchman.history.RouteFrequencySummary
import com.bggg.watchman.preferences.UserPreferences
import com.bggg.watchman.routing.OsrmRoutingProvider
import com.bggg.watchman.routing.NavigationProgress
import com.bggg.watchman.routing.RouteAnalyzer
import com.bggg.watchman.routing.RouteGeometry
import com.bggg.watchman.routing.RouteShaper
import com.bggg.watchman.routing.NoCamsObstaclePlanner
import com.bggg.watchman.routing.NoCamsRouteMonitor
import com.bggg.watchman.routing.RoadAwareExposure
import com.bggg.watchman.routing.RoadCoverageSource
import com.bggg.watchman.routing.RoadSourceDeviceNormalizer
import com.bggg.watchman.storage.RouteIdentity
import com.bggg.watchman.routing.ValhallaObstacleRoutingProvider
import com.bggg.watchman.storage.DeviceSearchRegion
import com.bggg.watchman.storage.RoutingDeviceSnapshot
import com.bggg.watchman.routing.RouteScorer
import com.bggg.watchman.routing.RoutePlanningPolicy
import com.bggg.watchman.routing.RouteContextPolicy
import com.bggg.watchman.routing.PendingRouteAction
import com.bggg.watchman.routing.PendingRouteRequest
import com.bggg.watchman.routing.PendingRouteActionPolicy
import com.bggg.watchman.routing.ScenicFeatureProvider
import com.bggg.watchman.search.NominatimSearchProvider
import com.bggg.watchman.storage.ActiveRouteState
import com.bggg.watchman.storage.ActiveRouteStateCodec
import com.bggg.watchman.storage.CameraDatabase
import com.bggg.watchman.storage.RegionalCameraCache
import com.bggg.watchman.storage.NationalCameraCache
import com.bggg.watchman.storage.BundledCameraSeed
import com.bggg.watchman.storage.AcquisitionHeadroomGuard
import com.bggg.watchman.storage.AuthoritativeDeviceRepository
import com.bggg.watchman.service.NavigationForegroundService
import com.bggg.watchman.service.NavigationRuntimePolicy
import com.bggg.watchman.ui.TileMapView
import com.bggg.watchman.ui.ManeuverSignView
import com.bggg.watchman.ui.MapDeviceDeclutter
import com.bggg.watchman.ui.AssetBitmapLoader
import com.bggg.watchman.ui.HeadingController
import com.bggg.watchman.ui.StateStarFieldView
import com.bggg.watchman.ui.BootstrapSpinnerView
import com.bggg.watchman.util.Geo
import com.bggg.watchman.util.ExecutorShutdown
import com.bggg.watchman.util.CoalescingWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import kotlin.math.roundToInt

/**
 * WatchMan v0.3.0.
 * Native Android Activity + native View map. No WebView/browser wrapper.
 */
class MainActivity : Activity() {
    private val executor: ExecutorService = Executors.newFixedThreadPool(6)
    private val navigationActivation = NavigationActivationCoordinator()
    private val routePlanning = NavigationActivationCoordinator()
    private val nationalExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val parallelWorkerCounter = AtomicInteger()
    private val parallelWorkerExecutor: ExecutorService = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "watchman-operation-${parallelWorkerCounter.incrementAndGet()}").apply { isDaemon = true }
    }
    @Volatile private var activityDestroyed = false
    private val main = Handler(Looper.getMainLooper())
    private val searchProvider = NominatimSearchProvider()
    private val routingProvider = OsrmRoutingProvider()
    private val cameraProvider = OverpassCameraProvider()
    private val publicCameraProvider = PublicCameraProvider()
    private val regionalCameraProvider = RegionalOverpassCameraProvider()
    private val regionalPublicCameraProvider = RegionalPublicCameraProvider()
    private val routeCameraCoverageProvider = RouteCameraCoverageProvider()
    private val acquisitionPriority = AcquisitionPriorityCoordinator()
    private val nationalCameraProvider = NationalCameraBootstrapProvider()
    private val scenicProvider = ScenicFeatureProvider()
    private val alertEngine = AlertEngine()
    private var lastProximityStatePersistAt = 0L
    private var lastProximityStateSignature: String? = null
    private val proximityTonePlayer = ProximityTonePlayer()

    private lateinit var map: TileMapView
    private lateinit var startInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var statusText: TextView
    private lateinit var tripText: TextView
    private lateinit var cameraText: TextView
    private lateinit var alertBanner: TextView
    private lateinit var alertButton: Button
    private lateinit var routeModeButton: Button
    private lateinit var compassImage: ImageView
    private lateinit var compassText: TextView
    private lateinit var speedImage: ImageView
    private lateinit var speedText: TextView
    private lateinit var rootView: FrameLayout
    private lateinit var searchBarView: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var routeControlsRow: LinearLayout
    private lateinit var mainActionsRow: LinearLayout
    private lateinit var compassHudView: FrameLayout
    private lateinit var speedHudView: FrameLayout
    private lateinit var recenterButton: Button
    private lateinit var fullScreenButton: Button
    private lateinit var stopButton: Button
    private lateinit var toolsButton: Button
    private lateinit var directionsButton: Button
    private lateinit var menuHost: FrameLayout
    private lateinit var menuPanel: LinearLayout
    private lateinit var menuTitle: TextView
    private lateinit var menuBody: LinearLayout
    private lateinit var menuBackButton: Button
    private lateinit var firstRunHost: FrameLayout
    private lateinit var firstRunIntroBox: LinearLayout
    private lateinit var firstRunIntroText: TextView
    private lateinit var completionNoticeView: TextView
    private lateinit var firstRunChoiceBox: LinearLayout
    private lateinit var firstRunSetupBox: LinearLayout
    private lateinit var firstRunProgress: ProgressBar
    private lateinit var firstRunBuffer: BootstrapSpinnerView
    private lateinit var firstRunSubtitle: TextView
    private lateinit var firstRunState: TextView
    private lateinit var firstRunCounty: TextView
    private lateinit var firstRunLocale: TextView
    private lateinit var firstRunNationalTitle: TextView
    private lateinit var firstRunDetail: TextView
    private lateinit var firstRunContinue: Button
    private lateinit var firstRunStarField: StateStarFieldView
    private var firstRunSubtitleAnimator: ValueAnimator? = null
    private var firstRunIntroAnimator: ValueAnimator? = null
    private var firstRunIntroDrawObserver: ViewTreeObserver? = null
    private var firstRunIntroDrawListener: ViewTreeObserver.OnDrawListener? = null
    private var firstRunIntroBaseColor: Int = Color.LTGRAY
    private val firstRunIntroTransitionRunnable = Runnable { transitionFirstRunIntroToChoice() }
    private var launchPresentation = LaunchPresentation()

    private var completionNoticeAnimator: ValueAnimator? = null
    private var completionNoticeBaseColor: Int = Color.LTGRAY
    private val completionNoticeQueue = ArrayDeque<String>()
    private val announcedLiveCompletionKeys = HashSet<String>()
    private var completionNoticeCurrent: String? = null
    private var activityResumed = false
    private val completionNoticeDismissRunnable = Runnable { dismissAcquisitionCompletionNotice() }

    private var firstRunSubtitleBaseColor: Int = Color.LTGRAY
    private var menuBackAction: (() -> Unit)? = null
    private var menuOpen = false
    private lateinit var headingController: HeadingController
    private lateinit var db: CameraDatabase
    private lateinit var historyDb: RouteHistoryDatabase
    private lateinit var regionalCameraCache: RegionalCameraCache
    private lateinit var nationalCameraCache: NationalCameraCache
    private lateinit var deviceRepository: AuthoritativeDeviceRepository
    private lateinit var exportManager: PreparedExportManager
    private lateinit var localDiagnostics: LocalDiagnostics
    private lateinit var stateBoundaryLookup: StateBoundaryLookup
    private lateinit var acquisitionHeadroomGuard: AcquisitionHeadroomGuard
    private lateinit var locationController: LocationController
    private lateinit var prefs: SharedPreferences

    private var currentLocation: Location? = null
    private var currentRoute: Route? = null
    private var currentRouteOrigin: SearchResult? = null
    private var activeRoutePreference: RoutePreference? = null
    private var previewRoute: Route? = null
    private var previewRouteOrigin: SearchResult? = null
    private var previewRoutePreference: RoutePreference? = null
    private var lastRouteAnalyses: List<RouteAnalysis> = emptyList()
    private data class RouteExposureRequest(
        val analyses: List<RouteAnalysis>, val origin: SearchResult?, val preference: RoutePreference?
    )
    private lateinit var routeExposureWorker: CoalescingWorker<RouteExposureRequest, List<RouteAnalysis>>
    private val routePreviewCameraLabels = HashMap<Route, TextView>()
    private var lastRouteAnalysisOrigin: SearchResult? = null
    private var lastRouteAnalysisPreference: RoutePreference? = null
    private var lastRoutePlanningNotice: String? = null
    private var activeTripId: Long = -1L
    private var currentCameras: List<CameraPoi> = emptyList()
    private var currentStep = 0
    private var spokenStep = -1
    private var spokenApproachStep = -1
    private var fullScreenNavigation = false
    private var routeEditorOpen = false
    private val speechOwner = "activity-${java.util.UUID.randomUUID()}"

    private var cameraRefreshAt = 0L
    private var cameraRefreshPoint: GeoPoint? = null
    private var deviceSnapshotAt = 0L
    private var deviceSnapshotPoint: GeoPoint? = null
    private var deviceSnapshotReady = false
    private data class DeviceLayerRequest(val point: GeoPoint?, val route: Route?, val active: Route?, val preview: Route?,
        val checkNoCams: Boolean = false)
    private data class DeviceLayerResult(val devices: List<CameraPoi>, val obstacleAhead: Boolean,
        val roadCoverageUnknown: Boolean = false)
    private val roadCoverageSource by lazy { RoadCoverageSource(File(filesDir,"road-coverage")) }
    private val roadCoverageLock = Any()
    private val roadCoverage = LinkedHashMap<String,RoadAwareExposure.Verified>(4,0.75f,true)
    private lateinit var deviceLayerWorker: CoalescingWorker<DeviceLayerRequest, DeviceLayerResult>
    private var lastNoCamsCheckAt = 0L
    private data class NationalSourceSummary(val regions: String, val devices: String, val details: List<String>, val failed: Boolean)
    private lateinit var nationalSourceSummaryWorker: CoalescingWorker<LinearLayout, NationalSourceSummary>
    private data class DirectionsDeviceRequest(
        val route: Route, val host: LinearLayout, val summaryPrefix: String,
        val map: TileMapView, val stepLabels: Map<Int, TextView>, val preference: RoutePreference
    )
    private data class DirectionsDevices(
        val count: Int, val mapCameras: List<CameraPoi>, val stepCameras: Map<Int, List<CameraPoi>>,
        val exposureLabel: String
    )
    private lateinit var directionsDeviceWorker: CoalescingWorker<DirectionsDeviceRequest, DirectionsDevices>
    private var latestDeviceLayerRequest: DeviceLayerRequest? = null
    private var cameraLastAttemptAt = 0L
    @Volatile private var cameraSyncInFlight = false
    private var cameraSyncPending = false
    private var cameraSyncPendingForce = false
    private var cameraSyncError: String? = null
    private var cameraSourceStatuses: List<CameraSourceStatus> = emptyList()
    @Volatile private var cameraGeneration = 0L

    @Volatile private var routePlanningInFlight = false
    @Volatile private var routeCoverageGeneration = 0L
    @Volatile private var routeCoverageInFlight = false
    private var routeCoverageCompleted = 0
    private var routeCoverageTotal = 0
    private var routeCoverageSuccess = 0
    private var routeCoverageFailure = 0
    private var routeCoverageCameraCount = 0
    private var routeCoverageError: String? = null
    private var routeCameraSourceStatuses: List<CameraSourceStatus> = emptyList()
    private var lastRerouteAt = 0L
    private var offRouteFixes = 0
    private var restoreAttempted = false
    private var restoreDecodeAttempted = false
    @Volatile private var restoreInFlight = false
    private var preparedExport: PreparedExportManager.PreparedExport? = null
    private var preparedExportContext = ExportWorkflowContext.generic()
    private var preparedExportContextEncoded: String? = null
    private var pendingRouteRequest: PendingRouteRequest? = null
    @Volatile private var nationalBootstrapInFlight = false
    @Volatile private var nationalBootstrapStopRequested = false
    @Volatile private var currentGpsStateSourceId: String? = null
    @Volatile private var gpsStateLookupInFlight = false
    private var lastGpsStateLookupAt = 0L
    private var lastGpsStateLookupPoint: GeoPoint? = null

    private var firstRunNearbyReady = false
    private var nationalCountyTotal = com.bggg.watchman.cameras.CountyCatalogProvider.FALLBACK_COUNTY_EQUIVALENT_TOTAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchPresentation = LaunchPresentation(
            savedInstanceState?.getString("launchPresentationStage"),
            savedInstanceState?.getLong("launchPresentationRemaining", LaunchPresentation.INTRO_MILLIS)
                ?: LaunchPresentation.INTRO_MILLIS
        )
        prefs = getSharedPreferences("watchman", Context.MODE_PRIVATE)
        if (prefs.getBoolean("activeNav", false)) {
            ProximityEncounterSession.restoreOnce(prefs.getString(ProximityEncounterSession.PREF_STATE_KEY, null))
        } else {
            ProximityEncounterSession.reset()
        }
        ProximityAudioOwnership.register(ProximityAudioOwnership.Owner.ACTIVITY) { proximityTonePlayer.stop() }
        setTheme(if (UserPreferences.isLight(prefs)) R.style.AppThemeLight else R.style.AppTheme)
        if (prefs.getBoolean("keepScreenOn", true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        db = CameraDatabase(this)
        historyDb = RouteHistoryDatabase(this)
        regionalCameraCache = RegionalCameraCache(this)
        nationalCameraCache = NationalCameraCache(this)
        deviceRepository = AuthoritativeDeviceRepository(db, nationalCameraCache, regionalCameraCache)
        deviceLayerWorker = CoalescingWorker(executor, ::postToUi, { request ->
            check(Looper.myLooper() != Looper.getMainLooper()) { "Device snapshots must load off-main" }
            val cameras = if (request.route != null) routeCameraCandidates(listOf(request.route))
                else nearbyCameraCandidates(checkNotNull(request.point))
            val checkStarted = monotonicMillis()
            val cancelled = { Thread.currentThread().isInterrupted || monotonicMillis() - checkStarted >= 2_000L }
            val checkActive = { check(!cancelled()) { "No Cams remaining-route check cancelled or deadline reached" } }
            val remaining = if (request.checkNoCams && request.active != null && request.point != null)
                NoCamsRouteMonitor.remainingRoute(request.active, request.point, checkActive) else null
            var roadCoverageUnknown = false
            val obstacleAhead = remaining?.let { route ->
                val snapshot = noCamsDeviceSnapshot(route, checkNotNull(request.point), cancelled)
                val original=checkNotNull(request.active)
                val originalProof=cachedRoadCoverage(original) ?: readRoadCoverage(original,snapshot,
                    (2_000L-(monotonicMillis()-checkStarted)).coerceAtLeast(0L),false,cancelled)
                val proof=RoadAwareExposure.remainingCoverage(original,route,originalProof,System.currentTimeMillis(),checkActive)
                val assessment=RoadAwareExposure.assess(route,snapshot,proof,System.currentTimeMillis(),checkActive)
                val checkResult=NoCamsRouteMonitor.decision(assessment)
                roadCoverageUnknown=checkResult.coverageUnknown
                checkResult.obstacleAhead
            } ?: false
            DeviceLayerResult(cameras, obstacleAhead, roadCoverageUnknown)
        }, ::publishDeviceLayer)
        nationalSourceSummaryWorker = CoalescingWorker(executor, ::postToUi,
            { readNationalSourceSummary() }, ::publishNationalSourceSummary)
        directionsDeviceWorker = CoalescingWorker(executor, ::postToUi,
            ::readDirectionsDevices, ::publishDirectionsDevices)
        routeExposureWorker = CoalescingWorker(executor, ::postToUi, { request ->
            check(Looper.myLooper() != Looper.getMainLooper()) { "Route exposure must load off-main" }
            val cameras = routeCameraCandidates(request.analyses.map { it.route }, requireAvailable = true)
            request.analyses.map { analysis ->
                if(request.preference==RoutePreference.FEWEST_CAMERAS) {
                    val started=monotonicMillis()
                    val cancelled={ Thread.currentThread().isInterrupted || monotonicMillis()-started>=2_000L }
                    val snapshot=noCamsDeviceSnapshot(analysis.route,analysis.route.requestedOrigin ?: analysis.route.points.first(),cancelled)
                    val proof=cachedRoadCoverage(analysis.route) ?: readRoadCoverage(analysis.route,snapshot,
                        (2_000L-(monotonicMillis()-started)).coerceAtLeast(0L),false,cancelled)
                    val assessedAt=System.currentTimeMillis()
                    val assessed=RoadAwareExposure.assess(analysis.route,snapshot,proof,assessedAt) {
                        check(!cancelled()) { "No Cams exposure refresh deadline reached" }
                    }
                    analysis.copy(knownCameraCount=assessed.nearbyRelevantDevices.size,
                        routedExposure=RoadAwareExposure.summary(assessed,proof,assessedAt))
                } else analysis.copy(knownCameraCount =
                    RouteAnalyzer.countKnownCameras(analysis.route, cameras, ROUTE_CAMERA_CORRIDOR_METERS),routedExposure=null)
            }
        }, ::publishRouteExposure)
        exportManager = PreparedExportManager(this)
        localDiagnostics = LocalDiagnostics(this)
        stateBoundaryLookup = StateBoundaryLookup(this)
        executor.execute { stateBoundaryLookup.preload() }
        acquisitionHeadroomGuard = AcquisitionHeadroomGuard(this)
        activeTripId = prefs.getLong("activeTripId", -1L)
        applySystemAppearance()

        buildUi()
        headingController = HeadingController(this, ::onHeading)
        headingController.start()
        locationController = LocationController(this, ::onLocation)
        restoreNavigationIfNeeded()

        restorePreparedExportState(savedInstanceState)
        restorePendingRouteRequestState()
        showFirstRunChoiceIfNeeded()
        main.postDelayed({ showPendingRouteRecoveryIfNeeded() }, 1_200L)

        // Keep the very first visual interaction as the explicit setup choice. Location permission
        // is requested after the user chooses Setup or Jump to Nav; setup itself remains GPS-optional.
        if (launchPresentation.stage == LaunchPresentation.Stage.NAVIGATION) ensureLocationStartedOrRequested()

        main.postDelayed({ initTtsSafely() }, 900L)
        scheduleBundledSeedInstall()
    }

    private fun postToUi(action: () -> Unit) {
        if (activityDestroyed) return
        main.post {
            if (!activityDestroyed) action()
        }
    }

    private fun scheduleBundledSeedInstall() {
        executor.execute {
            runCatching {
                awaitBackgroundAcquisitionWindow()
                BundledCameraSeed.installIfNeeded(this, nationalCameraCache)
            }.onSuccess { result ->
                val summary = runCatching { nationalCameraCache.successfulCountyCount() to nationalCameraCache.totalCameraCount() }
                    .onFailure { android.util.Log.w("WatchMan", "Seed cache summary unavailable; retaining displayed progress", it) }.getOrNull()
                postToUi {
                    refreshDisplayedCameraLayerFromCaches()
                    summary?.let { updateFirstRunNationalStats(it.first, it.second) }
                    if (result.installed) {
                        android.util.Log.i("WatchMan", "Installed bundled seed: ${result.recordCount} records across ${result.jurisdictionCount} jurisdictions")
                    }
                }
            }.onFailure { error ->
                android.util.Log.w("WatchMan", "Bundled seed remains unavailable; live acquisition continues", error)
            }
        }
    }

    private fun buildUi() {
        rootView = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(18, 20, 24))
            fitsSystemWindows = false
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }

        map = TileMapView(this).apply {
            onCameraTap = ::showCameraInfo
            setAlertDistanceFeet(prefs.getInt("alertFeet", 2000))
        }
        rootView.addView(map, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val light = UserPreferences.isLight(prefs)
        val panelColor = if (light) Color.argb(248, 248, 248, 246) else Color.argb(250, 25, 28, 34)
        val primaryText = if (light) Color.rgb(25, 27, 31) else Color.WHITE
        val secondaryText = if (light) Color.rgb(75, 79, 86) else Color.rgb(202, 207, 216)
        rootView.setBackgroundColor(if (light) Color.rgb(235, 235, 231) else Color.rgb(18, 20, 24))

        searchBarView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(panelColor, 20f)
            setPadding(dp(12), dp(5), dp(7), dp(5))
            elevation = dpF(6f)
        }
        startInput = EditText(this).apply {
            hint = "Start: (Street address, City, ST, ZIP) · blank = GPS"
            setHintTextColor(if (light) Color.rgb(105, 108, 114) else Color.rgb(165, 171, 182))
            setTextColor(primaryText)
            textSize = 14f
            setSingleLine(true)
            setBackgroundColor(Color.TRANSPARENT)
            imeOptions = EditorInfo.IME_ACTION_NEXT
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(5), 0, dp(8), 0)
        }
        val destinationRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        searchInput = EditText(this).apply {
            hint = "Destination: (Street address, City, ST, ZIP)"
            setHintTextColor(if (light) Color.rgb(105, 108, 114) else Color.rgb(165, 171, 182))
            setTextColor(primaryText)
            textSize = 14f
            setSingleLine(true)
            setBackgroundColor(Color.TRANSPARENT)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(5), 0, dp(8), 0)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) { beginSearch(); true } else false
            }
        }
        val go = largeButton("GO").apply { setOnClickListener { beginSearch() } }
        destinationRow.addView(searchInput, LinearLayout.LayoutParams(0, dp(42), 1f))
        destinationRow.addView(go, LinearLayout.LayoutParams(dp(66), dp(40)))
        searchBarView.addView(startInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)))
        searchBarView.addView(destinationRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
        rootView.addView(searchBarView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(90), Gravity.TOP).apply {
            setMargins(dp(10), dp(10), dp(10), 0)
        })

        alertBanner = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(252, 177, 38, 38), 16f)
            setPadding(dp(11), dp(7), dp(11), dp(7))
            elevation = dpF(7f)
        }
        rootView.addView(alertBanner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(10), dp(108), dp(10), 0)
        })

        recenterButton = largeButton("◎").apply {
            textSize = 26f
            contentDescription = "Recenter map on my location"
            setOnClickListener { map.recenter() }
        }
        rootView.addView(recenterButton, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END or Gravity.BOTTOM).apply {
            setMargins(0, 0, dp(10), dp(250))
        })

        compassHudView = FrameLayout(this).apply {
            background = rounded(if (light) Color.argb(225, 250, 250, 248) else Color.argb(210, 14, 16, 20), 13f)
            elevation = dpF(5f)
            contentDescription = "Compass heading. Long press for camera provider settings."
            setOnLongClickListener { showProviderSettings(); true }
        }
        compassImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageBitmap(AssetBitmapLoader.load(this@MainActivity, "wm10/compass_ball.png", dp(50)))
        }
        compassText = TextView(this).apply {
            text = "--°"
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(primaryText)
            setTypeface(typeface, Typeface.BOLD)
        }
        compassHudView.addView(compassImage, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(2) })
        compassHudView.addView(compassText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(18), Gravity.BOTTOM))
        rootView.addView(compassHudView, FrameLayout.LayoutParams(dp(58), dp(68), Gravity.END or Gravity.BOTTOM).apply {
            setMargins(0, 0, dp(10), dp(178))
        })

        speedHudView = FrameLayout(this).apply {
            background = rounded(if (light) Color.argb(225, 250, 250, 248) else Color.argb(210, 14, 16, 20), 13f)
            elevation = dpF(5f)
            contentDescription = "Live GPS speed. Tap to toggle mph and km/h."
            setOnClickListener {
                val metric = !prefs.getBoolean("speedMetric", false)
                prefs.edit().putBoolean("speedMetric", metric).apply()
                currentLocation?.let { updateSpeedHud(it) }
            }
        }
        speedImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageBitmap(AssetBitmapLoader.load(this@MainActivity, "wm10/speed_gauge.png", dp(58)))
        }
        speedText = TextView(this).apply {
            text = "--\nMPH"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(primaryText)
            setTypeface(typeface, Typeface.BOLD)
        }
        speedHudView.addView(speedImage, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
        speedHudView.addView(speedText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootView.addView(speedHudView, FrameLayout.LayoutParams(dp(66), dp(66), Gravity.START or Gravity.BOTTOM).apply {
            setMargins(dp(10), 0, 0, dp(178))
        })

        bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(if (light) Color.argb(252, 250, 250, 247) else Color.argb(252, 22, 25, 31), 20f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            elevation = dpF(8f)
        }
        statusText = TextView(this).apply {
            text = "Waiting for GPS…"
            setTextColor(primaryText)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
        }
        tripText = TextView(this).apply {
            text = "WatchMan v0.3.0"
            setTextColor(secondaryText)
            textSize = 11f
            maxLines = 2
            setPadding(0, dp(2), 0, 0)
        }
        cameraText = TextView(this).apply {
            text = "Cameras / sensors: waiting for GPS · Alert: ${alertDistanceShort()}"
            setTextColor(Color.rgb(238, 170, 80))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            setPadding(0, dp(4), 0, dp(1))
            isClickable = true
            isFocusable = true
            contentDescription = "Camera and sensor layer status. Tap to refresh; long press for source diagnostics."
            setOnClickListener {
                if (previewRoute != null && currentRoute == null) {
                    toast("Refreshing cameras / sensors along this route…")
                    retryRouteCoverage()
                } else {
                    toast("Refreshing camera / sensor layer…")
                    refreshCameras(force = true)
                }
            }
            setOnLongClickListener { showCameraSourceStatus(); true }
        }

        routeControlsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, 0) }
        routeModeButton = largeButton(routeModeLabel()).apply { setOnClickListener {
            val route = routeShownOnMap()
            showRoutePreferenceDialog(route?.destination, route?.let(::routeOriginFor))
        } }
        fullScreenButton = largeButton("FULL SCREEN").apply { setOnClickListener { setFullScreenNavigation(!fullScreenNavigation) } }
        routeControlsRow.addView(routeModeButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) })
        routeControlsRow.addView(fullScreenButton, LinearLayout.LayoutParams(dp(112), dp(38)).apply { marginStart = dp(4) })

        mainActionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, 0) }
        directionsButton = largeButton("DIRECTIONS").apply {
            contentDescription = "Open turn-by-turn directions for the route shown on the map"
            setOnClickListener {
                val route = routeShownOnMap()
                if (route == null) toast("Start or preview a route first")
                else showTurnByTurnDirections(route)
            }
        }

        toolsButton = largeButton("TOOLS").apply { setOnClickListener { if (fullScreenNavigation) setFullScreenNavigation(false) else showToolsMenu() } }
        alertButton = toolsButton
        stopButton = largeButton("STOP ROUTE", danger = true).apply { setOnClickListener { stopNavigation() } }
        mainActionsRow.addView(toolsButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) })
        mainActionsRow.addView(directionsButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) })
        mainActionsRow.addView(stopButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4) })

        bottomPanel.addView(statusText)
        bottomPanel.addView(tripText)
        bottomPanel.addView(cameraText)
        bottomPanel.addView(routeControlsRow)
        bottomPanel.addView(mainActionsRow)
        rootView.addView(bottomPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(10), 0, dp(10), dp(10))
        })
        bottomPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncFloatingControlInsets()
            syncMapViewportInsets()
        }

        // Persistent in-app menu surface for navigation/settings/history workflows. This replaces
        // transient AlertDialog menus so the map remains visible and the user keeps context.
        menuHost = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            elevation = dpF(32f)
            translationZ = dpF(32f)
            setBackgroundColor(panelColor)
        }
        menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setBackgroundColor(panelColor)
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        val menuHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        menuBackButton = largeButton("‹").apply {
            textSize = 26f
            visibility = View.GONE
            contentDescription = "Back"
            setOnClickListener { menuBackAction?.invoke() ?: closeMenu() }
        }
        menuTitle = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
        }
        val menuClose = largeButton("✕").apply {
            textSize = 18f
            contentDescription = "Close menu"
            setOnClickListener { closeMenu() }
        }
        menuHeader.addView(menuBackButton, LinearLayout.LayoutParams(dp(48), dp(44)))
        menuHeader.addView(menuTitle, LinearLayout.LayoutParams(0, dp(44), 1f))
        menuHeader.addView(menuClose, LinearLayout.LayoutParams(dp(48), dp(44)))
        menuPanel.addView(menuHeader)
        menuBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(16))
        }
        val menuScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(menuBody, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        menuPanel.addView(menuScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        menuHost.addView(menuPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        rootView.addView(menuHost, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        firstRunHost = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setBackgroundColor(if (light) Color.rgb(246, 246, 243) else Color.rgb(18, 20, 24))
            elevation = dpF(40f)
            translationZ = dpF(40f)
        }
        firstRunIntroBaseColor = secondaryText
        firstRunIntroBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(24), dp(30), dp(24), dp(30))
        }
        firstRunIntroText = TextView(this).apply {
            text = "Isaiah 21:6"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(firstRunIntroBaseColor)
            gravity = Gravity.CENTER
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        firstRunIntroBox.addView(firstRunIntroText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))


        // First screen shown on a clean v0.2.7 install. National setup is explicitly optional;
        // choosing Jump to Nav never blocks or alters the proven live nearby camera/audio runtime.
        firstRunChoiceBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(30), dp(24), dp(30))
        }
        firstRunChoiceBox.addView(TextView(this).apply {
            text = "WatchMan First Run"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(26))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        fun firstRunChoice(textValue: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = textValue
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText)
            gravity = Gravity.CENTER
            background = rounded(if (light) Color.rgb(231, 237, 244) else Color.rgb(34, 42, 54), 18f)
            elevation = dpF(5f)
            isClickable = true
            isFocusable = true
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setOnClickListener { onClick() }
        }

        val runSetupChoice = firstRunChoice("Run Setup (Recommended But Takes a While)") {
            prefs.edit().putString(PREF_FIRST_RUN_DECISION, FIRST_RUN_DECISION_SETUP).putBoolean(PREF_FIRST_RUN_ACK, false).apply()
            showFirstRunSetupScreen()
            startFirstRunBootstrapIfNeeded(showUi = true)
            ensureLocationStartedOrRequested()
        }
        firstRunChoiceBox.addView(runSetupChoice, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76)).apply {
            bottomMargin = dp(18)
        })
        val jumpToNavChoice = firstRunChoice("Jump to Nav") {
            prefs.edit().putString(PREF_FIRST_RUN_DECISION, FIRST_RUN_DECISION_NAV).putBoolean(PREF_FIRST_RUN_ACK, true).apply()
            ensureLocationStartedOrRequested()
            hideFirstRunHostAndRestoreLiveRuntime()
        }
        firstRunChoiceBox.addView(jumpToNavChoice, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68)))

        firstRunSetupBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(dp(24), dp(18), dp(24), dp(128))
        }
        firstRunBuffer = BootstrapSpinnerView(this)
        firstRunSetupBox.addView(firstRunBuffer, LinearLayout.LayoutParams(dp(88), dp(88)).apply {
            bottomMargin = dp(18)
        })
        firstRunSetupBox.addView(TextView(this).apply {
            text = "Preparing Watchman"
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        firstRunSubtitleBaseColor = secondaryText
        firstRunSubtitle = TextView(this).apply {
            text = "The WatchMan is Collecting\nNational Camera and Sensor Data"
            textSize = 14f
            setTextColor(firstRunSubtitleBaseColor)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(24))
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        firstRunSetupBox.addView(firstRunSubtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        firstRunState = TextView(this).apply {
            text = "Preparing state catalog"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText)
            gravity = Gravity.CENTER
        }
        firstRunSetupBox.addView(firstRunState, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        firstRunCounty = TextView(this).apply {
            text = "County-equivalent catalog"
            textSize = 16f
            setTextColor(primaryText)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        firstRunSetupBox.addView(firstRunCounty, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        firstRunLocale = TextView(this).apply {
            text = "Locale — / —"
            textSize = 14f
            setTextColor(secondaryText)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(22))
        }
        firstRunSetupBox.addView(firstRunLocale, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        firstRunNationalTitle = TextView(this).apply {
            text = "National"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText)
            gravity = Gravity.CENTER
        }
        firstRunSetupBox.addView(firstRunNationalTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        firstRunDetail = TextView(this).apply {
            text = "0 / ${formatCount(nationalCountyTotal)} county-equivalents complete\n0 cameras / sensors cached"
            textSize = 13f
            setTextColor(secondaryText)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        }
        firstRunSetupBox.addView(firstRunDetail, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        firstRunProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1
            progress = 0
        }
        firstRunSetupBox.addView(firstRunProgress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18)))
        firstRunContinue = largeButton("CONTINUE TO MAP").apply {
            setOnClickListener {
                prefs.edit().putBoolean(PREF_FIRST_RUN_ACK, true).apply()
                hideFirstRunHostAndRestoreLiveRuntime()
            }
        }
        firstRunSetupBox.addView(firstRunContinue, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(16)
        })

        firstRunStarField = StateStarFieldView(this).apply { setCompletedStates(0) }
        firstRunHost.addView(firstRunIntroBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        firstRunHost.addView(firstRunChoiceBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        firstRunHost.addView(firstRunSetupBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        firstRunHost.addView(firstRunStarField, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(104), Gravity.BOTTOM).apply {
            leftMargin = dp(34)
            rightMargin = dp(34)
            bottomMargin = dp(18)
        })
        rootView.addView(firstRunHost, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        completionNoticeBaseColor = secondaryText
        completionNoticeView = TextView(this).apply {
            visibility = View.GONE
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(completionNoticeBaseColor)
            gravity = Gravity.CENTER
            background = rounded(if (light) Color.argb(236, 236, 240, 246) else Color.argb(236, 30, 36, 46), 18f)
            elevation = dpF(60f)
            isClickable = false
            isFocusable = false
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        rootView.addView(
            completionNoticeView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                leftMargin = dp(20)
                rightMargin = dp(20)
                topMargin = dp(58)
            }
        )
        setContentView(rootView)
        applyNavigationLayout()
    }

    private fun ensureLocationStartedOrRequested() {
        if (locationController.hasPermission()) {
            locationController.start()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION
            )
        }
    }
    private fun showFirstRunIntroThenChoice() {
        firstRunHost.visibility = View.VISIBLE
        firstRunIntroBox.visibility = View.VISIBLE
        firstRunChoiceBox.visibility = View.GONE
        firstRunSetupBox.visibility = View.GONE
        firstRunStarField.visibility = View.GONE
        firstRunHost.bringToFront()
        scheduleFirstRunIntroTransition()
    }

    private fun scheduleFirstRunIntroTransition() {
        main.removeCallbacks(firstRunIntroTransitionRunnable)
        removeFirstRunIntroDrawListener()
        if (!activityResumed || launchPresentation.stage != LaunchPresentation.Stage.INTRO) return
        val observer = firstRunIntroText.viewTreeObserver
        var started = false
        lateinit var listener: ViewTreeObserver.OnDrawListener
        listener = ViewTreeObserver.OnDrawListener {
            if (!started && activityResumed && firstRunIntroText.isShown && launchPresentation.stage == LaunchPresentation.Stage.INTRO) {
                started = true
                // onResume can precede the first visible frame by hundreds of milliseconds.
                // Only drawn intro time earns credit toward the requested three seconds.
                startFirstRunIntroBreathing()
                main.postDelayed(firstRunIntroTransitionRunnable, launchPresentation.resume(SystemClock.elapsedRealtime()))
                postToUi {
                    // Android forbids removing an OnDrawListener during its draw callback.
                    if (firstRunIntroDrawListener === listener) removeFirstRunIntroDrawListener()
                }
            }
        }
        firstRunIntroDrawObserver = observer
        firstRunIntroDrawListener = listener
        observer.addOnDrawListener(listener)
        firstRunIntroText.invalidate()
    }

    private fun removeFirstRunIntroDrawListener() {
        val observer = firstRunIntroDrawObserver
        val listener = firstRunIntroDrawListener
        if (observer?.isAlive == true && listener != null) observer.removeOnDrawListener(listener)
        firstRunIntroDrawObserver = null
        firstRunIntroDrawListener = null
    }

    private fun startFirstRunIntroBreathing() {
        if (!::firstRunIntroText.isInitialized || firstRunIntroAnimator?.isRunning == true) return
        firstRunIntroAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = LaunchPresentation.INTRO_MILLIS
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val pulse = animator.animatedValue as Float
                val mix = 0.14f + 0.38f * pulse
                val target = Color.rgb(112, 212, 255)
                val r = (Color.red(firstRunIntroBaseColor) * (1f - mix) + Color.red(target) * mix).roundToInt()
                val g = (Color.green(firstRunIntroBaseColor) * (1f - mix) + Color.green(target) * mix).roundToInt()
                val b = (Color.blue(firstRunIntroBaseColor) * (1f - mix) + Color.blue(target) * mix).roundToInt()
                firstRunIntroText.setTextColor(Color.rgb(r, g, b))
                firstRunIntroText.alpha = 0.90f + 0.10f * pulse
                firstRunIntroText.setShadowLayer(
                    dpF(3.5f + 10.5f * pulse),
                    0f,
                    0f,
                    Color.argb((58 + 122 * pulse).roundToInt(), 72, 183, 255)
                )
            }
            start()
        }
    }

    private fun stopFirstRunIntroBreathing() {
        main.removeCallbacks(firstRunIntroTransitionRunnable)
        removeFirstRunIntroDrawListener()
        firstRunIntroAnimator?.cancel()
        firstRunIntroAnimator = null
        if (::firstRunIntroText.isInitialized) {
            firstRunIntroText.alpha = 1f
            firstRunIntroText.setTextColor(firstRunIntroBaseColor)
            firstRunIntroText.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    private fun transitionFirstRunIntroToChoice() {
        if (!activityResumed || !launchPresentation.advance(SystemClock.elapsedRealtime())) return
        showLaunchChoice()
    }

    private fun showLaunchChoice() {
        stopFirstRunIntroBreathing()
        firstRunHost.visibility = View.VISIBLE
        firstRunIntroBox.visibility = View.GONE
        firstRunChoiceBox.visibility = View.VISIBLE
        firstRunSetupBox.visibility = View.GONE
        firstRunStarField.visibility = View.GONE
        firstRunHost.bringToFront()
    }


    private fun showFirstRunChoiceIfNeeded() {
        when (launchPresentation.stage) {
            LaunchPresentation.Stage.INTRO -> showFirstRunIntroThenChoice()
            LaunchPresentation.Stage.CHOICE -> showLaunchChoice()
            LaunchPresentation.Stage.SETUP -> showFirstRunSetupScreen()
            LaunchPresentation.Stage.NAVIGATION -> firstRunHost.visibility = View.GONE
        }
        // Acquisition resumes independently; its old choice never hides this launch's verse/choices.
        if (prefs.getString(PREF_FIRST_RUN_DECISION, null) == FIRST_RUN_DECISION_SETUP) {
            startFirstRunBootstrapIfNeeded(showUi = false)
        }
    }

    private fun showFirstRunSetupScreen() {
        launchPresentation.showSetup()
        firstRunHost.visibility = View.VISIBLE
        firstRunChoiceBox.visibility = View.GONE
        stopFirstRunIntroBreathing()
        firstRunIntroBox.visibility = View.GONE
        firstRunSetupBox.visibility = View.VISIBLE
        firstRunStarField.visibility = View.VISIBLE
        firstRunHost.bringToFront()
        startFirstRunBreathingGlow()
        if (::firstRunBuffer.isInitialized) firstRunBuffer.start()
        firstRunState.text = "Preparing state catalog"
        firstRunCounty.text = "County-equivalent catalog"
        firstRunLocale.text = "Locale — / —"
        firstRunProgress.max = 1
        firstRunProgress.progress = 0
        executor.execute {
            runCatching {
                Triple(nationalCameraCache.successfulCountyCount(), nationalCameraCache.totalCameraCount(), nationalCameraCache.successfulRegionIds())
            }.onSuccess { summary ->
                postToUi {
                    if (launchPresentation.stage == LaunchPresentation.Stage.SETUP) {
                        updateFirstRunNationalStats(summary.first, summary.second)
                        updateFirstRunCompletionStars(summary.third)
                    }
                }
            }.onFailure { android.util.Log.w("WatchMan", "Setup cache summary unavailable; retaining displayed progress", it) }
        }
    }

    private fun hideFirstRunHostAndRestoreLiveRuntime() {
        launchPresentation.showNavigation()
        stopFirstRunIntroBreathing()
        stopFirstRunBreathingGlow()
        if (::firstRunBuffer.isInitialized) firstRunBuffer.stop()
        firstRunHost.visibility = View.GONE
        applyNavigationLayout()
        currentLocation?.let { location ->
            val point = GeoPoint(location.latitude, location.longitude)
            refreshDeviceSnapshot(point, force = true)
            updateCameraStatusText()
            refreshCameras(force = true)
        }
        initTtsSafely()
        postToUi { showPendingRouteRecoveryIfNeeded() }
    }

    private fun awaitBackgroundAcquisitionWindow() {
        val shouldStop = {
            nationalBootstrapStopRequested || Thread.currentThread().isInterrupted
        }
        acquisitionPriority.awaitNationalTurn(shouldStop)
        val ready = acquisitionHeadroomGuard.awaitSafeToAcquire(
            shouldStop = shouldStop,
            onWaiting = { snapshot ->
                val detail = if (!snapshot.storageReady) {
                    "Available ${formatBytes(snapshot.availableStorageBytes)}; protecting ${formatBytes(snapshot.requiredStorageBytes)} before acquiring more data."
                } else {
                    "App heap headroom ${formatBytes(snapshot.appHeapHeadroomBytes)}; live navigation keeps priority."
                }
                postToUi {
                    if (::firstRunLocale.isInitialized &&
                        firstRunHost.visibility == View.VISIBLE &&
                        firstRunSetupBox.visibility == View.VISIBLE) {
                        firstRunLocale.text = "Acquisition paused - ${snapshot.pauseReason()}"
                        firstRunDetail.text = detail
                    }
                }
            },
            onResumed = {
                postToUi {
                    if (::firstRunLocale.isInitialized &&
                        firstRunHost.visibility == View.VISIBLE &&
                        firstRunSetupBox.visibility == View.VISIBLE) {
                        firstRunLocale.text = "Resource headroom restored - resuming"
                        firstRunDetail.text = "National acquisition will continue from its saved live-source progress."
                    }
                }
            }
        )
        if (!ready) throw InterruptedException("Background acquisition stopped")
        acquisitionPriority.awaitNationalTurn(shouldStop)
    }

    private fun startFirstRunBootstrapIfNeeded(showUi: Boolean = false) {
        if (nationalBootstrapInFlight) {
            if (showUi) showFirstRunSetupScreen()
            return
        }
        if (showUi) showFirstRunSetupScreen()

        nationalBootstrapStopRequested = false
        nationalBootstrapInFlight = true
        nationalExecutor.execute {
            try {
                val successfulStates = nationalCameraCache.successfulRegionIds()
                val successfulCounties = nationalCameraCache.successfulCountyIds()
                val successfulLocales = nationalCameraCache.successfulLocaleIds()
                val successfulSources = nationalCameraCache.successfulSourceIds()
                val result = nationalCameraProvider.syncMissing(
                    alreadySuccessfulRegions = successfulStates,
                    alreadySuccessfulCounties = successfulCounties,
                    alreadySuccessfulLocales = successfulLocales,
                    alreadySuccessfulSources = successfulSources,
                    shouldContinue = { !nationalBootstrapStopRequested && !Thread.currentThread().isInterrupted },
                    preferredRegionSourceId = { currentGpsStateSourceId },
                    // National acquisition runs independently of the live GPS/map path. Every
                    // SafeTOC parity locale call uses the canonical SafeTOC Overpass endpoint and
                    // exact GET/query contract; WatchMan-only additive families run afterwards.
                    beforeNetwork = { awaitBackgroundAcquisitionWindow() },
                    onNationalCount = { total ->
                        nationalCountyTotal = total.coerceAtLeast(1)
                        val done = runCatching { nationalCameraCache.successfulCountyCount() }.getOrDefault(0)
                        val cached = runCatching { nationalCameraCache.totalCameraCount() }.getOrDefault(0)
                        postToUi { updateFirstRunNationalStats(done, cached) }
                    },
                    onStateStart = { region, countyCount ->
                        postToUi {
                            if (firstRunHost.visibility == View.VISIBLE && firstRunSetupBox.visibility == View.VISIBLE) {
                                firstRunState.text = region.name
                                firstRunCounty.text = "$countyCount county-equivalents"
                                firstRunLocale.text = "Preparing county locales"
                                firstRunProgress.max = 1
                                firstRunProgress.progress = 0
                            }
                        }
                    },
                    onCountyStart = { region, county, localeTotal, progress ->
                        val cached = runCatching { nationalCameraCache.totalCameraCount() }.getOrDefault(0)
                        postToUi {
                            if (::firstRunState.isInitialized) {
                                firstRunState.text = region.name
                                firstRunCounty.text = county.name
                                firstRunLocale.text = "Locale 1 / ${localeTotal.coerceAtLeast(1)}"
                                firstRunProgress.max = localeTotal.coerceAtLeast(1)
                                firstRunProgress.progress = 0
                                updateFirstRunNationalStats(progress.successfulCounties, cached)
                            }
                        }
                    },
                    onFamilyResult = { region, county, cell, familyResult, localeProgress ->
                        persistNationalFamilyResult(region, county, cell, familyResult)
                        val cached = runCatching { nationalCameraCache.totalCameraCount() }.getOrDefault(0)
                        val doneCounties = runCatching { nationalCameraCache.successfulCountyCount() }.getOrDefault(0)
                        postToUi {
                            if (::firstRunLocale.isInitialized) {
                                val depth = if (localeProgress.subdivisionDepth > 0) " · refining ${localeProgress.subdivisionDepth}" else ""
                                firstRunState.text = region.name
                                firstRunCounty.text = county.name
                                firstRunLocale.text = "Locale ${localeProgress.currentLocaleNumber} / ${localeProgress.totalLocales}$depth"
                                firstRunProgress.max = localeProgress.totalLocales.coerceAtLeast(1)
                                firstRunProgress.progress = localeProgress.completedLocales.coerceIn(0, localeProgress.totalLocales)
                                updateFirstRunNationalStats(doneCounties, cached)
                            }
                        }
                    },
                    onLocaleResolved = { _, county, ok, localeProgress ->
                        val localeId = "national_v026_${county.geoid}_locale_${localeProgress.currentLocaleNumber.toString().padStart(4, '0')}"
                        runCatching {
                            if (ok) nationalCameraCache.markLocaleSuccess(county.countyId, localeId)
                            else nationalCameraCache.markLocaleFailure(county.countyId, localeId, "Locale unresolved; will resume")
                        }
                        val cached = runCatching { nationalCameraCache.totalCameraCount() }.getOrDefault(0)
                        val doneCounties = runCatching { nationalCameraCache.successfulCountyCount() }.getOrDefault(0)
                        postToUi {
                            firstRunProgress.max = localeProgress.totalLocales.coerceAtLeast(1)
                            firstRunProgress.progress = localeProgress.completedLocales.coerceIn(0, localeProgress.totalLocales)
                            firstRunLocale.text = "Locale ${localeProgress.completedLocales.coerceAtMost(localeProgress.totalLocales)} / ${localeProgress.totalLocales}"
                            updateFirstRunNationalStats(doneCounties, cached)
                        }
                    },
                    onCountyResult = { region, county, ok, _, error, progress ->
                        val localeTotal = runCatching { nationalCameraProvider.buildLocales(county).size }.getOrDefault(0)
                        val completed = if (ok) localeTotal else 0
                        runCatching {
                            if (ok) nationalCameraCache.markCountySuccess(region.sourceId, county.countyId, county.name, localeTotal)
                            else nationalCameraCache.markCountyFailure(region.sourceId, county.countyId, county.name, localeTotal, completed, error)
                        }
                        val cached = runCatching { nationalCameraCache.totalCameraCount() }.getOrDefault(0)
                        val doneCounties = runCatching { nationalCameraCache.successfulCountyCount() }.getOrDefault(progress.successfulCounties)
                        postToUi {
                            firstRunState.text = region.name
                            firstRunCounty.text = if (ok) "${county.name} complete" else "${county.name} incomplete · will resume"
                            firstRunLocale.text = if (ok) "All $localeTotal locales complete" else "County locales will resume"
                            updateFirstRunNationalStats(doneCounties, cached)
                            if (ok && county.countyId !in successfulCounties && announcedLiveCompletionKeys.add("county:${county.countyId}")) {
                                enqueueAcquisitionCompletionNotice("A Watchman has been set in ${county.name}")
                            }
                        }
                    },
                    onStateResult = { region, ok, _, error, _ ->
                        runCatching {
                            if (ok) nationalCameraCache.markRegionSuccess(region.sourceId)
                            else nationalCameraCache.markRegionFailure(region.sourceId, error)
                        }
                        val states = runCatching { nationalCameraCache.successfulRegionIds() }.getOrDefault(emptySet())
                        val cached = runCatching { nationalCameraCache.totalCameraCount() }.getOrDefault(0)
                        val doneCounties = runCatching { nationalCameraCache.successfulCountyCount() }.getOrDefault(0)
                        postToUi {
                            updateFirstRunCompletionStars(states)
                            updateFirstRunNationalStats(doneCounties, cached)
                            if (ok && region.sourceId !in successfulStates && announcedLiveCompletionKeys.add("state:${region.sourceId}")) {
                                enqueueAcquisitionCompletionNotice("A Watchman has been set in ${region.name}")
                            }
                        }
                    }
                )

                if (nationalBootstrapStopRequested || Thread.currentThread().isInterrupted) return@execute
                val states = runCatching { nationalCameraCache.successfulRegionIds() }.getOrDefault(emptySet())
                val complete = states.size >= NationalCameraBootstrapProvider.REGIONS.size
                prefs.edit().putBoolean(PREF_NATIONAL_BOOTSTRAP_COMPLETE, complete).apply()
                val cached = runCatching { nationalCameraCache.totalCameraCount() }.getOrDefault(result.totalCamerasThisRun)
                val doneCounties = runCatching { nationalCameraCache.successfulCountyCount() }.getOrDefault(result.successfulCounties)
                postToUi {
                    if (complete) {
                        if (::firstRunBuffer.isInitialized) firstRunBuffer.stop()
                        firstRunState.text = "National cache ready"
                        firstRunCounty.text = "All county-equivalents complete"
                        firstRunLocale.text = "Setup complete"
                    } else if (firstRunHost.visibility == View.VISIBLE && firstRunSetupBox.visibility == View.VISIBLE) {
                        firstRunState.text = result.currentRegion?.name ?: "National cache paused"
                        firstRunCounty.text = result.currentCounty?.name ?: "Unresolved county-equivalents will resume"
                    }
                    updateFirstRunNationalStats(doneCounties, cached)
                    updateFirstRunCompletionStars(states)
                }
                // First-run completion is now independent of WatchMan-only enrichment. Once every
                // locale has earned completion through the exact SafeTOC local ALPR + traffic scan,
                // keep the existing broader WatchMan source families as a best-effort background pass.
                if (complete && !nationalBootstrapStopRequested && !Thread.currentThread().isInterrupted) {
                    nationalCameraProvider.syncAdditiveMissing(
                        alreadySuccessfulSources = runCatching { nationalCameraCache.successfulSourceIds() }.getOrDefault(emptySet()),
                        shouldContinue = { !nationalBootstrapStopRequested && !Thread.currentThread().isInterrupted },
                        beforeNetwork = { awaitBackgroundAcquisitionWindow() },
                        onFamilyResult = { region, county, cell, familyResult, _ ->
                            persistNationalFamilyResult(region, county, cell, familyResult)
                        }
                    )
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                nationalBootstrapInFlight = false
            }
        }
    }

    private fun persistNationalFamilyResult(
        region: NationalCameraBootstrapProvider.Region,
        county: CountyCatalogProvider.County,
        cell: NationalCameraBootstrapProvider.LocaleCell,
        familyResult: NationalCameraBootstrapProvider.FamilyResult
    ) {
        val source = familyResult.sourceResult
        runCatching {
            if (source.status.ok || source.cameras.isNotEmpty()) {
                nationalCameraCache.replaceSourceSnapshot(
                    region.sourceId,
                    county.countyId,
                    cell.baseLocaleId,
                    familyResult.snapshotId,
                    familyResult.family.key,
                    source.cameras,
                    source.status.ok && !familyResult.saturated,
                    source.status.error
                )
            } else {
                nationalCameraCache.markSourceFailure(
                    region.sourceId,
                    county.countyId,
                    cell.baseLocaleId,
                    familyResult.snapshotId,
                    familyResult.family.key,
                    source.status.error
                )
            }
        }
    }

    private fun startFirstRunBreathingGlow() {
        if (::firstRunStarField.isInitialized) firstRunStarField.setBreathingEnabled(true)
        if (!::firstRunSubtitle.isInitialized || firstRunSubtitleAnimator?.isRunning == true) return
        firstRunSubtitleAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 3600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val pulse = animator.animatedValue as Float
                val mix = 0.14f + 0.38f * pulse
                val target = Color.rgb(112, 212, 255)
                val r = (Color.red(firstRunSubtitleBaseColor) * (1f - mix) + Color.red(target) * mix).roundToInt()
                val g = (Color.green(firstRunSubtitleBaseColor) * (1f - mix) + Color.green(target) * mix).roundToInt()
                val b = (Color.blue(firstRunSubtitleBaseColor) * (1f - mix) + Color.blue(target) * mix).roundToInt()
                firstRunSubtitle.setTextColor(Color.rgb(r, g, b))
                firstRunSubtitle.alpha = 0.90f + 0.10f * pulse
                firstRunSubtitle.setShadowLayer(dpF(3.5f + 10.5f * pulse), 0f, 0f, Color.argb((58 + 122 * pulse).roundToInt(), 72, 183, 255))
            }
            start()
        }
    }

    private fun stopFirstRunBreathingGlow() {
        if (::firstRunStarField.isInitialized) firstRunStarField.setBreathingEnabled(false)
        firstRunSubtitleAnimator?.cancel()
        firstRunSubtitleAnimator = null
        if (::firstRunSubtitle.isInitialized) {
            firstRunSubtitle.alpha = 1f
            firstRunSubtitle.setTextColor(firstRunSubtitleBaseColor)
            firstRunSubtitle.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }
    private fun enqueueAcquisitionCompletionNotice(message: String) {
        if (message == completionNoticeCurrent || completionNoticeQueue.contains(message)) return
        completionNoticeQueue.addLast(message)
        showNextAcquisitionCompletionNotice()
    }

    private fun showNextAcquisitionCompletionNotice() {
        if (!activityResumed ||
            !::completionNoticeView.isInitialized ||
            completionNoticeCurrent != null ||
            completionNoticeQueue.isEmpty()) {
            return
        }
        val message = completionNoticeQueue.removeFirst()
        completionNoticeCurrent = message
        completionNoticeView.text = message
        completionNoticeView.visibility = View.VISIBLE
        completionNoticeView.bringToFront()
        completionNoticeView.announceForAccessibility(message)
        completionNoticeAnimator?.cancel()
        completionNoticeAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 3600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val pulse = animator.animatedValue as Float
                val mix = 0.14f + 0.38f * pulse
                val target = Color.rgb(112, 212, 255)
                val r = (Color.red(completionNoticeBaseColor) * (1f - mix) + Color.red(target) * mix).roundToInt()
                val g = (Color.green(completionNoticeBaseColor) * (1f - mix) + Color.green(target) * mix).roundToInt()
                val b = (Color.blue(completionNoticeBaseColor) * (1f - mix) + Color.blue(target) * mix).roundToInt()
                completionNoticeView.setTextColor(Color.rgb(r, g, b))
                completionNoticeView.alpha = 0.90f + 0.10f * pulse
                completionNoticeView.setShadowLayer(
                    dpF(3.5f + 10.5f * pulse),
                    0f,
                    0f,
                    Color.argb((58 + 122 * pulse).roundToInt(), 72, 183, 255)
                )
            }
            start()
        }
        main.removeCallbacks(completionNoticeDismissRunnable)
        main.postDelayed(completionNoticeDismissRunnable, COMPLETION_NOTICE_VISIBLE_MS)
    }

    private fun dismissAcquisitionCompletionNotice() {
        main.removeCallbacks(completionNoticeDismissRunnable)
        completionNoticeAnimator?.cancel()
        completionNoticeAnimator = null
        if (::completionNoticeView.isInitialized) {
            completionNoticeView.visibility = View.GONE
            completionNoticeView.alpha = 1f
            completionNoticeView.setTextColor(completionNoticeBaseColor)
            completionNoticeView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        completionNoticeCurrent = null
        if (activityResumed && completionNoticeQueue.isNotEmpty()) {
            main.postDelayed({ showNextAcquisitionCompletionNotice() }, COMPLETION_NOTICE_GAP_MS)
        }
    }

    private fun suspendAcquisitionCompletionNotice() {
        main.removeCallbacks(completionNoticeDismissRunnable)
        completionNoticeCurrent?.let { current ->
            if (!completionNoticeQueue.contains(current)) completionNoticeQueue.addFirst(current)
        }
        completionNoticeCurrent = null
        completionNoticeAnimator?.cancel()
        completionNoticeAnimator = null
        if (::completionNoticeView.isInitialized) completionNoticeView.visibility = View.GONE
    }


    private fun updateFirstRunCompletionStars(successfulRegionIds: Set<String>) {
        if (!::firstRunStarField.isInitialized) return
        val completedStates = successfulRegionIds.count { it in NationalCameraBootstrapProvider.STATE_SOURCE_IDS }
        firstRunStarField.setCompletedStates(completedStates)
    }

    private fun updateFirstRunNationalStats(completedCounties: Int, cachedDevices: Int) {
        if (!::firstRunDetail.isInitialized) return
        firstRunDetail.text = "${formatCount(completedCounties)} / ${formatCount(nationalCountyTotal)} county-equivalents complete\n${formatCount(cachedDevices)} cameras / sensors cached"
    }

    private fun formatCount(value: Int): String = String.format(Locale.US, "%,d", value.coerceAtLeast(0))

    private fun updateFirstRunNearbyState() {
        val liveSucceeded = cameraSourceStatuses.any { it.ok } || (cameraRefreshAt > 0L && currentCameras.isNotEmpty())
        if (liveSucceeded) firstRunNearbyReady = true
        // Nearby acquisition is intentionally independent from the national setup screen. It is
        // allowed to run in parallel and continues to publish to the live map/audio path normally.
    }

    private fun routeCameraCandidates(
        routes: List<Route>,
        corridorMeters: Double = ROUTE_CAMERA_CORRIDOR_METERS,
        requireAvailable: Boolean = false
    ): List<CameraPoi> {
        if (routes.isEmpty()) return emptyList()
        val snapshot = deviceRepository.alongRoutes(routes, corridorMeters, includeRegional = true)
        return (if (requireAvailable) snapshot.requireAvailable() else snapshot).devices
    }

    private fun noCamsDeviceSnapshot(route: Route, origin: GeoPoint, cancelled: () -> Boolean): RoutingDeviceSnapshot {
        val region = DeviceSearchRegion.forOnlineRoute(route, listOf(origin, route.destination.point),
            ROUTE_CAMERA_CORRIDOR_METERS) { check(!cancelled()) { "No Cams query preparation cancelled or deadline reached" } }
        return deviceRepository.withinRegion(region, maximumRowsPerLayer = 50_000, cancelled = cancelled)
            .freezeForRouting(region.queryScope)
    }

    private fun rememberRoadCoverage(route: Route, proof: RoadAwareExposure.Verified) {
        val identity=RouteIdentity.sha256(route)
        synchronized(roadCoverageLock) {
            roadCoverage[identity]=proof
            while(roadCoverage.size>2) roadCoverage.remove(roadCoverage.keys.first())
        }
    }

    private fun cachedRoadCoverage(route: Route): RoadAwareExposure.Verified? {
        val identity=RouteIdentity.sha256(route)
        val proof=synchronized(roadCoverageLock) { roadCoverage[identity] } ?: return null
        return proof.takeIf { RoadAwareExposure.isCurrent(route,it,System.currentTimeMillis()) }
    }

    private fun readRoadCoverage(route: Route, snapshot: RoutingDeviceSnapshot, remainingMillis: Long,
        allowNetwork: Boolean, cancelled: () -> Boolean, claimTraceRequest: () -> Unit = {}): RoadAwareExposure.Verified? {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "Source-road observations must load off-main" }
        if(remainingMillis<=0L) return null
        val started=monotonicMillis()
        val active={ check(!cancelled() && monotonicMillis()-started<remainingMillis) { "Source-road observation deadline reached" } }
        val result=roadCoverageSource.observe(route,snapshot,remainingMillis,allowNetwork,cancelled,
            onTraceRequest=claimTraceRequest)
        active()
        if(allowNetwork && result.sourceNodes.isNotEmpty()) {
            val discovered=result.sourceNodes.mapNotNull { receipt -> active(); RoadSourceDeviceNormalizer.normalize(receipt) }
            // Additive acquisition only. Original individual receipt times are retained;
            // no setup-completion flag, source clearing or fake regional credit is written.
            if(discovered.isNotEmpty()) db.upsertObservations(discovered,active)
        }
        val observation=result.observation ?: result.attribution ?: return null
        val proof=RoadAwareExposure.verify(route,observation,System.currentTimeMillis(),active) ?: return null
        if(result.observation!=null) rememberRoadCoverage(route,proof)
        return proof
    }

    private fun cachedRouteExposure(route: Route): com.bggg.watchman.data.RoutedDeviceExposure? = runCatching {
        val started=monotonicMillis()
        val cancelled={ Thread.currentThread().isInterrupted || monotonicMillis()-started>=2_000L }
        val snapshot=noCamsDeviceSnapshot(route,route.requestedOrigin ?: route.points.first(),cancelled)
        val proof=cachedRoadCoverage(route) ?: readRoadCoverage(route,snapshot,
            (2_000L-(monotonicMillis()-started)).coerceAtLeast(0L),false,cancelled)
        val assessedAt=System.currentTimeMillis()
        val assessment=RoadAwareExposure.assess(route,snapshot,proof,assessedAt) {
            check(!cancelled()) { "Cached routed-exposure deadline reached" }
        }
        RoadAwareExposure.summary(assessment,proof,assessedAt)
    }.getOrNull()

    private fun nearbyCameraCandidates(point: GeoPoint): List<CameraPoi> {
        return deviceRepository.nearby(point, CAMERA_CACHE_RADIUS_METERS).devices
    }

    private fun refreshDeviceSnapshot(point: GeoPoint, force: Boolean = false): Boolean {
        val now = monotonicMillis()
        val moved = deviceSnapshotPoint?.let { Geo.distanceMeters(it, point) } ?: Double.MAX_VALUE
        if (!force && !NavigationRuntimePolicy.shouldRefreshDeviceSnapshot(
                deviceSnapshotReady,
                currentRoute != null,
                now - deviceSnapshotAt,
                moved
            )) return false
        return requestDeviceLayer(DeviceLayerRequest(point, null, currentRoute, previewRoute))
    }

    private fun requestDeviceLayer(request: DeviceLayerRequest): Boolean {
        val now = monotonicMillis()
        val check = request.point != null && request.active != null &&
            activeRoutePreference == RoutePreference.FEWEST_CAMERAS && now - lastNoCamsCheckAt >= REROUTE_MIN_INTERVAL_MS
        val scheduled = request.copy(checkNoCams = check)
        if (check) lastNoCamsCheckAt = now
        latestDeviceLayerRequest = scheduled
        return deviceLayerWorker.request(scheduled)
    }

    private fun publishDeviceLayer(request: DeviceLayerRequest, result: Result<DeviceLayerResult>) {
        if (request.active !== currentRoute || request.preview !== previewRoute || request.route !== latestDeviceLayerRequest?.route) return
        val location = currentLocation
        if (request.point != null && location != null &&
            Geo.distanceMeters(request.point, GeoPoint(location.latitude, location.longitude)) > CAMERA_CACHE_RADIUS_METERS / 2.0) return
        result.onSuccess { layer ->
            val snapshot = layer.devices
            currentCameras = snapshot
            deviceSnapshotAt = monotonicMillis()
            deviceSnapshotPoint = request.point
            deviceSnapshotReady = request.route == null
            map.setCameras(snapshot)
            updateCameraStatusText()
            if (request.route == null && location != null) {
                handleCameraAlert(location)
                if (currentRoute != null) NavigationForegroundService.submitActivityLocation(location, snapshot)
            }
            val active = currentRoute
            if(layer.roadCoverageUnknown && active!=null && activeRoutePreference==RoutePreference.FEWEST_CAMERAS) {
                statusText.text="No Cams road coverage needs refresh"
                lastRoutePlanningNotice="Nearby devices remain visible. Affected-road coverage is unavailable or expired; the exact route is retained and zero exposure is not reconfirmed."
            }
            if (layer.obstacleAhead && active != null && activeRoutePreference == RoutePreference.FEWEST_CAMERAS &&
                !routePlanningInFlight && System.currentTimeMillis() - lastRerouteAt > REROUTE_MIN_INTERVAL_MS) {
                lastRerouteAt = System.currentTimeMillis()
                planRoutes(active.destination, RoutePreference.FEWEST_CAMERAS, automatic = true)
            }
        }.onFailure { android.util.Log.w("WatchMan", "Device snapshot unavailable; retaining previous cache", it) }
    }

    private fun refreshDisplayedCameraLayerFromCaches() {
        val preview = previewRoute.takeIf { currentRoute == null }
        if (preview != null) updatePreviewCameraLayer()
        else currentLocation?.let { refreshDeviceSnapshot(GeoPoint(it.latitude, it.longitude), force = true) }
    }

    private fun showMenu(title: String, backAction: (() -> Unit)? = null, build: (LinearLayout) -> Unit) {
        routePreviewCameraLabels.clear()
        menuTitle.text = title
        menuBackAction = backAction
        menuBackButton.visibility = if (backAction == null) View.GONE else View.VISIBLE
        menuBody.removeAllViews()
        build(menuBody)
        menuOpen = true
        applyNavigationLayout()
        menuHost.visibility = View.VISIBLE
        menuHost.bringToFront()
        menuHost.elevation = dpF(32f)
        menuHost.translationZ = dpF(32f)
    }

    private fun closeMenu() {
        routePreviewCameraLabels.clear()
        if (::menuHost.isInitialized) menuHost.visibility = View.GONE
        if (::menuBody.isInitialized) menuBody.removeAllViews()
        menuBackAction = null
        menuOpen = false
        applyNavigationLayout()
    }

    private fun addMenuText(container: LinearLayout, textValue: String, emphasized: Boolean = false) {
        val light = UserPreferences.isLight(prefs)
        container.addView(TextView(this).apply {
            text = textValue
            textSize = if (emphasized) 16f else 13f
            setTextColor(if (light) Color.rgb(38, 41, 46) else Color.rgb(220, 224, 231))
            if (emphasized) setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(7), dp(4), dp(7))
        })
    }

    private fun addMenuAction(
        container: LinearLayout,
        label: String,
        detail: String? = null,
        danger: Boolean = false,
        onClick: () -> Unit
    ) {
        val button = largeButton(label, danger).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 3
            setPadding(dp(14), dp(5), dp(12), dp(5))
            setOnClickListener { onClick() }
        }
        container.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(if (detail == null) 50 else 56)).apply {
            topMargin = dp(4)
        })
        if (!detail.isNullOrBlank()) {
            addMenuText(container, detail)
        }
    }

    private fun showToolsMenu() {
        showMenu("Tools") { body ->
            addMenuAction(body, "Edit / new route", "Enter a starting address and destination, or leave Start blank to use live GPS.") {
                closeMenu()
                routeEditorOpen = true
                applyNavigationLayout()
                startInput.requestFocus()
            }
            val routeForDirections = routeShownOnMap()
            if (routeForDirections != null) {
                addMenuAction(body, "Turn-by-turn directions", "Directly open the route map, maneuvers, roads/exits and camera/sensor context.") {
                    showTurnByTurnDirections(routeForDirections)
                }
                addMenuAction(body, "Save turn-by-turn + map PDF", "Render from existing cache, validate it, then open the Android save picker.") {
                    closeMenu(); exportCurrentDirections(DirectionsExportMode.SELECTED_ROUTE)
                }
            }
            addMenuAction(
                body,
                "Acquisition progress",
                "See current state, county, locale, national totals and completed-state stars."
            ) {
                closeMenu()
                prefs.edit().putString(PREF_FIRST_RUN_DECISION, FIRST_RUN_DECISION_SETUP).apply()
                showFirstRunSetupScreen()
                startFirstRunBootstrapIfNeeded(showUi = true)
            }
            addMenuAction(body, "Export & reports", "Directions, route alternatives, personal analytics reports and raw route-history data.") { showExportReportsMenu() }
            if (previewRoute != null) {
                val returnDetail = if (currentRoute != null) {
                    "Return the map and controls to the active navigation route."
                } else {
                    "Return the map to live GPS follow mode."
                }
                addMenuAction(body, "Clear route preview", returnDetail) {
                    clearRoutePreview()
                    closeMenu()
                }
            }
            addMenuAction(body, "Settings", "Appearance, alert distance/volume, vibration, speed units and screen behavior.") { showSettings() }
            addMenuAction(body, "Routes / history", "Review recent/most/least traveled routes and open personal route analytics.") { showRoutesWorkflow() }
            addMenuAction(body, "Camera source status", "See which camera/sensor feeds succeeded and refresh them.") { showCameraSourceStatus() }
            addMenuAction(body, "Local route / proximity diagnostics", "View bounded app-private planning and alert-state evidence, save it through Android, or clear it.") { showLocalDiagnostics() }
            addMenuAction(body, "Camera provider settings", "Optional regional API keys and custom HTTPS feeds.") { showProviderSettings() }
        }
    }

    private fun showLocalDiagnostics() {
        showMenu("Local diagnostics", { showToolsMenu() }) { body ->
            addMenuText(
                body,
                "These bounded records stay in WatchMan private storage. There is no remote telemetry. Saving is an explicit user action through Android's document picker.",
                emphasized = true
            )
            addMenuText(body, localDiagnostics.summary())
            addMenuAction(body, "SAVE DIAGNOSTICS LOG", "Prepare the current JSONL evidence and open the Android save picker.") {
                val bytes = localDiagnostics.exportBytes()
                closeMenu()
                requestDocumentExport(
                    bytes,
                    "WatchMan-local-diagnostics-${fileStamp()}.txt",
                    "text/plain",
                    ExportWorkflowContext.generic()
                )
            }
            addMenuAction(body, "CLEAR LOCAL DIAGNOSTICS", "Delete only WatchMan's private route/proximity diagnostic records.", danger = true) {
                localDiagnostics.clear()
                showLocalDiagnostics()
            }
        }
    }

    private fun setFullScreenNavigation(enabled: Boolean) {
        if (enabled && currentRoute == null) {
            toast("Start a route before entering full-screen navigation")
            return
        }
        fullScreenNavigation = enabled
        @Suppress("DEPRECATION")
        if (enabled) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            applySystemAppearance()
        }
        applyNavigationLayout()
    }

    private fun applyNavigationLayout() {
        if (!::searchBarView.isInitialized) return
        val active = currentRoute != null
        val previewing = previewRoute != null && !active
        val showSearch = !menuOpen && !fullScreenNavigation && (!active || routeEditorOpen)
        searchBarView.visibility = if (showSearch) View.VISIBLE else View.GONE
        bottomPanel.visibility = if (menuOpen) View.GONE else View.VISIBLE
        routeControlsRow.visibility = if (fullScreenNavigation) View.GONE else View.VISIBLE
        fullScreenButton.visibility = if (active) View.VISIBLE else View.GONE
        stopButton.visibility = if (active) View.VISIBLE else View.GONE
        directionsButton.visibility = if (active || previewing) View.VISIBLE else View.GONE
        setTextIfChanged(toolsButton, if (previewing) "TOOLS · PREVIEW" else if (fullScreenNavigation) "EXIT FULL" else "TOOLS")
        tripText.visibility = if (fullScreenNavigation) View.GONE else View.VISIBLE
        cameraText.visibility = if (fullScreenNavigation) View.GONE else View.VISIBLE
        val detailLines = if (active) 1 else 2
        if (cameraText.maxLines != detailLines) cameraText.maxLines = detailLines
        if (tripText.maxLines != detailLines) tripText.maxLines = detailLines
        if (statusText.maxLines != 2) statusText.maxLines = 2

        if (menuOpen) {
            alertBanner.visibility = View.GONE
            speedHudView.visibility = View.GONE
            compassHudView.visibility = View.GONE
            recenterButton.visibility = View.GONE
        } else {
            speedHudView.visibility = View.VISIBLE
            compassHudView.visibility = View.VISIBLE
            recenterButton.visibility = View.VISIBLE
        }

        (alertBanner.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val top = dp(if (showSearch) 106 else 10)
            val side = dp(10)
            if (lp.topMargin != top || lp.leftMargin != side || lp.rightMargin != side) {
                lp.topMargin = top
                lp.leftMargin = side
                lp.rightMargin = side
                alertBanner.layoutParams = lp
            }
        }
        bottomPanel.setPadding(dp(10), dp(if (fullScreenNavigation) 5 else 7), dp(10), dp(if (fullScreenNavigation) 5 else 7))
        setTextIfChanged(routeModeButton, routeModeLabel())
        postToUi {
            syncFloatingControlInsets()
            syncMapViewportInsets()
        }
    }

    private fun syncFloatingControlInsets() {
        if (!::bottomPanel.isInitialized || menuOpen) return
        val base = if (bottomPanel.visibility == View.VISIBLE && bottomPanel.height > 0) bottomPanel.height + dp(18) else dp(18)
        setBottomMarginPixels(speedHudView, base)
        setBottomMarginPixels(compassHudView, base)
        setBottomMarginPixels(recenterButton, base + dp(76))
    }

    private fun syncMapViewportInsets() {
        if (!::map.isInitialized) return
        val top = if (::searchBarView.isInitialized && searchBarView.visibility == View.VISIBLE && searchBarView.height > 0) searchBarView.height + dp(22) else dp(14)
        val bottom = if (::bottomPanel.isInitialized && bottomPanel.visibility == View.VISIBLE && bottomPanel.height > 0) bottomPanel.height + dp(22) else dp(14)
        map.setViewportInsets(top, bottom)
    }

    private fun setBottomMarginPixels(view: View, bottomPx: Int) {
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.bottomMargin == bottomPx) return
        lp.bottomMargin = bottomPx
        view.layoutParams = lp
    }

    private fun setBottomMargin(view: View, bottomDp: Int) {
        setBottomMarginPixels(view, dp(bottomDp))
    }

    /** Only for plain HUD/control strings, not styled text or editable fields. */
    private fun setTextIfChanged(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }
    private fun setPendingRouteRequest(action: PendingRouteRequest?, encodedAction: String? = null) {
        pendingRouteRequest = action
        if (action == null) prefs.edit().remove(PREF_PENDING_ROUTE_ACTION).apply()
        else prefs.edit().putString(PREF_PENDING_ROUTE_ACTION, encodedAction ?: action.encode()).apply()
    }

    private fun restorePendingRouteRequestState() {
        val encodedAction = prefs.getString(PREF_PENDING_ROUTE_ACTION, null) ?: return
        executor.execute {
            val restored = PendingRouteRequest.decode(encodedAction)
            postToUi {
                pendingRouteRequest = restored
                restored?.let { action ->
                    startInput.setText(action.startQuery)
                    searchInput.setText(action.destinationQuery)
                    main.postDelayed({ showPendingRouteRecoveryIfNeeded() }, 100L)
                }
            }
        }
    }

    private fun showPendingRouteRecoveryIfNeeded() {
        val action = pendingRouteRequest ?: return
        if (firstRunHost.visibility == View.VISIBLE || menuOpen || routePlanningInFlight) return
        showMenu("Route request restored") { body ->
            addMenuText(body, "Start: ${action.origin?.displayName ?: action.startQuery.ifBlank { CURRENT_GPS_LABEL }}", emphasized = true)
            addMenuText(body, "Destination: ${action.destination?.displayName ?: action.destinationQuery}")
            addMenuText(body, "Next action: ${postRouteActionLabel(action.postRouteAction)}", emphasized = true)
            addMenuText(body, "WatchMan retained this unfinished route action through recreation. Resume it or discard it explicitly.")
            addMenuAction(body, "RESUME ROUTE") {
                closeMenu()
                val origin = action.origin
                val destination = action.destination
                when {
                    action.stage == PendingRouteRequest.Stage.ROUTE_READY && !action.completedWorkflowContext.isNullOrBlank() ->
                        resumeCompletedRouteRequest(action)
                    action.stage == PendingRouteRequest.Stage.PLANNING && origin != null && destination != null && action.preference != null ->
                        planRoutes(destination, action.preference, automatic = false, explicitOrigin = origin, postRouteAction = action.postRouteAction)
                    origin != null && destination != null -> showRoutePreferenceDialog(destination, origin)
                    else -> beginSearch()
                }
            }
            addMenuAction(body, "DISCARD ROUTE REQUEST", danger = true) {
                setPendingRouteRequest(null)
                closeMenu()
                statusText.text = "Saved route request discarded"
            }
        }
    }
    private fun resumeCompletedRouteRequest(request: PendingRouteRequest) {
        val workflow = ExportWorkflowContext.decode(request.completedWorkflowContext)
        when (request.postRouteAction) {
            PendingRouteAction.PREVIEW -> {
                setPendingRouteRequest(null)
                restorePreviewExportOrigin(workflow) { restored ->
                    if (!restored) toast("Completed route preview could not be restored")
                }
            }
            PendingRouteAction.TURN_BY_TURN -> {
                setPendingRouteRequest(null)
                decodeWorkflowRouteAsync(workflow, "Restoring completed directions…") { state ->
                    if (state == null) {
                        toast("Completed turn-by-turn route could not be restored")
                    } else {
                        restoreWorkflowRouteStateAsync(state) {
                            showTurnByTurnDirections(state.route, state.currentStep, state.origin, state.preference)
                        }
                    }
                }
            }
            PendingRouteAction.NAVIGATE -> {
                setPendingRouteRequest(null)
                decodeWorkflowRouteAsync(workflow, "Restoring completed route…") { state ->
                    if (state == null) {
                        toast("Completed navigation route could not be restored")
                    } else {
                        val gpsPoint = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
                        val canStart = state.origin.displayName == CURRENT_GPS_LABEL ||
                            (gpsPoint != null && Geo.distanceMeters(gpsPoint, state.origin.point) <= LIVE_START_TOLERANCE_METERS)
                        if (canStart) activateRoute(state.route, state.preference, state.origin, startHistory = true)
                        else restorePreviewExportOrigin(workflow) {
                            toast("Route is ready; move to the planned start before live navigation")
                        }
                    }
                }
            }
            PendingRouteAction.EXPORT_SELECTED,
            PendingRouteAction.EXPORT_ALL,
            PendingRouteAction.EXPORT_COMPARISON -> {
                val mode = when (request.postRouteAction) {
                    PendingRouteAction.EXPORT_SELECTED -> DirectionsExportMode.SELECTED_ROUTE
                    PendingRouteAction.EXPORT_ALL -> DirectionsExportMode.ALL_ALTERNATIVES
                    PendingRouteAction.EXPORT_COMPARISON -> DirectionsExportMode.ROUTE_COMPARISON
                    else -> error("unreachable")
                }
                restorePreviewExportOrigin(workflow) { restored ->
                    if (!restored) {
                        toast("Completed route export context could not be restored")
                    } else {
                        setPendingRouteRequest(null)
                        closeMenu()
                        exportCurrentDirections(mode, workflow)
                    }
                }
            }
        }
    }

    private fun beginSearch() {
        val destinationQuery = searchInput.text.toString().trim()
        val startQuery = startInput.text.toString().trim()
        if (destinationQuery.isBlank()) {
            toast("Type a destination address")
            return
        }
        if (startQuery.isBlank() && currentLocation == null) {
            toast("Enter a starting address or wait for GPS")
            return
        }
        setPendingRouteRequest(
            PendingRouteRequest(
                startQuery = startQuery,
                destinationQuery = destinationQuery,
                stage = PendingRouteRequest.Stage.GEOCODING
            )
        )
        statusText.text = if (startQuery.isBlank()) "Searching for destination…" else "Searching start + destination…"
        executor.execute {
            try {
                val destinationResults: List<SearchResult>
                val originResults: List<SearchResult>
                if (startQuery.isBlank()) {
                    destinationResults = searchProvider.search(destinationQuery)
                    if (destinationResults.isEmpty()) throw IllegalStateException("No destination found")
                    val loc = currentLocation ?: throw IllegalStateException("GPS origin unavailable")
                    originResults = listOf(SearchResult(CURRENT_GPS_LABEL, GeoPoint(loc.latitude, loc.longitude)))
                } else {
                    val startFuture = parallelWorkerExecutor.submit<List<SearchResult>> { searchProvider.search(startQuery) }
                    val destinationFuture = parallelWorkerExecutor.submit<List<SearchResult>> { searchProvider.search(destinationQuery) }
                    try {
                        originResults = startFuture.get().also {
                            if (it.isEmpty()) throw IllegalStateException("No starting address found")
                        }
                        destinationResults = destinationFuture.get().also {
                            if (it.isEmpty()) throw IllegalStateException("No destination found")
                        }
                    } finally {
                        if (!startFuture.isDone) startFuture.cancel(true)
                        if (!destinationFuture.isDone) destinationFuture.cancel(true)
                    }
                }
                postToUi {
                    chooseSearchResult("Choose starting address", originResults) { origin ->
                        setPendingRouteRequest(
                            pendingRouteRequest?.copy(origin = origin, stage = PendingRouteRequest.Stage.CHOOSE_DESTINATION)
                                ?: PendingRouteRequest(startQuery, destinationQuery, origin = origin, stage = PendingRouteRequest.Stage.CHOOSE_DESTINATION)
                        )
                        if (origin.displayName != CURRENT_GPS_LABEL) {
                            clearRoutePreview(recenter = false)
                            map.focusOn(origin.point, 17)
                            statusText.text = "Starting location · ${origin.displayName.take(70)}"
                            tripText.text = "Choose destination / route mode to preview this trip"
                        }
                        chooseSearchResult("Choose destination", destinationResults) { destination ->
                            setPendingRouteRequest(
                                (pendingRouteRequest ?: PendingRouteRequest(startQuery, destinationQuery)).copy(
                                    origin = origin,
                                    destination = destination,
                                    stage = PendingRouteRequest.Stage.CHOOSE_MODE
                                )
                            )
                            routeEditorOpen = false
                            showRoutePreferenceDialog(destination, origin)
                        }
                    }
                }
            } catch (e: Exception) {
                postToUi {
                    statusText.text = "Search unavailable"
                    toast((e.cause ?: e).message ?: "Search failed")
                }
            }
        }
    }
    private fun chooseSearchResult(title: String, results: List<SearchResult>, onSelected: (SearchResult) -> Unit) {
        if (results.size == 1) {
            onSelected(results[0])
            return
        }
        showMenu(title) { body ->
            addMenuText(body, "Choose the exact result to use on the map.")
            results.forEach { result ->
                addMenuAction(body, result.displayName) {
                    closeMenu()
                    onSelected(result)
                }
            }
        }
    }

    private fun knownCrossStateEndpoints(
        origin: GeoPoint,
        destination: GeoPoint
    ): Pair<StateBoundaryLookup.Jurisdiction, StateBoundaryLookup.Jurisdiction>? {
        val originJurisdiction = stateBoundaryLookup.findIfLoaded(origin) ?: return null
        val destinationJurisdiction = stateBoundaryLookup.findIfLoaded(destination) ?: return null
        return if (originJurisdiction.fips != destinationJurisdiction.fips) {
            originJurisdiction to destinationJurisdiction
        } else null
    }

    private fun showRoutePreferenceDialog(
        destination: SearchResult?,
        origin: SearchResult? = null,
        postRouteAction: PendingRouteAction = pendingRouteRequest?.postRouteAction ?: PendingRouteAction.PREVIEW
    ) {
        val crossState = if (destination != null && origin != null) {
            knownCrossStateEndpoints(origin.point, destination.point)
        } else null
        val values = RoutePreference.values().filter { it != RoutePreference.SCENIC || crossState == null }
        val selected = currentRoutePreference()
        val target = destination
        val routeOrigin = origin
        val backAction: (() -> Unit)? = if (target == null) null else ({ closeMenu(); routeEditorOpen = true; applyNavigationLayout() })
        showMenu("Route mode", backAction) { body ->
            if (target != null && routeOrigin != null) {
                addMenuText(body, "From: ${routeOrigin.displayName}", emphasized = true)
                addMenuText(body, "To: ${target.displayName}")
                crossState?.let { (from, to) ->
                    addMenuText(body, "Scenic is available for same-state routes; ${from.name} to ${to.name} crosses state lines.")
                }
            } else {
                addMenuText(body, "Choose how WatchMan ranks route alternatives.")
            }
            if (target != null && routeOrigin != null) {
                addMenuAction(
                    body,
                    "AFTER ROUTE IS READY · ${postRouteActionLabel(postRouteAction)}",
                    "Keep the selected next operation through a long calculation or Activity/process recreation."
                ) {
                    showPostRouteActionDialog(target, routeOrigin, postRouteAction)
                }
            }
            values.forEach { preference ->
                val marker = if (preference == selected) "✓ " else ""
                addMenuAction(body, "$marker${preference.label}", preference.description) {
                    setRoutePreference(preference)
                    if (target != null) {
                        setPendingRouteRequest(
                            (pendingRouteRequest ?: PendingRouteRequest("", target.displayName)).copy(
                                origin = routeOrigin,
                                destination = target,
                                preference = preference,
                                stage = PendingRouteRequest.Stage.PLANNING,
                                postRouteAction = postRouteAction
                            )
                        )
                        closeMenu()
                        planRoutes(target, preference, automatic = false, explicitOrigin = routeOrigin, postRouteAction = postRouteAction)
                    } else {
                        closeMenu()
                    }
                }
            }
        }
    }

    private fun postRouteActionLabel(action: PendingRouteAction): String = when (action) {
        PendingRouteAction.PREVIEW -> "Route preview"
        PendingRouteAction.TURN_BY_TURN -> "Turn-by-turn"
        PendingRouteAction.NAVIGATE -> "Start navigation"
        PendingRouteAction.EXPORT_SELECTED -> "Export selected route"
        PendingRouteAction.EXPORT_ALL -> "Export all alternatives"
        PendingRouteAction.EXPORT_COMPARISON -> "Export comparison"
    }

    private fun showPostRouteActionDialog(
        destination: SearchResult,
        origin: SearchResult,
        current: PendingRouteAction
    ) {
        val choices = listOf(
            PendingRouteAction.PREVIEW to "Show the complete route preview and alternatives.",
            PendingRouteAction.TURN_BY_TURN to "Open the route map and ordered maneuver steps.",
            PendingRouteAction.NAVIGATE to "Start live navigation when the phone is at the planned start.",
            PendingRouteAction.EXPORT_SELECTED to "Prepare the selected turn-by-turn + map PDF.",
            PendingRouteAction.EXPORT_ALL to "Prepare all distinct route alternatives in one PDF.",
            PendingRouteAction.EXPORT_COMPARISON to "Prepare the route comparison PDF."
        )
        showMenu("After route is ready", { showRoutePreferenceDialog(destination, origin, current) }) { body ->
            addMenuText(body, "This choice is saved before routing begins, so a long calculation cannot erase it.", emphasized = true)
            choices.forEach { (action, description) ->
                val marker = if (action == current) "✓ " else ""
                addMenuAction(body, "$marker${postRouteActionLabel(action)}", description) {
                    setPendingRouteRequest(
                        (pendingRouteRequest ?: PendingRouteRequest("", destination.displayName)).copy(
                            origin = origin,
                            destination = destination,
                            stage = PendingRouteRequest.Stage.CHOOSE_MODE,
                            postRouteAction = action
                        )
                    )
                    showRoutePreferenceDialog(destination, origin, action)
                }
            }
        }
    }
    private fun postRoutePlanningUpdate(token: Long, action: () -> Unit) {
        postToUi { if (routePlanning.isCurrent(token)) action() }
    }

    private fun planRoutes(
        destination: SearchResult,
        preference: RoutePreference,
        automatic: Boolean,
        explicitOrigin: SearchResult? = null,
        postRouteAction: PendingRouteAction? = null
    ) {
        val effectivePostRouteAction = postRouteAction ?: if (automatic) PendingRouteAction.NAVIGATE else PendingRouteAction.PREVIEW
        val originResult = explicitOrigin ?: currentLocation?.let {
            SearchResult(CURRENT_GPS_LABEL, GeoPoint(it.latitude, it.longitude))
        } ?: return
        if (routePlanningInFlight) {
            if (!automatic) toast("Route analysis is already running")
            return
        }

        val planningToken = routePlanning.begin()
        routePlanningInFlight = true
        routeCoverageError = null
        lastRoutePlanningNotice = null
        statusText.text = if (automatic) "Rerouting · ${preference.label}…" else "Building route alternatives…"
        tripText.text = when (preference) {
            RoutePreference.FASTEST -> "Finding the lowest-ETA road route"
            RoutePreference.SHORTEST -> "Finding the shortest-distance road route"
            RoutePreference.FEWEST_CAMERAS -> "Prioritizing route coverage, then constructing roads around cameras / sensors"
            RoutePreference.MOST_CAMERAS -> "Prioritizing route coverage, then constructing roads through cameras / sensors"
            RoutePreference.SCENIC -> "Finding the longest reasonable road route inside deterministic detour bounds"
        }

        val origin = originResult.point
        val planningStartedAt = monotonicMillis()
        val planningRequestSnapshot = pendingRouteRequest
        executor.execute {
            val phaseTimingMs = linkedMapOf<String, Long>()
            var phaseStartedAt = planningStartedAt
            var baselineRouteCount = 0
            var baselineProviders: List<String> = emptyList()
            var shapingDeviceCount = 0
            var candidateDeviceCount = 0
            var shapedAttemptCount = 0
            var shapedRouteCount = 0
            var rawCandidateCount = 0
            var deduplicatedCandidateCount = 0
            var acceptedShapedCount = 0
            var finalCandidateCount = 0
            var exposureCounts: List<Int> = emptyList()
            var selectedDiagnosticRoute: Route? = null
            var fallbackDiagnostic: String? = null
            var priorityCoverageAttempted = false
            var priorityCoverageFailure: String? = null
            var priorityCoverageStatuses: List<CameraSourceStatus> = emptyList()
            var obstaclePlan: NoCamsObstaclePlanner.Planned? = null
            var obstacleRoadRequestCount = 0
            var obstacleAttributionRequestCount = 0
            var obstacleExcludedDeviceCount = 0

            fun markPlanningPhase(name: String) {
                val now = monotonicMillis()
                phaseTimingMs[name] = (now - phaseStartedAt).coerceAtLeast(0L)
                phaseStartedAt = now
            }

            fun sourceCountSnapshot(): Map<String, Int> = linkedMapOf(
                "nearbySuccessfulSources" to cameraSourceStatuses.count { it.ok },
                "nearbyFailedSources" to cameraSourceStatuses.count { !it.ok },
                "nearbyRecords" to cameraSourceStatuses.sumOf { it.count },
                "routeSuccessfulSources" to routeCameraSourceStatuses.count { it.ok },
                "routeFailedSources" to routeCameraSourceStatuses.count { !it.ok },
                "routeRecords" to routeCameraSourceStatuses.sumOf { it.count },
                "priorityRouteSuccessfulSources" to priorityCoverageStatuses.count { it.ok },
                "priorityRouteFailedSources" to priorityCoverageStatuses.count { !it.ok },
                "priorityRouteRecords" to priorityCoverageStatuses.sumOf { it.count }
            )

            fun recordRouteDiagnostic(status: String, error: String? = null) {
                phaseTimingMs["total"] = (monotonicMillis() - planningStartedAt).coerceAtLeast(0L)
                val selected = selectedDiagnosticRoute
                localDiagnostics.recordRouteSession(
                    LocalDiagnostics.RouteSession(
                        status = status,
                        mode = preference.name,
                        automatic = automatic,
                        phaseTimingMs = phaseTimingMs.toMap(),
                        baselineRoutes = baselineRouteCount,
                        baselineProviders = baselineProviders,
                        liveSourceCounts = sourceCountSnapshot(),
                        shapingDevices = shapingDeviceCount,
                        candidateDevices = candidateDeviceCount,
                        shapedAttempts = shapedAttemptCount,
                        shapedRoutes = shapedRouteCount,
                        rawCandidates = rawCandidateCount,
                        deduplicatedCandidates = deduplicatedCandidateCount,
                        acceptedShapedRoutes = acceptedShapedCount,
                        finalCandidates = finalCandidateCount,
                        exposureCounts = exposureCounts,
                        selectedIdentity = selected?.let(ActiveRouteStateCodec::routeIdentity),
                        selectedDistanceMeters = selected?.distanceMeters,
                        selectedDurationSeconds = selected?.durationSeconds,
                        viewport = selected?.points?.let { LocalDiagnostics.viewport(it) },
                        fallback = fallbackDiagnostic,
                        error = error,
                        obstacleRoadRequests = obstacleRoadRequestCount,
                        obstacleExcludedDevices = obstacleExcludedDeviceCount,
                        obstacleSnapshotRevision = obstaclePlan?.snapshot?.revision,
                        obstacleAttributionRequests = obstacleAttributionRequestCount
                    )
                )
            }

            try {
                if (preference == RoutePreference.SCENIC) {
                    val jurisdictions = runCatching {
                        stateBoundaryLookup.find(origin) to stateBoundaryLookup.find(destination.point)
                    }.getOrNull()
                    markPlanningPhase("jurisdiction")
                    val originJurisdiction = jurisdictions?.first
                    val destinationJurisdiction = jurisdictions?.second
                    if (originJurisdiction != null &&
                        destinationJurisdiction != null &&
                        originJurisdiction.fips != destinationJurisdiction.fips) {
                        fallbackDiagnostic = "Scenic rejected: ${originJurisdiction.name} to ${destinationJurisdiction.name} crosses state lines"
                        recordRouteDiagnostic("rejected", fallbackDiagnostic)
                        postRoutePlanningUpdate(planningToken) {
                            routePlanningInFlight = false
                            setPendingRouteRequest(pendingRouteRequest?.copy(stage = PendingRouteRequest.Stage.CHOOSE_MODE))
                            statusText.text = "Scenic unavailable for this route"
                            tripText.text = "${originJurisdiction.name} to ${destinationJurisdiction.name} crosses state lines. Choose another route mode."
                            toast("Scenic is available for routes within one state")
                            if (!automatic) showRoutePreferenceDialog(destination, originResult, effectivePostRouteAction)
                        }
                        return@execute
                    }
                }

                val cancelled = { !routePlanning.isCurrent(planningToken) ||
                    RoutePlanningPolicy.remainingMillis(planningStartedAt, monotonicMillis()) <= 0L }
                val baselineRoutes = if (preference == RoutePreference.FEWEST_CAMERAS) {
                    val roadProvider = ValhallaObstacleRoutingProvider()
                    postRoutePlanningUpdate(planningToken) {
                        statusText.text = "Searching roads around known cameras / sensors…"
                        tripText.text = "Checking the complete path and each detour against retained device data."
                    }
                    val planner = NoCamsObstaclePlanner(
                        router = { from, to, exclusions, remaining ->
                            obstacleRoadRequestCount++
                            obstacleExcludedDeviceCount = exclusions.totalDevices
                            try { roadProvider.route(from, to, exclusions, remaining) }
                            finally { markPlanningPhase("obstacleRoad$obstacleRoadRequestCount") }
                        },
                        readDevices = { route -> noCamsDeviceSnapshot(route, origin, cancelled) },
                        acquire = { route, waitMs ->
                            if (waitMs >= 1_000L) {
                                priorityCoverageAttempted = true
                                runCatching { fetchAndPersistRouteCoverage(listOf(route), waitMs, "No Cams road exclusions") }
                                    .onSuccess { priorityCoverageStatuses = it }
                                    .onFailure { priorityCoverageFailure = it.message ?: it.javaClass.simpleName }
                            }
                        },
                        now = ::monotonicMillis,
                        readRoadCoverage = { route,snapshot,remaining,claim ->
                            readRoadCoverage(route,snapshot,remaining,true,cancelled) {
                                claim(); obstacleAttributionRequestCount++
                            }?.observation
                        }
                    )
                    val roadPriority=acquisitionPriority.acquire(AcquisitionPriorityCoordinator.Work.ROUTE_CORRIDOR)
                    val planned = try { planner.plan(origin, destination, planningStartedAt, cancelled) }
                        finally { roadPriority.close() }
                    obstaclePlan = planned
                    listOf(planned.route)
                } else routingProvider.routes(origin, destination, ROUTE_ALTERNATIVE_REQUEST_COUNT)
                baselineRouteCount = baselineRoutes.size
                baselineProviders = baselineRoutes.map { it.provider }.distinct().sorted()
                markPlanningPhase("baselineProvider")
                if (baselineRoutes.isEmpty()) throw IllegalStateException("No route alternatives returned")

                val cameraSensitive = RoutePlanningPolicy.isCameraSensitive(preference)
                val priorityCoverageWaitMs = RoutePlanningPolicy.cameraCoverageWaitMillis(
                    preference,
                    planningStartedAt,
                    monotonicMillis()
                )
                if (cameraSensitive && preference != RoutePreference.FEWEST_CAMERAS && priorityCoverageWaitMs > 0L) {
                    priorityCoverageAttempted = true
                    postRoutePlanningUpdate(planningToken) {
                        statusText.text = "Priority acquisition · route cameras / sensors…"
                        tripText.text = "Acquiring bounded corridor coverage before ${preference.label} shaping; national acquisition will resume automatically."
                    }
                    runCatching {
                        fetchAndPersistRouteCoverage(
                            baselineRoutes,
                            priorityCoverageWaitMs,
                            "camera-sensitive route shaping"
                        )
                    }.onSuccess { statuses ->
                        priorityCoverageStatuses = statuses
                    }.onFailure { error ->
                        priorityCoverageFailure = error.message ?: error.javaClass.simpleName
                    }
                    markPlanningPhase("priorityRouteCoverage")
                }
                val shapingCameras = when (preference) {
                    RoutePreference.FEWEST_CAMERAS -> emptyList()
                    RoutePreference.MOST_CAMERAS -> routeCameraCandidates(baselineRoutes, RouteShaper.THROUGH_SEARCH_CORRIDOR_METERS)
                    else -> emptyList()
                }
                val waypointSets = when (preference) {
                    RoutePreference.FEWEST_CAMERAS -> emptyList()
                    RoutePreference.MOST_CAMERAS -> RouteShaper.throughWaypointSets(baselineRoutes, shapingCameras)
                    RoutePreference.SCENIC -> RouteShaper.scenicWaypointSets(origin, destination.point)
                    RoutePreference.FASTEST,
                    RoutePreference.SHORTEST -> emptyList()
                }
                shapingDeviceCount = shapingCameras.size
                shapedAttemptCount = waypointSets.take(RoutePlanningPolicy.MAX_SHAPED_REQUESTS).size
                markPlanningPhase("shapingInputs")

                if (waypointSets.isNotEmpty()) {
                    postRoutePlanningUpdate(planningToken) {
                        statusText.text = "Building bounded ${preference.label} alternatives…"
                        tripText.text = "Baseline route is ready; testing ${waypointSets.take(RoutePlanningPolicy.MAX_SHAPED_REQUESTS).size} shaped road alternatives concurrently."
                    }
                }
                val shapedRoutes = requestShapedRoutes(
                    origin,
                    destination,
                    waypointSets,
                    planningStartedAt
                )
                shapedRouteCount = shapedRoutes.size
                markPlanningPhase("shapedProviders")
                rawCandidateCount = baselineRoutes.size + shapedRoutes.size
                val measuredCandidates = RouteGeometry.deduplicate(baselineRoutes + shapedRoutes)
                deduplicatedCandidateCount = measuredCandidates.size
                var publicationSnapshot: RoutingDeviceSnapshot? = null
                val routeCameras = RoutePlanningPolicy.cameraSnapshotBeforePublication(preference) {
                    if (preference == RoutePreference.FEWEST_CAMERAS) {
                        noCamsDeviceSnapshot(baselineRoutes.single(), origin, cancelled).also { publicationSnapshot=it }.entries.map { it.device }
                    } else routeCameraCandidates(measuredCandidates, requireAvailable = true)
                }
                candidateDeviceCount = routeCameras.size
                markPlanningPhase("deviceExposureFetch")
                val baselineCounts = baselineRoutes.map { route ->
                    RouteAnalyzer.countKnownCameras(route, routeCameras, ROUTE_CAMERA_CORRIDOR_METERS)
                }
                val acceptedShapedRoutes = if (cameraSensitive) {
                    shapedRoutes.filter { route ->
                        val count = RouteAnalyzer.countKnownCameras(route, routeCameras, ROUTE_CAMERA_CORRIDOR_METERS)
                        RoutePlanningPolicy.acceptsShapedCameraCandidate(preference, baselineCounts, count)
                    }
                } else shapedRoutes
                acceptedShapedCount = acceptedShapedRoutes.size

                val routes = RouteGeometry.deduplicate(baselineRoutes + acceptedShapedRoutes)
                    .sortedBy { it.durationSeconds }
                    .take(6)
                    .mapIndexed { index, route -> route.copy(alternativeIndex = index) }
                finalCandidateCount = routes.size
                val analyses = routes.map { route ->
                    val assessedAt=System.currentTimeMillis()
                    val roadAssessment=if(preference==RoutePreference.FEWEST_CAMERAS) {
                        RoadAwareExposure.assess(route,checkNotNull(publicationSnapshot),obstaclePlan?.coverage,assessedAt) {
                            check(!cancelled()) { "No Cams final validation deadline reached" }
                        }
                    } else null
                    RouteAnalysis(
                        route = route,
                        knownCameraCount = roadAssessment?.nearbyRelevantDevices?.size ?: if (!cameraSensitive) RoutePlanningPolicy.EXPOSURE_PENDING else RouteAnalyzer.countKnownCameras(
                            route,
                            routeCameras,
                            ROUTE_CAMERA_CORRIDOR_METERS
                        ),
                        scenicScore = 0.0,
                        routedExposure=roadAssessment?.let { RoadAwareExposure.summary(it,obstaclePlan?.coverage,assessedAt) }
                    )
                }
                exposureCounts = analyses.map { it.knownCameraCount }
                val selected = RouteScorer.select(preference, analyses)
                if (preference == RoutePreference.FEWEST_CAMERAS) {
                    val exposure=checkNotNull(selected.routedExposure)
                    check(exposure.encounters == 0 && !cancelled() &&
                        exposure.validUntilMillis?.let { System.currentTimeMillis()<it } != false) {
                        "New device data or deadline changed during No Cams validation; please retry"
                    }
                }
                selectedDiagnosticRoute = selected.route
                val fallbackNotice = if (preference == RoutePreference.FEWEST_CAMERAS) {
                    "No routed encounters in the checked device caches. Nearby awareness devices remain visible; unacquired, unmapped or expired coverage remains unknown."
                } else RoutePlanningPolicy.cameraFallbackMessage(
                    preference,
                    shapingCameras.size,
                    shapedRoutes.size,
                    acceptedShapedRoutes.size
                )
                val elapsedMs = monotonicMillis() - planningStartedAt
                val deadlineNotice = if (elapsedMs > RoutePlanningPolicy.HARD_DEADLINE_MS) {
                    "The hard planning deadline was reached; WatchMan returned the best complete route available."
                } else null
                val priorityCoverageNotice = priorityCoverageFailure?.let {
                    "Priority route coverage failed ($it); retained cache was used."
                }
                fallbackDiagnostic = listOfNotNull(priorityCoverageNotice, fallbackNotice, deadlineNotice).joinToString(" ").ifBlank { null }
                markPlanningPhase("dedupeExposureSelection")
                recordRouteDiagnostic("complete")
                val completionMode = when (effectivePostRouteAction) {
                    PendingRouteAction.EXPORT_ALL -> DirectionsExportMode.ALL_ALTERNATIVES
                    PendingRouteAction.EXPORT_COMPARISON -> DirectionsExportMode.ROUTE_COMPARISON
                    else -> DirectionsExportMode.SELECTED_ROUTE
                }
                val preparedCompletionContext = if (automatic) null else buildPreviewExportWorkflowContext(
                    completionMode,
                    analyses,
                    selected,
                    selected,
                    preference,
                    originResult
                )
                val completedPendingRequest = if (automatic) null else planningRequestSnapshot?.copy(
                    stage = PendingRouteRequest.Stage.ROUTE_READY,
                    completedWorkflowContext = preparedCompletionContext?.encode()
                )
                val completedPendingRequestEncoded = completedPendingRequest?.encode()

                postRoutePlanningUpdate(planningToken) {
                    routePlanningInFlight = false
                    lastRouteAnalyses = analyses
                    lastRouteAnalysisOrigin = originResult
                    lastRouteAnalysisPreference = preference
                    lastRoutePlanningNotice = listOfNotNull(priorityCoverageNotice, fallbackNotice, deadlineNotice).joinToString(" ").ifBlank { null }
                    if (automatic) {
                        activateRoute(selected.route, preference, originResult, startHistory = false)
                    } else {
                        val completionContext = checkNotNull(preparedCompletionContext)
                        setPendingRouteRequest(completedPendingRequest, completedPendingRequestEncoded)
                        completePlannedRouteAction(
                            analyses,
                            selected,
                            preference,
                            originResult,
                            effectivePostRouteAction,
                            completionContext
                        )
                    }
                    refreshRouteExposureAnalyses()
                    if (priorityCoverageAttempted) {
                        scheduleRouteRegionPrefetch(routes)
                    } else {
                        scheduleRouteCoverageRefresh(routes)
                    }
                }
            } catch (e: Exception) {
                val failure = (e.cause ?: e).message ?: "Route analysis failed"
                markPlanningPhase("failure")
                recordRouteDiagnostic("failed", failure)
                postRoutePlanningUpdate(planningToken) {
                    routePlanningInFlight = false
                    if (!automatic) setPendingRouteRequest(pendingRouteRequest?.copy(stage = PendingRouteRequest.Stage.CHOOSE_MODE))
                    if (automatic && currentRoute != null) {
                        if (preference == RoutePreference.FEWEST_CAMERAS) {
                            statusText.text = "No Cams diversion unavailable"
                            tripText.text = "The current route may encounter known devices. $failure"
                            toast("No Cams diversion unavailable; current guidance may cross devices")
                        } else {
                            statusText.text = "Reroute unavailable · continuing current route"
                            tripText.text = "The route provider failed; guidance remains locked to the current route and will retry after the bounded cooldown."
                            toast("Reroute failed; continuing the current route")
                        }
                    } else {
                        statusText.text = "Routing unavailable"
                        tripText.text = (e.cause ?: e).message ?: "Route analysis failed"
                        toast((e.cause ?: e).message ?: "Route failed")
                    }
                }
            }
        }
    }

    private fun completePlannedRouteAction(
        analyses: List<RouteAnalysis>,
        selected: RouteAnalysis,
        preference: RoutePreference,
        origin: SearchResult,
        action: PendingRouteAction,
        completionContext: ExportWorkflowContext
    ) {
        when (action) {
            PendingRouteAction.PREVIEW -> {
                showRouteAlternatives(analyses, selected, preference, origin)
                setPendingRouteRequest(null)
            }
            PendingRouteAction.TURN_BY_TURN -> {
                setRoutePreview(selected.route, preference, origin)
                showTurnByTurnDirections(selected.route, 0)
                setPendingRouteRequest(null)
            }
            PendingRouteAction.NAVIGATE -> {
                val gpsPoint = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
                val canStart = origin.displayName == CURRENT_GPS_LABEL ||
                    (gpsPoint != null && Geo.distanceMeters(gpsPoint, origin.point) <= LIVE_START_TOLERANCE_METERS)
                if (canStart) {
                    activateRoute(selected.route, preference, origin, startHistory = true)
                } else {
                    showRouteAlternatives(analyses, selected, preference, origin)
                    toast("Route is ready; move to the planned start before live navigation")
                }
                setPendingRouteRequest(null)
            }
            PendingRouteAction.EXPORT_SELECTED,
            PendingRouteAction.EXPORT_ALL,
            PendingRouteAction.EXPORT_COMPARISON -> {
                setRoutePreview(selected.route, preference, origin)
                val mode = when (action) {
                    PendingRouteAction.EXPORT_SELECTED -> DirectionsExportMode.SELECTED_ROUTE
                    PendingRouteAction.EXPORT_ALL -> DirectionsExportMode.ALL_ALTERNATIVES
                    PendingRouteAction.EXPORT_COMPARISON -> DirectionsExportMode.ROUTE_COMPARISON
                    else -> error("unreachable")
                }
                exportCurrentDirections(
                    mode,
                    completionContext
                )
            }
        }
    }
    private fun requestShapedRoutes(
        origin: GeoPoint,
        destination: SearchResult,
        waypointSets: List<List<GeoPoint>>,
        planningStartedAt: Long
    ): List<Route> {
        val boundedSets = waypointSets.take(RoutePlanningPolicy.MAX_SHAPED_REQUESTS)
        if (boundedSets.isEmpty()) return emptyList()
        if (RoutePlanningPolicy.remainingMillis(planningStartedAt, monotonicMillis()) <= 0L) return emptyList()

        val completion = ExecutorCompletionService<List<Route>>(parallelWorkerExecutor)
        val futures = boundedSets.map { waypoints ->
            completion.submit(Callable {
                runCatching {
                    routingProvider.routesVia(origin, destination, waypoints, maxAlternatives = 1)
                }.getOrDefault(emptyList())
            })
        }
        return try {
            val routes = ArrayList<Route>()
            var completed = 0
            while (completed < boundedSets.size) {
                val remaining = RoutePlanningPolicy.remainingMillis(planningStartedAt, monotonicMillis())
                if (remaining <= 0L) break
                val future = completion.poll(remaining, TimeUnit.MILLISECONDS) ?: break
                completed++
                routes += runCatching { future.get() }.getOrDefault(emptyList())
            }
            RouteGeometry.deduplicate(routes)
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
        }
    }

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
    private fun fetchAndPersistRouteCoverage(routes: List<Route>, maxWaitMs: Long, reason: String): List<CameraSourceStatus> {
        if (routes.isEmpty()) return emptyList()
        val generation = ++routeCoverageGeneration
        postToUi {
            routeCoverageInFlight = true
            routeCoverageCompleted = 0
            routeCoverageTotal = routeCameraCoverageProvider.routeTiles(routes).size
            routeCoverageSuccess = 0
            routeCoverageFailure = 0
            routeCoverageError = null
            if (firstRunHost.visibility == View.VISIBLE && firstRunSetupBox.visibility == View.VISIBLE) {
                firstRunLocale.text = "Priority: route corridor · preparing coverage"
            }
            updateCameraStatusText()
        }

        val statuses = ConcurrentHashMap<String, CameraSourceStatus>()
        val priorityLease = acquisitionPriority.acquire(AcquisitionPriorityCoordinator.Work.ROUTE_CORRIDOR)
        val result = try {
            routeCameraCoverageProvider.syncRoutesIncremental(routes, maxWaitMs) { tileResult, completed, total ->
            if (tileResult.status.ok) {
                runCatching { db.replaceSourceSnapshot(tileResult.status.sourceId, tileResult.cameras) }
            }
            statuses[tileResult.status.sourceId] = tileResult.status
            val routeCameras = runCatching { routeCameraCandidates(routes) }.getOrDefault(emptyList())
            postToUi {
                if (generation != routeCoverageGeneration) return@postToUi
                routeCoverageInFlight = true
                routeCoverageCompleted = completed
                routeCoverageTotal = total
                routeCoverageSuccess = statuses.values.count { it.ok }
                routeCoverageFailure = statuses.values.count { !it.ok }
                routeCoverageCameraCount = routeCameras.size
                routeCameraSourceStatuses = statuses.values.sortedBy { it.sourceId }
                val preview = previewRoute.takeIf { currentRoute == null }
                if (preview != null && routes.any { it == preview }) {
                    updatePreviewCameraLayer()
                }
                statusText.text = "Loading route cameras / sensors · $completed/$total areas"
                updateCameraStatusText()
            }
            }
        } finally {
            priorityLease.close()
            postToUi {
                if (firstRunHost.visibility == View.VISIBLE && firstRunSetupBox.visibility == View.VISIBLE) {
                    firstRunLocale.text = "Route priority complete · resuming national acquisition"
                }
            }
        }

        val finalStatuses = result.sources
        val success = finalStatuses.count { it.ok }
        val failed = finalStatuses.size - success
        val routeCameras = runCatching { routeCameraCandidates(routes) }.getOrDefault(emptyList())
        postToUi {
            if (generation != routeCoverageGeneration) return@postToUi
            routeCoverageInFlight = false
            routeCoverageCompleted = finalStatuses.size
            routeCoverageTotal = finalStatuses.size
            routeCoverageSuccess = success
            routeCoverageFailure = failed
            routeCoverageCameraCount = routeCameras.size
            routeCameraSourceStatuses = finalStatuses.sortedBy { it.sourceId }
            routeCoverageError = when {
                finalStatuses.isEmpty() -> "No route coverage areas were generated"
                success == 0 && routeCameras.isEmpty() -> "Route camera sources unavailable"
                success == 0 -> "Live route refresh unavailable; using cached route cameras"
                failed > 0 -> "Partial live route coverage: $success/${finalStatuses.size} areas refreshed"
                else -> null
            }
            val preview = previewRoute.takeIf { currentRoute == null }
            if (preview != null && routes.any { it == preview }) {
                updatePreviewCameraLayer()
            }
            refreshRouteExposureAnalyses()
            if (reason == "manual retry") {
                toast(if (routeCoverageError == null) "Route camera coverage refreshed" else routeCoverageError!!)
            }
            updateCameraStatusText()
        }
        return finalStatuses
    }

    private fun scheduleRouteCoverageRefresh(routes: List<Route>) {
        if (routes.isEmpty()) return
        executor.execute {
            runCatching {
                fetchAndPersistRouteCoverage(routes, RouteCameraCoverageProvider.BACKGROUND_MAX_WAIT_MS, "background refresh")
            }
            scheduleRouteRegionPrefetch(routes)
        }
    }

    private fun retryRouteCoverage() {
        val preview = previewRoute ?: return
        if (routeCoverageInFlight) {
            toast("Route camera refresh is already running")
            return
        }
        val alternatives = lastRouteAnalyses.map { it.route }.filter {
            Geo.distanceMeters(it.destination.point, preview.destination.point) < 20.0
        }
        val routes = if (alternatives.isEmpty()) listOf(preview) else alternatives
        executor.execute {
            fetchAndPersistRouteCoverage(routes, RouteCameraCoverageProvider.BACKGROUND_MAX_WAIT_MS, "manual retry")
            scheduleRouteRegionPrefetch(routes)
        }
    }

    private fun routeCoverageShortLabel(): String {
        if (routeCoverageTotal <= 0) return "coverage not loaded"
        return when {
            routeCoverageInFlight -> "coverage ${routeCoverageCompleted}/${routeCoverageTotal} areas"
            routeCoverageFailure == 0 -> "coverage ${routeCoverageSuccess}/${routeCoverageTotal} areas"
            routeCoverageSuccess > 0 -> "partial ${routeCoverageSuccess}/${routeCoverageTotal} areas"
            else -> "coverage unavailable"
        }
    }

    /**
     * Export-only background coverage. This never replaces or participates in the live/startup
     * v0.1.8 camera database; it uses a separate regional cache.
     */
    private fun scheduleRouteRegionPrefetch(routes: List<Route>) {
        if (routes.isEmpty()) return
        executor.execute {
            runCatching {
                regionalCameraProvider.syncRouteRegionIncremental(
                    routes,
                    RegionalOverpassCameraProvider.ROUTE_REGION_RADIUS_METERS,
                    RegionalOverpassCameraProvider.BACKGROUND_MAX_WAIT_MS
                ) { result ->
                    if (result.status.ok) {
                        runCatching { regionalCameraCache.replaceSnapshot(result.status.sourceId, result.cameras) }
                    }
                }
            }
            runCatching {
                regionalPublicCameraProvider.syncRoutesIncremental(routes, prefs) { result ->
                    if (result.status.ok) {
                        runCatching { regionalCameraCache.replaceSnapshot("public_export_${result.status.sourceId}", result.cameras) }
                    }
                }
            }
            postToUi {
                if (lastRouteAnalyses.any { analysis -> routes.any { it === analysis.route } })
                    refreshRouteExposureAnalyses()
            }
        }
    }

    /**
     * Refreshes broad map context for exports from route geometry alone. Failed regional sources
     * leave their previous export-only cache snapshots intact. Live navigation camera state is not
     * read or written here.
     */
    private fun refreshRouteRegionForExport(routes: List<Route>) {
        if (routes.isEmpty()) return
        val osmTask = parallelWorkerExecutor.submit {
                runCatching {
                    regionalCameraProvider.syncRouteRegionIncremental(
                        routes,
                        RegionalOverpassCameraProvider.ROUTE_REGION_RADIUS_METERS,
                        RegionalOverpassCameraProvider.DEFAULT_MAX_WAIT_MS
                    ) { result ->
                        if (result.status.ok) {
                            runCatching { regionalCameraCache.replaceSnapshot(result.status.sourceId, result.cameras) }
                        }
                    }
                }
            }
        val publicTask = parallelWorkerExecutor.submit {
                runCatching {
                    regionalPublicCameraProvider.syncRoutesIncremental(routes, prefs) { result ->
                        if (result.status.ok) {
                            runCatching { regionalCameraCache.replaceSnapshot("public_export_${result.status.sourceId}", result.cameras) }
                        }
                    }
                }
            }
        try {
            runCatching { osmTask.get() }
            runCatching { publicTask.get() }
        } finally {
            if (!osmTask.isDone) osmTask.cancel(true)
            if (!publicTask.isDone) publicTask.cancel(true)
        }
    }

    private fun showRouteAlternatives(
        analyses: List<RouteAnalysis>,
        recommended: RouteAnalysis,
        preference: RoutePreference,
        originResult: SearchResult,
        selectedOverride: Int? = null
    ) {
        if (analyses.isEmpty()) return
        val recommendedIndex = analyses.indexOfFirst { it.route === recommended.route || it.route == recommended.route }.coerceAtLeast(0)
        val selectedIndex = (selectedOverride ?: recommendedIndex).coerceIn(0, analyses.lastIndex)
        val selected = analyses[selectedIndex]
        lastRouteAnalyses = analyses
        lastRouteAnalysisOrigin = originResult
        lastRouteAnalysisPreference = preference

        setRoutePreview(selected.route, preference, originResult)

        val gpsPoint = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
        val distanceFromPlannedStart = gpsPoint?.let { Geo.distanceMeters(it, originResult.point) }
        val canStartLive = originResult.displayName == CURRENT_GPS_LABEL || (distanceFromPlannedStart != null && distanceFromPlannedStart <= LIVE_START_TOLERANCE_METERS)

        showMenu("Route preview", { showRoutePreferenceDialog(selected.route.destination, originResult) }) { body ->
            addMenuText(body, "From: ${originResult.displayName}", emphasized = true)
            addMenuText(body, "To: ${selected.route.destination.displayName}")
            lastRoutePlanningNotice?.let { notice ->
                addMenuText(body, notice, emphasized = true)
            }
            if (!canStartLive) {
                val distanceText = distanceFromPlannedStart?.let { Geo.distanceText(it) } ?: "away"
                addMenuText(
                    body,
                    "Planning mode · live GPS is $distanceText from the typed start. This preview stays anchored to the entered Start → Destination route and will not reroute from the phone's location.",
                    emphasized = true
                )
            }

            analyses.forEachIndexed { index, analysis ->
                val isSelected = index == selectedIndex
                val isRecommended = analysis.route === recommended.route || analysis.route == recommended.route
                val label = buildString {
                    if (isSelected) append("✓ ")
                    if (isRecommended) append("★ ")
                    append("Route ${index + 1} · ${Geo.etaText(analysis.route.durationSeconds)} · ${distanceMiles(analysis.route.distanceMeters)}")
                }
                val detail = buildString {
                    append(RoutePlanningPolicy.exposureLabel(analysis))
                    append(" · ${routeCoverageShortLabel()}")
                    if (preference == RoutePreference.SCENIC) append(" · scenic ${String.format(Locale.US, "%.1f", analysis.scenicScore)}")
                }
                addMenuAction(body, label, detail) {
                    val current = lastRouteAnalyses.takeIf { updated ->
                        updated.size == analyses.size && updated.indices.all { updated[it].route === analyses[it].route }
                    } ?: analyses
                    showRouteAlternatives(current, recommended, preference, originResult, index)
                }
                (body.getChildAt(body.childCount - 1) as? TextView)?.let {
                    routePreviewCameraLabels[analysis.route] = it
                }
            }

            addMenuAction(body, "VIEW TURN-BY-TURN DIRECTIONS", "Open the route map, maneuver signs, exits/road guidance and cameras/sensors along each leg.") {
                showTurnByTurnDirections(selected.route)
            }
            addMenuAction(body, "SAVE TURN-BY-TURN + MAP PDF", "Generate from cached route/camera data, validate it, then open the Android save picker.") {
                closeMenu(); exportCurrentDirections(
                    DirectionsExportMode.SELECTED_ROUTE,
                    workflowContextFactory = {
                        buildPreviewExportWorkflowContext(
                            DirectionsExportMode.SELECTED_ROUTE,
                            analyses,
                            recommended,
                            selected,
                            preference,
                            originResult
                        )
                    }
                )
            }

            if (canStartLive) {
                addMenuAction(body, "START NAVIGATION", "Use live GPS, turn-by-turn guidance, rerouting and foreground navigation for the selected route.") {
                    closeMenu()
                    activateRoute(selected.route, preference, originResult, startHistory = true)
                }
            } else {
                addMenuAction(body, "KEEP PREVIEW", "Keep the typed Start → Destination route on the map for review/export. Live navigation remains off.") {
                    closeMenu()
                    setRoutePreview(selected.route, preference, originResult)
                }
                if (gpsPoint != null) {
                    addMenuAction(body, "ROUTE FROM CURRENT GPS INSTEAD", "Rebuild the trip from where the phone is now to the same destination.") {
                        closeMenu()
                        planRoutes(
                            selected.route.destination,
                            preference,
                            automatic = false,
                            explicitOrigin = SearchResult(CURRENT_GPS_LABEL, gpsPoint)
                        )
                    }
                }
            }
        }
    }

    private fun refreshRouteExposureAnalyses() {
        if (lastRouteAnalyses.isEmpty() || !::routeExposureWorker.isInitialized) return
        routeExposureWorker.request(RouteExposureRequest(
            lastRouteAnalyses, lastRouteAnalysisOrigin, lastRouteAnalysisPreference
        ))
    }

    private fun publishRouteExposure(request: RouteExposureRequest, result: Result<List<RouteAnalysis>>) {
        if (activityDestroyed || request.analyses !== lastRouteAnalyses || request.origin != lastRouteAnalysisOrigin ||
            request.preference != lastRouteAnalysisPreference) return
        val updated = result.getOrNull()
        if (updated == null) {
            routePreviewCameraLabels.values.filter { it.isAttachedToWindow }.forEach {
                it.text = "Camera/sensor counts unavailable · retry route coverage"
            }
            return
        }
        if (updated.size != request.analyses.size ||
            updated.indices.any { updated[it].route !== request.analyses[it].route }) return
        // Metadata only: never reselect/replan, activate another route or replace locked geometry.
        lastRouteAnalyses = updated
        updated.forEach { analysis ->
            routePreviewCameraLabels[analysis.route]?.takeIf { it.isAttachedToWindow }?.text = buildString {
                append(RoutePlanningPolicy.exposureLabel(analysis))
                append(" · ${routeCoverageShortLabel()}")
                if (request.preference == RoutePreference.SCENIC)
                    append(" · scenic ${String.format(Locale.US, "%.1f", analysis.scenicScore)}")
            }
        }
    }

    private fun setRoutePreview(route: Route, preference: RoutePreference, origin: SearchResult) {
        previewRoute = route
        previewRouteOrigin = origin
        previewRoutePreference = preference
        setRoutePreference(preference)
        routeEditorOpen = false
        syncMapViewportInsets()
        map.showRoutePreview(route, origin.point)
        statusText.text = "Route preview · ${preference.label}"
        tripText.text = "${distanceMiles(route.distanceMeters)} · ${Geo.etaText(route.durationSeconds)}\n${origin.displayName.take(54)} → ${route.destination.displayName.take(54)}"
        applyNavigationLayout()
        updatePreviewCameraLayer()
        main.postDelayed({
            if (previewRoute == route && previewRouteOrigin == origin) {
                syncMapViewportInsets()
                map.showRoutePreview(route, origin.point)
            }
        }, 120L)
    }

    private fun clearRoutePreview(recenter: Boolean = true) {
        previewRoute = null
        previewRouteOrigin = null
        previewRoutePreference = null
        if (currentRoute != null) activeRoutePreference?.let(::setRoutePreference)
        routeCoverageGeneration++
        routeCoverageInFlight = false
        routeCoverageCompleted = 0
        routeCoverageTotal = 0
        routeCoverageSuccess = 0
        routeCoverageFailure = 0
        routeCoverageCameraCount = 0
        routeCoverageError = null
        routeCameraSourceStatuses = emptyList()
        if (currentRoute != null) {
            map.setRoute(currentRoute)
        } else {
            map.setRoute(null)
            if (recenter) map.recenter()
        }
        currentLocation?.let { location ->
            val point = GeoPoint(location.latitude, location.longitude)
            refreshDeviceSnapshot(point, force = true)
            updateNavigationUi(location)
        }
        applyNavigationLayout()
    }

    private fun updatePreviewCameraLayer() {
        val route = previewRoute ?: return
        requestDeviceLayer(DeviceLayerRequest(null, route, currentRoute, previewRoute))
    }

    private fun activateRoute(
        route: Route,
        preference: RoutePreference,
        origin: SearchResult,
        startHistory: Boolean,
        restoredStep: Int = 0
    ) {
        val step = restoredStep.coerceIn(0, route.steps.lastIndex.coerceAtLeast(0))
        val token = navigationActivation.begin()
        val previousTripId = activeTripId
        statusText.text = if (startHistory) "Starting selected route…" else "Updating navigation route…"
        executor.execute {
            val encodedState = runCatching {
                ActiveRouteStateCodec.encode(ActiveRouteState(route, origin, preference, step))
            }.getOrNull()
            if (encodedState == null) {
                postToUi {
                    if (navigationActivation.isCurrent(token)) {
                        statusText.text = "Could not prepare the exact selected route"
                        toast("Navigation was not started; the selected route could not be preserved")
                    }
                }
                return@execute
            }

            val cameraCount = if (startHistory) {
                runCatching { routeCameraCandidates(listOf(route)).size }.getOrDefault(0)
            } else 0
            var preparedTripId = previousTripId
            var persisted = false
            val current = runCatching {
                navigationActivation.runIfCurrent(token) {
                    if (startHistory) {
                        preparedTripId = runCatching {
                            historyDb.startTrip(route, origin, preference, cameraCount)
                        }.getOrDefault(-1L)
                    }
                    persisted = prefs.edit()
                        .putBoolean("activeNav", true)
                        .putString("activeDestLat", route.destination.point.lat.toString())
                        .putString("activeDestLon", route.destination.point.lon.toString())
                        .putString("activeDestName", route.destination.displayName)
                        .putString("activeOriginLat", origin.point.lat.toString())
                        .putString("activeOriginLon", origin.point.lon.toString())
                        .putString("activeOriginName", origin.displayName)
                        .putLong("activeTripId", preparedTripId)
                        .putString(PREF_ACTIVE_ROUTE_STATE, encodedState)
                        .putInt(PREF_ACTIVE_ROUTE_STEP, step)
                        .commit()
                    if (persisted && startHistory && previousTripId > 0L) {
                        runCatching { historyDb.finishTrip(previousTripId) }
                    }
                }
            }.getOrDefault(false)

            if (!current || !persisted) {
                if (startHistory && preparedTripId > 0L && preparedTripId != previousTripId) {
                    runCatching { historyDb.finishTrip(preparedTripId) }
                }
                if (current) postToUi {
                    if (navigationActivation.isCurrent(token)) {
                        statusText.text = "Could not save the exact selected route"
                        toast("Navigation was not started because route-lock persistence failed")
                    }
                }
                return@execute
            }

            postToUi {
                if (!navigationActivation.isCurrent(token)) {
                    if (startHistory && preparedTripId > 0L && preparedTripId != previousTripId) {
                        executor.execute { runCatching { historyDb.finishTrip(preparedTripId) } }
                    }
                    return@postToUi
                }
                commitPreparedRoute(route, preference, origin, preparedTripId, step)
            }
        }
    }

    private fun commitPreparedRoute(
        route: Route,
        preference: RoutePreference,
        origin: SearchResult,
        tripId: Long,
        restoredStep: Int
    ) {
        cameraGeneration++
        cameraSyncInFlight = false
        cameraSyncPending = false
        cameraSyncPendingForce = false
        deviceSnapshotReady = false
        currentRoute = route
        currentRouteOrigin = origin
        activeRoutePreference = preference
        previewRoute = null
        previewRouteOrigin = null
        previewRoutePreference = null
        activeTripId = tripId
        currentStep = restoredStep.coerceIn(0, route.steps.lastIndex.coerceAtLeast(0))
        spokenStep = -1
        spokenApproachStep = -1
        routeEditorOpen = false
        offRouteFixes = 0
        alertEngine.reset()
        setRoutePreference(preference)
        map.setRoute(route)
        map.recenter()
        applyNavigationLayout()
        currentLocation?.let { updateNavigationUi(it) }
        if (voiceEnabled()) {
            route.steps.getOrNull(currentStep)?.let { step ->
                val stepIndex = currentStep
                WatchManSpeech.maneuver("nav-start-${System.identityHashCode(route)}-$stepIndex", step.instruction, true) {
                    !activityDestroyed && currentRoute === route && currentStep == stepIndex
                }
                spokenStep = currentStep
            }
        }
        runCatching { NavigationForegroundService.start(this, route) }
        NavigationForegroundService.requestImmediateCameraSync()
    }

    private fun persistActiveRouteState() {
        val route = currentRoute ?: return
        val origin = currentRouteOrigin ?: return
        val preference = activeRoutePreference ?: currentRoutePreference()
        val step = currentStep
        executor.execute {
            val encoded = runCatching {
                ActiveRouteStateCodec.encode(ActiveRouteState(route, origin, preference, step))
            }.getOrNull() ?: return@execute
            postToUi {
                if (currentRoute !== route) return@postToUi
                prefs.edit()
                    .putString(PREF_ACTIVE_ROUTE_STATE, encoded)
                    .putInt(PREF_ACTIVE_ROUTE_STEP, step)
                    .apply()
            }
        }
    }
    private fun updateGpsStatePriority(point: GeoPoint) {
        val now = System.currentTimeMillis()
        val lastPoint = lastGpsStateLookupPoint
        if (gpsStateLookupInFlight) return
        if (lastPoint != null &&
            now - lastGpsStateLookupAt < GPS_STATE_LOOKUP_INTERVAL_MS &&
            Geo.distanceMeters(lastPoint, point) < GPS_STATE_LOOKUP_DISTANCE_METERS) {
            return
        }

        gpsStateLookupInFlight = true
        lastGpsStateLookupAt = now
        lastGpsStateLookupPoint = point
        try {
            executor.execute {
                try {
                    val jurisdiction = stateBoundaryLookup.find(point)
                    currentGpsStateSourceId = jurisdiction?.nationalSourceId
                    if (jurisdiction != null) {
                        postToUi {
                            if (firstRunHost.visibility == View.VISIBLE && firstRunSetupBox.visibility == View.VISIBLE) {
                                firstRunLocale.text = "Priority queued: current GPS state - ${jurisdiction.name}"
                            }
                        }
                    }
                } finally {
                    gpsStateLookupInFlight = false
                }
            }
        } catch (_: RuntimeException) {
            gpsStateLookupInFlight = false
        }
    }

    private fun onLocation(location: Location) {

        try {
            val previous = currentLocation
            if (previous != null && location.accuracy > 100f && previous.accuracy < location.accuracy) return

            currentLocation = location
            val point = GeoPoint(location.latitude, location.longitude)
            updateGpsStatePriority(point)
            map.setLocation(point, location.accuracy, if (location.hasBearing()) location.bearing else null)
            updateSpeedHud(location)
            restoreNavigationIfNeeded()

            if (previewRoute == null || currentRoute != null) refreshDeviceSnapshot(point)

            updateNavigationUi(location)
            val typedRoutePreview = previewRoute != null && currentRoute == null && previewRouteOrigin?.displayName != CURRENT_GPS_LABEL
            if (!typedRoutePreview) {
                refreshCameras(force = false)
                handleCameraAlert(location)
            }
            if (currentRoute != null) {
                NavigationForegroundService.submitActivityLocation(location, currentCameras)
            }
            maybeReroute(location)
        } catch (e: Exception) {
            statusText.text = "Location update recovered"
            tripText.text = e.message ?: "A location update could not be processed"
        }
    }

    private fun updateNavigationUi(location: Location) {
        val route = currentRoute
        updateCameraStatusText()

        if (route == null) {
            val preview = previewRoute
            val origin = previewRouteOrigin
            if (preview != null && origin != null) {
                val preference = previewRoutePreference ?: currentRoutePreference()
                setTextIfChanged(statusText, "Route preview · ${preference.label} · live GPS navigation off")
                setTextIfChanged(tripText, "${distanceMiles(preview.distanceMeters)} · ${Geo.etaText(preview.durationSeconds)}\n${origin.displayName.take(54)} → ${preview.destination.displayName.take(54)}")
            } else {
                setTextIfChanged(statusText, "Ready · ${location.accuracy.roundToInt()} m GPS")
                setTextIfChanged(tripText, "Following your position · zoom ${map.currentZoom()} · ${currentRoutePreference().label} routing")
            }
            applyNavigationLayout()
            return
        }

        val me = GeoPoint(location.latitude, location.longitude)
        val progressed = NavigationProgress.upcomingStepIndex(route, me, currentStep)
        if (progressed != currentStep) {
            currentStep = progressed
            spokenStep = -1
            spokenApproachStep = -1
            persistActiveRouteStep()
        }

        var step = route.steps.getOrNull(currentStep)
        var toStep = step?.let { NavigationProgress.distanceToUpcomingManeuver(route, me, currentStep) }
        if (step != null && toStep == null && currentStep < route.steps.lastIndex) {
            currentStep++
            spokenStep = -1
            spokenApproachStep = -1
            persistActiveRouteStep()
            step = route.steps.getOrNull(currentStep)
            toStep = step?.let { NavigationProgress.distanceToUpcomingManeuver(route, me, currentStep) }
        }
        setTextIfChanged(statusText, if (step == null) {
            "Continue to destination"
        } else {
            "${step.instruction} · ${Geo.distanceText((toStep ?: 0.0).coerceAtLeast(0.0))}"
        })

        val remaining = Geo.remainingRouteDistanceMeters(me, route.points)
        val ratio = if (route.distanceMeters > 0.0) (remaining / route.distanceMeters).coerceIn(0.0, 1.2) else 0.0
        setTextIfChanged(tripText, "${Geo.distanceText(remaining)} remaining · ${Geo.etaText(route.durationSeconds * ratio)} · ${(activeRoutePreference ?: currentRoutePreference()).label}\n${route.destination.displayName.take(90)}")

        if (voiceEnabled() && step != null && toStep != null) {
            announceNavigationStep(step.instruction, toStep)
        }
    }

    private fun persistActiveRouteStep() {
        if (currentRoute == null) return
        prefs.edit().putInt(PREF_ACTIVE_ROUTE_STEP, currentStep).apply()
    }

    private fun announceNavigationStep(instruction: String, distanceMeters: Double) {
        val route = currentRoute ?: return
        val stepIndex = currentStep
        when {
            distanceMeters <= 180.0 && spokenApproachStep != currentStep -> {
                spokenApproachStep = currentStep
                WatchManSpeech.maneuver("nav-final-${System.identityHashCode(route)}-$stepIndex", instruction, true) {
                    !activityDestroyed && currentRoute === route && currentStep == stepIndex
                }
            }
            distanceMeters <= 1200.0 && spokenStep != currentStep -> {
                spokenStep = currentStep
                WatchManSpeech.maneuver("nav-preview-${System.identityHashCode(route)}-$stepIndex", "In ${Geo.distanceText(distanceMeters)}, $instruction", false) {
                    !activityDestroyed && currentRoute === route && currentStep == stepIndex && spokenApproachStep != stepIndex
                }
            }
        }
    }
    private fun maybeReroute(location: Location) {
        val route = currentRoute ?: return
        val me = GeoPoint(location.latitude, location.longitude)
        val idx = Geo.nearestRouteIndex(me, route.points)
        val distanceFromRoute = Geo.distanceToRouteMeters(me, route.points, idx)
        if (distanceFromRoute > 90.0) offRouteFixes++ else offRouteFixes = 0

        val now = System.currentTimeMillis()
        if (offRouteFixes >= 3 && !routePlanningInFlight && now - lastRerouteAt > REROUTE_MIN_INTERVAL_MS) {
            lastRerouteAt = now
            offRouteFixes = 0
            planRoutes(route.destination, activeRoutePreference ?: currentRoutePreference(), automatic = true)
        }
    }

    /**
     * Camera-network refresh is deliberately decoupled from location processing.
     * GPS fixes continue at the LocationController cadence regardless of a slow network call.
     * In-flight requests are coalesced, not dropped: movement or an explicit refresh queues
     * another pass immediately after the current pass completes.
     */
    private fun refreshCameras(force: Boolean) {
        val loc = currentLocation ?: return
        val point = GeoPoint(loc.latitude, loc.longitude)

        // During active navigation the foreground service owns camera-network scheduling.
        // The Activity uses its last published cache on each fix; database refreshes are off-main.
        if (currentRoute != null) {
            if (force) NavigationForegroundService.requestImmediateCameraSync()
            return
        }

        val now = System.currentTimeMillis()
        val moved = cameraRefreshPoint?.let { Geo.distanceMeters(it, point) } ?: Double.MAX_VALUE
        if (cameraSyncInFlight) {
            val meaningfulPending = force || moved >= CAMERA_INFLIGHT_MOVE_TRIGGER_M || now - cameraLastAttemptAt >= CAMERA_IDLE_REFRESH_MS
            if (meaningfulPending) { cameraSyncPending = true; cameraSyncPendingForce = cameraSyncPendingForce || force }
            return
        }
        if (!force && cameraSyncError != null && now - cameraLastAttemptAt < CAMERA_FAILURE_RETRY_MS) return
        if (!force && cameraRefreshAt > 0L && now - cameraRefreshAt < CAMERA_IDLE_REFRESH_MS && moved < CAMERA_IDLE_MOVE_TRIGGER_M) return

        cameraLastAttemptAt = now
        cameraSyncInFlight = true
        cameraSyncError = null
        val generation = ++cameraGeneration
        val statuses = ConcurrentHashMap<String, CameraSourceStatus>()
        val remainingGroups = AtomicInteger(2)
        updateCameraStatusText()

        fun publish(result: CameraSourceResult) {
            if (generation != cameraGeneration) return
            if (result.status.ok) runCatching { db.replaceSourceSnapshot(result.status.sourceId, result.cameras) }
            statuses[result.status.sourceId] = result.status
            postToUi {
                if (generation != cameraGeneration) return@postToUi
                cameraSourceStatuses = statuses.values.sortedBy { it.sourceId }
                refreshDisplayedCameraLayerFromCaches()
                cameraRefreshAt = System.currentTimeMillis()
                cameraRefreshPoint = point
                updateCameraStatusText()
                currentLocation?.let { updateNavigationUi(it) }
            }
        }

        fun finishGroup() {
            if (remainingGroups.decrementAndGet() != 0) return
            postToUi {
                if (generation != cameraGeneration) return@postToUi
                cameraSyncInFlight = false
                cameraSourceStatuses = statuses.values.sortedBy { it.sourceId }
                val failed = cameraSourceStatuses.count { !it.ok }
                val ok = cameraSourceStatuses.count { it.ok }
                cameraSyncError = when {
                    cameraSourceStatuses.isNotEmpty() && ok == 0 -> "all camera sources unavailable"
                    failed > 0 -> "$failed/${cameraSourceStatuses.size} sources unavailable"
                    else -> null
                }
                cameraRefreshAt = System.currentTimeMillis()
                cameraRefreshPoint = point
                updateCameraStatusText()
                consumePendingCameraRefresh()
            }
        }

        executor.execute {
            try { cameraProvider.syncNearbyIncremental(point, CAMERA_QUERY_RADIUS_METERS, ::publish) }
            catch (_: Exception) { }
            finally { finishGroup() }
        }
        executor.execute {
            try { publicCameraProvider.syncIncremental(point, prefs, ::publish) }
            catch (_: Exception) { }
            finally { finishGroup() }
        }
    }

    private fun consumePendingCameraRefresh() {
        if (!cameraSyncPending) return
        val force = cameraSyncPendingForce
        cameraSyncPending = false
        cameraSyncPendingForce = false
        refreshCameras(force = force)
    }

    private fun updateCameraStatusText() {
        if (!::cameraText.isInitialized) return
        val preview = previewRoute.takeIf { currentRoute == null }
        val count = currentCameras.size
        val alpr = currentCameras.count { it.type == CameraType.ALPR || it.type == CameraType.FLOCK }
        val enforcement = currentCameras.count { it.type == CameraType.SPEED || it.type == CameraType.RED_LIGHT }
        val traffic = currentCameras.count { it.type == CameraType.TRAFFIC || it.type == CameraType.HIGHWAY || it.type == CameraType.SENSOR }

        val state = if (preview != null) {
            when {
                routeCoverageInFlight -> {
                    val total = routeCoverageTotal.coerceAtLeast(1)
                    "route coverage ${routeCoverageCompleted.coerceAtMost(total)}/$total areas · $count known"
                }
                routeCoverageError != null && count > 0 -> "$count along route · ${routeCoverageShortLabel()} · tap to retry"
                routeCoverageError != null -> "route coverage unavailable · tap to retry"
                routeCoverageTotal > 0 -> "$count along route · ${routeCoverageShortLabel()}"
                count > 0 -> "$count cached along route · refreshing"
                else -> "route coverage pending · tap to refresh"
            }
        } else {
            val sourceSummary = if (cameraSourceStatuses.isEmpty()) "" else {
                val ok = cameraSourceStatuses.count { it.ok }
                " · $ok/${cameraSourceStatuses.size} feeds"
            }
            when {
                currentLocation == null -> "waiting for GPS"
                cameraSyncInFlight -> "syncing… · $count cached"
                cameraSyncError != null && count > 0 -> "partial sync · $count cached$sourceSummary · tap to retry"
                cameraSyncError != null -> "sync failed · tap to retry"
                cameraRefreshAt > 0L -> "$count nearby$sourceSummary"
                count > 0 -> "$count cached · syncing"
                else -> "not synced · tap to refresh"
            }
        }
        val breakdown = if (count > 0) " · $enforcement enforcement · $alpr ALPR · $traffic traffic" else ""
        setTextIfChanged(cameraText, "Cameras / sensors: $state$breakdown · Alert: ${alertDistanceShort()}")
        updateFirstRunNearbyState()
    }

    private fun handleCameraAlert(location: Location) {
        val update = alertEngine.update(
            location,
            currentCameras,
            currentRoute,
            UserPreferences.proximityProfile(prefs)
        )
        persistProximityEncounterState(update)
        proximityTonePlayer.update(
            update.proximity.takeIf { ProximityAudioOwnership.isOwner(ProximityAudioOwnership.Owner.ACTIVITY) },
            UserPreferences.proximityToneEnabled(prefs),
            UserPreferences.proximityVolumePercent(prefs)
        )
        if (voiceEnabled()) update.speechAlerts.forEach { WatchManSpeech.camera(it, alertEngine.describe(it)) }
        val alert = update.alert ?: return
        val msg = alertEngine.describe(alert)
        alertBanner.text = "CAMERA AHEAD · $msg"
        alertBanner.visibility = View.VISIBLE
        main.removeCallbacks(hideAlert)
        main.postDelayed(hideAlert, 7000L)

        if (UserPreferences.vibrationEnabled(prefs)) {
            try {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(UserPreferences.vibrationPattern(prefs), -1))
            } catch (_: Exception) {
                // Vibration is optional; never crash navigation if unavailable.
            }
        }

    }


    private fun persistProximityEncounterState(update: AlertEngine.Update) {
        val now = monotonicMillis()
        val signature = update.proximity?.let { "${it.deviceId}|${it.stage}|${it.fovBasis}" } ?: "none"
        val transition = signature != lastProximityStateSignature
        if (transition || update.alert != null || now - lastProximityStatePersistAt >= PROXIMITY_STATE_PERSIST_INTERVAL_MS) {
            prefs.edit().putString(ProximityEncounterSession.PREF_STATE_KEY, ProximityEncounterSession.encode()).apply()
            update.diagnostic?.let { diagnostic ->
                localDiagnostics.recordProximityState(
                    LocalDiagnostics.ProximityState(
                        owner = "ACTIVITY",
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
    private val hideAlert = Runnable { alertBanner.visibility = View.GONE }

    private fun showCameraSourceStatus() {
        lateinit var nationalSummaryHost: LinearLayout
        showMenu("Camera data sources", { showToolsMenu() }) { body ->
            addMenuText(body, "Nearby / live feeds", emphasized = true)
            if (cameraSourceStatuses.isEmpty()) {
                addMenuText(body, "No nearby-source diagnostics yet. Nearby live acquisition follows the v0.1.8 SafeTOC-compatible source-by-source behavior.")
            } else {
                cameraSourceStatuses.forEach { src ->
                    val headline = if (src.ok) "✓ ${src.sourceName}" else "✕ ${src.sourceName}"
                    val detail = buildString {
                        append(if (src.ok) "${src.count} records" else (src.error ?: "Unavailable"))
                        src.endpoint?.let { append("\n$it") }
                        append("\n${src.elapsedMs} ms")
                    }
                    addMenuText(body, headline, emphasized = true)
                    addMenuText(body, detail)
                }
            }

            if (previewRoute != null || routeCameraSourceStatuses.isNotEmpty()) {
                addMenuText(body, "Route-corridor coverage", emphasized = true)
                addMenuText(body, "${routeCoverageShortLabel()} · $routeCoverageCameraCount cameras/sensors in the active route corridor")
                routeCameraSourceStatuses.take(24).forEach { src ->
                    val state = if (src.ok) "✓" else "✕"
                    addMenuText(body, "$state ${src.sourceId} · ${if (src.ok) "${src.count} records" else (src.error ?: "Unavailable")}")
                }
                if (routeCameraSourceStatuses.size > 24) {
                    addMenuText(body, "${routeCameraSourceStatuses.size - 24} additional route areas omitted from this diagnostics view.")
                }
                if (previewRoute != null && currentRoute == null) {
                    addMenuAction(body, "REFRESH THIS ROUTE", "Retry the typed Start → Destination camera/sensor corridor without using GPS.") {
                        closeMenu()
                        retryRouteCoverage()
                    }
                }
            }

            addMenuText(body, "National bootstrap cache", emphasized = true)
            nationalSummaryHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(nationalSummaryHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addMenuText(nationalSummaryHost, "Loading national cache diagnostics…")
            addMenuAction(body, "RESUME NATIONAL CACHE", "Continue missing/failed United States region pulls in the background. GPS is not required.") {
                startFirstRunBootstrapIfNeeded()
                closeMenu()
            }

            addMenuAction(body, "REFRESH NEARBY LIVE SOURCES", "Refresh the normal GPS-centered v0.1.8 camera/sensor feeds.") {
                closeMenu()
                refreshCameras(force = true)
            }
        }
        // Request after showMenu returns: even synchronous executor rejection can render recovery.
        nationalSourceSummaryWorker.request(nationalSummaryHost)
    }

    private fun readNationalSourceSummary(): NationalSourceSummary {
        check(Looper.myLooper() != Looper.getMainLooper()) { "National source diagnostics must load off-main" }
        val states = runCatching { nationalCameraCache.regionStates() }
        val devices = runCatching { nationalCameraCache.totalCameraCount() }
        val details = ArrayList<String>()
        val regionsLabel = states.fold({ values ->
            val pending = values.filter { !it.ok }
            pending.take(8).forEach { details += "• ${it.regionId}: ${it.error ?: "retry pending"}" }
            if (pending.size > 8) details += "${pending.size - 8} additional regions pending retry."
            "${values.count { it.ok }}/${NationalCameraBootstrapProvider.REGIONS.size} regions ready"
        }, {
            details += "Region progress could not be read. Cached data has not been cleared."
            "Region status unavailable"
        })
        val devicesLabel = devices.fold({ "$it cached cameras/sensors" }, {
            details += "The cached device count could not be read. Cached data has not been cleared."
            "cached camera/sensor count unavailable"
        })
        return NationalSourceSummary(regionsLabel, devicesLabel, details, states.isFailure || devices.isFailure)
    }

    private fun publishNationalSourceSummary(host: LinearLayout, result: Result<NationalSourceSummary>) {
        // A close/reopen or another menu detaches the old host; never fill that stale drawer.
        if (activityDestroyed || !menuOpen || host.parent !== menuBody) return
        host.removeAllViews()
        val summary = result.getOrNull()
        if (summary == null) {
            addMenuText(host, "National cache diagnostics unavailable. Cached data has not been cleared.")
        } else {
            addMenuText(host, "${summary.regions} · ${summary.devices} · ${if (nationalBootstrapInFlight) "refreshing now" else "idle"}")
            summary.details.forEach { addMenuText(host, it) }
        }
        if (summary == null || summary.failed) {
            addMenuAction(host, "RETRY CACHE DIAGNOSTICS", "Read the existing cache again without starting a network refresh.") {
                showCameraSourceStatus()
            }
        }
    }

    private fun showCameraInfo(cam: CameraPoi) {
        val here = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
        val distance = here?.let { Geo.distanceMeters(it, cam.point) }
        val bearing = here?.let { Geo.bearingDegrees(it, cam.point) }
        val details = buildString {
            append(cam.type.label)
            cam.name?.let { append("\n\nName: $it") }
            cam.road?.let { append("\nRoad: $it") }
            cam.direction?.let { append("\nDirection: $it") }
            cam.operator?.let { append("\nOperator: $it") }
            cam.manufacturer?.let { append("\nManufacturer: $it") }
            if (distance != null && bearing != null) append("\nFrom you: ${Geo.distanceText(distance)} · ${bearing.roundToInt()}°")
            append("\nCoordinates: ${String.format(Locale.US, "%.6f, %.6f", cam.point.lat, cam.point.lon)}")
            append("\nConfidence: ${cam.confidence}%")
            append("\nSource feed: ${cam.source}")
            append("\nSource ID: ${cam.sourceId}")
            append("\nSource record: ${cam.sourceRecordId ?: cam.id}")
            val useful = cam.tags.filterKeys {
                it in setOf(
                    "highway", "enforcement", "surveillance", "surveillance:type", "surveillance:zone",
                    "camera:type", "camera:usage", "camera:mount", "camera:direction", "maxspeed",
                    "monitoring:traffic", "description", "ref", "operator", "manufacturer", "brand"
                )
            }
            useful.forEach { (k, v) -> append("\n$k: $v") }
        }
        showMenu("Camera / sensor information") { body ->
            addMenuText(body, details)
            addMenuAction(body, "OPEN SOURCE RECORD") {
                val url = cam.sourceUrl ?: "https://www.openstreetmap.org/${cam.id}"
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (_: Exception) { toast("No browser available") }
            }
        }
    }

    private fun showExportReportsMenu() {
        showMenu("Export & reports", { showToolsMenu() }) { body ->
            addMenuAction(body, "Directions & route maps", "Printable turn-by-turn directions with maneuver signs, OpenStreetMap route maps and cameras/sensors.") {
                showDirectionsExportMenu()
            }
            addMenuAction(body, "My route analytics", "Local route-history maps, most/least-used routes, charts and passed camera/sensor analytics.") {
                showAnalyticsPeriodMenu()
            }
            addMenuAction(body, "Raw route history CSV", "Portable tabular history for your own analysis.") {
                closeMenu(); exportRouteHistoryCsv()
            }
        }
    }

    private fun showDirectionsExportMenu() {
        val route = routeShownOnMap()
        if (route == null) {
            toast("Start or preview a route before exporting directions")
            return
        }
        val alternatives = exportableAlternatives(route)
        showMenu("Directions & route maps", { showExportReportsMenu() }) { body ->
            addMenuText(body, "Directions use the route shown on the map. Typed Start → Destination previews do not require GPS.", emphasized = true)
            addMenuAction(body, "View turn-by-turn directions", "Open the in-app route map, maneuver signs, road/exit guidance and cameras/sensors for each leg.") {
                showTurnByTurnDirections(route)
            }
            addMenuAction(body, "Save turn-by-turn + map PDF", "Full route map, turn-by-turn maneuver signs, road/exit guidance and cameras/sensors along/near the route.") {
                closeMenu(); exportCurrentDirections(DirectionsExportMode.SELECTED_ROUTE)
            }
            if (alternatives.size > 1) {
                addMenuAction(body, "Export all route alternatives PDF", "A complete directions package for each of the ${alternatives.size} available routes.") {
                    closeMenu(); exportCurrentDirections(DirectionsExportMode.ALL_ALTERNATIVES)
                }
                addMenuAction(body, "Export route comparison PDF", "Compare all alternatives on one map with distance, ETA and camera/sensor counts.") {
                    closeMenu(); exportCurrentDirections(DirectionsExportMode.ROUTE_COMPARISON)
                }
            } else {
                addMenuText(body, "Plan route alternatives first to enable All Alternatives and Route Comparison exports.")
            }
        }
    }

    private fun showTurnByTurnDirections(
        route: Route,
        requestedStartIndex: Int = -1,
        workflowOrigin: SearchResult? = null,
        workflowPreference: RoutePreference? = null
    ) {
        val origin = workflowOrigin ?: routeOriginFor(route) ?: SearchResult(
            "Route origin",
            route.points.firstOrNull() ?: route.destination.point
        )
        val preference = workflowPreference ?: routePreferenceFor(route)
        val focusedIndex = (if (requestedStartIndex < 0 && route == currentRoute) currentStep else requestedStartIndex.coerceAtLeast(0))
            .coerceIn(0, route.steps.lastIndex.coerceAtLeast(0))
        val focusedStep = route.steps.getOrNull(focusedIndex)
        val summaryPrefix = "${distanceMiles(route.distanceMeters)} · ${Geo.etaText(route.durationSeconds)} · ${preference.label}"
        lateinit var request: DirectionsDeviceRequest

        showMenu("Turn-by-turn directions", { showDirectionsExportMenu() }) { body ->
            addMenuText(body, "${origin.displayName} → ${route.destination.displayName}", emphasized = true)
            val deviceHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(deviceHost)
            addMenuText(deviceHost, "$summaryPrefix · Loading cached cameras/sensors…")
            val stepLabels = linkedMapOf<Int, TextView>()
            focusedStep?.let { addFocusedManeuverCard(body, route, focusedIndex, it) }

            val miniMap = TileMapView(this).apply {
                setAlertDistanceFeet(prefs.getInt("alertFeet", 2000))
            }
            body.addView(miniMap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260)).apply {
                topMargin = dp(10)
                bottomMargin = dp(12)
            })
            miniMap.post {
                if (activityDestroyed || !menuOpen || deviceHost.parent !== menuBody) return@post
                miniMap.setRoute(route)
                if (focusedStep == null) miniMap.showRoutePreview(route, origin.point)
                else miniMap.focusOn(focusedStep.maneuver, 16)
            }
            if (focusedStep != null) addManeuverFocusControls(body, route, focusedIndex, origin.point, miniMap)

            if (route.steps.isEmpty()) {
                addMenuText(body, "No maneuver steps were returned for this route.", emphasized = true)
            } else {
                val pageStart = focusedIndex.coerceIn(0, route.steps.lastIndex)
                val pageEndExclusive = (pageStart + DIRECTIONS_STEPS_PER_PAGE).coerceAtMost(route.steps.size)
                addMenuText(body, "Directions ${pageStart + 1}–$pageEndExclusive of ${route.steps.size}", emphasized = true)
                route.steps.subList(pageStart, pageEndExclusive).forEachIndexed { offset, step ->
                    stepLabels[pageStart + offset] = addDirectionStepCard(body, step)
                }
                if (pageStart > 0) {
                    addMenuAction(body, "PREVIOUS DIRECTIONS", "Show the preceding maneuver steps without rebuilding the route.") {
                        showTurnByTurnDirections(route, (pageStart - DIRECTIONS_STEPS_PER_PAGE).coerceAtLeast(0), origin, preference)
                    }
                }
                if (pageEndExclusive < route.steps.size) {
                    addMenuAction(body, "NEXT DIRECTIONS", "Show the next maneuver steps without rebuilding the route.") {
                        showTurnByTurnDirections(route, pageEndExclusive, origin, preference)
                    }
                }
            }
            addMenuAction(body, "SAVE TURN-BY-TURN + MAP PDF", "Generate from cached route/camera data, validate it, then open the Android save picker.") {
                closeMenu()
                exportCurrentDirections(
                    DirectionsExportMode.SELECTED_ROUTE,
                    workflowContextFactory = {
                        buildTurnByTurnExportWorkflowContext(DirectionsExportMode.SELECTED_ROUTE, route, focusedIndex)
                    }
                )
            }
            request = DirectionsDeviceRequest(route, deviceHost, summaryPrefix, miniMap, stepLabels,preference)
        }
        // Request after attachment: executor rejection must also produce a visible retry state.
        directionsDeviceWorker.request(request)
    }

    private fun readDirectionsDevices(request: DirectionsDeviceRequest): DirectionsDevices {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Directions devices must load off-main" }
        val route = request.route
        val corridor = routeCameraCandidates(listOf(route),requireAvailable=true).sortedBy {
            Geo.projectOntoRoute(it.point, route.points)?.distanceAlongMeters ?: Double.MAX_VALUE
        }
        val nearby = runCatching {
            deviceRepository.alongRoutes(listOf(route), DIRECTIONS_MAP_CAMERA_RADIUS_METERS,
                includeRegional = true).devices
        }.getOrDefault(corridor)
        val routed=if(request.preference==RoutePreference.FEWEST_CAMERAS) cachedRouteExposure(route) else null
        val exposureLabel=RoutePlanningPolicy.exposureLabel(RouteAnalysis(route,corridor.size,0.0,routed)) +
            if(request.preference==RoutePreference.FEWEST_CAMERAS && routed==null) " · routed check unavailable" else ""
        return DirectionsDevices(corridor.size,
            MapDeviceDeclutter.select(route, nearby, MapDeviceDeclutter.INTERACTIVE_ROUTE_LIMIT),
            request.stepLabels.keys.associateWith { camerasForStep(route, it, corridor) },exposureLabel)
    }

    private fun publishDirectionsDevices(request: DirectionsDeviceRequest, result: Result<DirectionsDevices>) {
        if (activityDestroyed || !menuOpen || request.host.parent !== menuBody) return
        request.host.removeAllViews()
        val devices = result.getOrNull()
        if (devices == null) {
            addMenuText(request.host, "${request.summaryPrefix} · Camera/sensor details unavailable. Directions and saving remain available.")
            request.stepLabels.values.forEach { it.text = "Camera/sensor details unavailable"; it.visibility = View.VISIBLE }
            addMenuAction(request.host, "RETRY DIRECTIONS CAMERA DETAILS", "Read existing cache again; no network refresh is required.") {
                request.host.removeAllViews()
                addMenuText(request.host, "${request.summaryPrefix} · Loading cached cameras/sensors…")
                request.stepLabels.values.forEach { it.text = "Loading cached cameras/sensors…" }
                directionsDeviceWorker.request(request)
            }
            return
        }
        addMenuText(request.host, "${request.summaryPrefix} · ${devices.exposureLabel}")
        request.map.setCameras(devices.mapCameras)
        request.stepLabels.forEach { (index, label) ->
            val cameras = devices.stepCameras[index].orEmpty()
            label.visibility = if (cameras.isEmpty()) View.GONE else View.VISIBLE
            val line = cameras.take(DIRECTIONS_MAX_CAMERAS_PER_STEP).joinToString(" · ") {
                it.name?.takeIf(String::isNotBlank) ?: it.type.label
            }
            val suffix = if (cameras.size > DIRECTIONS_MAX_CAMERAS_PER_STEP) " · +${cameras.size - DIRECTIONS_MAX_CAMERAS_PER_STEP} more" else ""
            label.text = "Nearby cameras / sensors before next maneuver: $line$suffix"
        }
    }

    private fun addFocusedManeuverCard(
        body: LinearLayout,
        route: Route,
        index: Int,
        step: com.bggg.watchman.data.RouteStep
    ) {
        val light = UserPreferences.isLight(prefs)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(if (light) Color.rgb(229, 238, 249) else Color.rgb(28, 45, 66), 18f)
        }
        row.addView(ManeuverSignView(this).apply {
            setStep(step)
            setPalette(
                if (light) Color.rgb(24, 59, 98) else Color.WHITE,
                if (light) Color.WHITE else Color.rgb(17, 26, 39),
                Color.rgb(55, 139, 235)
            )
        }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginEnd = dp(12) })
        val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textBox.addView(TextView(this).apply {
            text = if (route == currentRoute && index == currentStep) "NEXT MANEUVER" else "STEP ${index + 1} OF ${route.steps.size}"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (light) Color.rgb(49, 90, 139) else Color.rgb(142, 193, 255))
        })
        textBox.addView(TextView(this).apply {
            text = step.instruction
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (light) Color.rgb(20, 32, 47) else Color.WHITE)
            setPadding(0, dp(3), 0, 0)
        })
        textBox.addView(TextView(this).apply {
            text = buildString {
                append(Geo.distanceText(step.distanceMeters))
                stepRoadSign(step).takeIf { it.isNotBlank() }?.let { append(" · $it") }
                step.destinations.takeIf { it.isNotBlank() }?.let { append(" · toward $it") }
            }
            textSize = 13f
            setTextColor(if (light) Color.rgb(65, 76, 91) else Color.rgb(205, 217, 232))
            setPadding(0, dp(5), 0, 0)
        })
        route.steps.getOrNull(index + 1)?.let { following ->
            textBox.addView(TextView(this).apply {
                text = "Then: ${following.instruction}"
                textSize = 12f
                setTextColor(if (light) Color.rgb(79, 87, 99) else Color.rgb(173, 183, 198))
                setPadding(0, dp(5), 0, 0)
            })
        }
        row.addView(textBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
    }

    private fun addManeuverFocusControls(
        body: LinearLayout,
        route: Route,
        index: Int,
        origin: GeoPoint,
        directionsMap: TileMapView
    ) {
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val previous = largeButton("‹ PREVIOUS").apply {
            isEnabled = index > 0
            alpha = if (isEnabled) 1f else .45f
            setOnClickListener { showTurnByTurnDirections(route, index - 1) }
        }
        val overview = largeButton("ROUTE OVERVIEW").apply {
            contentDescription = "Fit the complete route on the directions map"
            setOnClickListener { directionsMap.showRoutePreview(route, origin) }
        }
        val next = largeButton("NEXT ›").apply {
            isEnabled = index < route.steps.lastIndex
            alpha = if (isEnabled) 1f else .45f
            setOnClickListener { showTurnByTurnDirections(route, index + 1) }
        }
        controls.addView(previous, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(3) })
        controls.addView(overview, LinearLayout.LayoutParams(0, dp(46), 1.25f).apply { marginStart = dp(3); marginEnd = dp(3) })
        controls.addView(next, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(3) })
        body.addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))
    }
    private fun addDirectionStepCard(
        body: LinearLayout,
        step: com.bggg.watchman.data.RouteStep
    ): TextView {
        val light = UserPreferences.isLight(prefs)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(if (light) Color.rgb(232, 230, 222) else Color.rgb(42, 48, 59), 14f)
        }
        val sign = ManeuverSignView(this).apply {
            setStep(step)
            setPalette(
                if (light) Color.rgb(32, 35, 40) else Color.WHITE,
                if (light) Color.rgb(250, 248, 240) else Color.rgb(27, 31, 38),
                if (light) Color.rgb(161, 124, 51) else Color.rgb(75, 145, 232)
            )
        }
        row.addView(sign, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(10) })

        val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val roadSign = stepRoadSign(step)
        if (roadSign.isNotBlank()) {
            textBox.addView(TextView(this).apply {
                text = roadSign
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (light) Color.rgb(88, 65, 18) else Color.rgb(244, 190, 91))
                setPadding(0, 0, 0, dp(3))
            })
        }
        textBox.addView(TextView(this).apply {
            text = step.instruction
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (light) Color.rgb(28, 31, 35) else Color.WHITE)
        })
        val detail = buildString {
            append(Geo.distanceText(step.distanceMeters))
            if (step.roadName.isNotBlank() && !step.instruction.contains(step.roadName, true)) append(" · ${step.roadName}")
            if (step.destinations.isNotBlank() && !step.instruction.contains(step.destinations, true)) append(" · toward ${step.destinations}")
        }
        textBox.addView(TextView(this).apply {
            text = detail
            textSize = 12f
            setTextColor(if (light) Color.rgb(78, 82, 88) else Color.rgb(194, 201, 211))
            setPadding(0, dp(3), 0, 0)
        })

        if (step.lanes.isNotEmpty()) {
            val laneText = step.lanes.joinToString("   ") { lane ->
                val indication = lane.indications.joinToString("/").ifBlank { "straight" }
                if (lane.valid) "[${indication.uppercase(Locale.US)}]" else indication.lowercase(Locale.US)
            }
            textBox.addView(TextView(this).apply {
                text = "Lanes: $laneText"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (light) Color.rgb(58, 82, 66) else Color.rgb(138, 214, 160))
                setPadding(0, dp(5), 0, 0)
            })
        }

        val cameraLabel = TextView(this).apply {
            text = "Loading cached cameras/sensors…"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(228, 156, 72))
            setPadding(0, dp(7), 0, 0)
        }
        textBox.addView(cameraLabel)
        row.addView(textBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
            bottomMargin = dp(5)
        })
        return cameraLabel
    }

    private fun camerasForStep(route: Route, stepIndex: Int, cameras: List<CameraPoi>): List<CameraPoi> {
        if (route.steps.isEmpty() || cameras.isEmpty()) return emptyList()
        val start = if (stepIndex <= 0) 0.0 else {
            Geo.projectOntoRoute(route.steps[stepIndex].maneuver, route.points)?.distanceAlongMeters ?: 0.0
        }
        val end = if (stepIndex >= route.steps.lastIndex) route.distanceMeters + 100.0 else {
            Geo.projectOntoRoute(route.steps[stepIndex + 1].maneuver, route.points)?.distanceAlongMeters ?: route.distanceMeters
        }
        val lo = minOf(start, end) - 30.0
        val hi = maxOf(start, end) + 30.0
        return cameras.filter { camera ->
            val along = Geo.projectOntoRoute(camera.point, route.points)?.distanceAlongMeters ?: return@filter false
            along in lo..hi
        }.sortedBy { Geo.projectOntoRoute(it.point, route.points)?.distanceAlongMeters ?: Double.MAX_VALUE }
    }

    private fun stepRoadSign(step: com.bggg.watchman.data.RouteStep): String = buildString {
        if (step.exits.isNotBlank()) append("EXIT ${step.exits}")
        if (step.roadRef.isNotBlank()) {
            if (isNotEmpty()) append("  ·  ")
            append(step.roadRef)
        }
        if (step.rotaryName.isNotBlank()) {
            if (isNotEmpty()) append("  ·  ")
            append(step.rotaryName)
        }
    }

    private fun exportableAlternatives(selectedRoute: Route): List<Route> {
        val routes = lastRouteAnalyses.map { it.route }
        val matching = routes.filter {
            Geo.distanceMeters(it.destination.point, selectedRoute.destination.point) < 20.0
        }
        return if (matching.any { it == selectedRoute } && matching.size > 1) matching else listOf(selectedRoute)
    }

    private fun routeShownOnMap(): Route? = RouteContextPolicy.visibleRoute(currentRoute, previewRoute)

    private fun routeIdentityMatches(route: Route, candidate: Route?): Boolean =
        candidate != null && (route === candidate || ActiveRouteStateCodec.routeIdentity(route) == ActiveRouteStateCodec.routeIdentity(candidate))

    private fun routeOriginFor(route: Route): SearchResult? = when {
        routeIdentityMatches(route, previewRoute) -> previewRouteOrigin
        routeIdentityMatches(route, currentRoute) -> currentRouteOrigin
        lastRouteAnalyses.any { routeIdentityMatches(route, it.route) } -> lastRouteAnalysisOrigin
        else -> null
    }

    private fun routePreferenceFor(route: Route): RoutePreference = when {
        routeIdentityMatches(route, previewRoute) -> previewRoutePreference ?: lastRouteAnalysisPreference ?: currentRoutePreference()
        routeIdentityMatches(route, currentRoute) -> activeRoutePreference ?: currentRoutePreference()
        lastRouteAnalyses.any { routeIdentityMatches(route, it.route) } -> lastRouteAnalysisPreference ?: currentRoutePreference()
        else -> currentRoutePreference()
    }

    private fun mergeCameraLayers(primary: List<CameraPoi>, secondary: List<CameraPoi>): List<CameraPoi> {
        val out = LinkedHashMap<String, CameraPoi>()
        fun key(camera: CameraPoi): String {
            val osm = (camera.sourceUrl ?: "").contains("openstreetmap.org") ||
                camera.id.startsWith("node/") || camera.id.startsWith("way/") || camera.id.startsWith("relation/")
            return if (osm && !camera.sourceRecordId.isNullOrBlank()) {
                "osm:${camera.sourceRecordId}"
            } else {
                "source:${camera.sourceId}:${camera.sourceRecordId ?: camera.id}"
            }
        }
        primary.forEach { camera -> out[key(camera)] = camera }
        secondary.forEach { camera -> out.putIfAbsent(key(camera), camera) }
        return out.values.toList()
    }

    private fun encodeWorkflowRoute(
        route: Route,
        origin: SearchResult,
        preference: RoutePreference,
        focusedStep: Int = 0
    ): String = ActiveRouteStateCodec.encode(
        ActiveRouteState(route, origin, preference, focusedStep.coerceIn(0, route.steps.lastIndex.coerceAtLeast(0)))
    )

    private fun buildPreviewExportWorkflowContext(
        mode: DirectionsExportMode,
        analyses: List<RouteAnalysis>,
        recommended: RouteAnalysis,
        selected: RouteAnalysis,
        preference: RoutePreference,
        origin: SearchResult
    ): ExportWorkflowContext = ExportWorkflowContext(
        artifactType = ExportArtifactType.forDirections(mode),
        origin = ExportWorkflowOrigin.PREVIEW,
        routeState = encodeWorkflowRoute(selected.route, origin, preference),
        previewRoutes = analyses.map { analysis ->
            PreviewRouteWorkflowState(
                routeState = encodeWorkflowRoute(analysis.route, origin, preference),
                knownCameraCount = analysis.knownCameraCount,
                scenicScore = analysis.scenicScore,
                routedExposure = analysis.routedExposure
            )
        },
        recommendedRouteIdentity = ActiveRouteStateCodec.routeIdentity(recommended.route),
        selectedRouteIdentity = ActiveRouteStateCodec.routeIdentity(selected.route)
    )

    private fun buildTurnByTurnExportWorkflowContext(
        mode: DirectionsExportMode,
        route: Route,
        focusedStep: Int
    ): ExportWorkflowContext {
        val origin = routeOriginFor(route) ?: SearchResult(
            "Route origin",
            route.points.firstOrNull() ?: route.destination.point
        )
        val preference = routePreferenceFor(route)
        return ExportWorkflowContext(
            artifactType = ExportArtifactType.forDirections(mode),
            origin = ExportWorkflowOrigin.TURN_BY_TURN,
            routeState = encodeWorkflowRoute(route, origin, preference, focusedStep)
        )
    }

    private fun buildDirectionsMenuExportWorkflowContext(
        mode: DirectionsExportMode,
        route: Route
    ): ExportWorkflowContext {
        val origin = routeOriginFor(route) ?: SearchResult(
            "Route origin",
            route.points.firstOrNull() ?: route.destination.point
        )
        val preference = routePreferenceFor(route)
        return ExportWorkflowContext(
            artifactType = ExportArtifactType.forDirections(mode),
            origin = ExportWorkflowOrigin.DIRECTIONS_MENU,
            routeState = encodeWorkflowRoute(route, origin, preference, if (route === currentRoute) currentStep else 0)
        )
    }
    private fun exportCurrentDirections(
        mode: DirectionsExportMode,
        workflowContext: ExportWorkflowContext? = null,
        workflowContextFactory: (() -> ExportWorkflowContext)? = null
    ) {
        val selectedRoute = routeShownOnMap()
        if (selectedRoute == null) {
            toast("Start or preview a route before exporting directions")
            return
        }
        val origin = routeOriginFor(selectedRoute) ?: currentLocation?.let {
            SearchResult(CURRENT_GPS_LABEL, GeoPoint(it.latitude, it.longitude))
        } ?: SearchResult("Route origin", selectedRoute.points.firstOrNull() ?: selectedRoute.destination.point)
        val preference = routePreferenceFor(selectedRoute)
        val focusedStep = if (selectedRoute === currentRoute) currentStep else 0
        val routes = when (mode) {
            DirectionsExportMode.SELECTED_ROUTE -> listOf(selectedRoute)
            else -> exportableAlternatives(selectedRoute)
        }
        statusText.text = "Preparing ${when (mode) { DirectionsExportMode.SELECTED_ROUTE -> "directions"; DirectionsExportMode.ALL_ALTERNATIVES -> "route alternatives"; DirectionsExportMode.ROUTE_COMPARISON -> "route comparison" }} PDF…"
        executor.execute {
            try {
                val exactWorkflowContext = workflowContext ?: workflowContextFactory?.invoke() ?: ExportWorkflowContext(
                    artifactType = ExportArtifactType.forDirections(mode),
                    origin = ExportWorkflowOrigin.DIRECTIONS_MENU,
                    routeState = encodeWorkflowRoute(selectedRoute, origin, preference, focusedStep)
                )
                postToUi { statusText.text = "Rendering directions from cached camera / sensor coverage…" }
                val selections = routes.mapIndexed { index, route ->
                    val routeCameras = routeCameraCandidates(listOf(route))
                    val mapCameras = runCatching {
                        deviceRepository.alongRoutes(
                            listOf(route),
                            RegionalOverpassCameraProvider.ROUTE_REGION_RADIUS_METERS.toDouble(),
                            includeRegional = true
                        ).devices
                    }.getOrDefault(routeCameras)
                    RouteExportSelection(
                        route = route,
                        originName = origin.displayName,
                        origin = origin.point,
                        preference = preference,
                        cameras = routeCameras,
                        selected = route === selectedRoute,
                        label = "Route ${index + 1}${if (route === selectedRoute) " · selected" else ""}",
                        mapCameras = mapCameras,
                        routedExposure = if(preference==RoutePreference.FEWEST_CAMERAS) cachedRouteExposure(route) else null
                    )
                }
                postToUi { statusText.text = "Rendering directions PDF…" }
                val bytes = DirectionsExporter.buildDirectionsPdf(
                    selections,
                    mode,
                    File(cacheDir, "osm-tiles"),
                    assets
                )
                val suffix = when (mode) {
                    DirectionsExportMode.SELECTED_ROUTE -> "directions"
                    DirectionsExportMode.ALL_ALTERNATIVES -> "route-alternatives"
                    DirectionsExportMode.ROUTE_COMPARISON -> "route-comparison"
                }
                postToUi {
                    statusText.text = "Directions PDF rendered"
                    requestDocumentExport(
                        bytes,
                        "WatchMan-$suffix-${fileStamp()}.pdf",
                        "application/pdf",
                        exactWorkflowContext
                    )
                }
                scheduleExportCoverageRefresh(routes)
            } catch (e: Exception) {
                postToUi { statusText.text = "Export failed"; toast(e.message ?: "Could not build directions PDF") }
            }
        }
    }

    private fun showAnalyticsPeriodMenu() {
        showMenu("My route analytics", { showExportReportsMenu() }) { body ->
            addMenuText(body, "Choose the history period. Analytics are generated from the route history stored on this device.", emphasized = true)
            addMenuAction(body, "Last 7 days") { openAnalyticsSummary(System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L, "Last 7 days") }
            addMenuAction(body, "Last 30 days") { openAnalyticsSummary(System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L, "Last 30 days") }
            addMenuAction(body, "Last 90 days") { openAnalyticsSummary(System.currentTimeMillis() - 90L * 24L * 60L * 60L * 1000L, "Last 90 days") }
            addMenuAction(body, "All history") { openAnalyticsSummary(null, "All history") }
        }
    }
    private fun scheduleExportCoverageRefresh(routes: List<Route>) {
        scheduleRouteCoverageRefresh(routes)
        executor.execute {
            runCatching { refreshRouteRegionForExport(routes) }
        }
    }


    private fun openAnalyticsSummary(sinceMillis: Long?, periodLabel: String) {
        statusText.text = "Reading route analytics…"
        executor.execute {
            val snapshot = runCatching { historyDb.analyticsSnapshot(sinceMillis) }.getOrElse {
                postToUi { toast(it.message ?: "Could not read route history") }; return@execute
            }
            if (snapshot.entries.isEmpty()) {
                postToUi { statusText.text = "No route history in $periodLabel"; toast("No saved trips in $periodLabel") }
                return@execute
            }
            val report = RouteAnalyticsEngine.analyze(snapshot)
            postToUi {
                statusText.text = "Analytics ready"
                showMenu("Analytics · $periodLabel", { showAnalyticsPeriodMenu() }) { body ->
                    addMenuText(body, "${report.totalTrips} trips · ${distanceMiles(report.totalDistanceMeters)} · ${report.actualCameraPasses} actual camera/sensor passes", emphasized = true)
                    addMenuText(body, "${report.trackedTripCount} trips include v0.2.0 GPS breadcrumbs. Older trips remain visible using their saved planned route geometry.")
                    report.routeFrequencies.firstOrNull()?.let { most ->
                        addMenuText(body, "Most used: ${shortName(most.representative.originName)} → ${shortName(most.representative.destinationName)} · ${most.tripCount} trips")
                    }
                    report.cameraFrequencies.firstOrNull()?.let { cam ->
                        addMenuText(body, "Most passed device: ${cam.name ?: cam.type.label} · ${cam.passCount} passes")
                    }
                    addMenuAction(body, "EXPORT FULL ANALYTICS PDF", "Maps, route frequency, activity charts, camera/sensor encounters and trip detail.") {
                        closeMenu(); exportAnalyticsReport(sinceMillis, periodLabel)
                    }
                    addMenuAction(body, "EXPORT RAW CSV", "Export the route-history table for the selected period.") {
                        val bytes = DirectionsExporter.routeHistoryCsv(snapshot.entries)
                        closeMenu(); requestDocumentExport(
                            bytes,
                            "WatchMan-route-history-${fileStamp()}.csv",
                            "text/csv",
                            ExportWorkflowContext(
                                artifactType = ExportArtifactType.ANALYTICS_CSV,
                                origin = ExportWorkflowOrigin.ANALYTICS_PERIOD,
                                analyticsSinceMillis = sinceMillis,
                                analyticsPeriodLabel = periodLabel
                            )
                        )
                    }
                }
            }
        }
    }

    private fun exportAnalyticsReport(sinceMillis: Long?, periodLabel: String) {
        statusText.text = "Building analytics report…"
        executor.execute {
            try {
                val snapshot = historyDb.analyticsSnapshot(sinceMillis)
                val bytes = AnalyticsReportExporter.buildPdf(
                    snapshot,
                    periodLabel,
                    File(cacheDir, "osm-tiles"),
                    assets
                )
                postToUi {
                    statusText.text = "Analytics export ready"
                    requestDocumentExport(
                        bytes,
                        "WatchMan-route-analytics-${fileStamp()}.pdf",
                        "application/pdf",
                        ExportWorkflowContext(
                            artifactType = ExportArtifactType.ANALYTICS_PDF,
                            origin = ExportWorkflowOrigin.ANALYTICS_PERIOD,
                            analyticsSinceMillis = sinceMillis,
                            analyticsPeriodLabel = periodLabel
                        )
                    )
                }
            } catch (e: Exception) {
                postToUi { statusText.text = "Analytics export failed"; toast(e.message ?: "Could not build analytics report") }
            }
        }
    }

    private fun showRoutesWorkflow() {
        showMenu("Routes / history", { showToolsMenu() }) { body ->
            addMenuAction(body, "Recently traveled routes") { showRecentRoutes() }
            addMenuAction(body, "Most frequently traveled routes") { showFrequencyRoutes(descending = true) }
            addMenuAction(body, "Least frequently traveled routes") { showFrequencyRoutes(descending = false) }
            addMenuAction(body, "Route analytics reports", "Maps, charts, most/least-used routes and passed camera/sensor analysis.") { showAnalyticsPeriodMenu() }
            addMenuAction(body, "Export route history (CSV)") { closeMenu(); exportRouteHistoryCsv() }
            addMenuAction(body, "Clear route history", danger = true) { confirmClearRouteHistory() }
        }
    }

    private fun showRecentRoutes() {
        statusText.text = "Reading recent routes…"
        executor.execute {
            val entries = runCatching { historyDb.recent(100) }.getOrDefault(emptyList())
            val counts = entries.groupingBy { it.routeKey }.eachCount()
            postToUi {
                if (entries.isEmpty()) {
                    toast("No saved route history yet")
                } else {
                    showMenu("Recently traveled", { showRoutesWorkflow() }) { body ->
                        entries.forEach { entry ->
                            val label = "${formatDate(entry.startedAt)} · ${entry.preference.label}"
                            val detail = "${shortName(entry.originName)} → ${shortName(entry.destinationName)} · ${distanceMiles(entry.distanceMeters)} · ${entry.cameraCount} cams"
                            addMenuAction(body, label, detail) { showRouteHistoryDetail(entry, counts[entry.routeKey] ?: 1) }
                        }
                    }
                }
            }
        }
    }

    private fun showFrequencyRoutes(descending: Boolean) {
        statusText.text = "Reading route frequency…"
        executor.execute {
            val summaries = runCatching { historyDb.frequency(descending, 100) }.getOrDefault(emptyList())
            postToUi {
                if (summaries.isEmpty()) {
                    toast("No saved route history yet")
                } else {
                    showMenu(if (descending) "Most frequently traveled" else "Least frequently traveled", { showRoutesWorkflow() }) { body ->
                        summaries.forEach { summary ->
                            val entry = summary.representative
                            val label = "${summary.tripCount} trip${if (summary.tripCount == 1) "" else "s"} · last ${formatDate(summary.lastStartedAt)}"
                            val detail = "${shortName(entry.originName)} → ${shortName(entry.destinationName)}"
                            addMenuAction(body, label, detail) { showRouteHistoryDetail(entry, summary.tripCount) }
                        }
                    }
                }
            }
        }
    }

    private fun showRouteHistoryDetail(entry: RouteHistoryEntry, tripCount: Int) {
        statusText.text = "Opening saved route…"
        executor.execute {
            val route = runCatching { entry.toRoute() }.getOrNull()
            postToUi {
                val origin = SearchResult(entry.originName, entry.origin)
                if (route != null && currentRoute == null) setRoutePreview(route, entry.preference, origin)
                val details = buildString {
                    append("From: ${entry.originName}\n")
                    append("To: ${entry.destinationName}\n\n")
                    append("Started: ${formatDate(entry.startedAt)}\n")
                    entry.endedAt?.let { append("Ended: ${formatDate(it)}\n") }
                    append("Route mode: ${entry.preference.label}\n")
                    append("Distance: ${distanceMiles(entry.distanceMeters)}\n")
                    append("Estimated time: ${Geo.etaText(entry.durationSeconds)}\n")
                    append("Known cameras/sensors at start: ${entry.cameraCount}\n")
                    append("Frequency: $tripCount trip${if (tripCount == 1) "" else "s"}\n")
                    append("Provider: ${entry.provider}")
                }
                showMenu("Route review", { showRoutesWorkflow() }) { body ->
                    addMenuText(body, details)
                    if (route != null) {
                        addMenuAction(body, "EXPORT DIRECTIONS PDF") { closeMenu(); exportHistoryDirections(entry, route) }
                        addMenuAction(body, "PLAN AGAIN", "Preview this saved origin/destination using current routing data.") {
                            closeMenu()
                            planRoutes(SearchResult(entry.destinationName, entry.destination), entry.preference, automatic = false, explicitOrigin = origin)
                        }
                    }
                }
            }
        }
    }

    private fun exportHistoryDirections(entry: RouteHistoryEntry, route: Route) {
        statusText.text = "Preparing saved-route PDF…"
        executor.execute {
            try {
                postToUi { statusText.text = "Rendering saved route from cached camera / sensor coverage…" }
                val routeCameras = routeCameraCandidates(listOf(route))
                val mapCameras = runCatching {
                    deviceRepository.alongRoutes(
                        listOf(route),
                        RegionalOverpassCameraProvider.ROUTE_REGION_RADIUS_METERS.toDouble(),
                        includeRegional = true
                    ).devices
                }.getOrDefault(routeCameras)
                val bytes = DirectionsExporter.buildDirectionsPdf(
                    listOf(
                        RouteExportSelection(
                            route = route,
                            originName = entry.originName,
                            origin = entry.origin,
                            preference = entry.preference,
                            cameras = routeCameras,
                            selected = true,
                            label = "Saved route",
                            mapCameras = mapCameras,
                            routedExposure = if(entry.preference==RoutePreference.FEWEST_CAMERAS) cachedRouteExposure(route) else null
                        )
                    ),
                    DirectionsExportMode.SELECTED_ROUTE,
                    File(cacheDir, "osm-tiles"),
                    assets
                )
                postToUi {
                    statusText.text = "Saved route export ready"
                    requestDocumentExport(
                        bytes,
                        "WatchMan-route-${entry.id}-${fileStamp()}.pdf",
                        "application/pdf",
                        ExportWorkflowContext(
                            artifactType = ExportArtifactType.SAVED_ROUTE_PDF,
                            origin = ExportWorkflowOrigin.SAVED_ROUTE,
                            routeState = encodeWorkflowRoute(
                                route,
                                SearchResult(entry.originName, entry.origin),
                                entry.preference
                            ),
                            savedRouteId = entry.id
                        )
                    )
                }
                scheduleExportCoverageRefresh(listOf(route))
            } catch (e: Exception) {
                postToUi { statusText.text = "Export failed"; toast(e.message ?: "Could not export saved route") }
            }
        }
    }

    private fun exportRouteHistoryCsv() {
        statusText.text = "Preparing route history CSV…"
        executor.execute {
            val entries = runCatching { historyDb.recent(1000) }.getOrDefault(emptyList())
            if (entries.isEmpty()) {
                postToUi { toast("No route history to export") }
                return@execute
            }
            val bytes = DirectionsExporter.routeHistoryCsv(entries)
            postToUi {
                requestDocumentExport(
                    bytes,
                    "WatchMan-route-history-${fileStamp()}.csv",
                    "text/csv",
                    ExportWorkflowContext(ExportArtifactType.ROUTE_HISTORY_CSV, ExportWorkflowOrigin.ROUTES)
                )
            }
        }
    }

    private fun confirmClearRouteHistory() {
        showMenu("Clear route history?", { showRoutesWorkflow() }) { body ->
            addMenuText(body, "This deletes saved route review/frequency records on this device. Camera cache and current settings are not affected.", emphasized = true)
            addMenuAction(body, "CLEAR ROUTE HISTORY", danger = true) {
                closeMenu()
                statusText.text = "Clearing route history…"
                executor.execute {
                    val cleared = runCatching { historyDb.clear() }.isSuccess
                    postToUi {
                        if (cleared) {
                            activeTripId = -1L
                            prefs.edit().remove("activeTripId").apply()
                            toast("Route history cleared")
                            showRoutesWorkflow()
                        } else {
                            statusText.text = "Route history was not cleared"
                            toast("Could not clear route history")
                            showRoutesWorkflow()
                        }
                    }
                }
            }
        }
    }

    private fun requestDocumentExport(bytes: ByteArray, name: String, mime: String, context: ExportWorkflowContext) {
        statusText.text = "Validating export…"
        executor.execute {
            try {
                val prepared = exportManager.prepare(bytes, name, mime)
                val encodedContext = context.encode()
                postToUi {
                    setPreparedExport(prepared, context, encodedContext)
                    val pending = pendingRouteRequest
                    if (pending?.stage == PendingRouteRequest.Stage.ROUTE_READY &&
                        PendingRouteActionPolicy.isExport(pending.postRouteAction)) {
                        setPendingRouteRequest(null)
                    }
                    statusText.text = "Export prepared · ${formatBytes(prepared.sizeBytes)}"
                    launchPreparedExportPicker(prepared)
                }
            } catch (e: Exception) {
                postToUi {
                    statusText.text = "Export validation failed"
                    toast(e.message ?: "Could not prepare export")
                }
            }
        }
    }

    private fun setPreparedExport(
        prepared: PreparedExportManager.PreparedExport?,
        context: ExportWorkflowContext = preparedExportContext,
        encodedContext: String? = preparedExportContextEncoded
    ) {
        preparedExport = prepared
        val edit = prefs.edit()
        if (prepared == null) {
            preparedExportContext = ExportWorkflowContext.generic()
            preparedExportContextEncoded = null
            edit.remove(PREF_EXPORT_PATH)
                .remove(PREF_EXPORT_NAME)
                .remove(PREF_EXPORT_MIME)
                .remove(PREF_EXPORT_CONTEXT)
                .apply()
        } else {
            val persistedContext = encodedContext ?: context.encode()
            preparedExportContext = context
            preparedExportContextEncoded = persistedContext
            edit.putString(PREF_EXPORT_PATH, prepared.path)
                .putString(PREF_EXPORT_NAME, prepared.fileName)
                .putString(PREF_EXPORT_MIME, prepared.mimeType)
                .putString(PREF_EXPORT_CONTEXT, persistedContext)
                .apply()
        }
    }

    private fun restorePreparedExportState(savedInstanceState: Bundle?) {
        val path = savedInstanceState?.getString(STATE_EXPORT_PATH) ?: prefs.getString(PREF_EXPORT_PATH, null)
        val name = savedInstanceState?.getString(STATE_EXPORT_NAME) ?: prefs.getString(PREF_EXPORT_NAME, null)
        val mime = savedInstanceState?.getString(STATE_EXPORT_MIME) ?: prefs.getString(PREF_EXPORT_MIME, null)
        val encodedContext = savedInstanceState?.getString(STATE_EXPORT_CONTEXT) ?: prefs.getString(PREF_EXPORT_CONTEXT, null)
        val restored = exportManager.restore(path, name, mime)
        if (restored == null) {
            setPreparedExport(null)
            return
        }
        preparedExport = restored
        preparedExportContextEncoded = encodedContext
        executor.execute {
            val decoded = ExportWorkflowContext.decode(encodedContext)
            postToUi {
                if (preparedExport == restored) {
                    preparedExportContext = decoded
                    preparedExportContextEncoded = encodedContext
                }
            }
        }
    }
    private fun showPreparedExportScreen(prepared: PreparedExportManager.PreparedExport) {
        val workflow = preparedExportContext
        showMenu("${workflow.label.replaceFirstChar { it.uppercase() }} not saved", { returnToExportOrigin(workflow) }) { body ->
            addMenuText(body, prepared.fileName, emphasized = true)
            addMenuText(
                body,
                "The validated ${workflow.label} is still available. The Android save picker was canceled or interrupted; save again, return to the originating workflow, or discard it explicitly."
            )
            addMenuAction(body, "SAVE AGAIN") { launchPreparedExportPicker(prepared) }
            addMenuAction(body, "RETURN TO WORKFLOW") { returnToExportOrigin(workflow) }
            addMenuAction(body, "DISCARD", danger = true) {
                exportManager.delete(prepared)
                setPreparedExport(null)
                returnToExportOrigin(workflow)
            }
            addMenuAction(body, "DONE") { closeMenu() }
        }
    }

    private data class RestoredPreviewWorkflow(
        val analyses: List<RouteAnalysis>,
        val recommendedIndex: Int,
        val selectedIndex: Int,
        val preference: RoutePreference,
        val origin: SearchResult
    )

    private fun decodeWorkflowRouteAsync(
        workflow: ExportWorkflowContext,
        status: String,
        onResult: (ActiveRouteState?) -> Unit
    ) {
        statusText.text = status
        executor.execute {
            val state = runCatching { ActiveRouteStateCodec.decode(workflow.routeState) }.getOrNull()
            postToUi { onResult(state) }
        }
    }

    private fun restoreWorkflowRouteStateAsync(
        state: ActiveRouteState,
        onRestored: () -> Unit = {}
    ) {
        val activeSnapshot = currentRoute
        executor.execute {
            val matchesActive = activeSnapshot != null && (
                state.route === activeSnapshot ||
                    ActiveRouteStateCodec.routeIdentity(state.route) == ActiveRouteStateCodec.routeIdentity(activeSnapshot)
                )
            postToUi {
                if (matchesActive && currentRoute === activeSnapshot) {
                    currentRouteOrigin = state.origin
                    activeRoutePreference = state.preference
                    setRoutePreference(state.preference)
                    currentStep = state.currentStep
                    persistActiveRouteState()
                } else {
                    setRoutePreview(state.route, state.preference, state.origin)
                }
                onRestored()
            }
        }
    }

    private fun decodePreviewExportOrigin(workflow: ExportWorkflowContext): RestoredPreviewWorkflow? {
        val selectedState = ActiveRouteStateCodec.decode(workflow.routeState) ?: return null
        val selectedIdentity = ActiveRouteStateCodec.routeIdentity(selectedState.route)
        val storedSelectedIdentity = workflow.selectedRouteIdentity.orEmpty()
        if (storedSelectedIdentity.isNotBlank() && storedSelectedIdentity != selectedIdentity) return null
        val decoded = workflow.previewRoutes.mapNotNull { stored ->
            ActiveRouteStateCodec.decode(stored.routeState)?.let { state ->
                state to stored
            }
        }
        if (decoded.isEmpty()) {
            return RestoredPreviewWorkflow(
                analyses = listOf(RouteAnalysis(selectedState.route, RoutePlanningPolicy.EXPOSURE_PENDING, 0.0)),
                recommendedIndex = 0,
                selectedIndex = 0,
                preference = selectedState.preference,
                origin = selectedState.origin
            )
        }
        val analyses = decoded.map { (state, stored) -> RouteAnalysis(state.route, stored.knownCameraCount, stored.scenicScore,stored.routedExposure) }
        val identities = analyses.map { ActiveRouteStateCodec.routeIdentity(it.route) }
        val selectedIndex = RouteContextPolicy.exactSingleIndex(identities, selectedIdentity) ?: return null
        val recommendedIndex = RouteContextPolicy.exactSingleIndex(identities, workflow.recommendedRouteIdentity.orEmpty())
            ?: selectedIndex
        return RestoredPreviewWorkflow(
            analyses = analyses,
            recommendedIndex = recommendedIndex,
            selectedIndex = selectedIndex,
            preference = selectedState.preference,
            origin = selectedState.origin
        )
    }

    private fun restorePreviewExportOrigin(
        workflow: ExportWorkflowContext,
        onResult: (Boolean) -> Unit = {}
    ) {
        statusText.text = "Restoring exact route preview…"
        executor.execute {
            val restored = runCatching { decodePreviewExportOrigin(workflow) }.getOrNull()
            postToUi {
                if (restored != null) {
                    showRouteAlternatives(
                        restored.analyses,
                        restored.analyses[restored.recommendedIndex],
                        restored.preference,
                        restored.origin,
                        restored.selectedIndex
                    )
                }
                onResult(restored != null)
            }
        }
    }

    private fun returnToExportOrigin(workflow: ExportWorkflowContext) {
        when (workflow.origin) {
            ExportWorkflowOrigin.PREVIEW -> {
                restorePreviewExportOrigin(workflow) { restored ->
                    if (!restored) {
                        toast("Exact route preview could not be restored")
                        showExportReportsMenu()
                    }
                }
            }
            ExportWorkflowOrigin.TURN_BY_TURN -> {
                decodeWorkflowRouteAsync(workflow, "Restoring exact turn-by-turn route…") { state ->
                    if (state == null) {
                        toast("Exact turn-by-turn state could not be restored")
                        showExportReportsMenu()
                    } else {
                        restoreWorkflowRouteStateAsync(state) {
                            showTurnByTurnDirections(state.route, state.currentStep, state.origin, state.preference)
                        }
                    }
                }
            }
            ExportWorkflowOrigin.SAVED_ROUTE -> {
                statusText.text = "Restoring saved route…"
                executor.execute {
                    val entry = workflow.savedRouteId?.let { historyDb.findById(it) }
                    val tripCount = entry?.let { runCatching { historyDb.countByRouteKey(it.routeKey) }.getOrDefault(1) } ?: 0
                    postToUi {
                        if (entry == null) {
                            toast("That saved route is no longer available")
                            showRoutesWorkflow()
                        } else {
                            showRouteHistoryDetail(entry, tripCount.coerceAtLeast(1))
                        }
                    }
                }
            }
            ExportWorkflowOrigin.ANALYTICS_PERIOD -> {
                val label = workflow.analyticsPeriodLabel
                if (label.isNullOrBlank()) showAnalyticsPeriodMenu()
                else openAnalyticsSummary(workflow.analyticsSinceMillis, label)
            }
            ExportWorkflowOrigin.DIRECTIONS_MENU -> {
                decodeWorkflowRouteAsync(workflow, "Restoring exact directions route…") { state ->
                    if (state == null) {
                        if (currentRoute != null || previewRoute != null) showDirectionsExportMenu()
                        else showExportReportsMenu()
                    } else {
                        restoreWorkflowRouteStateAsync(state) { showDirectionsExportMenu() }
                    }
                }
            }
            ExportWorkflowOrigin.ROUTES -> showRoutesWorkflow()
            ExportWorkflowOrigin.EXPORTS -> showExportReportsMenu()
        }
    }

    private fun launchPreparedExportPicker(prepared: PreparedExportManager.PreparedExport) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = prepared.mimeType
            putExtra(Intent.EXTRA_TITLE, prepared.fileName)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_EXPORT_DOCUMENT)
        } catch (_: Exception) {
            toast("No document picker is available")
        }
    }

    @Deprecated("Deprecated in platform; retained for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_DOCUMENT) return
        val prepared = preparedExport ?: exportManager.restore(
            prefs.getString(PREF_EXPORT_PATH, null),
            prefs.getString(PREF_EXPORT_NAME, null),
            prefs.getString(PREF_EXPORT_MIME, null)
        )
        val workflow = preparedExportContext
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null || prepared == null) {
            statusText.text = "Export not saved"
            prepared?.let { postToUi { showPreparedExportScreen(it) } }
                ?: toast("Export canceled")
            return
        }
        statusText.text = "Saving validated export…"
        executor.execute {
            try {
                val bytesWritten = exportManager.saveToUri(prepared, contentResolver, uri)
                exportManager.delete(prepared)
                postToUi {
                    setPreparedExport(null)
                    statusText.text = "Export saved · ${formatBytes(bytesWritten)}"
                    showMenu("Export saved", { returnToExportOrigin(workflow) }) { body ->
                        addMenuText(body, prepared.fileName, emphasized = true)
                        addMenuText(body, "Saved and re-opened successfully · ${formatBytes(bytesWritten)}")
                        addMenuAction(body, "OPEN") { openSavedExport(uri, prepared.mimeType) }
                        addMenuAction(body, "SHARE") {
                            shareSavedExport(uri, prepared.mimeType, prepared.fileName)
                        }
                        addMenuAction(body, "DONE") { returnToExportOrigin(workflow) }
                    }
                }
            } catch (e: Exception) {
                postToUi {
                    statusText.text = "Export save failed"
                    toast(e.message ?: "Could not save export")
                    showPreparedExportScreen(prepared)
                }
            }
        }
    }
    private fun openSavedExport(uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            toast("No app is available to open this export")
        }
    }

    private fun shareSavedExport(uri: Uri, mime: String, name: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Share WatchMan export"))
        } catch (_: Exception) {
            toast("No app is available to share this export")
        }
    }


    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun fileStamp(): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    private fun formatDate(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
    private fun shortName(value: String): String = if (value.length <= 34) value else value.take(31) + "…"

    private fun showSettings() {
        showMenu("Settings", { showToolsMenu() }) { box ->
            fun heading(textValue: String) = TextView(this).apply {
                text = textValue
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(2), dp(12), dp(2), dp(4))
            }

            box.addView(heading("Appearance"))
            val appearance = Spinner(this)
            val appearanceLabels = arrayOf("Dark mode", "Light mode")
            appearance.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, appearanceLabels)
            appearance.setSelection(if (UserPreferences.isLight(prefs)) 1 else 0)
            box.addView(appearance, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))

            box.addView(heading("Alert distance (feet)"))
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                textSize = 18f
                setText(prefs.getInt("alertFeet", 2000).toString())
                minHeight = dp(50)
            }
            box.addView(input)
            val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, 0) }
            listOf(500, 1000, 2000).forEach { feet ->
                val button = largeButton(if (feet >= 1000) "${feet / 1000}K FT" else "$feet FT")
                button.setOnClickListener { input.setText(feet.toString()); input.selectAll() }
                presets.addView(button, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            }
            box.addView(presets)

            box.addView(heading("Alert / voice volume"))
            val volumeLabel = TextView(this).apply { textSize = 14f }
            val volume = SeekBar(this).apply { max = 100; progress = prefs.getInt("alertVolume", 85).coerceIn(0, 100) }
            volumeLabel.text = "${volume.progress}%"
            volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { volumeLabel.text = "$progress%" }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            box.addView(volumeLabel); box.addView(volume)

            val voice = CheckBox(this).apply {
                text = "Voice navigation + camera alerts"
                textSize = 14f
                isChecked = voiceEnabled()
                minHeight = dp(44)
            }
            box.addView(voice)
            val voiceStatus = TextView(this).apply {
                text = prefs.getString("britishVoiceStatus", "British English voice initializing")
                textSize = 14f
            }
            box.addView(voiceStatus)
            val voiceStatusListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "britishVoiceStatus") voiceStatus.text = prefs.getString(key, "")
            }
            prefs.registerOnSharedPreferenceChangeListener(voiceStatusListener)
            voiceStatus.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { prefs.registerOnSharedPreferenceChangeListener(voiceStatusListener) }
                override fun onViewDetachedFromWindow(v: View) { prefs.unregisterOnSharedPreferenceChangeListener(voiceStatusListener) }
            })
            addMenuAction(box, "Test Voice") {
                if (!voiceEnabled()) toast("Enable voice and save settings first")
                else { initTtsSafely(); WatchManSpeech.testVoice() }
            }
            addMenuAction(box, "Android voice settings / install British English") {
                runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    .onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
            }
            addMenuAction(box, "Speech diagnostics") {
                showMenu("British voice delivery", { showSettings() }) { body ->
                    addMenuText(body, prefs.getString("speechDiagnostics", null) ?: "No speech requests recorded yet.")
                }
            }

            val proximityTone = CheckBox(this).apply {
                text = "Distance-responsive camera / sensor proximity tones"
                textSize = 14f
                isChecked = UserPreferences.proximityToneEnabled(prefs)
                minHeight = dp(44)
            }
            box.addView(proximityTone)

            box.addView(heading("Proximity tone distances (feet)"))
            addMenuText(
                box,
                "Slow warning begins at Proximity start, becomes rapid at Visual range, and becomes continuous only inside supported directional/FOV logic or the close-range fallback. Required order: fallback ≤ visual ≤ start ≤ alert distance."
            )
            fun proximityDistanceField(label: String, value: Int): EditText {
                box.addView(TextView(this).apply { text = label; textSize = 13f; setPadding(dp(2), dp(7), dp(2), dp(2)) })
                return EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    textSize = 17f
                    setText(value.toString())
                    minHeight = dp(48)
                    box.addView(this)
                }
            }
            val proximityStartInput = proximityDistanceField(
                "Proximity start (slow tone)",
                prefs.getInt("proximityStartFeet", UserPreferences.DEFAULT_PROXIMITY_START_FEET)
            )
            val proximityVisualInput = proximityDistanceField(
                "Visual range (rapid tone)",
                prefs.getInt("proximityVisualFeet", UserPreferences.DEFAULT_VISUAL_RANGE_FEET)
            )
            val proximityFovInput = proximityDistanceField(
                "FOV / continuous fallback",
                prefs.getInt("proximityFovFallbackFeet", UserPreferences.DEFAULT_FOV_FALLBACK_FEET)
            )
            box.addView(heading("Proximity tone volume"))
            val proximityVolumeLabel = TextView(this).apply { textSize = 14f }
            val proximityVolume = SeekBar(this).apply {
                max = 100
                progress = UserPreferences.proximityVolumePercent(prefs)
            }
            proximityVolumeLabel.text = "${proximityVolume.progress}%"
            proximityVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    proximityVolumeLabel.text = "$progress%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            box.addView(proximityVolumeLabel)
            box.addView(proximityVolume)

            val vibrate = CheckBox(this).apply {
                text = "Vibrate for camera / sensor alerts"
                textSize = 14f
                isChecked = UserPreferences.vibrationEnabled(prefs)
                minHeight = dp(44)
            }
            box.addView(vibrate)
            box.addView(heading("Vibration pattern"))
            val vibrationPatterns = listOf(
                "short" to "Short pulse",
                "double" to "Double pulse",
                "triple" to "Triple pulse",
                "long" to "Long pulse"
            )
            val vibeSpinner = Spinner(this)
            vibeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, vibrationPatterns.map { it.second })
            val currentPattern = UserPreferences.vibrationPatternName(prefs)
            vibeSpinner.setSelection(vibrationPatterns.indexOfFirst { it.first == currentPattern }.coerceAtLeast(0))
            box.addView(vibeSpinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))

            box.addView(heading("Speed units"))
            val speedUnits = Spinner(this)
            speedUnits.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("MPH", "KM/H"))
            speedUnits.setSelection(if (prefs.getBoolean("speedMetric", false)) 1 else 0)
            box.addView(speedUnits, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))

            val keepScreen = CheckBox(this).apply {
                text = "Keep screen awake while WatchMan is open"
                textSize = 14f
                isChecked = prefs.getBoolean("keepScreenOn", true)
                minHeight = dp(44)
            }
            box.addView(keepScreen)

            addMenuAction(box, "CAMERA PROVIDER SETTINGS") { showProviderSettings() }
            addMenuAction(box, "SAVE SETTINGS") {
                val feet = input.text.toString().toIntOrNull()
                if (feet == null || feet !in 100..20000) {
                    input.error = "Use 100–20,000 ft"
                    return@addMenuAction
                }
                val proximityStartFeet = proximityStartInput.text.toString().toIntOrNull()
                val proximityVisualFeet = proximityVisualInput.text.toString().toIntOrNull()
                val proximityFovFeet = proximityFovInput.text.toString().toIntOrNull()
                if (proximityStartFeet == null || proximityStartFeet !in 10..feet) {
                    proximityStartInput.error = "Use 10–$feet ft"
                    return@addMenuAction
                }
                if (proximityVisualFeet == null || proximityVisualFeet !in 10..proximityStartFeet) {
                    proximityVisualInput.error = "Use 10–$proximityStartFeet ft"
                    return@addMenuAction
                }
                if (proximityFovFeet == null || proximityFovFeet !in 10..proximityVisualFeet) {
                    proximityFovInput.error = "Use 10–$proximityVisualFeet ft"
                    return@addMenuAction
                }
                val oldAppearance = prefs.getString("appearance", UserPreferences.THEME_DARK)
                val newAppearance = if (appearance.selectedItemPosition == 1) UserPreferences.THEME_LIGHT else UserPreferences.THEME_DARK
                val pattern = vibrationPatterns[vibeSpinner.selectedItemPosition.coerceIn(0, vibrationPatterns.lastIndex)].first
                prefs.edit()
                    .putInt("alertFeet", feet)
                    .putInt("alertVolume", volume.progress)
                    .putBoolean("voice", voice.isChecked)
                    .putBoolean("proximityToneEnabled", proximityTone.isChecked)
                    .putInt("proximityStartFeet", proximityStartFeet)
                    .putInt("proximityVisualFeet", proximityVisualFeet)
                    .putInt("proximityFovFallbackFeet", proximityFovFeet)
                    .putInt("proximityVolume", proximityVolume.progress)
                    .putBoolean("vibrationEnabled", vibrate.isChecked)
                    .putString("vibrationPattern", pattern)
                    .putString("appearance", newAppearance)
                    .putBoolean("speedMetric", speedUnits.selectedItemPosition == 1)
                    .putBoolean("keepScreenOn", keepScreen.isChecked)
                    .apply()
                map.setAlertDistanceFeet(feet)
                alertEngine.reset()
                updateCameraStatusText()
                currentLocation?.let { updateSpeedHud(it) }
                if (keepScreen.isChecked) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                initTtsSafely()
                WatchManSpeech.refresh()
                currentRoute?.let { runCatching { NavigationForegroundService.start(this, it) } }
                closeMenu()
                if (oldAppearance != newAppearance) recreate() else applySystemAppearance()
                toast("Settings saved")
            }
        }
    }

    private fun restoreNavigationIfNeeded() {
        if (restoreAttempted || restoreInFlight || currentRoute != null) return
        if (!prefs.getBoolean("activeNav", false)) {
            restoreAttempted = true
            return
        }

        if (!restoreDecodeAttempted) {
            restoreDecodeAttempted = true
            restoreInFlight = true
            val token = navigationActivation.begin()
            val rawState = prefs.getString(PREF_ACTIVE_ROUTE_STATE, null)
            val storedStep = prefs.getInt(PREF_ACTIVE_ROUTE_STEP, 0)
            executor.execute {
                val restored = ActiveRouteStateCodec.decode(rawState)
                postToUi {
                    restoreInFlight = false
                    if (!navigationActivation.isCurrent(token) || !prefs.getBoolean("activeNav", false)) {
                        return@postToUi
                    }
                    if (restored != null) {
                        restoreAttempted = true
                        val step = storedStep.coerceIn(0, restored.route.steps.lastIndex.coerceAtLeast(0))
                        commitPreparedRoute(
                            route = restored.route,
                            preference = restored.preference,
                            origin = restored.origin,
                            tripId = activeTripId,
                            restoredStep = step
                        )
                    } else {
                        restoreNavigationIfNeeded()
                    }
                }
            }
            return
        }

        if (currentLocation == null) return
        restoreAttempted = true
        val lat = prefs.getString("activeDestLat", null)?.toDoubleOrNull() ?: return
        val lon = prefs.getString("activeDestLon", null)?.toDoubleOrNull() ?: return
        val name = prefs.getString("activeDestName", null) ?: "Saved destination"
        planRoutes(SearchResult(name, GeoPoint(lat, lon)), currentRoutePreference(), automatic = true)
    }

    private fun stopNavigation() {
        routePlanning.cancel()
        routePlanningInFlight = false
        navigationActivation.cancel()
        val tripToFinish = activeTripId
        activeTripId = -1L
        if (tripToFinish > 0L) executor.execute { runCatching { historyDb.finishTrip(tripToFinish) } }
        currentRoute = null
        currentRouteOrigin = null
        activeRoutePreference = null
        previewRoute = null
        previewRouteOrigin = null
        previewRoutePreference = null
        NavigationForegroundService.stop(this)
        currentStep = 0
        spokenStep = -1
        spokenApproachStep = -1
        routeEditorOpen = false
        alertEngine.reset()
        ProximityEncounterSession.reset()
        lastProximityStateSignature = null
        map.setRoute(null)
        prefs.edit()
            .putBoolean("activeNav", false)
            .remove("activeDestLat")
            .remove("activeDestLon")
            .remove("activeDestName")
            .remove("activeOriginLat")
            .remove("activeOriginLon")
            .remove("activeOriginName")
            .remove("activeTripId")
            .remove(PREF_ACTIVE_ROUTE_STATE)
            .remove(PREF_ACTIVE_ROUTE_STEP)
            .remove(ProximityEncounterSession.PREF_STATE_KEY)
            .apply()
        if (fullScreenNavigation) setFullScreenNavigation(false) else applyNavigationLayout()
        currentLocation?.let { updateNavigationUi(it) }
    }

    private fun onHeading(heading: Float) {
        if (!::compassImage.isInitialized) return
        compassImage.rotation = -heading
        compassText.text = "${heading.roundToInt()}°"
    }

    private fun updateSpeedHud(location: Location) {
        if (!::speedText.isInitialized) return
        val metersPerSecond = if (location.hasSpeed()) location.speed.coerceAtLeast(0f) else 0f
        val metric = prefs.getBoolean("speedMetric", false)
        val speed = if (metric) metersPerSecond * 3.6f else metersPerSecond * 2.2369363f
        setTextIfChanged(speedText, "${speed.roundToInt()}\n${if (metric) "KM/H" else "MPH"}")
    }

    private fun showProviderSettings() {
        showMenu("Public camera providers", { showToolsMenu() }) { box ->
            addMenuText(
                box,
                "Direct OpenStreetMap/Overpass compatibility feeds and applicable public transport feeds remain enabled without keys. These fields add optional regional/custom feeds."
            )
            fun field(hintText: String, key: String, multi: Boolean = false): EditText = EditText(this).apply {
                hint = hintText
                setText(prefs.getString(key, ""))
                inputType = InputType.TYPE_CLASS_TEXT
                if (multi) { minLines = 3; maxLines = 6; setSingleLine(false) } else setSingleLine(true)
                box.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (multi) dp(105) else dp(52)).apply { topMargin = dp(4) })
            }
            val ny = field("511NY API key (optional)", "camera_key_511ny")
            val ga = field("511GA API key (optional)", "camera_key_511ga")
            val wa = field("WSDOT access code (optional)", "camera_key_wsdot")
            val custom = field("Custom HTTPS JSON/GeoJSON/KML feeds, one URL per line", "custom_camera_feeds", true)
            addMenuAction(box, "SAVE PROVIDERS") {
                prefs.edit().putString("camera_key_511ny", ny.text.toString().trim())
                    .putString("camera_key_511ga", ga.text.toString().trim())
                    .putString("camera_key_wsdot", wa.text.toString().trim())
                    .putString("custom_camera_feeds", custom.text.toString().trim()).apply()
                closeMenu()
                refreshCameras(force = true)
                toast("Camera provider settings saved")
            }
        }
    }

    private fun currentRoutePreference(): RoutePreference {
        val raw = prefs.getString("routePreference", RoutePreference.FASTEST.name) ?: RoutePreference.FASTEST.name
        return runCatching { RoutePreference.valueOf(raw) }.getOrDefault(RoutePreference.FASTEST)
    }

    private fun setRoutePreference(preference: RoutePreference) {
        prefs.edit().putString("routePreference", preference.name).apply()
        if (::routeModeButton.isInitialized) routeModeButton.text = routeModeLabel()
    }

    private fun routeModeLabel(): String = "ROUTE: ${currentRoutePreference().label.uppercase(Locale.US)}"

    private fun voiceEnabled(): Boolean = prefs.getBoolean("voice", true)

    private fun alertDistanceLabel(): String = "ALERT ${alertDistanceShort()}"

    private fun alertDistanceShort(): String {
        val feet = prefs.getInt("alertFeet", 2000)
        return if (feet >= 1000 && feet % 1000 == 0) "${feet / 1000}K FT" else "$feet FT"
    }

    private fun distanceMiles(meters: Double): String = String.format(Locale.US, "%.1f mi", meters * Geo.FEET_PER_METER / 5280.0)

    private fun initTtsSafely() {
        WatchManSpeech.acquire(this, speechOwner)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("launchPresentationStage", launchPresentation.stage.name)
        outState.putLong("launchPresentationRemaining", launchPresentation.remainingMillis(SystemClock.elapsedRealtime()))
        preparedExport?.let {
            outState.putString(STATE_EXPORT_PATH, it.path)
            outState.putString(STATE_EXPORT_NAME, it.fileName)
            outState.putString(STATE_EXPORT_MIME, it.mimeType)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION) return

        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            locationController.start()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Location required")
                .setMessage("Location is optional for typed Start → Destination planning, national cache setup and exports. Enable it for live navigation, map follow mode, current-position routing and proximity alerts.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    @Deprecated("Deprecated in platform; retained for API 26 compatibility")
    override fun onBackPressed() {
        if (::menuHost.isInitialized && menuHost.visibility == View.VISIBLE) {
            closeMenu()
            return
        }
        if (previewRoute != null) {
            clearRoutePreview()
            return
        }
        super.onBackPressed()
    }

    override fun onTrimMemory(level: Int) {
        if (::acquisitionHeadroomGuard.isInitialized) acquisitionHeadroomGuard.noteTrimMemory(level)
        if (::map.isInitialized) map.trimMemory(level)
        super.onTrimMemory(level)
    }

    override fun onResume() {
        super.onResume()
        NavigationForegroundService.setActivityVisible(true)
        ProximityAudioOwnership.transferTo(ProximityAudioOwnership.Owner.ACTIVITY)
        if (::headingController.isInitialized) headingController.start()
        activityResumed = true
        WatchManSpeech.refresh()
        if (::locationController.isInitialized && locationController.hasPermission()) locationController.start()
        if (::firstRunHost.isInitialized && firstRunHost.visibility == View.VISIBLE) {
            if (::firstRunIntroBox.isInitialized && firstRunIntroBox.visibility == View.VISIBLE) {
                scheduleFirstRunIntroTransition()
            } else if (::firstRunSetupBox.isInitialized && firstRunSetupBox.visibility == View.VISIBLE) {
                startFirstRunBreathingGlow()
                if (::firstRunBuffer.isInitialized) firstRunBuffer.start()
            }
        }
        currentRoute?.let { NavigationForegroundService.activeRoute = it }
        if (fullScreenNavigation && currentRoute != null) setFullScreenNavigation(true)
        showNextAcquisitionCompletionNotice()
    }

    override fun onPause() {
        launchPresentation.pause(SystemClock.elapsedRealtime())
        NavigationForegroundService.setActivityVisible(false)
        if (currentRoute != null) ProximityAudioOwnership.transferTo(ProximityAudioOwnership.Owner.SERVICE)
        else ProximityAudioOwnership.release(ProximityAudioOwnership.Owner.ACTIVITY)
        if (::headingController.isInitialized) headingController.stop()
        activityResumed = false
        suspendAcquisitionCompletionNotice()
        proximityTonePlayer.stop()
        if (::locationController.isInitialized) locationController.stop()
        if (::firstRunBuffer.isInitialized) firstRunBuffer.stop()
        stopFirstRunBreathingGlow()
        stopFirstRunIntroBreathing()
        super.onPause()
    }

    override fun onDestroy() {
        activityDestroyed = true
        if (::deviceLayerWorker.isInitialized) deviceLayerWorker.close()
        if (::nationalSourceSummaryWorker.isInitialized) nationalSourceSummaryWorker.close()
        if (::directionsDeviceWorker.isInitialized) directionsDeviceWorker.close()
        if (::routeExposureWorker.isInitialized) routeExposureWorker.close()
        navigationActivation.cancel()
        routePlanning.cancel()
        routePlanningInFlight = false
        restoreInFlight = false
        NavigationForegroundService.setActivityVisible(false)
        main.removeCallbacksAndMessages(null)
        if (::headingController.isInitialized) headingController.stop()
        nationalBootstrapStopRequested = true
        acquisitionPriority.signalStateChanged()
        routeCameraCoverageProvider.close()
        nationalCameraProvider.close()
        ExecutorShutdown.shutdownAll(
            listOf(executor, nationalExecutor, parallelWorkerExecutor),
            EXECUTOR_SHUTDOWN_WAIT_MS
        )
        ProximityAudioOwnership.unregister(ProximityAudioOwnership.Owner.ACTIVITY)
        proximityTonePlayer.release()
        WatchManSpeech.release(speechOwner)
        if (::firstRunBuffer.isInitialized) firstRunBuffer.stop()
        stopFirstRunBreathingGlow()
        stopFirstRunIntroBreathing()
        if (::db.isInitialized) try { db.close() } catch (_: Exception) { }
        if (::historyDb.isInitialized) try { historyDb.close() } catch (_: Exception) { }
        if (::regionalCameraCache.isInitialized) try { regionalCameraCache.close() } catch (_: Exception) { }
        if (::nationalCameraCache.isInitialized) try { nationalCameraCache.close() } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun applySystemAppearance() {
        val light = UserPreferences.isLight(prefs)
        window.statusBarColor = if (light) Color.rgb(242, 242, 238) else Color.rgb(18, 20, 24)
        window.navigationBarColor = if (light) Color.rgb(242, 242, 238) else Color.rgb(18, 20, 24)
        @Suppress("DEPRECATION")
        run {
            val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = if (light) lightFlags else 0
        }
    }

    private fun largeButton(label: String, danger: Boolean = false): Button = Button(this).apply {
        text = label
        textSize = 15f
        val light = ::prefs.isInitialized && UserPreferences.isLight(prefs)
        setTextColor(if (danger) Color.WHITE else if (light) Color.rgb(28, 30, 34) else Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        isAllCaps = false
        minHeight = 0
        minWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
        background = rounded(
            if (danger) Color.rgb(126, 36, 40) else if (light) Color.rgb(221, 217, 205) else Color.rgb(48, 58, 72),
            16f
        )
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpF(radiusDp)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun dpF(value: Float): Float = value * resources.displayMetrics.density
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_LOCATION = 1001
        private const val REQUEST_EXPORT_DOCUMENT = 1002
        private const val PREF_FIRST_RUN_DECISION = "v026_first_run_decision"
        private const val PREF_FIRST_RUN_ACK = "v026_first_run_ack"
        private const val PREF_NATIONAL_BOOTSTRAP_COMPLETE = "v026_national_bootstrap_complete"
        private const val FIRST_RUN_DECISION_SETUP = "setup"
        private const val FIRST_RUN_DECISION_NAV = "nav"
        private const val PREF_EXPORT_PATH = "pending_export_path"
        private const val PREF_EXPORT_NAME = "pending_export_name"
        private const val PREF_EXPORT_MIME = "pending_export_mime"
        private const val PREF_EXPORT_CONTEXT = "pending_export_context_v030"
        private const val PREF_PENDING_ROUTE_ACTION = "pending_route_action_v030"
        private const val EXECUTOR_SHUTDOWN_WAIT_MS = 750L
        private const val PREF_ACTIVE_ROUTE_STATE = "active_route_state_v1"
        private const val PREF_ACTIVE_ROUTE_STEP = "active_route_step_v1"
        private const val STATE_EXPORT_PATH = "state_export_path"
        private const val STATE_EXPORT_NAME = "state_export_name"
        private const val STATE_EXPORT_MIME = "state_export_mime"
        private const val STATE_EXPORT_CONTEXT = "state_export_context_v030"
        private const val CURRENT_GPS_LABEL = "Current GPS position"
        private const val LIVE_START_TOLERANCE_METERS = 1000.0
        private const val ROUTE_ALTERNATIVE_REQUEST_COUNT = 3
        private const val ROUTE_CAMERA_CORRIDOR_METERS = 180.0
        private const val DIRECTIONS_MAP_CAMERA_RADIUS_METERS = 5_000.0
        private const val DIRECTIONS_MAX_CAMERAS_PER_STEP = 4
        private const val DIRECTIONS_STEPS_PER_PAGE = 60
        private const val REROUTE_MIN_INTERVAL_MS = 12_000L
        private const val COMPLETION_NOTICE_VISIBLE_MS = 7_600L
        private const val COMPLETION_NOTICE_GAP_MS = 400L

        private const val CAMERA_QUERY_RADIUS_METERS = 20_000
        private const val GPS_STATE_LOOKUP_INTERVAL_MS = 5 * 60_000L
        private const val GPS_STATE_LOOKUP_DISTANCE_METERS = 25_000.0
        private const val CAMERA_CACHE_RADIUS_METERS = 25_000.0
        private const val CAMERA_ACTIVE_REFRESH_MS = 5_000L
        private const val CAMERA_IDLE_REFRESH_MS = 20_000L
        private const val CAMERA_FAILURE_RETRY_MS = 2_000L
        private const val CAMERA_ACTIVE_MOVE_TRIGGER_M = 75.0
        private const val CAMERA_IDLE_MOVE_TRIGGER_M = 300.0
        private const val CAMERA_INFLIGHT_MOVE_TRIGGER_M = 50.0
        private const val PROXIMITY_STATE_PERSIST_INTERVAL_MS = 5_000L
    }
}
