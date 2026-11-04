package com.bggg.watchman

import android.app.Instrumentation
import android.os.SystemClock
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.SearchResult
import com.bggg.watchman.routing.*
import com.bggg.watchman.routing.ConditionalRestriction.Match
import com.bggg.watchman.storage.*
import com.bggg.watchman.util.Geo
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.PriorityQueue

/** Runs the actual full planner and current app-cache layers. Road endpoints/permission/profile/
 * context/control are audited synthetic fixture dependencies, not public-address acceptance.
 */
object AndroidFullRoadPlannerCheck {
    private const val PERMISSION = "71f15f90f39c50b3dfcde4db572db5e6f21c788fc480ca4d4af7a7e461279296"

    private data class OracleCost(val exposure: Long, val millis: Long, val mm: Long) : Comparable<OracleCost> {
        override fun compareTo(other: OracleCost): Int = compareValuesBy(this, other,
            OracleCost::exposure, OracleCost::millis, OracleCost::mm)
    }
    private data class OracleLabel(val node: Long, val restrictionPrefix: Int,
        val portions: List<RoadArcPortion>, val devices: Map<String, Long>, val cost: OracleCost)
    private data class OracleKey(val node: Long, val restrictionPrefix: Int, val deviceIds: Set<String>)
    private data class OracleResult(val cost: OracleCost, val paths: List<OracleLabel>, val expanded: Int)

