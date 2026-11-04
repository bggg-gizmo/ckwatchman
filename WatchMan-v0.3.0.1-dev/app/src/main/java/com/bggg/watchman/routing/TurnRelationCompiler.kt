package com.bggg.watchman.routing

/**
 * Resolves OSM relation geometry by original node identity, not coordinate proximity or turn angle.
 * This compiles one restriction value. Mode exceptions/conditional activation and arc legality are
 * supplied separately; a failed compilation must remain an unresolved graph constraint.
 */
object TurnRelationCompiler {
    data class Member(val type: Char, val id: Long, val role: String)
    data class Way(val id: Long, val nodes: List<Long>)
    interface Geometry {
        fun way(id: Long): Way?
        /** Stable directed topology arc ID, including directions later filtered by access policy. */
        fun arc(wayId: Long, segmentOrdinal: Int, forward: Boolean): Long?
    }
    data class Compiled(
        val relationId: Long,
        val rawRestriction: String,
        val members: List<Member>,
        val kind: TurnRestrictionMachine.Kind?,
        val paths: List<List<Long>>,
        val problems: List<String>
    ) {
        val resolved: Boolean get() = kind != null && paths.isNotEmpty() && problems.isEmpty()
        fun rule(key: Int): TurnRestrictionMachine.Rule {
            check(resolved) { "Unresolved relation $relationId cannot become a turn rule" }
            return TurnRestrictionMachine.Rule(key, relationId, kind!!, paths)
        }
    }
    private val noValues = setOf("no_left_turn", "no_right_turn", "no_straight_on", "no_u_turn", "no_entry", "no_exit")
    private val onlyValues = setOf("only_left_turn", "only_right_turn", "only_straight_on", "only_u_turn")
    private class Invalid(message: String) : Exception(message)

