package com.bggg.watchman.routing

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.LaneGuidance
import com.bggg.watchman.data.ProviderConfig
import com.bggg.watchman.data.Route
import com.bggg.watchman.data.RouteStep
import com.bggg.watchman.data.SearchResult
import com.bggg.watchman.net.HttpClient
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

interface RoutingProvider {
    fun routes(origin: GeoPoint, destination: SearchResult, maxAlternatives: Int = 3): List<Route>

    fun routesVia(
        origin: GeoPoint,
        destination: SearchResult,
        waypoints: List<GeoPoint>,
        maxAlternatives: Int = 1
    ): List<Route> = routes(origin, destination, maxAlternatives)
}

/**
 * OSRM-compatible multi-endpoint routing provider.
 *
 * All configured backends are queried in parallel and their distinct alternatives are merged.
 * Public OSRM servers are allowed to advertise different alternative-count limits, so a rejected
 * numeric alternative count is retried with OSRM's boolean alternatives mode and finally with a
 * single route. One backend failing never suppresses successful routes from another backend.
 */
class OsrmRoutingProvider : RoutingProvider {
    @Volatile var lastEndpoint: String? = null
        private set
    @Volatile var lastError: String? = null
        private set

    override fun routes(origin: GeoPoint, destination: SearchResult, maxAlternatives: Int): List<Route> {
        val coords = coordinateString(listOf(origin, destination.point))
        return routesForCoordinates(coords, destination, maxAlternatives)
    }

    override fun routesVia(
        origin: GeoPoint,
        destination: SearchResult,
        waypoints: List<GeoPoint>,
        maxAlternatives: Int
    ): List<Route> {
        require(waypoints.size <= RouteShaper.MAX_VIA_POINTS) {
            "At most 3 route-shaping waypoints are supported"
        }
        val coords = coordinateString(listOf(origin) + waypoints + destination.point)
        return routesForCoordinates(coords, destination, maxAlternatives)
    }

    private fun coordinateString(points: List<GeoPoint>): String {
        require(points.size >= 2) { "A route requires an origin and destination" }
        return points.joinToString(";") { point ->
            String.format(Locale.US, "%.7f,%.7f", point.lon, point.lat)
        }
    }

    private fun routesForCoordinates(
        coords: String,
        destination: SearchResult,
        maxAlternatives: Int
    ): List<Route> {
        val requested = maxAlternatives.coerceIn(1, 3)
        val bases = ProviderConfig.OSRM_BASES.distinct()
        if (bases.isEmpty()) throw IllegalStateException("No routing providers configured")

        data class EndpointResult(val base: String, val routes: List<Route>)
        val completion = ExecutorCompletionService<EndpointResult>(ENDPOINT_EXECUTOR)
        val futures = ArrayList<Future<EndpointResult>>(bases.size)
        bases.forEach { base ->
            futures += completion.submit(Callable {
                EndpointResult(base, fetchRoutesWithAlternativeFallback(base, coords, destination, requested))
            })
        }
        try {

            val merged = ArrayList<Route>()
            val endpoints = ArrayList<String>()
            val failures = ArrayList<String>()
            val started = System.currentTimeMillis()
            var completed = 0
            var firstSuccessAt = 0L

            while (completed < bases.size) {
                val now = System.currentTimeMillis()
                val overallRemaining = ROUTING_OVERALL_DEADLINE_MS - (now - started)
                if (overallRemaining <= 0L) break
                val waitMs = if (firstSuccessAt == 0L) {
                    overallRemaining
                } else {
                    minOf(overallRemaining, SECOND_BACKEND_GRACE_MS - (now - firstSuccessAt))
                }
                if (waitMs <= 0L) break
                val future = completion.poll(waitMs, TimeUnit.MILLISECONDS) ?: break
                completed++
                try {
                    val result = future.get()
                    if (result.routes.isNotEmpty()) {
                        if (firstSuccessAt == 0L) firstSuccessAt = System.currentTimeMillis()
                        endpoints += result.base
                        result.routes.forEach { route ->
                            val duplicate = merged.indexOfFirst { existing ->
                                RouteGeometry.equivalent(existing, route)
                            }
                            if (duplicate < 0) {
                                merged += route
                            } else if (route.durationSeconds < merged[duplicate].durationSeconds) {
                                merged[duplicate] = route
                            }
                        }
                    }
                } catch (e: Exception) {
                    val cause = e.cause ?: e
                    failures += cause.message ?: "Routing endpoint failed"
                }
            }

            if (merged.isEmpty()) {
                lastError = failures.joinToString("; ").ifBlank { "Routing provider unavailable" }
                throw IllegalStateException(lastError)
            }
            lastEndpoint = endpoints.joinToString(", ")
            lastError = if (failures.isEmpty()) null else failures.joinToString("; ")
            return RouteGeometry.deduplicate(merged)
                .sortedBy { it.durationSeconds }
                .take(requested)
                .mapIndexed { index, route -> route.copy(alternativeIndex = index) }
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
        }
    }

