package com.bggg.watchman.storage

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.GeocoderEvidence
import org.json.JSONArray
import org.json.JSONObject

/** Bounded optional provenance codec. The raw response is authoritative for typed metadata;
 * restore reparses it rather than trusting separately editable OSM IDs/address/extent fields.
 * No output is legal entrance, road connectivity, customer/private permission or freshness proof.
 */
object SearchResultEvidenceJson {
    private const val VERSION = 1
    /** Bound nesting BEFORE invoking the recursive JSON parser, including restored raw text. */
    private fun boundedObject(raw: String): JSONObject {
        require(raw.isNotBlank() && raw.length <= 65536)
        var depth = 0; var quoted = false; var escaped = false
        for (char in raw) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 8) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(!quoted && !escaped && depth == 0)
        return JSONObject(raw)
    }
    private fun string(json: JSONObject, key: String, maximum: Int): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return (json.get(key) as? String ?: error("Non-string geocoder $key")).also {
            require(it.isNotBlank() && it.length <= maximum)
        }
    }
    private fun id(value: Any): Long {
        val result = when (value) {
            is Long -> value
            is Int -> value.toLong()
            is String -> {
                require(value.matches(Regex("[1-9][0-9]{0,18}")))
                value.toLongOrNull() ?: error("Geocoder OSM ID exceeds integral range")
            }
            else -> error("Non-integral geocoder OSM ID")
        }
        require(result > 0)
        return result
    }
    private fun decimal(value: Any): Double {
        require(value is Number || value is String)
        return value.toString().toDoubleOrNull()?.also { require(it.isFinite()) }
            ?: error("Invalid geocoder coordinate")
    }
    private fun validateRaw(value: Any?, depth: Int, count: IntArray) {
        require(depth <= 8 && ++count[0] <= 4096) { "Geocoder nesting/record count exceeds bound" }
        when (value) {
            null, JSONObject.NULL -> Unit
            is JSONObject -> {
                require(value.length() <= 512)
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next(); require(key.length <= 256)
                    validateRaw(value.get(key), depth + 1, count)
                }
            }
            is JSONArray -> {
                require(value.length() <= 512)
                for (i in 0 until value.length()) validateRaw(value.get(i), depth + 1, count)
            }
            is String -> require(value.length <= 8192)
            is Double -> require(value.isFinite())
            is Float -> require(value.isFinite())
            is Number, is Boolean -> Unit
            else -> error("Unsupported geocoder source value")
        }
    }
    /** Invalid OPTIONAL metadata becomes absent evidence, never a fabricated identity. The
     * provider can still retain a valid existing displayName/coordinate result independently.
     */
    fun fromNominatim(record: JSONObject, requestUrl: String, point: GeoPoint): GeocoderEvidence? = runCatching {
        validateRaw(record, 0, intArrayOf(0))
        val raw = record.toString(); require(raw.length <= 65536)
        val responsePoint = GeoPoint(decimal(record.get("lat")), decimal(record.get("lon")))
        require(responsePoint == point) { "Geocoder provenance belongs to another coordinate" }
        val sourceId = if (!record.has("osm_id") || record.isNull("osm_id")) null else id(record.get("osm_id"))
        val address = LinkedHashMap<String, String>()
        if (record.has("address") && !record.isNull("address")) {
            val objectAddress = record.get("address") as? JSONObject ?: error("Non-object geocoder address")
            require(objectAddress.length() <= 128)
            val keys = objectAddress.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                address[key] = objectAddress.get(key) as? String ?: error("Non-string geocoder address field")
            }
        }
        val bounds = if (!record.has("boundingbox") || record.isNull("boundingbox")) null else {
            val array = record.get("boundingbox") as? JSONArray ?: error("Non-array geocoder extent")
            require(array.length() == 4)
            GeocoderEvidence.Bounds(decimal(array.get(0)), decimal(array.get(1)),
                decimal(array.get(2)), decimal(array.get(3)))
        }
        GeocoderEvidence("nominatim", requestUrl, responsePoint, string(record, "osm_type", 64),
            sourceId, string(record, "class", 256) ?: string(record, "category", 256),
            string(record, "type", 256), address, bounds, raw)
    }.getOrNull()

    fun encode(evidence: GeocoderEvidence?, point: GeoPoint): JSONObject? {
        if (evidence == null || evidence.providerId != "nominatim" || !evidence.binds(point)) return null
        // Revalidate even an object supplied directly by another caller before persisting it.
        val checked = runCatching {
            require(evidence.rawRecord.length <= 65536)
            fromNominatim(boundedObject(evidence.rawRecord), evidence.requestUrl, point)
        }.getOrNull() ?: return null
        return JSONObject().put("formatVersion", VERSION).put("providerId", checked.providerId)
            .put("requestUrl", checked.requestUrl).put("lat", checked.responsePoint.lat)
            .put("lon", checked.responsePoint.lon).put("rawRecord", checked.rawRecord)
    }

    fun decode(value: Any?, point: GeoPoint): GeocoderEvidence? = runCatching {
        val json = value as? JSONObject ?: return null
        require(json.keys().asSequence().toSet() == setOf("formatVersion", "providerId", "requestUrl", "lat", "lon", "rawRecord"))
        require(json.get("formatVersion") is Int && json.getInt("formatVersion") == VERSION)
        require(string(json, "providerId", 64) == "nominatim")
        val url = checkNotNull(string(json, "requestUrl", 8192))
        val observed = GeoPoint(decimal(json.get("lat")), decimal(json.get("lon")))
        require(observed == point)
        val raw = checkNotNull(string(json, "rawRecord", 65536))
        fromNominatim(boundedObject(raw), url, observed)
    }.getOrNull()
}
