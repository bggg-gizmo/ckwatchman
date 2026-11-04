package com.bggg.watchman.routing

import java.math.BigDecimal
import java.util.Locale
import com.bggg.watchman.routing.ConditionalRestriction.Match

/** Passenger-motorcar dimensional/mass constraints. No vehicle size is invented if unavailable. */
object VehicleLimitPolicy {
    enum class UnitKind { LENGTH_METRES, MASS_TONNES }
    data class Parsed(val amount: BigDecimal?, val unlimited: Boolean, val problem: String?)
    data class Limit(val key: String, val quantity: String, val physical: Boolean,
        val alternatives: Set<MotorcarTagPolicy.Alternative>, val passes: Match)
    data class Result(val limits: List<Limit>, val passes: Match, val sourceTags: Map<String, String>,
        val unhandledTags: Map<String, String>, val diagnostics: List<String>)
    private data class Definition(val key: String, val quantity: String, val units: UnitKind, val physical: Boolean = false)
    private val definitions = listOf(
        Definition("maxheight", "height", UnitKind.LENGTH_METRES),
        Definition("maxheight:physical", "height", UnitKind.LENGTH_METRES, true),
        Definition("maxwidth", "width", UnitKind.LENGTH_METRES),
        Definition("maxwidth:physical", "width", UnitKind.LENGTH_METRES, true),
        Definition("maxlength", "length", UnitKind.LENGTH_METRES),
        Definition("maxweight", "weight", UnitKind.MASS_TONNES),
        Definition("maxaxleload", "axleload", UnitKind.MASS_TONNES),
        Definition("maxweightrating", "weightrating", UnitKind.MASS_TONNES),
        Definition("maxunladenweight", "unladenweight", UnitKind.MASS_TONNES)
    )
    private val imperial = Regex("([0-9]+)'(?:([0-9]+(?:\\.[0-9]+)?)\")?")
    private val number = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*([a-z]*)")

    /** OSM defaults are metres and metric tonnes, including in US data; explicit st means short tons. */
    fun parse(raw: String, kind: UnitKind): Parsed {
        if (raw.length > 128) return Parsed(null, false, "Limit exceeds input bound")
        val text = raw.trim().lowercase(Locale.ROOT).replace('\u2019', '\'').replace('\u2032', '\'')
            .replace('\u201d', '"').replace('\u2033', '"').replace("''", "\"")
        if (text == "none") return Parsed(null, true, null)
        if (kind == UnitKind.LENGTH_METRES) imperial.matchEntire(text)?.let { match ->
            val feet = match.groupValues[1].toBigDecimal()
            val inches = match.groupValues[2].ifEmpty { "0" }.toBigDecimal()
            if (inches >= BigDecimal("12")) return Parsed(null, false, "Invalid feet/inches limit")
            return Parsed(feet * BigDecimal("0.3048") + inches * BigDecimal("0.0254"), false, null)
        }
        val match = number.matchEntire(text) ?: return Parsed(null, false, "Unknown or malformed limit: $raw")
        val factor = when (kind) {
            UnitKind.LENGTH_METRES -> when (match.groupValues[2]) { "", "m" -> "1"; "cm" -> "0.01"; "ft" -> "0.3048"; else -> null }
            UnitKind.MASS_TONNES -> when (match.groupValues[2]) { "", "t" -> "1"; "kg" -> "0.001"; "st" -> "0.90718474"; "lbs", "lb" -> "0.00045359237"; else -> null }
        } ?: return Parsed(null, false, "Unsupported or ambiguous unit: $raw")
        return Parsed(match.groupValues[1].toBigDecimal() * BigDecimal(factor), false, null)
    }