    private fun fetchRoutesWithAlternativeFallback(
        base: String,
        coords: String,
        destination: SearchResult,
        requested: Int
    ): List<Route> {
        val modes = LinkedHashSet<String>()
        if (requested <= 1) {
            modes += "false"
        } else {
            modes += requested.toString()
            modes += "true"
            modes += "false"
        }

        var last: Exception? = null
        for (mode in modes) {
            try {
                return fetchRoutes(base, coords, destination, mode)
            } catch (e: Exception) {
                last = e
                if (!isAlternativeLimitFailure(e)) throw e
            }
        }
        throw last ?: IllegalStateException("No route found")
    }

    private fun isAlternativeLimitFailure(e: Exception): Boolean {
        val message = e.message.orEmpty().lowercase(Locale.US)
        return message.contains("alternative") && (
            message.contains("maximum") ||
                message.contains("higher") ||
                message.contains("limit") ||
                message.contains("http 400")
            )
    }

    private fun fetchRoutes(base: String, coords: String, destination: SearchResult, alternatives: String): List<Route> {
        val url = "$base/route/v1/driving/$coords?overview=full&geometries=geojson&steps=true&alternatives=$alternatives"
        val root = JSONObject(HttpClient.get(url, timeoutMs = ENDPOINT_TIMEOUT_MS, attempts = 1))
        if (root.optString("code") != "Ok") {
            throw IllegalStateException(root.optString("message", "No route found"))
        }
        val array = root.getJSONArray("routes")
        if (array.length() == 0) throw IllegalStateException("No route found")
        val out = ArrayList<Route>(array.length())
        for (i in 0 until array.length()) out += parseRoute(array.getJSONObject(i), destination, base, i)
        return out
    }

