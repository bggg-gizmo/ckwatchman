package com.bggg.watchman.diagnostics

import android.content.Context
import com.bggg.watchman.data.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bounded, app-private diagnostics for route planning and proximity-audio state.
 *
 * This class has no network path. Logs can leave private storage only when the user explicitly
 * chooses the Tools save action, which uses Android's document picker.
 */
class LocalDiagnostics(context: Context) {
    data class Viewport(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double
    )

    data class RouteSession(
        val status: String,
        val mode: String,
        val automatic: Boolean,
        val phaseTimingMs: Map<String, Long>,
        val baselineRoutes: Int,
        val baselineProviders: List<String>,
        val liveSourceCounts: Map<String, Int>,
        val shapingDevices: Int,
        val candidateDevices: Int,
        val shapedAttempts: Int,
        val shapedRoutes: Int,
        val rawCandidates: Int,
        val deduplicatedCandidates: Int,
        val acceptedShapedRoutes: Int,
        val finalCandidates: Int,
        val exposureCounts: List<Int>,
        val selectedIdentity: String? = null,
        val selectedDistanceMeters: Double? = null,
        val selectedDurationSeconds: Double? = null,
        val viewport: Viewport? = null,
        val fallback: String? = null,
        val error: String? = null,
        val obstacleRoadRequests: Int = 0,
        val obstacleExcludedDevices: Int = 0,
        val obstacleSnapshotRevision: String? = null
    )

    data class ProximityState(
        val owner: String,
        val deviceId: String,
        val deviceType: String,
        val deviceSourceId: String,
        val distanceMeters: Double,
        val euclideanDistanceMeters: Double,
        val signedRouteDistanceMeters: Double?,
        val routeDistanceUsed: Boolean,
        val deviceDirectionDegrees: Double?,
        val travelBearingDegrees: Double?,
        val stage: String,
        val encounterState: String,
        val fovActive: Boolean,
        val fovBasis: String,
        val fovSupported: Boolean,
        val cadenceMs: Long,
        val toneRendererMode: String,
        val passed: Boolean,
        val devicesInRange: Int,
        val spokenAlert: Boolean
    )

    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val routeFile = File(directory, ROUTE_FILE_NAME)
    private val proximityFile = File(directory, PROXIMITY_FILE_NAME)

    fun recordRouteSession(record: RouteSession) {
        val json = JSONObject()
            .put("timestampMillis", System.currentTimeMillis())
            .put("status", record.status)
            .put("mode", record.mode)
            .put("automatic", record.automatic)
            .put("phaseTimingMs", jsonObject(record.phaseTimingMs))
            .put("baselineRoutes", record.baselineRoutes)
            .put("baselineProviders", jsonArray(record.baselineProviders))
            .put("liveSourceCounts", jsonObject(record.liveSourceCounts))
            .put("shapingDevices", record.shapingDevices)
            .put("candidateDevices", record.candidateDevices)
            .put("shapedAttempts", record.shapedAttempts)
            .put("shapedRoutes", record.shapedRoutes)
            .put("obstacleRoadRequests", record.obstacleRoadRequests)
            .put("obstacleExcludedDevices", record.obstacleExcludedDevices)
            .putNullable("obstacleSnapshotRevision", record.obstacleSnapshotRevision)
            .put("rawCandidates", record.rawCandidates)
            .put("deduplicatedCandidates", record.deduplicatedCandidates)
            .put("acceptedShapedRoutes", record.acceptedShapedRoutes)
            .put("finalCandidates", record.finalCandidates)
            .put("exposureCounts", jsonArray(record.exposureCounts))
            .putNullable("selectedIdentity", record.selectedIdentity)
            .putNullable("selectedDistanceMeters", record.selectedDistanceMeters)
            .putNullable("selectedDurationSeconds", record.selectedDurationSeconds)
            .put("viewport", record.viewport?.let(::viewportJson) ?: JSONObject.NULL)
            .putNullable("fallback", record.fallback)
            .putNullable("error", record.error)
        appendBounded(routeFile, json.toString())
    }

    fun recordProximityState(record: ProximityState) {
        val json = JSONObject()
            .put("timestampMillis", System.currentTimeMillis())
            .put("owner", record.owner)
            .put("deviceId", record.deviceId)
            .put("deviceType", record.deviceType)
            .put("deviceSourceId", record.deviceSourceId)
            .put("distanceMeters", record.distanceMeters)
            .put("euclideanDistanceMeters", record.euclideanDistanceMeters)
            .putNullable("signedRouteDistanceMeters", record.signedRouteDistanceMeters)
            .put("routeDistanceUsed", record.routeDistanceUsed)
            .putNullable("deviceDirectionDegrees", record.deviceDirectionDegrees)
            .putNullable("travelBearingDegrees", record.travelBearingDegrees)
            .put("stage", record.stage)
            .put("encounterState", record.encounterState)
            .put("fovActive", record.fovActive)
            .put("fovBasis", record.fovBasis)
            .put("fovSupported", record.fovSupported)
            .put("cadenceMs", record.cadenceMs)
            .put("toneRendererMode", record.toneRendererMode)
            .put("passed", record.passed)
            .put("devicesInRange", record.devicesInRange)
            .put("spokenAlert", record.spokenAlert)
        appendBounded(proximityFile, json.toString())
    }

