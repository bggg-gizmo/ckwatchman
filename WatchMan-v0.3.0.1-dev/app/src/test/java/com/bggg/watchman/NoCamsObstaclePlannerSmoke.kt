package com.bggg.watchman

import com.bggg.watchman.data.*
import com.bggg.watchman.routing.*
import com.bggg.watchman.storage.DeviceSearchRegion
import com.bggg.watchman.storage.RoutingDeviceSnapshot
import com.bggg.watchman.util.Geo
import java.util.PriorityQueue

/** Abstract grid, not a real road network or private test-location fixture. The injected router
 * runs Dijkstra over directed legal edges, excluding every edge intersecting a device radius.
 * This tests client orchestration; it does not validate any remote provider's legal data.
 */
fun noCamsObstaclePlannerSmoke() {
    fun point(x: Int, y: Int) = GeoPoint(y * 0.005, x * 0.005)
    val origin = point(0, 0)
    val destination = SearchResult("DESTINATION_FIXTURE", point(10, 0))
    fun camera(id: String, x: Int, y: Int) = CameraPoi(id, CameraType.ALPR, point(x, y),
        null, null, null, null, null, lastUpdatedMillis = 1L)
    fun snapshot(devices: List<CameraPoi>) = RoutingDeviceSnapshot.freeze(devices, "synthetic-grid-cache")
    data class Edge(val from: GeoPoint, val to: GeoPoint, val seconds: Double, val legal: Boolean = true)
    val edges = ArrayList<Edge>()
    for (x in 0..10) for (y in 0..2) {
        if (x < 10) edges += Edge(point(x, y), point(x + 1, y), if (y == 0) 10.0 else 20.0 + y)
        if (y < 2) {
            edges += Edge(point(x, y), point(x, y + 1), 15.0)
            edges += Edge(point(x, y + 1), point(x, y), 15.0)
        }
    }
    // An attractive illegal shortcut must never become a route around blocked roads.
    edges += Edge(origin, point(5, -1), 0.01, false)
    edges += Edge(point(5, -1), destination.point, 0.01, false)
    val calls = ArrayList<List<CameraPoi>>()
    fun graphRoute(from: GeoPoint, to: SearchResult, exclusions: NoCamsObstaclePlanner.Exclusions, remaining: Long): Route {
        val avoid = exclusions.devices
        check(remaining > 0)
        calls += avoid.toList()
        fun edgeRoute(edge: Edge) = Route(listOf(edge.from, edge.to), emptyList(),
            Geo.distanceMeters(edge.from, edge.to), edge.seconds, to, "synthetic directed graph")
        val allowed = edges.filter { edge -> edge.legal && avoid.none {
            RouteProximityIndex(edgeRoute(edge), RouteAnalyzer.DEFAULT_CORRIDOR_METERS).isWithin(it.point)
        } }.groupBy { it.from }
        data class Visit(val node: GeoPoint, val seconds: Double)
        val queue = PriorityQueue(compareBy<Visit> { it.seconds }.thenBy { it.node.lat }.thenBy { it.node.lon })
        val costs = hashMapOf(from to 0.0)
        val previous = HashMap<GeoPoint, Edge>()
        queue += Visit(from, 0.0)
        while (queue.isNotEmpty()) {
            val visit = queue.remove()
            if (costs[visit.node] != visit.seconds) continue
            if (visit.node == to.point) break
            for (edge in allowed[visit.node].orEmpty()) {
                val cost = visit.seconds + edge.seconds
                if (cost < costs.getOrDefault(edge.to, Double.POSITIVE_INFINITY)) {
                    costs[edge.to] = cost; previous[edge.to] = edge; queue += Visit(edge.to, cost)
                }
            }
        }
        check(to.point in costs) { "No legal graph path" }
        val path = ArrayList<GeoPoint>()
        var node = to.point
        path += node
        while (node != from) { node = checkNotNull(previous[node]).from; path += node }
        path.reverse()
        return Route(path, listOf(RouteStep("Follow synthetic legal roads", from, 0.0)),
            path.zipWithNext().sumOf { Geo.distanceMeters(it.first, it.second) },
            costs.getValue(to.point), to, "synthetic directed graph")
    }
    fun assertFailure(fragment: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        check(error != null && error.message.orEmpty().contains(fragment)) { "Expected $fragment, got $error" }
    }
    val baseline = graphRoute(origin, destination, NoCamsObstaclePlanner.Exclusions(emptyList(), emptyList()), 25_000)
    calls.clear()
    val clear = NoCamsObstaclePlanner(::graphRoute, { snapshot(emptyList()) }, now = { 0 }).plan(origin, destination)
    check(clear.requests == 1 && clear.route.points.all { it.lat == 0.0 }) // Camera-free main road stays usable.
    val devices = (1..8).map { camera("DEVICE_$it", it, 0) }
    calls.clear()
    val planned = NoCamsObstaclePlanner(::graphRoute, { snapshot(devices) }, now = { 0 }).plan(origin, destination)
    check(planned.requests == 2 && calls[1].size == 8) // All encounters, beyond three clusters, sent together.
    check(RouteAnalyzer.countKnownCameras(planned.route, devices) == 0)
    check(planned.route.points.any { it.lat > 0.0 } && planned.route.points.none { it.lat < 0.0 })
    check(planned.route.points.zipWithNext().all { (a, b) -> edges.any { it.legal && it.from == a && it.to == b } })
    val deviceTypes = listOf(CameraType.RED_LIGHT, CameraType.SPEED, CameraType.TRAFFIC, CameraType.HIGHWAY,
        CameraType.ALPR, CameraType.FLOCK, CameraType.ACOUSTIC, CameraType.RF_SENSOR, CameraType.SURVEILLANCE, CameraType.SENSOR)
    val vendorFamilies = listOf("Raven", "SignalTrace/EOC Plus", "Leonardo/ELSAG", "Vigilant/Motorola",
        "Rekor", "Genetec AutoVu", "ShotSpotter/SoundThinking")
    val allSensors = deviceTypes.mapIndexed { index, type ->
        camera("SENSOR_FAMILY_$index", 1 + index % 8, 0).copy(type = type, manufacturer = vendorFamilies[index % vendorFamilies.size])
    }
    val otherSensor = camera("OTHER_WITH_SENSOR_EVIDENCE", 4, 0).copy(type = CameraType.OTHER, tags = mapOf("sensor" to "yes"))
    val bareToll = camera("BARE_TOLL", 5, 0).copy(type = CameraType.TOLL)
    calls.clear()
    val sensorPlan = NoCamsObstaclePlanner(::graphRoute, { snapshot(allSensors + otherSensor + bareToll) }, now = { 0 })
        .plan(origin, destination)
    check(sensorPlan.excludedDevices == allSensors.size + 1)
    check(calls.last().map { it.id }.containsAll(allSensors.map { it.id } + otherSensor.id))
    check(calls.last().none { it.id == bareToll.id })
    check(RouteAnalyzer.countKnownCameras(sensorPlan.route, allSensors + otherSensor + bareToll) == 0)
    val detourDevices = devices + camera("DETOUR_DEVICE", 5, 1)
    calls.clear()
    val repaired = NoCamsObstaclePlanner(::graphRoute, { snapshot(detourDevices) }, now = { 0 }).plan(origin, destination)
    check(repaired.requests >= 3 && RouteAnalyzer.countKnownCameras(repaired.route, detourDevices) == 0)
    check(calls.last().map { it.id }.containsAll(detourDevices.map { it.id }))
    check(repaired.route.points.any { it.lat == point(0, 2).lat })
    var reads = 0
    calls.clear()
    val newData = NoCamsObstaclePlanner(::graphRoute,
        { snapshot(if (++reads == 1) emptyList() else devices) }, now = { 0 }).plan(origin, destination)
    check(newData.requests == 2 && RouteAnalyzer.countKnownCameras(newData.route, devices) == 0)
    assertFailure("cache unavailable") {
        NoCamsObstaclePlanner(::graphRoute, { error("cache unavailable") }, now = { 0 }).plan(origin, destination)
    }
    assertFailure("still crosses") {
        NoCamsObstaclePlanner({ _, _, _, _ -> baseline }, { snapshot(devices) }, now = { 0 }).plan(origin, destination)
    }
    // A backend failing after exclusions is NOT proof that a camera is unavoidable.
    assertFailure("Backend restricted search failed") {
        NoCamsObstaclePlanner({ _, _, exclusions, _ ->
            if (exclusions.devices.isEmpty()) baseline else error("Backend restricted search failed")
        }, { snapshot(devices) }, now = { 0 }).plan(origin, destination)
    }
    val endpointDevice = camera("ENDPOINT", 10, 0)
    assertFailure("No legal graph path") {
        NoCamsObstaclePlanner(::graphRoute, { snapshot(devices + endpointDevice) }, now = { 0 })
            .plan(origin, destination)
    }
    check(calls.last().any { it.id == endpointDevice.id }) // Still a constraint; proximity is not an exception.
    // Original endpoint counts even when provider has snapped the road geometry away from it.
    val offsetStart = GeoPoint(origin.lat, origin.lon - 0.0008)
    val endpointCamera = camera("ORIGINAL_ENDPOINT", 0, 0).copy(point = GeoPoint(0.0, -0.0017))
    assertFailure("still crosses") {
        NoCamsObstaclePlanner({ _, _, _, _ -> baseline }, { snapshot(listOf(endpointCamera)) }, now = { 0 })
            .plan(offsetStart, destination)
    }
    val observedOffsetRoute = baseline.copy(requestedOrigin = offsetStart)
    check(RouteAnalyzer.countKnownCameras(observedOffsetRoute, listOf(endpointCamera)) == 1)
    check(RouteCorridor.windows(listOf(observedOffsetRoute), 180.0).any { it.contains(endpointCamera.point, 180.0) })
    assertFailure("budget exceeded") {
        val tooMany = (1..NoCamsObstaclePlanner.MAX_OBSTACLES + 1).map { camera("OVERFLOW_$it", 5, 0) }
        NoCamsObstaclePlanner({ _, _, _, _ -> baseline }, { snapshot(tooMany) }, now = { 0 }).plan(origin, destination)
    }
    assertFailure("cancelled") {
        NoCamsObstaclePlanner(::graphRoute, { snapshot(emptyList()) }, now = { 0 })
            .plan(origin, destination, cancelled = { true })
    }
    var clock = 0L
    assertFailure("deadline") {
        NoCamsObstaclePlanner({ _, _, _, _ -> clock = 25_000L; baseline }, { snapshot(emptyList()) }, now = { clock })
            .plan(origin, destination)
    }
    assertFailure("detour exceeds") {
        NoCamsObstaclePlanner({ _, _, avoid, _ -> if (avoid.devices.isEmpty()) baseline else planned.route.copy(distanceMeters = 1e8) },
            { snapshot(devices) }, now = { 0 }).plan(origin, destination)
    }
    // The same camera can encounter several parallel roads. New road constraints must make
    // progress even though the device identity did not change between candidates.
    val parallelCamera = camera("PARALLEL_DEVICE", 5, 0).copy(point = GeoPoint(0.0004, 0.025))
    val parallel = NoCamsObstaclePlanner({ from, to, exclusions, _ ->
        val latitude = when (exclusions.encounteredRoadPoints.size) { 0 -> 0.0; 1 -> 0.0009; else -> 0.005 }
        val points = listOf(from, GeoPoint(latitude, from.lon), GeoPoint(latitude, to.point.lon), to.point)
        baseline.copy(points = points, durationSeconds = 200.0 + exclusions.encounteredRoadPoints.size * 10,
            distanceMeters = points.zipWithNext().sumOf { Geo.distanceMeters(it.first, it.second) })
    }, { snapshot(listOf(parallelCamera)) }, now = { 0 }).plan(origin, destination)
    check(parallel.requests == 3 && parallel.excludedDevices == 1)
    check(RouteAnalyzer.countKnownCameras(parallel.route, listOf(parallelCamera)) == 0)
    val region = DeviceSearchRegion.forOnlineRoute(baseline, listOf(offsetStart), 180.0)
    val remaining = checkNotNull(NoCamsRouteMonitor.remainingRoute(baseline, point(5, 0)))
    check(!NoCamsRouteMonitor.hasObstacle(remaining, snapshot(listOf(camera("PASSED", 2, 0)))))
    check(NoCamsRouteMonitor.hasObstacle(remaining, snapshot(listOf(camera("AHEAD", 8, 0)))))
    check(NoCamsRouteMonitor.hasObstacle(remaining, snapshot(listOf(camera("AT_POSITION", 5, 0)))))
    check(NoCamsRouteMonitor.hasObstacle(remaining, snapshot(listOf(camera("AT_DESTINATION", 10, 0)))))
    check(NoCamsRouteMonitor.remainingRoute(baseline, point(5, 2)) == null)
    check(region.contains(offsetStart) && baseline.points.all(region::contains))
    val seam = baseline.copy(points = listOf(GeoPoint(0.0, 179.99), GeoPoint(0.0, -179.99)))
    val seamRegion = DeviceSearchRegion.forOnlineRoute(seam, emptyList(), 180.0)
    check(seamRegion.contains(GeoPoint(0.0, 180.0)) && seamRegion.contains(GeoPoint(0.0, -180.0)))
    println("No Cams obstacle planner synthetic graph regressions passed")
}
