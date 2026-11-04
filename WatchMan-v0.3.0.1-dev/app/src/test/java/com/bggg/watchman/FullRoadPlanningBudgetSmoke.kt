package com.bggg.watchman

import com.bggg.watchman.routing.FullRoadPlanningBudget

fun fullRoadPlanningBudgetSmoke() {
    var now = 7_000_000_000L
    val start = now
    val clock = FullRoadPlanningBudget(start, { now })
    check(clock.state() == FullRoadPlanningBudget.State(0, 25_000, false, false))
    now += 8_500_000_000L // Existing baseline/provider work is NOT free preparation time.
    check(clock.remainingSearchMillis(20_000) == 16_500L)
    now += 4_250_000_000L // Pack opening and source endpoint/control preparation.
    check(clock.remainingSearchMillis(20_000) == 12_250L)
    check(clock.remainingSearchMillis(20_000, 1000) == 11_250L)
    check(clock.remainingSearchMillis(2_000) == 2_000L)
    now = start + 20_000_000_000L
    check(clock.state() == FullRoadPlanningBudget.State(20_000, 5_000, true, false))
    now = start + 24_999_999_999L
    check(clock.remainingSearchMillis(20_000) == 1L)
    check(clock.remainingSearchMillis(20_000, 1000) == 0L)
    now = start + 25_000_000_000L
    check(clock.state() == FullRoadPlanningBudget.State(25_000, 0, true, true))
    now = start + 30_000_000_000L
    check(clock.remainingSearchMillis(20_000) == 0L)
    check(runCatching { clock.remainingSearchMillis(0) }.isFailure)
    check(runCatching { clock.remainingSearchMillis(20_000, -1) }.isFailure)
    check(runCatching { clock.remainingSearchMillis(20_000, 25_000) }.isFailure)
    check(runCatching { FullRoadPlanningBudget(start, { now }, 20_001, 25_000) }.isFailure)
    check(runCatching { FullRoadPlanningBudget(start, { now }, 20_000, 25_001) }.isFailure)
    check(runCatching { FullRoadPlanningBudget(start, { now }, 20_000, 19_999) }.isFailure)
    now = start - 1
    check(runCatching { clock.state() }.isFailure)
    val wrapped = FullRoadPlanningBudget(Long.MAX_VALUE - 999_999L, { Long.MIN_VALUE + 1_000_000L })
    check(wrapped.state().elapsedMillis == 2L)
    var shortNow = 0L
    val short = FullRoadPlanningBudget(0, { shortNow }, 3, 5)
    shortNow = 4_000_000
    check(short.state() == FullRoadPlanningBudget.State(4, 1, true, false))
    println("Full-road whole-operation budget: baseline/preparation debit, target/hard boundary, exact remaining search allowance, monotonic wrap and invalid-clock refusal PASS")
}
