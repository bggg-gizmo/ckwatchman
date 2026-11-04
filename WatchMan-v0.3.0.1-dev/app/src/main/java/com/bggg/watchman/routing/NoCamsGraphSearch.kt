package com.bggg.watchman.routing

import com.bggg.watchman.routing.ConditionalRestriction.Match
import java.time.Instant
import java.util.PriorityQueue

/** Whole-path, exposure-first search. No waypoint shaping or highway/turn-count preference.
 * Network callbacks must use one immutable local snapshot, never synchronous network requests.
 * This kernel does not invent a speed model, device weights, interval permission or road links.
 */
class NoCamsGraphSearch(private val network:Network,
    private val relations:List<MotorcarGraphTraversal.Relation>,
    private val contextAt:(MotorcarGraphTraversal.Location,Instant)->RestrictionCondition.Context) {
    data class Exposure(val deviceId:String,val weight:Long) {
        init { require(deviceId.isNotBlank() && deviceId.length<=512 && weight>=0) }
    }
    /** Travel duration excludes the departure delay. intervalAllowed covers the actual travel
     * interval AFTER that delay. A positive delay additionally needs evidenced lawful waiting
     * over the entire node interval; a guessed signal penalty is not permission to wait.
     */
    data class Timing(val durationMillis:Long,val intervalAllowed:Match,val diagnostics:List<String> = emptyList(),
        val departureDelayMillis:Long=0,val waitAllowed:Match=Match.UNKNOWN,val waitEvidence:String?=null) {
        init {
            require(durationMillis>0 && departureDelayMillis>=0)
            require(waitEvidence==null || (waitEvidence.isNotBlank() && waitEvidence.length<=4096))
            require(departureDelayMillis==0L || waitAllowed!=Match.YES || waitEvidence!=null) {
                "Lawful control waiting requires explicit interval evidence"
            }
        }
    }
    /** Incoming portion is actual travelled geometry; an initial endpoint has no invented approach. */
    data class Approach(val state:MotorcarGraphTraversal.State,val incoming:RoadArcPortion?)
    class Journey internal constructor(val graph:MotorcarGraphTraversal.Graph,val graphRevision:String,
        val deviceRevision:String,val origin:RoadEndpointAnchor,val destination:RoadEndpointAnchor,
        val departure:Instant,val latestArrival:Instant,val endpointPolicy:EndpointAccessPolicy?,
        val contextAt:(MotorcarGraphTraversal.Location,Instant)->RestrictionCondition.Context)
    /** Optional evidence that ALL feasible continuations are time-shift invariant across the
     * entire horizon: timing, context/conditions, endpoint grants, devices and waiting semantics.
     * A local edge or clock-window observation is insufficient. No proof means exact-time labels.
     * The concrete adapter must establish this from its immutable model, not assume it.
     */
    class Stationarity(val journey:Journey,val evidence:String) {
        init { require(evidence.isNotBlank() && evidence.length<=4096) }
    }
    interface Network {
        val graph:MotorcarGraphTraversal.Graph
        val deviceRevision:String
        /** All original outgoing directed candidates, not a road-class-filtered subset. */
        fun outgoing(node:Long):List<Long>
        fun lengthMm(arcId:Long):Long
        /** Must account for travel/controls and establish permission over the entire interval. */
        fun timing(arc:MotorcarGraphTraversal.Arc,portion:RoadArcPortion,approach:Approach):Timing
        fun stationarity(journey:Journey):Stationarity? = null
        /** All affected devices on this exact portion, including departure/arrival exposure.
         * Stable logical IDs prevent duplicate source records/adjacent edges inflating the score.
         */
        fun exposures(arc:MotorcarGraphTraversal.Arc,portion:RoadArcPortion,
            departure:Instant,arrival:Instant):List<Exposure>
    }
    data class Bounds(val maximumDistanceMm:Long,val maximumDurationMillis:Long) {
        init { require(maximumDistanceMm>=0 && maximumDurationMillis>0) }
    }
    data class Budget(val maximumExpanded:Int=50000,val maximumGenerated:Int=100000,
        val maximumQueued:Int=20000,val maximumQueuedDeviceReferences:Long=500000,
        val maximumDevices:Int=50000,val maximumExposureRecordsPerPortion:Int=10000,
        val maximumRetainedLabels:Int=50000,val maximumRetainedDeviceReferences:Long=500000,
        val maximumOutgoing:Int=65536,val deadlineMillis:Long=20000) {
        init { require(maximumExpanded>0 && maximumGenerated>0 && maximumQueued>0 &&
            maximumQueuedDeviceReferences>0 && maximumDevices>0 && maximumExposureRecordsPerPortion>0 &&
            maximumRetainedLabels>0 && maximumRetainedDeviceReferences>0 && maximumOutgoing>0 && deadlineMillis>0) }
    }
    enum class Stop { FOUND, EXHAUSTED, RESOURCE_LIMIT, DEADLINE, CANCELLED, SOURCE_CHANGED, INVALID_DATA }
    data class Cost(val exposure:Long,val durationMillis:Long,val distanceMm:Long):Comparable<Cost> {
        override fun compareTo(other:Cost):Int = compareValuesBy(this,other,Cost::exposure,Cost::durationMillis,Cost::distanceMm)
    }
    data class Segment(val portion:RoadArcPortion,val departure:Instant,val arrival:Instant,
        val distanceMm:Long,val checks:List<MotorcarGraphTraversal.Check>,
        val travelDeparture:Instant=departure,val waitEvidence:String?=null)
    data class Route(val segments:List<Segment>,val cost:Cost,val devices:Map<String,Long>)
    data class Result(val route:Route?,val stop:Stop,val optimalWithinEvaluatedSnapshot:Boolean,
        val graphRevision:String,val deviceRevision:String,val expanded:Int,val generated:Int,
        val rejections:Map<String,Int>,val diagnostics:List<String>)
    private class Link(val previous:Link?,val segment:Segment)
    private class Label(val state:MotorcarGraphTraversal.State,val cost:Cost,val devices:Map<String,Long>,
        val path:Link?,val serial:Long) { var active=true }
    private data class ContinuationKey(val position:MotorcarGraphTraversal.Position,
        val turns:TurnRestrictionMachine.State,val permission:EndpointAccessPolicy.State?,
        val initial:Boolean,val incoming:RoadArcPortion?,val instant:Instant?)
    private class ResourceLimit:RuntimeException()
    private class InvalidData(message:String):RuntimeException(message)

    fun search(origin:RoadEndpointAnchor,destination:RoadEndpointAnchor,departure:Instant,bounds:Bounds,
        budget:Budget=Budget(),endpointPolicy:EndpointAccessPolicy?=null,
        cancelled:()->Boolean={false},nanoTime:()->Long=System::nanoTime,
        deadlineReached:()->Boolean={false}):Result {
        val started=nanoTime()
        val graph=network.graph
        val graphRevision=graph.revision
        val deviceRevision=network.deviceRevision
        require(graphRevision.isNotBlank() && deviceRevision.isNotBlank())
        require(endpointPolicy==null || (endpointPolicy.origin==origin && endpointPolicy.destination==destination))
        val engine=MotorcarGraphTraversal(graph,relations,endpointPolicy,contextAt)
        val target=engine.positionOf(destination)
        val initial=engine.startAt(origin,departure)
        require(initial.position!=target) { "Coincident endpoints require separate stationary-arrival evaluation" }
        val queue=PriorityQueue<Label>(compareBy<Label>{it.cost}.thenBy{it.serial})
        val initialLabel=Label(initial,Cost(0,0,0),emptyMap(),null,0)
        queue.add(initialLabel)
        var serial=0L;var expanded=0;var generated=1;var queuedReferences=0L
        var incumbent:Label?=null;var uncertain=false
        val weights=HashMap<String,Long>()
        val rejected=linkedMapOf<String,Int>();val diagnostics=ArrayList<String>()
        fun reject(kind:String,detail:String?=null,unknown:Boolean=false) {
            rejected[kind]=(rejected[kind] ?: 0)+1
            uncertain=uncertain || unknown
            if(detail!=null && diagnostics.size<24)diagnostics+=detail
        }
        fun stable()=runCatching{network.graph===graph && graph.revision==graphRevision && network.deviceRevision==deviceRevision}.getOrDefault(false)
        fun result(stop:Stop,chosen:Label?=incumbent):Result {
            val effective=if(stop!=Stop.INVALID_DATA && !stable())Stop.SOURCE_CHANGED else stop
            val valid=if(effective==Stop.SOURCE_CHANGED || effective==Stop.INVALID_DATA)null else chosen
            val route=valid?.let {
                val segments=ArrayList<Segment>();var cursor=it.path
                while(cursor!=null){segments+=cursor.segment;cursor=cursor.previous}
                segments.reverse();Route(segments,it.cost,it.devices.toMap())
            }
            if(effective!=Stop.INVALID_DATA && !stable())return Result(null,Stop.SOURCE_CHANGED,false,
                graphRevision,deviceRevision,expanded,generated,rejected.toMap(),diagnostics.toList())
            return Result(route,effective,effective==Stop.FOUND && !uncertain,graphRevision,deviceRevision,
                expanded,generated,rejected.toMap(),diagnostics.toList())
        }
        val journey=Journey(graph,graphRevision,deviceRevision,origin,destination,departure,
            departure.plusMillis(bounds.maximumDurationMillis),endpointPolicy,contextAt)
        val proof=try {
            network.stationarity(journey).also { check(it==null || it.journey===journey) { "Stationarity evidence belongs to another journey/context/grant horizon" } }
        } catch(failure:Exception) {
            reject("stationarity_unavailable",failure.javaClass.simpleName)
            null // An unavailable/mismatched optimisation certificate cannot weaken exact time.
        }
        fun key(label:Label)=ContinuationKey(label.state.position,label.state.turns,label.state.permission,
            label.state.initial,label.path?.segment?.portion,if(proof==null)label.state.instant else null)
        val retained=HashMap<ContinuationKey,MutableList<Label>>()
        retained[key(initialLabel)]=arrayListOf(initialLabel)
        var retainedLabels=1;var retainedReferences=0L
        fun dominates(a:Label,b:Label)=a.cost.durationMillis<=b.cost.durationMillis &&
            a.cost.distanceMm<=b.cost.distanceMm && b.devices.keys.containsAll(a.devices.keys)
        fun retain(label:Label):Boolean {
            val alternatives=retained.getOrPut(key(label)){ArrayList()}
            if(alternatives.any{dominates(it,label)}) { reject("dominated_label");return false }
            val iterator=alternatives.iterator()
            while(iterator.hasNext()) {
                val previous=iterator.next()
                if(dominates(label,previous)) {
                    previous.active=false;iterator.remove();retainedLabels--;retainedReferences-=previous.devices.size
                }
            }
            if(retainedLabels>=budget.maximumRetainedLabels ||
                retainedReferences+label.devices.size>budget.maximumRetainedDeviceReferences)throw ResourceLimit()
            alternatives+=label;retainedLabels++;retainedReferences+=label.devices.size
            return true
        }
        try {
            while(queue.isNotEmpty()) {
                if(!stable())return result(Stop.SOURCE_CHANGED)
                if(cancelled())return result(Stop.CANCELLED)
                if(deadlineReached() || (nanoTime()-started)/1_000_000>=budget.deadlineMillis)return result(Stop.DEADLINE)
                val current=queue.remove();queuedReferences-=current.devices.size
                if(!current.active)continue
                if(current.state.position==target)return result(Stop.FOUND,current)
                if(expanded>=budget.maximumExpanded)throw ResourceLimit()
                expanded++
                val candidates=try {
                    when(val position=current.state.position) {
                        is MotorcarGraphTraversal.Position.AtNode -> network.outgoing(position.id)
                        is MotorcarGraphTraversal.Position.Interior -> listOf(position.arcId)
                    }.also { if(it.size>budget.maximumOutgoing)throw ResourceLimit() }
                } catch(limit:ResourceLimit){throw limit}
                catch(failure:Exception){reject("adjacency_unavailable",failure.javaClass.simpleName,true);continue}
                for(id in candidates.distinct().sorted()) {
                    if(cancelled())return result(Stop.CANCELLED)
                    if(deadlineReached() || (nanoTime()-started)/1_000_000>=budget.deadlineMillis)return result(Stop.DEADLINE)
                    if(!stable())return result(Stop.SOURCE_CHANGED)
                    try {
                        val arc=checkNotNull(graph.arc(id)) { "Missing candidate arc$id" }
                        check(arc.id==id)
                        val from=(current.state.position as? MotorcarGraphTraversal.Position.Interior)?.fraction ?: 0.0
                        val targetFraction=(target as? MotorcarGraphTraversal.Position.Interior)
                            ?.takeIf{it.arcId==id && it.fraction>from}?.fraction
                        val ends=if(targetFraction==null)listOf(1.0) else listOf(targetFraction,1.0)
                        for(end in ends) {
                            if(cancelled())return result(Stop.CANCELLED)
                            if(deadlineReached() || (nanoTime()-started)/1_000_000>=budget.deadlineMillis)return result(Stop.DEADLINE)
                            val portion=RoadArcPortion(id,from,end,arc.direction)
                            val distance=portion.distanceMm(network.lengthMm(id))
                            val nextDistance=Math.addExact(current.cost.distanceMm,distance)
                            if(nextDistance>bounds.maximumDistanceMm){reject("distance_bound");continue}
                            val timing=network.timing(arc,portion,Approach(current.state,current.path?.segment?.portion))
                            val nextDuration=Math.addExact(current.cost.durationMillis,
                                Math.addExact(timing.departureDelayMillis,timing.durationMillis))
                            if(nextDuration>bounds.maximumDurationMillis){reject("duration_bound");continue}
                            if(timing.intervalAllowed!=Match.YES) {
                                reject("interval_${timing.intervalAllowed}",timing.diagnostics.joinToString("; "),timing.intervalAllowed==Match.UNKNOWN)
                                continue
                            }
                            val waiting=if(timing.departureDelayMillis==0L)null else
                                engine.waitAtNode(current.state,arc.direction,timing.departureDelayMillis,
                                    timing.waitAllowed,timing.waitEvidence)
                            if(waiting!=null && waiting.allowed!=Match.YES) {
                                reject("wait_${waiting.allowed}",waiting.diagnostics.joinToString("; "),waiting.allowed==Match.UNKNOWN)
                                continue
                            }
                            val departureState=waiting?.next ?: current.state
                            val step=engine.advanceTo(departureState,id,end,timing.durationMillis)
                            if(step.allowed!=Match.YES) {
                                reject("traversal_${step.allowed}","arc$id: ${step.diagnostics.joinToString("; ")}",step.allowed==Match.UNKNOWN)
                                continue
                            }
                            val next=checkNotNull(step.next)
                            val records=network.exposures(arc,portion,current.state.instant,next.instant)
                            if(records.size>budget.maximumExposureRecordsPerPortion)throw ResourceLimit()
                            val devices=HashMap(current.devices);var exposure=current.cost.exposure
                            for(record in records) {
                                val previous=weights.putIfAbsent(record.deviceId,record.weight)
                                if(previous!=null && previous!=record.weight)throw InvalidData("Conflicting weights for logical device ${record.deviceId}")
                                if(weights.size>budget.maximumDevices)throw ResourceLimit()
                                if(devices.putIfAbsent(record.deviceId,record.weight)==null)exposure=Math.addExact(exposure,record.weight)
                            }
                            if(!stable())return result(Stop.SOURCE_CHANGED)
                            val label=Label(next,Cost(exposure,nextDuration,nextDistance),devices,
                                Link(current.path,Segment(portion,current.state.instant,next.instant,distance,
                                    waiting?.checks.orEmpty()+step.checks,departureState.instant,
                                    timing.waitEvidence.takeIf{timing.departureDelayMillis>0})),++serial)
                            if(next.position==target && (incumbent==null || label.cost<incumbent!!.cost))incumbent=label
                            // Once a full path exists, nonnegative exposure/time cannot improve it
                            // from a prefix already no better. No candidate-count/turn-count cap.
                            if(incumbent!=null && next.position!=target && label.cost>=incumbent!!.cost)continue
                            if(!retain(label))continue
                            if(generated>=budget.maximumGenerated || queue.size>=budget.maximumQueued ||
                                queuedReferences+devices.size>budget.maximumQueuedDeviceReferences)throw ResourceLimit()
                            queue.add(label);generated++;queuedReferences+=devices.size
                        }
                    } catch(limit:ResourceLimit){throw limit}
                    catch(invalid:InvalidData){throw invalid}
                    catch(denial:SourceRoadTiming.Unavailable){
                        val reason=denial.message.orEmpty().replace('\n',' ').replace('\r',' ').take(512)
                        if(denial.permission==Match.NO)
                            reject("source_permission_NO","arc$id: $reason")
                        else reject("edge_data_unavailable","arc$id: ${denial.javaClass.simpleName}: $reason",true)
                    }
                    catch(failure:Exception){
                        val reason=failure.message.orEmpty().replace('\n',' ').replace('\r',' ').take(512)
                        reject("edge_data_unavailable","arc$id: ${failure.javaClass.simpleName}: $reason",true)
                    }
                }
            }
            return if(stable())result(Stop.EXHAUSTED) else result(Stop.SOURCE_CHANGED)
        } catch(limit:ResourceLimit){return result(Stop.RESOURCE_LIMIT)}
        catch(invalid:InvalidData){diagnostics+=invalid.message.orEmpty();return result(Stop.INVALID_DATA)}
    }
}
