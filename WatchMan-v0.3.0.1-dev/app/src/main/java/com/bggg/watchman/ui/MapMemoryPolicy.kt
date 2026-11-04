package com.bggg.watchman.ui

/**
 * Deterministic decoded-map cache budgets for Android memory-pressure callbacks.
 *
 * The integer thresholds mirror Android's ComponentCallbacks2 levels without coupling this pure
 * policy to the Android runtime, so the behavior remains executable in the host regression suite.
 * Disk raster tiles and bundled offline-road assets are never deleted by this policy.
 */
object MapMemoryPolicy {
    const val NORMAL_TILE_BYTES = 48 * 1024 * 1024
    const val NORMAL_OFFLINE_ROAD_POINTS = 500_000

    private const val RUNNING_MODERATE = 5
    private const val RUNNING_LOW = 10
    private const val RUNNING_CRITICAL = 15

    data class CacheBudget(
        val tileBytes: Int,
        val offlineRoadPoints: Int,
        val releasePerViewIcons: Boolean
    )

    fun budgetForTrimLevel(level: Int): CacheBudget? = when {
        level >= RUNNING_CRITICAL -> CacheBudget(
            tileBytes = 0,
            offlineRoadPoints = 0,
            releasePerViewIcons = true
        )
        level >= RUNNING_LOW -> CacheBudget(
            tileBytes = 12 * 1024 * 1024,
            offlineRoadPoints = 125_000,
            releasePerViewIcons = false
        )
        level >= RUNNING_MODERATE -> CacheBudget(
            tileBytes = 24 * 1024 * 1024,
            offlineRoadPoints = 250_000,
            releasePerViewIcons = false
        )
        else -> null
    }
}
