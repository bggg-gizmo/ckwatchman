package com.bggg.watchman.cameras

import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraSourceResult
import com.bggg.watchman.data.CameraSourceStatus
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.ProviderConfig
import com.bggg.watchman.net.HttpClient
import com.bggg.watchman.net.OperationDeadline
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * v0.2.7 national bootstrap: state -> county-equivalent -> SafeTOC-sized locale cells.
 *
 * The proven live v0.1.8 nearby provider remains completely separate. The county/locale layer is
 * only a geographic scaling wrapper around SafeTOC's acquisition contract. Every locale performs
 * the exact SafeTOC ALPR/Flock and traffic-device requests first: canonical overpass-api.de GET,
 * exact query text/limits, one independent request per family, and immediate successful
 * publication. Those proven SafeTOC families alone determine first-run locale/county/state
 * completion. WatchMan-only families remain available as a separate best-effort additive pass so
 * they can enrich the cache without blocking or delaying the mandatory SafeTOC acquisition pass.
 */
class NationalCameraBootstrapProvider(
    private val countyCatalog: CountyCatalogProvider = CountyCatalogProvider()
) : AutoCloseable {
    private val safeTocThreadCounter = AtomicInteger()
    private val safeTocExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "watchman-national-safetoc-${safeTocThreadCounter.incrementAndGet()}").apply { isDaemon = true }
    }

    data class Region(val code: String, val name: String, val fips: String) {
        val sourceId: String get() = "osm_national_${code.lowercase(Locale.US).replace('-', '_')}"
    }

    data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double)

    data class LocaleCell(
        val county: CountyCatalogProvider.County,
        val baseIndex: Int,
        val baseTotal: Int,
        val south: Double,
        val westWork: Double,
        val north: Double,
        val eastWork: Double,
        val subdivisionPath: String = ""
    ) {
        val baseLocaleId: String get() = "national_v026_${county.geoid}_locale_${(baseIndex + 1).toString().padStart(4, '0')}"
        val cellId: String get() = if (subdivisionPath.isEmpty()) baseLocaleId else "${baseLocaleId}_$subdivisionPath"
        val depth: Int get() = subdivisionPath.length

        fun queryBounds(): List<Bounds> {
            val west = westWork
            val east = eastWork
            return when {
                west >= 180.0 -> listOf(Bounds(south, west - 360.0, north, (east - 360.0).coerceAtMost(180.0)))
                east <= 180.0 -> listOf(Bounds(south, west.coerceAtLeast(-180.0), north, east))
                else -> listOf(
                    Bounds(south, west, north, 180.0),
                    Bounds(south, -180.0, north, east - 360.0)
                )
            }
        }
    }

    enum class Family(
        val key: String,
        val displayName: String,
        val saturationLimit: Int?,
        val safeTocExact: Boolean
    ) {
        ALPR("deflock_overpass_alpr", "DeFlock / OSM ALPR Cameras", 500, true),
        TRAFFIC("osm_traffic_devices_near_me", "OpenStreetMap Traffic Devices", 700, true),
        SURVEILLANCE("watchman_surveillance", "Surveillance / CCTV", null, false),
        ENFORCEMENT("watchman_enforcement", "Enforcement / cameras", null, false),
        SENSORS_TOLL("watchman_sensors_toll", "Traffic sensors / toll", null, false)
    }

    data class FamilyResult(
        val family: Family,
        val sourceResult: CameraSourceResult,
        val saturated: Boolean,
        val snapshotId: String
    )

    data class LocaleProgress(
        val region: Region,
        val county: CountyCatalogProvider.County,
        val completedLocales: Int,
        val totalLocales: Int,
        val currentLocaleNumber: Int,
        val subdivisionDepth: Int,
        val countyDevicesThisRun: Int
    )

    data class Progress(
        val completedCounties: Int,
        val totalCounties: Int,
        val successfulCounties: Int,
        val failedCounties: Int,
        val currentRegion: Region?,
        val currentCounty: CountyCatalogProvider.County?,
        val totalCamerasThisRun: Int,
        val lastStatus: CameraSourceStatus?
    )

    private val parser = OverpassCameraProvider()

    fun syncMissing(
        alreadySuccessfulRegions: Set<String>,
        alreadySuccessfulCounties: Set<String>,
        alreadySuccessfulLocales: Set<String>,
        alreadySuccessfulSources: Set<String>,
        preferredRegionSourceId: () -> String? = { null },
        shouldContinue: () -> Boolean = { true },
        beforeNetwork: () -> Unit = {},
        onNationalCount: (Int) -> Unit = {},
        onStateStart: (Region, Int) -> Unit = { _, _ -> },
        onCountyStart: (Region, CountyCatalogProvider.County, Int, Progress) -> Unit,
        onFamilyResult: (Region, CountyCatalogProvider.County, LocaleCell, FamilyResult, LocaleProgress) -> Unit,
        onLocaleResolved: (Region, CountyCatalogProvider.County, Boolean, LocaleProgress) -> Unit,
        onCountyResult: (Region, CountyCatalogProvider.County, Boolean, Int, String?, Progress) -> Unit,
        onStateResult: (Region, Boolean, Int, String?, Progress) -> Unit
    ): Progress {
        val expectedTotal = runCatching {
            beforeNetwork()
            countyCatalog.fetchNationalCountyCount()
        }.getOrDefault(CountyCatalogProvider.FALLBACK_COUNTY_EQUIVALENT_TOTAL)
        onNationalCount(expectedTotal)

        val successfulCountyIds = LinkedHashSet(alreadySuccessfulCounties)
        val successfulLocaleIds = LinkedHashSet(alreadySuccessfulLocales)
        val successfulSourceIds = LinkedHashSet(alreadySuccessfulSources)
        var completedCounties = successfulCountyIds.size
        var successfulCounties = successfulCountyIds.size
        var failedCounties = 0
        var totalCameras = 0
        var lastStatus: CameraSourceStatus? = null

        val pendingRegions = REGIONS.filterTo(ArrayList()) {
            it.sourceId !in alreadySuccessfulRegions
        }
        var stateSequence = 0
        while (pendingRegions.isNotEmpty()) {
            if (!shouldContinue()) break
            val preferredId = preferredRegionSourceId()
            val preferredIndex = pendingRegions.indexOfFirst { it.sourceId == preferredId }
            val region = pendingRegions.removeAt(if (preferredIndex >= 0) preferredIndex else 0)
            val stateIndex = stateSequence++
            val counties = try {
                beforeNetwork()
                countyCatalog.fetchCounties(region)
            } catch (e: Exception) {
                val p = Progress(completedCounties, expectedTotal, successfulCounties, failedCounties, region, null, totalCameras,
                    CameraSourceStatus(region.sourceId, "U.S. Census county catalog — ${region.name}", false, 0, null, e.message, 0))
                onStateResult(region, false, 0, e.message ?: "County catalog unavailable", p)
                continue
            }
            onStateStart(region, counties.size)
            var stateDevices = 0
            var stateOk = true
            var stateError: String? = null
            var deferredForPreferredRegion = false

            for ((countyOrdinal, county) in counties.withIndex()) {
                val preferredBeforeCounty = preferredRegionSourceId()
                if (preferredBeforeCounty != null &&
                    preferredBeforeCounty != region.sourceId &&
                    pendingRegions.any { it.sourceId == preferredBeforeCounty }) {
                    deferredForPreferredRegion = true
                    break
                }
                if (!shouldContinue()) break
                if (county.countyId in successfulCountyIds) continue

                val locales = buildLocales(county)
                var completedLocales = 0
                var countyDevices = 0
                var countyError: String? = null
                var countyOk = true
                var fetchOrdinal = stateIndex * 1_000_000 + countyOrdinal * 10_000

                fun progress(cell: LocaleCell): LocaleProgress = LocaleProgress(
                    region, county, completedLocales, locales.size,
                    (cell.baseIndex + 1).coerceIn(1, locales.size.coerceAtLeast(1)),
                    cell.depth, countyDevices
                )

                onCountyStart(
                    region,
                    county,
                    locales.size,
                    Progress(completedCounties, expectedTotal, successfulCounties, failedCounties, region, county, totalCameras, lastStatus)
                )

                fun hasSuccessfulDescendant(cell: LocaleCell, family: Family): Boolean {
                    val prefix = cell.cellId + "_"
                    return successfulSourceIds.any { it.startsWith(prefix) && it.endsWith("_${family.key}") }
                }

                fun resolveSafeTocCell(cell: LocaleCell, families: Collection<Family>): Boolean {
                    if (!shouldContinue()) return false
                    val pending = families.filter { it.safeTocExact && snapshotId(cell, it) !in successfulSourceIds }
                    if (pending.isEmpty()) return true

                    // SafeTOC Native starts the ALPR/Flock and traffic-device fetches independently.
                    // Repeat that behavior per locale: two canonical GETs may be in flight together,
                    // and each completed source is published immediately in completion order.
                    val completion = ExecutorCompletionService<FamilyResult>(safeTocExecutor)
                    val futures = ArrayList<Future<FamilyResult>>(pending.size)
                    try {
                        val futureFamilies = LinkedHashMap<Future<FamilyResult>, Family>()
                        for (family in pending) {
                            val ordinal = fetchOrdinal++
                            val future = completion.submit(Callable {
                                beforeNetwork()
                                if (!shouldContinue()) throw InterruptedException()
                                fetchFamily(cell, family, ordinal)
                            })
                            futures += future
                            futureFamilies[future] = family
                        }

                        val deadline = OperationDeadline(SAFETOC_WORKFLOW_TIMEOUT_MS)
                        val pendingFamilies = LinkedHashSet(pending)
                        val unresolvedForChildren = LinkedHashSet<Family>()
                        var hardFailure = false
                        while (pendingFamilies.isNotEmpty()) {
                            if (!shouldContinue()) return false
                            if (Thread.currentThread().isInterrupted) return false
                            val remaining = deadline.remainingMillis()
                            if (remaining <= 0L) break
                            val future = try {
                                completion.poll(minOf(remaining, WORKFLOW_POLL_SLICE_MS), TimeUnit.MILLISECONDS)
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return false
                            } ?: continue
                            val family = futureFamilies[future] ?: continue
                            pendingFamilies.remove(family)
                            var executionFailure: Exception? = null
                            val fetched = try {
                                future.get()
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return false
                            } catch (e: Exception) {
                                executionFailure = e.cause as? Exception ?: e
                                val snapshot = snapshotId(cell, family)
                                val status = CameraSourceStatus(
                                    snapshot,
                                    "SafeTOC ${family.displayName} — ${cell.county.name}, ${cell.county.stateName}",
                                    false,
                                    0,
                                    SAFETOC_CANONICAL_OVERPASS,
                                    executionFailure?.message ?: "SafeTOC source unavailable",
                                    0L
                                )
                                FamilyResult(family, CameraSourceResult(emptyList(), status), false, snapshot)
                            }
                            lastStatus = fetched.sourceResult.status
                            val cameras = fetched.sourceResult.cameras
                            if (cameras.isNotEmpty()) {
                                countyDevices += cameras.size
                                stateDevices += cameras.size
                                totalCameras += cameras.size
                            }
                            onFamilyResult(region, county, cell, fetched, progress(cell))

                            if (executionFailure != null) {
                                hardFailure = true
                                countyError = executionFailure?.message ?: "SafeTOC source unavailable"
                            } else if (fetched.sourceResult.status.ok && !fetched.saturated) {
                                successfulSourceIds += fetched.snapshotId
                            } else if (canSubdivide(cell) && (fetched.saturated || shouldSubdivideAfterFailure(fetched.sourceResult.status))) {
                                unresolvedForChildren += fetched.family
                            } else {
                                hardFailure = true
                                countyError = fetched.sourceResult.status.error
                                    ?: if (fetched.saturated) "Result limit reached" else "SafeTOC source unavailable"
                            }
                        }

                        if (pendingFamilies.isNotEmpty()) {
                            hardFailure = true
                            countyError = "SafeTOC family workflow deadline exceeded"
                            pendingFamilies.forEach { family ->
                                val snapshot = snapshotId(cell, family)
                                val status = CameraSourceStatus(
                                    snapshot,
                                    "SafeTOC ${family.displayName} — ${cell.county.name}, ${cell.county.stateName}",
                                    false,
                                    0,
                                    SAFETOC_CANONICAL_OVERPASS,
                                    countyError,
                                    SAFETOC_WORKFLOW_TIMEOUT_MS
                                )
                                lastStatus = status
                                onFamilyResult(region, county, cell, FamilyResult(family, CameraSourceResult(emptyList(), status), false, snapshot), progress(cell))
                            }
                        }

                        if (unresolvedForChildren.isNotEmpty()) {
                            if (!canSubdivide(cell)) return false
                            for (child in subdivide(cell)) {
                                if (!resolveSafeTocCell(child, unresolvedForChildren)) return false
                            }
                        }
                        return !hardFailure
                    } finally {
                        futures.forEach { if (!it.isDone) it.cancel(true) }
                    }
                }

                for (base in locales) {
                    val preferredBeforeLocale = preferredRegionSourceId()
                    if (preferredBeforeLocale != null &&
                        preferredBeforeLocale != region.sourceId &&
                        pendingRegions.any { it.sourceId == preferredBeforeLocale }) {
                        deferredForPreferredRegion = true
                        break
                    }
                    if (!shouldContinue()) return Progress(completedCounties, expectedTotal, successfulCounties, failedCounties, region, county, totalCameras, lastStatus)
                    if (base.baseLocaleId in successfulLocaleIds) {
                        completedLocales++
                        continue
                    }
                    // First-run completion is earned only from the proven SafeTOC local scan:
                    // independent canonical ALPR/Flock + traffic-device GETs for this locale.
                    // WatchMan-only discovery is intentionally not part of this completion gate.
                    val safeTocOk = resolveSafeTocCell(base, SAFETOC_FAMILY_ORDER)
                    if (safeTocOk) successfulLocaleIds += base.baseLocaleId else {
                        countyOk = false
                        countyError = countyError ?: "Locale ${base.baseIndex + 1}/${locales.size} unresolved"
                    }
                    completedLocales++
                    onLocaleResolved(region, county, safeTocOk, progress(base))
                    if (shouldContinue()) Thread.sleep(INTER_LOCALE_DELAY_MS)
                }
                if (deferredForPreferredRegion) break

                if (!shouldContinue()) break
                completedCounties++
                if (countyOk && completedLocales == locales.size) {
                    successfulCounties++
                    successfulCountyIds += county.countyId
                } else {
                    failedCounties++
                    stateOk = false
                    stateError = countyError ?: "County incomplete"
                }
                val p = Progress(completedCounties, expectedTotal, successfulCounties, failedCounties, region, county, totalCameras, lastStatus)
                onCountyResult(region, county, countyOk && completedLocales == locales.size, countyDevices, countyError, p)
                if (shouldContinue()) Thread.sleep(INTER_COUNTY_DELAY_MS)
            }
            if (deferredForPreferredRegion) {
                pendingRegions += region
                continue
            }

            if (!shouldContinue()) break
            if (counties.any { it.countyId !in successfulCountyIds }) stateOk = false
            val p = Progress(completedCounties, expectedTotal, successfulCounties, failedCounties, region, null, totalCameras, lastStatus)
            onStateResult(region, stateOk, stateDevices, stateError, p)
            if (shouldContinue()) Thread.sleep(INTER_STATE_DELAY_MS)
        }

        return Progress(completedCounties, expectedTotal, successfulCounties, failedCounties, null, null, totalCameras, lastStatus)
    }

    /**
     * Best-effort national enrichment using WatchMan-only source families. This pass is deliberately
     * separate from [syncMissing]: failures here never revoke SafeTOC-earned locale/county/state
     * completion and never erase a previously successful source snapshot.
     */
    fun syncAdditiveMissing(
        alreadySuccessfulSources: Set<String>,
        shouldContinue: () -> Boolean = { true },
        beforeNetwork: () -> Unit = {},
        onFamilyResult: (Region, CountyCatalogProvider.County, LocaleCell, FamilyResult, LocaleProgress) -> Unit
    ): Int {
        val successfulSourceIds = alreadySuccessfulSources.toMutableSet()
        var totalCameras = 0

        for ((stateIndex, region) in REGIONS.withIndex()) {
            if (!shouldContinue()) break
            val counties = try {
                beforeNetwork()
                countyCatalog.fetchCounties(region)
            } catch (_: Exception) {
                continue
            }

            for ((countyOrdinal, county) in counties.withIndex()) {
                if (!shouldContinue()) break
                val locales = buildLocales(county)
                var completedLocales = 0
                var countyDevices = 0
                var fetchOrdinal = stateIndex * 1_000_000 + countyOrdinal * 10_000 + 5_000

                fun progress(cell: LocaleCell): LocaleProgress = LocaleProgress(
                    region, county, completedLocales, locales.size,
                    (cell.baseIndex + 1).coerceIn(1, locales.size.coerceAtLeast(1)),
                    cell.depth, countyDevices
                )

                fun hasSuccessfulDescendant(cell: LocaleCell, family: Family): Boolean {
                    val prefix = cell.cellId + "_"
                    return successfulSourceIds.any { it.startsWith(prefix) && it.endsWith("_${family.key}") }
                }

                fun resolveAdditiveCell(cell: LocaleCell, families: Collection<Family>): Boolean {
                    if (!shouldContinue()) return false
                    val unresolvedForChildren = LinkedHashSet<Family>()
                    var allResolved = true

                    for (family in families) {
                        if (!shouldContinue()) return false
                        if (family.safeTocExact) continue
                        val snapshotId = snapshotId(cell, family)
                        if (snapshotId in successfulSourceIds) continue

                        if (hasSuccessfulDescendant(cell, family) && canSubdivide(cell)) {
                            unresolvedForChildren += family
                            continue
                        }

                        val fetched = try {
                            beforeNetwork()
                            if (!shouldContinue()) return false
                            fetchFamily(cell, family, fetchOrdinal++)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return false
                        } catch (_: Exception) {
                            allResolved = false
                            continue
                        }

                        val cameras = fetched.sourceResult.cameras
                        if (cameras.isNotEmpty()) {
                            countyDevices += cameras.size
                            totalCameras += cameras.size
                        }
                        onFamilyResult(region, county, cell, fetched, progress(cell))

                        if (fetched.sourceResult.status.ok && !fetched.saturated) {
                            successfulSourceIds += fetched.snapshotId
                        } else if (canSubdivide(cell) && (fetched.saturated || shouldSubdivideAfterFailure(fetched.sourceResult.status))) {
                            unresolvedForChildren += family
                        } else {
                            allResolved = false
                        }
                        if (shouldContinue()) Thread.sleep(INTER_FAMILY_DELAY_MS)
                    }

                    if (unresolvedForChildren.isNotEmpty()) {
                        if (!canSubdivide(cell)) return false
                        for (child in subdivide(cell)) {
                            if (!resolveAdditiveCell(child, unresolvedForChildren)) allResolved = false
                        }
                    }
                    return allResolved
                }

                for (base in locales) {
                    if (!shouldContinue()) return totalCameras
                    resolveAdditiveCell(base, ADDITIVE_FAMILY_ORDER)
                    completedLocales++
                    if (shouldContinue()) Thread.sleep(INTER_LOCALE_DELAY_MS)
                }
                if (shouldContinue()) Thread.sleep(INTER_COUNTY_DELAY_MS)
            }
            if (shouldContinue()) Thread.sleep(INTER_STATE_DELAY_MS)
        }
        return totalCameras
    }

    fun buildLocales(county: CountyCatalogProvider.County): List<LocaleCell> {
        val centers = LinkedHashMap<String, Pair<Double, Double>>()
        for (rawRing in county.rings) {
            if (rawRing.size < 3) continue
            val span = rawRing.maxOf { it.lon } - rawRing.minOf { it.lon }
            val shift = span > 180.0
            val ring = rawRing.map { p -> GeoPoint(p.lat, if (shift && p.lon < 0.0) p.lon + 360.0 else p.lon) }
            val south = ring.minOf { it.lat }
            val north = ring.maxOf { it.lat }
            val west = ring.minOf { it.lon }
            val east = ring.maxOf { it.lon }
            val latStep = LOCALE_STEP_KM / KM_PER_DEG_LAT
            var lat = south + latStep / 2.0
            if (lat > north) lat = (south + north) / 2.0
            while (lat <= north + latStep / 2.0) {
                val lonScale = (KM_PER_DEG_LAT * cos(Math.toRadians(lat))).coerceAtLeast(20.0)
                val lonStep = LOCALE_STEP_KM / lonScale
                var lon = west + lonStep / 2.0
                if (lon > east) lon = (west + east) / 2.0
                while (lon <= east + lonStep / 2.0) {
                    val halfLat = LOCALE_RADIUS_KM / KM_PER_DEG_LAT
                    val halfLon = LOCALE_RADIUS_KM / lonScale
                    val boxSouth = lat - halfLat
                    val boxNorth = lat + halfLat
                    val boxWest = lon - halfLon
                    val boxEast = lon + halfLon
                    if (boxIntersectsRing(boxSouth, boxWest, boxNorth, boxEast, ring)) {
                        val key = "${(lat * 10000).roundToInt()}:${(lon * 10000).roundToInt()}"
                        centers[key] = lat to lon
                    }
                    lon += lonStep
                }
                lat += latStep
            }
        }
        if (centers.isEmpty()) {
            val all = county.rings.flatten()
            if (all.isNotEmpty()) centers["fallback"] = all.map { it.lat }.average() to all.map { it.lon }.average()
        }
        val sorted = centers.values.sortedWith(compareBy<Pair<Double, Double>> { it.first }.thenBy { it.second })
        return sorted.mapIndexed { index, (lat, lon) ->
            val lonScale = (KM_PER_DEG_LAT * cos(Math.toRadians(lat))).coerceAtLeast(20.0)
            val halfLat = LOCALE_RADIUS_KM / KM_PER_DEG_LAT
            val halfLon = LOCALE_RADIUS_KM / lonScale
            LocaleCell(county, index, sorted.size, lat - halfLat, lon - halfLon, lat + halfLat, lon + halfLon)
        }
    }

    private fun boxIntersectsRing(south: Double, west: Double, north: Double, east: Double, ring: List<GeoPoint>): Boolean {
        val centerLat = (south + north) / 2.0
        val centerLon = (west + east) / 2.0
        if (pointInRing(centerLat, centerLon, ring)) return true
        if (pointInRing(south, west, ring) || pointInRing(south, east, ring) || pointInRing(north, west, ring) || pointInRing(north, east, ring)) return true
        return ring.any { it.lat in south..north && it.lon in west..east }
    }

    private fun pointInRing(lat: Double, lon: Double, ring: List<GeoPoint>): Boolean {
        var inside = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            val intersects = ((a.lat > lat) != (b.lat > lat)) &&
                (lon < (b.lon - a.lon) * (lat - a.lat) / ((b.lat - a.lat).takeIf { it != 0.0 } ?: 1e-12) + a.lon)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    private fun fetchFamily(cell: LocaleCell, family: Family, endpointOffset: Int): FamilyResult {
        val started = System.currentTimeMillis()
        val snapshotId = snapshotId(cell, family)
        val sourceName = if (family.safeTocExact) {
            "SafeTOC ${family.displayName} — ${cell.county.name}, ${cell.county.stateName}"
        } else {
            "WatchMan additive ${family.displayName} — ${cell.county.name}, ${cell.county.stateName}"
        }
        val merged = LinkedHashMap<String, CameraPoi>()
        var lastEndpoint: String? = null
        var lastError: Exception? = null
        var saturated = false

        for ((boxIndex, bounds) in cell.queryBounds().withIndex()) {
            val query = familyQuery(family, bounds)
            var worked = false
            val endpoints = if (family.safeTocExact) {
                // SafeTOC Native 1.3.4 generates one GET URL on canonical overpass-api.de.
                // The national county/locale wrapper does not substitute a mirror for this source.
                listOf(SAFETOC_CANONICAL_OVERPASS)
            } else {
                // Additive WatchMan discovery may fail over, but canonical still goes first and
                // these calls never gate or replace a successful SafeTOC-family snapshot.
                additiveEndpoints(endpointOffset + boxIndex)
            }

            for (endpoint in endpoints) {
                lastEndpoint = endpoint
                try {
                    val url = "$endpoint?data=${HttpClient.enc(query)}"
                    val json = HttpClient.get(
                        url,
                        timeoutMs = if (family.safeTocExact) SAFETOC_HTTP_TIMEOUT_MS else ADDITIVE_HTTP_TIMEOUT_MS,
                        attempts = 1
                    )
                    validateResponse(json)
                    val parsed = parser.parse(json, snapshotId, sourceName)
                    if (family.saturationLimit != null && parsed.size >= family.saturationLimit) saturated = true
                    parsed.forEach { poi -> merged[poi.id] = poi }
                    worked = true
                    break
                } catch (e: Exception) {
                    lastError = e
                }
            }
            if (!worked) {
                val status = CameraSourceStatus(
                    snapshotId,
                    sourceName,
                    false,
                    merged.size,
                    lastEndpoint,
                    lastError?.message ?: "Provider unavailable",
                    System.currentTimeMillis() - started
                )
                return FamilyResult(family, CameraSourceResult(merged.values.toList(), status), saturated, snapshotId)
            }
        }

        val cameras = merged.values.toList()
        val status = CameraSourceStatus(
            snapshotId,
            sourceName,
            true,
            cameras.size,
            lastEndpoint,
            if (saturated) "Result limit reached; refining locale" else null,
            System.currentTimeMillis() - started
        )
        return FamilyResult(family, CameraSourceResult(cameras, status), saturated, snapshotId)
    }

    private fun familyQuery(family: Family, b: Bounds): String {
        val box = "${b.south},${b.west},${b.north},${b.east}"
        return when (family) {
            Family.ALPR -> """
                [out:json][timeout:25];
                (node["surveillance:type"="ALPR"]($box);node["manufacturer"="Flock Safety"]($box););
                out body 500;
            """.trimIndent()
            Family.TRAFFIC -> """
                [out:json][timeout:25];
                (node["highway"="traffic_signals"]($box);node["highway"="speed_camera"]($box);node["enforcement"="maxspeed"]($box);node["surveillance"="traffic"]($box);node["camera:type"="traffic"]($box););
                out body 700;
            """.trimIndent()
            Family.SURVEILLANCE -> """
                [out:json][timeout:25];
                (nwr["surveillance"]($box);nwr["man_made"="surveillance"]($box););
                out body center qt;
            """.trimIndent()
            Family.ENFORCEMENT -> """
                [out:json][timeout:25];
                (
                  nwr["type"="enforcement"]($box);nwr["enforcement"]($box);nwr["enforcement:type"]($box);
                  nwr["camera:type"]($box);nwr["camera:usage"]($box);
                  nwr["surveillance:type"~"^(ALPR|ANPR|LPR)$",i]($box);
                  nwr["manufacturer"~"flock",i]($box);nwr["brand"~"flock",i]($box);nwr["operator"~"flock",i]($box);
                  nwr["description"~"ALPR|ANPR|LPR|license.?plate|number.?plate",i]($box);
                );
                out body center qt;
            """.trimIndent()
            Family.SENSORS_TOLL -> """
                [out:json][timeout:25];
                (
                  nwr["man_made"="monitoring_station"]["monitoring:traffic"]($box);
                  nwr["monitoring:traffic"]($box);nwr["monitoring"~"traffic",i]($box);nwr["traffic_sensor"]($box);
                  nwr["barrier"="toll_booth"]($box);nwr["highway"="toll_gantry"]($box);
                  nwr["toll"="yes"]["highway"="traffic_signals"]($box);
                );
                out body center qt;
            """.trimIndent()
        }
    }

    private fun snapshotId(cell: LocaleCell, family: Family): String = "${cell.cellId}_${family.key}"

    private fun canSubdivide(cell: LocaleCell): Boolean = cell.depth < MAX_SUBDIVISION_DEPTH &&
        ((cell.north - cell.south) > MIN_CELL_DEGREES || (cell.eastWork - cell.westWork) > MIN_CELL_DEGREES)

    private fun subdivide(cell: LocaleCell): List<LocaleCell> {
        val midLat = (cell.south + cell.north) / 2.0
        val midLon = (cell.westWork + cell.eastWork) / 2.0
        val p = cell.subdivisionPath
        return listOf(
            cell.copy(north = midLat, eastWork = midLon, subdivisionPath = p + "0"),
            cell.copy(westWork = midLon, north = midLat, subdivisionPath = p + "1"),
            cell.copy(south = midLat, eastWork = midLon, subdivisionPath = p + "2"),
            cell.copy(south = midLat, westWork = midLon, subdivisionPath = p + "3")
        )
    }

    private fun shouldSubdivideAfterFailure(status: CameraSourceStatus): Boolean {
        val error = (status.error ?: "").lowercase(Locale.US)
        if ("parse error" in error || "http 400" in error || "bad request" in error) return false
        if ("http 401" in error || "http 403" in error) return false
        return true
    }

    private fun additiveEndpoints(offset: Int): List<String> {
        val configured = ProviderConfig.OVERPASS_ENDPOINTS
        if (configured.isEmpty()) return emptyList()
        val canonical = configured.first()
        val mirrors = configured.drop(1)
        if (mirrors.isEmpty()) return listOf(canonical)
        val rotatedMirrors = List(mirrors.size) { i -> mirrors[(offset + i) % mirrors.size] }
        return (listOf(canonical) + rotatedMirrors).distinct().take(MAX_ADDITIVE_ENDPOINTS)
    }

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

    override fun close() {
        safeTocExecutor.shutdownNow()
    }

    companion object {
        private const val KM_PER_DEG_LAT = 111.0
        private const val LOCALE_RADIUS_KM = 12.0
        private const val LOCALE_STEP_KM = 20.0
        private const val MIN_CELL_DEGREES = 0.025
        private const val MAX_SUBDIVISION_DEPTH = 4
        private const val SAFETOC_CANONICAL_OVERPASS = "https://overpass-api.de/api/interpreter"
        // SafeTOC NetworkBackendEngine.fetchAsync uses DEFAULT_TIMEOUT_MS = 15000.
        private const val SAFETOC_HTTP_TIMEOUT_MS = 15_000
        private const val SAFETOC_WORKFLOW_TIMEOUT_MS = 17_000L
        private const val WORKFLOW_POLL_SLICE_MS = 250L
        private const val ADDITIVE_HTTP_TIMEOUT_MS = 15_000
        private const val MAX_ADDITIVE_ENDPOINTS = 3
        private const val INTER_FAMILY_DELAY_MS = 180L
        private const val INTER_LOCALE_DELAY_MS = 140L
        private const val INTER_COUNTY_DELAY_MS = 220L
        private const val INTER_STATE_DELAY_MS = 300L

        private val SAFETOC_FAMILY_ORDER = listOf(Family.ALPR, Family.TRAFFIC)
        private val ADDITIVE_FAMILY_ORDER = listOf(
            Family.SURVEILLANCE,
            Family.ENFORCEMENT,
            Family.SENSORS_TOLL
        )

        /** 50 states + DC + populated U.S. territories, with Census state/territory FIPS codes. */
        val REGIONS: List<Region> = listOf(
            Region("US-AL", "Alabama", "01"), Region("US-AK", "Alaska", "02"), Region("US-AZ", "Arizona", "04"), Region("US-AR", "Arkansas", "05"),
            Region("US-CA", "California", "06"), Region("US-CO", "Colorado", "08"), Region("US-CT", "Connecticut", "09"), Region("US-DE", "Delaware", "10"),
            Region("US-FL", "Florida", "12"), Region("US-GA", "Georgia", "13"), Region("US-HI", "Hawaii", "15"), Region("US-ID", "Idaho", "16"),
            Region("US-IL", "Illinois", "17"), Region("US-IN", "Indiana", "18"), Region("US-IA", "Iowa", "19"), Region("US-KS", "Kansas", "20"),
            Region("US-KY", "Kentucky", "21"), Region("US-LA", "Louisiana", "22"), Region("US-ME", "Maine", "23"), Region("US-MD", "Maryland", "24"),
            Region("US-MA", "Massachusetts", "25"), Region("US-MI", "Michigan", "26"), Region("US-MN", "Minnesota", "27"), Region("US-MS", "Mississippi", "28"),
            Region("US-MO", "Missouri", "29"), Region("US-MT", "Montana", "30"), Region("US-NE", "Nebraska", "31"), Region("US-NV", "Nevada", "32"),
            Region("US-NH", "New Hampshire", "33"), Region("US-NJ", "New Jersey", "34"), Region("US-NM", "New Mexico", "35"), Region("US-NY", "New York", "36"),
            Region("US-NC", "North Carolina", "37"), Region("US-ND", "North Dakota", "38"), Region("US-OH", "Ohio", "39"), Region("US-OK", "Oklahoma", "40"),
            Region("US-OR", "Oregon", "41"), Region("US-PA", "Pennsylvania", "42"), Region("US-RI", "Rhode Island", "44"), Region("US-SC", "South Carolina", "45"),
            Region("US-SD", "South Dakota", "46"), Region("US-TN", "Tennessee", "47"), Region("US-TX", "Texas", "48"), Region("US-UT", "Utah", "49"),
            Region("US-VT", "Vermont", "50"), Region("US-VA", "Virginia", "51"), Region("US-WA", "Washington", "53"), Region("US-WV", "West Virginia", "54"),
            Region("US-WI", "Wisconsin", "55"), Region("US-WY", "Wyoming", "56"), Region("US-DC", "District of Columbia", "11"),
            Region("US-PR", "Puerto Rico", "72"), Region("US-VI", "U.S. Virgin Islands", "78"), Region("US-GU", "Guam", "66"),
            Region("US-AS", "American Samoa", "60"), Region("US-MP", "Northern Mariana Islands", "69")
        )

        val STATE_SOURCE_IDS: Set<String> = REGIONS
            .filterNot { it.code in setOf("US-DC", "US-PR", "US-VI", "US-GU", "US-AS", "US-MP") }
            .mapTo(LinkedHashSet()) { it.sourceId }
    }
}
