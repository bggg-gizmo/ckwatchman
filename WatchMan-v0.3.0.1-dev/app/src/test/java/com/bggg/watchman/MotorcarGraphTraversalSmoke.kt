package com.bggg.watchman

import com.bggg.watchman.routing.MotorcarGraphTraversal as Traversal
import com.bggg.watchman.routing.TurnRelationCompiler as Compiler
import com.bggg.watchman.routing.MotorcarTagPolicy.Direction
import com.bggg.watchman.routing.RestrictionCondition
import com.bggg.watchman.routing.ConditionalRestriction.Match
import java.time.Instant
import java.time.ZoneId
import java.math.BigDecimal

fun motorcarGraphTraversalSmoke() {
    val yes = Match.YES; val no = Match.NO; val unknown = Match.UNKNOWN
    val at = Instant.parse("2026-09-14T14:59:00Z") // Monday 07:59 in the fixture zone; no host clock.
    val road = mapOf("highway" to "residential")
    class Graph : Traversal.Graph {
        override var revision = "test-content-revision-1"
        val arcs = linkedMapOf(
            1L to Traversal.Arc(1, 101, 10, 20, Direction.FORWARD, road),
            2L to Traversal.Arc(2, 102, 20, 30, Direction.FORWARD, road),
            3L to Traversal.Arc(3, 103, 30, 40, Direction.FORWARD, road),
            4L to Traversal.Arc(4, 104, 20, 50, Direction.FORWARD, road),
            5L to Traversal.Arc(5, 105, 50, 30, Direction.FORWARD, road),
            6L to Traversal.Arc(6, 106, 30, 60, Direction.FORWARD, road))
        val nodes = (10L..60L step 10).associateWith { Traversal.Node(it, emptyMap()) }.toMutableMap()
        var coverage = Traversal.Coverage(true, emptySet(), emptyList())
        var fail = false
        override fun arc(id: Long): Traversal.Arc? { if (fail) error("expected graph failure"); return arcs[id] }
        override fun node(id: Long) = nodes[id]
        override fun turnCoverage(arc: Traversal.Arc) = coverage
    }
    fun context(location: Traversal.Location, instant: Instant) = RestrictionCondition.Context(instant,
        if (location.nodeId == 60L) ZoneId.of("America/New_York") else ZoneId.of("America/Los_Angeles"),
        quantities = mapOf("height" to BigDecimal("2"), "width" to BigDecimal("1.8"), "weight" to BigDecimal("1.5")))
    fun compile(value: String): Compiler.Compiled {
        val ways = mapOf(101L to listOf(10L,20L), 102L to listOf(20L,30L), 103L to listOf(30L,40L))
        val geometry = object : Compiler.Geometry {
            override fun way(id: Long) = ways[id]?.let { Compiler.Way(id, it) }
            override fun arc(wayId: Long, segmentOrdinal: Int, forward: Boolean): Long? =
                if (segmentOrdinal == 0 && forward) wayId - 100 else if (segmentOrdinal == 0) wayId else null
        }
        return Compiler.compile(77, value, listOf(Compiler.Member('w',101,"from"),
            Compiler.Member('w',102,"via"), Compiler.Member('w',103,"to")), geometry)
    }
    fun relation(value: String = "no_straight_on", extra: Map<String,String> = emptyMap()) =
        Traversal.Relation(77, mapOf("type" to "restriction", "restriction" to value) + extra, listOf(compile(value)))
    val g = Graph()
    val base = Traversal(g, emptyList(), ::context)
    val start = base.start(10, at)
    val first = base.advance(start, 1, 60_000)
    check(first.allowed == yes && first.next!!.node == 20L && first.next.instant == at.plusSeconds(60))
    check(first.checks.map { it.location.phase } == listOf(Traversal.Phase.WAY_ENTRY, Traversal.Phase.INITIAL_NODE, Traversal.Phase.ARRIVAL_NODE))
    check(base.advance(first.next!!, 2, 60_000).checks.size == 2)
    check(base.advance(start, 3, 1).let { it.allowed == unknown && it.next == null && it.diagnostics.any { d -> "disconnected" in d } })
    check(base.advance(start, 999, 1).allowed == unknown)
    check(runCatching { base.advance(start, 1, 0) }.isFailure)
    check(runCatching { Traversal(g, emptyList(), ::context).advance(start, 1, 1) }.isFailure)
    check(base.advance(base.start(10, Instant.MAX), 1, 1).allowed == unknown)

    // Node constraints are actually crossed and timed; a nearby unrelated barrier is irrelevant.
    g.nodes[20] = Traversal.Node(20, mapOf("barrier" to "gate", "locked" to "yes"))
    check(base.advance(start, 1, 1).allowed == no)
    g.nodes[20] = Traversal.Node(20, mapOf("barrier" to "gate", "opening_hours" to "Mo 08:00-09:00"))
    check(base.advance(start, 1, 30_000).allowed == no)
    check(base.advance(start, 1, 60_000).allowed == yes)
    g.nodes[20] = Traversal.Node(20, emptyMap())
    g.nodes[60] = Traversal.Node(60, mapOf("barrier" to "block"))
    check(base.advance(start, 1, 1).allowed == yes)
    g.nodes[10] = Traversal.Node(10, mapOf("barrier" to "block"))
    check(base.advance(start, 1, 1).allowed == no)
    g.nodes[10] = Traversal.Node(10, emptyMap())
    g.nodes[20] = Traversal.Node(20, mapOf("maxheight:physical" to "2"))
    check(base.advance(start, 1, 1).allowed == no)
    g.nodes[20] = Traversal.Node(20, emptyMap())
    for ((tags, expected) in listOf(road + ("access" to "private") to no,
        road + ("maxweight" to "1") to no, road + ("access:conditional" to "no @ wet") to unknown)) {
        g.arcs[1] = g.arcs.getValue(1).copy(tags = tags)
        check(base.advance(start, 1, 1).allowed == expected)
    }
    g.arcs[1] = g.arcs.getValue(1).copy(tags = road + ("oneway" to "yes"), direction = Direction.BACKWARD)
    check(base.advance(start, 1, 1).allowed == no)
    g.arcs[1] = g.arcs.getValue(1).copy(tags = road, direction = Direction.FORWARD)

    // Same node/time reached by different approaches MUST remain different search labels.
    g.coverage = Traversal.Coverage(true, setOf(77), emptyList())
    val guarded = Traversal(g, listOf(relation()), ::context)
    fun walk(engine: Traversal, vararg arcs: Long): Traversal.State {
        var state = engine.start(10, at)
        for (arc in arcs) state = checkNotNull(engine.advance(state, arc, 60_000).next)
        return state
    }
    val restrictedApproach = walk(guarded, 1, 2)
    val detourMiddle = guarded.advance(walk(guarded, 1), 4, 30_000).next!!
    val detourApproach = guarded.advance(detourMiddle, 5, 30_000).next!!
    check(restrictedApproach.node == detourApproach.node && restrictedApproach.instant == detourApproach.instant)
    check(restrictedApproach.turns != detourApproach.turns)
    check(restrictedApproach != detourApproach && setOf(restrictedApproach, detourApproach).size == 2)
    check(guarded.advance(restrictedApproach, 3, 1).let { it.allowed == no && it.next == null && it.blockedRelations == setOf(77L) })
    check(guarded.advance(detourApproach, 3, 1).allowed == yes)
    val only = Traversal(g, listOf(relation("only_straight_on")), ::context)
    check(only.advance(walk(only, 1), 4, 1).allowed == no)
    check(only.advance(walk(only, 1, 2), 6, 1).allowed == no)
    check(only.advance(walk(only, 1, 2), 3, 1).allowed == yes)

    // Runtime policy evaluated at transition time, not the journey's initial time.
    val timed = Traversal(g, listOf(relation(extra = mapOf("restriction:conditional" to "none @ Mo 08:00-09:00"))), ::context)
    check(timed.advance(walk(timed, 1, 2), 3, 1).allowed == yes)
    val beforeWait=walk(timed,1,2)
    val waited=timed.waitAtNode(beforeWait,Direction.FORWARD,3_600_000,yes,"Synthetic interval wait proof")
    check(waited.allowed==yes && waited.next!!.instant==beforeWait.instant.plusSeconds(3600))
    check(waited.next.turns==beforeWait.turns && waited.next.position==beforeWait.position)
    check(waited.checks.size==2 && waited.checks.all { it.location.phase==Traversal.Phase.WAIT_NODE })
    check(timed.advance(waited.next,3,1).allowed==no) // restriction reactivates at actual departure
    check(timed.waitAtNode(beforeWait,Direction.FORWARD,1,unknown,null).allowed==unknown)
    check(timed.waitAtNode(beforeWait,Direction.FORWARD,1,no,null).allowed==no)
    check(timed.waitAtNode(beforeWait,Direction.FORWARD,1,yes,null).allowed==unknown)
    check(runCatching { base.waitAtNode(beforeWait,Direction.FORWARD,1,yes,"foreign owner") }.isFailure)
    check(runCatching { timed.waitAtNode(beforeWait,Direction.FORWARD,0,yes,"zero wait") }.isFailure)
    val wet = Traversal(g, listOf(relation(extra = mapOf("restriction:conditional" to "none @ wet"))), ::context)
    check(wet.advance(walk(wet, 1, 2), 3, 1).let { it.allowed == unknown && it.unresolvedRelations == setOf(77L) })
    val exempt = Traversal(g, listOf(relation(extra = mapOf("except" to "motorcar"))), ::context)
    check(exempt.advance(walk(exempt, 1, 2), 3, 1).allowed == yes)
    val uncompiled = Traversal(g, listOf(relation("no_right_turn_on_red")), ::context)
    check(uncompiled.advance(uncompiled.start(10, at), 1, 1).let { it.allowed == unknown && it.next == null && 77L in it.unresolvedRelations })
    val absent = Traversal(g, emptyList(), ::context)
    check(absent.advance(absent.start(10, at), 1, 1).unresolvedRelations == setOf(77L))
    g.coverage = Traversal.Coverage(false, emptySet(), listOf("unresolved shard seam"))
    check(base.advance(start, 1, 1).let { it.allowed == unknown && it.diagnostics.any { d -> "seam" in d } })
    g.coverage = Traversal.Coverage(true, emptySet(), emptyList())
    g.arcs[1] = g.arcs.getValue(1).copy(unresolved = listOf("interval restriction unhandled"))
    check(base.advance(start, 1, 1).allowed == unknown)
    g.arcs[1] = g.arcs.getValue(1).copy(unresolved = emptyList())
    g.fail = true
    check(base.advance(start, 1, 1).allowed == unknown)
    g.fail = false
    val badContext = Traversal(g, emptyList()) { location, instant -> context(location, instant.plusSeconds(1)) }
    check(badContext.advance(badContext.start(10, at), 1, 1).allowed == unknown)
    val failingContext = Traversal(g, emptyList()) { _, _ -> error("context unavailable") }
    check(failingContext.advance(failingContext.start(10, at), 1, 1).allowed == unknown)
    val calls = ArrayList<Pair<Traversal.Location, Instant>>()
    val recording = Traversal(g, emptyList()) { location, instant -> calls += location to instant; context(location, instant) }
    recording.advance(recording.start(10, at), 1, 60_000)
    check(calls.single { it.first.phase == Traversal.Phase.ARRIVAL_NODE }.second == at.plusSeconds(60))
    // A different zone at the reached node must use that node's context, not a global origin zone.
    g.nodes[60] = Traversal.Node(60, mapOf("barrier" to "gate", "opening_hours" to "Mo 11:00-12:00"))
    val zonePath = recording.advance(walk(recording, 1, 2), 6, 60_000)
    check(zonePath.allowed == yes)
    check(zonePath.checks.single { it.location.phase == Traversal.Phase.ARRIVAL_NODE }.instant == at.plusSeconds(180))
    // Constructor snapshots: later mutation of relation input must not change legal decisions.
    val mutableTags = mutableMapOf("type" to "restriction", "restriction" to "no_straight_on")
    val immutable = Traversal(g, listOf(Traversal.Relation(77, mutableTags, listOf(compile("no_straight_on")))), ::context)
    mutableTags["restriction"] = "none"
    check(immutable.advance(walk(immutable, 1, 2), 3, 1).allowed == no)
    // Concurrent evaluations must not leak arc direction or context across callers.
    g.arcs[7] = Traversal.Arc(7, 107, 10, 20, Direction.BACKWARD, road + ("oneway" to "yes"))
    val concurrent = Traversal(g, emptyList(), ::context)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
    try {
        val work = (0 until 200).map { index -> pool.submit<Boolean> {
            val id = if (index % 2 == 0) 1L else 7L
            concurrent.advance(concurrent.start(10, at), id, 1).allowed == if (id == 1L) yes else no
        } }
        check(work.all { it.get(10, java.util.concurrent.TimeUnit.SECONDS) })
    } finally { pool.shutdownNow() }
    val changedDuringRead = Traversal(g, emptyList()) { location, instant ->
        g.revision = "changed-during-read"
        context(location, instant)
    }
    check(changedDuringRead.advance(changedDuringRead.start(10, at), 1, 1).let { it.allowed == unknown && it.next == null })
    g.revision = "test-content-revision-2"
    check(base.advance(start, 1, 1).let { it.allowed == unknown && it.diagnostics.any { d -> "revision changed" in d } })
    println("Motorcar graph transitions: real compiler/policy/automaton integration, connectivity, approach history, timed crossings, one-way/access/dimensions, unresolved geometry and source/context failures PASS; compact graph, endpoint grants and route search remain pending")
}