    /** Independent oracle for this EXACT eight-way source fixture, not a second invocation of
     * the router, traversal engine, timing producer, exposure index or turn compiler. The literal
     * source topology/access/restriction must match before these fixture-specific rules apply.
     * Positive mm/speed costs and Pareto retention on (node, directed restriction prefix, unique
     * device set) exhaust all useful continuations, including legal history-clearing loops.
     */
    private fun fixtureOracle(graph: CompactRoadGraph, devices: RoutingDeviceSnapshot,
        origin: RoadEndpointAnchor, destination: RoadEndpointAnchor): OracleResult {
        val topology = linkedMapOf(101L to (1L to 2L), 102L to (2L to 3L), 103L to (3L to 4L),
            104L to (2L to 5L), 105L to (5L to 3L), 106L to (3L to 6L),
            107L to (4L to 7L), 108L to (3L to 8L))
        check(graph.counts["ways"] == 8L && graph.counts["nodes"] == 8L &&
            graph.counts["segments"] == 8L && graph.counts["relations"] == 1L)
        val arcs = topology.flatMap { (way, ends) ->
            check(checkNotNull(graph.way(way)).nodes == listOf(ends.first, ends.second))
            val tags = mapOf("highway" to "service", "name" to "Permission fixture $way") + when (way) {
                101L -> mapOf("access" to "destination", "oneway" to "yes")
                103L -> mapOf("access" to "private")
                108L -> mapOf("access" to "customers")
                else -> emptyMap()
            }
            listOf(true, false).map { forward ->
                checkNotNull(graph.arc(checkNotNull(graph.arc(way, 0, forward)))).also { arc ->
                    check(arc.tags == tags && arc.unresolved.isEmpty())
                    check(arc.from == if (forward) ends.first else ends.second)
                    check(arc.to == if (forward) ends.second else ends.first)
                }
            }
        }
        for (node in 1L..8L) {
            val original = checkNotNull(graph.node(node))
            check(original.tags == if (node == 6L) mapOf("barrier" to "block") else emptyMap<String, String>())
            check(original.unresolved.isEmpty())
            check(checkNotNull(graph.coordinate(node)).let {
                it.lonE7 == -770000000 + node.toInt() * 1000 && it.latE7 == 380000000 + node.toInt() * 1000
            })
        }
        val relation = checkNotNull(graph.relation(77))
        check(relation.tags == mapOf("type" to "restriction", "restriction" to "no_straight_on"))
        check(relation.members.map { Triple(it.type, it.ref, it.role) } ==
            listOf(Triple("w", 101L, "from"), Triple("w", 102L, "via"), Triple("w", 103L, "to")))
        fun arc(way: Long, forward: Boolean = true) = arcs.single {
            it.wayId == way && (it.direction == MotorcarTagPolicy.Direction.FORWARD) == forward
        }
        val departure = arc(101); val arrival = arc(103)
        check(origin.arcId == departure.id && destination.arcId == arrival.id &&
            origin.directedFraction == .5 && destination.directedFraction == .5)
        fun leg(previous: OracleLabel?, road: MotorcarGraphTraversal.Arc, from: Double, to: Double,
            prefix: Int): OracleLabel {
            val sourceLength = checkNotNull(graph.lengthMm(road.id))
            // Literal cumulative nearest-mm rounding in ORIGINAL source ordering; do not
            // invoke RoadArcPortion.distanceMm or the production speed/timing model.
            fun cumulative(fraction: Double): Long = BigDecimal.valueOf(sourceLength)
                .multiply(if (road.direction == MotorcarTagPolicy.Direction.FORWARD)
                    BigDecimal.valueOf(fraction) else BigDecimal.ONE.subtract(BigDecimal.valueOf(fraction)))
                .setScale(0, RoundingMode.HALF_UP).longValueExact()
            val mm = if (road.direction == MotorcarTagPolicy.Direction.FORWARD)
                cumulative(to) - cumulative(from) else cumulative(from) - cumulative(to)
            val millis = BigDecimal.valueOf(mm).multiply(BigDecimal("3.6"))
                .divide(BigDecimal(30), 0, RoundingMode.CEILING).longValueExact().coerceAtLeast(1L)
            fun point(fraction: Double): GeoPoint {
                val a = checkNotNull(graph.coordinate(road.from)); val b = checkNotNull(graph.coordinate(road.to))
                return GeoPoint((a.latE7 + (b.latE7 - a.latE7) * fraction) / 1e7,
                    (a.lonE7 + (b.lonE7 - a.lonE7) * fraction) / 1e7)
            }
            val start = point(from); val end = point(to)
            val encountered = LinkedHashMap(previous?.devices.orEmpty())
            for (entry in devices.entries) if (entry.weight > 0 &&
                Geo.pointToSegmentMeters(entry.device.point, start, end) <= devices.corridorMeters)
                encountered[entry.logicalId] = entry.weight
            return OracleLabel(road.to, prefix, previous?.portions.orEmpty() +
                RoadArcPortion(road.id, from, to, road.direction), encountered,
                OracleCost(encountered.values.sum(), (previous?.cost?.millis ?: 0L) + millis,
                    (previous?.cost?.mm ?: 0L) + mm))
        }
        val queue = PriorityQueue<OracleLabel>(compareBy { it.cost })
        queue += leg(null, departure, .5, 1.0, 1)
        val retained = HashMap<OracleKey, MutableList<OracleLabel>>()
        var best: OracleCost? = null
        val winners = ArrayList<OracleLabel>()
        var expanded = 0
        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (best != null && current.cost >= best!!) continue
            check(++expanded <= 20_000) { "Independent tiny fixture oracle exceeded its audited state bound" }
            val key = OracleKey(current.node, current.restrictionPrefix, current.devices.keys.toSet())
            val peers = retained.getOrPut(key) { ArrayList() }
            if (peers.any { it.cost.millis <= current.cost.millis && it.cost.mm <= current.cost.mm &&
                (it.cost.millis < current.cost.millis || it.cost.mm < current.cost.mm || it.portions == current.portions) }) continue
            peers.removeAll { current.cost.millis <= it.cost.millis && current.cost.mm <= it.cost.mm &&
                (current.cost.millis < it.cost.millis || current.cost.mm < it.cost.mm) }
            peers += current
            // Origin101 is one-way endpoint-only and cannot be reentered after egress.
            //106 reaches physical block6,108 needs absent customer permission.107 is
            // reachable only after full private103 ingress, whose unrelated exit is denied.
            // Hence102/104/105 are ALL pre-arrival public continuations, in both directions.
            for (road in arcs.filter { it.from == current.node && it.wayId in setOf(102L, 104L, 105L) }) {
                val prefix = if (current.restrictionPrefix == 1 && road.id == arc(102).id) 2 else 0
                val next = leg(current, road, 0.0, 1.0, prefix)
                if (next.cost.mm <= 1_000_000L && next.cost.millis < 60_000L) queue += next
            }
            if (current.node == 3L && current.restrictionPrefix != 2) {
                val complete = leg(current, arrival, 0.0, .5, 0)
                // Private grant is inclusive at departure, exclusive at60s arrival horizon.
                if (complete.cost.mm <= 1_000_000L && complete.cost.millis < 60_000L) {
                    if (best == null || complete.cost < best!!) { best = complete.cost; winners.clear() }
                    if (complete.cost == best) winners += complete
                }
            }
        }
        return OracleResult(checkNotNull(best), winners.toList(), expanded)
    }
    fun run(instrumentation: Instrumentation): String {
        val started = SystemClock.elapsedRealtime()
        val context = instrumentation.targetContext
        val live = CameraDatabase(context)
        val national = NationalCameraCache(context)
        val regional = RegionalCameraCache(context)
        val repository = AuthoritativeDeviceRepository(live, national, regional)
        val planner = FullRoadNoCamsPlanner(repository)
        val token = "planner-" + UUID.randomUUID().toString().take(20)
        val liveSource = "test-$token"
        var observed = 0
        var encountered = 0
        var duration = 0L
        try {
            CompactRoadGraph.open(File(context.filesDir, "compact-graph-check/permission"), PERMISSION).use { graph ->
                val at = Instant.parse("2026-09-28T12:00:00Z")
                fun anchor(way: Long) = checkNotNull(graph.arc(checkNotNull(graph.arc(way, 0, true)))).let {
                    RoadEndpointAnchor(graph.revision, it.id, it.from, it.to, .5)
                }
                val origin = anchor(101); val destination = anchor(103)
                val policy = EndpointAccessPolicy(graph, origin, destination,
                    EndpointAccessPolicy.scope(graph, setOf(101), listOf(EndpointAccessPolicy.Grant(
                        MotorcarTagPolicy.AccessClass.DESTINATION, EndpointAccessPolicy.Basis.ENDPOINT_PURPOSE,
                        "Explicit synthetic origin purpose"))),
                    EndpointAccessPolicy.scope(graph, setOf(103), listOf(EndpointAccessPolicy.Grant(
                        MotorcarTagPolicy.AccessClass.PRIVATE, EndpointAccessPolicy.Basis.INDIVIDUAL_PERMISSION,
                        "Explicit synthetic facility permission", at.minusSeconds(1), at.plusSeconds(60)))))
                val contexts = HashMap<Pair<RoadPolicyTimeline.Kind, Long>, RoadPolicyTimeline.Snapshot>()
                val timing = SourceRoadTiming(graph, { checkNotNull(graph.lengthMm(it)) },
                    RoadSpeedEstimate.Profile("planner-fixture-service30", "Explicit synthetic arithmetic profile, not traffic observation",
                        mapOf("service" to BigDecimal(30))), { kind, id -> contexts.getOrPut(kind to id) {
                        RoadPolicyTimeline.Snapshot(graph.revision, kind, id, "planner-synthetic-$kind-$id",
                            "Frozen synthetic source-local clock-rule context, not geographic discovery",
                            at.minusSeconds(1), at.plusSeconds(61), ZoneId.of("America/New_York"))
                    } }, { arc, approach -> SourceRoadTiming.Control(graph.revision, arc.id, approach,
                        "audited-fixture-controls", "Eight synthetic source ways have no signal/stop/yield operating schedules; explicit zero-control oracle",
                        at.plusSeconds(60), Match.YES, 0) }, policy)
                fun point(node: Long) = checkNotNull(graph.coordinate(node)).let { GeoPoint(it.latE7 / 1e7, it.lonE7 / 1e7) }
                fun camera(id: String, node: Long) = CameraPoi("$token-$id", CameraType.ALPR, point(node),
                    "INSTRUMENTATION ONLY", null, null, null, null, source = "Synthetic full planner cache fixture",
                    sourceId = liveSource, sourceRecordId = "$token-$id", tags = mapOf("watchman:test_fixture" to "true"))
                val first = camera("a", 2)
                val second = camera("b", 3).copy(sourceId = "seed-test-$token")
                live.replaceSourceSnapshot(liveSource, listOf(first))
                national.replaceSeedShard(token, listOf(second))
                regional.replaceSnapshot(token, listOf(first.copy(type = CameraType.FLOCK,
                    manufacturer = "Flock Safety", tags = first.tags + mapOf("surveillance:type" to "alpr", "model" to "Falcon"))))
                val end = checkNotNull(graph.segment((destination.arcId - 1) / 2 + 1))
                val destinationPoint = RoadSnapGeometry.pointAt(end.fromPoint, end.toPoint, destination.directedFraction)
                val target = SearchResult("Synthetic planner fixture destination", GeoPoint(destinationPoint.latitude, destinationPoint.longitude))
                fun plan(purpose: FullRoadNoCamsPlanner.Purpose = FullRoadNoCamsPlanner.Purpose.DEVELOPMENT_EVIDENCE,
                    cancelled: () -> Boolean = { false }, binding: EndpointAccessPolicy? = policy,
                    nanoTime: () -> Long = System::nanoTime,
                    wholeBudget: FullRoadPlanningBudget = FullRoadPlanningBudget(nanoTime(), nanoTime)) = planner.plan(
                    graph, origin, destination, target, at, NoCamsGraphSearch.Bounds(1000000, 60000),
                    NoCamsGraphSearch.Budget(maximumExpanded = 2000, maximumGenerated = 5000, deadlineMillis = 30000),
                    timing, GraphRouteMaterializer.DrivingSide.RIGHT, purpose, binding, cancelled = cancelled,
                    nanoTime = nanoTime, wholeBudget = wholeBudget)
                check(runCatching { plan(FullRoadNoCamsPlanner.Purpose.PRODUCTION) }.exceptionOrNull()?.message.orEmpty().contains("readiness"))
                check(runCatching { plan(cancelled = { true }) }.isFailure)
                check(runCatching { plan(binding = null) }.isFailure)
                // Baseline/provider time is real debit even though this fixture's preparation
                // uses an intentionally fixed monotonic clock. Never grant a new search epoch.
                var ticks = 24_000_000_000L
                val whole = FullRoadPlanningBudget(0, { ticks })
                check(runCatching { plan(nanoTime = { ticks }, wholeBudget = whole) }
                    .exceptionOrNull()?.message.orEmpty().contains("exhausted"))
                ticks = 25_000_000_000L
                check(runCatching { plan(nanoTime = { ticks }, wholeBudget = whole) }
                    .exceptionOrNull()?.message.orEmpty().contains("deadline"))
                val output = plan()
                check(output.search.stop == NoCamsGraphSearch.Stop.FOUND) { "Planner: ${output.search.stop} ${output.search.diagnostics}" }
                val route = checkNotNull(output.materialized)
                check(route.graphRevision == graph.revision && route.deviceRevision == output.devices.revision)
                check(output.devices.queryScope == output.region.queryScope)
                val oracle = fixtureOracle(graph, output.devices, origin, destination)
                val path = output.search.route!!
                val actualCost = OracleCost(path.cost.exposure, path.cost.durationMillis, path.cost.distanceMm)
                val actualPortions = route.segments.map { it.portion }
                val actualWays = actualPortions.map { checkNotNull(graph.arc(it.arcId)).let { road ->
                    "${road.wayId}:${road.direction}:${it.fromFraction}..${it.toFraction}"
                } }
                check(actualCost == oracle.cost && oracle.paths.any {
                    it.portions == actualPortions && it.devices == route.devices
                }) { "Full planner differs from independent original-source oracle: actual=$actualCost ways=$actualWays " +
                    "expected=${oracle.cost} paths=${oracle.paths.map { it.portions }} expanded=${oracle.expanded} " +
                    "search=${output.search.stop} rejections=${output.search.rejections} diagnostics=${output.search.diagnostics}" }
                val mergedFirst = output.devices.entries.single { it.device.sourceRecordId == first.sourceRecordId }
                check(mergedFirst.device.type == CameraType.FLOCK && mergedFirst.device.tags["model"] == "Falcon")
                check(route.devices.containsKey(mergedFirst.logicalId))
                check(route.devices.containsKey(DeviceIdentity.logicalKey(second)))
                // Independent direct existing exposure metric over every materialized source
                // segment, including endpoints, against ALL frozen current cache observations.
                val expected = output.devices.entries.filter { entry -> entry.weight > 0 &&
                    route.route.points.zipWithNext().any { (a, b) ->
                        Geo.pointToSegmentMeters(entry.device.point, a, b) <= output.devices.corridorMeters
                    } }.associate { it.logicalId to it.weight }
                check(route.devices == expected && output.search.route!!.cost.exposure == expected.values.sum())
                observed = output.devices.entries.size; encountered = expected.size
                duration = output.search.route!!.cost.durationMillis
                check(route.route.durationSeconds == duration / 1000.0)
                check(national.regionStates().none { it.regionId == token } && national.successfulSourceIds().none { it.contains(token) })
            }
        } finally {
            live.writableDatabase.delete("cameras", "source_id=?", arrayOf(liveSource))
            national.writableDatabase.delete("national_cameras", "region_id=?", arrayOf("seed:$token"))
            regional.writableDatabase.delete("regional_cameras", "tile_id=?", arrayOf(token))
            live.close(); national.close(); regional.close()
        }
        return "PASS: FullRoadNoCamsPlanner actual app live/national/regional SQLite caches, bounded full-search region, canonical rich immutable current observations, source timing/grants/turns/full-road search/exact Route, independent complete-path exposure and unready production/cancel/wrong binding refusal; observed=$observed,encountered=$encountered,durationMs=$duration,totalMs=${SystemClock.elapsedRealtime()-started}. Synthetic road/endpoint/context/control/profile inputs; not MainActivity/public-trip or physical acceptance."
    }
}
