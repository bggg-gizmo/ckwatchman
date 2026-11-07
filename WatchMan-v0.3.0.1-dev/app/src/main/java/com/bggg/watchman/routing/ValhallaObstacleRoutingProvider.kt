package com.bggg.watchman.routing

import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.ProviderConfig
import com.bggg.watchman.data.Route
import com.bggg.watchman.data.SearchResult
import com.bggg.watchman.net.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/** Sends road exclusions to the graph search itself. No artificial route-via coordinates.
 * Public demo availability/limits are explicit dependencies; local graph support stays separate.
 * API: https://github.com/valhalla/valhalla/blob/master/docs/docs/api/route/api-reference.md
 */
class ValhallaObstacleRoutingProvider(
    private val endpoint: String = ProviderConfig.OBSTACLE_ROUTING_BASE,
    private val transport: (String, String, Int) -> String = { url, body, timeout ->
        HttpClient.postJson(url, body, timeout, mapOf("X-Client-Id" to "bggg-gizmo/ckwatchman"))
    }
) {
    fun route(origin: GeoPoint, destination: SearchResult, obstacles: List<CameraPoi>, remainingMs: Long): Route {
        return route(origin, destination, NoCamsObstaclePlanner.Exclusions(obstacles, emptyList()), remainingMs)
    }

    fun route(origin: GeoPoint, destination: SearchResult, exclusions: NoCamsObstaclePlanner.Exclusions, remainingMs: Long): Route {
        check(remainingMs >= 1_000L) { "Insufficient No Cams planning time for a road request" }
        val started = System.nanoTime() / 1_000_000L
        val cancelled = { Thread.currentThread().isInterrupted || System.nanoTime() / 1_000_000L - started >= remainingMs - 1_000L }
        val request = request(origin, destination, exclusions.devices, exclusions.encounteredRoadPoints, cancelled)
        val networkAllowance = remainingMs - (System.nanoTime() / 1_000_000L - started)
        check(networkAllowance >= 1_000L) { "Camera exclusion preparation consumed the remaining request deadline" }
        val root = JSONObject(transport("${endpoint.trimEnd('/')}/route", request.toString(), minOf(8_000L, networkAllowance).toInt()))
        check(root.optString("code") == "Ok") { "Obstacle road provider did not return a complete route" }
        val routes = root.getJSONArray("routes")
        check(routes.length() > 0) { "No legal route returned for the camera exclusions" }
        return OsrmRoutingProvider().parseRoute(routes.getJSONObject(0), destination, "Valhalla camera exclusions", 0)
    }

    internal fun request(origin: GeoPoint, destination: SearchResult, obstacles: List<CameraPoi>,
        encounteredRoadPoints: List<GeoPoint> = emptyList(), cancelled: () -> Boolean = { false }): JSONObject {
        require(obstacles.size <= NoCamsObstaclePlanner.MAX_OBSTACLES) { "Provider polygon budget exceeded" }
        require(encounteredRoadPoints.size <= NoCamsObstaclePlanner.MAX_ROAD_BLOCKS)
        fun location(point: GeoPoint): JSONObject {
            require(point.lat.isFinite() && point.lat in -90.0..90.0 && point.lon.isFinite() && point.lon in -180.0..180.0)
            return JSONObject().put("lat", point.lat).put("lon", point.lon).put("type", "break")
                .put("search_cutoff", NoCamsObstaclePlanner.MAX_ENDPOINT_SNAP_METERS)
        }
        val rings = ArrayList<List<GeoPoint>>()
        obstacles.forEach { camera ->
            check(!cancelled()) { "Camera exclusion preparation cancelled or deadline reached" }
            val point = camera.point
            require(point.lat.isFinite() && point.lon.isFinite() && kotlin.math.abs(point.lat) < 85.0)
            // A circumscribed polygon encloses the accepted
            // 180 m encounter radius. The one-metre margin covers stitching/shape precision.
            // Avoiding only the closest parallel road is insufficient.
            val radius = (RouteAnalyzer.DEFAULT_CORRIDOR_METERS + 1.0) / cos(Math.PI / SIDES)
            val latPad = Math.toDegrees(radius / 6_371_000.0)
            val lonPad = latPad / cos(Math.toRadians(point.lat))
            require(point.lon - lonPad > -180.0 && point.lon + lonPad < 180.0) {
                "Camera exclusion crosses the dateline; this backend adapter cannot certify it"
            }
            val ring = ArrayList<GeoPoint>()
            repeat(SIDES + 1) { index ->
                val angle = 2.0 * Math.PI * (index % SIDES) / SIDES
                ring += GeoPoint(point.lat + latPad * sin(angle), point.lon + lonPad * cos(angle))
            }
            rings += ring
        }
        val merged = CameraExclusionUnion.merge(rings, cancelled)
        val encoded = RoadExclusionEncoding.encode(merged, encounteredRoadPoints, additionalRings = rings, cancelled = cancelled)
        val polygons = JSONArray()
        encoded.polygons.forEach { ring ->
            polygons.put(JSONArray().apply { ring.forEach { put(JSONArray().put(it.lon).put(it.lat)) } })
        }
        val request = JSONObject().put("locations", JSONArray().put(location(origin)).put(location(destination.point)))
            .put("costing", "auto").put("costing_options", JSONObject().put("auto", JSONObject()
                .put("ignore_restrictions", false).put("ignore_oneways", false).put("ignore_access", false)
                .put("ignore_closures", false)))
            .put("date_time", JSONObject().put("type", 0))
            .put("format", "osrm").put("shape_format", "geojson")
            .put("units", "kilometers").put("language", "en-GB").put("turn_lanes", true)
        if (polygons.length() > 0) request.put("exclude_polygons", polygons)
        if (encoded.roadPoints.isNotEmpty()) {
            request.put("exclude_locations", JSONArray().apply {
                encoded.roadPoints.forEach { point -> put(location(point).put("node_snap_tolerance", 0)) }
            })
        }
        return request
    }

    private companion object {
        const val SIDES = 8
    }
}
