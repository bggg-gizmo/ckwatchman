package com.bggg.watchman

import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.data.SearchResult
import com.bggg.watchman.storage.DeviceSearchRegion
import com.bggg.watchman.routing.RoadSnapGeometry
import com.bggg.watchman.util.Geo
import kotlin.math.*

fun deviceSearchRegionSmoke() {
    onlineCandidateRegionSmoke()
    val ordinary = DeviceSearchRegion.bounds("graph-fixture", "Declared whole synthetic graph plus exposure halo",
        -2.0, -3.0, 4.0, 5.0)
    val wrapped = DeviceSearchRegion.bounds("graph-fixture", "Declared antimeridian test search region",
        -2.0, 170.0, 4.0, -170.0)
    check(wrapped.windows.size == 2)
    for (latitude in -5..7) for (longitude in -180..180) {
        val point = GeoPoint(latitude.toDouble(), longitude.toDouble())
        check(ordinary.contains(point) == (latitude in -2..4 && longitude in -3..5))
        check(wrapped.contains(point) == (latitude in -2..4 && (longitude >= 170 || longitude <= -170)))
    }
    check(wrapped.contains(GeoPoint(0.0, 180.0)) && wrapped.contains(GeoPoint(0.0, -180.0)))
    val reordered = DeviceSearchRegion(wrapped.graphRevision, wrapped.evidence, wrapped.windows.reversed())
    check(reordered.queryScope == wrapped.queryScope)
    check(DeviceSearchRegion(wrapped.graphRevision, wrapped.evidence, wrapped.windows + wrapped.windows).queryScope == wrapped.queryScope)
    check(runCatching { (wrapped.windows as MutableList<DeviceSearchRegion.Window>).clear() }.isFailure)
    fun reject(block: () -> Unit) = check(runCatching(block).isFailure)
    reject { DeviceSearchRegion.Window(Double.NaN, 0.0, 1.0, 1.0) }
    reject { DeviceSearchRegion.Window(-91.0, 0.0, 1.0, 1.0) }
    reject { DeviceSearchRegion.Window(0.0, 170.0, 1.0, -170.0) }
    reject { DeviceSearchRegion("", "evidence", wrapped.windows) }
    reject { DeviceSearchRegion("graph", "", wrapped.windows) }
    reject { DeviceSearchRegion("graph", "evidence", emptyList()) }
    reject { DeviceSearchRegion("graph", "evidence", List(17) { wrapped.windows.first() }) }

    fun device(id: String, point: GeoPoint) = CameraPoi(id, CameraType.ALPR, point,
        null, null, null, null, null, sourceId = "synthetic-region", sourceRecordId = id)
    val observations = listOf(device("west", GeoPoint(0.0, -175.0)), device("east", GeoPoint(0.0, 175.0)),
        device("outside", GeoPoint(0.0, 0.0)))
    val budgets = ArrayList<Int>()
    val result = wrapped.collect(2) { window, remaining ->
        budgets += remaining
        observations.filter { window.contains(it.point) }
    }
    check(result.map { it.id }.toSet() == setOf("west", "east") && budgets == listOf(2, 1))
    reject { wrapped.collect(1) { window, _ -> observations.filter { window.contains(it.point) } } }
    reject { wrapped.collect(2, { true }) { _, _ -> error("Must not read after cancellation") } }
    reject { wrapped.collect(2) { _, _ -> error("Synthetic cache read failure") } }
    reject { wrapped.collect(2) { _, _ -> listOf(observations.last()) } }
    val overlap = DeviceSearchRegion("graph", "Overlap counts raw rows, not hidden truncation",
        listOf(DeviceSearchRegion.Window(-2.0, -3.0, 4.0, 5.0), DeviceSearchRegion.Window(-1.0, -2.0, 3.0, 4.0)))
    reject { overlap.collect(1) { _, _ -> listOf(observations.last()) } }
    var cancelled = false
    reject { ordinary.collect(2, { cancelled }) { _, _ -> cancelled = true; listOf(observations.last()) } }
    // Independent direct-spherical formula; do not reuse queryBoxes/pointAt to generate oracles.
    val random = java.util.Random(51005)
    var capComparisons = 0
    repeat(3000) {
        val origin = RoadSnapGeometry.Point(random.nextDouble() * 360 - 180, random.nextDouble() * 180 - 90)
        val distanceMm = (random.nextDouble() * 10000000000L).toLong()
        val region = DeviceSearchRegion.forSearch("independent-source-cap", origin, distanceMm, 1000, 180.0)
        val latitude = Math.toRadians(origin.latitude)
        val longitude = Math.toRadians(origin.longitude)
        for (fraction in listOf(0.0, .5, 1.0)) for (bearing in listOf(0.0, 90.0, 180.0, 270.0)) {
            val direction = Math.toRadians(bearing)
            // Include endpoint/device-halo angles measured with the exposure helper's radius.
            val angle = (distanceMm / 1000.0 * fraction) / 6371008.8 + 180.0 / 6371000.0
            val lat = asin((sin(latitude) * cos(angle) + cos(latitude) * sin(angle) * cos(direction)).coerceIn(-1.0, 1.0))
            val lon = longitude + atan2(sin(direction) * sin(angle) * cos(latitude), cos(angle) - sin(latitude) * sin(lat))
            val normalizedLon = ((Math.toDegrees(lon) + 540.0) % 360.0) - 180.0
            check(region.contains(GeoPoint(Math.toDegrees(lat), normalizedLon))) { "Independent feasible path/device cap omitted: $origin,$distanceMm,$fraction,$bearing" }
            capComparisons++
        }
    }
    val globe = DeviceSearchRegion.forSearch("global-source", RoadSnapGeometry.Point(0.0, 0.0), Long.MAX_VALUE, 1000, 180.0)
    check(globe.contains(GeoPoint(90.0, 180.0)) && globe.contains(GeoPoint(-90.0, -180.0)))
    // Independent spherical source lengths/interpolation, then the ACTUAL planar exposure
    // metric on source-bound partial chords. Do not reuse pointAt/queryBoxes for the oracle.
    fun normalized(longitude:Double)=((longitude+540.0)%360.0)-180.0
    fun near(longitude:Double,reference:Double):Double {
        var value=longitude
        while(value-reference>180.0)value-=360.0
        while(value-reference< -180.0)value+=360.0
        return value
    }
    fun sourceMetres(a:GeoPoint,b:GeoPoint):Double {
        val latA=Math.toRadians(a.lat);val latB=Math.toRadians(b.lat)
        val h=sin((latB-latA)/2).pow(2)+cos(latA)*cos(latB)*
            sin(Math.toRadians(b.lon-a.lon)/2).pow(2)
        return 2*6371008.8*atan2(sqrt(h.coerceIn(0.0,1.0)),sqrt((1-h).coerceIn(0.0,1.0)))
    }
    fun partial(a:GeoPoint,b:GeoPoint,fraction:Double):GeoPoint {
        if(fraction==0.0)return a
        if(fraction==1.0)return b
        val angle=sourceMetres(a,b)/6371008.8
        if(angle<1e-14)return a
        fun vector(p:GeoPoint):DoubleArray {
            val lat=Math.toRadians(p.lat);val lon=Math.toRadians(p.lon)
            return doubleArrayOf(cos(lat)*cos(lon),cos(lat)*sin(lon),sin(lat))
        }
        val av=vector(a);val bv=vector(b)
        val left=sin((1-fraction)*angle)/sin(angle);val right=sin(fraction*angle)/sin(angle)
        val x=av[0]*left+bv[0]*right;val y=av[1]*left+bv[1]*right;val z=av[2]*left+bv[2]*right
        return GeoPoint(Math.toDegrees(atan2(z,hypot(x,y))),Math.toDegrees(atan2(y,x)))
    }
    var planarComparisons=0
    repeat(400) { case ->
        val initial=GeoPoint(when(case%5) { 0->88.0;1-> -88.0;else->random.nextDouble()*160-80 },
            when(case%4) { 0->179.99;1-> -179.99;else->random.nextDouble()*360-180 })
        val original=ArrayList<GeoPoint>();original+=initial
        repeat(6) {
            val prior=original.last()
            original+=GeoPoint((prior.lat+(random.nextDouble()-.5)*.4).coerceIn(-89.0,89.0),
                normalized(prior.lon+(random.nextDouble()-.5)*.8))
        }
        val start=partial(original[0],original[1],.25)
        val portions=(0..5).map { i ->
            (if(i==0)start else original[i]) to
                (if(i==5)partial(original[i],original[i+1],.8) else original[i+1])
        }
        val distanceMm=(0..5).sumOf { i ->
            ((sourceMetres(original[i],original[i+1])*1000).roundToLong()*
                (if(i==0).75 else if(i==5).8 else 1.0)).roundToLong()
        }
        val region=DeviceSearchRegion.forSearch("planar-source-path",RoadSnapGeometry.Point(start.lon,start.lat),
            distanceMm,100,180.0)
        for((rawA,rawB) in portions) {
            val a=rawA;val b=GeoPoint(rawB.lat,near(rawB.lon,a.lon))
            for(t in listOf(0.0,.5,1.0))for(degrees in listOf(0.0,90.0,180.0,270.0)) {
                val p=GeoPoint(a.lat+(b.lat-a.lat)*t,a.lon+(b.lon-a.lon)*t)
                val angle=Math.toRadians(degrees)
                // Slightly INSIDE the existing180m predicate; avoid exact-boundary rounding.
                val latitude=p.lat+Math.toDegrees(179.999*sin(angle)/6371000.0)
                val longitude=p.lon+Math.toDegrees(179.999*cos(angle)/(6371000.0*cos(Math.toRadians(latitude))))
                val query=GeoPoint(latitude,longitude)
                check(Geo.pointToSegmentMeters(query,a,b)<=180.0)
                check(region.contains(GeoPoint(latitude,normalized(longitude)))) {
                    "Planar full/partial/dateline/high-latitude device omitted: $case,$a,$b,$query,$distanceMm"
                }
                planarComparisons++
            }
        }
    }
    // The API accepts large halo values too. 2m cannot replace an explicit Earth-radius
    // conversion when the difference exceeds2m; independent angular endpoints stay covered.
    for(halo in listOf(2_000_000.0,5_000_000.0,9_000_000.0)) {
        val region=DeviceSearchRegion.forSearch("large-planar-halo",RoadSnapGeometry.Point(0.0,0.0),0,1,halo)
        val latitude=Math.toDegrees(halo/6371000.0)
        check(region.contains(GeoPoint(latitude,0.0)) && region.contains(GeoPoint(-latitude,0.0)))
    }
    reject { DeviceSearchRegion.forSearch("graph", RoadSnapGeometry.Point(0.0, 0.0), -1, 1000, 180.0) }
    reject { DeviceSearchRegion.forSearch("graph", RoadSnapGeometry.Point(0.0, 0.0), 1000, 0, 180.0) }
    reject { DeviceSearchRegion.forSearch("graph", RoadSnapGeometry.Point(0.0, 0.0), 1000, 1000, Double.NaN) }
    println("Full-search-region device contract: 9386 independent window comparisons,$capComparisons independent spherical path/halo cap comparisons,$planarComparisons original-source partial/chord planar exposure comparisons, inclusive bounds/antimeridian/high latitudes/large halos, canonical immutable scope, all declared windows, raw-row budget/no truncation, cancellation and unavailable/out-of-region refusal PASS; actual SQLite/layer/graph-region coverage requires separate Android/provider evidence")
}