    fun summary(maxPerKind: Int = 8): String = buildString {
        append("Route sessions\n")
        val routes = recentLines(routeFile, maxPerKind)
        if (routes.isEmpty()) append("No local route-session records yet.\n")
        else routes.forEach { append(formatRoute(it)).append('\n') }
        append("\nProximity state\n")
        val proximity = recentLines(proximityFile, maxPerKind)
        if (proximity.isEmpty()) append("No local proximity-state records yet.\n")
        else proximity.forEach { append(formatProximity(it)).append('\n') }
    }.trim()

    fun exportBytes(): ByteArray = synchronized(FILE_LOCK) {
        buildString {
            append("WatchMan local diagnostics\n")
            append("Private on-device records; exported only by explicit user action.\n")
            append("Generated: ").append(formatTimestamp(System.currentTimeMillis())).append("\n\n")
            append("=== ROUTE SESSIONS (JSONL) ===\n")
            if (routeFile.isFile) append(routeFile.readText(Charsets.UTF_8)) else append("No records.\n")
            append("\n=== PROXIMITY STATE (JSONL) ===\n")
            if (proximityFile.isFile) append(proximityFile.readText(Charsets.UTF_8)) else append("No records.\n")
        }.toByteArray(Charsets.UTF_8)
    }

    fun clear() = synchronized(FILE_LOCK) {
        routeFile.delete()
        proximityFile.delete()
    }

    private fun appendBounded(file: File, line: String) = synchronized(FILE_LOCK) {
        directory.mkdirs()
        val encoded = (line + "\n").toByteArray(Charsets.UTF_8)
        if (file.length() + encoded.size > MAX_FILE_BYTES) {
            val current = if (file.isFile) file.readBytes() else ByteArray(0)
            var start = (current.size - RETAIN_BYTES).coerceAtLeast(0)
            while (start < current.size && current[start] != '\n'.code.toByte()) start++
            if (start < current.size) start++
            file.writeBytes(current.copyOfRange(start, current.size))
        }
        file.appendBytes(encoded)
    }

    private fun recentLines(file: File, limit: Int): List<String> = synchronized(FILE_LOCK) {
        if (!file.isFile) emptyList()
        else file.readLines(Charsets.UTF_8).asReversed().take(limit.coerceIn(1, MAX_VIEW_RECORDS))
    }

    private fun formatRoute(raw: String): String = runCatching {
        val json = JSONObject(raw)
        val selected = json.optString("selectedIdentity").takeIf { it.isNotBlank() && it != "null" }
            ?.take(12) ?: "none"
        "${formatTimestamp(json.optLong("timestampMillis"))} · ${json.optString("mode")} · ${json.optString("status")}\n" +
            "phases ${json.optJSONObject("phaseTimingMs") ?: JSONObject()} · provider ${json.optInt("baselineRoutes")} baseline / ${json.optInt("shapedRoutes")} shaped\n" +
            "devices ${json.optInt("shapingDevices")} shaping / ${json.optInt("candidateDevices")} measured · dedupe ${json.optInt("rawCandidates")}→${json.optInt("deduplicatedCandidates")}→${json.optInt("finalCandidates")} · exposure ${json.optJSONArray("exposureCounts") ?: JSONArray()} · selected $selected" +
            json.optString("fallback").takeIf { it.isNotBlank() && it != "null" }?.let { "\nfallback $it" }.orEmpty() +
            json.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { "\nerror $it" }.orEmpty()
    }.getOrElse { "Unreadable route diagnostic record" }

    private fun formatProximity(raw: String): String = runCatching {
        val json = JSONObject(raw)
        val distance = String.format(Locale.US, "%.1f", json.optDouble("distanceMeters"))
        val routeDistance = if (json.isNull("signedRouteDistanceMeters")) "n/a" else
            String.format(Locale.US, "%.1f", json.optDouble("signedRouteDistanceMeters"))
        "${formatTimestamp(json.optLong("timestampMillis"))} · ${json.optString("owner")} · ${json.optString("deviceType")} ${json.optString("deviceId")}\n" +
            "distance ${distance}m · route delta ${routeDistance}m · state ${json.optString("encounterState")} / ${json.optString("stage")} · FOV ${json.optBoolean("fovActive")} ${json.optString("fovBasis")} · cadence ${json.optLong("cadenceMs")}ms · ${json.optString("toneRendererMode")} · passed ${json.optBoolean("passed")}"
    }.getOrElse { "Unreadable proximity diagnostic record" }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

    private fun jsonObject(values: Map<String, out Any>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value) }
    }

    private fun jsonArray(values: Iterable<Any>): JSONArray = JSONArray().apply {
        values.forEach(::put)
    }

    private fun viewportJson(viewport: Viewport): JSONObject = JSONObject()
        .put("south", viewport.south)
        .put("west", viewport.west)
        .put("north", viewport.north)
        .put("east", viewport.east)

    companion object {
        private const val DIRECTORY_NAME = "local-diagnostics"
        private const val ROUTE_FILE_NAME = "route-sessions.jsonl"
        private const val PROXIMITY_FILE_NAME = "proximity-state.jsonl"
        const val MAX_FILE_BYTES = 256 * 1024L
        private const val RETAIN_BYTES = 128 * 1024
        private const val MAX_VIEW_RECORDS = 24
        private val FILE_LOCK = Any()

        fun viewport(points: List<GeoPoint>): Viewport? {
            if (points.isEmpty()) return null
            return Viewport(
                south = points.minOf { it.lat },
                west = points.minOf { it.lon },
                north = points.maxOf { it.lat },
                east = points.maxOf { it.lon }
            )
        }

        private fun formatTimestamp(epochMillis: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMillis))
    }
}
