package com.bggg.watchman.routing

import java.util.Locale
import com.bggg.watchman.routing.ConditionalRestriction.Match
import com.bggg.watchman.routing.MotorcarTagPolicy.AccessClass

/**
 * Explicit passenger-car routing fallback profile, not an assertion of universal traffic law.
 * Every default is named in access provenance. Ways and crossed point barriers are evaluated
 * separately. Result does not include turn legality, restricted-component grants or ferry timing.
 */
object MotorcarRoadProfile {
    const val VERSION = "watchman-passenger-road-profile/1"
    data class Decision(
        val structure: Match,
        val access: MotorcarTagPolicy.Result,
        val limits: VehicleLimitPolicy.Result,
        val controls: Set<String>,
        val sourceTags: Map<String, String>,
        val diagnostics: List<String>
    ) {
        /** Excludes access permission: a private gate may be physically passable but unauthorized. */
        val permissionIndependentPassability: Match get() = all(structure, access.directionAllowed, limits.passes)
        val publicThrough: Match get() = all(structure, access.accessAndDirection, limits.passes)
    }
    private val carRoads = setOf("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
        "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service", "track", "road")
    private val needsExplicitMotorPermission = setOf("footway", "path", "cycleway", "bridleway", "pedestrian", "busway")
    private val nonDriving = setOf("steps", "construction", "proposed", "abandoned", "razed", "disused", "platform", "corridor", "elevator", "bus_guideway")
    private val hardBarriers = setOf("block", "wall", "city_wall", "retaining_wall", "fence", "hedge", "ditch", "guard_rail", "cable_barrier",
        "jersey_barrier", "planter", "log", "stile", "turnstile", "full-height_turnstile", "kissing_gate", "sump_buster", "cycle_barrier")
    private val gates = setOf("gate", "lift_gate", "swing_gate", "sliding_gate", "bump_gate", "hampshire_gate", "chain", "rope")
    private fun normalized(value: String?) = value?.trim()?.lowercase(Locale.ROOT)
    private fun all(vararg values: Match): Match = when { values.any { it == Match.NO } -> Match.NO; values.any { it == Match.UNKNOWN } -> Match.UNKNOWN; else -> Match.YES }

    private fun explicitMotorPermission(access: MotorcarTagPolicy.Result): Match {
        val branches = access.access.map { option ->
            val explicit = listOf("vehicle", "motor_vehicle", "motorcar").any { option.sourceKey == it || option.sourceKey.startsWith("$it:") }
            when {
                option.rawValue == null -> Match.UNKNOWN
                !explicit -> Match.NO
                MotorcarTagPolicy.classify(option.rawValue) == AccessClass.UNKNOWN -> Match.UNKNOWN
                MotorcarTagPolicy.classify(option.rawValue) == AccessClass.DENIED -> Match.NO
                else -> Match.YES
            }
        }
        return when { branches.all { it == Match.YES } -> Match.YES; branches.all { it == Match.NO } -> Match.NO; else -> Match.UNKNOWN }
    }

    fun way(tags: Map<String, String>, direction: MotorcarTagPolicy.Direction, context: RestrictionCondition.Context): Decision {
        val highway = normalized(tags["highway"])
        val ferry = normalized(tags["route"]) == "ferry"
        val defaultAccess = when {
            highway == "raceway" -> "private"
            highway == "service" && normalized(tags["service"]) == "driveway" -> "destination"
            highway in carRoads -> "yes"
            highway in needsExplicitMotorPermission || highway in nonDriving -> "no"
            else -> null
        }
        val defaultOneWay = if (highway == "motorway" || normalized(tags["junction"]) == "roundabout") "yes" else "no"
        val access = MotorcarTagPolicy.resolve(tags, direction, MotorcarTagPolicy.Defaults(defaultAccess, defaultOneWay,
            "$VERSION highway=$highway route=${tags["route"]} service=${tags["service"]}; tagged-road routing fallback"), context)
        val limits = VehicleLimitPolicy.evaluate(tags, direction, context)
        val diagnostics = ArrayList<String>()
        val controls = linkedSetOf<String>()
        var structure = when {
            normalized(tags["area"]) == "yes" -> { diagnostics += "Area outline is not a routable centerline"; Match.NO }
            highway in nonDriving -> { diagnostics += "Non-driving/lifecycle highway: $highway"; Match.NO }
            highway in carRoads || highway == "raceway" -> Match.YES
            highway in needsExplicitMotorPermission || ferry -> explicitMotorPermission(access)
            else -> { diagnostics += "Unsupported or missing highway/ferry class"; Match.UNKNOWN }
        }
        if (ferry) {
            controls += "ferry"
            val operating = context.facts["ferry_operating"] ?: Match.UNKNOWN
            if (operating == Match.UNKNOWN) diagnostics += "Ferry vehicle eligibility does not establish service operation/timing"
            structure = all(structure, operating)
        }
        when (normalized(tags["smoothness"])) {
            "impassable" -> { structure = Match.NO; diagnostics += "Surface tagged impassable" }
            "horrible", "very_horrible" -> {
                val passable = context.facts["surface_passable"] ?: Match.UNKNOWN
                structure = all(structure, passable)
                if (passable == Match.UNKNOWN) diagnostics += "Vehicle suitability for extreme surface is unknown"
            }
        }
        if (normalized(tags["ford"]) !in setOf(null, "no")) {
            controls += "ford"
            val passable = context.facts["ford_passable"] ?: Match.UNKNOWN
            structure = all(structure, passable)
            if (passable == Match.UNKNOWN) diagnostics += "Ford passability is unknown"
        }
        if (normalized(tags["barrier"]) !in setOf(null, "no")) {
            structure = all(structure, Match.UNKNOWN)
            diagnostics += "Linear barrier on highway requires crossing/topology resolution; not a point-barrier default"
        }
        if (structure == Match.UNKNOWN) diagnostics += "Way structure requires unresolved evidence"
        return Decision(structure, access, limits, controls, tags.toMap(), (diagnostics + access.diagnostics + limits.diagnostics).distinct())
    }

