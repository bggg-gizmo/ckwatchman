package com.bggg.watchman.routing

import android.os.Looper
import com.bggg.watchman.data.SearchResult
import com.bggg.watchman.storage.AuthoritativeDeviceRepository
import java.time.Instant

/** Worker-owned production boundary for genuine full-road No Cams planning. It owns no network
 * client, guessed road catalogue, geometric entrance permission or fallback waypoint heuristic.
 * Production pack/model producers must be supplied by the application. Their absence remains
 * explicit unavailable data, not a successful No Cams route or a ready=false testing waiver.
 */
class FullRoadNoCamsProvider(
    devices: AuthoritativeDeviceRepository,
    private val packs: PackOpener,
    private val preparation: Preparation,
    private val nanoTime: () -> Long = System::nanoTime
) {
    /** The exact same monotonic epoch is used by this provider and the originating UI worker.
     * Departure is an explicit trip instant, not invented from the host clock by this provider.
     */
    class Request(val origin: SearchResult, val destination: SearchResult, val departure: Instant,
        val bounds: NoCamsGraphSearch.Bounds, val budget: NoCamsGraphSearch.Budget,
        val startedAtNanos: Long, val maximumRowsPerLayer: Int = 50_000,
        val maximumDevices: Int = 50_000, val maximumMetadataCharacters: Long = 8_000_000L) {
        init {
            for (endpoint in listOf(origin, destination)) {
                require(endpoint.displayName.isNotBlank() && endpoint.displayName.length <= 8192)
                require(endpoint.point.lat.isFinite() && endpoint.point.lat in -90.0..90.0 &&
                    endpoint.point.lon.isFinite() && endpoint.point.lon in -180.0..180.0)
            }
            require(maximumRowsPerLayer in 1..500_000 && maximumDevices in 1..500_000 && maximumMetadataCharacters > 0)
        }
    }

    /** Open on this calling worker from a trusted packaged catalogue/hash, never a hash supplied
     * by the external route request or copied from an untrusted pack's own manifest. Return null
     * when no production pack covers the request. The returned graph transfers ownership here.
     */
    fun interface PackOpener {
        fun open(request: Request, cancelled: () -> Boolean): CompactRoadGraph?
    }

    /** Resolve actual legal endpoint/entrance evidence, immutable source-local context/control
     * producers and driving profile. A nearest segment alone is NOT legal entrance evidence.
     * No automatic customer/private grants, global zone or implicit zero-control result.
     */
    fun interface Preparation {
        fun prepare(graph: CompactRoadGraph, request: Request, cancelled: () -> Boolean): Prepared?
    }

    class Prepared(val request: Request, val graphRevision: String,
        val origin: RoadEndpointAnchor, val destination: RoadEndpointAnchor,
        val timing: SourceRoadTiming, val drivingSide: GraphRouteMaterializer.DrivingSide,
        val entranceEvidence: String, val modelEvidence: String,
        val endpointPolicy: EndpointAccessPolicy? = null,
        val certifiedModel: SourceModelStationarity? = null) {
        init {
            require(graphRevision.isNotBlank() && graphRevision.length <= 512)
            require(origin.sourceRevision == graphRevision && destination.sourceRevision == graphRevision)
            require(entranceEvidence.isNotBlank() && entranceEvidence.length <= 4096)
            require(modelEvidence.isNotBlank() && modelEvidence.length <= 4096)
            check(certifiedModel == null || certifiedModel.timing === timing) { "Prepared certificate belongs to another timing model" }
        }
    }

    enum class Quality { OPTIMAL_IN_EVALUATED_SNAPSHOT, COMPLETE_BOUNDED_OR_UNCERTAIN }
    enum class Failure { SOURCE_UNAVAILABLE, DEADLINE, CANCELLED }
    sealed class Outcome {
        data class Planned(val result: FullRoadNoCamsPlanner.Result, val quality: Quality,
            val elapsedMillis: Long, val targetReached: Boolean, val hardDeadlineReached: Boolean,
            val entranceEvidence: String, val modelEvidence: String) : Outcome()
        data class Unavailable(val failure: Failure, val phase: String, val reason: String,
            val elapsedMillis: Long) : Outcome()
    }
    private class Interrupted : IllegalStateException()
    private val planner = FullRoadNoCamsPlanner(devices)

    fun plan(request: Request, cancelled: () -> Boolean = { false }): Outcome {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Full-road provider is forbidden on main thread" }
        val clock = FullRoadPlanningBudget(request.startedAtNanos, nanoTime)
        var phase = "open_verified_pack"
        fun interrupted() = cancelled() || clock.state().hardDeadlineReached
        fun current() { if (interrupted()) throw Interrupted() }
        fun unavailable(reason: String): Outcome.Unavailable {
            val state = clock.state()
            val failure = when {
                cancelled() -> Failure.CANCELLED
                state.hardDeadlineReached -> Failure.DEADLINE
                else -> Failure.SOURCE_UNAVAILABLE
            }
            return Outcome.Unavailable(failure, phase,
                reason.replace('\n', ' ').replace('\r', ' ').take(1024), state.elapsedMillis)
        }
        return try {
            current()
            val graph = packs.open(request, ::interrupted)
                ?: return unavailable("No trusted production road pack covers these endpoints")
            graph.use {
                current()
                check(graph.sourceRoutingReady) { "Development road pack has not passed production routing readiness" }
                val revision = graph.revision
                phase = "resolve_source_endpoints_and_model"
                val prepared = preparation.prepare(graph, request, ::interrupted)
                    ?: return unavailable("Source-bound legal endpoint/context/control model is unavailable")
                current()
                check(prepared.request === request) { "Prepared endpoints/model belong to another request" }
                check(prepared.graphRevision == revision && graph.revision == revision) { "Prepared graph source changed" }
                prepared.timing.requireGraph(graph)
                prepared.timing.requireEndpointPolicy(prepared.endpointPolicy)
                prepared.certifiedModel?.requireBindings(graph, prepared.timing, prepared.endpointPolicy)
                prepared.endpointPolicy?.requireGraph(graph)
                phase = "app_cache_search_materialization"
                val remaining = clock.remainingSearchMillis(request.budget.deadlineMillis)
                check(remaining > 0)
                val result = planner.plan(graph, prepared.origin, prepared.destination, request.destination,
                    request.departure, request.bounds, request.budget.copy(deadlineMillis = remaining),
                    prepared.timing, prepared.drivingSide, FullRoadNoCamsPlanner.Purpose.PRODUCTION,
                    prepared.endpointPolicy, request.maximumRowsPerLayer, request.maximumDevices,
                    request.maximumMetadataCharacters, cancelled = cancelled, nanoTime = nanoTime,
                    wholeBudget = clock, certifiedModel = prepared.certifiedModel)
                // External cancellation ALWAYS invalidates publication, including a complete path.
                if (cancelled()) return unavailable("Full-road request superseded or cancelled")
                check(graph.revision == revision)
                val materialized = result.materialized
                if (materialized == null || result.search.route == null)
                    return unavailable("No complete graph route: ${result.search.stop}; ${result.search.diagnostics.joinToString("; ")}")
                val state = clock.state()
                if (state.hardDeadlineReached) return unavailable("Whole-operation deadline reached before publication")
                val quality = if (result.search.stop == NoCamsGraphSearch.Stop.FOUND &&
                    result.search.optimalWithinEvaluatedSnapshot && !state.hardDeadlineReached)
                    Quality.OPTIMAL_IN_EVALUATED_SNAPSHOT else Quality.COMPLETE_BOUNDED_OR_UNCERTAIN
                Outcome.Planned(result, quality, state.elapsedMillis, state.targetReached,
                    state.hardDeadlineReached, prepared.entranceEvidence, prepared.modelEvidence)
            }
        } catch (_: Interrupted) {
            unavailable("Full-road request cancelled or whole-operation deadline reached")
        } catch (failure: Exception) {
            unavailable("${failure.javaClass.simpleName}: ${failure.message.orEmpty()}")
        }
    }
}
