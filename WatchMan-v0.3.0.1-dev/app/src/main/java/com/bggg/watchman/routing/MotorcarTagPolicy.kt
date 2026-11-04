package com.bggg.watchman.routing

import java.util.Locale
import com.bggg.watchman.routing.ConditionalRestriction.Match

/**
 * Resolves passenger-motorcar access/direction tags, not complete road legality.
 * Physical barriers, dimensions, turns, lane connectivity and destination-component permission
 * remain separate constraints. A caller must not mistake publicThroughAccess for a routable arc.
 * Precedence: mode specificity, direction, then matching conditional value in source order.
 * https://wiki.openstreetmap.org/wiki/Conditional_restrictions#Evaluation_of_conflicting_restrictions
 */
object MotorcarTagPolicy {
    enum class Direction(val suffix: String) { FORWARD("forward"), BACKWARD("backward") }
    enum class AccessClass {
        PUBLIC, DESTINATION, CUSTOMERS, DELIVERY, PRIVATE, PERMIT, RESIDENTS, EMPLOYEES,
        AGRICULTURAL, FORESTRY, DISABLED, DENIED, UNKNOWN
    }
    /** These are road-profile inputs, never assumed from geometry alone. null means undetermined. */
    data class Defaults(val access: String?, val oneway: String?, val evidence: String) {
        init { require(evidence.isNotBlank()) { "Road-profile default provenance is required" } }
    }
    data class Alternative(val rawValue: String?, val sourceKey: String)
    data class Result(
        val access: Set<Alternative>,
        val oneway: Set<Alternative>,
        val accessClasses: Set<AccessClass>,
        val publicThroughAccess: Match,
        val directionAllowed: Match,
        /** Includes evaluated and superseded raw tags; no provenance is silently discarded. */
        val sourceTags: Map<String, String>,
        /** Includes lane tags and malformed key shapes requiring additional handling. */
        val unhandledTags: Map<String, String>,
        val diagnostics: List<String>
    ) {
        /** Only answers the two tag families; still not barrier/turn/road legality. */
        val accessAndDirection: Match get() = when {
            directionAllowed == Match.NO -> Match.NO
            unhandledTags.isNotEmpty() -> Match.UNKNOWN
            publicThroughAccess == Match.NO -> Match.NO
            publicThroughAccess == Match.YES && directionAllowed == Match.YES -> Match.YES
            else -> Match.UNKNOWN
        }
    }
    private val modes = listOf("access", "vehicle", "motor_vehicle", "motorcar")
    private val onewayModes = listOf("oneway", "oneway:vehicle", "oneway:motor_vehicle", "oneway:motorcar")
    private fun normalized(value: String?) = value?.trim()?.lowercase(Locale.ROOT)

    fun resolve(tags: Map<String, String>, direction: Direction, defaults: Defaults, context: RestrictionCondition.Context): Result =
        resolve(tags, direction, defaults) { RestrictionCondition.evaluate(it, context) }

