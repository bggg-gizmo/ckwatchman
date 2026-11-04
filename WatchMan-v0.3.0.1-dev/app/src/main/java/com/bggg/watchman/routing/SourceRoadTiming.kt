package com.bggg.watchman.routing

import com.bggg.watchman.routing.ConditionalRestriction.Match
import java.time.Instant

/** Connects original source distance, directional speed, continuous access and departure-changing
 * controls. Snapshot/control producers must supply evidenced local data: this class does not
 * discover time zones, traffic, legal entrances, calendars or permission to wait. No fixed ETA,
 * host clock, global zone, implicit zero-control fallback or highway-convenience penalty exists.
 */
class SourceRoadTiming(
    private val graph: MotorcarGraphTraversal.Graph,
    private val lengthMm: (Long) -> Long,
    private val profile: RoadSpeedEstimate.Profile,
    private val snapshotAt: (RoadPolicyTimeline.Kind, Long) -> RoadPolicyTimeline.Snapshot,
    private val controls: (MotorcarGraphTraversal.Arc, NoCamsGraphSearch.Approach) -> Control,
    private val endpointPolicy: EndpointAccessPolicy? = null,
    private val maximumBoundaries: Int = 10000,
    private val maximumIterations: Int = 16,
    private val cancelled: () -> Boolean = { false }
) {
    /** One source/approach-bound result covering ALL controls before this portion. A zero delay
     * is also an explicit model result, not absence interpreted as permission. Positive delay
     * additionally needs lawful-wait evidence; passable road/node tags alone are insufficient.
     */
    class Control(val graphRevision: String, val arcId: Long,
        val approach: NoCamsGraphSearch.Approach, val revision: String, val evidence: String,
        val validUntil: Instant, val coverage: Match, val delayMillis: Long,
        val lawfulWait: Match = Match.UNKNOWN) {
        init {
            require(graphRevision.isNotBlank() && graphRevision.length <= 512 && arcId > 0)
            require(revision.isNotBlank() && revision.length <= 512)
            require(evidence.isNotBlank() && evidence.length <= 2048)
            require(validUntil > approach.state.instant && delayMillis >= 0)
        }
    }
    /** A definite source-policy denial is not missing evidence. Preserve the evaluated result
     * instead of making search infer permission from an exception's human-readable text. */
    class Unavailable(message: String, val permission: Match = Match.UNKNOWN) : IllegalStateException(message) {
        init { require(permission != Match.YES) }
    }
    private val revision = graph.revision.also { require(it.isNotBlank()) }
    private val timeline = RoadPolicyTimeline(graph)
    init {
        require(maximumBoundaries in 2..100000 && maximumIterations in 1..64)
        endpointPolicy?.requireGraph(graph)
    }
    private fun ready() {
        if (cancelled()) throw Unavailable("Source timing cancelled")
        check(graph.revision == revision) { "Timing graph revision changed" }
    }
    fun requireGraph(candidate: MotorcarGraphTraversal.Graph) {
        check(candidate === graph) { "Timing model belongs to another graph instance" }
        ready()
    }
    fun requireEndpointPolicy(candidate: EndpointAccessPolicy?) {
        check(candidate === endpointPolicy) { "Timing model belongs to another endpoint permission policy" }
        ready()
    }
    private fun snapshot(kind: RoadPolicyTimeline.Kind, id: Long): RoadPolicyTimeline.Snapshot {
        ready()
        return snapshotAt(kind, id).also {
            check(it.graphRevision == revision && it.kind == kind && it.sourceId == id) {
                "Timing context belongs to another source"
            }
            ready()
        }
    }

    /** Use this SAME frozen model for traversal/turn/node contexts and timing certificates. */
    fun contextAt(location: MotorcarGraphTraversal.Location, at: Instant): RestrictionCondition.Context {
        ready()
        val arcContext = location.phase in setOf(MotorcarGraphTraversal.Phase.WAY_ENTRY,
            MotorcarGraphTraversal.Phase.WAY_CONTINUATION, MotorcarGraphTraversal.Phase.RESTRICTION_COVERAGE)
        val kind = if (arcContext) RoadPolicyTimeline.Kind.ARC else RoadPolicyTimeline.Kind.NODE
        val id = if (arcContext) checkNotNull(location.arcId) else checkNotNull(location.nodeId)
        if (arcContext) {
            val arc = checkNotNull(graph.arc(id)) { "Context original arc unavailable" }
            check(arc.id == id && location.sourceId == arc.wayId) { "Context way/arc identity mismatch" }
            val fraction = checkNotNull(location.directedFraction)
            check(fraction.isFinite() && fraction >= 0.0 && fraction < 1.0)
            check(location.nodeId == null || (fraction == 0.0 && location.nodeId == arc.from)) {
                "Context node is not original arc departure"
            }
        } else {
            check(location.sourceId == id && graph.node(id)?.id == id) { "Context original node identity mismatch" }
            if (location.phase == MotorcarGraphTraversal.Phase.TURN) {
                val arc = checkNotNull(graph.arc(checkNotNull(location.arcId))) { "Turn context original arc unavailable" }
                check(arc.from == id && location.directedFraction == 0.0) { "Turn context is not original departure node" }
            } else check(location.arcId == null && location.directedFraction == null) {
                "Node context has unrelated arc/fraction metadata"
            }
        }
        return snapshot(kind, id).context(at).also { ready() }
    }

    fun timing(arc: MotorcarGraphTraversal.Arc, portion: RoadArcPortion,
        approach: NoCamsGraphSearch.Approach): NoCamsGraphSearch.Timing {
        ready()
        check(graph.arc(arc.id) == arc && portion.arcId == arc.id && portion.direction == arc.direction)
        // Copy BEFORE calling external producers. Comparing a mutable source object with itself
        // cannot detect tags/unresolved-list edits made without changing the graph revision.
        val sourceArc = arc.copy(tags = java.util.Collections.unmodifiableMap(LinkedHashMap(arc.tags)),
            unresolved = java.util.Collections.unmodifiableList(ArrayList(arc.unresolved)))
        val sourceNode = approach.state.node?.let { id ->
            val node = checkNotNull(graph.node(id)) { "Timing departure node unavailable" }
            check(node.id == id)
            node.copy(tags = java.util.Collections.unmodifiableMap(LinkedHashMap(node.tags)),
                unresolved = java.util.Collections.unmodifiableList(ArrayList(node.unresolved)))
        }
        fun sourceStable() {
            ready()
            check(arc == sourceArc && graph.arc(arc.id) == sourceArc) { "Timing source arc changed" }
            sourceNode?.let { check(graph.node(it.id) == it) { "Timing departure node changed" } }
            ready()
        }
        when (val position = approach.state.position) {
            is MotorcarGraphTraversal.Position.AtNode -> check(position.id == arc.from && portion.fromFraction == 0.0)
            is MotorcarGraphTraversal.Position.Interior -> check(position.arcId == arc.id && position.fraction == portion.fromFraction)
        }
        check((endpointPolicy == null) == (approach.state.permission == null)) { "Timing endpoint policy/state mismatch" }
        val control = controls(arc, approach)
        sourceStable()
        check(control.graphRevision == revision && control.arcId == arc.id && control.approach == approach) {
            "Control evidence belongs to another source/approach/instant"
        }
        if (control.coverage != Match.YES) throw Unavailable("Control coverage is ${control.coverage}: ${control.evidence}", control.coverage)
        val departure = approach.state.instant.plusMillis(control.delayMillis)
        check(departure < control.validUntil) { "Control evidence expires before travel departure" }
        var permission = approach.state.permission
        var waitAllowed = Match.UNKNOWN
        var waitEvidence: String? = null
        var departureNodeContext: RoadPolicyTimeline.Snapshot? = null
        val diagnostics = linkedSetOf("Estimated-speed profile ${profile.revision}: ${profile.evidence}",
            "Control model ${control.revision}: ${control.evidence}")
        if (control.delayMillis > 0) {
            val node = approach.state.node ?: throw Unavailable("Control wait has no original source node")
            if (control.lawfulWait != Match.YES) throw Unavailable("Lawful control waiting is ${control.lawfulWait}", control.lawfulWait)
            val context = snapshot(RoadPolicyTimeline.Kind.NODE, node)
            departureNodeContext = context
            val waiting = timeline.node(node, arc.direction, approach.state.instant, departure,
                context, endpointPolicy, permission, maximumBoundaries)
            diagnostics += waiting.diagnostics
            if (waiting.allowed != Match.YES) throw Unavailable("Continuous node waiting is ${waiting.allowed}: ${waiting.diagnostics.joinToString()}", waiting.allowed)
            permission = waiting.permissionAfter
            waitAllowed = Match.YES
            waitEvidence = "${control.revision}; ${control.evidence}; continuous node context ${context.revision}"
            check(waitEvidence.length <= 4096)
        } else if (approach.state.initial && approach.state.node != null && endpointPolicy != null) {
            // Match traversal's physical-order initial-node grant transition before entering way.
            val node = checkNotNull(approach.state.node)
            val nodeContext = snapshot(RoadPolicyTimeline.Kind.NODE, node)
            departureNodeContext = nodeContext
            val initial = timeline.node(node, arc.direction, departure, departure,
                nodeContext, endpointPolicy, permission, maximumBoundaries)
            if (initial.allowed != Match.YES) throw Unavailable("Initial node permission is ${initial.allowed}", initial.allowed)
            permission = initial.permissionAfter
        }
        val context = snapshot(RoadPolicyTimeline.Kind.ARC, arc.id)
        val sourceLength = lengthMm(arc.id)
        sourceStable()
        check(sourceLength >= 0)
        var speed = RoadSpeedEstimate.resolve(sourceArc.tags, arc.direction, context.context(departure), profile)
        var minimumSpeed = speed.estimatedKmH ?: throw Unavailable("Source speed unavailable: ${speed.diagnostics.joinToString()}")
        var duration = speed.travelMillis(portion, sourceLength) ?: throw Unavailable("No source travel estimate")
        diagnostics += speed.diagnostics
        repeat(maximumIterations) {
            ready()
            val arrival = departure.plusMillis(duration)
            check(arrival < control.validUntil) { "Control coverage expires during travel" }
            sourceStable()
            val interval = timeline.way(sourceArc, portion, departure, arrival, context,
                endpointPolicy, permission, maximumBoundaries)
            diagnostics += interval.diagnostics
            if (interval.allowed != Match.YES) throw Unavailable("Continuous road permission is ${interval.allowed}: ${interval.diagnostics.joinToString()}", interval.allowed)
            for (at in interval.evaluatedAt) {
                ready()
                speed = RoadSpeedEstimate.resolve(sourceArc.tags, arc.direction, context.context(at), profile)
                diagnostics += speed.diagnostics
                val candidate = speed.estimatedKmH ?: throw Unavailable("Interval speed unavailable: ${speed.diagnostics.joinToString()}")
                minimumSpeed = minimumSpeed.min(candidate)
            }
            // Conservative whole-interval minimum; never speed up on an expanded interval.
            val expanded = java.math.BigDecimal.valueOf(portion.distanceMm(sourceLength))
                .multiply(java.math.BigDecimal("3.6"))
                .divide(minimumSpeed, 0, java.math.RoundingMode.CEILING).longValueExact().coerceAtLeast(1L)
            if (expanded == duration) {
                sourceStable(); check(lengthMm(arc.id) == sourceLength)
                sourceStable()
                check(context === snapshot(RoadPolicyTimeline.Kind.ARC, arc.id)) { "Timing context snapshot replaced" }
                departureNodeContext?.let {
                    check(it === snapshot(RoadPolicyTimeline.Kind.NODE, it.sourceId)) { "Departure node context snapshot replaced" }
                }
                sourceStable()
                diagnostics += "Continuous speed/access context ${context.revision}; minimum estimated km/h ${minimumSpeed.toPlainString()}"
                return NoCamsGraphSearch.Timing(duration, Match.YES, diagnostics.toList(),
                    control.delayMillis, waitAllowed, waitEvidence)
            }
            check(expanded > duration) { "Nonmonotonic source travel estimate" }
            duration = expanded
        }
        throw Unavailable("Source speed/permission interval did not converge within explicit iteration budget")
    }
}
