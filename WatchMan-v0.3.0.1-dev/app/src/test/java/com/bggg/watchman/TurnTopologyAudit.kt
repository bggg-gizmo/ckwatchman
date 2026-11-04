package com.bggg.watchman

import com.bggg.watchman.routing.ConditionalRestriction
import com.bggg.watchman.routing.TurnRelationCompiler as Compiler
import com.bggg.watchman.routing.TurnRestrictionMachine
import com.bggg.watchman.routing.TurnRestrictionPolicy
import com.bggg.watchman.routing.RestrictionCondition
import com.bggg.watchman.routing.ConditionalRestriction.Match
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.time.Instant
import java.time.ZoneId

/** Offline evidence driver. Uses production compiler code; never bundled as routing data. */
fun main(args: Array<String>) {
    require(args.size == 3) { "Supply fixture path, explicit instant and route time-zone ID" }
    val context = RestrictionCondition.Context(Instant.parse(args[1]), ZoneId.of(args[2]))
    val file = File(args[0])
    require(file.isFile && file.length() in 1..67_108_864) { "Invalid or oversized audit fixture" }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    println("FIXTURE_SHA256\t" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    println("CONTEXT\tinstant=${context.instant}\tzone=${context.routeZone}\tholidayAndVehicleFacts=unknown")
    fun text(raw: String) = String(Base64.getDecoder().decode(raw), Charsets.UTF_8)
    fun safe(raw: String) = raw.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
    var relation = 0L
    val tags = linkedMapOf<String, String>()
    val members = ArrayList<Compiler.Member>()
    val ways = linkedMapOf<Long, Compiler.Way>()
    var relations = 0
    var attempted = 0
    var resolved = 0
    var unresolved = 0
    var syntaxProblems = 0
    var noValue = 0
    var paths = 0
    var nodeVia = 0
    var wayVia = 0
    var endSeen = false
    var totalMemberNodes = 0
    var certainPolicies = 0
    var uncertainPolicies = 0
    val activations = Match.entries.associateWith { 0 }.toMutableMap()
    val geometry = object : Compiler.Geometry {
        override fun way(id: Long) = ways[id]
        override fun arc(wayId: Long, segmentOrdinal: Int, forward: Boolean): Long? {
            val way = ways[wayId] ?: return null
            if (segmentOrdinal !in 0 until way.nodes.size - 1) return null
            return Math.addExact(Math.multiplyExact(wayId, 200_000L), segmentOrdinal * 2L + if (forward) 1 else 2)
        }
    }
    fun auditRelation() {
        relations++
        require(relations <= 100_000 && relation > 0)
        val policy = TurnRestrictionPolicy.resolve(tags, context)
        if (policy.certainValue != null) certainPolicies++ else uncertainPolicies++
        println("POLICY\t$relation\t${safe(policy.possibleValues.joinToString("|") { it ?: "UNRESOLVED_VALUE" })}\t${safe(policy.diagnostics.joinToString("; "))}")
        val values = tags.filterKeys { it == "restriction" || it.startsWith("restriction:") }
        if (values.isEmpty()) { noValue++; println("NO_VALUE\t$relation") }
        for ((key, raw) in values) {
            val parsed = if (key.endsWith(":conditional")) ConditionalRestriction.parse(raw) else null
            if (parsed?.problem != null) {
                syntaxProblems++
                println("SYNTAX\t$relation\t${safe(key)}\t${safe(raw)}\t${safe(parsed.problem)}")
                continue
            }
            for (value in (parsed?.clauses?.map { it.value } ?: listOf(raw)).distinct()) {
                attempted++
                val activation = policy.activation(value)
                activations[activation] = activations.getValue(activation) + 1
                val result = Compiler.compile(relation, value, members, geometry)
                if (result.resolved) {
                    TurnRestrictionMachine(listOf(result.rule(1))) // Actual bounded state construction, not only path presence.
                    // Independently decode each fixture arc and prove exact original-node continuity.
                    for (path in result.paths) {
                        var lastNode: Long? = null
                        for (arc in path) {
                            val wayId = (arc - 1) / 200_000
                            val local = (arc - 1) % 200_000
                            val ordinal = (local / 2).toInt()
                            val forward = local % 2 == 0L
                            val way = ways.getValue(wayId)
                            val start = way.nodes[ordinal + if (forward) 0 else 1]
                            val end = way.nodes[ordinal + if (forward) 1 else 0]
                            check(lastNode == null || start == lastNode) { "Disconnected compiled path at relation $relation" }
                            lastNode = end
                        }
                    }
                    resolved++; paths += result.paths.size
                    if (members.any { it.role == "via" && it.type == 'n' }) nodeVia++ else wayVia++
                    println("RESOLVED\t$relation\t${safe(key)}\t${safe(value)}\t${result.paths.size}\tpassengerActivation=$activation")
                } else {
                    unresolved++
                    println("UNRESOLVED\t$relation\t${safe(key)}\t${safe(value)}\t${safe(result.problems.joinToString("; "))}\tpassengerActivation=$activation")
                }
            }
        }
    }
    file.bufferedReader(Charsets.UTF_8).use { reader ->
        val header = reader.readLine()?.split('\t') ?: error("Empty fixture")
        require(header.size == 2 && header[0] == "WM-TURN-AUDIT-1" && Regex("[a-f0-9]{64}").matches(header[1]))
        println("TOPOLOGY_SHA256\t${header[1]}")
        while (true) {
            val line = reader.readLine() ?: break
            require(!endSeen && line.length <= 2_000_000) { "Trailing or oversized fixture record" }
            val fields = line.split('\t')
            when (fields[0]) {
                "R" -> { require(relation == 0L && fields.size == 2); relation = fields[1].toLong(); require(relation > 0); totalMemberNodes = 0 }
                "T" -> { require(relation > 0 && fields.size == 3 && tags.size < 1024); require(tags.put(text(fields[1]), text(fields[2])) == null) }
                "M" -> { require(relation > 0 && fields.size == 4 && members.size < 256); members += Compiler.Member(fields[1].single(), fields[2].toLong(), text(fields[3])) }
                "W" -> {
                    require(relation > 0 && fields.size == 3 && ways.size < 256)
                    val id = fields[1].toLong()
                    if (fields[2].isNotEmpty()) {
                        val nodes = fields[2].split(',').map { it.toLong() }
                        totalMemberNodes += nodes.size
                        require(nodes.size <= 100_000 && totalMemberNodes <= 250_000)
                        require(ways.put(id, Compiler.Way(id, nodes)) == null)
                    }
                }
                "E" -> { require(fields.size == 1 && relation > 0); auditRelation(); relation = 0; tags.clear(); members.clear(); ways.clear() }
                "Z" -> { require(fields.size == 2 && relation == 0L && fields[1].toInt() == relations); endSeen = true }
                else -> error("Unknown fixture record")
            }
        }
    }
    check(endSeen && relation == 0L && relations > 0 && attempted == resolved + unresolved)
    println("SUMMARY\trelations=$relations\tvalues=$attempted\tresolved=$resolved\tunresolved=$unresolved\tsyntaxProblems=$syntaxProblems\tnoValue=$noValue\tpaths=$paths\tviaNode=$nodeVia\tviaWay=$wayVia")
    check(certainPolicies + uncertainPolicies == relations && activations.values.sum() == attempted)
    println("POLICY_SUMMARY\tcertainRelations=$certainPolicies\tuncertainRelations=$uncertainPolicies\tactiveValues=${activations[Match.YES]}\tinactiveValues=${activations[Match.NO]}\tunknownValues=${activations[Match.UNKNOWN]}")
    println("SCOPE\tGeometry/automaton and explicit-context passenger policy audit; missing facts, physical road legality, real-time signal waiting and integrated routing acceptance are NOT established")
}
