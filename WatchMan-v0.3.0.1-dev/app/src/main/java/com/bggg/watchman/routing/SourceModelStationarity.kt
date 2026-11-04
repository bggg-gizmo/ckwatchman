package com.bggg.watchman.routing

import java.time.Instant

/** Whole-journey time-shift certificate for one explicitly configured mapped-only estimate.
 * The sole factory constructs its model from the actual read-only compact graph lengths. An
 * arbitrary SourceRoadTiming, length callback, context callback or local edge observation cannot
 * earn this certificate. This is NOT a production-readiness, physical-control or traffic claim.
 *
 * Proof: SourceMappedRoadModel freezes the speed/control profiles and original metadata, and
 * produces only null-zone, empty-fact/quantity/calendar contexts. In RestrictionCondition every
 * non-schedule branch is a static parse/comparison/fact/three-valued combination; every schedule
 * returns UNKNOWN before consulting its instant/calendar when the zone is null. Thus all way,
 * node, turn and speed alternatives are time-independent, INCLUDING unresolved alternatives.
 * Mapped control estimates depend only on retained initial/position/incoming-portion state.
 * No grant boundary may occur anywhere in the inclusive journey horizon. Original distances,
 * delays, permissions, lawful-wait results and subsequent state changes are therefore unchanged
 * by a time shift within that horizon. Unknown conditions remain unknown and are not bypassed.
 *
 * The concrete CompactNoCamsNetwork must additionally use its own one frozen, time-independent
 * RoutingDeviceSnapshot. The search still keys incoming portion, turns and endpoint-purpose
 * state, and requires device-set subset plus non-increasing duration/distance for dominance.
 * Bounded producer exhaustion/cancellation/source failures remain unavailable/uncertain; the
 * certificate does not promise that every lookup succeeds or waive any resource guard.
 */
class SourceModelStationarity private constructor(
    private val graph: CompactRoadGraph,
    private val speedProfile: RoadSpeedEstimate.Profile,
    private val controlProfile: SourceMappedRoadModel.ControlProfile,
    private val validFrom: Instant,
    private val validUntil: Instant,
    private val endpointPolicy: EndpointAccessPolicy?,
    maximumSources: Int,
    maximumRetainedBytes: Long,
    private val cancelled: () -> Boolean
) {
    private val revision = graph.revision
    private val model = SourceMappedRoadModel(graph,
        { id -> checkNotNull(graph.lengthMm(id)) { "Missing original compact arc length" } },
        speedProfile, controlProfile, validFrom, validUntil, maximumSources,
        maximumRetainedBytes, cancelled, endpointPolicy)
    val timing: SourceRoadTiming = model.timing
    /** Retain THIS function object for both search and its Journey. Equal-looking closures are
     * not evidence that traversal uses the same immutable context producer.
     */
    val contextAt: (MotorcarGraphTraversal.Location, Instant) -> RestrictionCondition.Context = timing::contextAt

    private fun ready() {
        check(!cancelled()) { "Stationary mapped model cancelled" }
        check(graph.revision == revision) { "Stationary compact source changed" }
        model.cacheState() // worker/source/cancellation guards, never discard retained metadata
    }

    fun requireBindings(candidateGraph: CompactRoadGraph, candidateTiming: SourceRoadTiming,
        candidatePolicy: EndpointAccessPolicy?) {
        check(candidateGraph === graph && candidateTiming === timing && candidatePolicy === endpointPolicy) {
            "Stationarity belongs to another graph, timing model or endpoint permission policy"
        }
        timing.requireGraph(graph); timing.requireEndpointPolicy(endpointPolicy); ready()
    }

    /** Used only by the concrete immutable-device compact adapter. A refused certificate leaves
     * the kernel's exact-time labels intact; it is not refusal of otherwise legal route planning.
     */
    fun certificate(journey: NoCamsGraphSearch.Journey): NoCamsGraphSearch.Stationarity? {
        ready()
        if (journey.graph !== graph || journey.graphRevision != revision ||
            journey.contextAt !== contextAt || journey.endpointPolicy !== endpointPolicy ||
            journey.departure < validFrom || journey.latestArrival >= validUntil ||
            journey.latestArrival < journey.departure || journey.deviceRevision.isBlank() ||
            journey.origin.sourceRevision != revision || journey.destination.sourceRevision != revision)
            return null
        if (endpointPolicy != null) {
            if (journey.origin != endpointPolicy.origin || journey.destination != endpointPolicy.destination)
                return null
            endpointPolicy.requireGraph(graph)
            if (endpointPolicy.temporalBoundaries(journey.departure, journey.latestArrival).isNotEmpty())
                return null
        }
        ready()
        val evidence = "Whole-journey null-zone source-mapped estimate; original compact lengths $revision; " +
            "immutable speed=${speedProfile.revision}; mapped controls=${controlProfile.revision}; " +
            "horizon ${journey.departure}..${journey.latestArrival} inside $validFrom..$validUntil; " +
            "exact context/timing/graph/purpose identity; no inclusive grant boundary; " +
            "UNKNOWN preserved; frozen compact-adapter device snapshot required; resource guards unchanged"
        return NoCamsGraphSearch.Stationarity(journey, evidence.take(4096))
    }

    companion object {
        fun fromCompact(graph: CompactRoadGraph, speedProfile: RoadSpeedEstimate.Profile,
            controlProfile: SourceMappedRoadModel.ControlProfile, validFrom: Instant, validUntil: Instant,
            endpointPolicy: EndpointAccessPolicy? = null, maximumSources: Int = 8192,
            maximumRetainedBytes: Long = 4L * 1024 * 1024,
            cancelled: () -> Boolean = { false }): SourceModelStationarity =
            SourceModelStationarity(graph, speedProfile, controlProfile, validFrom, validUntil,
                endpointPolicy, maximumSources, maximumRetainedBytes, cancelled)
    }
}
