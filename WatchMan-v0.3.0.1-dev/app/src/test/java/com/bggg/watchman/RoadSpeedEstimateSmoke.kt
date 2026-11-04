package com.bggg.watchman

import com.bggg.watchman.routing.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

fun roadSpeedEstimateSmoke() {
    val forward = MotorcarTagPolicy.Direction.FORWARD
    val backward = MotorcarTagPolicy.Direction.BACKWARD
    val supplied = linkedMapOf("residential" to BigDecimal(100), "service" to BigDecimal(15))
    val profile = RoadSpeedEstimate.Profile("test-speed-profile-1", "Explicit synthetic estimated speeds, not observed traffic", supplied)
    supplied["residential"] = BigDecimal(200)
    check(profile.highwayKmH.getValue("residential") == BigDecimal(100))
    check(runCatching { (profile.highwayKmH as MutableMap)["residential"] = BigDecimal(200) }.isFailure)
    val at = Instant.parse("2026-09-28T12:00:00Z")
    val context = RestrictionCondition.Context(at, ZoneId.of("America/New_York"))
    fun speed(vararg pairs: Pair<String, String>, direction: MotorcarTagPolicy.Direction = forward,
              ctx: RestrictionCondition.Context = context) = RoadSpeedEstimate.resolve(mapOf("highway" to "residential") + pairs, direction, ctx, profile)
    check(RoadSpeedEstimate.numericKmH("36 mph") == BigDecimal("57.936384"))
    check(RoadSpeedEstimate.numericKmH("10 knots") == BigDecimal("18.520"))
    check(RoadSpeedEstimate.numericKmH("90") == BigDecimal(90))
    for (raw in listOf("-1", "NaN", "Infinity", "50;70", "50|30", "walk", "DE:urban", "5000", "1e6", "")) check(RoadSpeedEstimate.numericKmH(raw) == null)
    val mph = speed("maxspeed" to "36 mph")
    check(mph.taggedLimitResolved && mph.travelMillis(RoadArcPortion(1, 0.0, 1.0), 1_609_344) == 100_000L)
    check(mph.travelMillis(RoadArcPortion(1, .25, .75), 1_609_344) == 50_000L)
    val knots = speed("maxspeed" to "10 knots")
    check(knots.travelMillis(RoadArcPortion(1, 0.0, 1.0), 18_520_000) == 3_600_000L)
    check(speed("maxspeed" to "90").travelMillis(RoadArcPortion(1, 0.0, 1.0), 90_000_000) == 3_600_000L)
    check(speed("maxspeed" to "none").estimatedKmH == BigDecimal(100))
    check(speed().estimatedKmH == BigDecimal(100) && speed().diagnostics.any { "profile estimate" in it })
    check(speed("maxspeed" to "90", "maxspeed:advisory" to "10").estimatedKmH == BigDecimal(90))
    val evidence = speed("maxspeed" to "25 mph", "maxspeed:note" to "Fixture unsigned alley speed",
        "maxspeed:type" to "sign", "maxspeed:source" to "sign", "source:maxspeed" to "survey")
    check(evidence.taggedLimitResolved && evidence.estimatedKmH == BigDecimal("40.233600"))
    check(evidence.sourceTags.getValue("source:maxspeed") == "survey" && evidence.sourceTags.size == 5)
    // Non-geographic speed literals; their occurrence is not observed traffic.
    for (literal in listOf("10 mph", "15 mph", "20 mph", "24 mph", "24.14 mph", "25 mph", "29 mph", "30 mph", "35 mph", "40 mph", "45 mph", "5 mph", "50 mph", "55 mph", "65 mph")) {
        val expected = literal.removeSuffix(" mph").toBigDecimal().multiply(BigDecimal("1.609344"))
        check(RoadSpeedEstimate.numericKmH(literal) == expected)
    }
    val school = speed("maxspeed" to "25 mph", "maxspeed:conditional" to "15 mph @ children_present")
    check(!school.taggedLimitResolved && school.estimatedKmH == BigDecimal("24.140160"))
    check(!speed("maxspeed" to "25 mph", "maxspeed:conditional" to "15 mph @ flashing").taggedLimitResolved)
    check(speed("maxspeed" to "25 mph", "maxspeed:conditional" to "15 mph").estimatedKmH == null)
    check(speed("maxspeed" to "25 mph", "maxspeed:conditional" to "15 mph @ (Mo-Fr 08:30-16:00; PH off").estimatedKmH == null)
    val tags = arrayOf("maxspeed" to "90", "maxspeed:backward" to "40", "maxspeed:vehicle" to "60", "maxspeed:motorcar:forward" to "25")
    check(speed(*tags).estimatedKmH == BigDecimal(25))
    val reverse = speed(*tags, direction = backward)
    check(reverse.estimatedKmH == BigDecimal(60))
    check(reverse.travelMillis(RoadArcPortion(2, .25, .75, backward), 60_000) == 1800L)
    check(runCatching { reverse.travelMillis(RoadArcPortion(2, 0.0, 1.0), 60_000) }.isFailure)
    val conditional = arrayOf("maxspeed" to "40", "maxspeed:conditional" to "20 @ (wet)")
    check(speed(*conditional, ctx = context.copy(facts = mapOf("wet" to ConditionalRestriction.Match.YES))).estimatedKmH == BigDecimal(20))
    check(speed(*conditional, ctx = context.copy(facts = mapOf("wet" to ConditionalRestriction.Match.NO))).estimatedKmH == BigDecimal(40))
    val unknown = speed(*conditional)
    check(!unknown.taggedLimitResolved && unknown.estimatedKmH == BigDecimal(20) && unknown.alternatives.size == 2)
    check(speed("maxspeed" to "20", "maxspeed:conditional" to "20 @ (wet)").taggedLimitResolved)
    val sameValueMatched = speed("maxspeed" to "20", "maxspeed:conditional" to "20 @ (wet)",
        ctx = context.copy(facts = mapOf("wet" to ConditionalRestriction.Match.YES)))
    check(sameValueMatched.alternatives.single().sourceKey == "maxspeed:conditional")
    val supersededMalformed = speed("maxspeed" to "40", "maxspeed:conditional" to "20 @ ()", "maxspeed:motorcar" to "30")
    check(supersededMalformed.taggedLimitResolved && supersededMalformed.estimatedKmH == BigDecimal(30))
    check(supersededMalformed.diagnostics.any { "maxspeed:conditional" in it } && supersededMalformed.sourceTags.containsKey("maxspeed:conditional"))
    // Independent exhaustive truth assignments to unknown predicates, all27 three-valued inputs.
    for (a in ConditionalRestriction.Match.entries) for (b in ConditionalRestriction.Match.entries) for (c in ConditionalRestriction.Match.entries) {
        val input = listOf(a, b, c)
        val expected = mutableSetOf<String>()
        fun enumerate(index: Int, chosen: List<Boolean>) {
            if (index == input.size) {
                var cap = "80"
                for ((position, active) in chosen.withIndex()) if (active) cap = listOf("60", "40", "20")[position]
                expected += cap
                return
            }
            when (input[index]) {
                ConditionalRestriction.Match.YES -> enumerate(index + 1, chosen + true)
                ConditionalRestriction.Match.NO -> enumerate(index + 1, chosen + false)
                ConditionalRestriction.Match.UNKNOWN -> { enumerate(index + 1, chosen + true); enumerate(index + 1, chosen + false) }
            }
        }
        enumerate(0, emptyList())
        val actual = speed("maxspeed" to "80", "maxspeed:conditional" to "60 @ (wet);40 @ (snow);20 @ (ice)",
            ctx = context.copy(facts = mapOf("wet" to a, "snow" to b, "ice" to c)))
        check(actual.alternatives.map { it.rawValue }.toSet() == expected)
        check(actual.estimatedKmH == expected.map(::BigDecimal).minOrNull())
        check(actual.taggedLimitResolved == (expected.size == 1))
    }
    check(speed("maxspeed" to "40", "maxspeed:conditional" to "20 @ ()").estimatedKmH == null)
    check(speed("maxspeed" to "walk").estimatedKmH == null)
    check(speed("maxspeed" to "40", "maxspeed:variable" to "yes").estimatedKmH == null)
    check(speed("maxspeed" to "40", "maxspeed:lanes" to "40|20").estimatedKmH == null)
    check(speed("maxspeed" to "40", "maxspeed:hgv" to "10").estimatedKmH == BigDecimal(40))
    check(speed("maxspeed" to "0").travelMillis(RoadArcPortion(1, 0.0, 1.0), 1000) == null)
    check(mph.travelMillis(RoadArcPortion(1, 0.0, 1.0), 0) == 1L)
    check(runCatching { mph.travelMillis(RoadArcPortion(1, 0.0, 1.0), -1) }.isFailure)
    check(runCatching { speed("maxspeed" to "0.00001").travelMillis(RoadArcPortion(1, 0.0, 1.0), Long.MAX_VALUE) }.isFailure)
    check(runCatching { RoadSpeedEstimate.Profile("", "evidence", mapOf("service" to BigDecimal(15))) }.isFailure)
    val unsupported = RoadSpeedEstimate.resolve(mapOf("highway" to "ferry"), forward, context, profile)
    check(unsupported.estimatedKmH == null && unsupported.diagnostics.any { "No evidenced" in it })
    println("Road speed estimate: exact km/h/mph/knots and15 base literals, immutable explicit profile/descriptive provenance, direction/mode/conditional semantics and27 exhaustive activations, superseded malformed/equal-value sources, school/variable/lane/advisory separation, source-mm partial travel/overflow guards PASS; controls/waiting/continuous interval and real-trip timing remain pending")
}
