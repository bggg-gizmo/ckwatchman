package com.bggg.watchman

import com.bggg.watchman.routing.TurnRestrictionPolicy as Policy
import com.bggg.watchman.routing.RestrictionCondition
import com.bggg.watchman.routing.ConditionalRestriction.Match
import com.bggg.watchman.routing.TurnRestrictionMachine as Machine
import java.time.Instant
import java.time.ZoneId

fun turnRestrictionPolicySmoke() {
    val context = RestrictionCondition.Context(Instant.parse("2026-09-14T15:00:00Z"), ZoneId.of("America/Los_Angeles"))
    fun resolve(vararg tags: Pair<String, String>, ctx: RestrictionCondition.Context = context) = Policy.resolve(mapOf("type" to "restriction") + mapOf(*tags), ctx)
    val yes = Match.YES; val no = Match.NO; val unknown = Match.UNKNOWN
    check(resolve("restriction" to "no_left_turn").certainValue == "no_left_turn")
    check(resolve("restriction" to "no_left_turn", "restriction:motorcar" to "only_right_turn").certainValue == "only_right_turn")
    check(resolve("restriction:vehicle" to "no_left_turn", "restriction:motor_vehicle" to "only_right_turn").certainValue == "only_right_turn")
    check(resolve("restriction:hgv" to "no_left_turn").certainlyExempt)
    check(resolve("type" to "restriction:bicycle", "restriction" to "no_left_turn").certainlyExempt)
    check(resolve("type" to "restriction:hgv", "restriction" to "no_left_turn", ctx = context.copy(facts = mapOf("hgv" to yes))).activation("no_left_turn") == unknown)
    check(resolve("type" to "restriction:bus", "restriction:motorcar" to "no_left_turn").activation("no_left_turn") == unknown)
    check(resolve("type" to "restriction:motorcar", "restriction" to "no_left_turn").certainValue == "no_left_turn")
    check(resolve("restriction" to "no_left_turn", "except" to "bicycle;psv").certainValue == "no_left_turn")
    for (mode in listOf("motorcar", "motor_vehicle", "vehicle", "bicycle; motorcar")) {
        val result = resolve("restriction" to "no_left_turn", "except" to mode)
        check(result.certainlyExempt && result.activation("no_left_turn") == no)
    }
    check(resolve("restriction" to "no_left_turn", "except" to "destination").activation("no_left_turn") == unknown)
    check(resolve("restriction" to "no_left_turn", "except:conditional" to "motorcar @ wet").activation("no_left_turn") == unknown)
    check(resolve("restriction" to "no_left_turn", "except" to "motorcar", "restriction:conditional" to "broken").certainlyExempt)
    check(resolve("restriction:conditional" to "no_left_turn @ Mo-Fr 07:00-09:30").activation("no_left_turn") == yes)
    check(resolve("restriction:conditional" to "no_left_turn @ Tu").certainlyExempt)
    check(resolve("restriction:conditional" to "no_left_turn @ wet").possibleValues == setOf("none", "no_left_turn"))
    check(resolve("restriction:conditional" to "no_left_turn @ wet").activation("no_left_turn") == unknown)
    check(resolve("restriction:conditional" to "no_left_turn @ wet").activation("no_right_turn") == no)
    check(resolve("restriction:conditional" to "no_left_turn @ wet; only_right_turn @ Mo-Fr").certainValue == "only_right_turn")
    check(resolve("restriction:conditional" to "broken", "restriction:motorcar" to "no_left_turn").certainValue == "no_left_turn")
    check(resolve("restriction" to "no_left_turn", "restriction:conditional" to "none @ Mo-Fr").certainlyExempt)
    check(resolve("restriction" to "no_left_turn", "day_on" to "Monday", "hour_on" to "07:30").activation("no_left_turn") == unknown)
    check(resolve("restriction:conditional" to "broken").activation("no_left_turn") == unknown)
    check(resolve().activation("no_left_turn") == unknown)
    check(resolve("restriction" to "").activation("no_left_turn") == unknown)
    check(resolve("type" to "restriction:unknown", "restriction" to "no_left_turn").activation("no_left_turn") == unknown)
    check(resolve("restriction:alien" to "no_left_turn").activation("no_left_turn") == unknown)
    check(resolve("restriction:hgv" to "no_left_turn", ctx = context.copy(facts = mapOf("hgv" to yes))).activation("no_left_turn") == unknown)
    val onRed = resolve("restriction" to "no_right_turn_on_red")
    check(onRed.certainValue == "no_right_turn_on_red" && onRed.activation("no_right_turn") == no)
    check(onRed.diagnostics.any { "Signal-phase" in it })
    val tags = mapOf("type" to "restriction", "restriction:conditional" to "no_left_turn @ (Mo-Fr 07:00-09:30; PH off)")
    check(Policy.resolve(tags, context).activation("no_left_turn") == unknown)
    check(Policy.resolve(tags, context.copy(holiday = { _, _ -> no })).activation("no_left_turn") == yes)
    check(Policy.resolve(tags, context.copy(holiday = { _, _ -> yes })).certainlyExempt)
    // Actual automaton activation changes with route-local context, without rebuilding its geometry.
    val machine = Machine(listOf(Machine.Rule(1, 77, Machine.Kind.NO, listOf(listOf(1L, 2L, 3L)))))
    fun traverse(ctx: RestrictionCondition.Context): Machine.Step {
        val resolved = Policy.resolve(tags, ctx)
        var state = machine.start
        for (arc in listOf(1L, 2L)) state = machine.advance(state, arc) { resolved.activation("no_left_turn") }.next!!
        return machine.advance(state, 3) { resolved.activation("no_left_turn") }
    }
    check(traverse(context).allowed == unknown)
    check(traverse(context.copy(holiday = { _, _ -> no })).allowed == no)
    check(traverse(context.copy(instant = Instant.parse("2026-09-14T20:00:00Z"))).allowed == yes)
    val mutable = mutableMapOf("type" to "restriction", "restriction" to "no_left_turn")
    val original = Policy.resolve(mutable, context); mutable["restriction"] = "none"
    check(original.sourceTags["restriction"] == "no_left_turn" && original.activation("no_left_turn") == yes)
    println("Passenger turn applicability: transport/legacy mode scope, precedence, except lists, conditional/holiday/unknown alternatives, malformed values, on-red preservation and runtime-context automaton activation PASS; special-use profiles/signal waiting/full graph integration remain pending")
}
