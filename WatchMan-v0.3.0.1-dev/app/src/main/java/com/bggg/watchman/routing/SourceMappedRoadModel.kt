package com.bggg.watchman.routing

import com.bggg.watchman.routing.ConditionalRestriction.Match
import java.time.Instant
import java.util.Collections
import java.util.Locale

/** Actual original-source producer for the explicitly named MAPPED passenger-road estimate.
 * Coverage concerns retained OSM controls, not the absence of unmapped real-world controls or
 * observed traffic. No time zone, holiday, vehicle facts, entrance grant or ready flag is guessed.
 * Missing/dynamic/unsupported control evidence stays UNKNOWN. No convenience penalties exist.
 */
class SourceMappedRoadModel(
    private val graph: MotorcarGraphTraversal.Graph,
    private val sourceLengthMm: (Long) -> Long,
    speedProfile: RoadSpeedEstimate.Profile,
    private val controlProfile: ControlProfile,
    private val validFrom: Instant,
    private val validUntil: Instant,
    private val maximumSources: Int = 8192,
    private val maximumRetainedBytes: Long = 4L * 1024 * 1024,
    private val cancelled: () -> Boolean = { false },
    endpointPolicy: EndpointAccessPolicy? = null
) {
    enum class MappedControl { SIGNAL, STOP, GIVE_WAY }
    class ControlProfile(val revision: String, val evidence: String, delayMillis: Map<MappedControl, Long>) {
        val delayMillis = Collections.unmodifiableMap(LinkedHashMap(delayMillis))
        init {
            require(revision.isNotBlank() && revision.length <= 512)
            require(evidence.isNotBlank() && evidence.length <= 2048)
            require(this.delayMillis.isNotEmpty() && this.delayMillis.values.all { it in 0..300000L })
        }
    }
    data class CacheState(val sources: Int, val retainedEstimatedBytes: Long)
    private data class Source(val arc: MotorcarGraphTraversal.Arc?, val node: MotorcarGraphTraversal.Node?,
        val snapshot: RoadPolicyTimeline.Snapshot)
    private val owner = Thread.currentThread()
    private val revision = graph.revision.also { require(it.isNotBlank() && it.length <= 512) }
    private val sources = LinkedHashMap<Pair<RoadPolicyTimeline.Kind, Long>, Source>()
    private var retainedBytes = 0L
    init {
        require(validFrom < validUntil)
        require(maximumSources in 1..100000 && maximumRetainedBytes in 1024..256L * 1024 * 1024)
    }
    val timing = SourceRoadTiming(graph, { id -> ready(); sourceLengthMm(id).also { ready() } },
        speedProfile, ::snapshot, ::control, endpointPolicy = endpointPolicy, cancelled = cancelled)

    private fun ready() {
        check(Thread.currentThread() === owner) { "Mapped road model belongs to another worker" }
        check(!cancelled()) { "Mapped road model cancelled" }
        check(graph.revision == revision) { "Mapped road model source changed" }
    }
    fun cacheState(): CacheState { ready(); return CacheState(sources.size, retainedBytes) }
    private fun normalized(raw: String?) = raw?.trim()?.lowercase(Locale.ROOT)
    private fun freeze(tags: Map<String, String>): Map<String, String> {
        require(tags.size <= 4096)
        require(tags.entries.sumOf { it.key.length.toLong() + it.value.length } <= 512000)
        return Collections.unmodifiableMap(LinkedHashMap(tags))
    }
    private fun stable(source: Source) {
        source.arc?.let { check(graph.arc(it.id) == it) { "Mapped original arc metadata changed" } }
        source.node?.let { check(graph.node(it.id) == it) { "Mapped original node metadata changed" } }
        ready()
    }
    /** No eviction/reconstruction: SourceRoadTiming requires snapshot identity throughout each
     * interval. Exhausting this explicitly bounded producer refuses the request, never drops
     * metadata or returns a different equal-looking snapshot as the same evidence.
     */
    fun snapshot(kind: RoadPolicyTimeline.Kind, id: Long): RoadPolicyTimeline.Snapshot {
        ready(); require(id > 0)
        val key = kind to id
        sources[key]?.let { stable(it); return it.snapshot }
        val arc = if (kind == RoadPolicyTimeline.Kind.ARC) checkNotNull(graph.arc(id)).let {
            check(it.id == id)
            it.copy(tags = freeze(it.tags), unresolved = Collections.unmodifiableList(ArrayList(it.unresolved)))
        } else null
        val node = if (kind == RoadPolicyTimeline.Kind.NODE) checkNotNull(graph.node(id)).let {
            check(it.id == id)
            it.copy(tags = freeze(it.tags), unresolved = Collections.unmodifiableList(ArrayList(it.unresolved)))
        } else null
        val tags = arc?.tags ?: checkNotNull(node).tags
        val unresolved = arc?.unresolved ?: checkNotNull(node).unresolved
        require(unresolved.size <= 4096 && unresolved.sumOf { it.length.toLong() } <= 512000)
        val weight = 512L + tags.entries.sumOf { 112L + 2L * (it.key.length.toLong() + it.value.length) } +
            unresolved.sumOf { 48L + 2L * it.length }
        check(sources.size < maximumSources && weight <= maximumRetainedBytes - retainedBytes) {
            "Mapped context source/retained-metadata budget exhausted"
        }
        val temporal = tags.keys.any { it.endsWith(":conditional") || it == "opening_hours" }
        val snapshot = RoadPolicyTimeline.Snapshot(revision, kind, id,
            "mapped-context/1:$kind:$id", "Original mapped source $kind/$id; zone/calendar/vehicle facts unavailable; " +
                "temporal_tags=$temporal; no physical-state or permission assertion",
            validFrom, validUntil, null)
        val source = Source(arc, node, snapshot)
        stable(source)
        sources[key] = source; retainedBytes += weight
        return snapshot
    }

    fun control(arc: MotorcarGraphTraversal.Arc, approach: NoCamsGraphSearch.Approach): SourceRoadTiming.Control {
        ready(); check(approach.state.instant >= validFrom && approach.state.instant < validUntil)
        snapshot(RoadPolicyTimeline.Kind.ARC, arc.id)
        check(graph.arc(arc.id) == arc)
        val problems = ArrayList<String>()
        val notes = ArrayList<String>()
        fun unsupported(tags: Map<String, String>, source: String) {
            if (tags.keys.any { (it.endsWith(":conditional") || it == "opening_hours") &&
                    (it.startsWith("traffic_signals") || it.startsWith("stop") || it.startsWith("give_way") ||
                        it.startsWith("crossing") || it.startsWith("barrier") || it == "opening_hours") })
                problems += "$source dynamic mapped controls"
            if (normalized(tags["ford"]) !in setOf(null, "no") || normalized(tags["highway"]) == "ford")
                problems += "$source ford requires passability/timing evidence"
            if (normalized(tags["route"]) == "ferry" || tags.keys.any { it.startsWith("ferry:") })
                problems += "$source ferry operation/timing unavailable"
            if (normalized(tags["railway"]) in setOf("level_crossing", "crossing") ||
                normalized(tags["bridge"]) == "movable") problems += "$source moving crossing operation unavailable"
            if (normalized(tags["barrier"]) !in setOf(null, "no", "entrance", "cattle_grid"))
                problems += "$source barrier operation/timing unavailable"
            if (tags.keys.any { it == "traffic_calming" || it.startsWith("traffic_calming:") })
                problems += "$source traffic-calming timing unavailable"
            if (tags.keys.any { it.startsWith("traffic_signals:") && it !in setOf("traffic_signals:direction") })
                problems += "$source additional signal state/turn metadata requires model"
            if (tags.keys.any { it.startsWith("red_turn:") || it.startsWith("turn_on_red") })
                problems += "$source signal-dependent turn evidence unavailable"
        }
        unsupported(arc.tags, "arc${arc.id}")
        if (normalized(arc.tags["highway"]) in setOf("traffic_signals", "stop", "give_way", "crossing"))
            problems += "Point control encoded as way cannot be placed at a fabricated node"
        var delay = 0L
        val position = approach.state.position
        if (position is MotorcarGraphTraversal.Position.Interior) {
            check(position.arcId == arc.id)
            notes += "Original interior continuation: no upstream node crossing or wait invented"
        } else {
            val nodeId = (position as MotorcarGraphTraversal.Position.AtNode).id
            check(nodeId == arc.from)
            snapshot(RoadPolicyTimeline.Kind.NODE, nodeId)
            val node = checkNotNull(graph.node(nodeId))
            unsupported(node.tags, "node$nodeId")
            if (node.unresolved.isNotEmpty()) problems += "Original node has unresolved source evidence"
            val incoming = approach.incoming
            val incomingArc = incoming?.let { portion ->
                val original = checkNotNull(graph.arc(portion.arcId))
                snapshot(RoadPolicyTimeline.Kind.ARC, original.id)
                check(original.to == nodeId && portion.toFraction == 1.0 && portion.direction == original.direction) {
                    "Control incoming approach does not reach this original node"
                }
                original
            }
            check(!approach.state.initial || incoming == null) { "Initial approach cannot invent an incoming crossing" }
            if (!approach.state.initial && incoming == null) problems += "Original arrival approach is missing"
            val highway = normalized(node.tags["highway"])
            if (highway?.contains(';') == true && highway.split(';').any {
                    it.trim() in setOf("traffic_signals", "stop", "give_way", "crossing")
                }) problems += "Compound point-control highway value requires resolution"
            val crossingSignal = normalized(node.tags["crossing"]) == "traffic_signals" ||
                normalized(node.tags["crossing:signals"]) == "yes"
            val type = when {
                highway == "traffic_signals" -> MappedControl.SIGNAL
                highway == "stop" -> MappedControl.STOP
                highway == "give_way" -> MappedControl.GIVE_WAY
                crossingSignal -> { problems += "Pedestrian crossing signal applicability requires road-control evidence"; null }
                highway in setOf("mini_roundabout", "toll_gantry") -> { problems += "Additional mapped junction/toll timing unavailable"; null }
                else -> null
            }
            if (node.tags.keys.any { it == "traffic_signals" } && highway != "traffic_signals")
                problems += "Signal value without supported point-control placement"
            if (highway == "traffic_signals" && "traffic_signals" in node.tags)
                problems += "Additional signal subtype/state value requires model"
            if (node.tags.keys.any { it == "stop" } && highway != "stop")
                problems += "Stop value without supported point-control placement"
            if (highway == "stop" && normalized(node.tags["stop"]) !in setOf(null, "all"))
                problems += "Stop-controlled approach allocation is unsupported"
            if (highway in setOf("traffic_signals", "stop", "give_way") && node.tags.keys.any {
                    it.startsWith("stop:") && it != "stop:direction" ||
                        it.startsWith("give_way:") && it != "give_way:direction"
                }) problems += "Additional stop/yield control metadata requires model"
            if (type != null && incomingArc != null && !approach.state.initial) {
                val directional = listOfNotNull(node.tags["direction"], node.tags["${when(type) {
                    MappedControl.SIGNAL -> "traffic_signals"; MappedControl.STOP -> "stop"; MappedControl.GIVE_WAY -> "give_way"
                }}:direction"]).map(::normalized).distinct()
                val applies = when {
                    directional.size > 1 -> { problems += "Conflicting original-way control directions"; false }
                    directional.isEmpty() -> true // explicitly omnidirectional MAPPED estimate, not observed cycle state
                    directional.single() == "both" -> true
                    incomingArc.wayId != arc.wayId -> { problems += "Directional control at multi-way departure has ambiguous original-way ownership"; false }
                    directional.single() == "forward" -> incomingArc.direction == MotorcarTagPolicy.Direction.FORWARD
                    directional.single() == "backward" -> incomingArc.direction == MotorcarTagPolicy.Direction.BACKWARD
                    else -> { problems += "Unsupported/compass original-way control direction"; false }
                }
                if (applies) {
                    val estimate = controlProfile.delayMillis[type]
                    if (estimate == null) problems += "No configured mapped $type delay estimate"
                    else { delay = estimate; notes += "Original approached node$nodeId mapped $type delay=${estimate}ms; incoming original-way direction=${incomingArc.direction}" }
                } else notes += "Mapped direction does not establish a control on this incoming original-way approach"
            } else if (type != null) notes += "Initial source position has no prior crossing; no passed-control delay charged"
            else notes += "Complete original node$nodeId metadata inspected; no supported mapped point control"
        }
        if (arc.unresolved.isNotEmpty()) problems += "Original arc has unresolved source evidence"
        ready()
        val evidence = ("Mapped-only estimate ${controlProfile.revision}: ${controlProfile.evidence}; " +
            (problems + notes).joinToString("; ")).take(2048)
        return SourceRoadTiming.Control(revision, arc.id, approach, controlProfile.revision, evidence,
            validUntil, if (problems.isEmpty()) Match.YES else Match.UNKNOWN, delay,
            if (delay > 0 && problems.isEmpty()) Match.YES else Match.UNKNOWN)
    }
}