    /** Call for an actual crossed graph node; never apply every nearby/parallel barrier to an arc. */
    fun node(tags: Map<String, String>, direction: MotorcarTagPolicy.Direction, context: RestrictionCondition.Context): Decision {
        val barrier = normalized(tags["barrier"])
        val access = MotorcarTagPolicy.resolve(tags, direction, MotorcarTagPolicy.Defaults("yes", "no",
            "$VERSION crossed point access fallback; parent way permission remains independently required"), context)
        val limits = VehicleLimitPolicy.evaluate(tags, direction, context)
        val controls = linkedSetOf<String>()
        val diagnostics = ArrayList<String>()
        fun fact(key: String): Match = (context.facts[key] ?: Match.UNKNOWN).also { if (it == Match.UNKNOWN) diagnostics += "Missing physical state: $key" }
        fun unlocked(): Match {
            val raw = tags["locked:conditional"]
            val values = if (raw == null) setOf(tags["locked"] ?: "no") else {
                val parsed = ConditionalRestriction.parse(raw)
                val resolved = ConditionalRestriction.resolve(parsed, tags["locked"] ?: "no") { RestrictionCondition.evaluate(it, context).match }
                if (resolved.syntaxProblem != null) { diagnostics += "Invalid locked condition: ${resolved.syntaxProblem}"; return Match.UNKNOWN }
                resolved.possibleValues
            }
            val possibilities = values.map { when (normalized(it)) { "no", "false", "0" -> Match.YES; "yes", "true", "1" -> Match.NO; else -> Match.UNKNOWN } }
            return when { possibilities.all { it == Match.YES } -> Match.YES; possibilities.all { it == Match.NO } -> Match.NO; else -> Match.UNKNOWN }
        }
        var structure = when {
            barrier in setOf(null, "no", "entrance", "cattle_grid") -> Match.YES
            barrier in hardBarriers -> Match.NO
            barrier in gates -> { controls += "operable_gate"; unlocked() }
            barrier == "bollard" || barrier == "bollard_row" -> {
                if (normalized(tags["bollard"]) in setOf("removable", "rising", "foldable", "retractable")) {
                    controls += "movable_bollard"; all(unlocked(), fact("barrier_open"))
                } else if (explicitMotorPermission(access) == Match.YES && "maxwidth:physical" in tags) limits.passes
                else Match.NO
            }
            barrier == "kerb" -> when (normalized(tags["kerb"])) { "flush", "lowered", "no" -> Match.YES; else -> fact("kerb_passable") }
            barrier == "height_restrictor" -> if (limits.limits.any { limit -> limit.quantity == "height" && limit.alternatives.all { alternative ->
                val parsed = alternative.rawValue?.let { VehicleLimitPolicy.parse(it, VehicleLimitPolicy.UnitKind.LENGTH_METRES) }
                parsed != null && parsed.problem == null && parsed.amount != null && !parsed.unlimited
            } }) limits.passes else {
                diagnostics += "Height restrictor has no evaluated clearance"; Match.UNKNOWN
            }
            barrier in setOf("toll_booth", "border_control") -> { controls += barrier!!; Match.YES }
            barrier in setOf("drawbridge", "wedge", "spikes", "cones") -> { controls += barrier!!; fact("barrier_open") }
            else -> { diagnostics += "Unsupported point barrier: $barrier"; Match.UNKNOWN }
        }
        if (normalized(tags["ford"]) !in setOf(null, "no")) { controls += "ford"; structure = all(structure, fact("ford_passable")) }
        tags["opening_hours"]?.let {
            val evaluated = RestrictionCondition.evaluate(it, context)
            structure = all(structure, evaluated.match)
            diagnostics += evaluated.unresolved
        }
        if (structure == Match.NO) diagnostics += "Crossed node has a non-passable physical constraint"
        if (structure == Match.UNKNOWN) diagnostics += "Crossed-node physical constraint remains unresolved"
        return Decision(structure, access, limits, controls, tags.toMap(), (diagnostics + access.diagnostics + limits.diagnostics).distinct())
    }
}
