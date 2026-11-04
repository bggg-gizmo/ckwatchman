package com.bggg.watchman.export

import kotlin.math.floor

/** Bounded, deterministic PDF label placement in page coordinates; no Android dependency. */
class MapLabelLayout(
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float
) {
    data class Placement(val x: Float, val baseline: Float, val right: Float, val top: Float, val bottom: Float)
    private val occupied = mutableListOf<Placement>()
    private val cells = HashSet<Pair<Int, Int>>()

    fun place(x: Float, baseline: Float, width: Float, ascent: Float, descent: Float): Placement? {
        if (!listOf(x, baseline, width, ascent, descent, left, top, right, bottom).all { it.isFinite() }) return null
        if (width <= 0f || ascent > 0f || descent < 0f || descent <= ascent) return null
        if (width > right - left || descent - ascent > bottom - top) return null
        val actualX = x.coerceIn(left, right - width)
        val actualBaseline = baseline.coerceIn(top - ascent, bottom - descent)
        val cell = floor((actualX - left) / 90f).toInt() to floor((actualBaseline - top) / 22f).toInt()
        if (cell in cells) return null
        val candidate = Placement(actualX, actualBaseline, actualX + width, actualBaseline + ascent, actualBaseline + descent)
        if (occupied.any { previous ->
                candidate.x < previous.right + GAP && candidate.right + GAP > previous.x &&
                    candidate.top < previous.bottom + GAP && candidate.bottom + GAP > previous.top
            }) return null
        cells.add(cell)
        occupied.add(candidate)
        return candidate
    }

    companion object { private const val GAP = 2f }
}