    /** Shared OSRM-format decoder; Valhalla's OSRM output preserves the existing maneuver UI. */
    internal fun parseRoute(r: JSONObject, destination: SearchResult, base: String, alternativeIndex: Int): Route {
        val geom = r.getJSONObject("geometry").getJSONArray("coordinates")
        val points = ArrayList<GeoPoint>(geom.length())
        for (i in 0 until geom.length()) {
            val c = geom.getJSONArray(i)
            points += GeoPoint(c.getDouble(1), c.getDouble(0))
        }

        val steps = ArrayList<RouteStep>()
        val legs = r.getJSONArray("legs")
        for (li in 0 until legs.length()) {
            val sa = legs.getJSONObject(li).getJSONArray("steps")
            for (si in 0 until sa.length()) {
                val s = sa.getJSONObject(si)
                val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                val name = s.optString("name", "")
                val type = m.optString("type", "continue")
                val mod = m.optString("modifier", "")
                val ref = s.optString("ref", "")
                val destinations = s.optString("destinations", "")
                val exits = s.optString("exits", "")
                val rotaryName = s.optString("rotary_name", "")
                val drivingSide = s.optString("driving_side", "")
                val lanes = ArrayList<LaneGuidance>()
                val intersections = s.optJSONArray("intersections")
                if (intersections != null && intersections.length() > 0) {
                    val intersection = intersections.optJSONObject(intersections.length() - 1)
                    val laneArray = intersection?.optJSONArray("lanes")
                    if (laneArray != null) {
                        for (laneIndex in 0 until laneArray.length()) {
                            val lane = laneArray.optJSONObject(laneIndex) ?: continue
                            val indications = ArrayList<String>()
                            val indicationArray = lane.optJSONArray("indications")
                            if (indicationArray != null) {
                                for (j in 0 until indicationArray.length()) {
                                    indicationArray.optString(j).takeIf { it.isNotBlank() }?.let(indications::add)
                                }
                            }
                            lanes += LaneGuidance(indications, lane.optBoolean("valid", false), lane.optBoolean("active", false))
                        }
                    }
                }
                steps += RouteStep(
                    instruction = instruction(type, mod, name, ref, destinations, exits, rotaryName),
                    maneuver = GeoPoint(loc.getDouble(1), loc.getDouble(0)),
                    distanceMeters = s.optDouble("distance", 0.0),
                    maneuverType = type,
                    maneuverModifier = mod,
                    roadName = name,
                    roadRef = ref,
                    destinations = destinations,
                    exits = exits,
                    rotaryName = rotaryName,
                    drivingSide = drivingSide,
                    bearingBefore = m.optDouble("bearing_before").takeUnless { it.isNaN() },
                    bearingAfter = m.optDouble("bearing_after").takeUnless { it.isNaN() },
                    lanes = lanes
                )
            }
        }

        return Route(
            points = points,
            steps = steps,
            distanceMeters = r.getDouble("distance"),
            durationSeconds = r.getDouble("duration"),
            destination = destination,
            provider = base,
            alternativeIndex = alternativeIndex
        )
    }

    private fun instruction(
        type: String,
        modifier: String,
        road: String,
        ref: String,
        destinations: String,
        exits: String,
        rotaryName: String
    ): String {
        val routeName = listOf(ref, road).filter { it.isNotBlank() }.distinct().joinToString(" / ")
        val onto = if (routeName.isBlank()) "" else " onto $routeName"
        val toward = destinations.takeIf { it.isNotBlank() }?.let { " toward $it" }.orEmpty()
        val exitPrefix = exits.takeIf { it.isNotBlank() }?.let { "Take exit $it" }
        return when (type) {
            "depart" -> "Start${if (modifier.isBlank()) "" else " $modifier"}$onto"
            "arrive" -> "Arrive at destination"
            "turn" -> "Turn ${modifier.ifBlank { "ahead" }}$onto$toward"
            "fork" -> "Keep ${modifier.ifBlank { "ahead" }}$onto$toward"
            "merge" -> "Merge ${modifier.ifBlank { "ahead" }}$onto$toward"
            "roundabout", "rotary" -> "Enter ${rotaryName.ifBlank { "roundabout" }}$toward${if (road.isBlank()) "" else " onto $road"}"
            "on ramp" -> "Take ramp ${modifier.ifBlank { "ahead" }}$onto$toward"
            "off ramp" -> "${exitPrefix ?: "Take exit ${modifier.ifBlank { "ahead" }}"}$onto$toward"
            "end of road" -> "At end of road, turn ${modifier.ifBlank { "ahead" }}$onto$toward"
            "new name", "continue" -> "Continue${if (modifier.isBlank()) "" else " $modifier"}$onto$toward"
            else -> "Continue$onto$toward"
        }
    }
    private companion object {
        private val ENDPOINT_THREAD_COUNTER = AtomicInteger(0)
        private val ENDPOINT_EXECUTOR = Executors.newFixedThreadPool(MAX_ENDPOINT_WORKERS) { runnable ->
            Thread(runnable, "watchman-osrm-${ENDPOINT_THREAD_COUNTER.incrementAndGet()}").apply { isDaemon = true }
        }
        const val MAX_ENDPOINT_WORKERS = 4
        const val ROUTING_OVERALL_DEADLINE_MS = 9_500L
        const val SECOND_BACKEND_GRACE_MS = 650L
        const val ENDPOINT_TIMEOUT_MS = 8_500
    }

}
