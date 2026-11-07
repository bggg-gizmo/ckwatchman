package com.bggg.watchman

import com.bggg.watchman.data.*
import com.bggg.watchman.routing.RoadAwareExposure
import com.bggg.watchman.routing.RoadCoverageSource
import com.bggg.watchman.routing.RoadSourceDeviceNormalizer
import com.bggg.watchman.storage.RoutingDeviceSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

fun roadCoverageSourceSmoke() {
    val root=File(System.getProperty("watchman.test.cache.root","build-road-source-tests"),java.util.UUID.randomUUID().toString())
    check(root.mkdirs())
    val upper=listOf(GeoPoint(0.0004,0.0),GeoPoint(0.0004,0.01),GeoPoint(0.0004,0.02))
    val route=Route(upper,emptyList(),2000.0,200.0,SearchResult("SYNTHETIC_ENDPOINT",upper.last()),"Valhalla camera exclusions")
    val camera=CameraPoi("node/12",CameraType.TRAFFIC,GeoPoint(0.0,0.01),null,null,null,null,null,
        sourceRecordId="node/12",tags=mapOf("highway" to "traffic_signals"))
    val snapshot=RoutingDeviceSnapshot.freeze(listOf(camera),"synthetic-source-reader")
    fun node(n: Int,p: GeoPoint)=JSONObject().put("type","node").put("id",n).put("lat",p.lat).put("lon",p.lon)
        .put("tags",JSONObject().apply { if(n==12) put("highway","traffic_signals") })
    fun way(n: Int,refs: List<Int>)=JSONObject().put("type","way").put("id",n).put("nodes",JSONArray(refs))
        .put("tags",JSONObject().put("highway","residential"))
    val nodes=(0..2).associate { 11+it to node(11+it,upper[it].copy(lat=0.0)) }+
        (0..2).associate { 21+it to node(21+it,upper[it]) }
    val ways=mapOf(100 to way(100,listOf(11,12,13)),200 to way(200,listOf(21,22,23)))
    fun response(rows: List<JSONObject>)=JSONObject().put("elements",JSONArray(rows)).toString()
    val correctTrace=JSONObject().put("shape",JSONObject().put("type","LineString").put("coordinates",JSONArray().apply {
        upper.forEach { put(JSONArray().put(it.lon).put(it.lat)) }
    })).put("edges",JSONArray().put(JSONObject().put("way_id",200).put("begin_shape_index",0).put("end_shape_index",2))).toString()
    var clock=0L
    var gets=0;var traces=0;var traceBudgetCallbacks=0
    var failMetadata=false;var badParent=false;var badTrace=false
    val get: (String,Int)->String = { url,timeout ->
        check(url.startsWith("https://api.openstreetmap.org/api/0.6/") && timeout in 1000..4000)
        gets++;clock+=20
        if(failMetadata) throw IOException("Synthetic metadata provider unavailable")
        when {
            url.endsWith("node/12/ways.json") -> response(listOf(if(badParent) way(100,listOf(11,13)) else ways.getValue(100)))
            url.contains("ways.json?ways=") -> response(url.substringAfter("ways.json?ways=").split(',').map { ways.getValue(it.toInt()) })
            url.contains("nodes.json?nodes=") -> response(url.substringAfter("nodes.json?nodes=").split(',').map { nodes.getValue(it.toInt()) })
            else -> error("Unexpected source request")
        }
    }
    val trace: (String,Int)->String = { body,timeout ->
        val request=JSONObject(body)
        check(request.getString("shape_match")=="edge_walk" && request.getJSONArray("shape").length()==upper.size)
        check(!request.getJSONObject("costing_options").getJSONObject("auto").getBoolean("ignore_access"))
        check(timeout in 1000..8000)
        traces++;clock+=25
        if(badTrace) JSONObject(correctTrace).put("shape","??").toString() else correctTrace
    }
    fun source(folder: String)=RoadCoverageSource(File(root,folder),get,trace,{1000000L+clock},{clock},{clock+=it})
    val reader=source("normal")
    val first=reader.observe(route,snapshot,25000,onTraceRequest={traceBudgetCallbacks++})
    val observation=checkNotNull(first.observation) { first.reason.orEmpty() }
    check(first.traceRequests==1 && first.metadataRequests==3 && traceBudgetCallbacks==1)
    check(first.sourceNodes.size==6 && first.sourceNodes.all { it.receivedAtMillis>observation.observedAtMillis })
    val discovered=first.sourceNodes.mapNotNull(RoadSourceDeviceNormalizer::normalize)
    check(discovered.size==1 && discovered.single().sourceRecordId=="node/12")
    check(discovered.single().lastUpdatedMillis==first.sourceNodes.single { it.id==12L }.receivedAtMillis)
    check(discovered.single().sourceId=="osm_road_observation" && discovered.single().type==CameraType.TRAFFIC)
    val ordinary=nodes.getValue(11).toString()
    check(RoadSourceDeviceNormalizer.normalize(RoadCoverageSource.NodeReceipt(11,ordinary,55))==null)
    val ordinaryOperator=JSONObject(ordinary).put("tags",JSONObject().put("operator","SYNTHETIC_OPERATOR")).toString()
    check(RoadSourceDeviceNormalizer.normalize(RoadCoverageSource.NodeReceipt(11,ordinaryOperator,55))==null)
    val rf=JSONObject(ordinary).put("tags",JSONObject().put("name","SignalTrace EOC+")).toString()
    check(RoadSourceDeviceNormalizer.normalize(RoadCoverageSource.NodeReceipt(11,rf,55))?.type==CameraType.RF_SENSOR)
    check(runCatching { RoadSourceDeviceNormalizer.normalize(RoadCoverageSource.NodeReceipt(99,ordinary,55)) }.isFailure)
    val assessment=RoadAwareExposure.assess(route,snapshot,RoadAwareExposure.verify(route,observation,1000000+clock),1000000+clock)
    check(assessment.awarenessOnlyDevices==listOf(camera) && assessment.weightedExposure==0L)
    val initialGets=gets;val initialTraces=traces
    val cached=checkNotNull(reader.observe(route,snapshot,25000,allowNetwork=false).observation)
    check(gets==initialGets && traces==initialTraces && cached.observedAtMillis==observation.observedAtMillis)
    val newUnknown=camera.copy(id="node/99",sourceRecordId="node/99")
    val extended=RoutingDeviceSnapshot.freeze(listOf(camera,newUnknown),"synthetic-new-device")
    val partial=checkNotNull(reader.observe(route,extended,25000,allowNetwork=false).observation)
    val partialAssessment=RoadAwareExposure.assess(route,extended,
        RoadAwareExposure.verify(route,partial,1000000+clock),1000000+clock)
    check(partialAssessment.awarenessOnlyDevices==listOf(camera) && partialAssessment.encounters==listOf(newUnknown))
    check(gets==initialGets && traces==initialTraces)
    val cacheFile=File(root,"normal/observations-v1.json")
    check(cacheFile.isFile && cacheFile.length() in 1..8_000_000L)
    check(File(root,"normal").listFiles().orEmpty().size==1) // One snapshot, not hundreds of tiny files.
    val cold=File(root,"copied").apply { check(mkdirs()) }
    cacheFile.copyTo(File(cold,cacheFile.name))
    checkNotNull(source("copied").observe(route,snapshot,25000,allowNetwork=false).observation)
    check(gets==initialGets && traces==initialTraces)
    val cancelled=reader.observe(route,snapshot,25000,cancelled={true})
    check(cancelled.observation==null && cancelled.traceRequests==0 && cancelled.metadataRequests==0)
    failMetadata=true
    val recovery=source("recovery")
    val failed=recovery.observe(route,snapshot,25000)
    check(failed.observation==null && failed.attribution!=null)
    val layoutProof=RoadAwareExposure.verify(route,checkNotNull(failed.attribution),1000000+clock)
    check(RoadAwareExposure.assess(route,snapshot,layoutProof,1000000+clock).unknownEncounters==1)
    val failedTraces=traces
    failMetadata=false
    checkNotNull(recovery.observe(route,snapshot,25000).observation)
    check(traces==failedTraces) // Resume successful attribution, not the whole network run.
    badParent=true
    val parentRetry=source("parent-retry")
    check(parentRetry.observe(route,snapshot,25000).observation==null)
    badParent=false
    checkNotNull(parentRetry.observe(route,snapshot,25000).observation)
    badTrace=true
    val traceRetry=source("trace-retry")
    check(traceRetry.observe(route,snapshot,25000).observation==null)
    badTrace=false
    checkNotNull(traceRetry.observe(route,snapshot,25000).observation)
    val beforeUnsupported=gets+traces
    check(reader.observe(route.copy(provider="SYNTHETIC_OTHER_PROVIDER"),snapshot,25000).observation==null)
    check(reader.observe(route.copy(points=List(16001) { upper.last() }),snapshot,25000).observation==null)
    check(gets+traces==beforeUnsupported)
    clock+=3600001
    val expired=reader.observe(route,snapshot,25000,allowNetwork=false)
    check(expired.observation==null && expired.traceRequests==0 && expired.metadataRequests==0)
    check(RoadAwareExposure.assess(route,snapshot,null,1000000+clock).weightedExposure==1L)
    val deadline=RoadCoverageSource(File(root,"deadline"),get,{ _,_ -> clock+=25000;correctTrace },
        {1000000L+clock},{clock},{clock+=it}).observe(route,snapshot,25000)
    check(deadline.observation==null && deadline.metadataRequests==0)
    println("Road coverage source: bounded exact attribution/API requests, original receipts, cold/warm/cache-only recovery, failed/malformed retry, expiry/deadline/cancel and no empty-exposure fallback PASS; mocked transport, not Android acceptance")
}
