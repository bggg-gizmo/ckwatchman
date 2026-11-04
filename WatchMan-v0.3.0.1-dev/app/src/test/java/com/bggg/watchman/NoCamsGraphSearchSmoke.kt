package com.bggg.watchman

import com.bggg.watchman.routing.NoCamsGraphSearch as Search
import com.bggg.watchman.routing.MotorcarGraphTraversal as Traversal
import com.bggg.watchman.routing.MotorcarTagPolicy.Direction
import com.bggg.watchman.routing.RestrictionCondition
import com.bggg.watchman.routing.RoadArcPortion
import com.bggg.watchman.routing.RoadEndpointAnchor
import com.bggg.watchman.routing.ConditionalRestriction.Match
import java.time.Instant
import java.time.ZoneId
import java.util.Random
import kotlin.math.ceil

fun noCamsGraphSearchSmoke() {
    val at=Instant.parse("2026-09-28T12:00:00Z")
    data class Edge(val id:Long,val from:Long,val to:Long,val mm:Long,val ms:Long,
        val records:List<Pair<Double,Search.Exposure>> = emptyList(),val tags:Map<String,String> = emptyMap())
    class Network:Search.Network,Traversal.Graph {
        override val graph get()=this
        override var revision="synthetic-road-snapshot"
        override var deviceRevision="synthetic-device-snapshot"
        val edges=linkedMapOf<Long,Edge>()
        val nodes=mutableMapOf<Long,Map<String,String>>()
        var unknownArc:Long?=null
        var mutate=false
        var stationary=false
        var timingOverride:((Traversal.Arc,RoadArcPortion,Search.Approach)->Search.Timing)?=null
        override fun stationarity(journey:Search.Journey)=if(stationary)Search.Stationarity(journey,
            "Synthetic source, context, grants and timing proven constant over the entire requested journey horizon") else null
        override fun arc(id:Long)=edges[id]?.let{Traversal.Arc(it.id,it.id,it.from,it.to,Direction.FORWARD,
            mapOf("highway" to "service")+it.tags)}
        override fun node(id:Long)=Traversal.Node(id,nodes[id].orEmpty())
        override fun turnCoverage(arc:Traversal.Arc)=Traversal.Coverage(true,emptySet(),emptyList())
        override fun outgoing(node:Long)=edges.values.filter{it.from==node}.map{it.id}
        override fun lengthMm(arcId:Long)=edges.getValue(arcId).mm
        override fun timing(arc:Traversal.Arc,portion:RoadArcPortion,approach:Search.Approach)=
            timingOverride?.invoke(arc,portion,approach) ?: Search.Timing(ceil(edges.getValue(arc.id).ms*(portion.toFraction-portion.fromFraction)).toLong().coerceAtLeast(1),
                if(arc.id==unknownArc)Match.UNKNOWN else Match.YES,listOf("Explicit time-invariant synthetic fixture"))
        override fun exposures(arc:Traversal.Arc,portion:RoadArcPortion,departure:Instant,arrival:Instant):List<Search.Exposure> {
            if(mutate)deviceRevision="changed"
            return edges.getValue(arc.id).records.filter{it.first>=portion.fromFraction && it.first<=portion.toFraction}.map{it.second}
        }
        fun anchor(id:Long,fraction:Double)=edges.getValue(id).let{RoadEndpointAnchor(revision,id,it.from,it.to,fraction)}
    }
    fun search(n:Network)=Search(n,emptyList()){_,time->RestrictionCondition.Context(time,ZoneId.of("UTC"))}
    fun mark(id:String,weight:Long=1,fraction:Double=0.5)=fraction to Search.Exposure(id,weight)
    fun fixture()=Network().also{n->
        n.edges[1]=Edge(1,1,9,1000,1000,listOf(mark("arterial")),mapOf("highway" to "primary"))
        for(i in 2L..7L)n.edges[i]=Edge(i,if(i==2L)1 else i,if(i==7L)9 else i+1,200,1000)
    }
    val bounds=Search.Bounds(10000,60000)
    fun run(n:Network,b:Search.Bounds=bounds,budget:Search.Budget=Search.Budget())=
        search(n).search(n.anchor(1,0.0),n.anchor(1,1.0),at,b,budget)
    fun path(result:Search.Result)=result.route!!.segments.map{it.portion.arcId}
    val side=fixture()
    val avoided=run(side)
    check(avoided.stop==Search.Stop.FOUND && avoided.optimalWithinEvaluatedSnapshot)
    check(path(avoided)==(2L..7L).toList() && avoided.route!!.cost==Search.Cost(0,6000,1200))
    check(avoided.route.segments.size>3)
    val bounded=run(side,Search.Bounds(1100,60000))
    check(path(bounded)==listOf(1L) && bounded.route!!.cost.exposure==1L)
    check(path(run(side,Search.Bounds(10000,2000)))==listOf(1L))
    // Unavoidable departure exposure remains counted, not hidden by an endpoint exclusion radius.
    side.edges[1]=side.edges.getValue(1).copy(records=listOf(mark("arterial"),mark("origin",3,0.0)))
    side.edges[2]=side.edges.getValue(2).copy(records=listOf(mark("origin",3,0.0)))
    check(run(side).route!!.let{it.cost.exposure==3L && it.devices==mapOf("origin" to 3L)})
    side.edges[7]=side.edges.getValue(7).copy(records=listOf(mark("arrival",8,1.0)))
    check(path(run(side))==listOf(1L))
    val dedup=fixture()
    dedup.edges[1]=dedup.edges.getValue(1).copy(records=listOf(mark("arterial",5)))
    for(i in 2L..7L)dedup.edges[i]=dedup.edges.getValue(i).copy(records=listOf(mark("one-logical-device"),mark("one-logical-device")))
    check(run(dedup).route!!.let{it.cost.exposure==1L && it.devices.size==1})
    val weighted=fixture()
    weighted.edges[1]=weighted.edges.getValue(1).copy(records=listOf(mark("a"),mark("b")))
    weighted.edges[4]=weighted.edges.getValue(4).copy(records=listOf(mark("high-weight",8)))
    check(path(run(weighted))==listOf(1L))
    val equal=fixture();equal.edges[4]=equal.edges.getValue(4).copy(records=listOf(mark("side")))
    check(path(run(equal))==listOf(1L))
    for(tags in listOf(mapOf("access" to "private"),mapOf("oneway" to "-1"))) {
        val illegal=fixture();illegal.edges[1]=illegal.edges.getValue(1).copy(records=emptyList(),tags=tags)
        check(path(run(illegal))==(2L..7L).toList())
    }
    val barrier=fixture();barrier.nodes[6]=mapOf("barrier" to "block")
    check(path(run(barrier))==listOf(1L))
    val uncertain=fixture();uncertain.unknownArc=1
    check(run(uncertain).let{path(it)==(2L..7L).toList() && !it.optimalWithinEvaluatedSnapshot && "interval_UNKNOWN" in it.rejections})
    val partial=fixture()
    partial.edges[2]=partial.edges.getValue(2).copy(records=listOf(mark("before-origin",9,0.1),mark("at-origin",2,0.5)))
    partial.edges[7]=partial.edges.getValue(7).copy(records=listOf(mark("at-destination",3,0.5),mark("after-destination",9,0.9)))
    val partialResult=search(partial).search(partial.anchor(2,0.5),partial.anchor(7,0.5),at,bounds)
    check(partialResult.route!!.let{it.cost==Search.Cost(5,5000,1000) && it.devices.keys==setOf("at-origin","at-destination")})
    check(partialResult.route.segments.first().portion.fromFraction==0.5 && partialResult.route.segments.last().portion.toFraction==0.5)
    val limited=run(fixture(),budget=Search.Budget(maximumExpanded=1))
    check(limited.stop==Search.Stop.RESOURCE_LIMIT && !limited.optimalWithinEvaluatedSnapshot && limited.route!=null)
    val stopped=fixture()
    check(search(stopped).search(stopped.anchor(1,0.0),stopped.anchor(1,1.0),at,bounds,cancelled={true}).stop==Search.Stop.CANCELLED)
    var ticks=0L
    check(search(stopped).search(stopped.anchor(1,0.0),stopped.anchor(1,1.0),at,bounds,
        Search.Budget(deadlineMillis=1),nanoTime={ticks+=1_000_000;ticks}).stop==Search.Stop.DEADLINE)
    // A whole-operation deadline is NOT external cancellation. Reach a real complete
    // incumbent first, then expire while exploring its lower-exposure alternative.
    val externalDeadline=fixture()
    var deadlineReached=false
    externalDeadline.timingOverride={arc,portion,_ ->
        if(arc.id==2L)deadlineReached=true
        Search.Timing(ceil(externalDeadline.edges.getValue(arc.id).ms*
            (portion.toFraction-portion.fromFraction)).toLong().coerceAtLeast(1),Match.YES)
    }
    val incumbentAtDeadline=search(externalDeadline).search(externalDeadline.anchor(1,0.0),
        externalDeadline.anchor(1,1.0),at,bounds,nanoTime={0L},deadlineReached={deadlineReached})
    check(incumbentAtDeadline.stop==Search.Stop.DEADLINE && !incumbentAtDeadline.optimalWithinEvaluatedSnapshot)
    check(path(incumbentAtDeadline)==listOf(1L) && incumbentAtDeadline.route!!.cost==Search.Cost(1,1000,1000))
    val immediate=fixture()
    val immediateDeadline=search(immediate).search(immediate.anchor(1,0.0),immediate.anchor(1,1.0),at,bounds,
        nanoTime={0L},deadlineReached={true})
    check(immediateDeadline.stop==Search.Stop.DEADLINE && immediateDeadline.route==null)
    deadlineReached=false
    val superseded=search(externalDeadline).search(externalDeadline.anchor(1,0.0),
        externalDeadline.anchor(1,1.0),at,bounds,cancelled={deadlineReached},nanoTime={0L},
        deadlineReached={deadlineReached})
    check(superseded.stop==Search.Stop.CANCELLED && !superseded.optimalWithinEvaluatedSnapshot)
    // A changed immutable source invalidates even an incumbent at the deadline.
    deadlineReached=false
    val changedAtDeadline=search(externalDeadline).search(externalDeadline.anchor(1,0.0),
        externalDeadline.anchor(1,1.0),at,bounds,nanoTime={0L},deadlineReached={
            if(deadlineReached)externalDeadline.deviceRevision="changed-at-deadline"
            deadlineReached
        })
    check(changedAtDeadline.stop==Search.Stop.SOURCE_CHANGED && changedAtDeadline.route==null)
    val changed=fixture();changed.mutate=true
    check(run(changed).let{it.stop==Search.Stop.SOURCE_CHANGED && it.route==null})
    val conflict=fixture()
    conflict.edges[1]=conflict.edges.getValue(1).copy(records=listOf(mark("same",1)))
    conflict.edges[2]=conflict.edges.getValue(2).copy(records=listOf(mark("same",2)))
    check(run(conflict).let{it.stop==Search.Stop.INVALID_DATA && it.route==null})
    check(run(fixture(),Search.Bounds(50,60000)).let{it.stop==Search.Stop.EXHAUSTED && it.route==null})
    // A stationary, camera-free dead-end circuit must not bury an exposed reachable goal.
    fun circuit()=fixture().also { n ->
        for(i in 2L..7L)n.edges.remove(i)
        n.edges[20]=Edge(20,1,20,1,100)
        n.edges[21]=Edge(21,20,21,1,100)
        n.edges[22]=Edge(22,21,20,1,100)
    }
    val noProof=circuit()
    check(run(noProof,budget=Search.Budget(maximumExpanded=10)).stop==Search.Stop.RESOURCE_LIMIT)
    val withProof=circuit();withProof.stationary=true
    check(run(withProof,budget=Search.Budget(maximumExpanded=10)).let{
        it.stop==Search.Stop.FOUND && it.optimalWithinEvaluatedSnapshot && path(it)==listOf(1L) &&
            it.rejections.getValue("dominated_label")>0})
    // Exact-time labels keep a later legal continuation. Earlier arrival is not always better.
    val temporal=Network()
    temporal.edges[1]=Edge(1,1,9,100,100)
    temporal.edges[20]=Edge(20,1,2,1,100)
    temporal.edges[21]=Edge(21,2,3,1,100)
    temporal.edges[22]=Edge(22,3,2,1,100)
    temporal.edges[23]=Edge(23,2,9,1,100)
    temporal.timingOverride={arc,_,approach ->
        val allowed=arc.id!=1L && (arc.id!=23L || approach.state.instant>=at.plusMillis(700))
        Search.Timing(100,if(allowed)Match.YES else Match.NO)
    }
    check(run(temporal).let{it.stop==Search.Stop.FOUND && it.route!!.cost==Search.Cost(0,800,8) &&
        path(it)==listOf(20L,21L,22L,21L,22L,21L,22L,23L)})
    // Equal node/time labels arriving from different roads have different movement timing.
    val approached=Network()
    approached.edges[1]=Edge(1,1,9,100,100,listOf(mark("baseline",10)))
    approached.edges[2]=Edge(2,1,2,10,100)
    approached.edges[3]=Edge(3,2,4,10,100)
    approached.edges[4]=Edge(4,1,3,10,100)
    approached.edges[5]=Edge(5,3,4,10,100)
    approached.edges[6]=Edge(6,4,9,10,100)
    approached.stationary=true
    approached.timingOverride={arc,_,approach ->
        Search.Timing(if(arc.id==6L && approach.incoming?.arcId!=5L)10000 else 100,Match.YES)
    }
    check(path(run(approached,Search.Bounds(1000,2000)))==listOf(4L,5L,6L))
    // Smaller current weight is insufficient dominance: future exposure may already be counted.
    val distinctSets=Network()
    distinctSets.edges[1]=Edge(1,1,9,100,100,listOf(mark("baseline",100)))
    distinctSets.edges[10]=Edge(10,1,2,10,100,listOf(mark("x",5)))
    distinctSets.edges[11]=Edge(11,1,3,10,100,listOf(mark("y",1)))
    distinctSets.edges[12]=Edge(12,2,4,10,100)
    distinctSets.edges[13]=Edge(13,3,4,10,100)
    distinctSets.edges[14]=Edge(14,4,5,10,100)
    distinctSets.edges[15]=Edge(15,5,9,10,100,listOf(mark("x",5)))
    distinctSets.stationary=true
    check(run(distinctSets).let{it.route!!.cost.exposure==5L && path(it)==listOf(10L,12L,14L,15L)})
    check(run(fixture(),budget=Search.Budget(maximumRetainedLabels=1)).let{
        it.stop==Search.Stop.RESOURCE_LIMIT && !it.optimalWithinEvaluatedSnapshot})
    val cancelChanged=fixture()
    check(search(cancelChanged).search(cancelChanged.anchor(1,0.0),cancelChanged.anchor(1,1.0),at,bounds,
        cancelled={cancelChanged.deviceRevision="changed-during-cancel";true}).let{
            it.stop==Search.Stop.SOURCE_CHANGED && it.route==null})
    // A real departure-changing wait must shift road policy checks, not just increase ETA.
    val waiting=Network()
    waiting.edges[1]=Edge(1,1,9,100,1000,tags=mapOf("access:conditional" to "no @ Mo 12:01-12:02"))
    val nearBoundary=at.plusSeconds(59)
    var waitPermission=Match.YES
    waiting.timingOverride={_,_,_ -> Search.Timing(1000,Match.YES,departureDelayMillis=2000,
        waitAllowed=waitPermission,waitEvidence="Synthetic continuous lawful-node waiting certificate") }
    fun runWait(origin:RoadEndpointAnchor=waiting.anchor(1,0.0),maximumMs:Long=10000)=
        search(waiting).search(origin,waiting.anchor(1,1.0),nearBoundary,Search.Bounds(10000,maximumMs))
    check(runWait().let { it.route==null && "traversal_NO" in it.rejections }) // closes during wait
    waiting.edges[1]=waiting.edges.getValue(1).copy(tags=mapOf("access" to "no","access:conditional" to "yes @ Mo 12:01-12:02"))
    val afterOpening=runWait()
    check(afterOpening.stop==Search.Stop.FOUND && afterOpening.route!!.cost==Search.Cost(0,3000,100))
    val waited=afterOpening.route.segments.single()
    check(waited.departure==nearBoundary && waited.travelDeparture==nearBoundary.plusMillis(2000) &&
        waited.arrival==nearBoundary.plusMillis(3000) && waited.waitEvidence!=null)
    check(waited.checks.count { it.location.phase==Traversal.Phase.WAIT_NODE }==2)
    check(waited.checks.single { it.location.phase==Traversal.Phase.WAY_ENTRY }.instant==waited.travelDeparture)
    check(runWait(maximumMs=2500).let { it.route==null && "duration_bound" in it.rejections })
    waitPermission=Match.UNKNOWN
    check(runWait().let { it.route==null && "wait_UNKNOWN" in it.rejections && !it.optimalWithinEvaluatedSnapshot })
    waitPermission=Match.NO
    check(runWait().let { it.route==null && "wait_NO" in it.rejections })
    waitPermission=Match.YES
    check(runWait(waiting.anchor(1,0.5)).let { it.route==null && "wait_UNKNOWN" in it.rejections })
    waiting.nodes[1]=mapOf("access:conditional" to "no @ Mo 12:01-12:02")
    check(runWait().let { it.route==null && "wait_NO" in it.rejections })
    check(runCatching { Search.Timing(1,Match.YES,departureDelayMillis=1,waitAllowed=Match.YES) }.isFailure)
    check(runCatching { Search.Timing(1,Match.YES,departureDelayMillis=-1) }.isFailure)
    // Exhaustive DAG oracle does not use the production search, state labels or scorer.
    val random=Random(9028)
    repeat(150) {
        val n=Network().also{it.stationary=true};var id=0L
        for(from in 1L..7L)for(to in from+1..8L)if(to==from+1 || random.nextBoolean()) {
            val records=(0..4).filter{random.nextInt(4)==0}.map{mark("device$it",it+1L)}
            n.edges[++id]=Edge(id,from,to,50+random.nextInt(450).toLong(),100+random.nextInt(1900).toLong(),records,
                if(random.nextInt(7)==0)mapOf("access" to "private") else emptyMap())
        }
        val origin=n.edges.values.first{it.from==1L};val target=n.edges.values.first{it.to==8L}
        val expected=ArrayList<Triple<Long,Long,Long>>()
        fun enumerate(node:Long,distance:Long,duration:Long,devices:Map<String,Long>) {
            if(distance>1500 || duration>7000)return
            if(node==8L){expected+=Triple(devices.values.sum(),duration,distance);return}
            for(e in n.edges.values.filter{it.from==node && it.tags["access"]!="private"})
                enumerate(e.to,distance+e.mm,duration+e.ms,devices+e.records.map{it.second.deviceId to it.second.weight})
        }
        enumerate(1,0,0,emptyMap())
        val best=expected.minWithOrNull(compareBy<Triple<Long,Long,Long>>{it.first}.thenBy{it.second}.thenBy{it.third})
        val actual=search(n).search(n.anchor(origin.id,0.0),n.anchor(target.id,1.0),at,Search.Bounds(1500,7000))
        if(best==null)check(actual.stop==Search.Stop.EXHAUSTED && actual.route==null)
        else check(actual.stop==Search.Stop.FOUND && actual.optimalWithinEvaluatedSnapshot &&
            actual.route!!.cost.let{Triple(it.exposure,it.durationMillis,it.distanceMm)}==best)
    }
    println("No Cams whole-path search: multi-edge side roads, whole/endpoint/late exposure, weighted unique identities, partial endpoints, legal constraints, bounded dominance, stationary circuits, exact-time openings, approach-sensitive timing, device-set counterexamples, source/deadline/resource guards and150 exhaustive DAG comparisons PASS; real PBF adapters, full timing/exposure/waiting policy, app integration and real-address performance remain pending")
}
