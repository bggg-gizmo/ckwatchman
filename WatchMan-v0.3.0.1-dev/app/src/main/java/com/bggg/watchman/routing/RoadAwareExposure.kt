package com.bggg.watchman.routing

import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.data.RoutedDeviceExposure
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
        wayIds: LongArray, geometryVerified: BooleanArray, edgeGroups: IntArray,
        parents: Map<Long, Set<Long>>, motorParents: Set<Long>) {
        private val ways = wayIds.copyOf()
        private val matched = geometryVerified.copyOf()
        private val edges = edgeGroups.copyOf()
        private val parentWays = Collections.unmodifiableMap(parents.mapValues { Collections.unmodifiableSet(HashSet(it.value)) })
        private val motorConnected = Collections.unmodifiableSet(HashSet(motorParents))
        internal fun wayAt(index: Int) = ways[index]
        internal fun matchedAt(index: Int) = matched[index]
        internal fun edgeAt(index: Int) = edges[index]
        internal fun parents(node: Long?) = parentWays[node].orEmpty()
        internal fun hasMotorParent(node: Long?) = node in motorConnected
        internal fun suffix(identity: String, firstSegment: Int) = Verified(identity, observation,
            ways.copyOfRange(firstSegment, ways.size), matched.copyOfRange(firstSegment, matched.size),
            edges.copyOfRange(firstSegment,edges.size),parentWays, motorConnected)
    }
    data class Assessment(val nearbyRelevantDevices: List<CameraPoi>, val encounters: List<CameraPoi>,
        val awarenessOnlyDevices: List<CameraPoi>, val verifiedRoadEncounters: Int, val unknownEncounters: Int,
        val weightedExposure: Long, val snapshotRevision: String,
        val roadEncounters: List<RoadEncounter> = emptyList())
    /** Only these verified affected segments may replace a conservative device-area blocker. */
    data class RoadEncounter(val device: CameraPoi, val segmentIndices: List<Int>)

    fun mayUseRoadCoverage(camera: CameraPoi) = camera.type == CameraType.TRAFFIC &&
        camera.tags["highway"]=="traffic_signals" && camera.manufacturer.isNullOrBlank() && ordinarySignalTags(camera.tags)

    fun isCurrent(route: Route, verified: Verified?, nowMillis: Long) = verified != null &&
        fresh(verified.observation,nowMillis) && verified.routeIdentity==RouteIdentity.sha256(route)

    fun summary(assessment: Assessment, verified: Verified?, nowMillis: Long) = RoutedDeviceExposure(
        assessment.encounters.size, assessment.unknownEncounters, nowMillis,
        verified?.observation?.takeIf { fresh(it,nowMillis) }?.validUntilMillis, assessment.snapshotRevision)

    /** An exact trace span is one provider edge, even if its display geometry contains many
     * samples. Keep one exclusion location per encountered edge, never merge by OSM way ID:
     * a single way can cross many graph junctions. This groups constraints, not exposure.
     * Unknown coverage still keeps the full device area and strict candidate revalidation.
     */
    fun blockingSegments(route: Route, device: CameraPoi, segments: List<Int>, verified: Verified?, nowMillis: Long,
        checkActive: () -> Unit = {}): List<Int> {
        checkActive()
        if(!isCurrent(route,verified,nowMillis)) return segments
        val proof=checkNotNull(verified)
        val groups=LinkedHashMap<Int,MutableList<Int>>()
        for(segment in segments) {
            checkActive()
            require(segment in 0 until route.points.lastIndex)
            groups.getOrPut(proof.edgeAt(segment)) { ArrayList() }.add(segment)
        }
        return groups.values.map { group -> group.minBy { segment ->
            Geo.projectOntoRoute(device.point,listOf(route.points[segment],route.points[segment+1]),checkActive)
                ?.distanceToRouteMeters ?: Double.POSITIVE_INFINITY
        } }.also { checkActive() }
    }

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
        val edgeGroups = IntArray(ids.size)
        var covered = 0
        for ((edgeGroup,span) in observation.spans.withIndex()) {
            checkActive()
            if (span.wayId <= 0 || span.first != covered || span.last !in span.first..route.points.lastIndex) return null
            val way = observation.ways[span.wayId]
            val source = way?.nodeIds?.mapNotNull { observation.nodes[it]?.point }
            val good = source != null && source.size == way.nodeIds.size &&
                sourceMatches(route.points.subList(span.first, span.last+1), source, checkActive)
            for (i in span.first until span.last) { ids[i] = span.wayId; matched[i] = good; edgeGroups[i]=edgeGroup }
            covered = span.last
        }
        if (covered != route.points.lastIndex) return null
        val parents = HashMap<Long, MutableSet<Long>>()
        val motorParents = HashSet<Long>()
        for (way in observation.ways.values) {
            for ((i, node) in way.nodeIds.withIndex()) {
                if (i % 128 == 0) checkActive()
                if (node in observation.completeParentNodeIds) {
                    parents.getOrPut(node) { HashSet() } += way.id
                    if (way.highway in MOTOR_ROADS) motorParents += node
                }
            }
        }
        checkActive()
        return Verified(RouteIdentity.sha256(route), observation, ids, matched, edgeGroups, parents, motorParents).also { checkActive() }
    }

    fun assess(route: Route, snapshot: RoutingDeviceSnapshot, verified: Verified?, nowMillis: Long,
        checkActive: () -> Unit = {}): Assessment {
        checkActive()
        val proof = verified?.takeIf { fresh(it.observation, nowMillis) && it.routeIdentity == RouteIdentity.sha256(route) }
        val index = RouteProximityIndex(route, snapshot.corridorMeters, checkActive)
        val nearby = ArrayList<CameraPoi>()
        val encounters = ArrayList<CameraPoi>()
        val awarenessOnly = ArrayList<CameraPoi>()
        val roadEncounters = ArrayList<RoadEncounter>()
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
            var affectedSegments = emptyList<Int>()
            if (proof != null && mayUseRoadCoverage(camera)) {
                val nodeId = camera.sourceRecordId?.takeIf { NODE_ID.matches(it) }?.substringAfter('/')?.toLongOrNull()
                val node = proof.observation.nodes[nodeId]
                val parents = proof.parents(nodeId)
                if (nodeId in proof.observation.completeParentNodeIds && node != null && proof.hasMotorParent(nodeId) && parents.isNotEmpty() &&
                    node.tags["highway"] == "traffic_signals" && ordinarySignalTags(node.tags) &&
                    Geo.distanceMeters(camera.point, node.point) <= 1.0) {
                    val segments = index.withinSegments(camera.point)
                    if (segments.isNotEmpty() && segments.all { proof.matchedAt(it) }) {
                        affectedSegments = segments.filter { proof.wayAt(it) in parents }
                        if (affectedSegments.isNotEmpty()) roadDecision = true
                        else if (separateParentGeometry(camera.point, route, segments, parents, proof.observation,
                                snapshot.corridorMeters, checkActive)) roadDecision = false
                    }
                }
            }
            // Source road attribution says nothing about an unobserved connector from a
            // geocoded facility point to the snapped road. Never exempt that endpoint area.
            if (RouteAnalyzer.overlapsRequestedEndpoint(camera, route, snapshot.corridorMeters) &&
                ((route.requestedOrigin?.let { Geo.distanceMeters(it, camera.point) <= snapshot.corridorMeters &&
                    Geo.distanceMeters(it, route.points.first()) > 1.0 } == true) ||
                    (Geo.distanceMeters(route.destination.point, camera.point) <= snapshot.corridorMeters &&
                        Geo.distanceMeters(route.destination.point, route.points.last()) > 1.0))) roadDecision = null
            if (roadDecision == false) awarenessOnly += camera else {
                encounters += camera
                weight = Math.addExact(weight, entry.weight)
                if (roadDecision == true) {
                    roadCount++
                    roadEncounters += RoadEncounter(camera, Collections.unmodifiableList(ArrayList(affectedSegments)))
                } else unknownCount++
            }
        }
        checkActive()
        return Assessment(Collections.unmodifiableList(nearby), Collections.unmodifiableList(encounters),
            Collections.unmodifiableList(awarenessOnly), roadCount, unknownCount, weight, snapshot.revision,
            Collections.unmodifiableList(roadEncounters))
    }

    /** Rebind only an exact remaining suffix of this route. Never copy edge indices onto
     * an unrelated replanned/shortened geometry or refresh the source receipt's lifetime.
     */
    fun remainingCoverage(original: Route, remaining: Route, verified: Verified?, nowMillis: Long,
        checkActive: () -> Unit = {}): Verified? {
        checkActive()
        val proof = verified ?: return null
        if (!fresh(proof.observation, nowMillis) || proof.routeIdentity != RouteIdentity.sha256(original) ||
            remaining.destination != original.destination || remaining.provider != original.provider ||
            remaining.points.size !in 2..original.points.size) return null
        val first = original.points.size - remaining.points.size
        for (i in 1 until remaining.points.size) {
            if(i%128==0) checkActive()
            if(remaining.points[i] != original.points[first+i]) return null
        }
        val sourceSegment = listOf(original.points[first], original.points[first+1])
        if(abs(sourceSegment[0].lon-sourceSegment[1].lon)>180.0) return null
        val projection = Geo.projectOntoRoute(remaining.points.first(),sourceSegment,checkActive) ?: return null
        if(projection.distanceToRouteMeters>0.001) return null
        remaining.requestedOrigin?.let {
            if(Geo.distanceMeters(it,remaining.points.first())>90.0) return null
        }
        checkActive()
        return proof.suffix(RouteIdentity.sha256(remaining),first).also { checkActive() }
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
