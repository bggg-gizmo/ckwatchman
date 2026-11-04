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
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Export-only regional camera/sensor acquisition.
 *
 * This class is intentionally separate from [OverpassCameraProvider]. The live/startup provider is
 * the v0.1.8 compatibility path and is not modified by regional export coverage. Regional fetches
 * are driven exclusively by planned route geometry, so they work with typed Start/Destination
 * routes even when GPS is unavailable.
 */
class RegionalOverpassCameraProvider {
    private val parser = OverpassCameraProvider()

    fun syncRouteRegionIncremental(
        routes: List<Route>,
        radiusMeters: Int = ROUTE_REGION_RADIUS_METERS,
        maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
        onTile: ((CameraSourceResult) -> Unit)? = null
    ): CameraSyncResult {
        val tiles = routeRegionTiles(routes, radiusMeters)
        if (tiles.isEmpty()) return CameraSyncResult(emptyList(), emptyList())

        val pool = Executors.newFixedThreadPool(MAX_CONCURRENCY)
        try {
            val completion = ExecutorCompletionService<CameraSourceResult>(pool)
            tiles.forEachIndexed { index, tile ->
                completion.submit(Callable { fetchTile(tile, index) })
            }
            val statuses = ArrayList<CameraSourceStatus>(tiles.size)
            val merged = LinkedHashMap<String, CameraPoi>()
            val completedIds = LinkedHashSet<String>()
            val deadline = System.currentTimeMillis() + maxWaitMs.coerceAtLeast(5_000L)
            var completed = 0
            while (completed < tiles.size) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                val future = completion.poll(remaining, TimeUnit.MILLISECONDS) ?: break
                val result = try {
                    future.get()
                } catch (e: Exception) {
                    val cause = e.cause ?: e
                    CameraSourceResult(
                        emptyList(),
                        CameraSourceStatus(
                            sourceId = "osm_export_region_unknown_$completed",
                            sourceName = "OpenStreetMap / Overpass — export region",
                            ok = false,
                            count = 0,
                            endpoint = null,
                            error = cause.message ?: cause.javaClass.simpleName,
                            elapsedMs = 0L
                        )
                    )
                }
                completed++
                completedIds += result.status.sourceId
                statuses += result.status
                result.cameras.forEach { camera -> mergeInto(merged, camera) }
                onTile?.invoke(result)
            }
            if (completed < tiles.size) {
                tiles.filterNot { it.sourceId in completedIds }.forEach { tile ->
                    statuses += CameraSourceStatus(
                        sourceId = tile.sourceId,
                        sourceName = "OpenStreetMap / Overpass — export region",
                        ok = false,
                        count = 0,
                        endpoint = null,
                        error = "Regional refresh time budget reached; prior tile cache retained",
                        elapsedMs = maxWaitMs
                    )
                }
            }
            return CameraSyncResult(merged.values.toList(), statuses)
        } finally {
            pool.shutdownNow()
        }
    }

    data class RegionTile(val x: Int, val y: Int, val bbox: String) {
        val sourceId: String get() = "osm_export_region_v1_${x}_${y}"
    }

    private fun fetchTile(tile: RegionTile, endpointOffset: Int): CameraSourceResult {
        val started = System.currentTimeMillis()
        val sourceName = "OpenStreetMap / Overpass — 100 mi export region"
        val query = routeRegionQuery(tile.bbox)
        var lastError: Exception? = null
        var lastEndpoint: String? = null

        val configured = ProviderConfig.OVERPASS_ENDPOINTS
        val endpoints = if (configured.isEmpty()) {
            emptyList()
        } else {
            val rotated = List(configured.size) { i -> configured[(i + endpointOffset) % configured.size] }
            (listOf(configured.first()) + rotated.filterNot { it == configured.first() }).take(MAX_ENDPOINTS_PER_TILE)
        }

        for (endpoint in endpoints) {
            lastEndpoint = endpoint
            try {
                val json = HttpClient.postForm(endpoint, mapOf("data" to query), timeoutMs = 18_000, attempts = 1)
                validateOverpassResponse(json)
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

    private fun validateOverpassResponse(json: String) {
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

    private fun routeRegionQuery(box: String): String = """
        [out:json][timeout:35];
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
        out body center;
    """.trimIndent()

    internal fun routeRegionTiles(routes: List<Route>, radiusMeters: Int = ROUTE_REGION_RADIUS_METERS): List<RegionTile> {
        if (routes.isEmpty()) return emptyList()
        val radius = radiusMeters.coerceAtLeast(10_000).toDouble()
        val span = TILE_METERS.toDouble()
        val cells = LinkedHashSet<Pair<Int, Int>>()
        val sampleSpacing = span * 0.45

        for (route in routes) {
            val samplePoints = LinkedHashMap<String, GeoPoint>()
            route.points.firstOrNull()?.let { samplePoints["start"] = it }
            Geo.sampleRoutePoints(route.points, sampleSpacing).forEachIndexed { index, p -> samplePoints["p$index"] = p }
            route.points.lastOrNull()?.let { samplePoints["end"] = it }

            for (point in samplePoints.values) {
                val (px, py) = mercatorMeters(point)
                val projectedRadius = radius / cos(Math.toRadians(point.lat.coerceIn(-85.0, 85.0))).coerceAtLeast(0.20)
                val minX = floor((px - projectedRadius) / span).toInt()
                val maxX = floor((px + projectedRadius) / span).toInt()
                val minY = floor((py - projectedRadius) / span).toInt()
                val maxY = floor((py + projectedRadius) / span).toInt()

                for (ix in minX..maxX) {
                    val left = ix * span
                    val right = left + span
                    val dx = when {
                        px < left -> left - px
                        px > right -> px - right
                        else -> 0.0
                    }
                    for (iy in minY..maxY) {
                        val bottom = iy * span
                        val top = bottom + span
                        val dy = when {
                            py < bottom -> bottom - py
                            py > top -> py - top
                            else -> 0.0
                        }
                        if (dx * dx + dy * dy <= projectedRadius * projectedRadius) cells += ix to iy
                    }
                }
            }
        }

        return cells
            .sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
            .mapNotNull { (ix, iy) -> regionTile(ix, iy, span) }
    }

    private fun regionTile(ix: Int, iy: Int, span: Double): RegionTile? {
        val left = ix * span
        val right = left + span
        val bottom = iy * span
        val top = bottom + span
        val west = Math.toDegrees(left / WEB_MERCATOR_RADIUS).coerceIn(-179.999999, 179.999999)
        val east = Math.toDegrees(right / WEB_MERCATOR_RADIUS).coerceIn(-179.999999, 179.999999)
        val south = inverseMercatorLat(bottom)
        val north = inverseMercatorLat(top)
        if (west >= east || south >= north) return null
        return RegionTile(
            ix,
            iy,
            String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", south, west, north, east)
        )
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
        const val ROUTE_REGION_RADIUS_METERS = 160_934
        private const val TILE_METERS = 90_000
        private const val WEB_MERCATOR_RADIUS = 6_378_137.0
        private const val MAX_CONCURRENCY = 2
        private const val MAX_ENDPOINTS_PER_TILE = 2
        const val DEFAULT_MAX_WAIT_MS = 75_000L
        const val BACKGROUND_MAX_WAIT_MS = 120_000L
    }
}
