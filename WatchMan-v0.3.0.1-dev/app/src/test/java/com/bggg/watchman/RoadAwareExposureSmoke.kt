package com.bggg.watchman

import com.bggg.watchman.data.*
import com.bggg.watchman.routing.RoadAwareExposure as Policy
import com.bggg.watchman.storage.RoutingDeviceSnapshot

fun roadAwareExposureSmoke() {
    val lower = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01), GeoPoint(0.0, 0.02))
    val upper = lower.map { it.copy(lat = 0.0004) }
    fun route(points: List<GeoPoint>) = Route(points, emptyList(), 2000.0, 200.0,
        SearchResult("SYNTHETIC_ENDPOINT", points.last()), "synthetic road source")
    val signal = CameraPoi("node/12", CameraType.TRAFFIC, lower[1], null, null, null, null, null,
        sourceId = "synthetic-osm", sourceRecordId = "node/12", tags = mapOf("highway" to "traffic_signals"))
    fun snapshot(camera: CameraPoi = signal) = RoutingDeviceSnapshot.freeze(listOf(camera), "synthetic-road-trial")
    val nodes = lower.mapIndexed { i, p -> Policy.Node(11L+i, p, if (i == 1) mapOf("highway" to "traffic_signals") else emptyMap()) } +
        upper.mapIndexed { i, p -> Policy.Node(21L+i, p, emptyMap()) }
    val ways = listOf(Policy.Way(100, listOf(11,12,13), "residential"), Policy.Way(200, listOf(21,22,23), "residential"))
    fun observation(path: Route, way: Long = 200, sourceNodes: List<Policy.Node> = nodes,
        parents: Set<Long> = setOf(12), spans: List<Policy.Span> = listOf(Policy.Span(way, 0, path.points.lastIndex)),
        shape: List<GeoPoint> = path.points) = Policy.Observation(shape, spans, sourceNodes, ways, parents,
            "synthetic exact edge walk + complete source node/way observations", 1000, 2000)
    val bypass = route(upper)
    val proof = checkNotNull(Policy.verify(bypass, observation(bypass), 1100))
    val clear = Policy.assess(bypass, snapshot(), proof, 1100)
    check(clear.nearbyRelevantDevices == listOf(signal) && clear.encounters.isEmpty())
    check(clear.awarenessOnlyDevices == listOf(signal) && clear.weightedExposure == 0L)
    val incompleteParents = nodes.filter { it.id != 11L }
    check(Policy.assess(bypass,snapshot(),Policy.verify(bypass,observation(bypass,sourceNodes=incompleteParents),1100),1100).unknownEncounters==1)
    // Different source IDs on coincident/stacked road geometry cannot establish avoidance.
    val coincidentNodes = nodes.map { if(it.id in 11L..13L) Policy.Node(it.id,it.point.copy(lat=0.0004),it.tags) else it }
    val coincidentSignal = signal.copy(point=signal.point.copy(lat=0.0004))
    check(Policy.assess(bypass,snapshot(coincidentSignal),
        Policy.verify(bypass,observation(bypass,sourceNodes=coincidentNodes),1100),1100).unknownEncounters==1)
    val crossingParents = nodes.map { when(it.id) {
        11L -> Policy.Node(it.id,GeoPoint(-0.01,0.01),it.tags)
        13L -> Policy.Node(it.id,GeoPoint(0.01,0.01),it.tags)
        else -> it
    } }
    check(Policy.assess(bypass,snapshot(),Policy.verify(bypass,observation(bypass,sourceNodes=crossingParents),1100),1100).unknownEncounters==1)
    val crossing = route(lower)
    val hit = Policy.assess(crossing, snapshot(), Policy.verify(crossing, observation(crossing, 100), 1100), 1100)
    check(hit.encounters == listOf(signal) && hit.verifiedRoadEncounters == 1 && hit.weightedExposure == 1L)
    val reverse = route(lower.reversed())
    check(Policy.assess(reverse, snapshot(), Policy.verify(reverse, observation(reverse, 100), 1100), 1100).weightedExposure == 1L)
    // No guessed directional/FOV exemption. Unknown source coverage keeps the full radius.
    for (missing in listOf(null, Policy.verify(bypass, observation(bypass, parents = emptySet()), 1100)))
        check(Policy.assess(bypass, snapshot(), missing, 1100).unknownEncounters == 1)
    check(Policy.assess(bypass, snapshot(), proof, 2000).unknownEncounters == 1)
    check(Policy.verify(bypass, observation(bypass), 999) == null)
    val moved = nodes.map { if (it.id == 12L) Policy.Node(it.id, it.point.copy(lat = 0.00002), it.tags) else it }
    check(Policy.assess(bypass, snapshot(), Policy.verify(bypass, observation(bypass, sourceNodes = moved), 1100), 1100).unknownEncounters == 1)
    for (type in CameraType.entries.filter { it != CameraType.TRAFFIC }) {
        val other = signal.copy(type = type, manufacturer = "SYNTHETIC_SENSOR_VENDOR", tags = signal.tags + ("sensor" to "yes"))
        check(Policy.assess(bypass, snapshot(other), proof, 1100).unknownEncounters == 1)
    }
    val enriched = signal.copy(tags = signal.tags + ("camera" to "yes"))
    check(Policy.assess(bypass, snapshot(enriched), proof, 1100).unknownEncounters == 1)
    check(Policy.assess(bypass, snapshot(signal.copy(manufacturer="SYNTHETIC_SENSOR_VENDOR")), proof, 1100).unknownEncounters == 1)
    check(Policy.assess(bypass, snapshot(signal.copy(tags=signal.tags+("man_made" to "surveillance"))), proof, 1100).unknownEncounters == 1)
    for (product in listOf("EOC+", "EOC Plus", "SignalTrace", "Flock Raven acoustic", "ELSAG MPH900",
        "Rekor", "Genetec AutoVu", "Vigilant Solutions", "Wavetronix traffic sensor", "SoundThinking")) {
        val changedSource = nodes.map { if(it.id == 12L) Policy.Node(it.id,it.point,it.tags+("name" to product)) else it }
        check(Policy.assess(bypass,snapshot(),Policy.verify(bypass,observation(bypass,sourceNodes=changedSource),1100),1100).unknownEncounters==1)
    }
    val gap = observation(bypass, spans = listOf(Policy.Span(200, 1, 2)))
    check(Policy.verify(bypass, gap, 1100) == null)
    check(Policy.assess(crossing, snapshot(), proof, 1100).unknownEncounters == 1) // Wrong route cannot reuse proof.
    val differentShape = observation(bypass, shape = lower)
    check(Policy.verify(bypass, differentShape, 1100) == null)
    // Endpoints alone match a source way; its intervening bend does not match the chord.
    val chord = route(listOf(upper.first(), upper.last()))
    val bentNodes = nodes.map { if (it.id == 22L) Policy.Node(it.id, it.point.copy(lat = 0.003), emptyMap()) else it }
    val uncertainBend = Policy.verify(chord, observation(chord, sourceNodes = bentNodes), 1100)
    check(Policy.assess(chord, snapshot(), uncertainBend, 1100).unknownEncounters == 1)
    val endpointOnly = bypass.copy(points = upper.map { it.copy(lat = 0.01) }, requestedOrigin = signal.point)
    check(Policy.assess(endpointOnly, snapshot(), null, 1100).unknownEncounters == 1)
    check(runCatching { Policy.verify(bypass, observation(bypass), 1100) { error("CANCELLED") } }
        .exceptionOrNull()?.message == "CANCELLED")
    check(runCatching { Policy.assess(bypass, snapshot(), proof, 1100) { error("CANCELLED") } }
        .exceptionOrNull()?.message == "CANCELLED")
    println("Road-aware exposure trial: parallel-road awareness retained, source-road hits/weights, reverse conservatism, unknown/stale/moved/other-family/geometry/gap/endpoint/cancellation guards PASS; not app integration")
}
