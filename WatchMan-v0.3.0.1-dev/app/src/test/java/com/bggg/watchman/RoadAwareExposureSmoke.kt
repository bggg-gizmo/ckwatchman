package com.bggg.watchman

import com.bggg.watchman.data.*
import com.bggg.watchman.routing.RoadAwareExposure as Policy
import com.bggg.watchman.routing.NoCamsObstaclePlanner
import com.bggg.watchman.routing.NoCamsRouteMonitor
import com.bggg.watchman.routing.RoutePlanningPolicy
import com.bggg.watchman.storage.RoutingDeviceSnapshot

fun roadAwareExposureSmoke() {
    val lower = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01), GeoPoint(0.0, 0.02))
    val upper = lower.map { it.copy(lat = 0.0004) }
    fun route(points: List<GeoPoint>) = Route(points, emptyList(), 2000.0, 200.0,
        SearchResult("SYNTHETIC_ENDPOINT", points.last()), "synthetic road source")
    val signal = CameraPoi("node/12", CameraType.TRAFFIC, lower[1], null, null, null, null, null,
        sourceId = "synthetic-osm", sourceRecordId = "node/12", tags = mapOf("highway" to "traffic_signals"))
    fun snapshot(camera: CameraPoi = signal) = RoutingDeviceSnapshot.freeze(listOf(camera), "synthetic-road-trial")
    val nodes = lower.mapIndexed { i, p -> Policy.Node(11L+i, p, if (i == 1) mapOf("highway" to "traffic_signals") else emptyMap()) } +
        upper.mapIndexed { i, p -> Policy.Node(21L+i, p, emptyMap()) }
    val ways = listOf(Policy.Way(100, listOf(11,12,13), "residential"), Policy.Way(200, listOf(21,22,23), "residential"))
    fun observation(path: Route, way: Long = 200, sourceNodes: List<Policy.Node> = nodes,
        parents: Set<Long> = setOf(12), spans: List<Policy.Span> = listOf(Policy.Span(way, 0, path.points.lastIndex)),
        shape: List<GeoPoint> = path.points) = Policy.Observation(shape, spans, sourceNodes, ways, parents,
            "synthetic exact edge walk + complete source node/way observations", 1000, 2000)
    val bypass = route(upper)
    val proof = checkNotNull(Policy.verify(bypass, observation(bypass), 1100))
    val clear = Policy.assess(bypass, snapshot(), proof, 1100)
    check(clear.nearbyRelevantDevices == listOf(signal) && clear.encounters.isEmpty())
    check(clear.awarenessOnlyDevices == listOf(signal) && clear.weightedExposure == 0L)
    val incompleteParents = nodes.filter { it.id != 11L }
    check(Policy.assess(bypass,snapshot(),Policy.verify(bypass,observation(bypass,sourceNodes=incompleteParents),1100),1100).unknownEncounters==1)
    // Different source IDs on coincident/stacked road geometry cannot establish avoidance.
    val coincidentNodes = nodes.map { if(it.id in 11L..13L) Policy.Node(it.id,it.point.copy(lat=0.0004),it.tags) else it }
    val coincidentSignal = signal.copy(point=signal.point.copy(lat=0.0004))
    check(Policy.assess(bypass,snapshot(coincidentSignal),
        Policy.verify(bypass,observation(bypass,sourceNodes=coincidentNodes),1100),1100).unknownEncounters==1)
    val crossingParents = nodes.map { when(it.id) {
        11L -> Policy.Node(it.id,GeoPoint(-0.01,0.01),it.tags)
        13L -> Policy.Node(it.id,GeoPoint(0.01,0.01),it.tags)
        else -> it
    } }
    check(Policy.assess(bypass,snapshot(),Policy.verify(bypass,observation(bypass,sourceNodes=crossingParents),1100),1100).unknownEncounters==1)
    // Unknown/custom way classes must not disappear from the affected-parent set.
    val customRoute = route(listOf(GeoPoint(0.0,0.0105),GeoPoint(0.0,0.011)))
    val customNodes = nodes + listOf(Policy.Node(31,GeoPoint(-0.01,0.01),emptyMap()),Policy.Node(33,GeoPoint(0.01,0.01),emptyMap()))
    val customWays = listOf(Policy.Way(100,listOf(11,12,13),"custom_unresolved"),Policy.Way(300,listOf(31,12,33),"residential"))
    val customObservation = Policy.Observation(customRoute.points,listOf(Policy.Span(100,0,1)),customNodes,
        customWays,setOf(12),"Synthetic complete parent context including an unresolved class",1000,2000)
    check(Policy.assess(customRoute,snapshot(),Policy.verify(customRoute,customObservation,1100),1100).weightedExposure==1L)
    val crossing = route(lower)
    val hit = Policy.assess(crossing, snapshot(), Policy.verify(crossing, observation(crossing, 100), 1100), 1100)
    check(hit.encounters == listOf(signal) && hit.verifiedRoadEncounters == 1 && hit.weightedExposure == 1L)
    check(hit.roadEncounters.single().segmentIndices == listOf(0,1))
    val reverse = route(lower.reversed())
    check(Policy.assess(reverse, snapshot(), Policy.verify(reverse, observation(reverse, 100), 1100), 1100).weightedExposure == 1L)
    // No guessed directional/FOV exemption. Unknown source coverage keeps the full radius.
    for (missing in listOf(null, Policy.verify(bypass, observation(bypass, parents = emptySet()), 1100)))
        check(Policy.assess(bypass, snapshot(), missing, 1100).unknownEncounters == 1)
    check(Policy.assess(bypass, snapshot(), proof, 2000).unknownEncounters == 1)
    check(Policy.verify(bypass, observation(bypass), 999) == null)
    val moved = nodes.map { if (it.id == 12L) Policy.Node(it.id, it.point.copy(lat = 0.00002), it.tags) else it }
    check(Policy.assess(bypass, snapshot(), Policy.verify(bypass, observation(bypass, sourceNodes = moved), 1100), 1100).unknownEncounters == 1)
    for (type in CameraType.entries.filter { it != CameraType.TRAFFIC }) {
        val other = signal.copy(type = type, manufacturer = "SYNTHETIC_SENSOR_VENDOR", tags = signal.tags + ("sensor" to "yes"))
        check(Policy.assess(bypass, snapshot(other), proof, 1100).unknownEncounters == 1)
    }
    val enriched = signal.copy(tags = signal.tags + ("camera" to "yes"))
    check(Policy.assess(bypass, snapshot(enriched), proof, 1100).unknownEncounters == 1)
    check(Policy.assess(bypass, snapshot(signal.copy(manufacturer="SYNTHETIC_SENSOR_VENDOR")), proof, 1100).unknownEncounters == 1)
    check(Policy.assess(bypass, snapshot(signal.copy(tags=signal.tags+("man_made" to "surveillance"))), proof, 1100).unknownEncounters == 1)
    for (product in listOf("EOC+", "EOC Plus", "SignalTrace", "Flock Raven acoustic", "ELSAG MPH900",
        "Rekor", "Genetec AutoVu", "Vigilant Solutions", "Wavetronix traffic sensor", "SoundThinking")) {
        val changedSource = nodes.map { if(it.id == 12L) Policy.Node(it.id,it.point,it.tags+("name" to product)) else it }
        check(Policy.assess(bypass,snapshot(),Policy.verify(bypass,observation(bypass,sourceNodes=changedSource),1100),1100).unknownEncounters==1)
    }
    val gap = observation(bypass, spans = listOf(Policy.Span(200, 1, 2)))
    check(Policy.verify(bypass, gap, 1100) == null)
    check(Policy.assess(crossing, snapshot(), proof, 1100).unknownEncounters == 1) // Wrong route cannot reuse proof.
    val differentShape = observation(bypass, shape = lower)
    check(Policy.verify(bypass, differentShape, 1100) == null)
    // Endpoints alone match a source way; its intervening bend does not match the chord.
    val chord = route(listOf(upper.first(), upper.last()))
    val bentNodes = nodes.map { if (it.id == 22L) Policy.Node(it.id, it.point.copy(lat = 0.003), emptyMap()) else it }
    val uncertainBend = Policy.verify(chord, observation(chord, sourceNodes = bentNodes), 1100)
    check(Policy.assess(chord, snapshot(), uncertainBend, 1100).unknownEncounters == 1)
    val endpointOnly = bypass.copy(points = upper.map { it.copy(lat = 0.01) }, requestedOrigin = signal.point)
    check(Policy.assess(endpointOnly, snapshot(), null, 1100).unknownEncounters == 1)
    val unprovenConnector = bypass.copy(requestedOrigin=GeoPoint(0.0,0.0095))
    check(Policy.assess(unprovenConnector,snapshot(),
        Policy.verify(unprovenConnector,observation(unprovenConnector),1100),1100).unknownEncounters==1)

    // The actual planner must avoid an affected road while retaining a nearby signal
    // on a source-proven different road. No area disk may block that legal bypass.
    val navigation = listOf(RouteStep("Synthetic instruction", lower.first(), 2000.0))
    val initial = crossing.copy(steps=navigation)
    val detourPoints = listOf(lower.first()) + upper + lower.last()
    val detour = initial.copy(points=detourPoints, distanceMeters=2200.0,durationSeconds=240.0)
    val connectedWays = ways + listOf(Policy.Way(300,listOf(11,21),"residential"),Policy.Way(400,listOf(23,13),"residential"))
    fun plannerObservation(path: Route) = Policy.Observation(path.points,
        if(path.points==lower) listOf(Policy.Span(100,0,2)) else
            listOf(Policy.Span(300,0,1),Policy.Span(200,1,3),Policy.Span(400,3,4)),
        nodes,connectedWays,setOf(12),"Synthetic exact connected-road planner source",1000,2000)
    var routingCalls=0;var tracingCalls=0
    val planned=NoCamsObstaclePlanner(router={ _,_,exclusions,_ ->
        routingCalls++
        if(routingCalls==1) initial else {
            check(exclusions.devices.isEmpty() && exclusions.encounteredRoadPoints.size==1 && exclusions.totalDevices==1)
            detour
        }
    },readDevices={snapshot()},now={0},wallTime={1100},readRoadCoverage={path,_,_,claim ->
        claim();tracingCalls++;plannerObservation(path)
    }).plan(lower.first(),initial.destination)
    check(routingCalls==2 && tracingCalls==2 && planned.requests==2 && planned.attributionRequests==2)
    check(planned.assessment.weightedExposure==0L && planned.assessment.nearbyRelevantDevices==listOf(signal))
    check(planned.assessment.awarenessOnlyDevices==listOf(signal) && planned.coverage!=null)
    val before=checkNotNull(NoCamsRouteMonitor.remainingRoute(bypass,GeoPoint(0.0004,0.005)))
    val suffix=checkNotNull(Policy.remainingCoverage(bypass,before,proof,1100))
    val suffixAssessment=Policy.assess(before,snapshot(),suffix,1100)
    check(suffixAssessment.awarenessOnlyDevices==listOf(signal) && suffixAssessment.encounters.isEmpty())
    check(!NoCamsRouteMonitor.decision(suffixAssessment).obstacleAhead)
    val after=checkNotNull(NoCamsRouteMonitor.remainingRoute(bypass,GeoPoint(0.0004,0.015)))
    check(Policy.assess(after,snapshot(),Policy.remainingCoverage(bypass,after,proof,1100),1100).nearbyRelevantDevices.isEmpty())
    check(Policy.remainingCoverage(bypass,before,proof,2000)==null)
    check(Policy.remainingCoverage(crossing,before,proof,1100)==null)
    check(Policy.remainingCoverage(bypass,before.copy(points=before.points.reversed()),proof,1100)==null)
    val lost=NoCamsRouteMonitor.decision(Policy.assess(before,snapshot(),null,1100))
    check(lost.coverageUnknown && !lost.obstacleAhead) // No invented new-camera event from lost proof.
    check(NoCamsRouteMonitor.decision(hit).obstacleAhead)
    val otherFamily=signal.copy(type=CameraType.ALPR)
    check(NoCamsRouteMonitor.decision(Policy.assess(before,snapshot(otherFamily),null,1100)).obstacleAhead)
    val summary=Policy.summary(clear,proof,1100)
    val label=RoutePlanningPolicy.exposureLabel(RouteAnalysis(bypass,1,0.0,summary),1100)
    check(label.contains("1 nearby") && label.contains("0 routed"))
    check(RoutePlanningPolicy.exposureLabel(RouteAnalysis(bypass,1,0.0,summary),2000).contains("expired"))
    val budgetError=runCatching {
        NoCamsObstaclePlanner({_,_,_,_->initial},{snapshot()},now={0},wallTime={1100},
            readRoadCoverage={_,_,_,claim->repeat(8){claim()};null}).plan(lower.first(),initial.destination)
    }.exceptionOrNull()
    check(budgetError?.message.orEmpty().contains("bounded requests"))
    val dense=initial.copy(points=(0..1000).map { GeoPoint(0.0,it*0.00002) },requestedOrigin=lower.first())
    val layout=Policy.Observation(dense.points,listOf(Policy.Span(100,0,1000)),emptyList(),emptyList(),
        emptySet(),"Synthetic exact edge layout without any affected-road metadata",1000,2000)
    val layoutProof=checkNotNull(Policy.verify(dense,layout,1100))
    val near=(400..600).toList()
    check(Policy.blockingSegments(dense,signal,near,layoutProof,1100).size==1)
    check(Policy.assess(dense,snapshot(),layoutProof,1100).unknownEncounters==1) // Grouping grants no exemption.
    val twoEdges=Policy.Observation(dense.points,listOf(Policy.Span(100,0,500),Policy.Span(100,500,1000)),
        emptyList(),emptyList(),emptySet(),"Two provider edges on the SAME source way",1000,2000)
    check(Policy.blockingSegments(dense,signal,near,Policy.verify(dense,twoEdges,1100),1100).size==2)
    check(Policy.blockingSegments(dense,signal,near,layoutProof,2000).size==near.size)
    var denseCalls=0
    val densePlan=NoCamsObstaclePlanner(router={_,_,exclusions,_->
        denseCalls++
        if(denseCalls==1) dense else {
            check(exclusions.devices==listOf(signal) && exclusions.encounteredRoadPoints.size==1)
            initial.copy(points=listOf(lower.first(),GeoPoint(0.01,0.0),GeoPoint(0.01,0.02),lower.last()),
                distanceMeters=4500.0,durationSeconds=500.0)
        }
    },readDevices={snapshot()},now={0},wallTime={1100},readRoadCoverage={path,_,_,_->
        Policy.Observation(path.points,listOf(Policy.Span(100,0,path.points.lastIndex)),emptyList(),emptyList(),
            emptySet(),"Synthetic exact layout only, no source coverage",1000,2000)
    }).plan(lower.first(),initial.destination)
    check(denseCalls==2 && densePlan.assessment.weightedExposure==0L && densePlan.excludedDevices==1)
    check(runCatching { Policy.verify(bypass, observation(bypass), 1100) { error("CANCELLED") } }
        .exceptionOrNull()?.message == "CANCELLED")
    check(runCatching { Policy.assess(bypass, snapshot(), proof, 1100) { error("CANCELLED") } }
        .exceptionOrNull()?.message == "CANCELLED")
    println("Road-aware policy/planner: awareness retention, source-road hits, unknown/family/endpoint guards, shared request cap, exact suffix/monitor uncertainty and dense provider-edge grouping PASS; host evidence, not Android acceptance")
}
