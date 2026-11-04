package com.bggg.watchman

import com.bggg.watchman.routing.*
import com.bggg.watchman.routing.ConditionalRestriction.Match
import com.bggg.watchman.routing.MotorcarTagPolicy.AccessClass
import com.bggg.watchman.routing.MotorcarTagPolicy.Direction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random

fun roadPolicyTimelineSmoke() {
    val at=Instant.parse("2026-10-05T12:00:15Z")
    val public=mapOf("highway" to "residential")
    class Graph:MotorcarGraphTraversal.Graph,TurnRelationCompiler.Geometry {
        override var revision="timeline-synthetic-graph"
        var road=MotorcarGraphTraversal.Arc(1,101,10,20,Direction.FORWARD,public)
        var nodeTags=emptyMap<String,String>()
        override fun arc(id:Long)=road.takeIf { it.id==id }
        override fun node(id:Long)=MotorcarGraphTraversal.Node(id,nodeTags)
        override fun turnCoverage(arc:MotorcarGraphTraversal.Arc)=MotorcarGraphTraversal.Coverage(true,emptySet(),emptyList())
        override fun way(id:Long)=TurnRelationCompiler.Way(id,listOf(10,20))
        override fun arc(wayId:Long,segmentOrdinal:Int,forward:Boolean)=1L
    }
    val graph=Graph()
    val timeline=RoadPolicyTimeline(graph)
    fun snapshot(kind:RoadPolicyTimeline.Kind=RoadPolicyTimeline.Kind.ARC,id:Long=1,
        zone:ZoneId?=ZoneId.of("UTC"),facts:Map<String,Match> = emptyMap(),
        holidays:Map<Pair<String,LocalDate>,Match> = emptyMap())=RoadPolicyTimeline.Snapshot(
        graph.revision,kind,id,"synthetic-frozen-context",
        "Explicit synthetic whole-source horizon, not discovered real-world jurisdiction/weather/permission",
        Instant.parse("1900-01-01T00:00:00Z"),Instant.parse("2030-01-01T00:00:00Z"),zone,facts,holidays=holidays)
    fun way(from:Instant=at,until:Instant=at.plusSeconds(120),context:RoadPolicyTimeline.Snapshot=snapshot(),
        policy:EndpointAccessPolicy?=null,state:EndpointAccessPolicy.State?=null,maximum:Int=10000)=
        timeline.way(graph.road,RoadArcPortion(1,0.0,1.0,graph.road.direction),from,until,context,policy,state,maximum)
    check(way().allowed==Match.YES)
    val closure="no @ Mo 12:01-12:02"
    graph.road=graph.road.copy(tags=public+("access:conditional" to closure))
    val interiorClosure=way()
    check(interiorClosure.allowed==Match.NO && Instant.parse("2026-10-05T12:01:00Z") in interiorClosure.evaluatedAt)
    check(MotorcarRoadProfile.way(graph.road.tags,Direction.FORWARD,snapshot().context(at)).publicThrough==Match.YES)
    check(MotorcarRoadProfile.way(graph.road.tags,Direction.FORWARD,snapshot().context(at.plusSeconds(120))).publicThrough==Match.YES)
    check(way(context=snapshot(zone=null)).allowed==Match.UNKNOWN)
    check(way(maximum=2).allowed==Match.UNKNOWN)
    graph.road=graph.road.copy(tags=public+("access:conditional" to "no @ sunset-sunrise"))
    check(way().allowed==Match.UNKNOWN)
    val mutableFacts=mutableMapOf("wet" to Match.NO)
    val frozen=snapshot(facts=mutableFacts)
    mutableFacts["wet"]=Match.YES
    graph.road=graph.road.copy(tags=public+("access:conditional" to "no @ wet"))
    check(way(context=frozen).allowed==Match.YES && way().allowed==Match.UNKNOWN)
    @Suppress("UNCHECKED_CAST")
    check(runCatching { (frozen.facts as MutableMap<String,Match>).clear() }.isFailure)
    graph.road=graph.road.copy(tags=public+("access:conditional" to "no @ PH"))
    check(way().allowed==Match.UNKNOWN)
    check(way(context=snapshot(holidays=mapOf(("PH" to LocalDate.of(2026,10,5)) to Match.NO))).allowed==Match.YES)
    graph.road=graph.road.copy(tags=public+("oneway:conditional" to "-1 @ Mo 12:01-12:02"))
    check(way().allowed==Match.NO)
    graph.nodeTags=mapOf("barrier" to "gate","opening_hours" to "Mo 12:00-12:01,12:02-12:03")
    check(timeline.node(10,Direction.FORWARD,at,at.plusSeconds(120),snapshot(RoadPolicyTimeline.Kind.NODE,10)).allowed==Match.NO)
    graph.nodeTags=emptyMap()
    graph.road=graph.road.copy(tags=public+("access:conditional" to "no @ Su 02:00-03:00"))
    check(way(Instant.parse("2026-03-08T06:59:15Z"),Instant.parse("2026-03-08T07:01:15Z"),
        snapshot(zone=ZoneId.of("America/New_York"))).allowed==Match.YES) // nonexistent spring hour
    graph.road=graph.road.copy(tags=public+("access:conditional" to "no @ Su 01:20-01:30"))
    check(way(Instant.parse("2026-11-01T05:35:00Z"),Instant.parse("2026-11-01T06:35:00Z"),
        snapshot(zone=ZoneId.of("America/New_York"))).allowed==Match.NO) // repeated fall hour

    val origin=RoadEndpointAnchor(graph.revision,1,10,20,0.0)
    val destination=RoadEndpointAnchor(graph.revision,1,10,20,1.0)
    fun policy(grants:List<EndpointAccessPolicy.Grant>)=EndpointAccessPolicy(graph,origin,destination,null,
        EndpointAccessPolicy.scope(graph,setOf(101),grants))
    val expires=Instant.parse("2026-10-05T12:01:00.500Z")
    fun grant(from:Instant?=null,until:Instant?=null)=EndpointAccessPolicy.Grant(AccessClass.PRIVATE,
        EndpointAccessPolicy.Basis.INDIVIDUAL_PERMISSION,"Synthetic individually granted access",from,until)
    graph.road=graph.road.copy(tags=public+("access" to "private"))
    val gap=policy(listOf(grant(until=expires),grant(from=expires.plusMillis(1000))))
    val gapResult=way(policy=gap,state=gap.start(origin))
    check(gapResult.allowed==Match.NO && expires in gapResult.evaluatedAt)
    val continuous=policy(listOf(grant(until=expires),grant(from=expires)))
    check(way(policy=continuous,state=continuous.start(origin)).allowed==Match.YES)
    graph.road=graph.road.copy(tags=public+("access:conditional" to "destination @ Mo 12:01-12:02"))
    val ingress=policy(listOf(EndpointAccessPolicy.Grant(AccessClass.DESTINATION,
        EndpointAccessPolicy.Basis.ENDPOINT_PURPOSE,"Synthetic exact destination purpose")))
    check(way(policy=ingress,state=ingress.start(origin)).let {
        it.allowed==Match.UNKNOWN && it.diagnostics.any { message -> "mid-interval" in message }
    })
    graph.road=graph.road.copy(tags=public)
    check(way(context=snapshot(RoadPolicyTimeline.Kind.NODE,10)).allowed==Match.UNKNOWN)
    val old=snapshot();graph.revision="changed"
    check(way(context=old).allowed==Match.UNKNOWN)
    graph.revision=origin.sourceRevision
    check(way(until=at.minusSeconds(1)).allowed==Match.UNKNOWN)
    check(way(until=at.plusSeconds(8*86400)).allowed==Match.UNKNOWN)

    // Independent each-second oracle on short exact-second intervals, not production partitions.
    val random=Random(1002)
    val schedules=listOf("Mo 12:01-12:02","Mo 12:00-12:01,12:02-12:03","Tu 00:00-01:00","Mo 23:59-00:01")
    repeat(80) { index ->
        graph.road=graph.road.copy(tags=public+("access:conditional" to "no @ ${schedules[index%schedules.size]}"))
        val start=at.plusSeconds(random.nextInt(180).toLong()-60)
        val seconds=random.nextInt(240)+1
        val context=snapshot()
        val outcomes=(0..seconds).map { offset -> MotorcarRoadProfile.way(graph.road.tags,Direction.FORWARD,
            context.context(start.plusSeconds(offset.toLong()))).publicThrough }
        val expected=when { Match.NO in outcomes -> Match.NO;Match.UNKNOWN in outcomes -> Match.UNKNOWN;else -> Match.YES }
        check(way(start,start.plusSeconds(seconds.toLong()),context).allowed==expected)
    }
    println("Continuous road/node timeline: interior closures, exact local-minute/zone/DST and grant-expiry boundaries, immutable facts/calendar/horizon, permission-state guards and80 independent per-second oracles PASS; actual context discovery/timing/control/provider acceptance remain separate")
}
