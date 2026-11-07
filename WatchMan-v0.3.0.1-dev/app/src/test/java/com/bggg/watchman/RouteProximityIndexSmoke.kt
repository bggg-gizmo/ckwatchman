package com.bggg.watchman

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.data.SearchResult
import com.bggg.watchman.routing.RouteProximityIndex
import com.bggg.watchman.util.Geo
import kotlin.random.Random

/** Synthetic geometry only. Brute-force segment distance is the independent lookup oracle. */
fun routeProximityIndexRegressions() {
    fun route(points: List<GeoPoint>) = Route(points, emptyList(), 0.0, 0.0,
        SearchResult("SYNTHETIC_ENDPOINT", points.last()))
    fun near(lon: Double, reference: Double): Double {
        var value = lon
        while (value - reference > 180.0) value -= 360.0
        while (value - reference < -180.0) value += 360.0
        return value
    }
    fun oracle(points: List<GeoPoint>, query: GeoPoint, radius: Double): Boolean =
        points.zipWithNext().any { (a, b) ->
            val end = GeoPoint(b.lat, near(b.lon, a.lon))
            val p = GeoPoint(query.lat, near(query.lon, (a.lon + end.lon) / 2.0))
            Geo.pointToSegmentMeters(p, a, end) <= radius
        }
    fun compare(points: List<GeoPoint>, query: GeoPoint, expected: Boolean? = null) {
        val truth = oracle(points, query, 180.0)
        if (expected != null) check(truth == expected) { "Bad synthetic oracle fixture" }
        check(RouteProximityIndex(route(points), 180.0).isWithin(query) == truth) {
            "Route lookup disagrees with independent exact segment check"
        }
    }
    compare(listOf(GeoPoint(0.0483822, 0.0), GeoPoint(0.0483822, 0.01)), GeoPoint(0.05, 0.005), true)
    compare(listOf(GeoPoint(89.9, 1.0), GeoPoint(89.9, 1.001)), GeoPoint(89.9, 1.5), true)
    compare(listOf(GeoPoint(-89.9, 1.0), GeoPoint(-89.9, 1.001)), GeoPoint(-89.9, 1.5), true)
    compare(listOf(GeoPoint(0.0, 179.9), GeoPoint(0.0, -179.9)), GeoPoint(0.001, -180.0), true)
    compare(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 120.0), GeoPoint(0.0, -120.0),
        GeoPoint(0.0, 0.0), GeoPoint(10.0, 0.0), GeoPoint(10.0, 120.0)), GeoPoint(10.0, 45.0), true)
    // Very long/diagonal segments must use exact checking, never incomplete capped sampling.
    compare(listOf(GeoPoint(-70.0, -80.0), GeoPoint(70.0, 80.0)), GeoPoint(0.001, 0.0), true)
    val random = Random(84912)
    repeat(600) {
        val lat = random.nextDouble(-89.999, 89.999)
        val lon = random.nextDouble(-180.0, 180.0)
        val points = listOf(GeoPoint(lat, lon), GeoPoint((lat + random.nextDouble(-0.12, 0.12)).coerceIn(-90.0, 90.0),
            near(lon + random.nextDouble(-0.12, 0.12), 0.0)))
        val query = GeoPoint((lat + random.nextDouble(-0.004, 0.004)).coerceIn(-90.0, 90.0),
            near(lon + random.nextDouble(-0.2, 0.2), 0.0))
        compare(points, query)
    }
    val longPath = (0..4096).map { GeoPoint(0.0, it * 0.0001) }
    var calls = 0
    check(runCatching { RouteProximityIndex(route(longPath), 180.0) {
        if (++calls == 4) error("CANCELLED_INDEX")
    } }.exceptionOrNull()?.message == "CANCELLED_INDEX")
    var expired = false
    val cancellable = RouteProximityIndex(route(longPath), 180.0) { check(!expired) { "EXPIRED_QUERY" } }
    expired = true
    check(runCatching { cancellable.isWithin(longPath.last()) }.exceptionOrNull()?.message == "EXPIRED_QUERY")
    calls = 0
    check(runCatching { Geo.projectOntoRoute(longPath.last(), longPath) {
        if (++calls == 4) error("CANCELLED_PROJECTION")
    } }.exceptionOrNull()?.message == "CANCELLED_PROJECTION")
    check(calls == 4)
    // Adversarial, disconnected-looking synthetic geometry forces exact overflow handling.
    // The index must stay bounded AND still find the last segment after its cell budget fills.
    val spread = (0..12_000).map { GeoPoint(-60.0 + (it / 100) * 0.5, -100.0 + (it % 100) * 0.5) }
    val bounded = RouteProximityIndex(route(spread), 180.0)
    check(bounded.indexedCellCount <= RouteProximityIndex.MAX_INDEXED_CELLS)
    check(bounded.indexedSegmentReferences <= RouteProximityIndex.MAX_INDEXED_REFERENCES)
    check(bounded.isWithin(spread.last()))
    println("Route proximity lookup: boundary/poles/seam/world-copy/long segment, 600 oracle comparisons, cancellation and bounded exact overflow PASS")
}
