package com.bggg.watchman.cameras

import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraSourceStatus
import com.bggg.watchman.data.CameraSourceResult
import com.bggg.watchman.data.CameraSyncResult
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.ProviderConfig
import com.bggg.watchman.data.Route
import com.bggg.watchman.net.HttpClient
import com.bggg.watchman.net.OperationDeadline
import com.bggg.watchman.util.Geo
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

interface CameraProvider {
    fun sync(center: GeoPoint, radiusMeters: Int): CameraSyncResult
    fun syncRouteCoverage(routes: List<Route>): CameraSyncResult
}

/**
 * OpenStreetMap/Overpass camera provider preserving the exact mandatory SafeTOC-compatible
 * live acquisition floor while identifying the actual upstream provider directly.
 *
 * SafeTOC parity behavior is intentionally special:
 * - overpass-api.de is queried first, because that is what the supplied SafeTOC build uses;
 * - the exact SafeTOC ALPR/Flock and traffic-device clauses and output limits are preserved;
 * - a mirror is used only when the canonical request fails, preventing a lagging mirror from
 *   successfully returning an older/smaller dataset and hiding a camera SafeTOC can see.
 *
 * WatchMan's extended feeds remain independent and fail over between public Overpass services.
 * All feeds are merged by stable OSM object id.
 */
class OverpassCameraProvider : CameraProvider {
    @Volatile var lastError: String? = null
        private set
    @Volatile var lastStatuses: List<CameraSourceStatus> = emptyList()
        private set

    private data class SourceSpec(
        val id: String,
        val name: String,
        val parityResultLimit: Int? = null,
        val queryBody: (String) -> String
    ) {
        val safeTocParity: Boolean get() = parityResultLimit != null
    }

    private val sources = listOf(
        SourceSpec("osm_overpass_alpr", "OpenStreetMap / Overpass — ALPR / Flock", 500) { bbox ->
            """
            node["surveillance:type"="ALPR"]($bbox);
            node["manufacturer"="Flock Safety"]($bbox);
            """.trimIndent()
        },
        SourceSpec("osm_overpass_traffic_devices", "OpenStreetMap / Overpass — traffic devices", 700) { bbox ->
            """
            node["highway"="traffic_signals"]($bbox);
            node["highway"="speed_camera"]($bbox);
            node["enforcement"="maxspeed"]($bbox);
            node["surveillance"="traffic"]($bbox);
            node["camera:type"="traffic"]($bbox);
            """.trimIndent()
        },
        SourceSpec("osm_extended_discovery", "OpenStreetMap / Overpass — extended discovery") { bbox ->
            """
            nwr["surveillance:type"~"^(ALPR|ANPR|LPR)$",i]($bbox);
            nwr["surveillance"~"^(ALPR|ANPR|LPR)$",i]($bbox);
            nwr["manufacturer"~"flock",i]($bbox);
            nwr["brand"~"flock",i]($bbox);
            nwr["operator"~"flock",i]($bbox);
            nwr["description"~"ALPR|ANPR|LPR|license.?plate|number.?plate",i]($bbox);
            nwr["highway"="speed_camera"]($bbox);
            nwr["type"="enforcement"]["enforcement"~"maxspeed|traffic_signals|red_light|average_speed|mobile_phone|distance",i]($bbox);
            nwr["enforcement"~"maxspeed|traffic_signals|red_light|average_speed|mobile_phone|distance",i]($bbox);
            nwr["enforcement:type"~"camera",i]($bbox);
            nwr["man_made"="surveillance"]($bbox);
            nwr["surveillance"="traffic"]($bbox);
            nwr["surveillance:zone"~"traffic|public_transport|parking",i]($bbox);
            nwr["camera:type"]($bbox);
            nwr["camera:usage"~"traffic|transport|highway|road",i]($bbox);
            nwr["man_made"="monitoring_station"]["monitoring:traffic"]($bbox);
            nwr["monitoring:traffic"]($bbox);
            nwr["monitoring"~"traffic",i]($bbox);
            nwr["traffic_sensor"]($bbox);
            nwr["barrier"="toll_booth"]($bbox);
            nwr["highway"="toll_gantry"]($bbox);
            nwr["toll"="yes"]["highway"="traffic_signals"]($bbox);
            """.trimIndent()
        }
    )

    override fun sync(center: GeoPoint, radiusMeters: Int): CameraSyncResult =
        syncNearbyIncremental(center, radiusMeters, null)

