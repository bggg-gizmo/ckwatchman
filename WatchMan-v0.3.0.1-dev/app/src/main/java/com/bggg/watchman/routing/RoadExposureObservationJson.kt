package com.bggg.watchman.routing

import com.bggg.watchman.data.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Decodes exact edge-walk attribution and source records. Complete-parent IDs and source
 * receipt times must come from successful provider reads, never from CameraPoi tags or an
 * external JSON field asserting that it is verified. The resulting object still needs the
 * geometry/freshness/identity verification in RoadAwareExposure.verify.
 */
object RoadExposureObservationJson {
    fun decode(traceJson: String, sourceJson: String, completeParentNodeIds: Set<Long>,
        observedAtMillis: Long, validUntilMillis: Long, checkActive: () -> Unit = {}): RoadAwareExposure.Observation {
        require(traceJson.length <= 2000000 && sourceJson.length <= 8000000)
        checkActive()
        val trace = JSONObject(traceJson)
        val source = JSONObject(sourceJson)
        check(!trace.has("error") && !source.has("remark") && !source.has("error")) { "Source attribution query was incomplete" }
        val shape = when (val raw = trace.get("shape")) {
            is String -> polyline6(raw, checkActive)
            is JSONObject -> {
                require(raw.getString("type") == "LineString")
                val coords = raw.getJSONArray("coordinates")
                require(coords.length() in 2..250000)
                (0 until coords.length()).map { i ->
                    if (i % 128 == 0) checkActive()
                    val p = coords.getJSONArray(i)
                    require(p.length() == 2)
                    GeoPoint(p.getDouble(1), p.getDouble(0))
                }
            }
            else -> error("Unsupported exact trace shape")
        }
        val edges = trace.getJSONArray("edges")
        require(edges.length() in 1..100000)
        val spans = (0 until edges.length()).map { i ->
            if (i % 128 == 0) checkActive()
            val edge = edges.getJSONObject(i)
            RoadAwareExposure.Span(positiveId(edge.get("way_id")), index(edge.get("begin_shape_index")),
                index(edge.get("end_shape_index")))
        }
        val nodes = ArrayList<RoadAwareExposure.Node>()
        val ways = ArrayList<RoadAwareExposure.Way>()
        val elements = source.getJSONArray("elements")
        require(elements.length() <= 22048)
        for (i in 0 until elements.length()) {
            if (i % 64 == 0) checkActive()
            val e = elements.getJSONObject(i)
            check(e.optBoolean("visible", true)) { "Deleted source record" }
            val id = positiveId(e.get("id"))
            when (e.getString("type")) {
                "node" -> nodes += RoadAwareExposure.Node(id, GeoPoint(e.getDouble("lat"), e.getDouble("lon")), tags(e.optJSONObject("tags")))
                "way" -> {
                    val raw = e.getJSONArray("nodes")
                    require(raw.length() in 2..100000)
                    val refs = (0 until raw.length()).map { n ->
                        if (n % 128 == 0) checkActive()
                        positiveId(raw.get(n))
                    }
                    val sourceTags=tags(e.optJSONObject("tags"))
                    ways += RoadAwareExposure.Way(id, refs, sourceTags["highway"],sourceTags)
                }
                else -> error("Unexpected source element kind")
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for (raw in listOf(traceJson, sourceJson)) {
            checkActive()
            val bytes = raw.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        digest.update("|receipts:$observedAtMillis:$validUntilMillis|parents:".toByteArray(Charsets.UTF_8))
        completeParentNodeIds.sorted().forEach { digest.update("$it;".toByteArray(Charsets.UTF_8)) }
        val revision = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return RoadAwareExposure.Observation(shape, spans, nodes, ways, completeParentNodeIds,
            "Exact edge-walk/source-observation SHA256:$revision; provider receipts required; not a legality/entrance certificate",
            observedAtMillis, validUntilMillis).also { checkActive() }
    }

    private fun tags(raw: JSONObject?): Map<String, String> {
        if (raw == null) return emptyMap()
        require(raw.length() <= 4096)
        val result = LinkedHashMap<String, String>()
        val keys = raw.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = raw.get(key) as? String ?: error("Non-string source tag")
        }
        return result
    }
    private fun integer(raw: Any): Long = when (raw) {
        is Byte -> raw.toLong()
        is Short -> raw.toLong()
        is Int -> raw.toLong()
        is Long -> raw
        else -> error("Source identity/index must be an exact integral JSON number")
    }
    private fun positiveId(raw: Any) = integer(raw).also { require(it > 0) }
    private fun index(raw: Any) = integer(raw).also { require(it in 0..249999L) }.toInt()

    private fun polyline6(encoded: String, active: () -> Unit): List<GeoPoint> {
        val points = ArrayList<GeoPoint>()
        var cursor = 0
        var latitude = 0L; var longitude = 0L
        fun delta(): Long {
            var value = 0L; var shift = 0
            while (true) {
                require(cursor < encoded.length && shift <= 30) { "Malformed trace polyline" }
                val part = encoded[cursor++].code-63
                require(part in 0..63)
                value = value or ((part and 31).toLong() shl shift)
                if (part < 32) return if (value and 1L == 1L) (value shr 1).inv() else value shr 1
                shift += 5
            }
        }
        while (cursor < encoded.length) {
            if (points.size % 128 == 0) active()
            require(points.size < 250000)
            latitude = Math.addExact(latitude, delta()); longitude = Math.addExact(longitude, delta())
            require(latitude in -90000000..90000000 && longitude in -180000000..180000000)
            points += GeoPoint(latitude/1e6, longitude/1e6)
        }
        require(points.size >= 2)
        return points
    }
}
