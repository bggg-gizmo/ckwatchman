package com.bggg.watchman

import android.app.Instrumentation
import android.os.SystemClock
import com.bggg.watchman.routing.*
import com.bggg.watchman.routing.ConditionalRestriction.Match
import com.bggg.watchman.storage.AuthoritativeDeviceRepository
import java.io.File
import java.math.BigDecimal
import java.time.Instant

/** Actual compact SQLite certificate/model/search exercise. All route/control profiles and road
 * coordinates are explicitly synthetic; this is not production geography or device acceptance.
 * Does not alter application camera databases, graph fixtures, acquisition or existing data.
 */
object AndroidSourceModelStationarityCheck {
    private const val GOOD = "00dc14796c7ba9178ca306bbb9c8479af02e1fbe5464fbf83aa1748a8d7b8a4c"

    fun run(instrumentation: Instrumentation): String {
        val started = SystemClock.elapsedRealtime()
        val folder = File(instrumentation.targetContext.filesDir, "compact-graph-check/good")
        val at = Instant.parse("2026-10-08T12:00:00Z")
        val latest = at.plusSeconds(60)
        val speed = RoadSpeedEstimate.Profile("TEST-stationary-source-30",
            "Explicit synthetic arithmetic profile, not traffic observation", mapOf("residential" to BigDecimal(30)))
        val controls = SourceMappedRoadModel.ControlProfile("TEST-static-mapped-control-estimates",
            "Explicit synthetic source-mapped estimates, not observed signal phases",
            mapOf(SourceMappedRoadModel.MappedControl.SIGNAL to 12000L,
                SourceMappedRoadModel.MappedControl.STOP to 3000L,
                SourceMappedRoadModel.MappedControl.GIVE_WAY to 1000L))
        fun reject(action: () -> Unit) { check(runCatching(action).isFailure) }
        var generated = 0
        CompactRoadGraph.open(folder, GOOD).use { graph ->
            fun anchor(way: Long): RoadEndpointAnchor {
                val arc = checkNotNull(graph.arc(checkNotNull(graph.arc(way, 0, true))))
                return RoadEndpointAnchor(graph.revision, arc.id, arc.from, arc.to, 0.5)
            }
            val origin = anchor(101); val destination = anchor(103)
            val snapshot = AuthoritativeDeviceRepository.Snapshot(emptyList(), 0, 0)
                .freezeForRouting("Synthetic empty camera oracle for certificate test only")
            fun source(policy: EndpointAccessPolicy? = null, from: Instant = at.minusSeconds(1),
                until: Instant = latest.plusSeconds(1), maximumSources: Int = 8192,
                cancelled: () -> Boolean = { false }) = SourceModelStationarity.fromCompact(
                graph, speed, controls, from, until, policy, maximumSources = maximumSources, cancelled = cancelled)
            val model = source()
            model.requireBindings(graph, model.timing, null)
            fun journey(context: (MotorcarGraphTraversal.Location, Instant) -> RestrictionCondition.Context = model.contextAt,
                policy: EndpointAccessPolicy? = null, departure: Instant = at,
                end: Instant = latest, roads: CompactRoadGraph = graph) =
                NoCamsGraphSearch.Journey(roads, roads.revision, snapshot.revision, origin,
                    destination, departure, end, policy, context)
            val exact = journey()
            check(model.certificate(exact)?.journey === exact)
            check(model.contextAt === model.contextAt)
            // Identical returned values are not the exact producer identity.
            check(model.certificate(journey(context = { location, time -> model.contextAt(location, time) })) == null)
            check(model.certificate(journey(departure = at.minusSeconds(2))) == null)
            check(model.certificate(journey(end = latest.plusSeconds(1))) == null)
            check(model.certificate(journey(end = at.minusMillis(1))) == null)
            val endingAtArrival = source(until = latest)
            check(endingAtArrival.certificate(journey(context = endingAtArrival.contextAt)) == null)
            CompactRoadGraph.open(folder, GOOD).use { other ->
                check(model.certificate(journey(roads = other)) == null)
                reject { model.requireBindings(other, model.timing, null) }
            }
            val arbitrary = SourceMappedRoadModel(graph, { 1000L }, speed, controls,
                at.minusSeconds(1), latest.plusSeconds(1))
            reject { model.requireBindings(graph, arbitrary.timing, null) }

            fun policy(from: Instant?, until: Instant?) = EndpointAccessPolicy(graph, origin, destination,
                EndpointAccessPolicy.scope(graph, setOf(101), listOf(EndpointAccessPolicy.Grant(
                    MotorcarTagPolicy.AccessClass.DESTINATION, EndpointAccessPolicy.Basis.ENDPOINT_PURPOSE,
                    "Synthetic declared endpoint purpose, not actual public-address permission", from, until))), null)
            val timeless = policy(null, null)
            val granted = source(timeless)
            check(granted.certificate(journey(granted.contextAt, timeless)) != null)
            reject { granted.requireBindings(graph, granted.timing, policy(null, null)) }
            for ((from, until) in listOf(at to null, at.plusSeconds(5) to null,
                null to latest, null to at.plusSeconds(5), latest to null)) {
                val bounded = policy(from, until)
                val conditional = source(bounded)
                check(conditional.certificate(journey(conditional.contextAt, bounded)) == null) {
                    "Grant boundary received time-shift certificate: $from..$until"
                }
            }
            val beyond = policy(at.minusSeconds(1), latest.plusSeconds(1))
            val entire = source(beyond)
            check(entire.certificate(journey(entire.contextAt, beyond)) != null)
            check(model.certificate(journey(policy = timeless)) == null)

            // Independent grammar witnesses, not two sample instants masquerading as proof.
            // The documented certificate derives from the evaluator's null-zone control flow.
            val instants = listOf(at.minusSeconds(3600), at, at.plusSeconds(3600),
                Instant.parse("2027-01-01T00:00:00Z"))
            for (raw in listOf("Mo-Fr 09:00-17:00", "24/7", "PH", "SH off", "sunrise-sunset",
                "Jan-Dec", "wet", "weight > 3.5", "(wet AND Mo 12:00-13:00)",
                "Mo 12:00-13:00 \"local permit\"", "(bad", "no such grammar")) {
                val results = instants.map { time -> RestrictionCondition.evaluate(raw, RestrictionCondition.Context(time, null)) }
                check(results.map { it.match }.distinct().size == 1)
                check(results.first().match == Match.UNKNOWN) { "Unknown null-zone condition promoted: $raw" }
            }

            fun search(candidate: SourceModelStationarity): NoCamsGraphSearch.Result {
                candidate.requireBindings(graph, candidate.timing, null)
                val network = CompactNoCamsNetwork(graph, snapshot, candidate.timing,
                    certifiedModel = candidate)
                return NoCamsGraphSearch(network, graph.compileTurnRelations(), candidate.contextAt)
                    .search(origin, destination, at, NoCamsGraphSearch.Bounds(1000000L, 60000L),
                        NoCamsGraphSearch.Budget(maximumExpanded = 2000, maximumGenerated = 5000, deadlineMillis = 30000))
            }
            val planned = search(model)
            check(planned.stop == NoCamsGraphSearch.Stop.FOUND && planned.optimalWithinEvaluatedSnapshot) {
                "Actual stationary SQLite search failed: ${planned.stop} ${planned.diagnostics} ${planned.rejections}"
            }
            check(planned.route?.cost?.exposure == 0L && planned.route!!.cost.durationMillis > 0)
            val expectedTravel = planned.route!!.segments.sumOf { segment ->
                BigDecimal.valueOf(segment.distanceMm).multiply(BigDecimal("3.6"))
                    .divide(BigDecimal(30), 0, java.math.RoundingMode.CEILING).longValueExact().coerceAtLeast(1L)
            }
            check(planned.route!!.cost.durationMillis == expectedTravel) {
                "Original mm/explicit decimal speed arithmetic changed under stationarity"
            }
            generated = planned.generated
            // A certificate never waives source-retention refusal or promotes failed coverage.
            val exhausted = search(source(maximumSources = 1))
            check(exhausted.route == null && !exhausted.optimalWithinEvaluatedSnapshot &&
                (exhausted.rejections["edge_data_unavailable"] ?: 0) +
                (exhausted.rejections["traversal_UNKNOWN"] ?: 0) > 0) {
                "Retained-context exhaustion lost its unknown result: $exhausted"
            }
            reject { source(cancelled = { true }).certificate(exact) }
            var foreignFailed = false
            val foreign = Thread { foreignFailed = runCatching { model.certificate(exact) }.isFailure }
            foreign.start(); foreign.join(); check(foreignFailed)
            graph.close()
            reject { model.certificate(exact) }
        }
        return "SOURCE_MODEL_STATIONARITY PASS elapsed_ms=${SystemClock.elapsedRealtime() - started} generated=$generated " +
            "actual_sqlite=true synthetic_only=true exact_identity_horizon_grants=true unknown_preserved=true " +
            "source_retention_worker_closed_guards=true production_acceptance=false"
    }
}
