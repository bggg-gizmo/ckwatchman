package com.bggg.watchman

import com.bggg.watchman.routing.VehicleLimitPolicy as Limits
import com.bggg.watchman.routing.MotorcarTagPolicy.Direction
import com.bggg.watchman.routing.ConditionalRestriction as Rule
import com.bggg.watchman.routing.RestrictionCondition
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

fun vehicleLimitPolicySmoke() {
    val length = Limits.UnitKind.LENGTH_METRES; val mass = Limits.UnitKind.MASS_TONNES
    fun amount(raw: String, kind: Limits.UnitKind, expected: String) {
        val parsed = Limits.parse(raw, kind)
        check(parsed.problem == null && !parsed.unlimited && parsed.amount!!.compareTo(BigDecimal(expected)) == 0) { "$raw -> $parsed" }
    }
    amount("6'8\"", length, "2.032"); amount("13'", length, "3.9624")
    amount("13'0''", length, "3.9624"); amount("6\u20192\u201d", length, "1.8796")
    amount("3.2 m", length, "3.2"); amount("200 cm", length, "2")
    amount("2500 lbs", mass, "1.133980925"); amount("6 st", mass, "5.44310844")
    amount("2 st", mass, "1.81436948"); amount("1250 kg", mass, "1.25")
    amount("1.25", mass, "1.25")
    for (raw in listOf("", "default", "below_default", "-1", "6'12\"", "6'8", "3,5", "3 metres", "1e10")) check(Limits.parse(raw, length).problem != null)
    check(Limits.parse("5 tons", mass).problem != null)
    check(Limits.parse("none", mass).unlimited)
    val context = RestrictionCondition.Context(Instant.parse("2026-09-14T15:00:00Z"), ZoneId.of("America/Los_Angeles"),
        quantities = mapOf("height" to BigDecimal("2"), "width" to BigDecimal("1.8"), "length" to BigDecimal("4.5"),
            "weight" to BigDecimal("1.5"), "axleload" to BigDecimal("0.8"), "weightrating" to BigDecimal("2.1")))
    fun result(vararg tags: Pair<String, String>, ctx: RestrictionCondition.Context = context, backward: Boolean = false) =
        Limits.evaluate(mapOf(*tags), if (backward) Direction.BACKWARD else Direction.FORWARD, ctx)
    val yes = Rule.Match.YES; val no = Rule.Match.NO; val unknown = Rule.Match.UNKNOWN
    check(result().passes == yes)
    check(result("maxheight" to "6'8\"").passes == yes)
    check(result("maxheight" to "6'6\"").passes == no)
    check(result("maxheight" to "2").passes == yes)
    check(result("maxheight:physical" to "2").passes == no)
    check(result("maxheight" to "3", "maxheight:physical" to "1.9").passes == no)
    check(result("maxheight" to "none", "maxheight:physical" to "1.9").passes == no)
    check(result("maxheight:physical" to "none").passes == unknown)
    check(result("maxheight:physical:conditional" to "1 @ Tu").passes == yes)
    check(result("maxheight:physical:conditional" to "3 @ wet").passes == yes)
    check(result("maxheight" to "default").passes == unknown)
    check(result("maxheight" to "3", ctx = context.copy(quantities = emptyMap())).passes == unknown)
    check(result("maxheight" to "1", "maxheight:backward" to "3", backward = true).passes == yes)
    check(result("maxheight" to "1", "maxheight:backward" to "3").passes == no)
    check(result("maxweight" to "2500 lbs").passes == no)
    check(result("maxweight:hgv" to "2500 lbs").passes == yes) // Passenger profile is not an HGV profile.
    check(result("maxweight" to "1", "maxweight:motorcar" to "2").passes == yes)
    check(result("maxweight:conditional" to "1 @ wet").passes == unknown)
    check(result("maxweight:conditional" to "2 @ wet").passes == yes) // Both unknown branches accommodate known weight.
    check(result("maxweight" to "1", "maxweight:conditional" to "none @ Mo-Fr").passes == yes)
    check(result("maxheight:conditional" to "6'8\" @ Mo-Fr; 6'6\" @ Sa-Su").passes == yes)
    check(result("maxheight:conditional" to "6'6\" @ Mo-Fr").passes == no)
    check(result("maxheight:conditional" to "broken").passes == unknown)
    check(result("maxheight:lanes" to "3|2").passes == unknown)
    check(result("maxheight" to "1", "maxheight:lanes" to "3|2").passes == unknown)
    check(result("maxheight:signed" to "no").passes == yes)
    check(result("maxweightrating" to "2").passes == no)
    check(result("maxaxleload" to "0.7").passes == no)
    val parsed = Rule.parse("6'8\" @ (Mo-Fr 07:00-09:00 \"event; contact@school\"); 7'0\" @ wet")
    check(parsed.problem == null && parsed.clauses.size == 2 && parsed.clauses[0].value == "6'8\"")
    for (raw in listOf("\"no\" @ wet", "6'8\"oops @ wet", "6\" @ wet", "no @ \"unfinished")) check(Rule.parse(raw).problem != null)
    println("Vehicle limits: feet/inches/short-ton/pound values, exact decimal conversion, physical/legal separation, mode/direction/conditional precedence, missing properties and imperial conditional lexer regression PASS; graph/profile integration still required")
}
