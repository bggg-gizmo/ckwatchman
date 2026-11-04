package com.bggg.watchman.routing

import com.bggg.watchman.routing.ConditionalRestriction.Match
import com.bggg.watchman.routing.MotorcarTagPolicy.AccessClass
import java.time.Instant
import java.util.Collections

/** Endpoint-use permission, NOT physical passability/turn legality or automatic scope discovery.
 * A restricted origin is an egress prefix; a restricted destination is an ingress suffix.
 * Scope/evidence selection must come from actual endpoint/facility/permission evidence, not radius.
 */
class EndpointAccessPolicy(private val graph:MotorcarGraphTraversal.Graph,
    val origin:RoadEndpointAnchor,val destination:RoadEndpointAnchor,
    private val originScope:Scope?,private val destinationScope:Scope?) {
    enum class Basis { ENDPOINT_PURPOSE, CUSTOMER_PURPOSE, DELIVERY_PURPOSE, INDIVIDUAL_PERMISSION, PERMIT_OR_STATUS }
    data class Grant(val access:AccessClass,val basis:Basis,val evidence:String,
        val validFrom:Instant?=null,val validUntil:Instant?=null) {
        init {
            require(evidence.isNotBlank() && evidence.length<=4096)
            require(validFrom==null || validUntil==null || validFrom<validUntil)
            val required=when(access) {
                AccessClass.DESTINATION->Basis.ENDPOINT_PURPOSE
                AccessClass.CUSTOMERS->Basis.CUSTOMER_PURPOSE
                AccessClass.DELIVERY->Basis.DELIVERY_PURPOSE
                AccessClass.PRIVATE->Basis.INDIVIDUAL_PERMISSION
                AccessClass.PERMIT,AccessClass.RESIDENTS,AccessClass.EMPLOYEES,AccessClass.AGRICULTURAL,
                AccessClass.FORESTRY,AccessClass.DISABLED->Basis.PERMIT_OR_STATUS
                else->error("Public, denied or unknown access cannot be granted")
            }
            require(basis==required) { "Permission basis does not establish this access class" }
        }
        fun valid(at:Instant)=(validFrom==null || at>=validFrom) && (validUntil==null || at<validUntil)
    }
    class Scope internal constructor(val revision:String,ways:Set<Long>,nodes:Set<Long>,grants:List<Grant>) {
        val wayIds:Set<Long> = Collections.unmodifiableSet(LinkedHashSet(ways))
        val nodeIds:Set<Long> = Collections.unmodifiableSet(LinkedHashSet(nodes))
        internal val grants:List<Grant> = Collections.unmodifiableList(ArrayList(grants))
        internal fun grant(access:AccessClass,at:Instant)=grants.firstOrNull{it.access==access && it.valid(at)}
    }
    class State internal constructor(val originOpen:Boolean,val destinationEntered:Boolean,internal val owner:Any) {
        override fun equals(other:Any?)=other is State && originOpen==other.originOpen &&
            destinationEntered==other.destinationEntered && owner===other.owner
        override fun hashCode()=listOf(originOpen,destinationEntered,System.identityHashCode(owner)).hashCode()
    }
    data class Use(val destination:Boolean,val grant:Grant)
    data class Result(val permission:Match,val next:State?,val evidence:List<Use>,val diagnostics:List<String>)
    val sourceRevision=origin.sourceRevision
    private val owner=Any()
    internal fun requireGraph(candidate:MotorcarGraphTraversal.Graph) {
        require(candidate===graph) { "Permission policy must use the traversal's graph snapshot" }
        check(graph.revision==sourceRevision)
    }
    private fun sourceFailure():Result? = if(runCatching{graph.revision==sourceRevision}.getOrDefault(false)) null
        else Result(Match.UNKNOWN,null,emptyList(),listOf("Permission source revision unavailable/changed"))
    init {
        require(originScope!=null || destinationScope!=null)
        check(destination.sourceRevision==sourceRevision && graph.revision==sourceRevision)
        fun verify(anchor:RoadEndpointAnchor,scope:Scope?) {
            val arc=checkNotNull(graph.arc(anchor.arcId)) { "Endpoint permission arc missing" }
            check(arc.id==anchor.arcId && arc.from==anchor.fromNode && arc.to==anchor.toNode) { "Endpoint permission identity mismatch" }
            if(scope!=null) {
                check(scope.revision==sourceRevision)
                val endpointNode=when(anchor.directedFraction){0.0->arc.from;1.0->arc.to;else->null}
                check(if(endpointNode!=null)endpointNode in scope.nodeIds else arc.wayId in scope.wayIds) { "Permission scope does not contain endpoint" }
            }
        }
        verify(origin,originScope);verify(destination,destinationScope)
        check(graph.revision==sourceRevision)
    }
    fun start(anchor:RoadEndpointAnchor):State {
        require(anchor==origin) { "Permission journey must start at its bound origin" }
        check(graph.revision==sourceRevision)
        return State(originScope!=null,false,owner)
    }
    /** Exact arbitrary-instant grant boundaries for continuous source timing. This exposes no
     * additional permission and must be bound to this same immutable graph/purpose policy.
     */
    internal fun temporalBoundaries(from:Instant,until:Instant):Set<Instant> {
        require(until>=from)
        check(graph.revision==sourceRevision) { "Permission source changed" }
        return (originScope?.grants.orEmpty()+destinationScope?.grants.orEmpty())
            .flatMap { listOfNotNull(it.validFrom,it.validUntil) }
            .filterTo(java.util.TreeSet()) { it>=from && it<=until }
    }
    /** Call for each entered/continued way. Public node tags between ways do not close a scope. */
    fun enterWay(state:State,wayId:Long,access:MotorcarTagPolicy.Result,at:Instant):Result {
        require(state.owner===owner && wayId>0)
        sourceFailure()?.let{return it}
        if(state.destinationEntered && destinationScope?.wayIds?.contains(wayId)!=true)
            return Result(Match.NO,null,emptyList(),listOf("Restricted destination ingress cannot become a through-road exit"))
        val originOpen=state.originOpen && originScope?.wayIds?.contains(wayId)==true
        return resolve(state,originOpen,wayId,false,access,at)
    }
    /** Call in physical order: starting node, way, reached node. This checks only permission. */
    fun crossNode(state:State,nodeId:Long,access:MotorcarTagPolicy.Result,at:Instant):Result {
        require(state.owner===owner && nodeId>0)
        sourceFailure()?.let{return it}
        if(state.destinationEntered && destinationScope?.nodeIds?.contains(nodeId)!=true)
            return Result(Match.NO,null,emptyList(),listOf("Restricted destination ingress cannot exit through an unrelated node"))
        return resolve(state,state.originOpen && originScope?.nodeIds?.contains(nodeId)==true,nodeId,true,access,at)
    }
    private fun resolve(state:State,originOpen:Boolean,id:Long,node:Boolean,access:MotorcarTagPolicy.Result,at:Instant):Result {
        fun rejected(message:String)=Result(Match.UNKNOWN,null,emptyList(),listOf(message))
        if(runCatching{graph.revision==sourceRevision}.getOrDefault(false)!=true) return rejected("Permission source revision unavailable/changed")
        if(access.unhandledTags.isNotEmpty()) return rejected("Unhandled access/direction tags cannot be bypassed by an endpoint grant")
        if(access.accessClasses.isEmpty()) return rejected("No evaluated access alternatives")
        fun member(scope:Scope?)=scope!=null && if(node)id in scope.nodeIds else id in scope.wayIds
        val uses=ArrayList<Use>()
        val alternatives=access.accessClasses.map { kind ->
            when(kind) {
                AccessClass.PUBLIC->Match.YES
                AccessClass.DENIED->Match.NO
                AccessClass.UNKNOWN->Match.UNKNOWN
                else->{
                    val from=if(originOpen && member(originScope))originScope?.grant(kind,at) else null
                    val to=if(member(destinationScope))destinationScope?.grant(kind,at) else null
                    when {
                        from!=null->{uses+=Use(false,from);Match.YES}
                        to!=null->{uses+=Use(true,to);Match.YES}
                        else->Match.NO
                    }
                }
            }
        }
        val decision=when { alternatives.all{it==Match.YES}->Match.YES;alternatives.all{it==Match.NO}->Match.NO;else->Match.UNKNOWN }
        val next=if(decision==Match.YES)State(originOpen,state.destinationEntered || uses.any{it.destination},owner) else null
        return Result(decision,next,uses.toList(),if(decision==Match.YES)emptyList() else listOf("Endpoint permission absent, expired, denied or uncertain for source$id"))
    }

    companion object {
        /** Validate a DECLARED connected scope from original way/node identities. This does not
         * discover the full component, establish ownership or fabricate customer/private evidence.
         */
        fun scope(graph:MotorcarGraphTraversal.Graph,memberWays:Set<Long>,grants:List<Grant>,
            maximumWays:Int=10000,maximumNodes:Int=100000):Scope {
            require(maximumWays in 1..100000 && maximumNodes in 1..1000000)
            require(memberWays.isNotEmpty() && memberWays.size<=maximumWays && memberWays.all{it>0})
            require(grants.size in 1..64)
            val geometry=graph as? TurnRelationCompiler.Geometry ?: error("Original source way geometry required")
            val revision=graph.revision;require(revision.isNotBlank())
            val ids=memberWays.sorted();val parent=IntArray(ids.size){it}
            fun root(index:Int):Int { var i=index;while(parent[i]!=i){parent[i]=parent[parent[i]];i=parent[i]};return i }
            val nodes=HashMap<Long,Int>();var references=0L
            for((index,id) in ids.withIndex()) {
                val way=checkNotNull(geometry.way(id)) { "Missing permission-scope way$id" }
                check(way.id==id && way.nodes.size>=2)
                references+=way.nodes.size;check(references<=2000000) { "Permission scope reference budget exceeded" }
                for(node in way.nodes) {
                    check(node>0)
                    val previous=nodes.putIfAbsent(node,index)
                    if(previous!=null)parent[root(index)]=root(previous)
                    check(nodes.size<=maximumNodes) { "Permission scope node budget exceeded" }
                }
            }
            check(ids.indices.all{root(it)==root(0)}) { "Disconnected permission scope; coordinates cannot join source nodes" }
            check(graph.revision==revision) { "Permission source changed during scope validation" }
            return Scope(revision,ids.toSet(),nodes.keys,grants)
        }
    }
}
