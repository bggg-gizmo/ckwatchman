package com.bggg.watchman

import com.bggg.watchman.routing.ConditionalRestriction as Rule

fun conditionalRestrictionSmoke() {
    fun parsed(raw: String): Rule.Parsed = Rule.parse(raw).also {
        check(it.raw == raw && it.problem == null) { "Unexpected parse failure: $it" }
    }
    val observed = "no_left_turn @ (Mo-Fr 07:00-09:30,16:00-18:30; PH off)"
    check(parsed(observed).clauses.single() == Rule.Clause("no_left_turn", "(Mo-Fr 07:00-09:30,16:00-18:30; PH off)"))
    check(parsed("no@(Mo - Fr 07:00-9:30; SH off)").clauses.size == 1)
    check(parsed("55 mph @ (Mo 06:00-24:00; Tu-Fr 00:00-24:00); 30 mph @ wet").clauses.size == 2)
    check(parsed("no @ (Mo-Fr AND (weight>7.5))").clauses.single().condition == "(Mo-Fr AND (weight>7.5))")
    check(parsed("no @ (Mo-Fr 07:00-09:30 \"event; contact@school\"); yes @ Sa-Su").clauses.size == 2)
    check(parsed("no @ \"event \\\"special; @ time\\\"\"").clauses.size == 1)
    check(parsed("yes|designated @ Mo-Fr 07:00-09:00").clauses.single().value == "yes|designated")
    check(parsed("no @ (sunrise-sunset)").clauses.size == 1) // syntax accepted, predicate is NOT evaluated here

    val malformed = listOf("", "  ", "no", "15 mph", "no @", "@ wet", "no @ ()", "no @ ( )",
        "no @ (Mo-Fr (07:00-09:30; PH off)", "no @ (wet))", "no @ wet;", ";no @ wet",
        "no @ wet @ snow", "no @ \"unfinished", "(no) @ wet", "\"no\" @ wet",
        "no @ " + "(".repeat(33) + "wet" + ")".repeat(33), "no @ " + "x".repeat(16_384),
        (1..257).joinToString(";") { "no @ wet" })
    for (raw in malformed) {
        val result = Rule.parse(raw)
        check(result.raw == raw && result.problem != null && result.clauses.isEmpty()) { "Accepted malformed syntax: $raw" }
        val resolved = Rule.resolve(result, "yes") { error("Must not evaluate malformed input") }
        check(resolved.certainValue == null && resolved.syntaxProblem != null && resolved.possibleValues.isEmpty())
    }

    val multiple = parsed("no @ first; destination @ second; yes @ third")
    fun resolve(first: Rule.Match, second: Rule.Match, third: Rule.Match) = Rule.resolve(multiple, "private") {
        when (it) { "first" -> first; "second" -> second; "third" -> third; else -> error("Unexpected predicate") }
    }
    check(resolve(Rule.Match.YES, Rule.Match.YES, Rule.Match.NO).certainValue == "destination")
    check(resolve(Rule.Match.NO, Rule.Match.NO, Rule.Match.NO).certainValue == "private")
    check(resolve(Rule.Match.UNKNOWN, Rule.Match.NO, Rule.Match.NO).possibleValues == setOf("private", "no"))
    check(resolve(Rule.Match.UNKNOWN, Rule.Match.NO, Rule.Match.NO).certainValue == null)
    check(resolve(Rule.Match.UNKNOWN, Rule.Match.YES, Rule.Match.NO).certainValue == "destination")
    check(resolve(Rule.Match.YES, Rule.Match.UNKNOWN, Rule.Match.NO).possibleValues == setOf("no", "destination"))
    check(resolve(Rule.Match.UNKNOWN, Rule.Match.UNKNOWN, Rule.Match.YES).certainValue == "yes")
    check(resolve(Rule.Match.UNKNOWN, Rule.Match.UNKNOWN, Rule.Match.UNKNOWN).possibleValues == setOf("private", "no", "destination", "yes"))
    val unavailable = Rule.resolve(parsed("yes @ holiday"), "no") { throw IllegalStateException("Calendar unavailable") }
    check(unavailable.certainValue == null && unavailable.possibleValues == setOf("no", "yes") && unavailable.unknownConditions == listOf("holiday"))
    check(Rule.resolve(parsed("no @ unknown"), "no") { Rule.Match.UNKNOWN }.certainValue == "no")

    // Exhaustively compare three-valued resolution with every concrete completion of unknown predicates.
    for (a in Rule.Match.entries) for (b in Rule.Match.entries) for (c in Rule.Match.entries) {
        val input = listOf(a, b, c)
        val expected = linkedSetOf<String>()
        for (mask in 0..7) {
            var value = "private"
            for (index in input.indices) {
                val matches = when (input[index]) { Rule.Match.YES -> true; Rule.Match.NO -> false; Rule.Match.UNKNOWN -> mask and (1 shl index) != 0 }
                if (matches) value = multiple.clauses[index].value
            }
            expected += value
        }
        check(resolve(a, b, c).possibleValues == expected) { "Resolution lost or invented a possible restriction" }
    }
    println("Conditional restriction syntax/resolution: actual nested-semicolon form, quoted comments, malformed/bounded input, ordered precedence, unknown/error preservation and all 27 three-valued combinations PASS; calendar/access/turn evaluation not implemented by this primitive")
}
