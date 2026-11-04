package com.bggg.watchman.routing

import com.bggg.watchman.storage.RoutingDeviceSnapshot
import java.time.Instant

/** Real SQLite adjacency/geometry/device adapter. Timing is an explicit evidence dependency:
 * this adapter does not manufacture ETA, interval permission, waiting or stationarity proof.
 * Opening development graph data does not certify route readiness or phone acceptance.
 */
class CompactNoCamsNetwork(
    override val graph: CompactRoadGraph, snapshot: RoutingDeviceSnapshot,
    private val timingModel: (MotorcarGraphTraversal.Arc, RoadArcPortion, NoCamsGraphSearch.Approach) -> NoCamsGraphSearch.Timing,
    private val maximumOutgoing: Int = 4096, private val maximumGeometryEntries: Int = 512
) : NoCamsGraphSearch.Network, GraphRouteMaterializer.Source {
    constructor(graph: CompactRoadGraph, snapshot: RoutingDeviceSnapshot, timing: SourceRoadTiming,
        maximumOutgoing: Int = 4096, maximumGeometryEntries: Int = 512,
        certifiedModel: SourceModelStationarity? = null) :
        this(graph, snapshot, timing.also { it.requireGraph(graph) }::timing, maximumOutgoing, maximumGeometryEntries) {
        check(certifiedModel == null || certifiedModel.timing === timing) { "Stationarity does not own this timing model" }
        this.certifiedTiming = timing
        this.certifiedModel = certifiedModel
    }
    // Assigned only by the source-model constructor. The arbitrary callback constructor cannot
    // install a certificate for some other timing function and weaken exact-time search.
    private var certifiedTiming: SourceRoadTiming? = null
    private var certifiedModel: SourceModelStationarity? = null
    private val graphRevision = graph.revision
    private val exposure = RoadDeviceExposureIndex(snapshot, graphRevision)
    override val deviceRevision: String get() = exposure.snapshot.revision
    private val geometry = LinkedHashMap<Long, RoadDeviceExposureIndex.Geometry>(64, 0.75f, true)
    init { require(maximumOutgoing in 1..65536 && maximumGeometryEntries in 1..4096) }

    private fun ready() { check(graph.revision == graphRevision) { "Compact routing source changed" } }
    override fun outgoing(node: Long): List<Long> {
        ready(); return graph.outgoing(node, maximumOutgoing).map { it.id }.also { ready() }
    }
    override fun lengthMm(arcId: Long): Long {
        ready(); return checkNotNull(graph.lengthMm(arcId)) { "Missing original arc length" }.also { ready() }
    }
    override fun stationarity(journey: NoCamsGraphSearch.Journey): NoCamsGraphSearch.Stationarity? {
        ready()
        val model = certifiedModel ?: return null
        model.requireBindings(graph, checkNotNull(certifiedTiming), journey.endpointPolicy)
        return model.certificate(journey).also { ready() }
    }
    override fun sourceGeometry(arcId: Long): GraphRouteMaterializer.Geometry {
        ready()
        val arc = checkNotNull(graph.arc(arcId)) { "Missing original arc" }
        val segment = checkNotNull(graph.segment((arcId - 1) / 2 + 1)) { "Missing original segment" }
        val forward = arc.direction == MotorcarTagPolicy.Direction.FORWARD
        require(arc.id == arcId && segment.wayId == arc.wayId &&
            arc.from == (if (forward) segment.from else segment.to) &&
            arc.to == (if (forward) segment.to else segment.from))
        return GraphRouteMaterializer.Geometry(graphRevision, arcId, arc.from, arc.to,
            if (forward) segment.fromPoint else segment.toPoint,
            if (forward) segment.toPoint else segment.fromPoint, segment.lengthMm).also { ready() }
    }
    override fun timing(arc: MotorcarGraphTraversal.Arc, portion: RoadArcPortion, approach: NoCamsGraphSearch.Approach): NoCamsGraphSearch.Timing {
        ready(); require(graph.arc(arc.id) == arc && portion.arcId == arc.id && portion.direction == arc.direction)
        return timingModel(arc, portion, approach).also { ready() }
    }
    override fun exposures(arc: MotorcarGraphTraversal.Arc, portion: RoadArcPortion,
                           departure: Instant, arrival: Instant): List<NoCamsGraphSearch.Exposure> {
        ready(); require(arrival > departure && graph.arc(arc.id) == arc)
        val source = geometry[arc.id] ?: run {
            val segment = checkNotNull(graph.segment((arc.id - 1) / 2 + 1)) { "Missing original segment geometry" }
            val forward = arc.direction == MotorcarTagPolicy.Direction.FORWARD
            require(segment.wayId == arc.wayId && arc.from == (if (forward) segment.from else segment.to) &&
                arc.to == (if (forward) segment.to else segment.from)) { "Original arc/source geometry mismatch" }
            RoadDeviceExposureIndex.Geometry(graphRevision, arc.id, arc.direction,
                if (forward) segment.fromPoint else segment.toPoint,
                if (forward) segment.toPoint else segment.fromPoint).also {
                geometry[arc.id] = it
                if (geometry.size > maximumGeometryEntries) geometry.entries.iterator().let { iterator -> iterator.next(); iterator.remove() }
            }
        }
        return exposure.exposures(source, portion).also { ready() }
    }
}
