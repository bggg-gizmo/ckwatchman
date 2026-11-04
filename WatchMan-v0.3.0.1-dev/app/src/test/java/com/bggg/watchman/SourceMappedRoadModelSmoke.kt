package com.bggg.watchman

import com.bggg.watchman.routing.*
import com.bggg.watchman.routing.ConditionalRestriction.Match
import com.bggg.watchman.routing.MotorcarTagPolicy.Direction
import java.math.BigDecimal
import java.time.Instant

fun sourceMappedRoadModelSmoke() {
    val at = Instant.parse("2026-10-08T12:00:00Z")
    val road = mapOf("highway" to "residential", "maxspeed" to "30")
    class Graph : MotorcarGraphTraversal.Graph {
        override var revision = "source-mapped-original-fixture"
        val arcs = linkedMapOf(
            1L to MotorcarGraphTraversal.Arc(1, 101, 10, 20, Direction.FORWARD, road),
            2L to MotorcarGraphTraversal.Arc(2, 101, 20, 10, Direction.BACKWARD, road),
            3L to MotorcarGraphTraversal.Arc(3, 101, 20, 30, Direction.FORWARD, road))
        val nodes = linkedMapOf(10L to MotorcarGraphTraversal.Node(10, emptyMap()),
            20L to MotorcarGraphTraversal.Node(20, mapOf("highway" to "traffic_signals")),
            30L to MotorcarGraphTraversal.Node(30, emptyMap()))
        override fun arc(id: Long) = arcs[id]
        override fun node(id: Long) = nodes[id]
        override fun turnCoverage(arc: MotorcarGraphTraversal.Arc) = MotorcarGraphTraversal.Coverage(true, emptySet(), emptyList())
    }
    val speed = RoadSpeedEstimate.Profile("TEST-estimated-30", "Synthetic arithmetic profile; not product configuration",
        mapOf("residential" to BigDecimal(30)))
    val estimates = linkedMapOf(SourceMappedRoadModel.MappedControl.SIGNAL to 12000L,
        SourceMappedRoadModel.MappedControl.STOP to 3000L, SourceMappedRoadModel.MappedControl.GIVE_WAY to 1000L)
    val controlProfile = SourceMappedRoadModel.ControlProfile("TEST-mapped-controls", "Synthetic mapped-only arithmetic delays", estimates)
    estimates[SourceMappedRoadModel.MappedControl.SIGNAL] = 99999
    check(controlProfile.delayMillis[SourceMappedRoadModel.MappedControl.SIGNAL] == 12000L)
    val graph = Graph()
    fun model(maximumSources: Int = 8192, maximumBytes: Long = 4L * 1024 * 1024,
        cancelled: () -> Boolean = { false }) = SourceMappedRoadModel(graph, { 100000L }, speed, controlProfile,
        at.minusSeconds(1), at.plusSeconds(3600), maximumSources, maximumBytes, cancelled)
    fun reject(action: () -> Unit) = check(runCatching(action).isFailure)
    val engine = MotorcarGraphTraversal(graph, emptyList()) { _, time -> RestrictionCondition.Context(time, null) }
    val initial = engine.startAt(RoadEndpointAnchor(graph.revision, 1, 10, 20, 0.0), at)
    val reached = checkNotNull(engine.advance(initial, 1, 12000).next)
    val approach = NoCamsGraphSearch.Approach(reached, RoadArcPortion(1, 0.0, 1.0))
    fun controls(source: SourceMappedRoadModel = model(), state: NoCamsGraphSearch.Approach = approach,
        arc: MotorcarGraphTraversal.Arc = checkNotNull(graph.arc(3))) = source.control(arc, state)
    val source = model()
    check(source.snapshot(RoadPolicyTimeline.Kind.ARC, 1) === source.snapshot(RoadPolicyTimeline.Kind.ARC, 1))
    check(source.snapshot(RoadPolicyTimeline.Kind.ARC, 1).zone == null)
    check(source.snapshot(RoadPolicyTimeline.Kind.ARC, 1).facts.isEmpty())
    check(source.snapshot(RoadPolicyTimeline.Kind.ARC, 1).quantities.isEmpty())
    check(controls(source).let { it.coverage == Match.YES && it.delayMillis == 12000L && it.lawfulWait == Match.YES })
    val timed = source.timing.timing(checkNotNull(graph.arc(3)), RoadArcPortion(3, 0.0, 1.0), approach)
    check(timed.departureDelayMillis == 12000L && timed.durationMillis == 12000L && timed.waitAllowed == Match.YES)
    check(controls(model(), NoCamsGraphSearch.Approach(engine.start(20, at), null)).delayMillis == 0L)
    val interior = engine.startOnArc(3, 0.25, at)
    check(controls(model(), NoCamsGraphSearch.Approach(interior, null)).delayMillis == 0L)
    check(model().timing.timing(checkNotNull(graph.arc(3)), RoadArcPortion(3, 0.25, 0.75),
        NoCamsGraphSearch.Approach(interior, null)).durationMillis == 6000L)

    fun nodeTags(tags: Map<String, String>) { graph.nodes[20] = MotorcarGraphTraversal.Node(20, tags) }
    nodeTags(mapOf("highway" to "traffic_signals", "traffic_signals:direction" to "backward"))
    check(controls().let { it.coverage == Match.YES && it.delayMillis == 0L })
    nodeTags(mapOf("highway" to "traffic_signals", "traffic_signals:direction" to "forward"))
    check(controls().delayMillis == 12000L)
    graph.arcs[3] = checkNotNull(graph.arc(3)).copy(wayId = 102)
    check(controls().coverage == Match.UNKNOWN)
    graph.arcs[3] = checkNotNull(graph.arc(3)).copy(wayId = 101)
    nodeTags(mapOf("highway" to "traffic_signals", "direction" to "both"))
    check(controls().delayMillis == 12000L)
    for (tags in listOf(
        mapOf("highway" to "traffic_signals", "direction" to "north"),
        mapOf("highway" to "traffic_signals", "direction" to "forward", "traffic_signals:direction" to "backward"),
        mapOf("highway" to "traffic_signals", "traffic_signals:direction" to "forward;backward"),
        mapOf("highway" to "traffic_signals;stop"),
        mapOf("highway" to "traffic_signals", "traffic_signals:operating_times" to "Mo-Fr"),
        mapOf("highway" to "traffic_signals", "traffic_signals" to "blink_mode"),
        mapOf("highway" to "traffic_signals", "red_turn:right" to "yes"),
        mapOf("highway" to "crossing", "crossing" to "traffic_signals"),
        mapOf("highway" to "crossing", "crossing:signals" to "yes"),
        mapOf("highway" to "stop", "stop" to "minor"),
        mapOf("highway" to "stop", "stop:conditional" to "all @ Mo"),
        mapOf("barrier" to "lift_gate"), mapOf("railway" to "level_crossing"),
        mapOf("ford" to "yes"), mapOf("bridge" to "movable"),
        mapOf("traffic_calming" to "hump"), mapOf("highway" to "mini_roundabout")
    )) {
        nodeTags(tags)
        check(controls().coverage == Match.UNKNOWN) { "Unsupported mapped control accepted: $tags" }
    }
    nodeTags(mapOf("highway" to "stop", "stop" to "all"))
    check(controls().delayMillis == 3000L)
    nodeTags(mapOf("highway" to "give_way"))
    check(controls().delayMillis == 1000L)
    nodeTags(emptyMap())
    check(controls().let { it.coverage == Match.YES && it.delayMillis == 0L && "Mapped-only" in it.evidence })
    check(controls(state = approach.copy(incoming = null)).coverage == Match.UNKNOWN)
    reject { controls(state = approach.copy(incoming = RoadArcPortion(1, 0.0, 0.5))) }
    reject { controls(state = approach.copy(incoming = RoadArcPortion(2, 0.0, 1.0))) }
    reject { controls(state = NoCamsGraphSearch.Approach(engine.start(20, at), RoadArcPortion(1, 0.0, 1.0))) }

    for (tags in listOf(road + ("route" to "ferry"), road + ("ford" to "yes"),
        road + ("traffic_calming" to "hump"), road + ("barrier" to "gate"))) {
        graph.arcs[3] = checkNotNull(graph.arc(3)).copy(tags = tags)
        check(controls().coverage == Match.UNKNOWN)
    }
    graph.arcs[3] = checkNotNull(graph.arc(3)).copy(tags = road + ("access:conditional" to "no @ Mo 12:00-13:00"))
    reject { model().timing.timing(checkNotNull(graph.arc(3)), RoadArcPortion(3, 0.0, 1.0), approach) }
    graph.arcs[3] = checkNotNull(graph.arc(3)).copy(tags = road + ("access" to "private"))
    reject { model().timing.timing(checkNotNull(graph.arc(3)), RoadArcPortion(3, 0.0, 1.0), approach) }
    graph.arcs[3] = checkNotNull(graph.arc(3)).copy(tags = road)
    val one = model(maximumSources = 1)
    one.snapshot(RoadPolicyTimeline.Kind.ARC, 1)
    reject { one.snapshot(RoadPolicyTimeline.Kind.NODE, 10) }
    check(one.cacheState().sources == 1)
    val small = model(maximumBytes = 1024)
    graph.arcs[3] = checkNotNull(graph.arc(3)).copy(tags = road + ("description" to "x".repeat(500)))
    reject { small.snapshot(RoadPolicyTimeline.Kind.ARC, 3) }
    check(small.cacheState().sources == 0)
    graph.arcs[3] = checkNotNull(graph.arc(3)).copy(tags = road)
    val frozen = model()
    frozen.snapshot(RoadPolicyTimeline.Kind.NODE, 20)
    nodeTags(mapOf("highway" to "stop"))
    reject { frozen.snapshot(RoadPolicyTimeline.Kind.NODE, 20) }
    nodeTags(emptyMap())
    val changed = model()
    graph.revision = "changed"
    reject { changed.cacheState() }
    graph.revision = "source-mapped-original-fixture"
    reject { model(cancelled = { true }).snapshot(RoadPolicyTimeline.Kind.ARC, 1) }
    val ended = SourceMappedRoadModel(graph, { 100000L }, speed, controlProfile,
        at.minusSeconds(10), at.plusSeconds(1))
    reject { controls(ended) } // actual approached state is already twelve seconds after start
    val threaded = model()
    var foreignFailed = false
    val worker = Thread { foreignFailed = runCatching { threaded.cacheState() }.isFailure }
    worker.start(); worker.join(); check(foreignFailed)
    println("Source-mapped road producer: original metadata/control placement, estimates, temporal/private UNKNOWN, immutable bounded contexts PASS")
}
