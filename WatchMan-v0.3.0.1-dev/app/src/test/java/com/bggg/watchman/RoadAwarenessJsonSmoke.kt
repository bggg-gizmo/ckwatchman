package com.bggg.watchman

import com.bggg.watchman.data.*
import com.bggg.watchman.routing.RoadAwareExposure
import com.bggg.watchman.routing.RoadExposureObservationJson
import com.bggg.watchman.storage.RoutingDeviceSnapshot
import org.json.JSONArray
import org.json.JSONObject

fun roadAwarenessJsonSmoke() {
    val path = listOf(GeoPoint(0.0004,0.0), GeoPoint(0.0004,0.01), GeoPoint(0.0004,0.02))
    val route = Route(path, emptyList(), 2000.0, 200.0, SearchResult("SYNTHETIC_ENDPOINT", path.last()))
    fun node(id: Int, p: GeoPoint, signal: Boolean = false) = JSONObject().put("type", "node").put("id", id)
        .put("lat", p.lat).put("lon", p.lon).put("tags", JSONObject().apply { if (signal) put("highway", "traffic_signals") })
    fun way(id: Int, refs: List<Int>) = JSONObject().put("type", "way").put("id", id)
        .put("nodes", JSONArray(refs)).put("tags", JSONObject().put("highway", "residential"))
    val elements = JSONArray()
    for (i in 0..2) elements.put(node(21+i, path[i]))
    for (i in 0..2) elements.put(node(11+i, path[i].copy(lat=0.0), i==1))
    elements.put(way(100, listOf(11,12,13))).put(way(200, listOf(21,22,23)))
    val source = JSONObject().put("elements", elements)
    val shape = JSONObject().put("type", "LineString").put("coordinates", JSONArray().apply {
        path.forEach { put(JSONArray().put(it.lon).put(it.lat)) }
    })
    val trace = JSONObject().put("shape", shape).put("edges", JSONArray().put(JSONObject()
        .put("way_id",200).put("begin_shape_index",0).put("end_shape_index",2)))
    val camera = CameraPoi("node/12", CameraType.TRAFFIC, GeoPoint(0.0,0.01), null,null,null,null,null,
        sourceRecordId="node/12", tags=mapOf("highway" to "traffic_signals"))
    val snapshot = RoutingDeviceSnapshot.freeze(listOf(camera), "synthetic-wire-road-trial")
    fun decoded(rawTrace: String = trace.toString(), rawSource: String = source.toString(), parents: Set<Long> = setOf(12)) =
        RoadExposureObservationJson.decode(rawTrace, rawSource, parents, 1000, 2000)
    val observation = decoded()
    val proof = checkNotNull(RoadAwareExposure.verify(route, observation, 1100))
    val result = RoadAwareExposure.assess(route, snapshot, proof, 1100)
    check(result.encounters.isEmpty() && result.awarenessOnlyDevices == listOf(camera))
    // Untrusted JSON claims cannot replace complete-parent receipts supplied by the provider.
    val untrusted = JSONObject(source.toString()).put("verified",true).put("completeParentNodeIds",JSONArray().put(12))
    val withoutReceipts = decoded(rawSource=untrusted.toString(), parents=emptySet())
    check(RoadAwareExposure.assess(route,snapshot,RoadAwareExposure.verify(route,withoutReceipts,1100),1100).unknownEncounters==1)
    fun reject(block: () -> Unit) = check(runCatching(block).isFailure)
    reject { decoded(rawTrace=trace.toString().replace("\"way_id\":200","\"way_id\":200.5")) }
    reject { decoded(rawSource=JSONObject(source.toString()).put("remark","partial source response").toString()) }
    val badTag = JSONObject(source.toString())
    badTag.getJSONArray("elements").getJSONObject(0).getJSONObject("tags").put("highway",42)
    reject { decoded(rawSource=badTag.toString()) }
    reject { decoded(rawTrace=JSONObject(trace.toString()).put("shape","_").toString()) }
    reject { decoded(rawTrace=JSONObject(trace.toString()).put("shape","??").toString()) }
    // Independent signed-delta encoder exercises negative values and polyline6 precision.
    fun encoded(points: List<GeoPoint>): String = buildString {
        var lat=0L; var lon=0L
        fun appendDelta(delta: Long) {
            var bits = if(delta<0) (delta shl 1).inv() else delta shl 1
            while(bits>=32) { append(((bits and 31) or 32).toInt().plus(63).toChar()); bits=bits shr 5 }
            append((bits.toInt()+63).toChar())
        }
        for(p in points) {
            val nextLat=Math.round(p.lat*1e6); val nextLon=Math.round(p.lon*1e6)
            appendDelta(nextLat-lat); appendDelta(nextLon-lon);lat=nextLat;lon=nextLon
        }
    }
    val decodedPolyline = decoded(rawTrace=JSONObject(trace.toString()).put("shape",encoded(path)).toString())
    check(decodedPolyline.shape==path)
    check(RoadAwareExposure.assess(route,snapshot,RoadAwareExposure.verify(route,decodedPolyline,1100),1100).weightedExposure==0L)
    val signed = path.map { GeoPoint(-it.lat, if(it.lon == 0.0) 0.0 else -it.lon) }
    check(decoded(rawTrace=JSONObject(trace.toString()).put("shape",encoded(signed)).toString()).shape==signed)
    reject { (observation.shape as MutableList<GeoPoint>).clear() }
    reject { (observation.ways.getValue(100).nodeIds as MutableList<Long>).clear() }
    check(observation.sourceEvidence != decoded(parents=emptySet()).sourceEvidence)
    reject { RoadExposureObservationJson.decode(trace.toString(),source.toString(),setOf(12),1000,2000) { error("CANCELLED") } }
    println("Road awareness source decoder: GeoJSON/polyline6, exact numeric identities, malformed/partial/false-claim/receipt binding, immutable records and cancellation PASS; host evidence, not Android acceptance")
}
