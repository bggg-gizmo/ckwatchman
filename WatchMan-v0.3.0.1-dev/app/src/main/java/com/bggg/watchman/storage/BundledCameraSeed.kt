package com.bggg.watchman.storage

import android.content.Context
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream

/**
 * Installs the deterministic bundled OSM baseline one jurisdiction at a time.
 *
 * Seed rows are written only to NationalCameraCache seed snapshots. They are queryable by maps,
 * routing, alerts, and exports, but never write live source/county/state completion records.
 */
object BundledCameraSeed {
    const val SEED_ASSET = "national_seed_v028.jsonl.gzipdata"
    const val MANIFEST_ASSET = "national_seed_v028.manifest.json"

    private const val PREFS = "watchman_bundled_seed"
    private const val INSTALLED_SHA_KEY = "installed_seed_sha256"
    private const val STATE_SHA_PREFIX = "jurisdiction_sha256:"

    data class InstallResult(
        val installed: Boolean,
        val recordCount: Int,
        val jurisdictionCount: Int,
        val seedSha256: String
    )

    fun installIfNeeded(context: Context, cache: NationalCameraCache): InstallResult {
        val manifest = context.assets.open(MANIFEST_ASSET).bufferedReader(Charsets.UTF_8).use {
            JSONObject(it.readText())
        }
        require(manifest.getInt("schema_version") == 1) { "Unsupported bundled seed schema" }
        require(manifest.optInt("completion_credit", -1) == 0) {
            "Bundled seed must grant zero setup completion credit"
        }
        require(manifest.optJSONArray("failures")?.length() == 0) {
            "Bundled seed manifest contains failed jurisdictions"
        }

        val expectedSha = manifest.getString("seed_sha256").lowercase()
        val expectedCount = manifest.getInt("record_count")
        val expectedJurisdictions = manifest.getJSONArray("jurisdictions_in_seed").length()
        val seedPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (seedPrefs.getString(INSTALLED_SHA_KEY, null) == expectedSha) {
            return InstallResult(false, expectedCount, expectedJurisdictions, expectedSha)
        }

        val actualSha = context.assets.open(SEED_ASSET).use(::sha256)
        require(actualSha == expectedSha) { "Bundled seed SHA-256 mismatch" }

        var parsedCount = 0
        var jurisdictionCount = 0
        var currentJurisdiction: String? = null
        val jurisdictionRows = ArrayList<CameraPoi>()

        fun flushJurisdiction() {
            val jurisdiction = currentJurisdiction ?: return
            if (seedPrefs.getString(STATE_SHA_PREFIX + jurisdiction, null) != expectedSha) {
                cache.replaceSeedShard(jurisdiction, jurisdictionRows)
                check(seedPrefs.edit()
                    .putString(STATE_SHA_PREFIX + jurisdiction, expectedSha)
                    .commit()) { "Unable to persist seed shard checkpoint" }
            }
            jurisdictionRows.clear()
            jurisdictionCount++
        }

        context.assets.open(SEED_ASSET).use { raw ->
            GZIPInputStream(raw).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val json = JSONObject(line)
                    require(json.getInt("schema_version") == 1)
                    val jurisdiction = json.getString("jurisdiction")
                    require(jurisdiction.matches(Regex("[a-z0-9-]{2,40}")))
                    if (currentJurisdiction != jurisdiction) {
                        currentJurisdiction?.let { previous -> require(jurisdiction > previous) { "Bundled seed is not jurisdiction-sorted" } }
                        flushJurisdiction()
                        currentJurisdiction = jurisdiction
                    }
                    parsedCount++
                    if (seedPrefs.getString(STATE_SHA_PREFIX + jurisdiction, null) != expectedSha) {
                        jurisdictionRows += parseCamera(json)
                    }
                }
            }
        }
        flushJurisdiction()

        require(parsedCount == expectedCount) {
            "Bundled seed count mismatch: expected $expectedCount, parsed $parsedCount"
        }
        require(jurisdictionCount == expectedJurisdictions) {
            "Bundled seed jurisdiction mismatch: expected $expectedJurisdictions, parsed $jurisdictionCount"
        }
        check(seedPrefs.edit().putString(INSTALLED_SHA_KEY, expectedSha).commit()) {
            "Unable to persist bundled seed completion"
        }
        return InstallResult(true, parsedCount, jurisdictionCount, expectedSha)
    }

    private fun parseCamera(json: JSONObject): CameraPoi {
        val jurisdiction = json.getString("jurisdiction")
        val upstreamId = json.getString("upstream_id")
        val tags = json.optJSONObject("tags").toStringMap()
        tags["watchman:broad_type"] = json.optString("broad_type", "UNKNOWN_RELEVANT_DEVICE")
        tags["watchman:subtype"] = json.optString("subtype", "unknown_relevant")
        tags["watchman:jurisdiction"] = jurisdiction
        json.stringOrNull("model")?.let { tags["model"] = it }
        json.optJSONObject("provenance")?.let { provenance ->
            val keys = provenance.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!provenance.isNull(key)) tags["provenance:$key"] = provenance.optString(key)
            }
        }
        return CameraPoi(
            id = upstreamId,
            type = runCatching {
                CameraType.valueOf(json.getString("camera_type"))
            }.getOrDefault(CameraType.OTHER),
            point = GeoPoint(json.getDouble("latitude"), json.getDouble("longitude")),
            name = json.stringOrNull("name"),
            road = json.stringOrNull("road"),
            direction = json.stringOrNull("direction"),
            operator = json.stringOrNull("operator"),
            manufacturer = json.stringOrNull("manufacturer"),
            source = "OpenStreetMap - bundled baseline",
            sourceId = "osm_seed:$jurisdiction",
            sourceUrl = json.stringOrNull("source_url"),
            sourceRecordId = upstreamId,
            confidence = json.optInt("confidence", 70).coerceIn(0, 100),
            tags = tags,
            lastUpdatedMillis = sourceDateMillis(json.stringOrNull("source_date"))
        )
    }

    private fun JSONObject?.toStringMap(): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val json = this ?: return out
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!json.isNull(key)) out[key] = json.optString(key)
        }
        return out
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).trim().takeIf { it.isNotEmpty() } else null

    private fun sourceDateMillis(raw: String?): Long = runCatching {
        LocalDate.parse(raw).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrDefault(0L)

    private fun sha256(stream: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
