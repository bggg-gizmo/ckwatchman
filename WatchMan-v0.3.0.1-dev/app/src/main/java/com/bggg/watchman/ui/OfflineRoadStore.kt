package com.bggg.watchman.ui

import android.content.Context
import android.content.res.AssetManager
import android.os.Looper
import android.util.LruCache
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.util.SharedAssetGate
import org.json.JSONObject
import java.io.Closeable
import java.io.FileNotFoundException
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Asynchronously reads the compact PBF-derived major-road pack bundled with the APK.
 * All map views share one bounded process-wide worker and point cache. Per-view listeners are weak,
 * so a temporary directions map cannot retain its Activity or multiply offline-road budgets.
 */
internal class OfflineRoadStore(
    context: Context,
    private val onReady: () -> Unit
) : Closeable, SharedAssetGate.Listener {
    data class Road(val rank: Int, val label: String?, val points: List<GeoPoint>)

    private val assets = context.applicationContext.assets
    private val active = AtomicBoolean(true)

    fun roadsAround(center: GeoPoint): List<Road> {
        if (!active.get() || requestManifest() != true) return emptyList()
        val (tileX, tileY) = tile(center)
        val output = ArrayList<Road>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                val x = (tileX + dx).coerceIn(0, TILE_LIMIT)
                val y = (tileY + dy).coerceIn(0, TILE_LIMIT)
                val key = "${INDEX_ZOOM.toString().padStart(3, '0')}/${x.toString().padStart(3, '0')}/${y.toString().padStart(3, '0')}"
                SHARED_CACHE.get(key)?.let(output::addAll) ?: schedule(key)
            }
        }
        return output
    }

    private fun requestManifest(): Boolean? {
        // Capture only application assets, never this store or its Activity callback, in the queued load.
        val assetManager = assets
        return SHARED_MANIFEST.observe(this) { manifestValid(assetManager) }
    }

    override fun onAssetReady(valid: Boolean) = notifyReady()

    private fun schedule(key: String) {
        if (!active.get() || key in SHARED_ABSENT) return
        val listeners = SHARED_LISTENERS.computeIfAbsent(key) { CopyOnWriteArrayList() }
        if (listeners.none { it.get() === this }) listeners += WeakReference(this)
        if (!SHARED_PENDING.add(key)) return
        val assetManager = assets
        SHARED_EXECUTOR.execute {
            try {
                val roads = load(assetManager, key)
                if (roads.isEmpty()) SHARED_ABSENT.add(key) else SHARED_CACHE.put(key, roads)
            } catch (_: Exception) {
                SHARED_ABSENT.add(key)
            } finally {
                SHARED_PENDING.remove(key)
                SHARED_LISTENERS.remove(key)?.forEach { it.get()?.notifyReady() }
            }
        }
    }

    private fun notifyReady() {
        if (active.get()) onReady()
    }

    fun activate() {
        active.set(true)
        if (requestManifest() != null) notifyReady()
    }

    override fun close() {
        active.set(false)
        SHARED_MANIFEST.forget(this)
        SHARED_LISTENERS.values.forEach { listeners ->
            listeners.removeIf { reference ->
                val owner = reference.get()
                owner == null || owner === this
            }
        }
    }

    companion object {
        /** Streams the existing visual pack on the PDF worker without filling the interactive cache. */
        fun visitRoads(assets: AssetManager, northWest: GeoPoint, southEast: GeoPoint, visitor: (Road) -> Unit) {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Offline PDF roads must be prepared off the UI thread" }
            if (!manifestValid(assets)) return
            val (minX, minY) = tile(northWest)
            val (maxX, maxY) = tile(southEast)
            for (x in minX..maxX) for (y in minY..maxY) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("PDF road preparation cancelled")
                val key = "${INDEX_ZOOM.toString().padStart(3, '0')}/${x.toString().padStart(3, '0')}/${y.toString().padStart(3, '0')}"
                val raw = try { assets.open("offline_roads/$key.wmr.gzipdata") } catch (_: FileNotFoundException) { continue }
                raw.use { stream ->
                    GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            if (Thread.currentThread().isInterrupted) throw InterruptedException("PDF road preparation cancelled")
                            parseRoad(line)?.let(visitor)
                        }
                    }
                }
            }
        }

        private fun manifestValid(assets: AssetManager): Boolean {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Offline manifest must load off-main" }
            return runCatching {
                val json = assets.open(MANIFEST_ASSET).bufferedReader(Charsets.UTF_8).use {
                    JSONObject(it.readText())
                }
                json.getInt("schema_version") == 1 &&
                    json.getInt("index_zoom") == INDEX_ZOOM &&
                    json.optJSONArray("failures")?.length() == 0 &&
                    json.optJSONArray("missing_50_state_inputs")?.length() == 0 &&
                    (json.optJSONArray("jurisdictions_in_pack")?.length() ?: 0) >= 50 &&
                    json.optBoolean("public_raster_tiles_prefetched", true).not()
            }.getOrDefault(false)
        }

        private val SHARED_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "watchman-offline-roads").apply { isDaemon = true }
        }
        private val SHARED_MANIFEST = SharedAssetGate(SHARED_EXECUTOR)
        internal fun manifestDiagnostics(): SharedAssetGate.Snapshot = SHARED_MANIFEST.snapshot()
        private val SHARED_PENDING = ConcurrentHashMap.newKeySet<String>()
        private val SHARED_ABSENT = ConcurrentHashMap.newKeySet<String>()
        private val SHARED_LISTENERS = ConcurrentHashMap<String, CopyOnWriteArrayList<WeakReference<OfflineRoadStore>>>()
        private val SHARED_CACHE = object : LruCache<String, List<Road>>(MAX_CACHED_POINTS) {
            override fun sizeOf(key: String, value: List<Road>): Int =
                value.sumOf { road -> road.points.size }.coerceAtLeast(1)
        }

        fun trimSharedCache(maxPoints: Int) {
            SHARED_CACHE.trimToSize(maxPoints.coerceIn(0, MAX_CACHED_POINTS))
        }

        private fun load(assets: AssetManager, key: String): List<Road> {
            val path = "offline_roads/$key.wmr.gzipdata"
            return assets.open(path).use { raw ->
                GZIPInputStream(raw).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.mapNotNull(::parseRoad).toList()
                }
            }
        }

        private fun parseRoad(line: String): Road? {
            val parts = line.split('\t', limit = 3)
            if (parts.size != 3) return null
            val rank = parts[0].toIntOrNull()?.takeIf { it in 1..4 } ?: return null
            val points = parts[2].split(';').mapNotNull { encoded ->
                val comma = encoded.indexOf(',')
                if (comma <= 0 || comma >= encoded.lastIndex) return@mapNotNull null
                val lat = encoded.substring(0, comma).toLongOrNull() ?: return@mapNotNull null
                val lon = encoded.substring(comma + 1).toLongOrNull() ?: return@mapNotNull null
                GeoPoint(lat / 1_000_000.0, lon / 1_000_000.0)
            }
            if (points.size < 2) return null
            return Road(rank, parts[1].trim().takeIf { it.isNotEmpty() }, points)
        }

        private fun tile(point: GeoPoint): Pair<Int, Int> {
            val n = 1 shl INDEX_ZOOM
            val x = floor((point.lon + 180.0) / 360.0 * n).toInt()
            val lat = point.lat.coerceIn(-85.05112878, 85.05112878)
            val y = floor((1.0 - asinh(tan(Math.toRadians(lat))) / Math.PI) / 2.0 * n).toInt()
            return min(TILE_LIMIT, max(0, x)) to min(TILE_LIMIT, max(0, y))
        }

        private const val INDEX_ZOOM = 8
        private const val TILE_LIMIT = (1 shl INDEX_ZOOM) - 1
        private const val MAX_CACHED_POINTS = MapMemoryPolicy.NORMAL_OFFLINE_ROAD_POINTS
        private const val MANIFEST_ASSET = "offline_roads.manifest.json"
    }
}