    /**
     * Executes all camera feeds with bounded concurrency and publishes each completed source
     * immediately. SafeTOC parity feeds are submitted first and never wait for supplemental feeds.
     */
    fun syncNearbyIncremental(
        center: GeoPoint,
        radiusMeters: Int,
        onSource: ((CameraSourceResult) -> Unit)?
    ): CameraSyncResult = syncBboxesIncremental(
        allBboxes = listOf(bbox(center, radiusMeters)),
        purpose = "nearby",
        nearbyCenter = center,
        onSource = onSource
    )

    override fun syncRouteCoverage(routes: List<Route>): CameraSyncResult =
        syncRouteCoverageIncremental(routes, null)

    fun syncRouteCoverageIncremental(
        routes: List<Route>,
        onSource: ((CameraSourceResult) -> Unit)?
    ): CameraSyncResult {
        val centers = LinkedHashMap<String, GeoPoint>()
        for (route in routes) {
            for (p in Geo.sampleRoutePoints(route.points, 10_000.0)) {
                val key = String.format(Locale.US, "%.3f:%.3f", p.lat, p.lon)
                centers.putIfAbsent(key, p)
            }
        }
        if (centers.isEmpty()) return CameraSyncResult(emptyList(), emptyList())
        val boxes = centers.values.map { bbox(it, 7000) }
        return syncBboxesIncremental(boxes, "route", null, onSource)
    }

