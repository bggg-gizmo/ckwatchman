package com.bggg.watchman.cameras

import android.content.SharedPreferences
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraSourceResult
import com.bggg.watchman.data.CameraSourceStatus
import com.bggg.watchman.data.CameraSyncResult
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.net.HttpClient
import com.bggg.watchman.net.OperationDeadline
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/** Additive direct public/keyed/custom camera feeds in the normal v0.1.8 runtime. */
class PublicCameraProvider {
    private data class Spec(val id: String, val name: String, val url: String, val defaultType: CameraType = CameraType.HIGHWAY)

    @Volatile var lastStatuses: List<CameraSourceStatus> = emptyList()
        private set

    fun syncIncremental(
        center: GeoPoint,
        prefs: SharedPreferences,
        onSource: ((CameraSourceResult) -> Unit)? = null
    ): CameraSyncResult {
        val specs = specsFor(center, prefs)
        if (specs.isEmpty()) {
            lastStatuses = emptyList()
            return CameraSyncResult(emptyList(), emptyList())
        }
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
            val statuses = ArrayList<CameraSourceStatus>()
            val all = ArrayList<CameraPoi>()
            while (pending.isNotEmpty()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Public camera workflow interrupted")
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
                    CameraSourceResult(emptyList(), CameraSourceStatus(spec?.id ?: "public_unknown", spec?.name ?: "Public camera source", false, 0, spec?.url, cause.message, 0))
                }
                if (spec != null) pending.remove(spec)
                statuses += result.status
                all += result.cameras
                onSource?.invoke(result)
            }
            pending.forEach { spec ->
                val timeoutResult = CameraSourceResult(emptyList(), CameraSourceStatus(spec.id, spec.name, false, 0, spec.url, "Public camera workflow deadline exceeded", WORKFLOW_TIMEOUT_MS))
                statuses += timeoutResult.status
                onSource?.invoke(timeoutResult)
            }
            lastStatuses = statuses.toList()
            return CameraSyncResult(all, statuses)
        } finally {
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
        }
    }

    private fun specsFor(c: GeoPoint, prefs: SharedPreferences): List<Spec> {
        val out = ArrayList<Spec>()
        if (c.lat in -38.8..-27.8 && c.lon in 140.7..154.2) {
            out += Spec("nsw_live_traffic", "Transport for NSW — Live Traffic Cameras", NSW)
        }
        if (c.lat in 37.7..39.9 && c.lon in -79.8..-74.8) {
            out += Spec("maryland_public_cameras", "Maryland Public Traffic Cameras", MARYLAND)
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
            if (body.length > MAX_FEED_BYTES) throw IllegalStateException("camera feed too large")
            val rows = PublicCameraParser.parse(body, spec.id, spec.name, spec.url, spec.defaultType)
            CameraSourceResult(rows, CameraSourceStatus(spec.id, spec.name, true, rows.size, spec.url, null, System.currentTimeMillis() - started))
        } catch (e: Exception) {
            CameraSourceResult(emptyList(), CameraSourceStatus(spec.id, spec.name, false, 0, spec.url, e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - started))
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8.name())

    companion object {
        const val NSW = "https://opendata.transport.nsw.gov.au/data/dataset/b0212311-b0da-4363-8dc3-825fe10941b2/resource/cc776d1a-d96c-4ae4-a465-c380a53717c9/download/livetrafficcamera.json"
        const val MARYLAND = "https://mdgeodata.md.gov/imap/rest/services/Transportation/MD_TrafficCameras/FeatureServer/0/query?where=1%3D1&outFields=*&outSR=4326&f=geojson"
        const val MAX_FEED_BYTES = 24 * 1024 * 1024
        private const val WORKFLOW_TIMEOUT_MS = 30_000L
    }
}

/** Parser compatibility layer for public JSON, GeoJSON, Overpass-like and KML shapes. */
object PublicCameraParser {
    private val latKeys = arrayOf("lat", "latitude", "Latitude", "LAT", "y", "Y")
    private val lonKeys = arrayOf("lng", "lon", "longitude", "Longitude", "LON", "x", "X")
    private val arrayKeys = arrayOf("features", "elements", "records", "cameras", "value")

