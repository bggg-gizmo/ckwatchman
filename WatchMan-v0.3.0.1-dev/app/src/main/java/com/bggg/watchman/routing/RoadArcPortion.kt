package com.bggg.watchman.routing

import java.math.BigDecimal
import java.math.RoundingMode
import com.bggg.watchman.routing.MotorcarTagPolicy.Direction

/** Fractions in the ORIGINAL directed arc's ordering, not projected points promoted to nodes.
 * This is geometry/accounting only: permissions, barriers, turn history and timing are separate.
 */
data class RoadArcPortion(val arcId:Long,val fromFraction:Double,val toFraction:Double,
    val direction:Direction=Direction.FORWARD) {
    init {
        require(arcId>0 && fromFraction.isFinite() && toFraction.isFinite() &&
            fromFraction>=0.0 && toFraction<=1.0 && fromFraction<toFraction) { "Invalid directed arc portion" }
    }
    /** Cumulative rounding telescopes across splits, including distances above Double's exact range. */
    fun distanceMm(sourceLengthMm:Long):Long {
        require(sourceLengthMm>=0) { "Negative source distance" }
        if(sourceLengthMm==0L || (fromFraction==0.0 && toFraction==1.0)) return sourceLengthMm
        fun cumulative(fraction:Double):Long {
            val directed=BigDecimal.valueOf(fraction)
            val canonical=if(direction==Direction.FORWARD)directed else BigDecimal.ONE.subtract(directed)
            return BigDecimal.valueOf(sourceLengthMm).multiply(canonical).setScale(0,RoundingMode.HALF_UP).longValueExact()
        }
        val difference=cumulative(toFraction)-cumulative(fromFraction)
        return if(direction==Direction.FORWARD)difference else -difference
    }
}