    private fun syncBboxesIncremental(
        allBboxes: List<String>,
        purpose: String,
        nearbyCenter: GeoPoint? = null,
        onSource: ((CameraSourceResult) -> Unit)? = null
    ): CameraSyncResult {
        // Two concurrent Overpass requests prevents self-induced pressure on public endpoints.
        val pool = Executors.newFixedThreadPool(MAX_OVERPASS_CONCURRENCY)
        val futures = ArrayList<Future<SourceResult>>(sources.size)
        try {
            val completion = ExecutorCompletionService<SourceResult>(pool)
            val futureSpecs = LinkedHashMap<Future<SourceResult>, SourceSpec>()
            sources.forEachIndexed { index, spec ->
                val sourceBboxes = if (purpose == "nearby" && spec.safeTocParity && nearbyCenter != null) {
                    listOf(bbox(nearbyCenter, SAFETOC_NEARBY_RADIUS_METERS))
                } else {
                    allBboxes
                }
                val future = completion.submit(Callable { fetchSource(spec, index, sourceBboxes, purpose) })
                futures += future
                futureSpecs[future] = spec
            }

            val workflowTimeout = if (purpose == "route") ROUTE_WORKFLOW_TIMEOUT_MS else NEARBY_WORKFLOW_TIMEOUT_MS
            val deadline = OperationDeadline(workflowTimeout)
            val pending = LinkedHashSet(sources)
            val statuses = ArrayList<CameraSourceStatus>()
            val merged = LinkedHashMap<String, CameraPoi>()
            while (pending.isNotEmpty()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Overpass $purpose workflow interrupted")
                val remaining = deadline.remainingMillis()
                if (remaining <= 0L) break
                val future = try {
                    completion.poll(remaining, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                } ?: break
                val spec = futureSpecs[future]
                val result = try {
                    future.get()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                } catch (e: Exception) {
                    val cause = e.cause ?: e
                    val outputId = spec?.id?.let { if (purpose == "route") "${it}_route" else it } ?: "unknown"
                    val outputName = spec?.name?.let { if (purpose == "route") "$it · route" else it } ?: "Unknown source"
                    SourceResult(emptyList(), CameraSourceStatus(outputId, outputName, false, 0, null, cause.message, 0))
                }
                if (spec != null) pending.remove(spec)
                statuses += result.status
                for (poi in result.cameras) mergeInto(merged, poi)
                // This callback is deliberately inside the completion loop: no all-feed barrier.
                onSource?.invoke(CameraSourceResult(result.cameras, result.status))
            }
            pending.forEach { spec ->
                val outputId = if (purpose == "route") "${spec.id}_route" else spec.id
                val outputName = if (purpose == "route") "${spec.name} · route" else spec.name
                val timeoutStatus = CameraSourceStatus(outputId, outputName, false, 0, null, "Overpass $purpose workflow deadline exceeded", workflowTimeout)
                statuses += timeoutStatus
                onSource?.invoke(CameraSourceResult(emptyList(), timeoutStatus))
            }
            lastStatuses = statuses.toList()
            val failures = statuses.filterNot { it.ok }
            lastError = when {
                statuses.isEmpty() -> "No camera sources configured"
                failures.size == statuses.size -> failures.joinToString("; ") { "${it.sourceName}: ${it.error ?: "failed"}" }
                failures.isNotEmpty() -> "${failures.size}/${statuses.size} camera sources unavailable"
                else -> null
            }
            return CameraSyncResult(merged.values.toList(), statuses)
        } finally {
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
        }
    }

    private data class SourceResult(val cameras: List<CameraPoi>, val status: CameraSourceStatus)

    private fun fetchSource(spec: SourceSpec, sourceIndex: Int, bboxes: List<String>, purpose: String): SourceResult {
        val started = System.currentTimeMillis()
        val all = LinkedHashMap<String, CameraPoi>()
        var successfulBatches = 0
        var failedBatches = 0
        var lastFailure: Exception? = null
        var lastEndpoint: String? = null

        // Nearby and route-corridor snapshots are different query scopes. Give route coverage a
        // separate persisted source id so a successful corridor refresh cannot erase the current
        // nearby cache (and vice versa). CameraDatabase deduplicates identical OSM identities at
        // read time, so overlapping nearby/route records still render and alert only once.
        val outputSpec = if (purpose == "route") {
            spec.copy(id = "${spec.id}_route", name = "${spec.name} · route")
        } else {
            spec
        }

        // SafeTOC applies its result limit to one nearby bbox. Preserve that behavior for every
        // route-coverage bbox as well instead of combining many boxes under one global cap.
        val batches = if (spec.safeTocParity) bboxes.map { listOf(it) } else bboxes.chunked(8)

        for (batch in batches) {
            val query = queryFor(spec, batch)
            var batchWorked = false

            if (spec.safeTocParity) {
                // Exact SafeTOC endpoint/query form first. This is the parity path.
                val primary = ProviderConfig.OVERPASS_ENDPOINTS.firstOrNull()
                if (primary != null) {
                    lastEndpoint = primary
                    try {
                        val url = "$primary?data=${HttpClient.enc(query)}"
                        val json = HttpClient.get(url, timeoutMs = 25_000, attempts = 1)
                        parse(json, outputSpec).forEach { mergeInto(all, it) }
                        successfulBatches++
                        batchWorked = true
                    } catch (e: Exception) {
                        lastFailure = e
                    }
                }

                // Only fail over if SafeTOC's canonical endpoint failed. A successful but lagging
                // mirror must never replace a successful primary response.
                if (!batchWorked) {
                    for (endpoint in ProviderConfig.OVERPASS_ENDPOINTS.drop(1)) {
                        lastEndpoint = endpoint
                        try {
                            val json = HttpClient.postForm(endpoint, mapOf("data" to query), timeoutMs = 25_000, attempts = 1)
                            parse(json, outputSpec).forEach { mergeInto(all, it) }
                            successfulBatches++
                            batchWorked = true
                            break
                        } catch (e: Exception) {
                            lastFailure = e
                        }
                    }
                }
            } else {
                // Extended feeds use independent endpoint rotation and failover.
                for (endpoint in rotatedEndpoints(sourceIndex + successfulBatches + failedBatches)) {
                    lastEndpoint = endpoint
                    try {
                        val json = HttpClient.postForm(endpoint, mapOf("data" to query), timeoutMs = 25_000, attempts = 1)
                        parse(json, outputSpec).forEach { mergeInto(all, it) }
                        successfulBatches++
                        batchWorked = true
                        break
                    } catch (e: Exception) {
                        lastFailure = e
                    }
                }
            }

            if (!batchWorked) failedBatches++
        }

        // A multi-bbox route result is a single persisted snapshot. If even one batch failed,
        // the snapshot is incomplete and must NOT replace the last-known complete source rows.
        // Partial cameras are still returned for diagnostics, but status.ok remains false so the
        // caller preserves the previous SQLite snapshot.
        val ok = bboxes.isEmpty() || failedBatches == 0
        val error = when {
            failedBatches == 0 -> null
            successfulBatches > 0 -> "$failedBatches data batch(es) unavailable; last complete snapshot retained"
            else -> lastFailure?.message ?: "Provider unavailable"
        }
        return SourceResult(
            all.values.toList(),
            CameraSourceStatus(
                sourceId = outputSpec.id,
                sourceName = outputSpec.name,
                ok = ok,
                count = all.size,
                endpoint = lastEndpoint,
                error = error,
                elapsedMs = System.currentTimeMillis() - started
            )
        )
    }

    private fun queryFor(spec: SourceSpec, boxes: List<String>): String {
        val body = buildString {
            for (box in boxes) {
                append(spec.queryBody(box))
                append('\n')
            }
        }
        return if (spec.parityResultLimit != null) {
            "[out:json][timeout:25];($body);out body ${spec.parityResultLimit};"
        } else {
            "[out:json][timeout:25];($body);out body center;"
        }
    }

    internal fun safeTocAlprQuery(box: String): String = queryFor(sources[0], listOf(box))
    internal fun safeTocTrafficQuery(box: String): String = queryFor(sources[1], listOf(box))

    private fun rotatedEndpoints(offset: Int): List<String> {
        val src = ProviderConfig.OVERPASS_ENDPOINTS
        if (src.isEmpty()) return emptyList()
        return List(src.size) { i -> src[(i + offset) % src.size] }
    }

    internal fun parse(
        json: String,
        sourceId: String = "osm_overpass",
        sourceName: String = "OpenStreetMap / Overpass"
    ): List<CameraPoi> = parse(json, SourceSpec(sourceId, sourceName) { "" })

    private fun parse(json: String, spec: SourceSpec): List<CameraPoi> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("elements")
        val out = LinkedHashMap<String, CameraPoi>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val osmType = e.optString("type", "node")
            val osmId = e.optLong("id", -1L)
            if (osmId < 0) continue
            val id = "$osmType/$osmId"

            val lat: Double
            val lon: Double
            if (e.has("lat") && e.has("lon")) {
                lat = e.getDouble("lat")
                lon = e.getDouble("lon")
            } else {
                val c = e.optJSONObject("center") ?: continue
                lat = c.optDouble("lat", Double.NaN)
                lon = c.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
            }

            val tagsJson = e.optJSONObject("tags") ?: JSONObject()
            val tags = LinkedHashMap<String, String>()
            val keys = tagsJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = tagsJson.optString(k).trim()
                if (v.isNotEmpty()) tags[k] = v
            }
            tags["data_source"] = "OpenStreetMap"
            tags["retrieved_via"] = "Overpass API"

