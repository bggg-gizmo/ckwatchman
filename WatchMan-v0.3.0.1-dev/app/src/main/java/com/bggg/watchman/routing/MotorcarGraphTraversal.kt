package com.bggg.watchman.routing

import java.time.Instant
import com.bggg.watchman.routing.ConditionalRestriction.Match
import com.bggg.watchman.routing.MotorcarTagPolicy.Direction

/**
 * Directed full/partial-arc transition evaluator with optional evidenced endpoint permission.
 * The graph adapter must expose ORIGINAL consecutive-node arcs, immutable revision identity and
 * complete scoped restriction coverage, including unresolved relations. Missing data is UNKNOWN.
 * Without an explicit endpoint policy this only allows public-through access. It does not choose paths, estimate travel time or wait for
 * signals/ferries. Interior positions preserve original arc identity, never create road junctions.
 * Entry/continuation-time way policy and arrival-time node policy are explicit, not proof of
 * continuous permission over the interval. Adapters must retain unsupported interval constraints.
 */
class MotorcarGraphTraversal(
    private val graph: Graph,
    relations: List<Relation>,
    private val endpointPolicy: EndpointAccessPolicy?,
    private val contextAt: (Location, Instant) -> RestrictionCondition.Context
) {
    constructor(graph:Graph,relations:List<Relation>,contextAt:(Location,Instant)->RestrictionCondition.Context):
        this(graph,relations,null,contextAt)
    data class Arc(val id: Long, val wayId: Long, val from: Long, val to: Long,
        val direction: Direction, val tags: Map<String, String>, val unresolved: List<String> = emptyList())
    data class Node(val id: Long, val tags: Map<String, String>, val unresolved: List<String> = emptyList())
    /** Must include unresolved/missing-source relation IDs and every relation touching this arc. */
    data class Coverage(val complete: Boolean, val relationIds: Set<Long>, val problems: List<String>)
    interface Graph {
        /** A content revision, not a mutable file path or an unverified 'authoritative' label. */
        val revision: String
        fun arc(id: Long): Arc?
        fun node(id: Long): Node?
        fun turnCoverage(arc: Arc): Coverage
    }
    data class Relation(val id: Long, val tags: Map<String, String>, val variants: List<TurnRelationCompiler.Compiled>)
    enum class Phase { WAY_ENTRY, WAY_CONTINUATION, INITIAL_NODE, ARRIVAL_NODE, TURN, RESTRICTION_COVERAGE, WAIT_NODE }
    /** Interior contexts have no nodeId. Resolve their coordinate from arcId/directedFraction. */
    data class Location(val phase: Phase, val sourceId: Long, val nodeId: Long?,
        val arcId: Long? = null, val directedFraction: Double? = null)
    /** Road decision retains its public-through result; permission is separate audited evidence. */
    data class Check(val location: Location, val instant: Instant, val decision: MotorcarRoadProfile.Decision,
        val permission:EndpointAccessPolicy.Result? = null)
    sealed class Position {
        data class AtNode(val id:Long):Position() { init { require(id>0) } }
        data class Interior(val arcId:Long,val fraction:Double):Position() {
            init { require(arcId>0 && fraction.isFinite() && fraction>0.0 && fraction<1.0) }
        }
    }
    class State internal constructor(val position:Position, val instant: Instant,
        val turns: TurnRestrictionMachine.State, val initial: Boolean, internal val owner: Any,
        val permission:EndpointAccessPolicy.State? = null) {
        val node:Long? get()=(position as? Position.AtNode)?.id
        override fun equals(other: Any?) = other is State && position == other.position && instant == other.instant &&
            turns == other.turns && initial == other.initial && owner === other.owner && permission == other.permission
        override fun hashCode(): Int = listOf(position, instant, turns, initial, System.identityHashCode(owner),permission).hashCode()
    }
    data class Step(val allowed: Match, val next: State?, val checks: List<Check>,
        val blockedRelations: Set<Long>, val unresolvedRelations: Set<Long>, val diagnostics: List<String>,
        val portion:RoadArcPortion? = null)
    private val identity = Any()
    private val revision = graph.revision.also { require(it.isNotBlank()) { "Graph revision required" } }
    private val catalogue: Map<Long, Relation>
    private val byKey = HashMap<Int, Pair<Long, String>>()
    private val machine: TurnRestrictionMachine

    init {
        endpointPolicy?.requireGraph(graph)
        require(relations.size <= 100_000) { "Relation catalogue exceeds budget" }
        require(relations.all { it.id > 0 } && relations.map { it.id }.distinct().size == relations.size) { "Invalid/duplicate relation identity" }
        var pathArcs = 0L
        var variantCount = 0L
        for (relation in relations) for (variant in relation.variants) {
            require(variant.relationId == relation.id) { "Compiled geometry belongs to a different relation" }
            variantCount++
            require(variantCount <= 100_000 && variant.paths.size <= 4096) { "Variant/path count exceeds budget" }
            for (path in variant.paths) {
                pathArcs += path.size
                require(path.size <= 4096 && pathArcs <= 4_000_000) { "Relation arc budget exceeded" }
            }
        }
        catalogue = relations.associate { relation ->
            require(relation.variants.map { it.rawRestriction }.distinct().size == relation.variants.size) { "Duplicate restriction variant" }
            relation.id to relation.copy(tags = relation.tags.toMap(), variants = relation.variants.map {
                it.copy(members = it.members.toList(), paths = it.paths.map { path -> path.toList() }, problems = it.problems.toList())
            })
        }
        val rules = ArrayList<TurnRestrictionMachine.Rule>()
        for (relation in catalogue.values) for (variant in relation.variants) if (variant.resolved) {
            val key = rules.size
            byKey[key] = relation.id to variant.rawRestriction
            rules += variant.rule(key)
        }
        machine = TurnRestrictionMachine(rules)
    }

    /** The starting node is evaluated on the first departure, not silently skipped. */
    fun start(node: Long, instant: Instant): State {
        require(endpointPolicy==null) { "Endpoint permissions require startAt with the bound origin" }
        require(node > 0) { "Invalid starting node" }
        return State(Position.AtNode(node), instant, machine.start, true, identity)
    }

    /** Validate the spatial-reader/graph boundary before using a projected endpoint. No access
     * check is bypassed: the following transition still evaluates way, node and turn policies.
     */
    fun positionOf(anchor:RoadEndpointAnchor):Position {
        check(anchor.sourceRevision==revision && graph.revision==revision) { "Endpoint source revision mismatch" }
        val arc=checkNotNull(graph.arc(anchor.arcId)) { "Endpoint directed arc missing" }
        check(arc.id==anchor.arcId && arc.from==anchor.fromNode && arc.to==anchor.toNode) { "Endpoint source identity mismatch" }
        check(graph.revision==revision) { "Endpoint source changed during lookup" }
        return when(anchor.directedFraction) {
            0.0 -> Position.AtNode(arc.from)
            1.0 -> Position.AtNode(arc.to)
            else -> Position.Interior(arc.id,anchor.directedFraction)
        }
    }
    fun startAt(anchor:RoadEndpointAnchor,instant:Instant):State =
        State(positionOf(anchor),instant,machine.start,true,identity,endpointPolicy?.start(anchor))

    /** Use only after successful transitions; a nearby node or other interior fraction is not
     * the journey's destination. This is not proof of interval permission or entrance discovery.
     */
    fun hasReachedBoundDestination(state:State):Boolean {
        require(state.owner===identity)
        val policy=checkNotNull(endpointPolicy) { "No endpoint-bound journey" }
        return !state.initial && state.permission!=null && state.position==positionOf(policy.destination)
    }

    /** No proximity-based access grant. Choose the permitted directed candidate before departure.
     * Fractions exactly0/1 must be represented by their actual source nodes, not interior aliases.
     */
    fun startOnArc(arcId:Long,directedFraction:Double,instant:Instant):State {
        require(endpointPolicy==null) { "Endpoint permissions require startAt with the bound origin" }
        return State(Position.Interior(arcId,directedFraction),instant,machine.start,true,identity)
    }

    /** Explicit departure-changing control wait at an ORIGINAL node. The caller's immutable
     * timing model must establish lawful waiting/node permission throughout the interval, not
     * just at these endpoint checks. This preserves approach/turn/purpose history and introduces
     * no road arc or coordinate connector. An interior endpoint cannot become a waiting junction.
     */
    fun waitAtNode(state:State,direction:Direction,durationMillis:Long,
        intervalAllowed:Match,evidence:String?):Step {
        require(state.owner===identity) { "Waiting state belongs to another graph evaluator" }
        require(durationMillis>0)
        val checks=ArrayList<Check>()
        val diagnostics=ArrayList<String>()
        var allowed=intervalAllowed
        var permissionState=state.permission
        fun include(value:Match) {
            allowed=when { allowed==Match.NO || value==Match.NO -> Match.NO
                allowed==Match.UNKNOWN || value==Match.UNKNOWN -> Match.UNKNOWN;else -> Match.YES }
        }
        fun result(next:State?=null)=Step(allowed,if(allowed==Match.YES)next else null,
            checks.toList(),emptySet(),emptySet(),diagnostics.distinct())
        if(intervalAllowed!=Match.YES) {
            diagnostics+="Lawful continuous control waiting is $intervalAllowed";return result()
        }
        if(evidence.isNullOrBlank() || evidence.length>4096) {
            include(Match.UNKNOWN);diagnostics+="Missing lawful wait interval evidence";return result()
        }
        val position=state.position as? Position.AtNode ?: run {
            include(Match.UNKNOWN);diagnostics+="No original-node waiting position";return result()
        }
        try {
            check(graph.revision==revision) { "Wait source changed" }
            val node=checkNotNull(graph.node(position.id)) { "Wait node missing" }
            check(node.id==position.id)
            if(node.unresolved.isNotEmpty()) { include(Match.UNKNOWN);diagnostics+=node.unresolved }
            val end=state.instant.plusMillis(durationMillis)
            for(instant in listOf(state.instant,end)) {
                val location=Location(Phase.WAIT_NODE,node.id,node.id)
                val context=contextAt(location,instant)
                require(context.instant==instant)
                val decision=MotorcarRoadProfile.node(node.tags,direction,context)
                var permission:EndpointAccessPolicy.Result?=null
                if(endpointPolicy==null)include(decision.publicThrough) else {
                    include(decision.permissionIndependentPassability)
                    val previous=permissionState
                    if(previous==null) { include(Match.UNKNOWN);diagnostics+="Wait permission state unavailable" }
                    else {
                        permission=endpointPolicy.crossNode(previous,node.id,decision.access,instant)
                        include(permission.permission);diagnostics+=permission.diagnostics
                        permissionState=permission.next
                    }
                }
                checks+=Check(location,instant,decision,permission)
                diagnostics+=decision.diagnostics
            }
            check(graph.revision==revision) { "Wait source changed during evaluation" }
            return result(State(position,end,state.turns,state.initial,identity,permissionState))
        } catch(failure:Exception) {
            include(Match.UNKNOWN);diagnostics+="Wait source/context unavailable: ${failure.javaClass.simpleName}"
            return result()
        }
    }

    /** Duration is supplied by the separate timing model, never guessed or read from a wall clock. */
    fun advance(state: State, arcId: Long, durationMillis: Long): Step = advanceTo(state,arcId,1.0,durationMillis)

    /** Reach an interior destination or the original arc end. An interior state can only continue
     * that same directed arc; it cannot jump to a parallel road or reverse at an invented junction.
     * Splitting an already-entered arc must not feed it into the turn machine a second time.
     */
    fun advanceTo(state:State,arcId:Long,toFraction:Double,durationMillis:Long):Step {
        require(state.owner === identity) { "Traversal state belongs to a different graph evaluator" }
        require(arcId > 0 && durationMillis > 0) { "Positive arc identity and duration required" }
        require(toFraction.isFinite() && toFraction>0.0 && toFraction<=1.0) { "Invalid destination fraction" }
        val fromFraction=(state.position as? Position.Interior)?.fraction ?: 0.0
        require(toFraction>fromFraction) { "Arc portion must make forward progress" }
        val entering=state.initial || state.position is Position.AtNode
        var portion:RoadArcPortion?=null
        val checks = ArrayList<Check>()
        val diagnostics = ArrayList<String>()
        val uncertain = linkedSetOf<Long>()
        val blocked = linkedSetOf<Long>()
        var allowed = Match.YES
        var permissionState=state.permission
        fun include(value: Match) {
            allowed = when { allowed == Match.NO || value == Match.NO -> Match.NO
                allowed == Match.UNKNOWN || value == Match.UNKNOWN -> Match.UNKNOWN; else -> Match.YES }
        }
        fun issue(message: String) { include(Match.UNKNOWN); diagnostics += message }
        fun result(next: State? = null) = Step(allowed, if (allowed == Match.YES) next else null,
            checks.toList(), blocked.toSet(), uncertain.toSet(), diagnostics.distinct(),portion)
        fun context(location: Location, instant: Instant): RestrictionCondition.Context? = try {
            contextAt(location, instant).also { require(it.instant == instant) { "Context instant differs from traversal instant" } }
        } catch (failure: Exception) { issue("Context unavailable at $location: ${failure.javaClass.simpleName}"); null }
        fun evaluate(location: Location, instant: Instant, tags: Map<String, String>, direction: Direction) {
            val ctx = context(location, instant) ?: return
            val decision = if (location.phase == Phase.WAY_ENTRY || location.phase == Phase.WAY_CONTINUATION) MotorcarRoadProfile.way(tags, direction, ctx)
                else MotorcarRoadProfile.node(tags, direction, ctx)
            var permission:EndpointAccessPolicy.Result?=null
            if(endpointPolicy==null) include(decision.publicThrough)
            else {
                include(decision.permissionIndependentPassability)
                val current=permissionState
                if(current==null) issue("Endpoint permission state unavailable after a denied/uncertain event")
                else {
                    permission=if(location.phase==Phase.WAY_ENTRY || location.phase==Phase.WAY_CONTINUATION)
                        endpointPolicy.enterWay(current,location.sourceId,decision.access,instant)
                    else endpointPolicy.crossNode(current,location.sourceId,decision.access,instant)
                    include(permission.permission)
                    diagnostics+=permission.diagnostics
                    permissionState=permission.next
                }
            }
            checks += Check(location, instant, decision,permission)
            diagnostics += decision.diagnostics
            if (endpointPolicy==null && decision.access.publicThroughAccess == Match.NO) diagnostics += "No public-through permission at $location; endpoint/purpose grants are separate"
        }
        // Graph reads can fail independently of legal policy. Never publish a partially read step.
        val arc: Arc
        val from: Node?
        val to: Node?
        val coverage: Coverage
        val arrival: Instant
        try {
            if (graph.revision != revision) { issue("Graph revision changed; restart search against one snapshot"); return result() }
            arc = graph.arc(arcId) ?: run { issue("Missing directed arc $arcId"); return result() }
            val connected=when(val position=state.position) {
                is Position.AtNode -> arc.from==position.id
                is Position.Interior -> arc.id==position.arcId
            }
            if (arc.id != arcId || arc.wayId <= 0 || !connected || arc.from<=0 || arc.to <= 0 || arc.from == arc.to) {
                issue("Invalid or disconnected directed arc $arcId"); return result()
            }
            portion=RoadArcPortion(arcId,fromFraction,toFraction,arc.direction)
            from = if(fromFraction==0.0) graph.node(arc.from) ?: run { issue("Missing departure node ${arc.from}"); return result() } else null
            to = if(toFraction==1.0) graph.node(arc.to) ?: run { issue("Missing arrival node ${arc.to}"); return result() } else null
            if ((from!=null && from.id != arc.from) || (to!=null && to.id != arc.to)) { issue("Graph node identity mismatch"); return result() }
            coverage = graph.turnCoverage(arc)
            arrival = state.instant.plusMillis(durationMillis)
        } catch (failure: Exception) { issue("Graph/timing lookup failed: ${failure.javaClass.simpleName}"); return result() }
        for (problem in arc.unresolved + from?.unresolved.orEmpty() + to?.unresolved.orEmpty() + coverage.problems) issue(problem)
        if (!coverage.complete) issue("Turn coverage incomplete on arc $arcId")
        // Endpoint policy state follows physical order, including a restricted departure node.
        if (endpointPolicy!=null && state.initial && from!=null) evaluate(Location(Phase.INITIAL_NODE, from.id, from.id), state.instant, from.tags, arc.direction)
        evaluate(Location(if(entering)Phase.WAY_ENTRY else Phase.WAY_CONTINUATION, arc.wayId, from?.id,arc.id,fromFraction), state.instant, arc.tags, arc.direction)
        if (endpointPolicy==null && state.initial && from!=null) evaluate(Location(Phase.INITIAL_NODE, from.id, from.id), state.instant, from.tags, arc.direction)
        if(to!=null) evaluate(Location(Phase.ARRIVAL_NODE, to.id, to.id), arrival, to.tags, arc.direction)
        val turnContext = context(Location(if(from!=null)Phase.TURN else Phase.RESTRICTION_COVERAGE,
            from?.id ?: arc.wayId, from?.id,arc.id,fromFraction), state.instant)
        val policies = HashMap<Long, TurnRestrictionPolicy.Resolution>()
        fun policy(id: Long): TurnRestrictionPolicy.Resolution? {
            val relation = catalogue[id] ?: run { uncertain += id; issue("Missing restriction catalogue entry $id"); return null }
            if (turnContext == null) { uncertain += id; return null }
            return policies.getOrPut(id) { TurnRestrictionPolicy.resolve(relation.tags, turnContext) }
        }
        // Even a relation with NO successfully compiled variants must retain a scoped guard.
        for (id in coverage.relationIds) {
            val resolved = policy(id) ?: continue
            diagnostics += resolved.diagnostics
            for (value in resolved.possibleValues) if (value != "none") {
                val geometry = catalogue.getValue(id).variants.singleOrNull { it.rawRestriction == value }
                if (value == null || geometry?.resolved != true) {
                    uncertain += id
                    issue("Unresolved active/possible restriction $id value=$value: ${geometry?.problems ?: "missing variant"}")
                }
            }
        }
        val nextTurns=if(entering) {
            val turn = machine.advance(state.turns, arcId) { rule ->
                val binding = byKey.getValue(rule.key)
                policy(binding.first)?.activation(binding.second) ?: Match.UNKNOWN
            }
            include(turn.allowed)
            blocked += turn.blockedRelations
            uncertain += turn.unresolvedRelations
            turn.next
        } else state.turns
        try { if (graph.revision != revision) issue("Graph revision changed during traversal") }
        catch (failure: Exception) { issue("Graph revision check failed: ${failure.javaClass.simpleName}") }
        val reached=if(to!=null)Position.AtNode(to.id) else Position.Interior(arcId,toFraction)
        return result(if (allowed == Match.YES) State(reached, arrival, checkNotNull(nextTurns), false, identity,permissionState) else null)
    }
}