    fun parse(text: String, sourceId: String, sourceName: String, feedUrl: String, defaultType: CameraType): List<CameraPoi> {
        val trimmed = text.trim()
        if (trimmed.startsWith("<")) return parseKml(trimmed, sourceId, sourceName, feedUrl, defaultType)
        val root = JSONTokener(trimmed).nextValue()
        val out = LinkedHashMap<String, CameraPoi>()
        when (root) {
            is JSONArray -> parseArray(root, sourceId, sourceName, feedUrl, defaultType, out)
            is JSONObject -> {
                var consumed = false
                for (key in arrayKeys) {
                    root.optJSONArray(key)?.let { arr -> parseArray(arr, sourceId, sourceName, feedUrl, defaultType, out); consumed = true }
                }
                if (!consumed) recordFrom(root, null, sourceId, sourceName, feedUrl, defaultType, 0)?.let { out[it.sourceRecordId ?: it.id] = it }
            }
        }
        return out.values.toList()
    }

    private fun parseArray(arr: JSONArray, sid: String, sname: String, url: String, defaultType: CameraType, out: MutableMap<String, CameraPoi>) {
        for (i in 0 until minOf(arr.length(), 5000)) {
            val o = arr.optJSONObject(i) ?: continue
            val geometry = o.optJSONObject("geometry")
            val props = o.optJSONObject("properties") ?: o.optJSONObject("attributes") ?: o.optJSONObject("tags") ?: o
            recordFrom(props, geometry ?: o.optJSONObject("center"), sid, sname, url, defaultType, i, o)?.let { poi ->
                out[poi.sourceRecordId ?: poi.id] = poi
            }
        }
    }

    private fun recordFrom(
        props: JSONObject,
        geometryOrCenter: JSONObject?,
        sid: String,
        sname: String,
        feedUrl: String,
        defaultType: CameraType,
        index: Int,
        outer: JSONObject? = null
    ): CameraPoi? {
        val point = pointFromGeometry(geometryOrCenter) ?: pointFromObject(props) ?: outer?.let { pointFromObject(it) } ?: return null
        val tags = toStringMap(props)
        outer?.optJSONObject("tags")?.let { tags.putAll(toStringMap(it)) }
        val classification = CameraClassifier.classify(tags)
        val type = if (classification.type == CameraType.OTHER) defaultType else classification.type
        val recordId = first(tags, "osm_id", "id", "ID", "Id", "CameraID", "camera_id", "site_id", "ref", "Reference")
            ?: outer?.opt("id")?.toString()?.takeIf { it.isNotBlank() }
            ?: String.format(Locale.US, "%.6f,%.6f", point.lat, point.lon)
        val name = first(tags, "name", "Name", "title", "Title", "description", "Description", "CameraName", "camera_name")
            ?: "${type.label} ${index + 1}"
        val media = first(tags, "VideoUrl", "videoUrl", "VideoURL", "ImageUrl", "imageUrl", "ImageURL", "CameraURL", "CameraUrl", "cameraUrl", "url", "Url", "URL")
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        if (media != null) tags["feed_url"] = feedUrl
        return CameraPoi(
            id = "$sid/$recordId",
            type = type,
            point = point,
            name = name,
            road = first(tags, "road", "roadway", "Roadway", "route", "Route", "street", "Street", "location", "LocationName"),
            direction = first(tags, "direction", "Direction", "cameraDirection", "CameraDirection", "bearing"),
            operator = first(tags, "operator", "Operator", "agency", "Agency", "owner", "Owner"),
            manufacturer = first(tags, "manufacturer", "Manufacturer", "brand", "Brand", "make", "Make"),
            source = sname,
            sourceId = sid,
            sourceUrl = media ?: feedUrl,
            sourceRecordId = recordId,
            confidence = if (classification.type == CameraType.OTHER && defaultType != CameraType.OTHER) 85 else classification.confidence,
            tags = tags
        )
    }

