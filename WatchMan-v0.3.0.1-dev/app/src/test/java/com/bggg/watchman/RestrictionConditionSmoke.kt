package com.bggg.watchman

import com.bggg.watchman.routing.RestrictionCondition as Condition
import com.bggg.watchman.routing.ConditionalRestriction as Rule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.math.BigDecimal
import java.util.TimeZone

fun restrictionConditionSmoke() {
    val zone = ZoneId.of("America/Los_Angeles")
    fun context(time: String) = Condition.Context(Instant.parse(time), zone)
    val monday = context("2026-09-14T15:00:00Z") // 08:00 PDT, explicitly not host time
    fun expect(raw: String, expected: Rule.Match, ctx: Condition.Context = monday) {
        val result = Condition.evaluate(raw, ctx)
        check(result.match == expected) { "$raw at ${ctx.instant}/${ctx.routeZone}: expected $expected, got $result" }
        if (expected == Rule.Match.UNKNOWN) check(result.unresolved.isNotEmpty())
    }
    val yes = Rule.Match.YES; val no = Rule.Match.NO; val unknown = Rule.Match.UNKNOWN
    expect("Mo-Fr 07:00-09:30,16:00-18:30", yes)
    expect("Mo-Fr 07:00-09:30,16:00-18:30", no, context("2026-09-14T16:30:00Z"))
    expect("Mo-Fr 07:00-09:30", yes, context("2026-09-14T14:00:00Z"))
    expect("Mo-Fr 07:00-09:30", no, context("2026-09-14T13:59:59.999999999Z"))
    expect("Mo-Fr 07:00-09:30", no, monday.copy(routeZone = ZoneId.of("America/New_York")))
    expect("Mo-Fr 07:00-09:30", unknown, monday.copy(routeZone = null))
    expect("Fr-Mo", yes); expect("Tu-Th", no); expect("Aug-Oct", yes); expect("Nov-Feb", no)
    expect("24/7", yes); expect("00:00-24:00", yes); expect("Mo - Fr 7:00-9:30", yes)
    expect("Fr 22:00-02:00", yes, context("2026-09-12T08:00:00Z")) // Saturday 01:00, Friday's span
    expect("Fr 22:00-02:00", no, context("2026-09-11T08:00:00Z"))
    expect("Fr 22:00-02:00", no, context("2026-09-12T09:00:00Z"))
    expect("Dec-Feb 22:00-02:00", yes, context("2026-03-01T09:00:00Z")) // February's overnight span
    expect("Su 01:00-02:00", yes, context("2026-11-01T08:30:00Z")) // both fall-back occurrences
    expect("Su 01:00-02:00", yes, context("2026-11-01T09:30:00Z"))
    expect("Su 02:00-03:00", no, context("2026-03-08T10:00:00Z")) // skipped local hour

    val actual = "(Mo-Fr 07:00-09:30,16:00-18:30; PH off)"
    expect(actual, unknown)
    expect(actual, yes, monday.copy(holiday = { _, _ -> no }))
    expect(actual, no, monday.copy(holiday = { _, _ -> yes }))
    expect(actual, unknown, monday.copy(holiday = { _, _ -> error("Calendar not loaded") }))
    expect(actual, no, context("2026-09-14T18:00:00Z")) // unknown calendar cannot activate a closed window
    expect("Mo-Fr;PH off;SH off", unknown, monday.copy(holiday = { kind, _ -> if (kind == "PH") no else unknown }))
    expect("Sa,Su,PH", unknown)
    expect("Sa,Su,PH", yes, monday.copy(holiday = { _, _ -> yes }))
    val observedDates = ArrayList<LocalDate>()
    expect("PH 22:00-02:00", yes, context("2026-09-15T08:00:00Z").copy(holiday = { _, date ->
        observedDates += date; if (date == LocalDate.parse("2026-09-14")) yes else no
    }))
    check(observedDates == listOf(LocalDate.parse("2026-09-14")))

    val known = monday.copy(facts = mapOf("wet" to yes, "snow" to no), quantities = mapOf("weight" to BigDecimal("7.50"), "axles" to BigDecimal("2")))
    expect("wet", yes, known); expect("snow", no, known); expect("hazmat", unknown, known)
    expect("weight >= 7.5", yes, known); expect("weight>7.5", no, known); expect("weight=7.500", yes, known)
    expect("axles < 3", yes, known); expect("length>5", unknown, known)
    expect("weight>2", unknown, known.copy(quantities = mapOf("weight" to BigDecimal("-1"))))
    expect("(Mo-Fr 07:00-09:30 AND (weight>=7.5 AND wet))", yes, known)
    expect("wet AND unknown_event", unknown, known); expect("snow AND unknown_event", no, known)
    expect("Mo-Fr 07:00-09:30 \"when school is active\"", unknown)
    expect("Mo-Fr 10:00-11:00 \"when school is active\"", no)
    expect("\"event; contact@school\"", unknown)
    for (raw in listOf("", "()", "(Mo-Fr", "Mo-Fr)", "Mo-Fr 07:99-09:30", "24:00-25:00", "08:00-08:00",
        "Mo-Fr 07:00-09:00; Sa 10:00-12:00", "Mo-Fr; PH off; nonsense", "sunrise-sunset", "2026 Sep 14",
        "Mo-Fr 08:00-09:00 garbage", "Mo-Fr OR PH", "weight > 7 tonnes", "Mo-Fr AND", "( ".repeat(34) + "wet" + ")".repeat(34), "x".repeat(16_385))) expect(raw, unknown)
    val hostZone = TimeZone.getDefault()
    try {
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
        expect("Mo-Fr 07:00-09:30", yes)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        expect("Mo-Fr 07:00-09:30", yes)
    } finally { TimeZone.setDefault(hostZone) }
    val parsed = Rule.parse("no_left_turn @ $actual")
    check(Rule.resolve(parsed, "none") { Condition.evaluate(it, monday).match }.possibleValues == setOf("none", "no_left_turn"))
    check(Rule.resolve(parsed, "none") { Condition.evaluate(it, monday.copy(holiday = { _, _ -> no })).match }.certainValue == "no_left_turn")
    println("Route-local restriction conditions: zones/DST, boundaries/overnight dates, weekday/month selectors, holiday unknowns/exclusions, facts/comparisons/AND/comments, malformed bounds and resolver integration PASS; full opening-hours grammar and graph integration remain pending")
}
