package com.bggg.watchman

import com.bggg.watchman.routing.*
import com.bggg.watchman.routing.ConditionalRestriction.Match
import com.bggg.watchman.routing.MotorcarTagPolicy.Direction
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

fun sourceRoadTimingSmoke() {
    val at = Instant.parse("2026-10-05T12:00:30Z")
    val public = mapOf("highway" to "residential", "maxspeed" to "60")
    class Graph : MotorcarGraphTraversal.Graph, TurnRelationCompiler.Geometry {
        override var revision = "source-timing-synthetic-graph"
        var road = MotorcarGraphTraversal.Arc(1, 101, 10, 20, Direction.FORWARD, public)
        var extraRoad: MotorcarGraphTraversal.Arc? = null
        var nodeTags = emptyMap<String, String>()
        var length = 1000000L
        override fun arc(id: Long) = road.takeIf { id == it.id } ?: extraRoad?.takeIf { id == it.id }
        override fun node(id: Long) = MotorcarGraphTraversal.Node(id, nodeTags)
        override fun turnCoverage(arc: MotorcarGraphTraversal.Arc) = MotorcarGraphTraversal.Coverage(true, emptySet(), emptyList())
        override fun way(id: Long) = when (id) {
            road.wayId -> TurnRelationCompiler.Way(id, listOf(10, 20))
            extraRoad?.wayId -> TurnRelationCompiler.Way(id, listOf(10, 30))
            else -> null
        }
        override fun arc(wayId: Long, segmentOrdinal: Int, forward: Boolean) = 1L
    }
    val graph = Graph()
    val profile = RoadSpeedEstimate.Profile("synthetic-explicit-60", "Arithmetic fixture, not a shipping speed profile",
        mapOf("residential" to BigDecimal(60)))
    val contexts = HashMap<Pair<RoadPolicyTimeline.Kind, Long>, RoadPolicyTimeline.Snapshot>()
    fun context(kind: RoadPolicyTimeline.Kind, id: Long) = contexts.getOrPut(kind to id) {
        RoadPolicyTimeline.Snapshot(graph.revision, kind, id, "synthetic-source-$kind-$id",
            "Explicit synthetic UTC/frozen conditions for this original source only",
            at.minusSeconds(86400), at.plusSeconds(86400), ZoneId.of("UTC"))
    }
    var delay = 0L
    var coverage = Match.YES
    var lawful = Match.YES
    var wrongApproach = false
    var controlHorizon = at.plusSeconds(3600)
    fun model(policy: EndpointAccessPolicy? = null, iterations: Int = 16, cancelled: () -> Boolean = { false }) = SourceRoadTiming(
        graph, { graph.length }, profile, ::context, { arc, approach ->
            val bound = if (wrongApproach) approach.copy(incoming = RoadArcPortion(1, 0.0, 0.5)) else approach
            SourceRoadTiming.Control(graph.revision, arc.id, bound, "synthetic-controls", "Explicit synthetic complete control oracle",
                controlHorizon, coverage, delay, lawful)
        }, policy, maximumIterations = iterations, cancelled = cancelled)
    val origin = RoadEndpointAnchor(graph.revision, 1, 10, 20, 0.0)
    val destination = RoadEndpointAnchor(graph.revision, 1, 10, 20, 1.0)
    fun approach(policy: EndpointAccessPolicy? = null, fraction: Double = 0.0): NoCamsGraphSearch.Approach {
        val engine = MotorcarGraphTraversal(graph, emptyList(), policy) { _, time -> context(RoadPolicyTimeline.Kind.ARC, 1).context(time) }
        return NoCamsGraphSearch.Approach(engine.startAt(origin.copy(directedFraction = fraction), at), null)
    }
    fun timing(source: SourceRoadTiming = model(), state: NoCamsGraphSearch.Approach = approach(),
        portion: RoadArcPortion = RoadArcPortion(1, 0.0, 1.0)) = source.timing(graph.road, portion, state)
    fun reject(action: () -> Unit) = check(runCatching(action).isFailure)

    check(timing().let { it.durationMillis == 60000L && it.intervalAllowed == Match.YES && it.departureDelayMillis == 0L })
    check(timing(state = approach(fraction = 0.25), portion = RoadArcPortion(1, 0.25, 0.75)).durationMillis == 30000L)
    graph.road = graph.road.copy(tags = public + ("maxspeed:conditional" to "30 @ Mo 12:01-12:02"))
    check(timing().durationMillis == 120000L) // expanded interval rechecked, not departure-only speed
    reject { timing(model(iterations = 1)) }
    graph.road = graph.road.copy(tags = public + ("maxspeed" to "signals"))
    reject { timing() }
    graph.road = graph.road.copy(tags = public + ("access:conditional" to "no @ Mo 12:01-12:02"))
    reject { timing() } // start/end arithmetic alone cannot certify travel
    graph.road = graph.road.copy(tags = public + ("access:conditional" to "no @ Mo 12:00-12:01"))
    delay = 30000
    val waited = timing()
    check(waited.durationMillis == 60000L && waited.departureDelayMillis == 30000L &&
        waited.waitAllowed == Match.YES && !waited.waitEvidence.isNullOrBlank())
    val source = model()
    val sourceGraph = graph
    reject { source.requireGraph(Graph()) }
    reject { source.contextAt(MotorcarGraphTraversal.Location(MotorcarGraphTraversal.Phase.WAY_ENTRY,
        999, 10, 1, 0.0), at) }
    reject { source.contextAt(MotorcarGraphTraversal.Location(MotorcarGraphTraversal.Phase.TURN,
        20, 20, 1, 0.0), at) }
    val network = object : NoCamsGraphSearch.Network {
        override val graph = sourceGraph
        override val deviceRevision = "synthetic-no-devices"
        override fun outgoing(node: Long) = if (node == 10L) listOf(1L) else emptyList()
        override fun lengthMm(arcId: Long) = graph.length
        override fun timing(arc: MotorcarGraphTraversal.Arc, portion: RoadArcPortion, approach: NoCamsGraphSearch.Approach) = source.timing(arc, portion, approach)
        override fun exposures(arc: MotorcarGraphTraversal.Arc, portion: RoadArcPortion, departure: Instant, arrival: Instant) = emptyList<NoCamsGraphSearch.Exposure>()
    }
    val result = NoCamsGraphSearch(network, emptyList(), source::contextAt).search(origin, destination, at,
        NoCamsGraphSearch.Bounds(1000000, 90000))
    check(result.stop == NoCamsGraphSearch.Stop.FOUND && result.route!!.cost.durationMillis == 90000L)
    check(result.route!!.segments.single().travelDeparture == at.plusSeconds(30))
    check(result.route!!.segments.single().arrival == at.plusSeconds(90))
    reject { timing(state = approach(fraction = 0.25), portion = RoadArcPortion(1, 0.25, 0.75)) }
    lawful = Match.UNKNOWN; reject { timing() }; lawful = Match.YES
    graph.nodeTags = mapOf("barrier" to "gate", "opening_hours" to "Mo 12:00-12:01")
    reject { timing() } // wait endpoint closes, no fabricated lawful wait
    graph.nodeTags = emptyMap()
    delay = 0; graph.road = graph.road.copy(tags = public)
    coverage = Match.UNKNOWN; reject { timing() }
    val unavailable = NoCamsGraphSearch(network, emptyList(), source::contextAt).search(origin, destination, at,
        NoCamsGraphSearch.Bounds(1000000, 90000))
    check(unavailable.route == null && !unavailable.optimalWithinEvaluatedSnapshot &&
        unavailable.diagnostics.any { "Control coverage is UNKNOWN" in it })
    coverage = Match.NO
    val deniedEvidence = runCatching { timing() }.exceptionOrNull() as SourceRoadTiming.Unavailable
    check(deniedEvidence.permission == Match.NO)
    val denied = NoCamsGraphSearch(network, emptyList(), source::contextAt).search(origin, destination, at,
        NoCamsGraphSearch.Bounds(1000000, 90000))
    check(denied.route == null && denied.rejections["source_permission_NO"] == 1 &&
        denied.rejections["edge_data_unavailable"] == null)
    // A forbidden alternative must not falsely invalidate the proof for a complete legal route.
    val legalWithDeniedBranch = object : NoCamsGraphSearch.Network by network {
        override fun outgoing(node: Long) = if (node == 10L) listOf(1L, 2L) else emptyList()
        override fun timing(arc: MotorcarGraphTraversal.Arc, portion: RoadArcPortion,
            approach: NoCamsGraphSearch.Approach): NoCamsGraphSearch.Timing {
            if (arc.id == 2L) throw SourceRoadTiming.Unavailable("Explicit synthetic branch denied", Match.NO)
            return source.timing(arc, portion, approach)
        }
    }
    graph.extraRoad = MotorcarGraphTraversal.Arc(2, 201, 10, 30, Direction.FORWARD, public)
    coverage = Match.YES
    val legal = NoCamsGraphSearch(legalWithDeniedBranch, emptyList(), source::contextAt).search(origin, destination, at,
        NoCamsGraphSearch.Bounds(1000000, 90000))
    check(legal.stop == NoCamsGraphSearch.Stop.FOUND && legal.optimalWithinEvaluatedSnapshot &&
        legal.rejections["source_permission_NO"] == 1 && legal.rejections["edge_data_unavailable"] == null)
    val uncertainBranch = object : NoCamsGraphSearch.Network by legalWithDeniedBranch {
        override fun timing(arc: MotorcarGraphTraversal.Arc, portion: RoadArcPortion,
            approach: NoCamsGraphSearch.Approach): NoCamsGraphSearch.Timing {
            if (arc.id == 2L) throw SourceRoadTiming.Unavailable("Explicit synthetic branch unresolved")
            return source.timing(arc, portion, approach)
        }
    }
    val uncertain = NoCamsGraphSearch(uncertainBranch, emptyList(), source::contextAt).search(origin, destination, at,
        NoCamsGraphSearch.Bounds(1000000, 90000))
    check(uncertain.stop == NoCamsGraphSearch.Stop.FOUND && !uncertain.optimalWithinEvaluatedSnapshot &&
        uncertain.rejections["edge_data_unavailable"] == 1)
    graph.extraRoad = null
    wrongApproach = true; reject { timing() }; wrongApproach = false
    controlHorizon = at.plusSeconds(40); reject { timing() }; controlHorizon = at.plusSeconds(3600)
    reject { timing(model(cancelled = { true })) }
    val old = model(); graph.revision = "changed"; reject { timing(old) }; graph.revision = origin.sourceRevision

    graph.road = graph.road.copy(tags = public + ("access" to "private"))
    val grant = EndpointAccessPolicy.Grant(MotorcarTagPolicy.AccessClass.PRIVATE,
        EndpointAccessPolicy.Basis.INDIVIDUAL_PERMISSION, "Synthetic individual permission", validUntil = at.plusSeconds(45))
    val policy = EndpointAccessPolicy(graph, origin, destination, null,
        EndpointAccessPolicy.scope(graph, setOf(101), listOf(grant)))
    reject { timing(model(policy), approach(policy)) }
    graph.road = graph.road.copy(tags = public)
    // The initial physical node can start destination ingress BEFORE the outgoing public way.
    // Checking only that outgoing way would illegally permit exit from the restricted facility.
    graph.nodeTags = mapOf("access" to "private")
    graph.extraRoad = MotorcarGraphTraversal.Arc(2, 201, 10, 30, Direction.FORWARD, public)
    val longGrant = grant.copy(validUntil = at.plusSeconds(3600))
    val ingress = EndpointAccessPolicy(graph, origin,
        RoadEndpointAnchor(graph.revision, 2, 10, 30, 1.0), null,
        EndpointAccessPolicy.scope(graph, setOf(201), listOf(longGrant)))
    reject { timing(model(ingress), approach(ingress)) }
    graph.extraRoad = null
    graph.road = graph.road.copy(tags = public + ("access" to "private"))
    val egress = EndpointAccessPolicy(graph, origin, destination,
        EndpointAccessPolicy.scope(graph, setOf(101), listOf(longGrant)), null)
    val egressSource = model(egress)
    check(timing(egressSource, approach(egress)).durationMillis == 60000L)
    egressSource.requireEndpointPolicy(egress)
    reject { egressSource.requireEndpointPolicy(null) }
    reject { egressSource.requireEndpointPolicy(policy) }
    // Replacing an equally described snapshot is still a producer revision/snapshot change.
    fun replacement(snapshot: RoadPolicyTimeline.Snapshot) = RoadPolicyTimeline.Snapshot(
        snapshot.graphRevision, snapshot.kind, snapshot.sourceId, snapshot.revision, snapshot.evidence,
        snapshot.validFrom, snapshot.validUntil, snapshot.zone, snapshot.facts, snapshot.quantities, snapshot.holidays)
    var nodeReads = 0
    val replacedInitial = SourceRoadTiming(graph, { graph.length }, profile, { kind, id ->
        val found = context(kind, id)
        if (kind == RoadPolicyTimeline.Kind.NODE && ++nodeReads > 1) replacement(found) else found
    }, { arc, state -> SourceRoadTiming.Control(graph.revision, arc.id, state, "synthetic-controls",
        "Explicit synthetic control oracle", controlHorizon, Match.YES, 0) }, egress)
    reject { timing(replacedInitial, approach(egress)) }
    check(nodeReads == 2)
    graph.nodeTags = emptyMap()
    graph.road = graph.road.copy(tags = public)
    var arcReads = 0
    val replacedArc = SourceRoadTiming(graph, { graph.length }, profile, { kind, id ->
        val found = context(kind, id)
        if (kind == RoadPolicyTimeline.Kind.ARC && ++arcReads > 1) replacement(found) else found
    }, { arc, state -> SourceRoadTiming.Control(graph.revision, arc.id, state, "synthetic-controls",
        "Explicit synthetic control oracle", controlHorizon, Match.YES, 0) })
    reject { timing(replacedArc) }
    // Source objects may have mutable collections; unchanged object/revision is not integrity.
    val mutableTags = LinkedHashMap(public)
    graph.road = graph.road.copy(tags = mutableTags)
    val mutatedSource = SourceRoadTiming(graph, { graph.length }, profile, ::context, { arc, state ->
        mutableTags["maxspeed"] = "30"
        SourceRoadTiming.Control(graph.revision, arc.id, state, "synthetic-controls",
            "Explicit synthetic control oracle", controlHorizon, Match.YES, 0)
    })
    reject { timing(mutatedSource) }
    graph.road = graph.road.copy(tags = public)
    val mutableNodeTags = LinkedHashMap<String, String>()
    graph.nodeTags = mutableNodeTags
    val mutatedNode = SourceRoadTiming(graph, { graph.length }, profile, ::context, { arc, state ->
        mutableNodeTags["access"] = "private"
        SourceRoadTiming.Control(graph.revision, arc.id, state, "synthetic-controls",
            "Explicit synthetic control oracle", controlHorizon, Match.YES, 0)
    })
    reject { timing(mutatedNode) }
    graph.nodeTags = emptyMap()
    nodeReads = 0
    val replacedWait = SourceRoadTiming(graph, { graph.length }, profile, { kind, id ->
        val found = context(kind, id)
        if (kind == RoadPolicyTimeline.Kind.NODE && ++nodeReads > 1) replacement(found) else found
    }, { arc, state -> SourceRoadTiming.Control(graph.revision, arc.id, state, "synthetic-controls",
        "Explicit synthetic lawful control wait", controlHorizon, Match.YES, 30000, Match.YES) })
    reject { timing(replacedWait) }
    check(nodeReads == 2)
    val changedLength = SourceRoadTiming(graph, {
        graph.length.also { graph.length += 1 }
    }, profile, ::context, { arc, state -> SourceRoadTiming.Control(graph.revision, arc.id, state,
        "synthetic-controls", "Explicit synthetic control oracle", controlHorizon, Match.YES, 0) })
    reject { timing(changedLength) }
    graph.length = 1000000
    var stopped = false
    val cancelInProducer = SourceRoadTiming(graph, { graph.length }, profile, ::context, { arc, state ->
        stopped = true
        SourceRoadTiming.Control(graph.revision, arc.id, state, "synthetic-controls",
            "Explicit synthetic control oracle", controlHorizon, Match.YES, 0)
    }, cancelled = { stopped })
    reject { timing(cancelInProducer) }
    reject { source.contextAt(MotorcarGraphTraversal.Location(MotorcarGraphTraversal.Phase.WAIT_NODE,
        10, 10, 1, 0.0), at) }
    reject { source.contextAt(MotorcarGraphTraversal.Location(MotorcarGraphTraversal.Phase.TURN,
        10, 10, 1, 0.25), at) }
    check(source.contextAt(MotorcarGraphTraversal.Location(MotorcarGraphTraversal.Phase.INITIAL_NODE,
        10, 10), at).instant == at)
    graph.road = graph.road.copy(from = 20, to = 10, direction = Direction.BACKWARD,
        tags = public + mapOf("maxspeed:forward" to "60", "maxspeed:backward" to "30"))
    val reverseSource = model()
    val reverseEngine = MotorcarGraphTraversal(graph, emptyList(), reverseSource::contextAt)
    val reverseState = NoCamsGraphSearch.Approach(reverseEngine.startOnArc(1, 0.25, at), null)
    check(reverseSource.timing(graph.road, RoadArcPortion(1, 0.25, 0.75, Direction.BACKWARD),
        reverseState).durationMillis == 60000L)
    reject { reverseSource.timing(graph.road, RoadArcPortion(1, 0.25, 0.75), reverseState) }
    graph.road = graph.road.copy(from = 10, to = 20, direction = Direction.FORWARD, tags = public)
    val shortSnapshot = context(RoadPolicyTimeline.Kind.ARC, 1)
    contexts[RoadPolicyTimeline.Kind.ARC to 1] = RoadPolicyTimeline.Snapshot(graph.revision,
        shortSnapshot.kind, shortSnapshot.sourceId, "synthetic-short-horizon", shortSnapshot.evidence,
        shortSnapshot.validFrom, at.plusSeconds(30), shortSnapshot.zone)
    reject { timing() }
    contexts[RoadPolicyTimeline.Kind.ARC to 1] = shortSnapshot
    val snap = context(RoadPolicyTimeline.Kind.ARC, 1)
    contexts[RoadPolicyTimeline.Kind.ARC to 1] = RoadPolicyTimeline.Snapshot("wrong-source", snap.kind, snap.sourceId,
        "wrong-context", "Synthetic source mismatch", snap.validFrom, snap.validUntil, snap.zone)
    reject { timing() }
    println("Source road timing: source-mm directional speed, continuous speed/access/grant intervals, delayed entry and lawful original-node waiting, initial-node physical-order ingress/egress, frozen source tags/length/snapshot identity, actual search/context/policy binding, partial travel, source/approach/control/context horizon/unknown/callback cancellation/convergence guards PASS; actual context/control producers and app provider acceptance remain separate")
}
