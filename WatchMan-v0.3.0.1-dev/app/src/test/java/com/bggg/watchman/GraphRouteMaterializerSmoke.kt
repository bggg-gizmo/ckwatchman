package com.bggg.watchman

import com.bggg.watchman.data.*
import com.bggg.watchman.routing.*
import com.bggg.watchman.routing.ConditionalRestriction.Match
import java.time.Instant
import java.time.ZoneId

fun graphRouteMaterializerSmoke() {
    val points = mapOf(1L to RoadSnapGeometry.Point(0.0, 0.0),
        2L to RoadSnapGeometry.Point(0.0, 0.001), 3L to RoadSnapGeometry.Point(0.001, 0.001),
        4L to RoadSnapGeometry.Point(-0.001, 0.001), 5L to RoadSnapGeometry.Point(-0.001, 0.002))
    fun arc(id: Long, way: Long, from: Long, to: Long, name: String) =
        MotorcarGraphTraversal.Arc(id, way, from, to, MotorcarTagPolicy.Direction.FORWARD,
            mapOf("highway" to "residential", "name" to name))
    val arcs = listOf(arc(1,10,1,2,"Start Road"), arc(2,20,2,3,"Camera Road"),
        arc(3,30,2,4,"West Detour").let { it.copy(tags=it.tags+("ref" to "T-30")) },
        arc(4,31,4,5,"North Detour"), arc(5,32,5,3,"Arrival Road").let {
            it.copy(tags=it.tags+("destination:forward" to "Public destination"))
        }).associateBy { it.id }
    var revision = "materialization-fixture-1"
    var deviceRevision = "materialization-devices-1"
    val graph = object : MotorcarGraphTraversal.Graph {
        override val revision get() = revision
        override fun arc(id: Long) = arcs[id]
        override fun node(id: Long) = points[id]?.let { MotorcarGraphTraversal.Node(id, emptyMap()) }
        override fun turnCoverage(arc: MotorcarGraphTraversal.Arc) =
            MotorcarGraphTraversal.Coverage(true, emptySet(), emptyList())
    }
    val source = object : GraphRouteMaterializer.Source {
        override val graph = graph
        override val deviceRevision get() = deviceRevision
        override fun sourceGeometry(arcId: Long): GraphRouteMaterializer.Geometry {
            val arc=arcs.getValue(arcId)
            return GraphRouteMaterializer.Geometry(revision,arcId,arc.from,arc.to,
                points.getValue(arc.from),points.getValue(arc.to),100_000)
        }
    }
    var controlDelay=0L
    var stopAfterDetourTiming=false
    var deadlineReached=false
    val network = object : NoCamsGraphSearch.Network {
        override val graph=graph
        override val deviceRevision get() = deviceRevision
        override fun outgoing(node: Long)=arcs.values.filter { it.from==node }.map { it.id }
        override fun lengthMm(arcId: Long)=100_000L
        override fun timing(arc:MotorcarGraphTraversal.Arc,portion:RoadArcPortion,approach:NoCamsGraphSearch.Approach):NoCamsGraphSearch.Timing {
            if(stopAfterDetourTiming && arc.id==3L)deadlineReached=true
            return NoCamsGraphSearch.Timing(1000,Match.YES,departureDelayMillis=if(arc.id==3L)controlDelay else 0,
                waitAllowed=Match.YES,waitEvidence="Explicit lawful node-wait synthetic oracle")
        }
        override fun exposures(arc:MotorcarGraphTraversal.Arc,portion:RoadArcPortion,departure:Instant,arrival:Instant)=
            if(arc.id==2L)listOf(NoCamsGraphSearch.Exposure("known-device",1)) else emptyList()
    }
    val origin=RoadEndpointAnchor(revision,1,1,2,0.25)
    val destination=RoadEndpointAnchor(revision,2,2,3,1.0)
    val named=SearchResult("Public synthetic fixture",GeoPoint(0.001,0.001))
    val at=Instant.parse("2026-10-02T16:00:00Z")
    val result=NoCamsGraphSearch(network,emptyList()) { _,time -> RestrictionCondition.Context(time,ZoneId.of("UTC")) }
        .search(origin,destination,at,NoCamsGraphSearch.Bounds(1_000_000,10_000))
    check(result.stop==NoCamsGraphSearch.Stop.FOUND && result.route!!.segments.map { it.portion.arcId }==listOf(1L,3L,4L,5L))
    fun materialize(value:NoCamsGraphSearch.Result=result,actual:GraphRouteMaterializer.Source=source)=
        GraphRouteMaterializer.materialize(value,actual,origin,destination,named,GraphRouteMaterializer.DrivingSide.RIGHT)
    fun reject(action:()->Unit)=check(runCatching(action).isFailure)
    val output=materialize()
    val start=RoadSnapGeometry.pointAt(points.getValue(1),points.getValue(2),0.25)
    check(output.route.points==listOf(GeoPoint(start.latitude,start.longitude),GeoPoint(0.001,0.0),
        GeoPoint(0.001,-0.001),GeoPoint(0.002,-0.001),GeoPoint(0.001,0.001)))
    check(output.route.distanceMeters==375.0 && output.route.durationSeconds==4.0)
    check(output.route.steps.size==5 && output.route.steps[1].maneuverModifier=="left")
    check(output.route.steps[1].roadRef=="T-30" && output.route.steps[2].maneuverModifier=="right")
    check(output.route.steps.last().maneuverType=="arrive" && output.route.steps.sumOf { it.distanceMeters }==375.0)
    check(output.route.steps[3].destinations=="Public destination" && !output.route.provider.contains("OSRM"))
    check(output.devices.isEmpty() && output.graphRevision==revision && output.deviceRevision==deviceRevision)
    check(!materialize(result.copy(optimalWithinEvaluatedSnapshot=false)).optimalWithinEvaluatedSnapshot)
    val path=result.route!!
    val first=path.segments.first()
    val split=listOf(first.copy(portion=RoadArcPortion(1,0.25,0.5),arrival=at.plusMillis(500),distanceMm=25_000),
        first.copy(portion=RoadArcPortion(1,0.5,1.0),departure=at.plusMillis(500),travelDeparture=at.plusMillis(500),distanceMm=50_000))+path.segments.drop(1)
    val splitOutput=materialize(result.copy(route=path.copy(segments=split)))
    check(splitOutput.route.distanceMeters==375.0 && splitOutput.route.durationSeconds==4.0)
    check(splitOutput.route.steps==output.route.steps && splitOutput.route.points.size==6)
    controlDelay=500
    val waited=NoCamsGraphSearch(network,emptyList()) { _,time -> RestrictionCondition.Context(time,ZoneId.of("UTC")) }
        .search(origin,destination,at,NoCamsGraphSearch.Bounds(1_000_000,10_000))
    val waitedOutput=materialize(waited)
    check(waitedOutput.route.points==output.route.points && waitedOutput.route.steps==output.route.steps)
    check(waitedOutput.route.distanceMeters==375.0 && waitedOutput.route.durationSeconds==4.5)
    val waitedPath=waited.route!!
    val waitSegment=waitedPath.segments[1]
    check(waitSegment.departure==at.plusMillis(1000) && waitSegment.travelDeparture==at.plusMillis(1500))
    check(waitSegment.checks.single { it.location.phase==MotorcarGraphTraversal.Phase.WAY_ENTRY }.instant==waitSegment.travelDeparture)
    fun replaceWait(replacement:NoCamsGraphSearch.Segment)=waited.copy(route=waitedPath.copy(
        segments=waitedPath.segments.mapIndexed { i,s -> if(i==1)replacement else s }))
    reject { materialize(replaceWait(waitSegment.copy(waitEvidence=null))) }
    reject { materialize(replaceWait(waitSegment.copy(checks=waitSegment.checks.filter {
        it.location.phase!=MotorcarGraphTraversal.Phase.WAIT_NODE }))) }
    reject { materialize(replaceWait(waitSegment.copy(travelDeparture=waitSegment.arrival))) }
    controlDelay=0
    for(stop in listOf(NoCamsGraphSearch.Stop.DEADLINE,NoCamsGraphSearch.Stop.RESOURCE_LIMIT,NoCamsGraphSearch.Stop.EXHAUSTED)) {
        val bounded=materialize(result.copy(stop=stop,diagnostics=listOf("Explicit search bound")))
        check(bounded.route==output.route && bounded.searchStop==stop && !bounded.optimalWithinEvaluatedSnapshot)
        check(bounded.diagnostics==listOf("Explicit search bound"))
    }
    for(stop in listOf(NoCamsGraphSearch.Stop.CANCELLED,NoCamsGraphSearch.Stop.SOURCE_CHANGED,NoCamsGraphSearch.Stop.INVALID_DATA))
        reject { materialize(result.copy(stop=stop)) }
    // Materialize a REAL deadline incumbent, not only a FOUND result with a copied stop.
    stopAfterDetourTiming=true
    val actualDeadline=NoCamsGraphSearch(network,emptyList()) { _,time ->
        RestrictionCondition.Context(time,ZoneId.of("UTC"))
    }.search(origin,destination,at,NoCamsGraphSearch.Bounds(1_000_000,10_000),
        nanoTime={0L},deadlineReached={deadlineReached})
    check(actualDeadline.stop==NoCamsGraphSearch.Stop.DEADLINE && actualDeadline.route!=null)
    val boundedActual=materialize(actualDeadline)
    check(boundedActual.searchStop==NoCamsGraphSearch.Stop.DEADLINE && !boundedActual.optimalWithinEvaluatedSnapshot)
    check(boundedActual.devices==mapOf("known-device" to 1L) &&
        boundedActual.segments.map { it.portion.arcId }==listOf(1L,2L))
    check(boundedActual.route.distanceMeters==175.0 && boundedActual.route.durationSeconds==2.0 &&
        boundedActual.route.points.last()==named.point)
    stopAfterDetourTiming=false;deadlineReached=false
    reject { GraphRouteMaterializer.materialize(actualDeadline,source,origin,destination,named,
        GraphRouteMaterializer.DrivingSide.RIGHT,interrupted={true}) }
    var sourceInterrupted=false
    val interruptedSource=object:GraphRouteMaterializer.Source {
        override val graph=graph
        override val deviceRevision get()=deviceRevision
        override fun sourceGeometry(arcId:Long):GraphRouteMaterializer.Geometry {
            if(arcId==3L)sourceInterrupted=true
            return source.sourceGeometry(arcId)
        }
    }
    reject { GraphRouteMaterializer.materialize(result,interruptedSource,origin,destination,named,
        GraphRouteMaterializer.DrivingSide.RIGHT,interrupted={sourceInterrupted}) }
    // Supersession at the final cooperative publication check also refuses the result.
    var publicationChecks=0
    GraphRouteMaterializer.materialize(result,source,origin,destination,named,
        GraphRouteMaterializer.DrivingSide.RIGHT,interrupted={publicationChecks++;false})
    val finalCheck=publicationChecks
    check(finalCheck>result.route!!.segments.size)
    publicationChecks=0
    reject { GraphRouteMaterializer.materialize(result,source,origin,destination,named,
        GraphRouteMaterializer.DrivingSide.RIGHT,interrupted={++publicationChecks==finalCheck}) }
    reject { materialize(result.copy(route=null)) }
    @Suppress("UNCHECKED_CAST")
    reject { (output.route.points as MutableList<GeoPoint>).clear() }
    @Suppress("UNCHECKED_CAST")
    reject { (output.route.steps as MutableList<RouteStep>).clear() }
    @Suppress("UNCHECKED_CAST")
    reject { (output.segments as MutableList<NoCamsGraphSearch.Segment>).clear() }
    reject { materialize(result.copy(route=path.copy(cost=path.cost.copy(distanceMm=1)))) }
    reject { materialize(result.copy(route=path.copy(devices=mapOf("invented" to 1L)))) }
    reject { materialize(result.copy(route=path.copy(segments=path.segments.mapIndexed { i,s ->
        if(i==1)s.copy(departure=s.departure.plusMillis(1)) else s
    }))) }
    reject { materialize(result.copy(route=path.copy(segments=path.segments.drop(1)))) }
    reject { materialize(result.copy(route=path.copy(segments=path.segments.dropLast(1)))) }
    reject { GraphRouteMaterializer.materialize(result,source,origin,destination,named,GraphRouteMaterializer.DrivingSide.RIGHT,3) }
    val wrong=object:GraphRouteMaterializer.Source {
        override val graph=graph
        override val deviceRevision get()=deviceRevision
        override fun sourceGeometry(arcId:Long)=source.sourceGeometry(arcId).copy(arcId=999)
    }
    reject { materialize(actual=wrong) }
    revision="changed";reject { materialize() };revision=origin.sourceRevision
    deviceRevision="changed";reject { materialize() };deviceRevision=result.deviceRevision

    val backward=MotorcarGraphTraversal.Arc(10,10,2,1,MotorcarTagPolicy.Direction.BACKWARD,
        mapOf("highway" to "residential","name" to "Reverse Road"))
    val reverseGraph=object:MotorcarGraphTraversal.Graph {
        override val revision="reverse-fixture"
        override fun arc(id:Long)=backward.takeIf { id==10L }
        override fun node(id:Long)=MotorcarGraphTraversal.Node(id,emptyMap())
        override fun turnCoverage(arc:MotorcarGraphTraversal.Arc)=MotorcarGraphTraversal.Coverage(true,emptySet(),emptyList())
    }
    val reverseSource=object:GraphRouteMaterializer.Source {
        override val graph=reverseGraph
        override val deviceRevision="reverse-devices"
        override fun sourceGeometry(arcId:Long)=GraphRouteMaterializer.Geometry(reverseGraph.revision,10,2,1,
            points.getValue(2),points.getValue(1),100_001)
    }
    val portion=RoadArcPortion(10,0.25,0.75,MotorcarTagPolicy.Direction.BACKWARD)
    val reversePath=NoCamsGraphSearch.Route(listOf(NoCamsGraphSearch.Segment(portion,at,at.plusMillis(1200),
        portion.distanceMm(100_001),emptyList())),NoCamsGraphSearch.Cost(0,1200,portion.distanceMm(100_001)),emptyMap())
    val reverseResult=NoCamsGraphSearch.Result(reversePath,NoCamsGraphSearch.Stop.FOUND,true,
        reverseGraph.revision,reverseSource.deviceRevision,1,2,emptyMap(),emptyList())
    check(reverseSource.sourceGeometry(10).graphRevision==reverseGraph.revision)
    val reverse=GraphRouteMaterializer.materialize(reverseResult,reverseSource,
        RoadEndpointAnchor(reverseGraph.revision,10,2,1,0.25),RoadEndpointAnchor(reverseGraph.revision,10,2,1,0.75),
        named,GraphRouteMaterializer.DrivingSide.LEFT)
    check(reverse.route.points.first().lat>reverse.route.points.last().lat)
    check(reverse.route.steps.first().instruction.startsWith("Head south") && reverse.route.steps.all { it.drivingSide=="left" })
    check(reverse.route.distanceMeters==portion.distanceMm(100_001)/1000.0)
    println("PASS graph materializer: full-search camera-free original-road detour, exact partial geometry/time/distance/maneuvers, split/reverse and invalid/source-change guards")
}
