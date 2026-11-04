package com.bggg.watchman.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.ProviderConfig
import com.bggg.watchman.data.Route
import com.bggg.watchman.net.HttpClient
import com.bggg.watchman.util.Geo
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh

/** Native canvas/slippy-map renderer. Defaults to a close follow view; never opens at continent scale. */
class TileMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val tileSize = 256.0
    private val tileRoot = File(context.cacheDir, "osm-tiles")
    private val offlineRoads = OfflineRoadStore(context) { postInvalidate() }
    private val offlineRoadOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 18, 20, 24)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val offlineRoadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(153, 158, 166)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val cameraIcons = HashMap<CameraType, Bitmap?>()

    private var center: GeoPoint? = null
    private var zoom = DEFAULT_ZOOM
    private var follow = true
    private var route: Route? = null
    private var displayRoutePoints: List<GeoPoint> = emptyList()
    private var cameras: List<CameraPoi> = emptyList()
    private var current: GeoPoint? = null
    private var currentBearing: Float? = null
    private var accuracyMeters = 0f
    private var destination: GeoPoint? = null
    private var routeOrigin: GeoPoint? = null
    private var viewportTopInsetPx = 0
    private var viewportBottomInsetPx = 0
    private var alertFeet = 2000

    var onCameraTap: ((CameraPoi) -> Unit)? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(18f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val routeContextOverlayPaint = Paint().apply {
        color = Color.argb(42, 242, 244, 240)
        style = Paint.Style.FILL
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 132, 255)
        strokeWidth = dp(9f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routeOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 8, 10, 14)
        strokeWidth = dp(15f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cameraGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val cameraLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(10.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val cameraLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 12, 14, 18)
        style = Paint.Style.FILL
    }
    private val radiusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 150, 40)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 245, 245, 245)
        textSize = dp(10f)
        setShadowLayer(dp(2f), 0f, dp(1f), Color.BLACK)
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                when {
                    detector.scaleFactor > 1.10f && zoom < MAX_ZOOM -> zoom++
                    detector.scaleFactor < 0.90f && zoom > MIN_INTERACTIVE_ZOOM -> zoom--
                    else -> return true
                }
                follow = false
                invalidate()
                return true
            }
        }
    )

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f

    init {
        setBackgroundColor(Color.rgb(25, 27, 31))
        isClickable = true
        isFocusable = true
        if (TILE_TRIM_STARTED.compareAndSet(false, true)) {
            SHARED_TILE_EXECUTOR.execute { trimTileDiskCache() }
        }
    }

    fun setLocation(point: GeoPoint, accuracy: Float, bearing: Float? = null) {
        current = point
        currentBearing = bearing
        accuracyMeters = accuracy
        if (follow || center == null) center = point
        invalidate()
    }

    fun recenter() {
        current?.let {
            center = it
            follow = true
            routeOrigin = null
            if (zoom < DEFAULT_ZOOM) zoom = DEFAULT_ZOOM
            invalidate()
        }
    }

    fun focusOn(point: GeoPoint, zoomLevel: Int = DEFAULT_ZOOM) {
        center = point
        zoom = zoomLevel.coerceIn(MIN_INTERACTIVE_ZOOM, MAX_ZOOM)
        follow = false
        invalidate()
    }


    fun setViewportInsets(topPx: Int, bottomPx: Int) {
        viewportTopInsetPx = topPx.coerceAtLeast(0)
        viewportBottomInsetPx = bottomPx.coerceAtLeast(0)
    }

    fun refitRoutePreview() {
        val value = route ?: return
        val origin = routeOrigin ?: value.points.firstOrNull() ?: return
        fitRoute(value, origin)
    }
    fun setRoute(value: Route?) {
        route = value
        displayRoutePoints = selectEvenly(value?.points.orEmpty(), MAX_RENDER_ROUTE_POINTS)
        destination = value?.destination?.point
        routeOrigin = null
        invalidate()
    }

    fun showRoutePreview(value: Route, origin: GeoPoint) {
        route = value
        displayRoutePoints = selectEvenly(value.points, MAX_RENDER_ROUTE_POINTS)
        destination = value.destination.point
        routeOrigin = origin
        follow = false
        if (width > 0 && height > 0) {
            fitRoute(value, origin)
        } else {
            center = origin
            zoom = DEFAULT_ZOOM
            post { fitRoute(value, origin) }
        }
        invalidate()
    }

    private fun fitRoute(value: Route, origin: GeoPoint) {
        val points = LinkedHashSet<GeoPoint>().apply {
            add(origin)
            addAll(displayRoutePoints)
            selectEvenly(value.steps, MAX_FIT_MANEUVER_POINTS).forEach { add(it.maneuver) }
            add(value.destination.point)
        }.toList()
        if (points.size < 2 || width <= 0 || height <= 0) {
            center = origin
            zoom = DEFAULT_ZOOM
            invalidate()
            return
        }

        val horizontalPadding = dp(28f).toDouble()
        val topInset = viewportTopInsetPx.coerceAtMost((height * 0.42f).toInt()).toDouble()
        val bottomInset = viewportBottomInsetPx.coerceAtMost((height * 0.48f).toInt()).toDouble()
        val fit = RouteViewport.fit(
            points = points,
            viewportWidthPx = width.toDouble(),
            viewportHeightPx = height.toDouble(),
            horizontalPaddingPx = horizontalPadding,
            topInsetPx = topInset,
            bottomInsetPx = bottomInset,
            minZoom = MIN_OVERVIEW_ZOOM,
            maxZoom = MAX_ZOOM,
            tileSizePx = tileSize.toDouble()
        ) ?: RouteViewport.Fit(origin, DEFAULT_ZOOM)
        zoom = fit.zoom
        center = fit.center
        follow = false
        invalidate()
    }

    fun setCameras(value: List<CameraPoi>) {
        cameras = selectEvenly(value, MAX_RENDER_CAMERAS)
        invalidate()
    }

    fun setAlertDistanceFeet(value: Int) {
        alertFeet = value.coerceIn(100, 20000)
        invalidate()
    }

    fun setFollowEnabled(enabled: Boolean) {
        follow = enabled
        if (enabled) recenter()
    }

    fun currentZoom(): Int = zoom

    /** Releases recoverable decoded map data while preserving disk tiles and bundled road assets. */
    fun trimMemory(level: Int) {
        val budget = MapMemoryPolicy.budgetForTrimLevel(level) ?: return
        SHARED_TILE_MEMORY.trimToSize(budget.tileBytes)
        OfflineRoadStore.trimSharedCache(budget.offlineRoadPoints)
        if (budget.releasePerViewIcons) cameraIcons.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = center
        if (c == null) {
            canvas.drawColor(Color.rgb(25, 27, 31))
            val msg = "Waiting for your GPS location…"
            val width = textPaint.measureText(msg)
            canvas.drawText(msg, (this.width - width) / 2f, height / 2f, textPaint)
            return
        }

        val rasterComplete = drawTiles(canvas, c)
        if (!rasterComplete) drawOfflineRoads(canvas, c)
        if (route != null) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), routeContextOverlayPaint)
        drawAlertRadius(canvas, c)
        drawCameras(canvas, c)
        drawRoute(canvas, c)
        drawRouteOrigin(canvas, c)
        drawDestination(canvas, c)
        drawCurrent(canvas, c)
        canvas.drawText("© OpenStreetMap contributors", dp(8f), height - dp(245f), attributionPaint)
    }

    private fun drawTiles(canvas: Canvas, c: GeoPoint): Boolean {
        var complete = true
        val centerPx = worldPixels(c, zoom)
        val left = centerPx.first - width / 2.0
        val top = centerPx.second - height / 2.0
        val minX = floor(left / tileSize).toInt() - 1
        val maxX = floor((left + width) / tileSize).toInt() + 1
        val minY = floor(top / tileSize).toInt() - 1
        val maxY = floor((top + height) / tileSize).toInt() + 1
        val n = 1 shl zoom

        for (txRaw in minX..maxX) {
            for (ty in minY..maxY) {
                if (ty !in 0 until n) continue
                val tx = ((txRaw % n) + n) % n
                val sx = (txRaw * tileSize - left).toFloat()
                val sy = (ty * tileSize - top).toFloat()
                val key = "$zoom/$tx/$ty"
                val bitmap = tile(key, zoom, tx, ty)
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, sx, sy, markerPaint)
                } else {
                    complete = false
                    markerPaint.style = Paint.Style.FILL
                    markerPaint.color = if ((txRaw + ty) % 2 == 0) {
                        Color.rgb(41, 44, 49)
                    } else {
                        Color.rgb(47, 50, 55)
                    }
                    canvas.drawRect(sx, sy, sx + 256f, sy + 256f, markerPaint)
                }
            }
        }
        return complete
    }

    private fun drawOfflineRoads(canvas: Canvas, c: GeoPoint) {
        offlineRoads.roadsAround(c).forEach { road ->
            val path = Path()
            var visible = false
            road.points.forEachIndexed { index, point ->
                val screen = screenPoint(point, c)
                if (screen.x in -100f..(width + 100f) && screen.y in -100f..(height + 100f)) visible = true
                if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
            }
            if (!visible) return@forEach
            val roadWidth = when (road.rank) {
                1 -> dp(5.5f)
                2 -> dp(4.5f)
                3 -> dp(3.6f)
                else -> dp(2.8f)
            }
            offlineRoadOutlinePaint.strokeWidth = roadWidth + dp(2.4f)
            offlineRoadPaint.strokeWidth = roadWidth
            offlineRoadPaint.color = when (road.rank) {
                1 -> Color.rgb(235, 174, 78)
                2 -> Color.rgb(205, 185, 115)
                else -> Color.rgb(155, 160, 169)
            }
            canvas.drawPath(path, offlineRoadOutlinePaint)
            canvas.drawPath(path, offlineRoadPaint)
        }
        }

    private fun tile(key: String, z: Int, x: Int, y: Int): Bitmap? {
        SHARED_TILE_MEMORY.get(key)?.let { return it }

        val file = File(tileRoot, "$z/$x/$y.png")
        if (file.isFile) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                SHARED_TILE_MEMORY.put(key, bitmap)
                return bitmap
            }
            file.delete()
        }

        watchTile(key)
        if (SHARED_TILE_PENDING.add(key)) {
            SHARED_TILE_EXECUTOR.execute {
                try {
                    file.parentFile?.mkdirs()
                    val bytes = HttpClient.getBytes(
                        "${ProviderConfig.OSM_TILE_BASE}/$z/$x/$y.png",
                        timeoutMs = TILE_WORKFLOW_TIMEOUT_MS,
                        maxBytes = MAX_TILE_BYTES,
                        accept = "image/png"
                    )
                    val temp = File(file.absolutePath + ".tmp")
                    FileOutputStream(temp).use { output -> output.write(bytes) }
                    if (temp.length() > 100) {
                        if (!temp.renameTo(file)) {
                            temp.copyTo(file, overwrite = true)
                            temp.delete()
                        }
                        BitmapFactory.decodeFile(file.absolutePath)?.let { SHARED_TILE_MEMORY.put(key, it) }
                    } else {
                        temp.delete()
                    }
                } catch (_: Exception) {
                    // A missing tile is rendered from offline roads/neutral fill and retried later.
                } finally {
                    SHARED_TILE_PENDING.remove(key)
                    SHARED_TILE_WATCHERS.remove(key)?.forEach { it.get()?.postInvalidate() }
                }
            }
        }
        return null
    }

    private fun watchTile(key: String) {
        val watchers = SHARED_TILE_WATCHERS.computeIfAbsent(key) { CopyOnWriteArrayList() }
        watchers.removeIf { it.get() == null }
        if (watchers.none { it.get() === this }) watchers += WeakReference(this)
    }

    private fun stopWatchingTiles() {
        SHARED_TILE_WATCHERS.values.forEach { watchers ->
            watchers.removeIf { reference ->
                val owner = reference.get()
                owner == null || owner === this
            }
        }
    }

    private fun drawAlertRadius(canvas: Canvas, c: GeoPoint) {
        val me = current ?: return
        val centerPoint = screenPoint(me, c)
        val metersPerPixel = metersPerPixel(me.lat)
        if (metersPerPixel <= 0.0) return
        val radiusMeters = alertFeet / Geo.FEET_PER_METER
        val radiusPx = (radiusMeters / metersPerPixel).toFloat()
        if (radiusPx < dp(12f)) return
        canvas.drawCircle(centerPoint.x, centerPoint.y, min(radiusPx, width * 1.5f), radiusPaint)
    }

    private fun drawRoute(canvas: Canvas, c: GeoPoint) {
        val points = displayRoutePoints
        if (points.size < 2) return
        val path = Path()
        points.forEachIndexed { index, point ->
            val screen = screenPoint(point, c)
            if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
        }
        canvas.drawPath(path, routeOutlinePaint)
        canvas.drawPath(path, routePaint)
    }

    private fun drawRouteOrigin(canvas: Canvas, c: GeoPoint) {
        val origin = routeOrigin ?: return
        val p = screenPoint(origin, c)
        markerPaint.style = Paint.Style.FILL
        markerPaint.color = Color.argb(245, 26, 176, 112)
        canvas.drawCircle(p.x, p.y, dp(11f), markerPaint)
        markerPaint.style = Paint.Style.STROKE
        markerPaint.strokeWidth = dp(3f)
        markerPaint.color = Color.WHITE
        canvas.drawCircle(p.x, p.y, dp(15f), markerPaint)
        markerPaint.style = Paint.Style.FILL
    }

    private fun drawCameras(canvas: Canvas, c: GeoPoint) {
        val showLabels = zoom >= 18 && cameras.size <= 80
        cameras.sortedBy { cameraPriority(it.type) }.forEach { camera ->
            val p = screenPoint(camera.point, c)
            if (p.x < -70 || p.y < -70 || p.x > width + 70 || p.y > height + 70) return@forEach

            val color = when (camera.type) {
                CameraType.RED_LIGHT -> Color.rgb(238, 63, 63)
                CameraType.SPEED -> Color.rgb(255, 116, 42)
                CameraType.FLOCK, CameraType.ALPR -> Color.rgb(217, 59, 172)
                CameraType.TOLL -> Color.rgb(241, 177, 31)
                CameraType.SENSOR -> Color.rgb(103, 232, 162)
                CameraType.ACOUSTIC -> Color.rgb(255, 148, 76)
                CameraType.RF_SENSOR -> Color.rgb(60, 210, 217)
                CameraType.SURVEILLANCE -> Color.rgb(177, 130, 255)
                CameraType.TRAFFIC, CameraType.HIGHWAY -> Color.rgb(24, 181, 212)
                CameraType.OTHER -> Color.rgb(239, 80, 80)
            }

            // High-importance camera families receive an outer halo so they remain
            // obvious against detailed road tiles while driving.
            if (camera.type in setOf(CameraType.FLOCK, CameraType.ALPR, CameraType.RED_LIGHT, CameraType.SPEED)) {
                markerPaint.style = Paint.Style.FILL
                markerPaint.color = Color.argb(82, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawCircle(p.x, p.y, dp(20f), markerPaint)
            }

            // Approved category artwork is decoded on demand at marker scale, never at full source size.
            val icon = cameraIcons.getOrPut(camera.type) {
                AssetBitmapLoader.load(context, cameraAsset(camera.type), dp(38f).toInt())
            }
            if (icon != null) {
                val half = dp(19f)
                canvas.drawBitmap(icon, null, RectF(p.x - half, p.y - half, p.x + half, p.y + half), markerPaint)
            } else {
                // Fallback stays native and allocation-free if an asset cannot be decoded.
                markerPaint.style = Paint.Style.FILL
                markerPaint.color = Color.argb(242, 10, 12, 17)
                canvas.drawCircle(p.x, p.y, dp(18f), markerPaint)
                markerPaint.color = color
                canvas.drawCircle(p.x, p.y, dp(14f), markerPaint)
                markerPaint.style = Paint.Style.STROKE
                markerPaint.strokeWidth = dp(2.2f)
                markerPaint.color = Color.WHITE
                canvas.drawCircle(p.x, p.y, dp(14f), markerPaint)
            }

            if (showLabels) {
                val label = cameraShortLabel(camera.type)
                val textW = cameraLabelPaint.measureText(label)
                val y = p.y + dp(28f)
                val rect = RectF(
                    p.x - textW / 2f - dp(5f),
                    y - dp(12f),
                    p.x + textW / 2f + dp(5f),
                    y + dp(4f)
                )
                canvas.drawRoundRect(rect, dp(5f), dp(5f), cameraLabelBg)
                canvas.drawText(label, p.x, y, cameraLabelPaint)
            }

            markerPaint.style = Paint.Style.FILL
        }
    }


    private fun cameraAsset(type: CameraType): String = when (type) {
        CameraType.SPEED -> "wm10/camera_speed.png"
        CameraType.RED_LIGHT -> "wm10/camera_red_light.png"
        CameraType.ALPR, CameraType.FLOCK -> "wm10/camera_alpr.png"
        CameraType.TRAFFIC, CameraType.HIGHWAY -> "wm10/camera_highway.png"
        CameraType.SENSOR, CameraType.ACOUSTIC, CameraType.RF_SENSOR -> "wm10/camera_sensor.png"
        CameraType.TOLL -> "wm10/camera_toll.png"
        CameraType.OTHER, CameraType.SURVEILLANCE -> "wm10/camera_generic.png"
    }

    private fun cameraShortLabel(type: CameraType): String = when (type) {
        CameraType.FLOCK -> "FLOCK"
        CameraType.ALPR -> "ALPR"
        CameraType.RED_LIGHT -> "RED"
        CameraType.SPEED -> "SPEED"
        CameraType.TRAFFIC -> "CAM"
        CameraType.HIGHWAY -> "HWY"
        CameraType.SENSOR -> "SENSOR"
        CameraType.ACOUSTIC -> "AUDIO"
        CameraType.RF_SENSOR -> "RF"
        CameraType.SURVEILLANCE -> "SURV"
        CameraType.TOLL -> "TOLL"
        CameraType.OTHER -> "CAM"
    }

    private fun cameraPriority(type: CameraType): Int = when (type) {
        CameraType.SENSOR -> 10
        CameraType.TRAFFIC -> 20
        CameraType.HIGHWAY -> 25
        CameraType.TOLL -> 30
        CameraType.OTHER -> 35
        CameraType.ACOUSTIC -> 45
        CameraType.RF_SENSOR -> 50
        CameraType.SURVEILLANCE -> 55
        CameraType.SPEED -> 70
        CameraType.RED_LIGHT -> 75
        CameraType.ALPR -> 90
        CameraType.FLOCK -> 100
    }

    private fun drawDestination(canvas: Canvas, c: GeoPoint) {
        val dest = destination ?: return
        val p = screenPoint(dest, c)
        markerPaint.style = Paint.Style.FILL
        markerPaint.color = Color.rgb(52, 211, 153)
        canvas.drawCircle(p.x, p.y, dp(12f), markerPaint)
        markerPaint.style = Paint.Style.STROKE
        markerPaint.strokeWidth = dp(4f)
        markerPaint.color = Color.WHITE
        canvas.drawCircle(p.x, p.y, dp(17f), markerPaint)
        markerPaint.style = Paint.Style.FILL
    }

    private fun drawCurrent(canvas: Canvas, c: GeoPoint) {
        val me = current ?: return
        val p = screenPoint(me, c)
        val metersPerPixel = metersPerPixel(me.lat)
        val accuracyPx = (accuracyMeters / metersPerPixel).coerceIn(10.0, 130.0).toFloat()

        markerPaint.style = Paint.Style.FILL
        markerPaint.color = Color.argb(52, 50, 140, 255)
        canvas.drawCircle(p.x, p.y, accuracyPx, markerPaint)

        markerPaint.color = Color.WHITE
        canvas.drawCircle(p.x, p.y, dp(14f), markerPaint)
        markerPaint.color = Color.rgb(35, 115, 255)
        canvas.drawCircle(p.x, p.y, dp(10f), markerPaint)

        val bearing = currentBearing
        if (bearing != null) {
            canvas.save()
            canvas.rotate(bearing, p.x, p.y)
            val arrow = Path().apply {
                moveTo(p.x, p.y - dp(24f))
                lineTo(p.x - dp(6f), p.y - dp(12f))
                lineTo(p.x + dp(6f), p.y - dp(12f))
                close()
            }
            markerPaint.color = Color.WHITE
            canvas.drawPath(arrow, markerPaint)
            canvas.restore()
        }
    }

    private fun screenPoint(point: GeoPoint, c: GeoPoint): PointF {
        val wp = worldPixels(point, zoom)
        val wc = worldPixels(c, zoom)
        return PointF(
            (wp.first - wc.first + width / 2.0).toFloat(),
            (wp.second - wc.second + height / 2.0).toFloat()
        )
    }

    private fun worldPixels(point: GeoPoint, z: Int): Pair<Double, Double> {
        val n = (1 shl z).toDouble() * tileSize
        val x = (point.lon + 180.0) / 360.0 * n
        val lat = point.lat.coerceIn(-85.05112878, 85.05112878)
        val sinLat = sin(Math.toRadians(lat))
        val y = (0.5 - ln((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)) * n
        return x to y
    }

    private fun geoFromWorld(x: Double, y: Double, z: Int): GeoPoint {
        val n = (1 shl z).toDouble() * tileSize
        val lon = x / n * 360.0 - 180.0
        val yy = Math.PI * (1 - 2 * y / n)
        val lat = Math.toDegrees(atan(sinh(yy)))
        return GeoPoint(lat, lon)
    }

    private fun metersPerPixel(lat: Double): Double =
        156543.03392 * cos(Math.toRadians(lat)) / (1 shl zoom)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val c = center ?: return true
                    val wc = worldPixels(c, zoom)
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    center = geoFromWorld(wc.first - dx, wc.second - dy, zoom)
                    follow = false
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                val moved = hypot(event.x - downX, event.y - downY)
                if (moved < dp(20f)) {
                    val c = center
                    if (c != null) {
                        val hit = cameras
                            .asSequence()
                            .map { camera -> camera to screenPoint(camera.point, c) }
                            .map { (camera, p) -> camera to hypot(event.x - p.x, event.y - p.y) }
                            .filter { it.second <= dp(50f) }
                            .minByOrNull { it.second }
                            ?.first
                        if (hit != null) onCameraTap?.invoke(hit)
                    }
                    performClick()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        offlineRoads.activate()
    }

    override fun onDetachedFromWindow() {
        offlineRoads.close()
        stopWatchingTiles()
        super.onDetachedFromWindow()
    }

    private fun trimTileDiskCache() {
        val files = tileRoot.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= TILE_DISK_HIGH_WATER_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= TILE_DISK_LOW_WATER_BYTES) return@forEach
            val length = file.length()
            if (file.delete()) total -= length
        }
    }
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    /**
     * Bounds canvas work while retaining the first point, last point, and evenly distributed
     * intermediate geometry. The complete Route remains in [route] for navigation and export.
     */
    private fun <T> selectEvenly(values: List<T>, maxItems: Int): List<T> {
        if (values.size <= maxItems) return values
        if (maxItems <= 1) return listOf(values.first())
        return (0 until maxItems).map { index ->
            values[(index.toLong() * values.lastIndex / (maxItems - 1)).toInt()]
        }
    }

    companion object {
        private val TILE_THREAD_COUNTER = AtomicInteger()
        private val SHARED_TILE_EXECUTOR = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "watchman-raster-tile-${TILE_THREAD_COUNTER.incrementAndGet()}").apply { isDaemon = true }
        }
        private val SHARED_TILE_PENDING = ConcurrentHashMap.newKeySet<String>()
        private val SHARED_TILE_WATCHERS = ConcurrentHashMap<String, CopyOnWriteArrayList<WeakReference<TileMapView>>>()
        private val SHARED_TILE_MEMORY = object : LruCache<String, Bitmap>(MapMemoryPolicy.NORMAL_TILE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
        private val TILE_TRIM_STARTED = AtomicBoolean(false)
        private const val TILE_DISK_HIGH_WATER_BYTES = 128L * 1024L * 1024L
        private const val TILE_WORKFLOW_TIMEOUT_MS = 10_000
        private const val MAX_TILE_BYTES = 2 * 1024 * 1024
        private const val TILE_DISK_LOW_WATER_BYTES = 96L * 1024L * 1024L
        private const val DEFAULT_ZOOM = 17
        private const val MIN_INTERACTIVE_ZOOM = 2
        private const val MIN_OVERVIEW_ZOOM = 2
        private const val MAX_ZOOM = 20
        private const val MAX_RENDER_ROUTE_POINTS = 4_096
        private const val MAX_FIT_MANEUVER_POINTS = 512
        private const val MAX_RENDER_CAMERAS = 3_000
    }
}
