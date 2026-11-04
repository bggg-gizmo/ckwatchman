package com.bggg.watchman.routing

import java.util.ArrayDeque
import com.bggg.watchman.routing.ConditionalRestriction.Match

/**
 * Search-state automaton over resolved, directed graph-arc IDs. The compiler must resolve actual
 * relation connectivity/directions and retain missing or unsupported relations before calling this.
 * NO paths prohibit completing the path. ONLY paths constrain every continuation after approach,
 * including intermediate via-way exits. Alternative paths within one rule are allowed as a union.
 * Search labels must include [State], not just the current graph node, to retain approach history.
 */
class TurnRestrictionMachine(input: List<Rule>, maxNodes: Int = 250_000) {
    enum class Kind { NO, ONLY }
    /** key identifies a compiled rule; multiple conditional variants may share an OSM relation ID. */
    data class Rule(val key: Int, val relationId: Long, val kind: Kind, val paths: List<List<Long>>)
    class State internal constructor(val node: Int, internal val owner: Any) {
        override fun equals(other: Any?) = other is State && node == other.node && owner === other.owner
        override fun hashCode() = 31 * System.identityHashCode(owner) + node
        override fun toString() = "TurnState($node)"
    }
    data class Step(val allowed: Match, val next: State?, val blockedRelations: Set<Long>, val unresolvedRelations: Set<Long>)
    private class Node {
        val children = HashMap<Long, Int>()
        var failure = 0
        val noRules = ArrayList<Int>()
        val onlyRules = HashMap<Int, MutableSet<Long>>()
    }
    private val identity = Any()
    private val nodes = ArrayList<Node>()
    private val rules = snapshot(input)
    val start: State get() = State(0, identity)
    val stateCount: Int get() = nodes.size

    private fun snapshot(input: List<Rule>): List<Rule> {
        require(input.size <= 100_000) { "Turn-rule count exceeds budget" }
        var arcs = 0L
        for (rule in input) {
            require(rule.paths.size in 1..4096) { "Missing or excessive resolved paths" }
            for (path in rule.paths) {
                require(path.size in 2..4096) { "Invalid resolved path length" }
                arcs += path.size
                require(arcs <= 4_000_000) { "Turn-rule arc budget exceeded" }
            }
        }
        return input.map { it.copy(paths = it.paths.map { path -> path.toList() }) }
    }

    init {
        require(maxNodes in 1..2_000_000) { "Invalid turn-machine node budget" }
        require(rules.size <= 100_000) { "Turn-rule count exceeds budget" }
        require(rules.map { it.key }.distinct().size == rules.size) { "Duplicate compiled rule key" }
        nodes += Node()
        var arcCount = 0L
        for ((ruleIndex, rule) in rules.withIndex()) {
            require(rule.paths.isNotEmpty() && rule.paths.size <= 4096) { "Missing or excessive resolved paths for relation ${rule.relationId}" }
            val terminals = HashSet<Int>()
            val prefixes = HashSet<Int>()
            for (path in rule.paths) {
                require(path.size in 2..4096 && path.all { it > 0 }) { "Invalid directed path for relation ${rule.relationId}" }
                arcCount += path.size
                require(arcCount <= 4_000_000) { "Turn-rule arc budget exceeded" }
                var node = 0
                for ((index, arc) in path.withIndex()) {
                    if (index > 0 && rule.kind == Kind.ONLY) {
                        nodes[node].onlyRules.getOrPut(ruleIndex) { linkedSetOf() }.add(arc)
                        prefixes += node
                    }
                    node = nodes[node].children[arc] ?: run {
                        require(nodes.size < maxNodes) { "Turn-machine node budget exceeded; no restriction may be discarded" }
                        val child = nodes.size
                        nodes += Node()
                        nodes[node].children[arc] = child
                        child
                    }
                }
                terminals += node
                if (rule.kind == Kind.NO && ruleIndex !in nodes[node].noRules) nodes[node].noRules += ruleIndex
            }
            require(rule.kind != Kind.ONLY || terminals.none { it in prefixes }) {
                "Ambiguous prefix-complete ONLY paths for relation ${rule.relationId}"
            }
        }
        val queue = ArrayDeque<Int>()
        for (child in nodes[0].children.values) queue.add(child)
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            for ((arc, child) in nodes[parent].children) {
                var suffix = nodes[parent].failure
                while (suffix != 0 && arc !in nodes[suffix].children) suffix = nodes[suffix].failure
                nodes[child].failure = nodes[suffix].children[arc] ?: 0
                queue.add(child)
            }
        }
    }

    /** Activation is evaluated at this traversal's route-local time, never frozen to build time. */
    fun advance(state: State, arc: Long, activation: (Rule) -> Match): Step {
        require(state.owner === identity && state.node in nodes.indices) { "State belongs to another turn machine" }
        require(arc > 0) { "Directed arc IDs must be positive" }
        val blocked = linkedSetOf<Long>()
        val uncertain = linkedSetOf<Long>()
        val matches = HashMap<Int, Match>()
        fun violation(index: Int) {
            val rule = rules[index]
            val active = matches.getOrPut(index) { try { activation(rule) } catch (_: Exception) { Match.UNKNOWN } }
            when (active) { Match.YES -> blocked += rule.relationId; Match.UNKNOWN -> uncertain += rule.relationId; Match.NO -> Unit }
        }
        var cursor = state.node
        while (cursor != 0) {
            for ((rule, allowed) in nodes[cursor].onlyRules) if (arc !in allowed) violation(rule)
            cursor = nodes[cursor].failure
        }
        cursor = state.node
        while (cursor != 0 && arc !in nodes[cursor].children) cursor = nodes[cursor].failure
        val next = nodes[cursor].children[arc] ?: 0
        cursor = next
        while (cursor != 0) {
            nodes[cursor].noRules.forEach(::violation)
            cursor = nodes[cursor].failure
        }
        val decision = when { blocked.isNotEmpty() -> Match.NO; uncertain.isNotEmpty() -> Match.UNKNOWN; else -> Match.YES }
        return Step(decision, if (decision == Match.YES) State(next, identity) else null, blocked.toSet(), uncertain.toSet())
    }
}
