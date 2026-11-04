package com.bggg.watchman.routing

import com.bggg.watchman.routing.ConditionalRestriction.Match
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.Locale

/** Continuous road/node policy for the supported RestrictionCondition language. That language
 * changes only at local minute/date boundaries, offset transitions, or supplied grant boundaries;
 * facts, quantities and calendars below are immutable and explicitly valid over the horizon.
 * This is an exhaustive partition, NOT arbitrary interval sampling. Unsupported predicates stay
 * UNKNOWN. It does not discover a zone, grants, entrances, operating controls or a speed model.
 */
class RoadPolicyTimeline(private val graph:MotorcarGraphTraversal.Graph) {
    enum class Kind { ARC, NODE }
    class Snapshot(val graphRevision:String,val kind:Kind,val sourceId:Long,val revision:String,
        val evidence:String,val validFrom:Instant,val validUntil:Instant,val zone:ZoneId?,
        facts:Map<String,Match> = emptyMap(),quantities:Map<String,BigDecimal> = emptyMap(),
        holidays:Map<Pair<String,LocalDate>,Match> = emptyMap()) {
        val facts:Map<String,Match> = Collections.unmodifiableMap(LinkedHashMap(facts))
        val quantities:Map<String,BigDecimal> = Collections.unmodifiableMap(LinkedHashMap(quantities))
        val holidays:Map<Pair<String,LocalDate>,Match> = Collections.unmodifiableMap(LinkedHashMap(holidays))
        internal val rules=zone?.rules
        init {
            require(graphRevision.isNotBlank() && graphRevision.length<=512 && sourceId>0)
            require(revision.isNotBlank() && revision.length<=512 && evidence.isNotBlank() && evidence.length<=4096)
            require(validFrom<validUntil)
            require(this.facts.size<=256 && this.quantities.size<=256 && this.holidays.size<=4096)
            require((this.facts.keys+this.quantities.keys).all { it.matches(Regex("[a-z][a-z0-9_:]{0,63}")) })
            require(this.quantities.values.all { it.signum()>=0 && it.precision()<=128 && kotlin.math.abs(it.scale())<=64 })
            require(this.holidays.keys.all { it.first in setOf("PH","SH") })
        }
        internal fun context(at:Instant):RestrictionCondition.Context {
            check(at>=validFrom && at<validUntil) { "Context horizon unavailable" }
            check(zone?.rules==rules) { "Time-zone rule revision changed" }
            return RestrictionCondition.Context(at,zone,facts,quantities) { kind,date ->
                holidays[kind.uppercase(Locale.ROOT) to date] ?: Match.UNKNOWN
            }
        }
    }
    data class Result(val allowed:Match,val evaluatedAt:List<Instant>,val diagnostics:List<String>,
        val contextRevision:String,val permissionAfter:EndpointAccessPolicy.State?)

    fun way(arc:MotorcarGraphTraversal.Arc,portion:RoadArcPortion,from:Instant,until:Instant,
        snapshot:Snapshot,endpointPolicy:EndpointAccessPolicy?=null,
        permission:EndpointAccessPolicy.State?=null,maximumBoundaries:Int=10000):Result =
        evaluate(snapshot,from,until,endpointPolicy,permission,maximumBoundaries) {
            check(snapshot.kind==Kind.ARC && snapshot.sourceId==arc.id && portion.arcId==arc.id && portion.direction==arc.direction)
            check(graph.arc(arc.id)==arc) { "Source arc changed/unavailable" }
            val frozen=arc.copy(tags=Collections.unmodifiableMap(LinkedHashMap(arc.tags)),unresolved=arc.unresolved.toList())
            require(frozen.tags.size<=4096 && frozen.tags.values.sumOf { it.length.toLong() }<=512000)
            if(frozen.unresolved.isNotEmpty())error("Unresolved source arc")
            Evaluator({ at -> MotorcarRoadProfile.way(frozen.tags,frozen.direction,snapshot.context(at)) },
                { state,decision,at -> checkNotNull(endpointPolicy).enterWay(state,frozen.wayId,decision.access,at) },
                { check(graph.arc(arc.id)==frozen) { "Source arc changed during interval" } },
                temporal(frozen.tags))
        }

    /** Node occupancy permission/physical state only. A separate control model must establish
     * that waiting there is lawful; ordinary passability alone is not a waiting authorization.
     */
    fun node(nodeId:Long,direction:MotorcarTagPolicy.Direction,from:Instant,until:Instant,
        snapshot:Snapshot,endpointPolicy:EndpointAccessPolicy?=null,
        permission:EndpointAccessPolicy.State?=null,maximumBoundaries:Int=10000):Result =
        evaluate(snapshot,from,until,endpointPolicy,permission,maximumBoundaries) {
            check(snapshot.kind==Kind.NODE && snapshot.sourceId==nodeId)
            val node=checkNotNull(graph.node(nodeId)) { "Source node unavailable" }
            check(node.id==nodeId)
            val frozen=node.copy(tags=Collections.unmodifiableMap(LinkedHashMap(node.tags)),unresolved=node.unresolved.toList())
            require(frozen.tags.size<=4096 && frozen.tags.values.sumOf { it.length.toLong() }<=512000)
            if(frozen.unresolved.isNotEmpty())error("Unresolved source node")
            Evaluator({ at -> MotorcarRoadProfile.node(frozen.tags,direction,snapshot.context(at)) },
                { state,decision,at -> checkNotNull(endpointPolicy).crossNode(state,nodeId,decision.access,at) },
                { check(graph.node(nodeId)==frozen) { "Source node changed during interval" } },
                temporal(frozen.tags))
        }

