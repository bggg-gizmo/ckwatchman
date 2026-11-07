package com.bggg.watchman.routing

import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.cameras.CameraClassifier
import com.bggg.watchman.storage.RouteIdentity
import com.bggg.watchman.storage.RoutingDeviceSnapshot
import com.bggg.watchman.util.Geo
import java.util.Collections
import kotlin.math.abs

/** Road-association trial authorized by the user. Awareness retains the180m population.
 * Only source-connected, ordinary traffic-signal nodes currently admit a road-specific
 * exemption. Other camera/sensor families and missing/expired/ambiguous source coverage
 * stay conservative. This is not sensor FOV, road legality or an entrance certificate.
 */
object RoadAwareExposure {
    class Node(val id: Long, val point: GeoPoint, tags: Map<String, String>) {
        val tags: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(tags))
        init { require(id > 0 && valid(point) && tags.size <= 4096 &&
            tags.entries.sumOf { it.key.length.toLong()+it.value.length } <= 131072) }
    }
    class Way(val id: Long, nodeIds: List<Long>, val highway: String?) {
        val nodeIds: List<Long> = Collections.unmodifiableList(ArrayList(nodeIds))
        init { require(id > 0 && nodeIds.size in 2..100000 && nodeIds.all { it > 0 } &&
            (highway == null || highway.length in 1..256)) }
    }
    data class Span(val wayId: Long, val first: Int, val last: Int)
    class Observation(shape: List<GeoPoint>, spans: List<Span>, nodes: Collection<Node>, ways: Collection<Way>,
        completeParentNodeIds: Set<Long>, val sourceEvidence: String,
        val observedAtMillis: Long, val validUntilMillis: Long) {
        val shape: List<GeoPoint> = Collections.unmodifiableList(ArrayList(shape))
        val spans: List<Span> = Collections.unmodifiableList(ArrayList(spans))
        val nodes: Map<Long, Node> = Collections.unmodifiableMap(nodes.associateBy { it.id })
        val ways: Map<Long, Way> = Collections.unmodifiableMap(ways.associateBy { it.id })
        val completeParentNodeIds: Set<Long> = Collections.unmodifiableSet(HashSet(completeParentNodeIds))
        init {
            require(shape.size in 2..250000 && shape.all(::valid) && spans.size in 1..100000)
            require(nodes.size <= 20000 && ways.size <= 2048 && this.nodes.size == nodes.size && this.ways.size == ways.size)
            require(nodes.sumOf { n -> n.tags.entries.sumOf { it.key.length.toLong()+it.value.length } } <= 8000000)
            require(ways.sumOf { it.nodeIds.size.toLong() } <= 200000)
            require(completeParentNodeIds.size <= 512 && completeParentNodeIds.all { it > 0 })
            require(sourceEvidence.isNotBlank() && sourceEvidence.length <= 4096)
            require(observedAtMillis >= 0 && validUntilMillis > observedAtMillis && validUntilMillis-observedAtMillis <= 3600000)
        }
    }
    class Verified internal constructor(internal val routeIdentity: String, internal val observation: Observation,
        wayIds: LongArray, geometryVerified: BooleanArray, parents: Map<Long, Set<Long>>) {
        private val ways = wayIds.copyOf()
        private val matched = geometryVerified.copyOf()
        private val parentWays = Collections.unmodifiableMap(parents.mapValues { Collections.unmodifiableSet(HashSet(it.value)) })
        internal fun wayAt(index: Int) = ways[index]
        internal fun matchedAt(index: Int) = matched[index]
        internal fun parents(node: Long?) = parentWays[node].orEmpty()
    }
    data class Assessment(val nearbyRelevantDevices: List<CameraPoi>, val encounters: List<CameraPoi>,
        val awarenessOnlyDevices: List<CameraPoi>, val verifiedRoadEncounters: Int, val unknownEncounters: Int,
        val weightedExposure: Long, val snapshotRevision: String)

    /** Source IDs alone are insufficient: exact edge walking must cover every candidate
     * segment and each admitted source way must reproduce its actual geometry. A bad way
     * remains unverified locally, rather than granting exemptions or dropping that segment.
     */
    fun verify(route: Route, observation: Observation, nowMillis: Long,
        checkActive: () -> Unit = {}): Verified? {
        checkActive()
        if (!fresh(observation, nowMillis) || route.points.size != observation.shape.size) return null
        for (i in route.points.indices) {
            if (i % 128 == 0) checkActive()
            if (!valid(route.points[i]) || Geo.distanceMeters(route.points[i], observation.shape[i]) > 0.25) return null
        }
        val ids = LongArray(route.points.lastIndex)
        val matched = BooleanArray(ids.size)
        var covered = 0
        for (span in observation.spans) {
            checkActive()
            if (span.wayId <= 0 || span.first != covered || span.last !in span.first..route.points.lastIndex) return null
            val way = observation.ways[span.wayId]
            val source = way?.nodeIds?.mapNotNull { observation.nodes[it]?.point }
            val good = source != null && source.size == way.nodeIds.size &&
                sourceMatches(route.points.subList(span.first, span.last+1), source, checkActive)
            for (i in span.first until span.last) { ids[i] = span.wayId; matched[i] = good }
            covered = span.last
        }
        if (covered != route.points.lastIndex) return null
        val parents = HashMap<Long, MutableSet<Long>>()
        for (way in observation.ways.values) if (way.highway in MOTOR_ROADS) {
            for ((i, node) in way.nodeIds.withIndex()) {
                if (i % 128 == 0) checkActive()
                if (node in observation.completeParentNodeIds) parents.getOrPut(node) { HashSet() } += way.id
            }
        }
        checkActive()
        return Verified(RouteIdentity.sha256(route), observation, ids, matched, parents).also { checkActive() }
    }

    fun assess(route: Route, snapshot: RoutingDeviceSnapshot, verified: Verified?, nowMillis: Long,
        checkActive: () -> Unit = {}): Assessment {
        checkActive()
        val proof = verified?.takeIf { fresh(it.observation, nowMillis) && it.routeIdentity == RouteIdentity.sha256(route) }
        val index = RouteProximityIndex(route, snapshot.corridorMeters, checkActive)
        val nearby = ArrayList<CameraPoi>()
        val encounters = ArrayList<CameraPoi>()
        val awarenessOnly = ArrayList<CameraPoi>()
        var roadCount = 0
        var unknownCount = 0
        var weight = 0L
        for (entry in snapshot.entries) {
            checkActive()
            val camera = entry.device
            if (entry.weight <= 0 || !(index.isWithin(camera.point) ||
                    RouteAnalyzer.overlapsRequestedEndpoint(camera, route, snapshot.corridorMeters))) continue
            nearby += camera
            var roadDecision: Boolean? = null
            if (proof != null && camera.type == CameraType.TRAFFIC && camera.tags["highway"] == "traffic_signals" &&
                camera.manufacturer.isNullOrBlank() &&
                ordinarySignalTags(camera.tags)) {
                val nodeId = camera.sourceRecordId?.takeIf { NODE_ID.matches(it) }?.substringAfter('/')?.toLongOrNull()
                val node = proof.observation.nodes[nodeId]
                val parents = proof.parents(nodeId)
                if (nodeId in proof.observation.completeParentNodeIds && node != null && parents.isNotEmpty() &&
                    node.tags["highway"] == "traffic_signals" && ordinarySignalTags(node.tags) &&
                    Geo.distanceMeters(camera.point, node.point) <= 1.0) {
                    val segments = index.withinSegments(camera.point)
                    if (segments.isNotEmpty() && segments.all { proof.matchedAt(it) }) {
                        if (segments.any { proof.wayAt(it) in parents }) roadDecision = true
                        else if (separateParentGeometry(camera.point, route, segments, parents, proof.observation,
                                snapshot.corridorMeters, checkActive)) roadDecision = false
                    }
                }
            }
            if (roadDecision == false) awarenessOnly += camera else {
                encounters += camera
                weight = Math.addExact(weight, entry.weight)
                if (roadDecision == true) roadCount++ else unknownCount++
            }
        }
        checkActive()
        return Assessment(Collections.unmodifiableList(nearby), Collections.unmodifiableList(encounters),
            Collections.unmodifiableList(awarenessOnly), roadCount, unknownCount, weight, snapshot.revision)
    }

    private fun fresh(source: Observation, now: Long) = now >= source.observedAtMillis && now < source.validUntilMillis

    /** Exact edge walking of a shape cannot distinguish stacked/coincident roads by itself.
     * Before a negative exemption, require every affected parent geometry and reject any
     * nearby crossing/overlap. Missing geometry and grade ambiguity remain UNKNOWN.
     * Checking full local segments may over-reject a very long segment; never relax it.
     */
    private fun separateParentGeometry(camera: GeoPoint, route: Route, segments: List<Int>, parents: Set<Long>,
        source: Observation, radius: Double, active: () -> Unit): Boolean {
        data class XY(val x: Double, val y: Double)
        fun xy(p: GeoPoint): XY {
            var lon = p.lon-camera.lon
            while (lon > 180) lon -= 360
            while (lon < -180) lon += 360
            return XY(Math.toRadians(lon)*6371000.0*kotlin.math.cos(Math.toRadians(camera.lat)),
                Math.toRadians(p.lat-camera.lat)*6371000.0)
        }
        fun cross(a: XY, b: XY) = a.x*b.y-a.y*b.x
        fun subtract(a: XY, b: XY) = XY(a.x-b.x,a.y-b.y)
        fun distance(p: XY, a: XY, b: XY): Double {
            val d = subtract(b,a)
            val length = d.x*d.x+d.y*d.y
            val t = if (length == 0.0) 0.0 else (((p.x-a.x)*d.x+(p.y-a.y)*d.y)/length).coerceIn(0.0,1.0)
            return kotlin.math.hypot(p.x-a.x-t*d.x,p.y-a.y-t*d.y)
        }
        fun touches(a: XY,b: XY,c: XY,d: XY): Boolean {
            val ab=subtract(b,a); val cd=subtract(d,c); val ac=subtract(c,a)
            val denominator=cross(ab,cd)
            if (abs(denominator)>1e-9) {
                val t=cross(ac,cd)/denominator; val u=cross(ac,ab)/denominator
                if (t in 0.0..1.0 && u in 0.0..1.0) return true
            }
            return minOf(distance(a,c,d),distance(b,c,d),distance(c,a,b),distance(d,a,b)) <= 1.0
        }
        for (wayId in parents) {
            active()
            val way = source.ways[wayId] ?: return false
            val geometry = way.nodeIds.map { source.nodes[it]?.point ?: return false }
            if (geometry.any { abs(it.lat)>=85.0 } || geometry.zipWithNext().any { abs(it.first.lon-it.second.lon)>180.0 }) return false
            for (i in 0 until geometry.lastIndex) {
                if (i % 64 == 0) active()
                val c=xy(geometry[i]); val d=xy(geometry[i+1])
                if (distance(XY(0.0,0.0),c,d)>radius+1.0) continue
                for ((j, segment) in segments.withIndex()) {
                    if (j % 64 == 0) active()
                    if (touches(xy(route.points[segment]),xy(route.points[segment+1]),c,d)) return false
                }
            }
        }
        return true
    }
    private fun valid(p: GeoPoint) = p.lat.isFinite() && p.lat in -90.0..90.0 && p.lon.isFinite() && p.lon in -180.0..180.0
    private fun ordinarySignalTags(tags: Map<String, String>) = !broaderDeviceEvidence(tags) &&
        CameraClassifier.classify(tags).type in setOf(CameraType.TRAFFIC, CameraType.HIGHWAY)
    private fun broaderDeviceEvidence(tags: Map<String, String>) = tags.keys.any { key ->
        val value = key.lowercase()
        listOf("camera", "surveillance", "monitoring", "sensor", "detector", "manufacturer", "brand", "model", "product", "enforcement")
            .any { value == it || value.startsWith("$it:") }
    } || tags.values.any { value -> BROADER_DEVICE.containsMatchIn(value) }

    /** Check both directions of source/path coverage. Endpoint-only nearest-way matching
     * would accept a shortcut across a bend. Unknown polar/seam geometry is not permission.
     * Positive and negative source progress may not mix within one directed source span.
     */
    private fun sourceMatches(candidate: List<GeoPoint>, source: List<GeoPoint>, active: () -> Unit): Boolean {
        if (candidate.size < 2 || source.size < 2 || (candidate+source).any { abs(it.lat) >= 85.0 } ||
            candidate.zipWithNext().any { abs(it.first.lon-it.second.lon) > 180.0 } ||
            source.zipWithNext().any { abs(it.first.lon-it.second.lon) > 180.0 }) return false
        val projected = candidate.map {
            active()
            Geo.projectOntoRoute(it, source, active) ?: return false
        }
        if (projected.any { it.distanceToRouteMeters > 1.0 }) return false
        var forward = false; var backward = false
        for ((a, b) in projected.zipWithNext()) {
            if (b.distanceAlongMeters-a.distanceAlongMeters > 1.0) forward = true
            if (a.distanceAlongMeters-b.distanceAlongMeters > 1.0) backward = true
        }
        if (forward && backward) return false
        val first = projected.first().distanceAlongMeters
        val last = projected.last().distanceAlongMeters
        val low = minOf(first, last); val high = maxOf(first, last)
        var along = 0.0
        for (i in source.indices) {
            active()
            if (i > 0) along += Geo.distanceMeters(source[i-1], source[i])
            if (along in low..high && (Geo.projectOntoRoute(source[i], candidate, active)?.distanceToRouteMeters ?: Double.MAX_VALUE) > 1.0)
                return false
        }
        return true
    }

    private val NODE_ID = Regex("node/[1-9][0-9]{0,18}")
    private val BROADER_DEVICE = Regex("(?i)\\b(camera|surveillance|cctv|alpr|anpr|sensor|signaltrace|shotspotter|soundthinking|rf_sensor)\\b")
    private val MOTOR_ROADS = setOf("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
        "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service", "road", "track")
}