private fun onlineCandidateRegionSmoke() {
    val points = (0..1000).map { GeoPoint(0.0, it * 0.01) } +
        (1..1000).map { GeoPoint(it * 0.01, 10.0) }
    val endpoints = listOf(GeoPoint(-0.0005, -0.0005), GeoPoint(10.0005, 10.0005))
    val route = Route(points, emptyList(), 0.0, 0.0, SearchResult("SYNTHETIC_DESTINATION", endpoints.last()))
    val region = DeviceSearchRegion.forOnlineRoute(route, endpoints, 180.0)
    check(region.windows.size in 2..16)
    check(!region.contains(GeoPoint(5.0, 5.0))) // Not the entire bent-route rectangle.
    check(region.queryScope == DeviceSearchRegion.forOnlineRoute(route, endpoints, 180.0).queryScope)
    var comparisons = 0
    for ((a, b) in points.zipWithNext()) {
        for (fraction in listOf(0.0, 0.5, 1.0)) for (angle in listOf(0.0, 90.0, 180.0, 270.0)) {
            val center = GeoPoint(a.lat + (b.lat - a.lat) * fraction, a.lon + (b.lon - a.lon) * fraction)
            val radians = Math.toRadians(angle)
            val lat = center.lat + Math.toDegrees(179.99 * sin(radians) / 6_371_000.0)
            val lon = center.lon + Math.toDegrees(179.99 * cos(radians) / (6_371_000.0 * cos(Math.toRadians(lat))))
            val query = GeoPoint(lat, lon)
            check(Geo.pointToSegmentMeters(query, a, b) <= 180.0)
            check(region.contains(query)) { "A candidate segment/halo was omitted" }
            comparisons++
        }
    }
    for (endpoint in endpoints) for (angle in listOf(0.0, 90.0, 180.0, 270.0)) {
        val radians = Math.toRadians(angle)
        val lat = endpoint.lat + Math.toDegrees(179.99 * sin(radians) / 6_371_000.0)
        val lon = endpoint.lon + Math.toDegrees(179.99 * cos(radians) / (6_371_000.0 * cos(Math.toRadians(lat))))
        check(region.contains(GeoPoint(lat, lon)))
    }
    val sparse = route.copy(points = listOf(points.first(), points.last()))
    check(DeviceSearchRegion.forOnlineRoute(sparse, endpoints, 180.0).contains(GeoPoint(5.0, 5.0)))
    val identical = route.copy(points = List(100) { GeoPoint(0.0, 0.0) })
    check(DeviceSearchRegion.forOnlineRoute(identical, emptyList(), 180.0).contains(GeoPoint(0.0, 0.001)))
    for (latitude in listOf(-89.999, 0.0, 89.999)) {
        val seam = route.copy(points = listOf(GeoPoint(latitude, 179.99), GeoPoint(latitude, -179.99)))
        val window = DeviceSearchRegion.forOnlineRoute(seam, emptyList(), 180.0)
        check(window.contains(GeoPoint(latitude, 180.0)) && window.contains(GeoPoint(latitude, -180.0)))
    }
    var checks = 0
    check(runCatching { DeviceSearchRegion.forOnlineRoute(route, endpoints, 180.0) {
        if (++checks == 4) error("CANCELLED_WINDOW_PREPARATION")
    } }.exceptionOrNull()?.message == "CANCELLED_WINDOW_PREPARATION")
    check(checks == 4)
    println("Online candidate region: $comparisons segment/halo comparisons, endpoint disks, bounded windows, empty interior, sparse/seam/polar/zero-length/cancellation PASS")
}