    fun compile(relationId: Long, restriction: String, members: List<Member>, geometry: Geometry): Compiled {
        val rawMembers = members.toList()
        val kind = when (restriction) {
            in noValues -> TurnRestrictionMachine.Kind.NO
            in onlyValues -> TurnRestrictionMachine.Kind.ONLY
            else -> null
        }
        fun failure(reason: String) = Compiled(relationId, restriction, rawMembers, kind, emptyList(), listOf(reason))
        if (kind == null) return failure("Unsupported restriction value: $restriction")
        if (relationId <= 0 || members.size !in 3..256) return failure("Invalid relation identity/member count")
        try {
            fun ensure(ok: Boolean, reason: String) { if (!ok) throw Invalid(reason) }
            ensure(members.all { it.id > 0 }, "Invalid member identity")
            ensure(members.none { it.role !in setOf("from", "via", "to", "location_hint") }, "Unrecognized relation member role")
            val from = members.filter { it.role == "from" }
            val to = members.filter { it.role == "to" }
            val via = members.filter { it.role == "via" }
            ensure(from.isNotEmpty() && to.isNotEmpty() && via.isNotEmpty(), "Missing from/via/to member")
            ensure(from.all { it.type == 'w' } && to.all { it.type == 'w' }, "from/to must reference ways")
            ensure(from.size == 1 || restriction == "no_entry", "Multiple from ways only valid for no_entry")
            ensure(to.size == 1 || restriction == "no_exit", "Multiple to ways only valid for no_exit")
            ensure(from.map { it.id }.distinct().size == from.size && to.map { it.id }.distinct().size == to.size, "Duplicate from/to members")
            val viaNode = via.singleOrNull()?.takeIf { it.type == 'n' }?.id
            ensure(viaNode != null || via.all { it.type == 'w' }, "via must be one node or connecting ways")
            ensure(via.size <= 16, "Via-way count exceeds bounded compiler budget")
            ensure(via.map { it.id }.distinct().size == via.size, "Duplicate via members")
            val ways = linkedMapOf<Long, Way>()
            var retainedNodes = 0L
            for (member in from + to + if (viaNode == null) via else emptyList()) {
                if (member.id in ways) continue
                val way = geometry.way(member.id) ?: throw Invalid("Missing way member ${member.id}; shard resolution required")
                ensure(way.id == member.id && way.nodes.size in 2..100_000 && way.nodes.all { it > 0 }, "Invalid way geometry ${member.id}")
                ensure(way.nodes.first() != way.nodes.last(), "Closed member ${member.id} requires explicit junction splitting")
                ensure(way.nodes.zipWithNext().none { it.first == it.second }, "Degenerate member segment ${member.id}")
                retainedNodes += way.nodes.size
                ensure(retainedNodes <= 250_000, "Member geometry budget exceeded")
                ways[member.id] = way.copy(nodes = way.nodes.toList())
            }
            fun arc(way: Way, ordinal: Int, forward: Boolean): Long {
                val id = geometry.arc(way.id, ordinal, forward)
                ensure(id != null && id > 0, "Missing directed arc ${way.id}:$ordinal:$forward")
                return id!!
            }
            fun incoming(way: Way, node: Long): Long? = when (node) {
                way.nodes.last() -> arc(way, way.nodes.size - 2, true)
                way.nodes.first() -> arc(way, 0, false)
                else -> null
            }
            fun outgoing(way: Way, node: Long): Long? = when (node) {
                way.nodes.first() -> arc(way, 0, true)
                way.nodes.last() -> arc(way, way.nodes.size - 2, false)
                else -> null
            }
            val paths = linkedSetOf<List<Long>>()
            var expansions = 0
            for (fromMember in from) for (toMember in to) {
                val fromWay = ways.getValue(fromMember.id)
                val toWay = ways.getValue(toMember.id)
                val pairPaths = linkedSetOf<List<Long>>()
                if (viaNode != null) {
                    val entry = incoming(fromWay, viaNode)
                    val exit = outgoing(toWay, viaNode)
                    ensure(entry != null && exit != null, "via node $viaNode is not a from/to endpoint; resolve/split source junction")
                    ensure(fromWay.id != toWay.id || restriction in setOf("no_u_turn", "only_u_turn"), "Same-way node restriction is not an unambiguous U-turn")
                    pairPaths += listOf(entry!!, exit!!)
                } else {
                    val viaIds = via.map { it.id }.toSet()
                    ensure(fromWay.id !in viaIds && toWay.id !in viaIds, "Approach/exit repeats a via member")
                    fun walk(node: Long, remaining: Set<Long>, path: List<Long>) {
                        expansions++
                        ensure(expansions <= 20_000, "Relation connectivity search budget exceeded")
                        ensure(path.size <= 4095, "Resolved turn path exceeds arc budget")
                        if (remaining.isEmpty()) {
                            outgoing(toWay, node)?.let { pairPaths += path + it }
                            ensure(pairPaths.size <= 1, "Ambiguous via-way connectivity; no arbitrary path selected")
                            return
                        }
                        for (id in remaining.sorted()) {
                            val way = ways.getValue(id)
                            val after = remaining - id
                            val connectors = if (after.isEmpty()) setOf(toWay.nodes.first(), toWay.nodes.last())
                                else after.flatMap { ways.getValue(it).nodes }.toSet()
                            // A via way need not have been split at each relevant junction. Follow exact
                            // contiguous subpaths to shared original nodes, never coordinate shortcuts.
                            for (entry in way.nodes.indices.filter { way.nodes[it] == node }) for (increment in listOf(1, -1)) {
                                var position = entry
                                val prefix = ArrayList<Long>()
                                while (position + increment in way.nodes.indices) {
                                    ensure(path.size + prefix.size < 4095, "Resolved turn path exceeds arc budget")
                                    val nextPosition = position + increment
                                    prefix += arc(way, minOf(position, nextPosition), increment > 0)
                                    position = nextPosition
                                    val nextNode = way.nodes[position]
                                    if (nextNode in connectors) walk(nextNode, after, path + prefix)
                                }
                            }
                        }
                    }
                    for (node in listOf(fromWay.nodes.first(), fromWay.nodes.last())) {
                        walk(node, viaIds, listOf(incoming(fromWay, node)!!))
                    }
                    ensure(pairPaths.size == 1, "Disconnected or unresolved via-way chain")
                }
                paths += pairPaths
                ensure(paths.size <= 4096, "Resolved path count exceeds budget")
            }
            return Compiled(relationId, restriction, rawMembers, kind, paths.toList(), emptyList())
        } catch (error: Invalid) {
            return failure(error.message ?: "Invalid restriction geometry")
        } catch (error: Exception) {
            return failure("Geometry unavailable: ${error.javaClass.simpleName}")
        }
    }
}