    fun resolve(
        tags: Map<String, String>, direction: Direction, defaults: Defaults,
        condition: (String) -> RestrictionCondition.Evaluation
    ): Result {
        val diagnostics = ArrayList<String>()
        val handled = linkedSetOf<String>()
        fun overlay(input: Set<Alternative>, key: String): Set<Alternative> {
            handled += key
            handled += "$key:conditional"
            var current = tags[key]?.let { setOf(Alternative(it, key)) } ?: input
            val conditionalKey = "$key:conditional"
            tags[conditionalKey]?.let { raw ->
                val parsed = ConditionalRestriction.parse(raw)
                if (parsed.problem != null) {
                    diagnostics += "$conditionalKey: ${parsed.problem}"
                    current = current + Alternative(null, conditionalKey)
                } else for (clause in parsed.clauses) {
                    val evaluated = try { condition(clause.condition) } catch (_: Exception) {
                        RestrictionCondition.Evaluation(Match.UNKNOWN, listOf("Condition evaluator unavailable"))
                    }
                    diagnostics += evaluated.unresolved.map { "$conditionalKey: $it" }
                    val replacement = Alternative(clause.value, conditionalKey)
                    when (evaluated.match) {
                        Match.YES -> current = setOf(replacement)
                        Match.NO -> Unit
                        Match.UNKNOWN -> {
                            current = current + replacement
                            diagnostics += "$conditionalKey: unresolved predicate ${clause.condition}"
                        }
                    }
                }
            }
            return current
        }
        var access = setOf(Alternative(defaults.access, "profile: ${defaults.evidence}"))
        for (mode in modes) {
            access = overlay(access, mode)
            access = overlay(access, "$mode:${direction.suffix}")
            // Opposite-direction keys are understood but do not apply to this directed traversal.
            val opposite = if (direction == Direction.FORWARD) "backward" else "forward"
            handled += "$mode:$opposite"; handled += "$mode:$opposite:conditional"
        }
        var oneway = setOf(Alternative(defaults.oneway, "profile: ${defaults.evidence}"))
        for (mode in onewayModes) oneway = overlay(oneway, mode)
        val source = tags.filterKeys { key -> modes.any { key == it || key.startsWith("$it:") } || key == "oneway" || key.startsWith("oneway:") }.toSortedMap()
        // Other travel modes do not create uncertainty for a passenger motorcar.
        val otherOnewayModes = setOf("bicycle", "foot", "bus", "psv", "hgv", "motorcycle", "moped", "mofa", "taxi", "horse")
        val unhandled = source.filterKeys { key ->
            key !in handled && !(key.startsWith("oneway:") && key.split(':').getOrNull(1) in otherOnewayModes)
        }
        diagnostics += unhandled.keys.map { "Additional access/direction constraint requires handling: $it" }
        fun accessClass(alternative: Alternative): AccessClass =
            if (normalized(alternative.rawValue) == "designated" &&
                (alternative.sourceKey == "access" || alternative.sourceKey.startsWith("access:"))) AccessClass.UNKNOWN
            else classify(alternative.rawValue)
        val classes = access.map(::accessClass).toSet()
        for (alternative in access) if (accessClass(alternative) == AccessClass.UNKNOWN) {
            diagnostics += "Unknown access value at ${alternative.sourceKey}: ${alternative.rawValue}"
        }
        val through = consensus(classes.map { when (it) { AccessClass.PUBLIC -> Match.YES; AccessClass.UNKNOWN -> Match.UNKNOWN; else -> Match.NO } })
        val directed = consensus(oneway.map { alternative ->
            when (normalized(alternative.rawValue)) {
                "no", "false", "0" -> Match.YES
                "yes", "true", "1" -> if (direction == Direction.FORWARD) Match.YES else Match.NO
                "-1" -> if (direction == Direction.BACKWARD) Match.YES else Match.NO
                else -> {
                    diagnostics += "Unresolved one-way direction at ${alternative.sourceKey}: ${alternative.rawValue}"
                    Match.UNKNOWN
                }
            }
        })
        return Result(access.toSet(), oneway.toSet(), classes, through, directed, source, unhandled, diagnostics.distinct())
    }

    private fun consensus(values: Collection<Match>): Match =
        if (values.isNotEmpty() && values.all { it == Match.YES }) Match.YES
        else if (values.isNotEmpty() && values.all { it == Match.NO }) Match.NO
        else Match.UNKNOWN

    fun classify(value: String?): AccessClass = when (normalized(value)) {
        "yes", "permissive", "designated", "discouraged" -> AccessClass.PUBLIC
        "destination" -> AccessClass.DESTINATION
        "customers" -> AccessClass.CUSTOMERS
        "delivery" -> AccessClass.DELIVERY
        "private" -> AccessClass.PRIVATE
        "permit" -> AccessClass.PERMIT
        "residents" -> AccessClass.RESIDENTS
        "employees" -> AccessClass.EMPLOYEES
        "agricultural" -> AccessClass.AGRICULTURAL
        "forestry" -> AccessClass.FORESTRY
        "disabled" -> AccessClass.DISABLED
        "no", "use_sidepath" -> AccessClass.DENIED
        else -> AccessClass.UNKNOWN
    }
}