    fun evaluate(tags: Map<String, String>, direction: MotorcarTagPolicy.Direction, context: RestrictionCondition.Context): Result {
        val source = tags.filterKeys { key -> definitions.any { key == it.key || key.startsWith("${it.key}:") } }.toSortedMap()
        val handled = linkedSetOf<String>()
        val diagnostics = ArrayList<String>()
        val limits = ArrayList<Limit>()
        val otherDirection = if (direction == MotorcarTagPolicy.Direction.FORWARD) "backward" else "forward"
        for (definition in definitions) {
            var values = setOf(MotorcarTagPolicy.Alternative("none", "unmapped:${definition.key}"))
            var present = false
            fun overlay(key: String) {
                handled += key; handled += "$key:conditional"
                tags[key]?.let { present = true; values = setOf(MotorcarTagPolicy.Alternative(it, key)) }
                tags["$key:conditional"]?.let { raw ->
                    present = true
                    val parsed = ConditionalRestriction.parse(raw)
                    if (parsed.problem != null) {
                        values = values + MotorcarTagPolicy.Alternative(null, "$key:conditional")
                        diagnostics += "$key:conditional: ${parsed.problem}"
                    } else for (clause in parsed.clauses) {
                        val evaluation = RestrictionCondition.evaluate(clause.condition, context)
                        diagnostics += evaluation.unresolved.map { "$key:conditional: $it" }
                        val next = MotorcarTagPolicy.Alternative(clause.value, "$key:conditional")
                        when (evaluation.match) { Match.YES -> values = setOf(next); Match.NO -> Unit; Match.UNKNOWN -> values = values + next }
                    }
                }
            }
            for (mode in listOf("", ":vehicle", ":motor_vehicle", ":motorcar")) {
                val key = definition.key + mode
                overlay(key)
                overlay("$key:${direction.suffix}")
                handled += "$key:$otherDirection"; handled += "$key:$otherDirection:conditional"
            }
            if (!present) continue
            val quantity = context.quantities[definition.quantity]
            val results = values.map { alternative ->
                val raw = alternative.rawValue
                val parsed = if (raw == null) Parsed(null, false, "Unresolved limit") else parse(raw, definition.units)
                when {
                    parsed.problem != null -> { diagnostics += "${alternative.sourceKey}: ${parsed.problem}"; Match.UNKNOWN }
                    parsed.unlimited && (!definition.physical || alternative.sourceKey.startsWith("unmapped:")) -> Match.YES
                    parsed.unlimited -> { diagnostics += "${alternative.sourceKey}: Physical clearance is unspecified"; Match.UNKNOWN }
                    quantity == null || quantity.signum() <= 0 -> { diagnostics += "Missing/invalid vehicle ${definition.quantity}"; Match.UNKNOWN }
                    definition.physical && quantity >= parsed.amount!! -> Match.NO // Exact physical contact is not clearance.
                    !definition.physical && quantity > parsed.amount!! -> Match.NO
                    else -> Match.YES
                }
            }
            val passes = when { results.all { it == Match.YES } -> Match.YES; results.all { it == Match.NO } -> Match.NO; else -> Match.UNKNOWN }
            limits += Limit(definition.key, definition.quantity, definition.physical, values.toSet(), passes)
        }
        val otherModes = setOf("hgv", "hgv_articulated", "bus", "psv", "bicycle", "foot", "motorcycle", "moped", "mofa", "horse", "agricultural", "emergency")
        val metadata = setOf("signed", "source", "note", "description")
        val unhandled = source.filterKeys { key ->
            key !in handled && key.split(':').drop(1).none { it in otherModes || it in metadata }
        }
        diagnostics += unhandled.keys.map { "Unhandled vehicle constraint: $it" }
        val result = when {
            unhandled.isNotEmpty() -> Match.UNKNOWN // A lane-specific constraint may supersede a road-level limit.
            limits.any { it.passes == Match.NO } -> Match.NO
            limits.any { it.passes == Match.UNKNOWN } -> Match.UNKNOWN
            else -> Match.YES
        }
        return Result(limits.toList(), result, source, unhandled, diagnostics.distinct())
    }
}
