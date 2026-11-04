package com.bggg.watchman

import com.bggg.watchman.data.*
import com.bggg.watchman.routing.*
import com.bggg.watchman.storage.DeviceIdentity
import com.bggg.watchman.storage.RoutingDeviceSnapshot
import com.bggg.watchman.util.Geo
import kotlin.random.Random

fun roadDeviceExposureSmoke() {
    val forward = MotorcarTagPolicy.Direction.FORWARD
    val backward = MotorcarTagPolicy.Direction.BACKWARD
    fun device(id: String, point: GeoPoint, type: CameraType = CameraType.TRAFFIC,
               tags: Map<String, String> = mapOf("highway" to "traffic_signals")) = CameraPoi(
        id, type, point, null, null, "180", null, null, sourceId = "osm_national",
        sourceRecordId = id, tags = tags, lastUpdatedMillis = 1234
    )
    val from = RoadSnapGeometry.Point(-75.0, 40.0)
    val to = RoadSnapGeometry.Point(-74.94, 40.0)
    fun at(fraction: Double) = RoadSnapGeometry.pointAt(from, to, fraction).let { GeoPoint(it.latitude, it.longitude) }
    val fractions = listOf(.15, .24, .25, .5, .75, .85)
    val input = fractions.mapIndexed { i, fraction -> device("node/${i+1}", at(fraction)) }.toMutableList()
    input += device("node/100", at(.5), CameraType.OTHER, emptyMap())
    val rawTags = linkedMapOf("model" to "RF", "sensor" to "radio")
    input += device("node/101", at(.5), CameraType.RF_SENSOR, rawTags)
    val snapshot = RoutingDeviceSnapshot.freeze(input, "source-bound test search region")
    val reordered = RoutingDeviceSnapshot.freeze(input.reversed(), snapshot.queryScope)
    check(reordered.revision == snapshot.revision && snapshot.revision.length == 64)
    val original = input.toList()
    rawTags["model"] = "changed after freeze"; input.clear()
    check(snapshot.entries.first { it.logicalId == "osm:node/101" }.device.tags["model"] == "RF")
    check(runCatching { (snapshot.entries as MutableList).clear() }.isFailure)
    check(runCatching { (snapshot.entries.first().device.tags as MutableMap)["x"] = "y" }.isFailure)
    check(RoutingDeviceSnapshot.freeze(original, "different scope").revision != snapshot.revision)
    check(RoutingDeviceSnapshot.freeze(original.map { it.copy(confidence = it.confidence + 1) }, snapshot.queryScope).revision != snapshot.revision)
    check(runCatching { RoutingDeviceSnapshot.freeze(original + original.first(), snapshot.queryScope) }.isFailure)
    check(runCatching { RoutingDeviceSnapshot.freeze(original, snapshot.queryScope, maximumDevices = 1) }.isFailure)
    check(runCatching { RoutingDeviceSnapshot.freeze(original, snapshot.queryScope, maximumMetadataCharacters = 1) }.isFailure)
    check(runCatching { RoutingDeviceSnapshot.freeze(listOf(device("node/bad", GeoPoint(Double.NaN, 0.0))), "bad") }.isFailure)
    check(DeviceIdentity.logicalKey(original.first().copy(sourceId = "osm_overpass")) == DeviceIdentity.logicalKey(original.first()))
    check(DeviceIdentity.logicalKey(original.first().copy(id = "agency/1", sourceRecordId = "1")) != DeviceIdentity.logicalKey(original.first()))

    val index = RoadDeviceExposureIndex(snapshot, "graph-1")
    val geometry = RoadDeviceExposureIndex.Geometry("graph-1", 1, forward, from, to)
    val partial = index.exposures(geometry, RoadArcPortion(1, .25, .75))
    // Immediately before departure is still within180m: no blanket endpoint exclusion.
    check(partial.map { it.deviceId }.toSet() == setOf("osm:node/2", "osm:node/3", "osm:node/4", "osm:node/5", "osm:node/101"))
    check(partial.all { it.weight == 1L })
    val reverse = index.exposures(RoadDeviceExposureIndex.Geometry("graph-1", 2, backward, to, from), RoadArcPortion(2, .25, .75, backward))
    check(reverse == partial)
    val all = index.exposures(geometry, RoadArcPortion(1, 0.0, 1.0))
    check(all.size == 7)
    val route = Route(listOf(GeoPoint(40.0, -75.0), GeoPoint(40.0, -74.94)), emptyList(), 5000.0, 400.0,
        SearchResult("target", GeoPoint(40.0, -74.94)))
    check(RouteAnalyzer.countKnownCameras(route, snapshot.entries.map { it.device }) == all.size)
    val left = index.exposures(geometry, RoadArcPortion(1, 0.0, .5))
    val right = index.exposures(geometry, RoadArcPortion(1, .5, 1.0))
    check((left + right).map { it.deviceId }.toSet() == all.map { it.deviceId }.toSet())
    check(runCatching { index.exposures(geometry.copy(graphRevision = "changed"), RoadArcPortion(1, 0.0, 1.0)) }.isFailure)
    check(runCatching { index.exposures(geometry, RoadArcPortion(2, 0.0, 1.0)) }.isFailure)
    check(runCatching { index.exposures(geometry, RoadArcPortion(1, 0.0, 1.0, backward)) }.isFailure)
    check(runCatching { RoadDeviceExposureIndex(snapshot, "graph-1", 1).exposures(geometry, RoadArcPortion(1, 0.0, 1.0)) }.isFailure)
    check(RoadSnapGeometry.pointAt(from, to, 0.0) == from && RoadSnapGeometry.pointAt(from, to, 1.0) == to)
    check(runCatching { RoadSnapGeometry.pointAt(RoadSnapGeometry.Point(0.0, 0.0), RoadSnapGeometry.Point(180.0, 0.0), .5) }.isFailure)

    val random = Random(381279)
    var compared = 0
    fun near(lon: Double, reference: Double): Double {
        var value = lon
        while (value - reference > 180) value -= 360
        while (value - reference < -180) value += 360
        return value
    }
    fun normalized(lon: Double): Double = near(lon, 0.0)
    // Independent exhaustive metric checks: no bounding box, latitude index or candidate cap.
    repeat(100) { case ->
        val lat = when (case % 4) { 0 -> 89.5; 1 -> -89.5; else -> random.nextDouble(-75.0, 75.0) }
        val lon = if (case % 5 == 0) 179.95 else random.nextDouble(-170.0, 170.0)
        val a = RoadSnapGeometry.Point(lon, lat)
        val b = RoadSnapGeometry.Point(normalized(lon + random.nextDouble(.03, .3)), (lat + random.nextDouble(-.05, .05)).coerceIn(-89.8, 89.8))
        val f1 = random.nextDouble(0.0, .3); val f2 = random.nextDouble(.7, 1.0)
        val p1 = RoadSnapGeometry.pointAt(a, b, f1); val p2 = RoadSnapGeometry.pointAt(a, b, f2)
        val bLon = near(p2.longitude, p1.longitude); val middleLon = (p1.longitude + bLon) / 2
        val devices = (0 until 180).map { i ->
            val p = RoadSnapGeometry.pointAt(a, b, random.nextDouble())
            device("node/${case * 1000 + i + 1}", GeoPoint((p.latitude + random.nextDouble(-.005, .005)).coerceIn(-90.0, 90.0),
                normalized(p.longitude + random.nextDouble(-.04, .04))))
        }
        val frozen = RoutingDeviceSnapshot.freeze(devices, "oracle-region-$case")
        val actual = RoadDeviceExposureIndex(frozen, "oracle-graph").exposures(
            RoadDeviceExposureIndex.Geometry("oracle-graph", 1, forward, a, b), RoadArcPortion(1, f1, f2)).map { it.deviceId }.toSet()
        val expected = devices.filter {
            Geo.pointToSegmentMeters(GeoPoint(it.point.lat, near(it.point.lon, middleLon)),
                GeoPoint(p1.latitude, p1.longitude), GeoPoint(p2.latitude, bLon)) <= 180.0
        }.map(DeviceIdentity::logicalKey).toSet()
        check(actual == expected) { "Exposure index disagrees with exhaustive metric in case$case" }
        compared += devices.size
        val midpoint = RoadSnapGeometry.pointAt(a, b, .5)
        val projection = checkNotNull(RoadSnapGeometry.project(midpoint, a, b).projection)
        check(kotlin.math.abs(projection.fraction - .5) < 1e-8 && projection.distanceMetres < 1e-6)
    }
    println("Road device exposure: immutable canonical snapshot/content hash, preserved traffic/unit weights/180m metric, original forward/reverse partial endpoints, duplicate/budget/source guards and$compared exhaustive polar/dateline candidate comparisons PASS; acquisition completeness/timing/provider acceptance remain separate")
}
