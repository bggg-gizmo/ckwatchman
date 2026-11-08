package com.bggg.watchman

import com.bggg.watchman.data.*
import com.bggg.watchman.routing.*
import com.bggg.watchman.storage.RoutingDeviceSnapshot
import com.bggg.watchman.util.Geo

/** Synthetic road network only. Unknown crossing exposure remains charged until a
 * source-proven separate detour is found; no source/family/weight is discarded.
 */
fun roadRiskConstraintSmoke() {
    val sourceNodes=listOf(
        RoadAwareExposure.Node(11,GeoPoint(0.0,-0.01),emptyMap()),
        RoadAwareExposure.Node(12,GeoPoint(0.0,0.0),mapOf("highway" to "traffic_signals")),
        RoadAwareExposure.Node(13,GeoPoint(0.0,0.01),emptyMap()),
        RoadAwareExposure.Node(14,GeoPoint(0.0,0.0005),emptyMap()),
        RoadAwareExposure.Node(20,GeoPoint(0.0005,-0.003),emptyMap()),
        RoadAwareExposure.Node(21,GeoPoint(0.0005,-0.002),emptyMap()),
        RoadAwareExposure.Node(22,GeoPoint(0.0005,0.0005),emptyMap()),
        RoadAwareExposure.Node(23,GeoPoint(-0.0005,0.0005),emptyMap()),
        RoadAwareExposure.Node(24,GeoPoint(-0.003,-0.003),emptyMap()),
        RoadAwareExposure.Node(25,GeoPoint(-0.003,0.0005),emptyMap()))
    val points=sourceNodes.associate { it.id to it.point }
    val ways=listOf(RoadAwareExposure.Way(100,listOf(11,12,14,13),"residential"),
        RoadAwareExposure.Way(200,listOf(20,21,22),"residential"),
        RoadAwareExposure.Way(300,listOf(22,14,23),"residential"),
        RoadAwareExposure.Way(400,listOf(20,24,25,23),"residential"))
    val destination=SearchResult("SYNTHETIC_DESTINATION",points.getValue(23))
    fun route(ids: List<Long>,seconds: Double)=Route(ids.map(points::getValue),
        listOf(RouteStep("Follow synthetic legal roads",points.getValue(21),0.0)),
        ids.zipWithNext().sumOf { Geo.distanceMeters(points.getValue(it.first),points.getValue(it.second)) },
        seconds,destination,"synthetic source road network",requestedOrigin=points.getValue(21))
    val crossing=route(listOf(21,22,14,23),100.0)
    val detour=route(listOf(21,20,24,25,23),240.0)
    val signal=CameraPoi("node/12",CameraType.TRAFFIC,points.getValue(12),null,null,null,null,null,
        sourceRecordId="node/12",tags=mapOf("highway" to "traffic_signals"))
    fun snapshot(camera: CameraPoi=signal)=RoutingDeviceSnapshot.freeze(listOf(camera),"synthetic-road-risk")
    fun observation(path: Route,nodes: List<RoadAwareExposure.Node> = sourceNodes,parents: Set<Long> = setOf(12)) =
        RoadAwareExposure.Observation(path.points,if(path.points==crossing.points)
            listOf(RoadAwareExposure.Span(200,0,1),RoadAwareExposure.Span(300,1,2),RoadAwareExposure.Span(300,2,3))
        else listOf(RoadAwareExposure.Span(200,0,1),RoadAwareExposure.Span(400,1,4)),nodes,ways,parents,
            "Synthetic complete road-contact source, not a physical FOV or entrance certificate",1000,2000)
    val proof=checkNotNull(RoadAwareExposure.verify(crossing,observation(crossing),1100))
    val assessment=RoadAwareExposure.assess(crossing,snapshot(),proof,1100)
    check(assessment.unknownEncounters==1 && assessment.weightedExposure==1L && assessment.awarenessOnlyDevices.isEmpty())
    check(assessment.roadEncounters.isEmpty() && assessment.roadConstraints.single().segmentIndices==listOf(1,2))
    // A known parent-road hit must not hide a second ambiguous crossing: block both.
    val mixedWays=ways.map { if(it.id==200L) RoadAwareExposure.Way(200,listOf(12,20,21,22),"residential") else it }
    val mixedSource=RoadAwareExposure.Observation(crossing.points,observation(crossing).spans,sourceNodes,mixedWays,
        setOf(12),"Synthetic parent-road hit plus independent ambiguous crossing",1000,2000)
    val mixed=RoadAwareExposure.assess(crossing,snapshot(),RoadAwareExposure.verify(crossing,mixedSource,1100),1100)
    check(mixed.verifiedRoadEncounters==1 && mixed.weightedExposure==1L)
    check(mixed.roadEncounters.single().segmentIndices==listOf(0))
    check(mixed.roadConstraints.single().segmentIndices==listOf(0,1,2))
    val missingParentGeometry=observation(crossing,nodes=sourceNodes.filter { it.id!=11L })
    val mismatchedRoadGeometry=observation(crossing,nodes=sourceNodes.map {
        if(it.id==22L) RoadAwareExposure.Node(it.id,it.point.copy(lat=0.001),it.tags) else it })
    val missingParentSet=observation(crossing,parents=emptySet())
    for((source,time) in listOf(missingParentGeometry to 1100L,mismatchedRoadGeometry to 1100L,
        missingParentSet to 1100L,observation(crossing) to 2000L,observation(crossing) to 999L)) {
        val guarded=RoadAwareExposure.assess(crossing,snapshot(),RoadAwareExposure.verify(crossing,source,time),time)
        check(guarded.unknownEncounters==1 && guarded.weightedExposure==1L && guarded.roadConstraints.isEmpty())
    }
    val moved=signal.copy(point=signal.point.copy(lat=0.00002))
    check(RoadAwareExposure.assess(crossing,snapshot(moved),proof,1100).roadConstraints.isEmpty())
    val unbound=crossing.copy(requestedOrigin=GeoPoint(0.0004,-0.001))
    val unboundProof=RoadAwareExposure.verify(unbound,observation(unbound),1100)
    val endpoint=RoadAwareExposure.assess(unbound,snapshot(),unboundProof,1100)
    check(endpoint.unknownEncounters==1 && endpoint.roadConstraints.isEmpty())
    var calls=0
    val plan=NoCamsObstaclePlanner(router={_,_,exclusions,_->
        calls++
        if(calls==1) crossing else {
            check(exclusions.devices.isEmpty()) { "An uncertain crossing must not block a source-proven separate departure street by a blind area" }
            check(exclusions.totalDevices==1 && exclusions.encounteredRoadPoints.size==2)
            check(exclusions.encounteredRoadPoints.all { kotlin.math.abs(it.lon-0.0005)<1e-12 })
            detour
        }
    },readDevices={snapshot()},now={0},wallTime={1100},readRoadCoverage={path,_,_,_->observation(path)})
        .plan(crossing.points.first(),destination)
    check(calls==2 && plan.excludedDevices==1 && plan.assessment.weightedExposure==0L)
    check(plan.assessment.nearbyRelevantDevices==listOf(signal) && plan.assessment.awarenessOnlyDevices==listOf(signal))
    check(plan.snapshot.entries.single().weight==1L)
    for(type in CameraType.entries.filter { it!=CameraType.TRAFFIC }) {
        val guarded=RoadAwareExposure.assess(crossing,snapshot(signal.copy(type=type,
            manufacturer="SYNTHETIC_SENSOR_VENDOR")),proof,1100)
        check(guarded.unknownEncounters==1 && guarded.roadConstraints.isEmpty())
    }
    for(camera in listOf(signal.copy(type=CameraType.RF_SENSOR,manufacturer="SignalTrace"),
        signal.copy(type=CameraType.ALPR,manufacturer="SYNTHETIC_VENDOR"),
        signal.copy(tags=signal.tags+("sensor" to "yes")))) {
        var observedArea=false
        val failure=runCatching {
            NoCamsObstaclePlanner({_,_,exclusions,_->
                if(exclusions.devices.isNotEmpty()) { observedArea=true; error("Conservative area retained") }
                crossing
            },{snapshot(camera)},now={0},wallTime={1100},readRoadCoverage={path,_,_,_->observation(path)})
                .plan(crossing.points.first(),destination)
        }.exceptionOrNull()
        check(observedArea && failure?.message=="Conservative area retained")
    }
    println("Road-risk search: unknown crossing stays charged, all source-contact edges blocked, separate-road departure/detour retained and other sensor families conservative PASS; synthetic host evidence only")
}
