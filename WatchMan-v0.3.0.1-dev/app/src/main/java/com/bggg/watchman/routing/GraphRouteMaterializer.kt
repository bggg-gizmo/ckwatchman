package com.bggg.watchman.routing

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.data.RouteStep
import com.bggg.watchman.data.SearchResult
import com.bggg.watchman.util.Geo
import java.time.Duration
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt

/** Bridges a searched ORIGINAL directed path to existing map/navigation/export/route-lock models.
 * No OSRM request, waypoint shaping, coordinate-based topology merge or straight endpoint connector.
 * This preserves search evidence; it does not certify unknown coverage, entrances or legal permission.
 */
object GraphRouteMaterializer {
    data class Geometry(val graphRevision: String, val arcId: Long, val fromNode: Long, val toNode: Long,
        val from: RoadSnapGeometry.Point, val to: RoadSnapGeometry.Point, val sourceLengthMm: Long)
    interface Source {
        val graph: MotorcarGraphTraversal.Graph
        val deviceRevision: String
        fun sourceGeometry(arcId: Long): Geometry
    }
    enum class DrivingSide(val tag: String) { LEFT("left"), RIGHT("right") }
    data class Materialized(val route: Route, val graphRevision: String, val deviceRevision: String,
        val originAnchor: RoadEndpointAnchor, val destinationAnchor: RoadEndpointAnchor,
        val segments: List<NoCamsGraphSearch.Segment>, val devices: Map<String, Long>,
        val optimalWithinEvaluatedSnapshot: Boolean, val diagnostics: List<String>,
        val searchStop: NoCamsGraphSearch.Stop)
    private data class Piece(val arc: MotorcarGraphTraversal.Arc, val portion: RoadArcPortion,
        val start: GeoPoint, val end: GeoPoint, val distanceMm: Long, val bearing: Double?)

