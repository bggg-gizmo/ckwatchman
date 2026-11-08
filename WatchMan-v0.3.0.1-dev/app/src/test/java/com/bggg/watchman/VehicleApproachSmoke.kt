package com.bggg.watchman

import com.bggg.watchman.data.*
import com.bggg.watchman.routing.RoadAwareExposure as Policy
import com.bggg.watchman.storage.RoutingDeviceSnapshot
import com.bggg.watchman.storage.SearchResultEvidenceJson
import org.json.JSONObject
import com.bggg.watchman.util.Geo

fun vehicleApproachSmoke() {
    val a=GeoPoint(0.0004,0.002); val b=GeoPoint(0.0004,0.018)
    fun address(point: GeoPoint,id: Long=200,road: String="SYNTHETIC_ROAD"): SearchResult {
        val raw=JSONObject().put("lat",point.lat.toString()).put("lon",point.lon.toString())
            .put("osm_type","way").put("osm_id",id).put("category","place").put("type","house")
            .put("address",JSONObject().put("road",road).put("house_number","FIXTURE_NUMBER"))
        return SearchResult("SYNTHETIC_ADDRESS",point,checkNotNull(SearchResultEvidenceJson.fromNominatim(
            raw,"https://nominatim.example.invalid/search",point)))
    }
    val origin=address(a.copy(lat=0.00045));val destination=address(b.copy(lat=0.00045))
    val route=Route(listOf(a,GeoPoint(0.0004,0.01),b),listOf(RouteStep("Synthetic instruction",a,1800.0)),
        1800.0,200.0,destination,"synthetic source",requestedOrigin=origin.point)
    val nodes=listOf(Policy.Node(11,GeoPoint(0.0,0.0),emptyMap()),
        Policy.Node(12,GeoPoint(0.0,0.003),mapOf("highway" to "traffic_signals")),
        Policy.Node(13,GeoPoint(0.0,0.02),emptyMap()),
        Policy.Node(21,GeoPoint(0.0004,0.0),emptyMap()),
        Policy.Node(22,GeoPoint(0.0004,0.003),mapOf("highway" to "traffic_signals")),
        Policy.Node(23,GeoPoint(0.0004,0.02),emptyMap()))
    val publicTags=mapOf("highway" to "residential","name" to "SYNTHETIC_ROAD")
    val signal=CameraPoi("node/12",CameraType.TRAFFIC,nodes[1].point,null,null,null,null,null,
        sourceRecordId="node/12",tags=mapOf("highway" to "traffic_signals"))
    fun snapshot(camera: CameraPoi=signal)=RoutingDeviceSnapshot.freeze(listOf(camera),"synthetic-approach")
    fun source(path: Route=route,originInput: SearchResult?=origin,tags: Map<String,String> = publicTags,
        extraNodes: List<Policy.Node> = nodes,refs: List<Long> = listOf(21,22,23)) = Policy.Observation(
        path.points,listOf(Policy.Span(200,0,path.points.lastIndex)),extraNodes,
        listOf(Policy.Way(100,listOf(11,12,13),"residential"),Policy.Way(200,refs,tags["highway"],tags)),
        setOf(12,22),"Synthetic complete source + actual road-address observation",1000,2000,originInput)
    val proof=checkNotNull(Policy.verify(route,source(),1100))
    val originApproach=checkNotNull(proof.originApproach) { "Expected source-bound public street origin" }
    val destinationApproach=checkNotNull(proof.destinationApproach) { "Expected source-bound public street destination" }
    check(Geo.distanceMeters(originApproach.point,a)<0.001 && Geo.distanceMeters(destinationApproach.point,b)<0.001)
    check(proof.originApproach?.original==origin && proof.destinationApproach?.original==destination)
    check(Policy.assess(route,snapshot(),proof,1100).awarenessOnlyDevices==listOf(signal))
    val absent=checkNotNull(Policy.verify(route,source(originInput=null),1100))
    check(absent.originApproach==null && Policy.assess(route,snapshot(),absent,1100).unknownEncounters==1)
    // Lawful approach is not an exemption from actual driving-path exposure, any family.
    val onRoad=signal.copy(id="node/22",sourceRecordId="node/22",point=nodes[4].point)
    check(Policy.assess(route,snapshot(onRoad),proof,1100).verifiedRoadEncounters==1)
    val rf=signal.copy(type=CameraType.RF_SENSOR,manufacturer="SignalTrace")
    check(Policy.assess(route,snapshot(rf),proof,1100).unknownEncounters==1)
    val addressOnly=rf.copy(point=GeoPoint(0.00204,0.002))
    check(Policy.assess(route,snapshot(addressOnly),proof,1100).awarenessOnlyDevices==listOf(addressOnly))
    check(Policy.assess(route,snapshot(addressOnly),absent,1100).unknownEncounters==1)
    for(tags in listOf(publicTags+("access" to "private"),publicTags+("access" to "destination"),
        publicTags+("oneway" to "-1"),publicTags+("access:conditional" to "yes @ (Mo-Fr 08:00-18:00)"),
        publicTags+("maxheight" to "3"),publicTags+("smoothness" to "impassable"),
        publicTags+("highway" to "footway"),publicTags+("area" to "yes"))) {
        val refused=checkNotNull(Policy.verify(route,source(tags=tags),1100))
        check(refused.originApproach==null && refused.destinationApproach==null)
        check(Policy.assess(route,snapshot(),refused,1100).unknownEncounters==1)
    }
    check(Policy.verify(route,source(originInput=address(origin.point,999)),1100)?.originApproach==null)
    check(Policy.verify(route,source(originInput=address(origin.point,road="DISPLAY_ALIAS")),1100)?.originApproach!=null)
    val originalEvidence=checkNotNull(address(origin.point,999).geocoderEvidence)
    val forged=GeocoderEvidence(originalEvidence.providerId,originalEvidence.requestUrl,origin.point,"way",200,
        originalEvidence.featureClass,originalEvidence.featureType,originalEvidence.address,null,originalEvidence.rawRecord)
    check(Policy.verify(route,source(originInput=origin.copy(geocoderEvidence=forged)),1100)?.originApproach==null)
    val moved=route.copy(points=listOf(a.copy(lon=a.lon+0.0001))+route.points.drop(1))
    check(Policy.verify(moved,source(path=moved),1100)?.originApproach==null)
    val gate=Policy.Node(26,a,mapOf("barrier" to "gate"))
    check(Policy.verify(route,source(extraNodes=nodes+gate,refs=listOf(21,26,22,23)),1100)?.originApproach==null)
    val loop=Policy.Node(24,nodes[3].point,emptyMap())
    check(Policy.verify(route,source(extraNodes=nodes+loop,refs=listOf(21,22,23,24)),1100)?.originApproach==null)
    check(Policy.verify(route,source(),2000)==null)
    check(runCatching { Policy.verify(route,source(),1100) { error("CANCELLED") } }.isFailure)
    println("Vehicle approach: actual source road/geometry/public-direction binding, original awareness, all-family driving exposure and private/conditional/limit/barrier/ambiguity/movement/forgery/expiry guards PASS; host evidence, no entrance or unavoidable-exposure certificate")
}
