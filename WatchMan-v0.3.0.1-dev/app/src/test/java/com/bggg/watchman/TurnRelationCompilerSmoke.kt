package com.bggg.watchman

import com.bggg.watchman.routing.TurnRelationCompiler as Compiler
import com.bggg.watchman.routing.TurnRestrictionMachine as Machine
import com.bggg.watchman.routing.ConditionalRestriction.Match

fun turnRelationCompilerSmoke() {
    val ways = listOf(
        Compiler.Way(1, listOf(10, 20)), Compiler.Way(2, listOf(20, 30, 40)), Compiler.Way(3, listOf(40, 50)),
        Compiler.Way(4, listOf(20, 60)), Compiler.Way(5, listOf(40, 70)), Compiler.Way(6, listOf(80, 40)),
        Compiler.Way(7, listOf(90, 10)), Compiler.Way(8, listOf(60, 40)), Compiler.Way(9, listOf(20, 91, 20)),
        Compiler.Way(10, listOf(101, 102)), Compiler.Way(11, listOf(20, 20)), Compiler.Way(12, listOf(31, 40)),
        Compiler.Way(13, listOf(30, 95)), Compiler.Way(14, listOf(30, 35, 36)), Compiler.Way(15, listOf(35, 96))
    ).associateBy { it.id }
    val geometry = object : Compiler.Geometry {
        override fun way(id: Long) = ways[id]
        override fun arc(wayId: Long, segmentOrdinal: Int, forward: Boolean) = wayId * 1000 + segmentOrdinal * 2 + if (forward) 1L else 2L
    }
    fun member(role: String, id: Long, type: Char = 'w') = Compiler.Member(type, id, role)
    fun compile(value: String, vararg members: Compiler.Member) = Compiler.compile(123, value, members.toList(), geometry)
    val basic = listOf(member("from", 1), member("via", 20, 'n'), member("to", 4))
    check(Compiler.compile(123, "no_right_turn", basic, geometry).paths == listOf(listOf(1001L, 4001L)))
    val reverse = compile("no_left_turn", member("from", 4), member("via", 20, 'n'), member("to", 1))
    check(reverse.paths == listOf(listOf(4002L, 1002L)))
    val uturn = compile("no_u_turn", member("from", 1), member("via", 20, 'n'), member("to", 1))
    check(uturn.paths == listOf(listOf(1001L, 1002L)))
    val via = compile("no_right_turn", member("from", 1), member("via", 2), member("to", 3))
    check(via.resolved && via.paths == listOf(listOf(1001L, 2001L, 2003L, 3001L)))
    val reversedVia = compile("no_left_turn", member("from", 3), member("via", 2), member("to", 1))
    check(reversedVia.paths == listOf(listOf(3002L, 2004L, 2002L, 1002L)))
    val interior = compile("no_right_turn", member("from", 1), member("via", 2), member("to", 13))
    check(interior.paths == listOf(listOf(1001L, 2001L, 13001L)))
    val interiorReverse = compile("no_left_turn", member("from", 13), member("via", 2), member("to", 1))
    check(interiorReverse.paths == listOf(listOf(13002L, 2002L, 1002L)))
    val interiorChain = compile("only_right_turn", member("from", 1), member("via", 14), member("via", 2), member("to", 15))
    check(interiorChain.paths == listOf(listOf(1001L, 2001L, 14001L, 15001L)))
    val multi = compile("only_straight_on", member("to", 3), member("via", 2), member("from", 7), member("via", 1))
    check(multi.paths == listOf(listOf(7001L, 1001L, 2001L, 2003L, 3001L))) // Actual connectivity, not member order.
    val entry = compile("no_entry", member("from", 1), member("from", 4), member("via", 20, 'n'), member("to", 2))
    check(entry.resolved && entry.paths.toSet() == setOf(listOf(1001L, 2001L), listOf(4002L, 2001L)))
    val exit = compile("no_exit", member("from", 2), member("via", 40, 'n'), member("to", 3), member("to", 5))
    check(exit.resolved && exit.paths.toSet() == setOf(listOf(2003L, 3001L), listOf(2003L, 5001L)))
    fun fails(result: Compiler.Compiled) {
        check(!result.resolved && result.paths.isEmpty() && result.problems.isNotEmpty()) { "Unexpected compilation: $result" }
        check(runCatching { result.rule(1) }.isFailure)
    }
    fails(Compiler.compile(123, "no_right_turn_on_red", basic, geometry)) // Not an unconditional forbidden turn.
    fails(compile("no_left_turn", member("from", 1), member("from", 4), member("via", 20, 'n'), member("to", 2)))
    fails(compile("only_left_turn", member("from", 1), member("via", 20, 'n'), member("to", 1)))
    fails(compile("no_left_turn", member("from", 1), member("via", 30, 'n'), member("to", 2)))
    fails(compile("no_left_turn", member("from", 1), member("via", 2), member("via", 20, 'n'), member("to", 3)))
    fails(compile("no_left_turn", member("from", 1), member("via", 999), member("to", 3)))
    fails(compile("no_left_turn", member("from", 1), member("via", 10), member("to", 3)))
    fails(compile("no_left_turn", member("from", 1), member("via", 9), member("to", 3)))
    fails(compile("no_left_turn", member("from", 1), member("via", 11), member("to", 3)))
    fails(compile("no_left_turn", member("from", 1), member("via", 2), member("via", 2), member("to", 3)))
    fails(compile("no_left_turn", member("from", 1), member("via", 12), member("to", 3))) // Different node IDs never merged.
    val unavailable = object : Compiler.Geometry {
        override fun way(id: Long): Compiler.Way? = throw IllegalStateException("Shard missing")
        override fun arc(wayId: Long, segmentOrdinal: Int, forward: Boolean): Long? = null
    }
    fails(Compiler.compile(123, "no_right_turn", basic, unavailable))
    val missingArc = object : Compiler.Geometry {
        override fun way(id: Long) = ways[id]
        override fun arc(wayId: Long, segmentOrdinal: Int, forward: Boolean): Long? = null
    }
    fails(Compiler.compile(123, "no_right_turn", basic, missingArc))
    val noMachine = Machine(listOf(via.rule(1)))
    var state = noMachine.start
    for (arc in via.paths.single().dropLast(1)) state = noMachine.advance(state, arc) { Match.YES }.next!!
    check(noMachine.advance(state, 3001) { Match.YES }.blockedRelations == setOf(123L))
    check(noMachine.advance(state, 5001) { Match.YES }.allowed == Match.YES)
    val onlyMachine = Machine(listOf(multi.rule(1)))
    var onlyState = onlyMachine.start
    for (arc in listOf(7001L, 1001L)) onlyState = onlyMachine.advance(onlyState, arc) { Match.YES }.next!!
    check(onlyMachine.advance(onlyState, 4001) { Match.YES }.allowed == Match.NO)
    println("OSM turn relation compiler: exact node identity, direction/segment order, via-node/via-way chain, reordered members, U-turn, no_entry/no_exit, missing/invalid geometry and compiled automaton integration PASS; real-shard compiler audit and full graph routing still pending")
}