    fun materialize(result: NoCamsGraphSearch.Result, source: Source,
        origin: RoadEndpointAnchor, destination: RoadEndpointAnchor, destinationResult: SearchResult,
        drivingSide: DrivingSide, maximumSegments: Int = 100_000,
        interrupted: () -> Boolean = { false }): Materialized {
        require(maximumSegments in 1..500_000)
        require(result.stop in setOf(NoCamsGraphSearch.Stop.FOUND, NoCamsGraphSearch.Stop.DEADLINE,
            NoCamsGraphSearch.Stop.RESOURCE_LIMIT, NoCamsGraphSearch.Stop.EXHAUSTED)) {
            "Cancelled, changed or invalid search cannot publish a route"
        }
        val selected = requireNotNull(result.route) { "No searched path" }
        require(selected.segments.size in 1..maximumSegments) { "Path size exceeds materialization budget" }
        val graph = source.graph
        fun stable() {
            check(!interrupted()) { "Route materialization interrupted; result must not publish" }
            check(source.graph === graph && graph.revision == result.graphRevision &&
                source.deviceRevision == result.deviceRevision) { "Graph/device snapshot changed during route materialization" }
        }
        stable()
        require(origin.sourceRevision == result.graphRevision && destination.sourceRevision == result.graphRevision)
        fun anchorArc(anchor: RoadEndpointAnchor): MotorcarGraphTraversal.Arc =
            checkNotNull(graph.arc(anchor.arcId)).also {
                check(it.id == anchor.arcId && it.from == anchor.fromNode && it.to == anchor.toNode)
            }
        anchorArc(origin); anchorArc(destination)
        val pieces = ArrayList<Piece>(selected.segments.size)
        var distanceMm = 0L
        var durationMs = 0L
        for ((index, segment) in selected.segments.withIndex()) {
            stable()
            val portion = segment.portion
            val arc = checkNotNull(graph.arc(portion.arcId)) { "Missing searched arc" }
            check(arc.id == portion.arcId && arc.direction == portion.direction)
            val geometry = source.sourceGeometry(arc.id)
            check(geometry.graphRevision == result.graphRevision && geometry.arcId == arc.id &&
                geometry.fromNode == arc.from && geometry.toNode == arc.to &&
                geometry.sourceLengthMm >= 0) { "Source geometry identity mismatch" }
            val distance = portion.distanceMm(geometry.sourceLengthMm)
            check(segment.distanceMm == distance) { "Searched/source portion distance mismatch" }
            check(segment.arrival > segment.departure)
            check(segment.travelDeparture>=segment.departure && segment.travelDeparture<segment.arrival)
            val wait=Duration.between(segment.departure,segment.travelDeparture)
            check(wait.nano % 1_000_000==0)
            if(!wait.isZero) {
                check(portion.fromFraction==0.0 && !segment.waitEvidence.isNullOrBlank()) {
                    "Unrepresented or interior control wait"
                }
                val waitChecks=segment.checks.filter { it.location.phase==MotorcarGraphTraversal.Phase.WAIT_NODE }
                check(waitChecks.size==2 && waitChecks[0].instant==segment.departure &&
                    waitChecks[1].instant==segment.travelDeparture &&
                    waitChecks.all { it.location.nodeId==arc.from &&
                        it.decision.permissionIndependentPassability==ConditionalRestriction.Match.YES &&
                        (it.permission?.permission ?: it.decision.publicThrough)==ConditionalRestriction.Match.YES }) {
                    "Missing exact original-node wait checks"
                }
            } else check(segment.waitEvidence==null) { "Wait evidence without a represented wait" }
            val duration = Duration.between(segment.departure, segment.arrival)
            check(duration.nano % 1_000_000 == 0) { "Search path has sub-millisecond duration" }
            durationMs = Math.addExact(durationMs, duration.toMillis())
            distanceMm = Math.addExact(distanceMm, distance)
            fun point(fraction: Double): GeoPoint = RoadSnapGeometry.pointAt(geometry.from, geometry.to, fraction)
                .let { GeoPoint(it.latitude, it.longitude) }
            val start = point(portion.fromFraction)
            val end = point(portion.toFraction)
            if (index == 0) {
                val originNode = when (origin.directedFraction) {
                    0.0 -> origin.fromNode
                    1.0 -> origin.toNode
                    else -> null
                }
                check(if (originNode != null) portion.fromFraction == 0.0 && arc.from == originNode
                    else arc.id == origin.arcId && portion.fromFraction == origin.directedFraction) {
                    "Path does not begin at its exact bound origin"
                }
            } else {
                val previous = pieces.last()
                val previousTime = selected.segments[index - 1]
                check(previousTime.arrival == segment.departure) { "Unrepresented wait/gap/overlap in path time" }
                check(if (previous.portion.toFraction == 1.0)
                    portion.fromFraction == 0.0 && previous.arc.to == arc.from
                else previous.arc.id == arc.id && previous.portion.toFraction == portion.fromFraction) {
                    "Disconnected original-node/interior path; no invented connector permitted"
                }
                check(previous.end == start) { "Connected source geometry disagrees at shared original node" }
            }
            val bearing = if (start == end) null else Geo.bearingDegrees(start, end)
            pieces += Piece(arc.copy(tags = arc.tags.toMap(), unresolved = arc.unresolved.toList()),
                portion, start, end, distance, bearing)
            stable()
        }
        val last = pieces.last()
        val destinationNode = when (destination.directedFraction) {
            0.0 -> destination.fromNode
            1.0 -> destination.toNode
            else -> null
        }
        check(if (destinationNode != null) last.portion.toFraction == 1.0 && last.arc.to == destinationNode
            else last.arc.id == destination.arcId && last.portion.toFraction == destination.directedFraction) {
            "Path does not reach its exact bound destination"
        }
        check(distanceMm == selected.cost.distanceMm && durationMs == selected.cost.durationMillis)
        val exposure = selected.devices.values.fold(0L) { sum, weight ->
            require(weight >= 0); Math.addExact(sum, weight)
        }
        check(exposure == selected.cost.exposure)
        val points = ArrayList<GeoPoint>(pieces.size + 1)
        points += pieces.first().start
        pieces.forEach { stable(); points += it.end } // Preserve every original arc endpoint; no simplification.
        val starts = arrayListOf(0)
        for (index in 1 until pieces.size) {
            stable()
            val prior = pieces[index - 1]
            val current = pieces[index]
            // OSM may split a named road into several ways. Do not invent a turn at every tag split.
            val previousLabel = prior.arc.tags["name"].orEmpty() to prior.arc.tags["ref"].orEmpty()
            val currentLabel = current.arc.tags["name"].orEmpty() to current.arc.tags["ref"].orEmpty()
            val changedRoad = if (previousLabel == ("" to "") && currentLabel == ("" to ""))
                prior.arc.wayId != current.arc.wayId else previousLabel != currentLabel
            val angle = if (prior.bearing != null && current.bearing != null)
                signedTurn(prior.bearing, current.bearing) else 0.0
            val junction = prior.arc.tags["junction"].orEmpty() != current.arc.tags["junction"].orEmpty()
            if (changedRoad || abs(angle) >= 60.0 || junction) starts += index
        }
        val steps = starts.mapIndexed { group, index ->
            stable()
            val piece = pieces[index]
            val before = pieces.getOrNull(index - 1)?.bearing
            val delta = if (before != null && piece.bearing != null) signedTurn(before, piece.bearing) else 0.0
            val road = piece.arc.tags["name"].orEmpty()
            val ref = piece.arc.tags["ref"].orEmpty()
            val label = listOf(road, ref).filter { it.isNotBlank() }.distinct().joinToString(" / ")
                .ifBlank { "unnamed road" }
            val modifier = when {
                index == 0 -> ""
                abs(delta) >= 150.0 -> "uturn"
                abs(delta) < 20.0 -> "straight"
                delta > 0 -> if (delta < 60.0) "slight right" else "right"
                else -> if (delta > -60.0) "slight left" else "left"
            }
            val kind = when {
                index == 0 -> "depart"
                piece.arc.tags["junction"] in setOf("roundabout", "circular") &&
                    pieces[index - 1].arc.tags["junction"] !in setOf("roundabout", "circular") -> "roundabout"
                modifier == "straight" -> "continue"
                else -> "turn"
            }
            val instruction = when {
                index == 0 && piece.bearing != null -> "Head ${compass(piece.bearing)} on $label"
                index == 0 -> "Depart on $label"
                kind == "roundabout" -> "Enter the roundabout onto $label"
                modifier == "uturn" -> "Make a U-turn onto $label"
                modifier == "straight" -> "Continue onto $label"
                else -> "Turn $modifier onto $label"
            }
            val end = starts.getOrNull(group + 1) ?: pieces.size
            val groupMm = (index until end).fold(0L) { sum, i -> stable(); Math.addExact(sum, pieces[i].distanceMm) }
            val suffix = if (piece.arc.direction == MotorcarTagPolicy.Direction.FORWARD) "forward" else "backward"
            RouteStep(instruction, piece.start, groupMm / 1000.0, kind, modifier, road, ref,
                piece.arc.tags["destination:$suffix"] ?: piece.arc.tags["destination"].orEmpty(),
                piece.arc.tags["exit_ref"].orEmpty(), drivingSide = drivingSide.tag,
                bearingBefore = before, bearingAfter = piece.bearing)
        } + RouteStep("Arrive at destination", pieces.last().end, 0.0, "arrive", drivingSide = drivingSide.tag)
        stable()
        val materialized = Materialized(Route(Collections.unmodifiableList(points), Collections.unmodifiableList(steps),
            distanceMm / 1000.0, durationMs / 1000.0,
            destinationResult, "WatchMan compact road graph / No cams"), result.graphRevision, result.deviceRevision,
            origin, destination, Collections.unmodifiableList(selected.segments.map {
                stable()
                it.copy(checks = Collections.unmodifiableList(it.checks.toList()))
            }), Collections.unmodifiableMap(selected.devices.toMap()),
            result.stop == NoCamsGraphSearch.Stop.FOUND && result.optimalWithinEvaluatedSnapshot,
            Collections.unmodifiableList(result.diagnostics.toList()), result.stop)
        stable()
        return materialized
    }

    private fun signedTurn(before: Double, after: Double): Double =
        ((after - before + 540.0) % 360.0) - 180.0
    private fun compass(bearing: Double): String = listOf("north", "northeast", "east", "southeast",
        "south", "southwest", "west", "northwest")[((bearing / 45.0).roundToInt() % 8 + 8) % 8]
}
