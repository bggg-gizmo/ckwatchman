package com.bggg.watchman.ui

import com.bggg.watchman.data.RouteStep
import java.util.Locale

/** Shared classification for the on-screen and exported maneuver signs. */
object ManeuverVisual {
    enum class Kind {
        STRAIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, TURN_LEFT, TURN_RIGHT, SHARP_LEFT, SHARP_RIGHT,
        U_TURN_LEFT, U_TURN_RIGHT, MERGE_LEFT, MERGE_RIGHT, FORK_LEFT, FORK_RIGHT,
        RAMP_LEFT, RAMP_RIGHT, ROUNDABOUT_LEFT, ROUNDABOUT_RIGHT, ARRIVE
    }

    fun classify(step: RouteStep): Kind {
        val type = step.maneuverType.lowercase(Locale.US)
        val modifier = step.maneuverModifier.lowercase(Locale.US)
        val words = (step.instruction + " " + modifier).lowercase(Locale.US)
        val right = modifier.contains("right") || words.contains("right")
        return when {
            type == "arrive" || words.contains("arrive") -> Kind.ARRIVE
            type == "roundabout" || type == "rotary" || words.contains("roundabout") -> if (right) Kind.ROUNDABOUT_RIGHT else Kind.ROUNDABOUT_LEFT
            modifier.contains("uturn") || words.contains("u-turn") || words.contains("uturn") -> if (right) Kind.U_TURN_RIGHT else Kind.U_TURN_LEFT
            type == "merge" || words.contains("merge") -> if (right) Kind.MERGE_RIGHT else Kind.MERGE_LEFT
            type == "fork" || words.contains("keep left") || words.contains("keep right") -> if (right) Kind.FORK_RIGHT else Kind.FORK_LEFT
            type == "on ramp" || type == "off ramp" || words.contains("ramp") || words.contains("exit") -> if (right) Kind.RAMP_RIGHT else Kind.RAMP_LEFT
            modifier.contains("sharp left") -> Kind.SHARP_LEFT
            modifier.contains("sharp right") -> Kind.SHARP_RIGHT
            modifier.contains("slight left") -> Kind.SLIGHT_LEFT
            modifier.contains("slight right") -> Kind.SLIGHT_RIGHT
            modifier.contains("left") || words.contains("turn left") -> Kind.TURN_LEFT
            modifier.contains("right") || words.contains("turn right") -> Kind.TURN_RIGHT
            else -> Kind.STRAIGHT
        }
    }
}
