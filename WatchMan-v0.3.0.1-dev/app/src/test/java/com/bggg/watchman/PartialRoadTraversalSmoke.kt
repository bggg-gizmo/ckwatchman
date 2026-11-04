package com.bggg.watchman

import com.bggg.watchman.routing.MotorcarGraphTraversal as Traversal
import com.bggg.watchman.routing.TurnRelationCompiler as Compiler
import com.bggg.watchman.routing.MotorcarTagPolicy.Direction
import com.bggg.watchman.routing.RoadArcPortion
import com.bggg.watchman.routing.RoadEndpointAnchor
import com.bggg.watchman.routing.RestrictionCondition
import com.bggg.watchman.routing.ConditionalRestriction.Match
import java.time.Instant
import java.time.ZoneId

fun partialRoadTraversalSmoke() {
    val at=Instant.parse("2026-09-18T12:00:00Z")
    val road=mapOf("highway" to "residential")
    class Graph:Traversal.Graph {
        override var revision="partial-source-1"
        val arcs=linkedMapOf(
            1L to Traversal.Arc(1,101,10,20,Direction.FORWARD,road),
            2L to Traversal.Arc(2,102,20,30,Direction.FORWARD,road),
            3L to Traversal.Arc(3,103,30,40,Direction.FORWARD,road),
            4L to Traversal.Arc(4,104,20,50,Direction.FORWARD,road),
            101L to Traversal.Arc(101,101,20,10,Direction.BACKWARD,road),
            102L to Traversal.Arc(102,102,30,20,Direction.BACKWARD,road),
            103L to Traversal.Arc(103,103,40,30,Direction.BACKWARD,road),
            104L to Traversal.Arc(104,104,50,20,Direction.BACKWARD,road))
        val nodes=(10L..50L step 10).associateWith{Traversal.Node(it,emptyMap())}.toMutableMap()
        var coverage=Traversal.Coverage(true,emptySet(),emptyList())
        override fun arc(id:Long)=arcs[id]
        override fun node(id:Long)=nodes[id]
        override fun turnCoverage(arc:Traversal.Arc)=coverage
    }
    val g=Graph()
    val calls=ArrayList<Traversal.Location>()
    fun context(location:Traversal.Location,instant:Instant):RestrictionCondition.Context {
        calls+=location
        return RestrictionCondition.Context(instant,ZoneId.of("America/New_York"))
    }
    val engine=Traversal(g,emptyList(),::context)
    fun reject(block:()->Unit) { check(runCatching(block).isFailure) }
    val start=engine.startOnArc(1,0.25,at)
    val anchor=RoadEndpointAnchor(g.revision,1,10,20,0.25)
    check(engine.startAt(anchor,at)==start)
    check(engine.positionOf(anchor.copy(directedFraction=0.0))==Traversal.Position.AtNode(10))
    check(engine.positionOf(anchor.copy(directedFraction=1.0))==Traversal.Position.AtNode(20))
    check(engine.startAt(RoadEndpointAnchor(g.revision,101,20,10,0.75),at).position==Traversal.Position.Interior(101,0.75))
    reject { engine.startAt(anchor.copy(sourceRevision="other-source"),at) }
    reject { engine.startAt(anchor.copy(fromNode=30),at) }
    reject { engine.startAt(anchor.copy(arcId=999),at) }
    check(start.node==null && start.position==Traversal.Position.Interior(1,0.25))
    check(start!=engine.startOnArc(1,0.5,at))
    val part=engine.advanceTo(start,1,0.75,500)
    check(part.allowed==Match.YES && part.next!!.node==null && part.portion==RoadArcPortion(1,0.25,0.75))
    check(part.checks.map{it.location.phase}==listOf(Traversal.Phase.WAY_ENTRY))
    check(part.checks.single().location.let{it.nodeId==null && it.arcId==1L && it.directedFraction==0.25})
    check(calls.all{it.nodeId==null && it.arcId==1L && it.directedFraction==0.25})
    val end=engine.advance(part.next!!,1,250)
    check(end.next!!.node==20L && end.next.instant==at.plusMillis(750))
    check(end.checks.map{it.location.phase}==listOf(Traversal.Phase.WAY_CONTINUATION,Traversal.Phase.ARRIVAL_NODE))
    check(end.next.turns==part.next.turns) // No second turn-machine input for the split arc.
    check(engine.advance(part.next,4,1).let{it.allowed==Match.UNKNOWN && it.next==null})
    reject { engine.advanceTo(part.next,1,0.5,1) }
    reject { engine.advanceTo(start,1,Double.NaN,1) }
    reject { engine.advanceTo(start,1,1.1,1) }
    reject { engine.advanceTo(start,1,0.75,0) }
    reject { Traversal(g,emptyList(),::context).advance(start,1,1) }
    for(f in listOf(0.0,1.0,-0.1,Double.NaN,Double.POSITIVE_INFINITY)) reject { engine.startOnArc(1,f,at) }
    reject { engine.startOnArc(0,0.5,at) }

    // Full versus split traversal yields the exact same node/time/turn-history label.
    val whole=engine.advance(engine.start(10,at),1,1000).next!!
    val split=engine.advance(engine.advanceTo(engine.start(10,at),1,0.4,400).next!!,1,600).next!!
    check(whole==split)
    // Unvisited upstream/downstream node barriers are not imaginary crossings.
    g.nodes[10]=Traversal.Node(10,mapOf("barrier" to "block"))
    g.nodes[20]=Traversal.Node(20,mapOf("barrier" to "block"))
    check(engine.advance(engine.start(10,at),1,1).allowed==Match.NO)
    check(engine.advanceTo(start,1,0.75,1).allowed==Match.YES)
    check(engine.advance(start,1,1).allowed==Match.NO)
    check(engine.advance(part.next,1,1).allowed==Match.NO)
    g.nodes[10]=Traversal.Node(10,emptyMap());g.nodes[20]=Traversal.Node(20,emptyMap())
    check(engine.advance(engine.startOnArc(999,0.5,at),999,1).allowed==Match.UNKNOWN)
    for((tags,direction,expected) in listOf(
        Triple(road+mapOf("access" to "private"),Direction.FORWARD,Match.NO),
        Triple(road+mapOf("access" to "destination"),Direction.FORWARD,Match.NO),
        Triple(road+mapOf("oneway" to "yes"),Direction.BACKWARD,Match.NO),
        Triple(road+mapOf("access:conditional" to "no @ wet"),Direction.FORWARD,Match.UNKNOWN))) {
        g.arcs[1]=g.arcs.getValue(1).copy(tags=tags,direction=direction)
        check(engine.advanceTo(start,1,0.75,1).allowed==expected)
    }
    g.arcs[1]=g.arcs.getValue(1).copy(tags=road,direction=Direction.FORWARD)

    val geometry=object:Compiler.Geometry {
        override fun way(id:Long)=g.arcs.values.singleOrNull{it.wayId==id && it.direction==Direction.FORWARD}?.let{Compiler.Way(id,listOf(it.from,it.to))}
        override fun arc(wayId:Long,segmentOrdinal:Int,forward:Boolean)=
            if(segmentOrdinal==0)g.arcs.values.singleOrNull{it.wayId==wayId &&
                it.direction==if(forward)Direction.FORWARD else Direction.BACKWARD}?.id else null
    }
    fun guarded(value:String):Traversal {
        val compiled=Compiler.compile(77,value,listOf(Compiler.Member('w',101,"from"),Compiler.Member('w',102,"via"),Compiler.Member('w',103,"to")),geometry)
        check(compiled.resolved) { "Fixture turn geometry unresolved: ${compiled.problems}" }
        return Traversal(g,listOf(Traversal.Relation(77,mapOf("type" to "restriction","restriction" to value),listOf(compiled))),::context)
    }
    g.coverage=Traversal.Coverage(true,setOf(77),emptyList())
    val no=guarded("no_straight_on")
    var state=no.advance(no.startOnArc(1,0.25,at),1,750).next!!
    state=no.advanceTo(state,2,0.4,400).next!!
    state=no.advanceTo(state,2,0.8,400).next!!
    state=no.advance(state,2,200).next!!
    check(no.advanceTo(state,3,0.25,250).let{it.allowed==Match.NO && it.next==null && it.blockedRelations==setOf(77L)})
    // Starting in the via-way does not fabricate prior traversal of the from-way.
    val viaStart=no.advance(no.startOnArc(2,0.5,at),2,500).next!!
    check(no.advanceTo(viaStart,3,0.25,250).allowed==Match.YES)
    val only=guarded("only_straight_on")
    val approach=only.advance(only.startOnArc(1,0.25,at),1,750).next!!
    check(only.advanceTo(approach,4,0.25,250).allowed==Match.NO)
    val viaHalf=only.advanceTo(approach,2,0.5,500).next!!
    val viaEnd=only.advance(viaHalf,2,500).next!!
    check(only.advanceTo(viaEnd,3,0.25,250).allowed==Match.YES)
    g.coverage=Traversal.Coverage(false,emptySet(),listOf("missing cross-shard member"))
    check(engine.advanceTo(start,1,0.75,1).allowed==Match.UNKNOWN)
    g.coverage=Traversal.Coverage(true,emptySet(),emptyList())
    val unavailable=Traversal(g,emptyList()){location,instant->
        checkNotNull(location.nodeId) { "No interior jurisdiction evidence" };context(location,instant)
    }
    check(unavailable.advance(unavailable.startOnArc(1,0.5,at),1,1).allowed==Match.UNKNOWN)
    check(engine.advance(engine.startOnArc(1,0.5,Instant.MAX),1,1).allowed==Match.UNKNOWN)
    g.revision="changed-source"
    reject { engine.positionOf(anchor) }
    check(engine.advanceTo(start,1,0.75,1).let{it.allowed==Match.UNKNOWN && it.next==null})

    check(RoadArcPortion(1,0.0,0.5).distanceMm(1001)==501L)
    check(RoadArcPortion(1,0.5,1.0).distanceMm(1001)==500L)
    check(RoadArcPortion(101,0.5,1.0,Direction.BACKWARD).distanceMm(1001)==501L)
    check(RoadArcPortion(101,0.0,0.5,Direction.BACKWARD).distanceMm(1001)==500L)
    val random=java.util.Random(182609)
    for(length in listOf(0L,1L,1001L,9007199254740993L,Long.MAX_VALUE)) repeat(100) {
        val fractions=(listOf(0.0,1.0)+List(20){random.nextDouble()}).distinct().sorted()
        val portions=fractions.zipWithNext().map{(a,b)->RoadArcPortion(7,a,b)}
        check(portions.sumOf{it.distanceMm(length)}==length)
        check(portions.sumOf{it.copy(direction=Direction.BACKWARD).distanceMm(length)}==length)
        check(portions.all{it.distanceMm(length)>=0})
    }
    for((a,b) in listOf(-0.1 to 0.5,0.5 to 0.5,0.6 to 0.5,0.0 to 1.1,Double.NaN to 0.5)) reject{RoadArcPortion(1,a,b)}
    reject{RoadArcPortion(0,0.0,1.0)}
    reject{RoadArcPortion(1,0.0,1.0).distanceMm(-1)}
    println("Directed partial road traversal: source-bound anchors, real-node/interior identity, exact split labels, crossed-node barriers, source/context/access guards, partial NO/ONLY turn history and1000 forward/reverse telescoping source-distance splits PASS; endpoint purpose grants and whole-route integration remain pending")
}
