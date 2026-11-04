package com.bggg.watchman.routing

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Collections
import java.util.Locale
import com.bggg.watchman.routing.ConditionalRestriction.Match

/** Source-driven passenger-car travel estimate, NOT observed traffic or interval permission.
 * Numeric OSM maxspeed is km/h unless explicit units are present. A caller must supply an
 * identified estimated-speed profile; no hidden road/highway/convenience penalty is added.
 * References: https://wiki.openstreetmap.org/wiki/Key:maxspeed and Conditional_restrictions.
 */
object RoadSpeedEstimate {
    class Profile(val revision: String, val evidence: String, highwayKmH: Map<String, BigDecimal>) {
        val highwayKmH: Map<String, BigDecimal> = Collections.unmodifiableMap(LinkedHashMap(highwayKmH))
        init {
            require(revision.isNotBlank() && revision.length <= 512 && evidence.isNotBlank() && evidence.length <= 4096)
            require(highwayKmH.size in 1..128 && highwayKmH.all { (key, speed) ->
                key.isNotBlank() && key.length <= 64 && speed.signum() > 0 && speed <= BigDecimal(300) &&
                    speed.precision() <= 128 && kotlin.math.abs(speed.scale()) <= 64
            }) { "Invalid explicit estimated-speed profile" }
        }
    }
    data class Alternative(val rawValue: String?, val sourceKey: String)
    data class Result(val estimatedKmH: BigDecimal?, val mappedCapKmH: BigDecimal?,
                      val taggedLimitResolved: Boolean, val alternatives: Set<Alternative>,
                      val sourceTags: Map<String, String>, val diagnostics: List<String>,
                      val profileRevision: String, val direction: MotorcarTagPolicy.Direction) {
        /** Exact source-mm portion / decimal speed, rounded up once. Kernel requires positive ETA.
         * This omits intersection/turn/signal/ferry waiting; those must be added by the timing model.
         */
        fun travelMillis(portion: RoadArcPortion, sourceLengthMm: Long): Long? {
            require(portion.direction == direction) { "Speed estimate belongs to opposite directed traversal" }
            val distance = portion.distanceMm(sourceLengthMm)
            val speed = estimatedKmH ?: return null
            if (speed.signum() <= 0) return null
            return BigDecimal.valueOf(distance).multiply(BigDecimal("3.6"))
                .divide(speed, 0, RoundingMode.CEILING).longValueExact().coerceAtLeast(1L)
        }
    }
    private val numeric = Regex("([0-9]+(?:\\.[0-9]+)?)(?:\\s*(km/h|kph|kmh|mph|knots|knot|kt))?")

    /** null means nonnumeric/unhandled, never 'unlimited'. 'none' is handled separately. */
    fun numericKmH(raw: String): BigDecimal? {
        if (raw.length > 256) return null
        val match = numeric.matchEntire(raw.trim().lowercase(Locale.ROOT)) ?: return null
        val number = match.groupValues[1].toBigDecimalOrNull() ?: return null
        if (number.precision() > 128 || number.scale() > 64) return null
        val factor = when (match.groupValues[2]) { "mph" -> BigDecimal("1.609344"); "knots", "knot", "kt" -> BigDecimal("1.852"); else -> BigDecimal.ONE }
        val speed = number.multiply(factor)
        return speed.takeIf { it.signum() >= 0 && it <= BigDecimal(500) }
    }