            val classified = CameraClassifier.classify(tags)
            val name = firstNonBlank(
                tags["name"], tags["ref"], tags["operator"], tags["manufacturer"], tags["brand"]
            )
            val road = firstNonBlank(
                tags["addr:street"], tags["road"], tags["location"], tags["destination"]
            )
            val direction = firstNonBlank(tags["direction"], tags["camera:direction"])
            val osmUrl = "https://www.openstreetmap.org/$id"

            val poi = CameraPoi(
                id = id,
                type = classified.type,
                point = GeoPoint(lat, lon),
                name = name,
                road = road,
                direction = direction,
                operator = tags["operator"],
                manufacturer = tags["manufacturer"] ?: tags["brand"],
                source = spec.name,
                sourceId = spec.id,
                sourceUrl = osmUrl,
                sourceRecordId = id,
                confidence = classified.confidence,
                tags = tags
            )
            mergeInto(out, poi)
        }
        return out.values.toList()
    }

    private fun mergeInto(target: MutableMap<String, CameraPoi>, poi: CameraPoi) {
        val old = target[poi.id]
        target[poi.id] = if (old == null) poi else mergePoi(old, poi)
    }

    private fun mergePoi(a: CameraPoi, b: CameraPoi): CameraPoi {
        val preferred = if (CameraClassifier.priority(b.type) > CameraClassifier.priority(a.type)) b else a
        val other = if (preferred === a) b else a
        return preferred.copy(
            name = preferred.name ?: other.name,
            road = preferred.road ?: other.road,
            direction = preferred.direction ?: other.direction,
            operator = preferred.operator ?: other.operator,
            manufacturer = preferred.manufacturer ?: other.manufacturer,
            confidence = max(preferred.confidence, other.confidence),
            source = if (preferred.source == other.source) preferred.source else "${preferred.source}; ${other.source}",
            sourceId = if (preferred.sourceId == other.sourceId) preferred.sourceId else "${preferred.sourceId},${other.sourceId}",
            tags = LinkedHashMap<String, String>().apply {
                putAll(other.tags)
                putAll(preferred.tags)
            }
        )
    }

    private fun bbox(center: GeoPoint, radiusMeters: Int): String {
        val km = radiusMeters.coerceAtLeast(500) / 1000.0
        val dLat = max(0.01, km / 111.0)
        val dLng = max(0.01, km / max(20.0, 111.0 * cos(Math.toRadians(center.lat))))
        val south = (center.lat - dLat).coerceIn(-89.9, 89.9)
        val north = (center.lat + dLat).coerceIn(-89.9, 89.9)
        val west = (center.lon - dLng).coerceIn(-179.9, 179.9)
        val east = (center.lon + dLng).coerceIn(-179.9, 179.9)
        return String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", south, west, north, east)
    }

    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private companion object {
        /** Exact default radius used by the supplied SafeTOC native build for its nearby feeds. */
        const val SAFETOC_NEARBY_RADIUS_METERS = 12_000
        const val MAX_OVERPASS_CONCURRENCY = 2
        private const val NEARBY_WORKFLOW_TIMEOUT_MS = 45_000L
        private const val ROUTE_WORKFLOW_TIMEOUT_MS = 60_000L
    }
}
