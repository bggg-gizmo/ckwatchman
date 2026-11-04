package com.bggg.watchman.cameras

import android.content.SharedPreferences
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraSourceResult
import com.bggg.watchman.data.CameraSourceStatus
import com.bggg.watchman.data.CameraSyncResult
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.net.HttpClient
import com.bggg.watchman.net.OperationDeadline
import com.bggg.watchman.util.Geo
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Export-only public-feed acquisition selected from route geometry rather than phone GPS.
 * The normal [PublicCameraProvider] startup path remains byte-for-byte compatible with v0.1.8.
 */
class RegionalPublicCameraProvider {
    private data class Spec(val id: String, val name: String, val url: String, val defaultType: CameraType = CameraType.HIGHWAY)

    fun syncRoutesIncremental(
        routes: List<Route>,
        prefs: SharedPreferences,
        onSource: ((CameraSourceResult) -> Unit)? = null
    ): CameraSyncResult {
        val unique = LinkedHashMap<String, Spec>()
        for (route in routes) {
            val points = LinkedHashMap<String, GeoPoint>()
            route.points.firstOrNull()?.let { points["start"] = it }
            Geo.sampleRoutePoints(route.points, ROUTE_FEED_SAMPLE_METERS).forEachIndexed { index, point ->
                points["p$index"] = point
            }
            route.points.lastOrNull()?.let { points["end"] = it }
            points.values.forEach { point ->
                specsFor(point, prefs).forEach { spec -> unique["${spec.id}|${spec.url}"] = spec }
            }
        }
        return syncSpecs(unique.values.toList(), onSource)
    }

    private fun syncSpecs(specs: List<Spec>, onSource: ((CameraSourceResult) -> Unit)?): CameraSyncResult {
        if (specs.isEmpty()) return CameraSyncResult(emptyList(), emptyList())
        val pool = Executors.newFixedThreadPool(minOf(3, specs.size))
        val futures = ArrayList<Future<CameraSourceResult>>(specs.size)
        try {
            val completion = ExecutorCompletionService<CameraSourceResult>(pool)
            val futureSpecs = LinkedHashMap<Future<CameraSourceResult>, Spec>()
            specs.forEach { spec ->
                val future = completion.submit(Callable { fetch(spec) })
                futures += future
                futureSpecs[future] = spec
            }
            val pending = LinkedHashSet(specs)
            val deadline = OperationDeadline(WORKFLOW_TIMEOUT_MS)
            val statuses = ArrayList<CameraSourceStatus>(specs.size)
            val cameras = ArrayList<CameraPoi>()
            while (pending.isNotEmpty()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Public route-camera workflow interrupted")
                val remaining = deadline.remainingMillis()
                if (remaining <= 0L) break
                val future = completion.poll(remaining, TimeUnit.MILLISECONDS) ?: break
                val spec = futureSpecs[future]
                val result = try {
                    future.get()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                } catch (e: Exception) {
                    val cause = e.cause ?: e
                    CameraSourceResult(
                        emptyList(),
                        CameraSourceStatus(spec?.id ?: "public_export_unknown", spec?.name ?: "Public route camera source", false, 0, spec?.url, cause.message, 0L)
                    )
                }
                if (spec != null) pending.remove(spec)
                statuses += result.status
                cameras += result.cameras
                onSource?.invoke(result)
            }
            pending.forEach { spec ->
                val timeoutResult = CameraSourceResult(emptyList(), CameraSourceStatus(spec.id, spec.name, false, 0, spec.url, "Public route-camera workflow deadline exceeded", WORKFLOW_TIMEOUT_MS))
                statuses += timeoutResult.status
                onSource?.invoke(timeoutResult)
            }
            return CameraSyncResult(cameras, statuses)
        } finally {
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
        }
    }

    private fun specsFor(c: GeoPoint, prefs: SharedPreferences): List<Spec> {
        val out = ArrayList<Spec>()
        if (c.lat in -38.8..-27.8 && c.lon in 140.7..154.2) {
            out += Spec("nsw_live_traffic", "Transport for NSW — Live Traffic Cameras", PublicCameraProvider.NSW)
        }
        if (c.lat in 37.7..39.9 && c.lon in -79.8..-74.8) {
            out += Spec("maryland_public_cameras", "Maryland Public Traffic Cameras", PublicCameraProvider.MARYLAND)
        }
        val ny = prefs.getString("camera_key_511ny", "")?.trim().orEmpty()
        if (ny.isNotEmpty() && c.lat in 40.2..45.2 && c.lon in -80.2..-71.3) {
            out += Spec("511ny", "511NY Traffic Cameras", "https://511ny.org/api/v2/get/cameras?key=${enc(ny)}&format=json")
        }
        val ga = prefs.getString("camera_key_511ga", "")?.trim().orEmpty()
        if (ga.isNotEmpty() && c.lat in 30.1..35.2 && c.lon in -85.7..-80.7) {
            out += Spec("511ga", "511GA Traffic Cameras", "https://511ga.org/api/v2/get/cameras?key=${enc(ga)}&format=json")
        }
        val wa = prefs.getString("camera_key_wsdot", "")?.trim().orEmpty()
        if (wa.isNotEmpty() && c.lat in 45.4..49.2 && c.lon in -125.0..-116.7) {
            out += Spec("wsdot", "WSDOT Highway Cameras", "https://wsdot.wa.gov/Traffic/api/HighwayCameras/HighwayCamerasREST.svc/GetCamerasAsJson?AccessCode=${enc(wa)}")
        }
        prefs.getString("custom_camera_feeds", "")?.lineSequence()?.map { it.trim() }
            ?.filter { it.startsWith("https://") }?.distinct()?.take(10)?.forEach { url ->
                out += Spec("custom_${url.hashCode().toUInt().toString(16)}", "Custom Public Camera Feed", url, CameraType.OTHER)
            }
        return out
    }

    private fun fetch(spec: Spec): CameraSourceResult {
        val started = System.currentTimeMillis()
        return try {
            val body = HttpClient.get(spec.url, timeoutMs = 14_000, attempts = 1)
            if (body.length > PublicCameraProvider.MAX_FEED_BYTES) throw IllegalStateException("camera feed too large")
            val rows = PublicCameraParser.parse(body, spec.id, spec.name, spec.url, spec.defaultType)
            CameraSourceResult(rows, CameraSourceStatus(spec.id, spec.name, true, rows.size, spec.url, null, System.currentTimeMillis() - started))
        } catch (e: Exception) {
            CameraSourceResult(emptyList(), CameraSourceStatus(spec.id, spec.name, false, 0, spec.url, e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - started))
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8.name())

    companion object {
        private const val ROUTE_FEED_SAMPLE_METERS = 100_000.0
        private const val WORKFLOW_TIMEOUT_MS = 30_000L
    }
}