    fun resolve(tags: Map<String, String>, direction: MotorcarTagPolicy.Direction,
                context: RestrictionCondition.Context, profile: Profile): Result {
        require(tags.size <= 4096)
        val source = tags.filterKeys { it == "maxspeed" || it.startsWith("maxspeed:") || it == "source:maxspeed" }.toSortedMap()
        require(source.values.sumOf { it.length.toLong() } <= 512_000) { "Speed metadata exceeds explicit budget" }
        val diagnostics = ArrayList<String>()
        val handled = HashSet<String>()
        var options: Set<Alternative> = setOf(Alternative(null, "unmapped"))
        fun overlay(key: String) {
            handled += key; handled += "$key:conditional"
            tags[key]?.let { options = setOf(Alternative(it, key)) }
            val raw = tags["$key:conditional"] ?: return
            val parsed = ConditionalRestriction.parse(raw)
            if (parsed.problem != null) {
                options = options + Alternative(null, "$key:conditional")
                diagnostics += "$key:conditional: ${parsed.problem}"; return
            }
            for (clause in parsed.clauses) {
                val evaluated = try { RestrictionCondition.evaluate(clause.condition, context) }
                    catch (_: Exception) { RestrictionCondition.Evaluation(Match.UNKNOWN, listOf("Condition evaluator unavailable")) }
                diagnostics += evaluated.unresolved.map { "$key:conditional: $it" }
                val replacement = Alternative(clause.value, "$key:conditional")
                when (evaluated.match) {
                    Match.YES -> options = setOf(replacement)
                    Match.NO -> Unit
                    Match.UNKNOWN -> {
                        options = options + replacement
                        diagnostics += "Unresolved speed condition at$key: ${clause.condition}"
                    }
                }
                require(options.size <= 1024) { "Speed alternatives exceed explicit budget" }
            }
        }
        val suffix = if (direction == MotorcarTagPolicy.Direction.FORWARD) "forward" else "backward"
        val opposite = if (suffix == "forward") "backward" else "forward"
        overlay("maxspeed"); overlay("maxspeed:$suffix")
        handled += "maxspeed:$opposite"; handled += "maxspeed:$opposite:conditional"
        for (mode in listOf("vehicle", "motor_vehicle", "motorcar")) {
            overlay("maxspeed:$mode"); overlay("maxspeed:$mode:$suffix")
            handled += "maxspeed:$mode:$opposite"; handled += "maxspeed:$mode:$opposite:conditional"
        }
        val otherModes = setOf("hgv", "bus", "psv", "motorcycle", "moped", "mofa", "bicycle", "foot", "taxi", "agricultural")
        // These describe the origin/type of a limit, not an independently posted numeric cap.
        // An implicit code in maxspeed itself still needs an evidenced jurisdiction resolver.
        val metadata = setOf("maxspeed:note", "maxspeed:source", "maxspeed:type", "source:maxspeed")
        val unhandled = source.keys.filter { key ->
            key !in handled && key !in metadata && key.split(':').getOrNull(1) !in otherModes && !key.startsWith("maxspeed:advisory") &&
                !(key == "maxspeed:variable" && tags[key]?.trim()?.lowercase(Locale.ROOT) == "no")
        }
        diagnostics += unhandled.map { "Additional speed constraint requires handling: $it" }
        val caps = ArrayList<BigDecimal>()
        var unsupported = unhandled.isNotEmpty()
        var unlimitedOrUnmapped = false
        for (option in options) {
            val raw = option.rawValue
            if (raw == null) {
                if (option.sourceKey != "unmapped") unsupported = true
                unlimitedOrUnmapped = true; continue
            }
            if (raw.trim().lowercase(Locale.ROOT) == "none") { unlimitedOrUnmapped = true; continue }
            val value = numericKmH(raw)
            if (value == null) { unsupported = true; diagnostics += "Unsupported speed at${option.sourceKey}: $raw" }
            else caps += value
        }
        val distinct = caps.map { it.stripTrailingZeros() }.toSet()
        val resolved = !unsupported && (options.size == 1 || (distinct.size == 1 && !unlimitedOrUnmapped))
        val cap = caps.minOrNull()
        val highway = tags["highway"]?.trim()?.lowercase(Locale.ROOT)
        val model = profile.highwayKmH[highway]
        if (model == null) diagnostics += "No evidenced estimated-speed profile for highway=$highway"
        if (options.all { it.rawValue == null }) diagnostics += "No mapped numeric speed limit; travel speed is an explicit profile estimate"
        if (!resolved) diagnostics += "Tagged speed is unresolved; no continuous speed-limit guarantee"
        var estimate = if (unsupported) null else model?.let { speed -> if (cap == null) speed else speed.min(cap) }
        if (estimate?.signum() == 0) { diagnostics += "Zero speed cap has no positive travel estimate"; estimate = null }
        return Result(estimate, cap, resolved, Collections.unmodifiableSet(LinkedHashSet(options)),
            Collections.unmodifiableMap(source), Collections.unmodifiableList(diagnostics.distinct()), profile.revision, direction)
    }
}
