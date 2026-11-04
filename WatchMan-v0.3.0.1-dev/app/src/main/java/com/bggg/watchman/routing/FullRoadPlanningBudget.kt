package com.bggg.watchman.routing

/** A whole-operation clock, including pack opening, endpoint/model preparation and cache reads.
 * The camera target is diagnostic; the hard ceiling is authoritative. A search starts with
 * only the time that is actually left, never another independent 20/25-second allowance.
 */
class FullRoadPlanningBudget(
    private val startedAtNanos: Long,
    private val nanoTime: () -> Long = System::nanoTime,
    val targetMillis: Long = 20_000L,
    val hardDeadlineMillis: Long = 25_000L
) {
    data class State(val elapsedMillis: Long, val remainingMillis: Long,
        val targetReached: Boolean, val hardDeadlineReached: Boolean)

    init {
        require(targetMillis in 1..20_000L)
        require(hardDeadlineMillis in targetMillis..25_000L)
    }

    fun state(): State {
        // System.nanoTime is not wall time and its signed value may wrap. Subtraction preserves
        // short elapsed intervals across that wrap; negative elapsed is a broken/mismatched clock.
        val elapsedNanos = nanoTime() - startedAtNanos
        check(elapsedNanos >= 0) { "Full-road monotonic clock moved backwards or belongs to another epoch" }
        val elapsed = elapsedNanos / 1_000_000L
        return State(elapsed, (hardDeadlineMillis - elapsed).coerceAtLeast(0L),
            elapsed >= targetMillis, elapsed >= hardDeadlineMillis)
    }

    /** Reserve comes from this same clock, never an additional materialization allowance. */
    fun remainingSearchMillis(requestedMillis: Long, reserveMillis: Long = 0): Long {
        require(requestedMillis > 0)
        require(reserveMillis in 0 until hardDeadlineMillis)
        return minOf(requestedMillis, (state().remainingMillis - reserveMillis).coerceAtLeast(0))
    }
}
