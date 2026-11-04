package com.bggg.watchman.routing

import java.util.Locale
import com.bggg.watchman.routing.ConditionalRestriction.Match

/**
 * Turn applicability for the ordinary passenger-motorcar profile, not HGV/PSV/emergency service.
 * Resolve anew using the transition's route-local context. Geometry is compiled separately and
 * UNKNOWN values/applicability must remain unresolved graph constraints, never discarded rules.
 */
object TurnRestrictionPolicy {
    data class Resolution(
        val alternatives: Set<MotorcarTagPolicy.Alternative>,
        val sourceTags: Map<String, String>,
        val diagnostics: List<String>
    ) {
        val possibleValues: Set<String?> get() = alternatives.map { it.rawValue }.toSet()
        val certainValue: String? get() = possibleValues.singleOrNull()
        val certainlyExempt: Boolean get() = certainValue == "none"
        /** Used for a compiled variant of this relation; null alternatives may be any restriction. */
        fun activation(value: String): Match = when {
            null in possibleValues -> Match.UNKNOWN
            possibleValues == setOf(value) -> Match.YES
            value in possibleValues -> Match.UNKNOWN
            else -> Match.NO
        }
    }
    private val passengerModes = setOf("vehicle", "motor_vehicle", "motorcar")
    private val otherModes = setOf("hgv", "hgv_articulated", "bus", "psv", "bicycle", "foot", "motorcycle", "moped", "mofa",
        "horse", "taxi", "emergency", "agricultural", "forestry", "hazmat", "caravan", "trailer", "goods", "tourist_bus", "coach")
    private val keys = listOf("restriction", "restriction:vehicle", "restriction:motor_vehicle", "restriction:motorcar")
    private fun normalized(raw: String) = raw.trim().lowercase(Locale.ROOT)

    fun resolve(tags: Map<String, String>, context: RestrictionCondition.Context): Resolution {
        val source = tags.toSortedMap()
        val diagnostics = ArrayList<String>()
        val type = tags["type"]
        val legacyMode = type?.takeIf { it.startsWith("restriction:") }?.substringAfter(':')
        if (legacyMode in otherModes) {
            if (context.facts[legacyMode] == Match.YES || tags.keys.any { key -> passengerModes.any { key == "restriction:$it" || key.startsWith("restriction:$it:") } }) {
                return Resolution(setOf(MotorcarTagPolicy.Alternative(null, "type")), source,
                    listOf("Legacy special-mode type conflicts with passenger/special-use evidence; explicit profile resolution required"))
            }
            return Resolution(setOf(MotorcarTagPolicy.Alternative("none", "type")), source,
                listOf("Legacy relation type applies to $legacyMode, not the ordinary passenger profile"))
        }
        var options = setOf(MotorcarTagPolicy.Alternative("none", "no applicable base restriction"))
        val handled = linkedSetOf<String>()
        for (key in keys) {
            handled += key; handled += "$key:conditional"
            tags[key]?.let { options = setOf(MotorcarTagPolicy.Alternative(it, key)) }
            tags["$key:conditional"]?.let { raw ->
                val parsed = ConditionalRestriction.parse(raw)
                if (parsed.problem != null) {
                    options = options + MotorcarTagPolicy.Alternative(null, "$key:conditional")
                    diagnostics += "$key:conditional: ${parsed.problem}"
                } else for (clause in parsed.clauses) {
                    val result = RestrictionCondition.evaluate(clause.condition, context)
                    diagnostics += result.unresolved.map { "$key:conditional: $it" }
                    val replacement = MotorcarTagPolicy.Alternative(clause.value, "$key:conditional")
                    when (result.match) {
                        Match.YES -> options = setOf(replacement)
                        Match.NO -> Unit
                        Match.UNKNOWN -> { options = options + replacement; diagnostics += "$key:conditional: unresolved ${clause.condition}" }
                    }
                }
            }
        }
        val restrictionKeys = tags.keys.filter { it == "restriction" || it.startsWith("restriction:") }
        if (restrictionKeys.isEmpty()) {
            options = options + MotorcarTagPolicy.Alternative(null, "missing restriction value")
            diagnostics += "Restriction relation has no value"
        }
        for (key in restrictionKeys) {
            val mode = key.split(':').getOrNull(1)
            if (key !in handled && mode !in otherModes) {
                options = options + MotorcarTagPolicy.Alternative(null, key)
                diagnostics += "Unsupported restriction key: $key"
            }
            if (mode in otherModes && context.facts[mode] == Match.YES) {
                options = options + MotorcarTagPolicy.Alternative(null, key)
                diagnostics += "Special vehicle/use fact $mode requires a non-passenger turn profile"
            }
        }
        if (type != "restriction" && legacyMode !in passengerModes) {
            options = options + MotorcarTagPolicy.Alternative(null, "type")
            diagnostics += "Missing or unsupported restriction relation type: $type"
        }
        if (tags.keys.any { it in setOf("day_on", "day_off", "hour_on", "hour_off") }) {
            options = options + MotorcarTagPolicy.Alternative("none", "legacy time fields")
            diagnostics += "Legacy time fields require explicit evaluation; not an all-day restriction"
        }
        var exemption = Match.NO
        tags["except"]?.let { raw ->
            val modes = raw.split(';').map(::normalized)
            exemption = when {
                modes.size > 256 || raw.length > 16_384 -> Match.UNKNOWN
                modes.any { it in passengerModes } -> Match.YES
                modes.any { it !in otherModes } -> Match.UNKNOWN
                modes.any { context.facts[it] == Match.YES } -> Match.UNKNOWN
                else -> Match.NO
            }
            if (exemption == Match.UNKNOWN) diagnostics += "Unresolved passenger exemption: $raw"
        }
        if (tags.keys.any { it.startsWith("except:") } && exemption != Match.YES) {
            exemption = Match.UNKNOWN
            diagnostics += "Additional exception constraint requires evaluation"
        }
        options = when (exemption) {
            Match.YES -> setOf(MotorcarTagPolicy.Alternative("none", "except"))
            Match.UNKNOWN -> options + MotorcarTagPolicy.Alternative("none", "uncertain exception")
            Match.NO -> options
        }
        for (option in options) {
            if (option.rawValue?.isBlank() == true) diagnostics += "Empty restriction value at ${option.sourceKey}"
            if (option.rawValue?.endsWith("_on_red") == true) diagnostics += "Signal-phase dependent restriction retained; not an unconditional turn ban"
        }
        // An empty value is not the same as explicit none; its unknown meaning remains represented.
        options = options.map { if (it.rawValue?.isBlank() == true) it.copy(rawValue = null) else it }.toSet()
        return Resolution(options, source, diagnostics.distinct())
    }
}
