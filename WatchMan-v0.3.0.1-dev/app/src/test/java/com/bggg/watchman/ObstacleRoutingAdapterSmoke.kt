package com.bggg.watchman

import com.bggg.watchman.data.*
import com.bggg.watchman.routing.*
import com.bggg.watchman.net.HttpClient
import com.bggg.watchman.storage.ActiveRouteState
import com.bggg.watchman.storage.ActiveRouteStateCodec
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import kotlin.math.*

/** Entirely synthetic wire fixtures. Also exercises the production HTTP JSON transport against
 * a loopback server, without sending fixture coordinates to a public provider.
 */
fun main() {
    deviceRecordMergeSmoke()
    roadCoverageSourceSmoke()
    roadAwareExposureSmoke()
    roadAwarenessJsonSmoke()
    cameraExclusionUnionSmoke()
    val origin = GeoPoint(0.0, 0.0)
    val destination = SearchResult("DESTINATION_FIXTURE", GeoPoint(0.0, 0.02))
    val camera = CameraPoi("DEVICE_FIXTURE", CameraType.TRAFFIC, GeoPoint(0.0, 0.01),
        null, null, null, null, null, lastUpdatedMillis = 1L)
    val provider = ValhallaObstacleRoutingProvider()
    val request = provider.request(origin, destination, listOf(camera))
    check(request.getString("costing") == "auto" && request.getString("format") == "osrm")
    check(request.getString("shape_format") == "geojson" && request.getString("language") == "en-GB")
    val auto = request.getJSONObject("costing_options").getJSONObject("auto")
    for (key in listOf("ignore_restrictions", "ignore_oneways", "ignore_access", "ignore_closures")) check(!auto.getBoolean(key))
    check(!auto.has("use_highways")) // Obstacles drive detours; no blanket main-road avoidance.
    check(!request.has("exclude_locations") && request.getJSONArray("locations").length() == 2)
    val ring = request.getJSONArray("exclude_polygons").getJSONArray(0)
    check(ring.length() == 9 && ring.getJSONArray(0).toString() == ring.getJSONArray(8).toString())
    // Each polygon side encloses the whole accepted encounter disk, not an inscribed shortcut.
    fun xy(index: Int): Pair<Double, Double> {
        val p = ring.getJSONArray(index)
        return Math.toRadians(p.getDouble(0) - camera.point.lon) * 6_371_000 to
            Math.toRadians(p.getDouble(1) - camera.point.lat) * 6_371_000
    }
    for (index in 0 until ring.length() - 1) {
        val (ax, ay) = xy(index); val (bx, by) = xy(index + 1)
        val distance = abs(ax * by - ay * bx) / hypot(bx - ax, by - ay)
        check(distance >= RouteAnalyzer.DEFAULT_CORRIDOR_METERS + 0.99)
    }
    check(runCatching { provider.request(origin, destination, List(NoCamsObstaclePlanner.MAX_OBSTACLES + 1) { camera }) }.isFailure)
    check(runCatching { provider.request(origin, destination, listOf(camera), cancelled = { true }) }.isFailure)
    val box = listOf(GeoPoint(-0.001, -0.001), GeoPoint(-0.001, 0.001),
        GeoPoint(0.001, 0.001), GeoPoint(0.001, -0.001), GeoPoint(-0.001, -0.001))
    val inside = GeoPoint(0.0, 0.0)
    val boundary = GeoPoint(0.0, 0.001)
    val outside = GeoPoint(0.0, 0.002)
    val smallPayload = RoadExclusionEncoding.encode(listOf(box), listOf(inside, boundary, outside))
    check(smallPayload.polygons == listOf(box) && smallPayload.roadPoints == listOf(boundary, outside))
    check(RoadExclusionEncoding.encode(emptyList(), listOf(outside)).roadPoints == listOf(outside))
    check(runCatching { RoadExclusionEncoding.encode(listOf(box),
        (0..50).map { GeoPoint(0.0, 0.002 + it * 0.00001) }) }.isFailure)
    val separated = (0..12).map { camera.copy(id = "SEPARATED_$it", point = GeoPoint(0.0, it * 0.02)) }
    val roadPoints = listOf(GeoPoint(0.0, 0.01), GeoPoint(0.001, 0.03))
    val roadRequest = provider.request(origin, destination, separated, roadPoints)
    check(!roadRequest.has("exclude_polygons") && roadRequest.getJSONArray("exclude_locations").length() == roadPoints.size)
    roadPoints.forEachIndexed { index, point ->
        val excluded = roadRequest.getJSONArray("exclude_locations").getJSONObject(index)
        check(excluded.getDouble("lat") == point.lat && excluded.getDouble("lon") == point.lon)
        check(excluded.getInt("node_snap_tolerance") == 0)
    }
    check(runCatching { provider.request(origin, destination, separated) }.isFailure)
    val manyRoads = (0..63).map { GeoPoint((it % 8) * 0.00001, (it / 8) * 0.02) }
    val mixedRequest = provider.request(origin, destination, separated, manyRoads)
    val selectedRings = mixedRequest.getJSONArray("exclude_polygons")
    val selected = (0 until selectedRings.length()).map { index ->
        val ring = selectedRings.getJSONArray(index)
        (0 until ring.length()).map { i -> ring.getJSONArray(i).let { GeoPoint(it.getDouble(1), it.getDouble(0)) } }
    }
    val locations = mixedRequest.getJSONArray("exclude_locations")
    check(locations.length() <= 50 && selected.isNotEmpty())
    val retained = (0 until locations.length()).map { locations.getJSONObject(it).let {
        GeoPoint(it.getDouble("lat"), it.getDouble("lon"))
    } }
    check(manyRoads.all { point -> point in retained || selected.any { RoadExclusionEncoding.containsInterior(it, point) } })
    val perimeters = selected.sumOf { ring -> ring.zipWithNext().sumOf { com.bggg.watchman.util.Geo.distanceMeters(it.first, it.second) } }
    check(perimeters <= 9000.0)
    check(selected.sumOf { it.size } <= 100)
    check(runCatching { provider.request(origin, destination, listOf(camera.copy(point = GeoPoint(0.0, 180.0)))) }.isFailure)
    val wireRoute = JSONObject().put("distance", 2400.0).put("duration", 240.0)
        .put("geometry", JSONObject().put("type", "LineString").put("coordinates",
            JSONArray().put(JSONArray().put(0.0).put(0.0)).put(JSONArray().put(0.02).put(0.0))))
        .put("legs", JSONArray().put(JSONObject().put("steps", JSONArray().put(JSONObject()
            .put("distance", 2400.0).put("name", "SYNTHETIC_ROAD").put("ref", "REF_FIXTURE")
            .put("destinations", "SIGN_FIXTURE").put("exits", "EXIT_FIXTURE").put("driving_side", "right")
            .put("maneuver", JSONObject().put("location", JSONArray().put(0.0).put(0.0))
                .put("type", "off ramp").put("modifier", "right").put("bearing_before", 80).put("bearing_after", 90))
            .put("intersections", JSONArray().put(JSONObject().put("lanes", JSONArray().put(JSONObject()
                .put("indications", JSONArray().put("right")).put("valid", true).put("active", true)))))))))
    val response = JSONObject().put("code", "Ok").put("routes", JSONArray().put(wireRoute)).toString()
    var invoked = 0
    val adapter = ValhallaObstacleRoutingProvider("https://provider.invalid") { url, body, timeout ->
        invoked++
        check(url == "https://provider.invalid/route" && timeout in 1000..4500)
        check(JSONObject(body).getJSONArray("exclude_polygons").length() == 1)
        response
    }
    val decoded = adapter.route(origin, destination, listOf(camera), 4500)
    check(decoded.destination == destination && decoded.points == listOf(origin, destination.point))
    check(decoded.distanceMeters == 2400.0 && decoded.durationSeconds == 240.0)
    val step = decoded.steps.single()
    check(step.exits == "EXIT_FIXTURE" && step.destinations == "SIGN_FIXTURE" && step.roadRef == "REF_FIXTURE")
    check(step.maneuverType == "off ramp" && step.maneuverModifier == "right" && step.bearingAfter == 90.0)
    check(step.lanes.single().valid && step.lanes.single().active)
    val requestedOrigin = GeoPoint(-0.0001, 0.0)
    val locked = ActiveRouteState(decoded.copy(requestedOrigin = requestedOrigin), SearchResult("ORIGIN_FIXTURE", requestedOrigin),
        RoutePreference.FEWEST_CAMERAS, 0)
    val restored = checkNotNull(ActiveRouteStateCodec.decode(ActiveRouteStateCodec.encode(locked)))
    check(restored == locked)
    check(ActiveRouteStateCodec.routeIdentity(decoded) != ActiveRouteStateCodec.routeIdentity(locked.route))
    val legacy = locked.copy(route = decoded)
    val legacyJson = JSONObject(ActiveRouteStateCodec.encode(legacy))
    legacyJson.getJSONObject("route").remove("requestedOrigin")
    check(ActiveRouteStateCodec.decode(legacyJson.toString()) == legacy)
    check(runCatching { adapter.route(origin, destination, emptyList(), 999) }.isFailure && invoked == 1)
    check(runCatching {
        ValhallaObstacleRoutingProvider("https://provider.invalid") { _, _, _ -> "{\"code\":\"NoRoute\"}" }
            .route(origin, destination, emptyList(), 4500)
    }.isFailure)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    var received = false
    server.createContext("/bounded-json") { exchange ->
        try {
            check(exchange.requestMethod == "POST")
            check(exchange.requestHeaders.getFirst("Content-Type").startsWith("application/json"))
            check(JSONObject(exchange.requestBody.bufferedReader().use { it.readText() }).getBoolean("fixture"))
            val bytes = if (exchange.requestHeaders.getFirst("X-Fixture-Large") == "true") ByteArray(2048) else "{\"ok\":true}".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } finally { exchange.close() }
    }
    server.createContext("/route") { exchange ->
        try {
            check(exchange.requestMethod == "POST")
            check(exchange.requestHeaders.getFirst("Content-Type").startsWith("application/json"))
            check(exchange.requestHeaders.getFirst("X-Client-Id") == "bggg-gizmo/ckwatchman")
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            check(body.getJSONArray("exclude_polygons").length() == 1)
            received = true
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } finally { exchange.close() }
    }
    server.start()
    try {
        val actual = ValhallaObstacleRoutingProvider("http://127.0.0.1:${server.address.port}")
            .route(origin, destination, listOf(camera), 4500)
        check(received && actual == decoded)
        val url = "http://127.0.0.1:${server.address.port}/bounded-json"
        check(JSONObject(String(HttpClient.postJsonBytes(url,"{\"fixture\":true}",2000,1024),Charsets.UTF_8)).getBoolean("ok"))
        check(runCatching { HttpClient.postJsonBytes(url,"{\"fixture\":true}",2000,1024,
            mapOf("X-Fixture-Large" to "true")) }.isFailure)
        check(runCatching { HttpClient.postJsonBytes(url,"x".repeat(2*1024*1024+1),2000) }.isFailure)
    } finally { server.stop(0) }
    println("Obstacle adapter: polygon coverage/budget, legal options, HTTP JSON/header transport, OSRM geometry/maneuver/lane decoding PASS")
}
