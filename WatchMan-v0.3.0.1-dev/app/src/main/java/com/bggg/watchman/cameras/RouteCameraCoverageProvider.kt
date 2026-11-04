package com.bggg.watchman.cameras

import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraSourceResult
import com.bggg.watchman.data.CameraSourceStatus
import com.bggg.watchman.data.CameraSyncResult
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.ProviderConfig
import com.bggg.watchman.data.Route
import com.bggg.watchman.net.HttpClient
import com.bggg.watchman.util.Geo
import org.json.JSONObject
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * High-priority route-corridor camera/sensor acquisition used for route scoring and preview.
 *
 * This is deliberately separate from OverpassCameraProvider. The v0.1.8 nearby/startup camera
 * path remains unchanged. Long routes are divided into deterministic Web-Mercator cells and each
 * successful cell is published independently, so one slow or failed Overpass request cannot turn
 * an otherwise useful 80+ mile route into an all-or-nothing "camera sync failed" result.
 *
 * The query is broader than the two SafeTOC nearby requests because this provider is additive and
 * route-specific. The final route camera set is still filtered to the normal narrow corridor by
 * CameraDatabase.nearRoutes().
 */
class RouteCameraCoverageProvider : Closeable {
    private val parser = OverpassCameraProvider()
    private val workerCounter = AtomicInteger()
    private val foregroundWorkerExecutor = Executors.newFixedThreadPool(MAX_CONCURRENCY) { runnable ->
        Thread(runnable, "watchman-route-coverage-foreground-${workerCounter.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    private val backgroundWorkerExecutor = Executors.newFixedThreadPool(MAX_CONCURRENCY) { runnable ->
        Thread(runnable, "watchman-route-coverage-background-${workerCounter.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    private val foregroundEpoch = AtomicInteger()
    private val foregroundSessions = AtomicInteger()
    private val backgroundFutures = ConcurrentHashMap.newKeySet<Future<CameraSourceResult>>()

    data class RouteTile(val x: Int, val y: Int, val bbox: String) {
        val sourceId: String get() = "osm_route_tile_v1_${x}_${y}"
    }

    fun syncRoutesIncremental(
        routes: List<Route>,
        maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
        onTile: ((CameraSourceResult, Int, Int) -> Unit)? = null
    ): CameraSyncResult {
        val foreground = maxWaitMs <= DEFAULT_MAX_WAIT_MS
        val tileLimit = if (foreground) FOREGROUND_TILE_CAP else BACKGROUND_TILE_CAP
        val tiles = routeTiles(routes, tileLimit)
        if (tiles.isEmpty()) return CameraSyncResult(emptyList(), emptyList())

        if (foreground) {
            foregroundSessions.incrementAndGet()
            foregroundEpoch.incrementAndGet()
            backgroundFutures.forEach { it.cancel(true) }
        } else if (foregroundSessions.get() > 0) {
            return CameraSyncResult(
                emptyList(),
                tiles.map { tile ->
                    CameraSourceStatus(
                        sourceId = tile.sourceId,
                        sourceName = "OpenStreetMap / Overpass — route coverage",
                        ok = false,
                        count = 0,
                        endpoint = null,
                        error = "Foreground route acquisition has priority; cached tile retained",
                        elapsedMs = 0L
                    )
                }
            )
        }
        val sessionEpoch = foregroundEpoch.get()
        val workerExecutor = if (foreground) foregroundWorkerExecutor else backgroundWorkerExecutor
        val completion = ExecutorCompletionService<CameraSourceResult>(workerExecutor)
        val futures = ArrayList<Future<CameraSourceResult>>(tiles.size)
        val futureTiles = LinkedHashMap<Future<CameraSourceResult>, RouteTile>(tiles.size)
        try {
            tiles.forEachIndexed { index, tile ->
                if (!foreground && sessionEpoch != foregroundEpoch.get()) return@forEachIndexed
                val future = completion.submit(Callable { fetchTile(tile, index) })
                futures += future
                futureTiles[future] = tile
                if (!foreground) {
                    backgroundFutures += future
                    if (sessionEpoch != foregroundEpoch.get()) future.cancel(true)
                }
            }

            val statuses = ArrayList<CameraSourceStatus>(tiles.size)
            val merged = LinkedHashMap<String, CameraPoi>()
            val completedIds = LinkedHashSet<String>()
            val deadline = System.currentTimeMillis() + maxWaitMs.coerceAtLeast(1_000L)
            var completedCount = 0
            while (completedCount < futures.size) {
                if (!foreground && sessionEpoch != foregroundEpoch.get()) break
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                val future = completion.poll(remaining, TimeUnit.MILLISECONDS) ?: break
                if (!foreground) backgroundFutures.remove(future)
                val result = try {
                    future.get()
                } catch (e: Exception) {
                    val cause = e.cause ?: e
                    CameraSourceResult(
                        emptyList(),
                        CameraSourceStatus(
                            sourceId = futureTiles[future]?.sourceId ?: "osm_route_tile_unknown_$completedCount",
                            sourceName = "OpenStreetMap / Overpass — route coverage",
                            ok = false,
                            count = 0,
                            endpoint = null,
                            error = cause.message ?: cause.javaClass.simpleName,
                            elapsedMs = 0L
                        )
                    )
                }
                completedCount++
                completedIds += result.status.sourceId
                statuses += result.status
                result.cameras.forEach { camera -> mergeInto(merged, camera) }
                onTile?.invoke(result, completedCount, tiles.size)
            }
            if (completedCount < tiles.size) {
                tiles.filterNot { it.sourceId in completedIds }.forEach { tile ->
                    statuses += CameraSourceStatus(
                        sourceId = tile.sourceId,
                        sourceName = "OpenStreetMap / Overpass — route coverage",
                        ok = false,
                        count = 0,
                        endpoint = null,
                        error = "Planning time budget reached; cached tile retained",
                        elapsedMs = maxWaitMs
                    )
                }
            }
            return CameraSyncResult(merged.values.toList(), statuses)
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
            if (foreground) {
                foregroundSessions.decrementAndGet()
            } else {
                backgroundFutures.removeAll(futures.toSet())
            }
        }
    }

    internal fun routeTiles(routes: List<Route>, maxTiles: Int = FOREGROUND_TILE_CAP): List<RouteTile> {
        if (routes.isEmpty()) return emptyList()
        val cells = LinkedHashMap<Pair<Int, Int>, Int>()
        fun add(point: GeoPoint, priority: Int) {
            val (mx, my) = mercatorMeters(point)
            val cell = floor(mx / TILE_METERS).toInt() to floor(my / TILE_METERS).toInt()
            cells[cell] = minOf(cells[cell] ?: Int.MAX_VALUE, priority)
        }
        routes.forEach { route ->
            val samples = Geo.sampleRoutePoints(route.points, ROUTE_SAMPLE_METERS)
            samples.forEachIndexed { index, point ->
                add(point, if (index == 0 || index == samples.lastIndex) 0 else 1)
            }
            add(route.destination.point, 0)
            route.steps.forEach { add(it.maneuver, 2) }
        }
        val limit = maxTiles.coerceIn(1, BACKGROUND_TILE_CAP)
        val selected = ArrayList<Pair<Int, Int>>(limit)
        val mandatory = cells.filterValues { it == 0 }.keys.toList()
        selected += mandatory.take(limit)
        for (priority in 1..2) {
            val room = limit - selected.size
            if (room <= 0) break
            val candidates = cells.filterValues { it == priority }.keys
                .filterNot { it in selected }
            selected += selectEvenly(candidates, room)
        }
        val selectedSet = selected.toHashSet()
        val traversalOrder = cells.keys.filter { it in selectedSet }
        return ProgressiveCoverageOrder.apply(traversalOrder).mapNotNull { (x, y) -> routeTile(x, y) }
    }

    override fun close() {
        foregroundEpoch.incrementAndGet()
        backgroundFutures.forEach { it.cancel(true) }
        foregroundWorkerExecutor.shutdownNow()
        backgroundWorkerExecutor.shutdownNow()
    }

    private fun <T> selectEvenly(items: List<T>, limit: Int): List<T> {
        if (items.size <= limit) return items
        if (limit <= 1) return listOf(items[items.size / 2])
        return (0 until limit).map { index ->
            items[(index.toLong() * items.lastIndex.toLong() / (limit - 1)).toInt()]
        }
    }

    private fun fetchTile(tile: RouteTile, endpointOffset: Int): CameraSourceResult {
        val started = System.currentTimeMillis()
        val sourceName = "OpenStreetMap / Overpass — route coverage"
        val query = routeCoverageQuery(tile.bbox)
        var lastEndpoint: String? = null
        var lastError: Exception? = null

        val configured = ProviderConfig.OVERPASS_ENDPOINTS
        val endpoints = if (configured.isEmpty()) emptyList() else {
            val rotated = List(configured.size) { i -> configured[(i + endpointOffset) % configured.size] }
            (listOf(configured.first()) + rotated.filterNot { it == configured.first() }).take(MAX_ENDPOINTS_PER_TILE)
        }

        for (endpoint in endpoints) {
            lastEndpoint = endpoint
            try {
                val json = HttpClient.postForm(endpoint, mapOf("data" to query), timeoutMs = TILE_TIMEOUT_MS, attempts = 1)
                validateResponse(json)
                val cameras = parser.parse(json, tile.sourceId, sourceName)
                return CameraSourceResult(
                    cameras,
                    CameraSourceStatus(
                        sourceId = tile.sourceId,
                        sourceName = sourceName,
                        ok = true,
                        count = cameras.size,
                        endpoint = endpoint,
                        error = null,
                        elapsedMs = System.currentTimeMillis() - started
                    )
                )
            } catch (e: Exception) {
                lastError = e
            }
        }

        return CameraSourceResult(
            emptyList(),
            CameraSourceStatus(
                sourceId = tile.sourceId,
                sourceName = sourceName,
                ok = false,
                count = 0,
                endpoint = lastEndpoint,
                error = lastError?.message ?: "Provider unavailable",
                elapsedMs = System.currentTimeMillis() - started
            )
        )
    }

    private fun routeCoverageQuery(box: String): String = """
        [out:json][timeout:30];
        (
          node["surveillance:type"="ALPR"]($box);
          node["manufacturer"="Flock Safety"]($box);
          node["highway"="traffic_signals"]($box);
          node["highway"="speed_camera"]($box);
          node["enforcement"="maxspeed"]($box);
          node["surveillance"="traffic"]($box);
          node["camera:type"="traffic"]($box);
          nwr["surveillance:type"~"^(ALPR|ANPR|LPR)$",i]($box);
          nwr["surveillance"~"^(ALPR|ANPR|LPR)$",i]($box);
          nwr["manufacturer"~"flock",i]($box);
          nwr["brand"~"flock",i]($box);
          nwr["operator"~"flock",i]($box);
          nwr["description"~"ALPR|ANPR|LPR|license.?plate|number.?plate",i]($box);
          nwr["highway"="traffic_signals"]($box);
          nwr["highway"="speed_camera"]($box);
          nwr["type"="enforcement"]["enforcement"~"maxspeed|traffic_signals|red_light|average_speed|mobile_phone|distance",i]($box);
          nwr["enforcement"~"maxspeed|traffic_signals|red_light|average_speed|mobile_phone|distance",i]($box);
          nwr["enforcement:type"~"camera",i]($box);
          nwr["man_made"="surveillance"]($box);
          nwr["surveillance"="traffic"]($box);
          nwr["surveillance:zone"~"traffic|public_transport|parking",i]($box);
          nwr["camera:type"]($box);
          nwr["camera:usage"~"traffic|transport|highway|road",i]($box);
          nwr["man_made"="monitoring_station"]["monitoring:traffic"]($box);
          nwr["monitoring:traffic"]($box);
          nwr["monitoring"~"traffic",i]($box);
          nwr["traffic_sensor"]($box);
          nwr["barrier"="toll_booth"]($box);
          nwr["highway"="toll_gantry"]($box);
          nwr["toll"="yes"]["highway"="traffic_signals"]($box);
        );
        out body center qt;
    """.trimIndent()

    private fun validateResponse(json: String) {
        val root = JSONObject(json)
        if (!root.has("elements")) throw IllegalStateException("Overpass response did not contain elements")
        val remark = root.optString("remark", "").trim()
        if (remark.isNotEmpty()) {
            val lower = remark.lowercase(Locale.US)
            if ("runtime error" in lower || "timed out" in lower || "timeout" in lower || "out of memory" in lower) {
                throw IllegalStateException(remark.take(220))
            }
        }
    }

    private fun routeTile(x: Int, y: Int): RouteTile? {
        val left = x * TILE_METERS - TILE_EDGE_PAD_METERS
        val right = (x + 1) * TILE_METERS + TILE_EDGE_PAD_METERS
        val bottom = y * TILE_METERS - TILE_EDGE_PAD_METERS
        val top = (y + 1) * TILE_METERS + TILE_EDGE_PAD_METERS
        val west = Math.toDegrees(left / WEB_MERCATOR_RADIUS).coerceIn(-179.999, 179.999)
        val east = Math.toDegrees(right / WEB_MERCATOR_RADIUS).coerceIn(-179.999, 179.999)
        val south = inverseMercatorLat(bottom)
        val north = inverseMercatorLat(top)
        if (west >= east || south >= north) return null
        return RouteTile(x, y, String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", south, west, north, east))
    }

    private fun mercatorMeters(point: GeoPoint): Pair<Double, Double> {
        val lat = point.lat.coerceIn(-85.0, 85.0)
        val x = WEB_MERCATOR_RADIUS * Math.toRadians(point.lon.coerceIn(-179.999999, 179.999999))
        val y = WEB_MERCATOR_RADIUS * ln(tan(PI / 4.0 + Math.toRadians(lat) / 2.0))
        return x to y
    }

    private fun inverseMercatorLat(y: Double): Double =
        Math.toDegrees(2.0 * atan(exp(y / WEB_MERCATOR_RADIUS)) - PI / 2.0).coerceIn(-85.0, 85.0)

    private fun mergeInto(target: MutableMap<String, CameraPoi>, camera: CameraPoi) {
        val key = camera.sourceRecordId ?: camera.id
        val existing = target[key]
        if (existing == null || CameraClassifier.priority(camera.type) > CameraClassifier.priority(existing.type)) {
            target[key] = camera
        }
    }

    companion object {
        const val TILE_METERS = 25_000.0
        const val TILE_EDGE_PAD_METERS = 1_500.0
        const val ROUTE_SAMPLE_METERS = 5_000.0
        private const val TILE_TIMEOUT_MS = 12_000
        private const val MAX_CONCURRENCY = 2
        private const val MAX_ENDPOINTS_PER_TILE = 2
        const val DEFAULT_MAX_WAIT_MS = 28_000L
        const val BACKGROUND_MAX_WAIT_MS = 60_000L
        const val FOREGROUND_TILE_CAP = 48
        const val BACKGROUND_TILE_CAP = 160
        private const val WEB_MERCATOR_RADIUS = 6_378_137.0
    }
}