    private fun pointFromObject(o: JSONObject): GeoPoint? {
        val lat = firstDouble(o, latKeys)
        val lon = firstDouble(o, lonKeys)
        if (valid(lat, lon)) return GeoPoint(lat!!, lon!!)
        for (key in arrayOf("Location", "location", "position", "Position")) {
            o.optJSONObject(key)?.let { nested -> pointFromObject(nested)?.let { return it } }
        }
        return null
    }

    private fun pointFromGeometry(g: JSONObject?): GeoPoint? {
        if (g == null) return null
        pointFromObject(g)?.let { return it }
        val c = g.optJSONArray("coordinates") ?: return null
        if (g.optString("type").equals("Point", true) && c.length() >= 2) {
            val lon = c.optDouble(0, Double.NaN); val lat = c.optDouble(1, Double.NaN)
            if (valid(lat, lon)) return GeoPoint(lat, lon)
        }
        val acc = doubleArrayOf(0.0, 0.0, 0.0)
        walkCoordinates(c, acc)
        return if (acc[2] > 0.0) GeoPoint(acc[0] / acc[2], acc[1] / acc[2]) else null
    }

    private fun walkCoordinates(a: JSONArray, acc: DoubleArray) {
        if (a.length() == 0) return
        if (a.opt(0) is Number && a.length() >= 2) {
            val lon = a.optDouble(0, Double.NaN); val lat = a.optDouble(1, Double.NaN)
            if (valid(lat, lon)) { acc[0] += lat; acc[1] += lon; acc[2]++ }
            return
        }
        for (i in 0 until a.length()) a.optJSONArray(i)?.let { walkCoordinates(it, acc) }
    }

    private fun parseKml(text: String, sid: String, sname: String, url: String, defaultType: CameraType): List<CameraPoi> {
        val out = ArrayList<CameraPoi>()
        val pm = Pattern.compile("(?is)<Placemark\\b.*?</Placemark>").matcher(text)
        val coordsPattern = Pattern.compile("(?is)<coordinates>\\s*([-+0-9.eE]+)\\s*,\\s*([-+0-9.eE]+)")
        val namePattern = Pattern.compile("(?is)<name>(.*?)</name>")
        val descPattern = Pattern.compile("(?is)<description>(.*?)</description>")
        var index = 0
        while (pm.find() && out.size < 1000) {
            val block = pm.group() ?: continue; val cm = coordsPattern.matcher(block); if (!cm.find()) continue
            val lon = cm.group(1)?.toDoubleOrNull() ?: continue; val lat = cm.group(2)?.toDoubleOrNull() ?: continue
            if (!valid(lat, lon)) continue
            val tags = LinkedHashMap<String, String>()
            val nm = namePattern.matcher(block); if (nm.find()) nm.group(1)?.let { tags["name"] = stripXml(it) }
            val dm = descPattern.matcher(block); if (dm.find()) dm.group(1)?.let { tags["description"] = stripXml(it) }
            val props = JSONObject(tags as Map<*, *>)
            recordFrom(props, JSONObject().put("lat", lat).put("lon", lon), sid, sname, url, defaultType, index++)?.let { out += it }
        }
        return out
    }

    private fun stripXml(s: String): String = s.replace(Regex("(?is)<[^>]+>"), " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()
    private fun firstDouble(o: JSONObject, keys: Array<String>): Double? {
        for (key in keys) if (o.has(key)) {
            val v = when (val raw = o.opt(key)) { is Number -> raw.toDouble(); else -> raw?.toString()?.toDoubleOrNull() }
            if (v != null && !v.isNaN()) return v
        }
        return null
    }
    private fun valid(lat: Double?, lon: Double?): Boolean = lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0
    private fun toStringMap(o: JSONObject): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>(); val it = o.keys()
        while (it.hasNext()) { val k = it.next(); val v = o.opt(k); if (v != null && v != JSONObject.NULL) out[k] = v.toString() }
        return out
    }
    private fun first(m: Map<String, String>, vararg keys: String): String? = keys.firstNotNullOfOrNull { m[it]?.trim()?.takeIf(String::isNotEmpty) }
}
