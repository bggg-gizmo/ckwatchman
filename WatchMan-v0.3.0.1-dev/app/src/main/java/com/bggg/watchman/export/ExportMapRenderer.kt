package com.bggg.watchman.export

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.ui.OfflineRoadStore
import java.io.File
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * PDF map renderer backed by cached raster and the bundled PBF-derived visual road pack.
 * Raster tiles must be already cached by the interactive map; exports never download them.
 *
 * Offline road context is prepared before saving. If neither source has coverage, route geometry,
 * camera markers and endpoints remain available with an explicit missing-basemap notice. Nothing
 * about directions/history export depends on a fresh basemap download.
 */
class ExportMapRenderer(private val tileCacheDirectory: File? = null, private val assets: AssetManager? = null) : AutoCloseable {
    data class RouteLine(
        val points: List<GeoPoint>,
        val selected: Boolean = false,
        val weight: Float = 1f,
        val label: String? = null
    )

    data class MapResult(val basemapLoaded: Boolean, val tileCount: Int, val offlineRoadCount: Int = 0)

    private val tileCache = LinkedHashMap<String, Bitmap>(32, 0.75f, true)

    fun draw(
        canvas: Canvas,
        rect: RectF,
        routes: List<RouteLine>,
        cameras: List<CameraPoi> = emptyList(),
        start: GeoPoint? = null,
        destination: GeoPoint? = null,
        showCameraLabels: Boolean = false
    ): MapResult {
        val routePoints = routes.flatMap { it.points }
        val basis = if (routePoints.isNotEmpty()) routePoints else cameras.map { it.point }
        if (basis.isEmpty()) {
            canvas.drawRect(rect, Paint().apply { color = Color.rgb(242, 243, 240) })
            return MapResult(false, 0)
        }

        val bounds = boundsFor(basis)
        val zoom = chooseZoom(bounds, rect)
        val transform = Transform(bounds, rect, zoom)
        canvas.save()
        canvas.clipRect(rect)
        canvas.drawRect(rect, Paint().apply { color = Color.rgb(239, 241, 238) })

        val roadCount = drawOfflineRoads(canvas, rect, transform)
        val tileResult = drawTiles(canvas, rect, transform)
        val hasRoadContext = tileResult.first || roadCount > 0
        if (!hasRoadContext) drawFallbackGrid(canvas, rect)

        cameras.forEach { camera ->
            val p = transform.toCanvas(camera.point)
            if (rect.contains(p.first, p.second)) drawCamera(canvas, p.first, p.second, camera, showCameraLabels)
        }
        routes.forEachIndexed { index, line ->
            drawRouteLine(canvas, transform, line, index)
        }
        start?.let {
            val p = transform.toCanvas(it)
            drawEndpoint(canvas, p.first, p.second, "S", Color.rgb(30, 125, 64))
        }
        destination?.let {
            val p = transform.toCanvas(it)
            drawEndpoint(canvas, p.first, p.second, "D", Color.rgb(190, 48, 46))
        }

        canvas.restore()
        val attribution = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 72, 74); textSize = 7f }
        canvas.drawText("Map © OpenStreetMap contributors", rect.left + 4f, rect.bottom - 4f, attribution)
        if (!hasRoadContext) {
            val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 90, 90); textSize = 8f }
            canvas.drawText("Basemap unavailable — route geometry retained", rect.left + 4f, rect.top + 12f, note)
        }
        return MapResult(hasRoadContext, tileResult.second, roadCount)
    }

    private fun drawOfflineRoads(canvas: Canvas, rect: RectF, t: Transform): Int {
        val assetManager = assets ?: return 0
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.rgb(166, 171, 177)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(73, 79, 86); textSize = 7f }
        val labelLayout = MapLabelLayout(rect.left + 4f, rect.top + 4f, rect.right - 4f, rect.bottom - 14f)
        val labels = mutableListOf<Pair<String, MapLabelLayout.Placement>>()
        val labelMetrics = labelPaint.fontMetrics
        var count = 0
        OfflineRoadStore.visitRoads(assetManager, t.fromCanvas(rect.left, rect.top), t.fromCanvas(rect.right, rect.bottom)) { road ->
            val path = Path()
            var left = Float.POSITIVE_INFINITY; var right = Float.NEGATIVE_INFINITY
            var top = Float.POSITIVE_INFINITY; var bottom = Float.NEGATIVE_INFINITY
            var labelPoint: Pair<Float, Float>? = null
            road.points.forEachIndexed { index, point ->
                val p = t.toCanvas(point)
                if (index == 0) path.moveTo(p.first, p.second) else path.lineTo(p.first, p.second)
                left = min(left, p.first); right = max(right, p.first)
                top = min(top, p.second); bottom = max(bottom, p.second)
                if (labelPoint == null && rect.contains(p.first, p.second)) labelPoint = p
            }
            if (right >= rect.left && left <= rect.right && bottom >= rect.top && top <= rect.bottom) {
                stroke.strokeWidth = when (road.rank) { 1 -> 1.5f; 2 -> 1.1f; 3 -> 0.8f; else -> 0.55f }
                canvas.drawPath(path, stroke)
                count++
                val position = labelPoint
                val label = road.label
                if (position != null && label != null) {
                    val width = labelPaint.measureText(label)
                    labelLayout.place(position.first, position.second, width, labelMetrics.top, labelMetrics.bottom)?.let {
                        labels.add(label to it)
                    }
                }
            }
        }
        // Draw once geometry is complete so later road strokes cannot obscure earlier text.
        labels.forEach { (label, position) -> canvas.drawText(label, position.x, position.baseline, labelPaint) }
        return count
    }

    private fun drawTiles(canvas: Canvas, rect: RectF, t: Transform): Pair<Boolean, Int> {
        val world = t.worldSize
        val leftWorld = t.centerWorldX - rect.width() / (2f * t.scale)
        val rightWorld = t.centerWorldX + rect.width() / (2f * t.scale)
        val topWorld = t.centerWorldY - rect.height() / (2f * t.scale)
        val bottomWorld = t.centerWorldY + rect.height() / (2f * t.scale)
        val minTileX = floor(leftWorld / TILE).toInt()
        val maxTileX = floor(rightWorld / TILE).toInt()
        val minTileY = floor(topWorld / TILE).toInt().coerceAtLeast(0)
        val maxTileY = floor(bottomWorld / TILE).toInt().coerceAtMost((world / TILE).toInt() - 1)
        val tileCount = (maxTileX - minTileX + 1) * (maxTileY - minTileY + 1)
        if (tileCount <= 0 || tileCount > MAX_TILES_PER_MAP) return false to 0

        var loaded = 0
        for (ty in minTileY..maxTileY) {
            for (txRaw in minTileX..maxTileX) {
                val tileSpan = 1 shl t.zoom
                val tx = ((txRaw % tileSpan) + tileSpan) % tileSpan
                val bitmap = loadTile(t.zoom, tx, ty) ?: continue
                val worldX = txRaw * TILE.toDouble()
                val worldY = ty * TILE.toDouble()
                val left = rect.centerX() + ((worldX - t.centerWorldX) * t.scale).toFloat()
                val top = rect.centerY() + ((worldY - t.centerWorldY) * t.scale).toFloat()
                val target = RectF(left, top, left + (TILE * t.scale).toFloat(), top + (TILE * t.scale).toFloat())
                canvas.drawBitmap(bitmap, null, target, null)
                loaded++
            }
        }
        return (loaded > 0) to loaded
    }

    private fun loadTile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        synchronized(tileCache) { tileCache[key]?.let { return it } }
        val file = tileCacheDirectory?.resolve("$key.png")?.takeIf { it.isFile } ?: return null
        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            synchronized(tileCache) {
                tileCache[key] = bitmap
                while (tileCache.size > MAX_CACHED_TILES) {
                    val first = tileCache.entries.iterator().next()
                    tileCache.remove(first.key)
                    if (!first.value.isRecycled) first.value.recycle()
                }
            }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        synchronized(tileCache) {
            tileCache.values.forEach { if (!it.isRecycled) it.recycle() }
            tileCache.clear()
        }
    }

    private fun drawFallbackGrid(canvas: Canvas, rect: RectF) {
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(214, 216, 211); strokeWidth = 0.8f }
        for (i in 1..5) {
            val x = rect.left + rect.width() * i / 6f
            val y = rect.top + rect.height() * i / 6f
            canvas.drawLine(x, rect.top, x, rect.bottom, grid)
            canvas.drawLine(rect.left, y, rect.right, y, grid)
        }
    }

    private fun drawRouteLine(canvas: Canvas, t: Transform, line: RouteLine, index: Int) {
        if (line.points.size < 2) return
        val path = Path()
        line.points.forEachIndexed { i, p ->
            val xy = t.toCanvas(p)
            if (i == 0) path.moveTo(xy.first, xy.second) else path.lineTo(xy.first, xy.second)
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = (if (line.selected) 7.5f else 5f) * line.weight.coerceIn(0.7f, 2.4f)
        }
        val route = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = routeColor(index, line.selected)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = (if (line.selected) 4.6f else 2.8f) * line.weight.coerceIn(0.7f, 2.4f)
            alpha = if (line.selected) 255 else 185
        }
        canvas.drawPath(path, outline)
        canvas.drawPath(path, route)
    }

    private fun drawEndpoint(canvas: Canvas, x: Float, y: Float, text: String, colorValue: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorValue; style = Paint.Style.FILL }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
        canvas.drawCircle(x, y, 8f, p)
        canvas.drawCircle(x, y, 8f, border)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 8f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        canvas.drawText(text, x, y + 3f, tp)
    }

    private fun drawCamera(canvas: Canvas, x: Float, y: Float, camera: CameraPoi, labels: Boolean) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cameraColor(camera.type); style = Paint.Style.FILL }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.2f }
        canvas.drawCircle(x, y, 4.4f, fill)
        canvas.drawCircle(x, y, 4.4f, border)
        if (labels) {
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 35, 35); textSize = 6.5f }
            canvas.drawText(cameraAbbrev(camera.type), x + 6f, y + 2f, t)
        }
    }

    private fun routeColor(index: Int, selected: Boolean): Int {
        if (selected) return Color.rgb(182, 128, 35)
        return when (index % 4) {
            0 -> Color.rgb(47, 108, 174)
            1 -> Color.rgb(102, 82, 160)
            2 -> Color.rgb(50, 135, 112)
            else -> Color.rgb(175, 85, 70)
        }
    }

    private fun cameraColor(type: CameraType): Int = when (type) {
        CameraType.RED_LIGHT -> Color.rgb(194, 49, 45)
        CameraType.SPEED -> Color.rgb(215, 110, 32)
        CameraType.TRAFFIC, CameraType.HIGHWAY -> Color.rgb(54, 112, 178)
        CameraType.ALPR, CameraType.FLOCK -> Color.rgb(116, 73, 155)
        CameraType.TOLL -> Color.rgb(39, 130, 109)
        CameraType.SENSOR -> Color.rgb(77, 143, 77)
        CameraType.ACOUSTIC -> Color.rgb(181, 91, 44)
        CameraType.RF_SENSOR -> Color.rgb(28, 132, 147)
        CameraType.SURVEILLANCE -> Color.rgb(105, 93, 145)
        CameraType.OTHER -> Color.rgb(90, 90, 90)
    }

    private fun cameraAbbrev(type: CameraType): String = when (type) {
        CameraType.RED_LIGHT -> "RL"
        CameraType.SPEED -> "SPD"
        CameraType.TRAFFIC -> "TRF"
        CameraType.HIGHWAY -> "HWY"
        CameraType.ALPR -> "LPR"
        CameraType.FLOCK -> "FLK"
        CameraType.TOLL -> "TOLL"
        CameraType.SENSOR -> "SNS"
        CameraType.ACOUSTIC -> "ACO"
        CameraType.RF_SENSOR -> "RF"
        CameraType.SURVEILLANCE -> "SURV"
        CameraType.OTHER -> "CAM"
    }

    private data class Bounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

    private fun boundsFor(points: List<GeoPoint>): Bounds {
        var minLat = points.minOf { it.lat }
        var maxLat = points.maxOf { it.lat }
        var minLon = points.minOf { it.lon }
        var maxLon = points.maxOf { it.lon }
        if (maxLat - minLat < 0.002) { minLat -= 0.001; maxLat += 0.001 }
        if (maxLon - minLon < 0.002) { minLon -= 0.001; maxLon += 0.001 }
        val latPad = (maxLat - minLat) * 0.12
        val lonPad = (maxLon - minLon) * 0.12
        return Bounds(
            (minLat - latPad).coerceAtLeast(-85.0),
            minLon - lonPad,
            (maxLat + latPad).coerceAtMost(85.0),
            maxLon + lonPad
        )
    }

    private fun chooseZoom(bounds: Bounds, rect: RectF): Int {
        for (z in 17 downTo 3) {
            val a = worldPixel(GeoPoint(bounds.minLat, bounds.minLon), z)
            val b = worldPixel(GeoPoint(bounds.maxLat, bounds.maxLon), z)
            val w = kotlin.math.abs(b.first - a.first).coerceAtLeast(1.0)
            val h = kotlin.math.abs(b.second - a.second).coerceAtLeast(1.0)
            val scale = min(rect.width() / w.toFloat(), rect.height() / h.toFloat())
            if (scale >= 0.72f) {
                val tiles = ((rect.width() / (TILE * scale)).toInt() + 3) * ((rect.height() / (TILE * scale)).toInt() + 3)
                if (tiles <= MAX_TILES_PER_MAP) return z
            }
        }
        return 3
    }

    private class Transform(bounds: Bounds, private val rect: RectF, val zoom: Int) {
        val worldSize: Double = TILE * 2.0.pow(zoom.toDouble())
        val centerWorldX: Double
        val centerWorldY: Double
        val scale: Float

        init {
            val a = worldPixel(GeoPoint(bounds.minLat, bounds.minLon), zoom)
            val b = worldPixel(GeoPoint(bounds.maxLat, bounds.maxLon), zoom)
            val minX = min(a.first, b.first); val maxX = max(a.first, b.first)
            val minY = min(a.second, b.second); val maxY = max(a.second, b.second)
            centerWorldX = (minX + maxX) / 2.0
            centerWorldY = (minY + maxY) / 2.0
            val width = (maxX - minX).coerceAtLeast(1.0)
            val height = (maxY - minY).coerceAtLeast(1.0)
            scale = min(rect.width() / width.toFloat(), rect.height() / height.toFloat())
        }

        fun toCanvas(p: GeoPoint): Pair<Float, Float> {
            val w = worldPixel(p, zoom)
            return (rect.centerX() + ((w.first - centerWorldX) * scale).toFloat()) to
                (rect.centerY() + ((w.second - centerWorldY) * scale).toFloat())
        }

        fun fromCanvas(x: Float, y: Float): GeoPoint {
            val wx = centerWorldX + (x - rect.centerX()) / scale
            val wy = centerWorldY + (y - rect.centerY()) / scale
            val latitude = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * wy / worldSize))))
            val longitude = (wx / worldSize * 360.0 - 180.0).coerceIn(-180.0, 180.0)
            return GeoPoint(latitude.coerceIn(-85.05112878, 85.05112878), longitude)
        }
    }

    companion object {
        private const val TILE = 256.0
        private const val MAX_TILES_PER_MAP = 24
        private const val MAX_CACHED_TILES = 40

        private fun worldPixel(p: GeoPoint, zoom: Int): Pair<Double, Double> {
            val lat = p.lat.coerceIn(-85.05112878, 85.05112878)
            val n = 2.0.pow(zoom.toDouble())
            val x = (p.lon + 180.0) / 360.0 * n * TILE
            val latRad = Math.toRadians(lat)
            val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n * TILE
            return x to y
        }
    }
}
