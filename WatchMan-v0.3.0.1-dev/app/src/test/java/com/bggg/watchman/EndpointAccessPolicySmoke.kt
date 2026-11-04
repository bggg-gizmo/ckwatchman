package com.bggg.watchman

import com.bggg.watchman.routing.EndpointAccessPolicy as Policy
import com.bggg.watchman.routing.MotorcarGraphTraversal as Traversal
import com.bggg.watchman.routing.TurnRelationCompiler as Compiler
import com.bggg.watchman.routing.MotorcarTagPolicy as Tags
import com.bggg.watchman.routing.MotorcarTagPolicy.AccessClass as Kind
import com.bggg.watchman.routing.RoadEndpointAnchor
import com.bggg.watchman.routing.RestrictionCondition
import com.bggg.watchman.routing.ConditionalRestriction.Match
import java.time.Instant
import java.time.ZoneId

fun endpointAccessPolicySmoke() {
    val at=Instant.parse("2026-09-18T12:00:00Z")
    class Graph:Traversal.Graph,Compiler.Geometry {
        override var revision="permission-source-1"
        var mutate=false
        val tags=mutableMapOf<Long,Map<String,String>>()
        val nodeTags=mutableMapOf<Long,Map<String,String>>()
        var coverage=Traversal.Coverage(true,emptySet(),emptyList())
        val ways=mutableMapOf(101L to listOf(1L,2L),102L to listOf(2L,3L),103L to listOf(3L,4L),
            104L to listOf(4L,5L),105L to listOf(10L,11L))
        override fun way(id:Long):Compiler.Way? { if(mutate)revision="mutated";return ways[id]?.let{Compiler.Way(id,it)} }
        override fun arc(id:Long):Traversal.Arc? {
            val forward=id<1000;val wayId=if(forward)id else id-1000
            return ways[wayId]?.let{Traversal.Arc(id,wayId,if(forward)it.first() else it.last(),if(forward)it.last() else it.first(),
                if(forward)Tags.Direction.FORWARD else Tags.Direction.BACKWARD,mapOf("highway" to "service")+tags[wayId].orEmpty())}
        }
        override fun arc(wayId:Long,segmentOrdinal:Int,forward:Boolean)=if(segmentOrdinal==0 && wayId in ways)wayId+(if(forward)0 else 1000) else null
        override fun node(id:Long)=Traversal.Node(id,nodeTags[id].orEmpty())
        override fun turnCoverage(arc:Traversal.Arc)=coverage
    }
    val graph=Graph()
    val origin=RoadEndpointAnchor(graph.revision,101,1,2,0.5)
    val destination=RoadEndpointAnchor(graph.revision,103,3,4,0.5)
    fun reject(block:()->Unit){check(runCatching(block).isFailure)}
    fun access(value:String,extra:Map<String,String> = emptyMap())=Tags.resolve(mapOf("access" to value)+extra,
        Tags.Direction.FORWARD,Tags.Defaults("yes","no","explicit synthetic fixture"),RestrictionCondition.Context(at,ZoneId.of("UTC")))
    val purpose=Policy.Grant(Kind.DESTINATION,Policy.Basis.ENDPOINT_PURPOSE,"Fixture endpoint on declared connected road scope")
    val originScope=Policy.scope(graph,setOf(101),listOf(purpose))
    val destinationScope=Policy.scope(graph,setOf(103),listOf(purpose))
    val policy=Policy(graph,origin,destination,originScope,destinationScope)
    val initial=policy.start(origin)
    check(initial==policy.start(origin) && initial.originOpen && !initial.destinationEntered)
    val first=policy.enterWay(initial,101,access("destination"),at)
    check(first.permission==Match.YES && first.evidence.single().destination==false)
    val publicNode=policy.crossNode(first.next!!,2,access("yes"),at).next!!
    check(publicNode.originOpen) // Public node tags inside a member way do not end origin access.
    val through=policy.enterWay(publicNode,102,access("yes"),at).next!!
    check(!through.originOpen && through!=initial)
    check(policy.enterWay(through,101,access("destination"),at).permission==Match.NO)
    val incoming=policy.crossNode(through,3,access("destination"),at)
    check(incoming.permission==Match.YES && incoming.next!!.destinationEntered)
    val ingress=policy.enterWay(incoming.next!!,103,access("destination"),at)
    check(ingress.permission==Match.YES && ingress.evidence.single().destination)
    check(policy.enterWay(ingress.next!!,104,access("yes"),at).permission==Match.NO)
    check(policy.crossNode(ingress.next,5,access("yes"),at).permission==Match.NO)
    // Merely travelling on a public road in a declared scope does not consume a restricted grant.
    val notRestricted=policy.enterWay(through,103,access("yes"),at).next!!
    check(!notRestricted.destinationEntered && policy.enterWay(notRestricted,104,access("yes"),at).permission==Match.YES)
    for(value in listOf("no","private","customers","delivery","permit","residents","employees","agricultural","forestry","disabled"))
        check(policy.enterWay(initial,101,access(value),at).permission==Match.NO)
    check(policy.enterWay(initial,101,access("unknown-value"),at).permission==Match.UNKNOWN)
    check(policy.enterWay(initial,101,access("yes",mapOf("access:conditional" to "private @ wet")),at).permission==Match.UNKNOWN)
    check(policy.enterWay(initial,101,access("destination",mapOf("motorcar:lanes" to "yes|no")),at).permission==Match.UNKNOWN)
    reject{policy.start(destination)}
    val other=Policy(graph,origin,destination,originScope,destinationScope)
    reject{other.enterWay(initial,101,access("destination"),at)}
    reject{Policy(graph,origin.copy(sourceRevision="old"),destination,originScope,destinationScope)}
    reject{Policy(graph,origin.copy(fromNode=99),destination,originScope,destinationScope)}
    reject{Policy(graph,origin,destination,Policy.scope(graph,setOf(105),listOf(purpose)),destinationScope)}
    reject{Policy.Grant(Kind.PRIVATE,Policy.Basis.ENDPOINT_PURPOSE,"Proximity is not private permission")}
    reject{Policy.Grant(Kind.DENIED,Policy.Basis.INDIVIDUAL_PERMISSION,"Cannot override access=no")}
    reject{Policy.Grant(Kind.PRIVATE,Policy.Basis.INDIVIDUAL_PERMISSION,"")}
    val privateGrant=Policy.Grant(Kind.PRIVATE,Policy.Basis.INDIVIDUAL_PERMISSION,"Explicit fixture permission for these original ways/nodes",at.minusSeconds(60),at.plusSeconds(60))
    val privateScope=Policy.scope(graph,setOf(103),listOf(privateGrant))
    val privatePolicy=Policy(graph,origin,destination,null,privateScope)
    check(privatePolicy.enterWay(privatePolicy.start(origin),103,access("private"),at).permission==Match.YES)
    check(privatePolicy.enterWay(privatePolicy.start(origin),103,access("private"),at.plusSeconds(60)).permission==Match.NO)
    check(privatePolicy.enterWay(privatePolicy.start(origin),103,access("private"),at.minusSeconds(61)).permission==Match.NO)
    val customer=Policy.Grant(Kind.CUSTOMERS,Policy.Basis.CUSTOMER_PURPOSE,"Actual fixture facility patron purpose")
    val customerPolicy=Policy(graph,origin,destination,null,Policy.scope(graph,setOf(103),listOf(customer)))
    check(customerPolicy.enterWay(customerPolicy.start(origin),103,access("customers"),at).permission==Match.YES)
    check(customerPolicy.enterWay(customerPolicy.start(origin),103,access("private"),at).permission==Match.NO)
    val connected=Policy.scope(graph,setOf(101,102,103),listOf(purpose))
    check(connected.wayIds==setOf(101L,102L,103L) && connected.nodeIds==setOf(1L,2L,3L,4L))
    reject{(connected.wayIds as MutableSet<Long>).add(999)}
    reject{Policy.scope(graph,setOf(101,105),listOf(purpose))}
    reject{Policy.scope(graph,setOf(999),listOf(purpose))}
    reject{Policy.scope(graph,setOf(101,102),listOf(purpose),maximumWays=1)}
    reject{Policy.scope(graph,setOf(101),listOf(purpose),maximumNodes=1)}
    reject{Policy.scope(graph,emptySet(),listOf(purpose))}
    val mutableWays=mutableSetOf(101L);val mutableGrants=mutableListOf(purpose)
    val immutable=Policy.scope(graph,mutableWays,mutableGrants)
    mutableWays+=105L;mutableGrants.clear()
    check(immutable.wayIds==setOf(101L))
    check(Policy(graph,origin,destination,immutable,destinationScope).let{it.enterWay(it.start(origin),101,access("destination"),at).permission}==Match.YES)

    // Exercise the real graph evaluator, not permission decisions in isolation.
    fun context(location:Traversal.Location,instant:Instant)=RestrictionCondition.Context(instant,ZoneId.of("UTC"))
    graph.tags[101]=mapOf("access" to "destination")
    graph.tags[103]=mapOf("access" to "destination")
    val engine=Traversal(graph,emptyList(),policy,::context)
    val departure=engine.startAt(origin,at)
    check(!engine.hasReachedBoundDestination(departure))
    reject{engine.start(1,at)}
    reject{engine.startOnArc(101,0.5,at)}
    reject{engine.startAt(destination,at)}
    reject{Traversal(Graph(),emptyList(),policy,::context)}
    val originStep=engine.advance(departure,101,1000)
    check(originStep.allowed==Match.YES && originStep.next!!.permission!!.originOpen)
    check(originStep.checks.first().decision.publicThrough==Match.NO)
    check(originStep.checks.first().permission!!.evidence.single().grant==purpose)
    val connecting=engine.advance(originStep.next!!,102,1000)
    check(connecting.allowed==Match.YES && !connecting.next!!.permission!!.originOpen)
    val arrival=engine.advanceTo(connecting.next!!,103,0.5,1000)
    check(arrival.allowed==Match.YES && engine.hasReachedBoundDestination(arrival.next!!))
    check(arrival.next!!.permission!!.destinationEntered)
    check(!engine.hasReachedBoundDestination(engine.advanceTo(connecting.next,103,0.25,500).next!!))
    val pastDestination=engine.advance(arrival.next,103,1000)
    check(!engine.hasReachedBoundDestination(pastDestination.next!!))
    check(engine.advance(pastDestination.next,104,1000).let{it.allowed==Match.NO && it.next==null})
    check(engine.advance(connecting.next,1102,1000).let{it.allowed==Match.YES &&
        engine.advance(it.next!!,1101,1000).allowed==Match.NO})
    val publicOnly=Traversal(graph,emptyList(),::context)
    check(publicOnly.advance(publicOnly.startAt(origin,at),101,1000).allowed==Match.NO)

    // A restricted departure node belongs to origin egress even when its outgoing way is public.
    val nodeOrigin=origin.copy(directedFraction=1.0)
    graph.nodeTags[2]=mapOf("access" to "destination")
    val nodeEngine=Traversal(graph,emptyList(),Policy(graph,nodeOrigin,destination,originScope,destinationScope),::context)
    val nodeExit=nodeEngine.advance(nodeEngine.startAt(nodeOrigin,at),102,1000)
    check(nodeExit.allowed==Match.YES)
    check(nodeExit.checks.map{it.location.phase}==listOf(Traversal.Phase.INITIAL_NODE,Traversal.Phase.WAY_ENTRY,Traversal.Phase.ARRIVAL_NODE))
    check(nodeExit.checks.first().permission!!.evidence.single().destination==false)
    graph.nodeTags.clear()

    // Permission cannot override any independently denied or unresolved driving constraint.
    for((extra,expected) in listOf(
        mapOf("access" to "no") to Match.NO,
        mapOf("access" to "private") to Match.NO,
        mapOf("oneway" to "-1") to Match.NO,
        mapOf("smoothness" to "impassable") to Match.NO,
        mapOf("maxwidth:physical" to "1") to Match.UNKNOWN,
        mapOf("motorcar:lanes" to "yes|no") to Match.UNKNOWN,
        mapOf("access:conditional" to "private @ wet") to Match.UNKNOWN)) {
        graph.tags[103]=mapOf("access" to "destination")+extra
        check(engine.advanceTo(connecting.next,103,0.5,1000).let{it.allowed==expected && it.next==null}) { "Grant bypassed constraint $extra" }
    }
    graph.tags[103]=mapOf("access" to "destination")
    graph.nodeTags[3]=mapOf("barrier" to "block","access" to "destination")
    check(engine.advance(originStep.next,102,1000).allowed==Match.NO)
    graph.nodeTags[3]=mapOf("access" to "private")
    check(engine.advance(originStep.next,102,1000).allowed==Match.NO)
    graph.nodeTags.clear()
    graph.coverage=Traversal.Coverage(false,emptySet(),listOf("Unresolved fixture coverage"))
    check(engine.advanceTo(connecting.next,103,0.5,1000).allowed==Match.UNKNOWN)
    graph.coverage=Traversal.Coverage(true,emptySet(),emptyList())
    val compiled=Compiler.compile(77,"no_straight_on",listOf(Compiler.Member('w',101,"from"),Compiler.Member('w',102,"via"),Compiler.Member('w',103,"to")),graph)
    check(compiled.resolved) { compiled.problems.toString() }
    graph.coverage=Traversal.Coverage(true,setOf(77),emptyList())
    val turnEngine=Traversal(graph,listOf(Traversal.Relation(77,mapOf("type" to "restriction","restriction" to "no_straight_on"),listOf(compiled))),policy,::context)
    val turn1=turnEngine.advance(turnEngine.startAt(origin,at),101,1000)
    val turn2=turnEngine.advance(turn1.next!!,102,1000)
    check(turnEngine.advanceTo(turn2.next!!,103,0.5,1000).let{it.allowed==Match.NO && it.next==null && 77L in it.blockedRelations})
    graph.coverage=Traversal.Coverage(true,emptySet(),emptyList())
    graph.tags[101]=emptyMap();graph.tags[103]=mapOf("access" to "private")
    val privateEngine=Traversal(graph,emptyList(),privatePolicy,::context)
    val private1=privateEngine.advance(privateEngine.startAt(origin,at),101,1000).next!!
    val private2=privateEngine.advance(private1,102,1000).next!!
    check(privateEngine.advanceTo(private2,103,0.5,1000).allowed==Match.YES)
    val expired1=privateEngine.advance(privateEngine.startAt(origin,at.plusSeconds(60)),101,1000).next!!
    val expired2=privateEngine.advance(expired1,102,1000).next!!
    check(privateEngine.advanceTo(expired2,103,0.5,1000).allowed==Match.NO)
    graph.revision="new-source"
    check(policy.enterWay(initial,101,access("destination"),at).permission==Match.UNKNOWN)
    check(policy.enterWay(ingress.next,104,access("yes"),at).permission==Match.UNKNOWN)
    check(engine.advanceTo(connecting.next,103,0.5,1000).allowed==Match.UNKNOWN)
    reject{engine.hasReachedBoundDestination(arrival.next)}
    graph.mutate=true;reject{Policy.scope(graph,setOf(101),listOf(purpose))}
    println("Endpoint permission/traversal: connected source scopes, exact origin/destination and partial arcs, physical-order egress/ingress, no through shortcut, private/customer/expired evidence, independent barriers/one-way/dimensions/turn/unknown guards and state ownership PASS; automatic scope/entrance discovery, interval permissions and whole-route integration remain pending")
}