    private class Evaluator(val decision:(Instant)->MotorcarRoadProfile.Decision,
        val grant:(EndpointAccessPolicy.State,MotorcarRoadProfile.Decision,Instant)->EndpointAccessPolicy.Result,
        val stable:()->Unit,val temporal:Boolean)
    private fun temporal(tags:Map<String,String>)=tags.keys.any { it.endsWith(":conditional") || it=="opening_hours" }
    private fun evaluate(snapshot:Snapshot,from:Instant,until:Instant,policy:EndpointAccessPolicy?,
        permission:EndpointAccessPolicy.State?,maximum:Int,prepare:()->Evaluator):Result {
        require(maximum in 2..100000)
        val visited=ArrayList<Instant>()
        val diagnostics=ArrayList<String>()
        fun result(match:Match,state:EndpointAccessPolicy.State?=null)=Result(match,
            Collections.unmodifiableList(visited.toList()),Collections.unmodifiableList(diagnostics.distinct()),snapshot.revision,state)
        try {
            check(graph.revision==snapshot.graphRevision) { "Interval graph revision mismatch" }
            check(until>=from && from>=snapshot.validFrom && until<snapshot.validUntil) { "Interval outside evidenced context horizon" }
            require(Duration.between(from,until)<=Duration.ofDays(7)) { "Interval exceeds seven-day model bound" }
            require((policy==null)==(permission==null)) { "Endpoint policy/state must be supplied together" }
            policy?.requireGraph(graph)
            val evaluator=prepare()
            val boundaries=java.util.TreeSet<Instant>()
            boundaries+=from;boundaries+=until
            policy?.temporalBoundaries(from,until)?.let { boundaries.addAll(it) }
            check(boundaries.size<=maximum) { "Grant interval boundary budget exceeded" }
            if(evaluator.temporal && snapshot.zone!=null) {
                var cursor=from
                while(cursor<until) {
                    val local=cursor.atZone(snapshot.zone)
                    val nextMinute=cursor.plusNanos((60L-local.second)*1000000000L-local.nano)
                    val transition=snapshot.rules!!.nextTransition(cursor)?.instant
                    val next=if(transition!=null && transition<nextMinute)transition else nextMinute
                    check(next>cursor) { "Nonprogressing time partition" }
                    if(next>=until)break
                    check(boundaries.size<maximum) { "Temporal interval boundary budget exceeded" }
                    boundaries+=next;cursor=next
                }
            }
            var state=permission
            var firstState:EndpointAccessPolicy.State?=null
            var allowed=Match.YES
            fun include(value:Match) { allowed=when {
                allowed==Match.NO || value==Match.NO -> Match.NO
                allowed==Match.UNKNOWN || value==Match.UNKNOWN -> Match.UNKNOWN
                else -> Match.YES
            } }
            for(at in boundaries) {
                check(graph.revision==snapshot.graphRevision)
                evaluator.stable()
                visited+=at
                val decision=evaluator.decision(at)
                diagnostics+=decision.diagnostics.take(12)
                if(policy==null)include(decision.publicThrough) else {
                    include(decision.permissionIndependentPassability)
                    val resolved=evaluator.grant(checkNotNull(state),decision,at)
                    include(resolved.permission);diagnostics+=resolved.diagnostics
                    state=resolved.next
                    if(at==from)firstState=state
                    else if(state!=null && state!=firstState) {
                        include(Match.UNKNOWN)
                        diagnostics+="Unrepresented mid-interval endpoint-purpose transition"
                    }
                }
                if(allowed==Match.NO) {
                    evaluator.stable();check(graph.revision==snapshot.graphRevision && snapshot.zone?.rules==snapshot.rules)
                    return result(Match.NO)
                }
                if(policy!=null && state==null)return result(Match.UNKNOWN)
                check(diagnostics.size<=maximum*24L) { "Interval diagnostics budget exceeded" }
            }
            evaluator.stable()
            check(graph.revision==snapshot.graphRevision && snapshot.zone?.rules==snapshot.rules)
            return result(allowed,state.takeIf { allowed==Match.YES })
        } catch(failure:Exception) {
            diagnostics+="Continuous policy unavailable: ${failure.message ?: failure.javaClass.simpleName}"
            return result(Match.UNKNOWN)
        }
    }
}
