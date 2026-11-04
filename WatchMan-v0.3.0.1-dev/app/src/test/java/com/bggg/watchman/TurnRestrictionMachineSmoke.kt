package com.bggg.watchman

import com.bggg.watchman.routing.TurnRestrictionMachine as Machine
import com.bggg.watchman.routing.ConditionalRestriction.Match

fun turnRestrictionMachineSmoke() {
    fun rule(key: Int, kind: Machine.Kind, vararg paths: List<Long>) = Machine.Rule(key, 1000L + key, kind, paths.toList())
    val no = Machine.Kind.NO; val only = Machine.Kind.ONLY
    val rules = listOf(rule(1, no, listOf(1, 2, 3)), rule(2, only, listOf(4, 5, 6), listOf(4, 5, 7)),
        rule(3, no, listOf(5, 7, 8)), rule(4, no, listOf(2, 2, 3)))
    val machine = Machine(rules)
    fun drive(arcs: List<Long>, activation: (Machine.Rule) -> Match = { Match.YES }): Machine.Step {
        var state = machine.start
        var result = Machine.Step(Match.YES, state, emptySet(), emptySet())
        for (arc in arcs) {
            result = machine.advance(state, arc, activation)
            state = result.next ?: return result
        }
        return result
    }
    check(drive(listOf(1, 2, 3)).blockedRelations == setOf(1001L))
    check(drive(listOf(9, 2, 3)).allowed == Match.YES) // Same node/exit, different approach.
    check(drive(listOf(1, 2, 9, 3)).allowed == Match.YES) // NO may depart the restricted corridor early.
    check(drive(listOf(4, 9)).blockedRelations == setOf(1002L))
    check(drive(listOf(4, 5, 9)).blockedRelations == setOf(1002L)) // ONLY cannot leave an intermediate via way.
    check(drive(listOf(4, 5, 6, 9)).allowed == Match.YES)
    check(drive(listOf(4, 5, 7)).allowed == Match.YES)
    check(drive(listOf(4, 5, 7, 8)).blockedRelations == setOf(1003L)) // Suffix match survives longer prefix.
    check(drive(listOf(2, 2, 2, 3)).blockedRelations == setOf(1004L)) // Overlapping repeated prefix.
    check(drive(listOf(1, 2, 3)) { Match.NO }.allowed == Match.YES)
    val uncertain = drive(listOf(1, 2, 3)) { Match.UNKNOWN }
    check(uncertain.allowed == Match.UNKNOWN && uncertain.next == null && uncertain.unresolvedRelations == setOf(1001L))
    check(drive(listOf(4, 9)) { error("Condition unavailable") }.allowed == Match.UNKNOWN)
    val temporal = machine.advance(machine.advance(machine.start, 1) { Match.NO }.next!!, 2) { Match.NO }
    check(machine.advance(temporal.next!!, 3) { Match.YES }.allowed == Match.NO) // Restriction activates during traversal.
    val parallelRules = Machine(listOf(rule(20, no, listOf(1, 2)), rule(21, no, listOf(1, 2))))
    val a = parallelRules.advance(parallelRules.start, 1) { Match.YES }.next!!
    val both = parallelRules.advance(a, 2) { if (it.key == 20) Match.YES else Match.UNKNOWN }
    check(both.allowed == Match.NO && both.blockedRelations == setOf(1020L) && both.unresolvedRelations == setOf(1021L))
    check(runCatching { machine.advance(Machine(emptyList()).start, 1) { Match.YES } }.isFailure)
    check(runCatching { machine.advance(machine.start, 0) { Match.YES } }.isFailure)
    check(runCatching { Machine(listOf(rule(1, no, listOf(1)))) }.isFailure)
    check(runCatching { Machine(listOf(rule(1, no, listOf(1, 2)), rule(1, only, listOf(2, 3)))) }.isFailure)
    check(runCatching { Machine(listOf(rule(1, only, listOf(1, 2), listOf(1, 2, 3)))) }.isFailure)
    check(runCatching { Machine(listOf(rule(1, no, listOf(1, 2))), maxNodes = 2) }.isFailure)
    val path = mutableListOf(1L, 2L)
    val immutable = Machine(listOf(rule(31, no, path)))
    path[1] = 9
    check(immutable.advance(immutable.advance(immutable.start, 1) { Match.YES }.next!!, 2) { Match.YES }.allowed == Match.NO)

    // Independent brute-force suffix oracle across all 7,381 sequences up to length4 in a 9-arc alphabet.
    fun oracle(history: List<Long>, arc: Long): Set<Long> {
        val violated = linkedSetOf<Long>()
        for (rule in rules) {
            if (rule.kind == no) {
                if (rule.paths.any { path -> (history + arc).takeLast(path.size) == path }) violated += rule.relationId
            } else {
                for (length in 1 until rule.paths.maxOf { it.size }) {
                    if (history.size < length) continue
                    val prefix = history.takeLast(length)
                    val matching = rule.paths.filter { it.size > length && it.take(length) == prefix }
                    if (matching.isNotEmpty() && matching.none { it[length] == arc }) violated += rule.relationId
                }
            }
        }
        return violated
    }
    var checked = 0
    fun walk(history: List<Long>, state: Machine.State, remaining: Int) {
        if (remaining == 0) return
        for (arc in 1L..9L) {
            val result = machine.advance(state, arc) { Match.YES }
            check(result.blockedRelations == oracle(history, arc)) { "Turn-state mismatch: $history + $arc" }
            checked++
            result.next?.let { walk(history + arc, it, remaining - 1) }
        }
    }
    walk(emptyList(), machine.start, 4)
    check(checked > 5000)
    println("Turn restriction automaton: approach/via-way history, NO/ONLY alternatives, early exits, overlaps, temporal/unknown activation, provenance, state ownership, resource limits and $checked brute-force comparisons PASS; relation-to-arc compilation and graph search integration pending")
}
