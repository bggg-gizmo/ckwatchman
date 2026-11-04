package com.bggg.watchman.routing

import android.os.Looper
import com.bggg.watchman.data.SearchResult
import com.bggg.watchman.storage.AuthoritativeDeviceRepository
import com.bggg.watchman.storage.DeviceSearchRegion
import com.bggg.watchman.storage.RoutingDeviceSnapshot
import java.time.Instant

/** Worker-owned end-to-end graph planner using the APP'S current live/national/regional caches.
 * It does not fetch roads/devices, use a baseline-route corridor or substitute waypoint shaping.
 * Caller owns verified graph/model/endpoint evidence and lifecycle cancellation. Development
 * data cannot silently acquire production readiness. MainActivity/provider wiring and real-trip
 * acceptance remain independent gates, not consequences of constructing this planner.
 */
class FullRoadNoCamsPlanner(private val devices: AuthoritativeDeviceRepository) {
    enum class Purpose { PRODUCTION, DEVELOPMENT_EVIDENCE }
    data class Result(val search: NoCamsGraphSearch.Result,
        val materialized: GraphRouteMaterializer.Materialized?, val devices: RoutingDeviceSnapshot,
        val region: DeviceSearchRegion, val cacheSourceSummary: String)

    fun plan(graph: CompactRoadGraph, origin: RoadEndpointAnchor, destination: RoadEndpointAnchor,
        destinationResult: SearchResult, departure: Instant, bounds: NoCamsGraphSearch.Bounds,
        budget: NoCamsGraphSearch.Budget, timing: SourceRoadTiming,
        drivingSide: GraphRouteMaterializer.DrivingSide, purpose: Purpose,
        endpointPolicy: EndpointAccessPolicy? = null, maximumRowsPerLayer: Int = 50000,
        maximumDevices: Int = 50000, maximumMetadataCharacters: Long = 8000000,
        cancelled: () -> Boolean = { false }, nanoTime: () -> Long = System::nanoTime,
        wholeBudget: FullRoadPlanningBudget = FullRoadPlanningBudget(nanoTime(), nanoTime),
        certifiedModel: SourceModelStationarity? = null): Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Full-road planning is forbidden on main thread" }
        require(maximumRowsPerLayer in 1..500000 && maximumDevices in 1..500000 && maximumMetadataCharacters > 0)
        fun current() {
            check(!cancelled()) { "Full-road planning cancelled; result must not publish" }
            check(!wholeBudget.state().hardDeadlineReached) { "Full-road whole-operation deadline reached; result must not publish" }
        }
        fun interrupted() = cancelled() || wholeBudget.state().hardDeadlineReached
        current()
        val revision = graph.revision
        check(origin.sourceRevision == revision && destination.sourceRevision == revision)
        check(purpose == Purpose.DEVELOPMENT_EVIDENCE || graph.sourceRoutingReady) {
            "Development road pack has not passed production routing readiness"
        }
        timing.requireGraph(graph); timing.requireEndpointPolicy(endpointPolicy)
        certifiedModel?.requireBindings(graph, timing, endpointPolicy)
        fun boundArc(anchor: RoadEndpointAnchor) = checkNotNull(graph.arc(anchor.arcId)).also {
            check(it.id == anchor.arcId && it.from == anchor.fromNode && it.to == anchor.toNode) { "Endpoint source identity mismatch" }
        }
        val sourceOrigin = boundArc(origin)
        boundArc(destination)
        val original = checkNotNull(graph.segment((sourceOrigin.id - 1) / 2 + 1))
        val forward = sourceOrigin.direction == MotorcarTagPolicy.Direction.FORWARD
        val originPoint = RoadSnapGeometry.pointAt(if (forward) original.fromPoint else original.toPoint,
            if (forward) original.toPoint else original.fromPoint, origin.directedFraction)
        val region = DeviceSearchRegion.forSearch(revision, originPoint, bounds.maximumDistanceMm,
            budget.maximumGenerated, RouteAnalyzer.DEFAULT_CORRIDOR_METERS)
        current()
        val observed = devices.withinRegion(region, maximumRowsPerLayer, ::interrupted).requireAvailable()
        current()
        // Freeze the actual queried app-cache observations, including zero-weight irrelevant
        // records/provenance. Failed reads never become a successful empty camera universe.
        val snapshot = RoutingDeviceSnapshot.freeze(observed.devices, region.queryScope,
            maximumDevices, maximumMetadataCharacters)
        val network = CompactNoCamsNetwork(graph, snapshot, timing, certifiedModel = certifiedModel)
        val catalogue = graph.compileTurnRelations()
        current(); check(graph.revision == revision)
        // Cache reads, snapshot construction and turn compilation consume the same budget.
        // Leave one second INSIDE its ceiling for validating/materializing a complete incumbent.
        val reserveMillis = minOf(1000L, wholeBudget.hardDeadlineMillis - 1)
        val remaining = wholeBudget.remainingSearchMillis(budget.deadlineMillis, reserveMillis)
        check(remaining > 0) { "Full-road preparation exhausted the search/materialization budget" }
        val contexts = certifiedModel?.contextAt ?: timing::contextAt
        val result = NoCamsGraphSearch(network, catalogue, contexts).search(origin, destination,
            departure, bounds, budget.copy(deadlineMillis = remaining), endpointPolicy, cancelled,
            nanoTime, deadlineReached = { wholeBudget.state().remainingMillis <= reserveMillis })
        current()
        val route = if (result.route != null && result.stop in setOf(NoCamsGraphSearch.Stop.FOUND,
                NoCamsGraphSearch.Stop.DEADLINE, NoCamsGraphSearch.Stop.RESOURCE_LIMIT, NoCamsGraphSearch.Stop.EXHAUSTED))
            GraphRouteMaterializer.materialize(result, network, origin, destination, destinationResult,
                drivingSide, interrupted = ::interrupted)
            else null
        current(); check(graph.revision == revision)
        return Result(result, route, snapshot, region, observed.sourceSummary)
    }
}
