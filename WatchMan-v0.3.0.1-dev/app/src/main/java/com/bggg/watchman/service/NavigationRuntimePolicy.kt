package com.bggg.watchman.service

/** Deterministic battery/network policy. Alert evaluation remains cache-first on every fix. */
object NavigationRuntimePolicy {
    const val ACTIVE_DEVICE_SNAPSHOT_MS = 1_000L
    const val IDLE_DEVICE_SNAPSHOT_MS = 3_000L
    const val ACTIVE_DEVICE_SNAPSHOT_MOVE_METERS = 25.0
    const val IDLE_DEVICE_SNAPSHOT_MOVE_METERS = 75.0
    const val EMPTY_CACHE_REFRESH_MS = 5_000L
    const val ACTIVE_ROUTE_REFRESH_MS = 15_000L
    const val IDLE_REFRESH_MS = 45_000L
    const val MAX_BACKOFF_MS = 5 * 60_000L

    fun refreshIntervalMs(cachedDeviceCount: Int, activeRoute: Boolean, consecutiveFailures: Int): Long {
        val base = when {
            cachedDeviceCount <= 0 -> EMPTY_CACHE_REFRESH_MS
            activeRoute -> ACTIVE_ROUTE_REFRESH_MS
            else -> IDLE_REFRESH_MS
        }
        val multiplier = 1L shl consecutiveFailures.coerceIn(0, 5)
        return (base * multiplier).coerceAtMost(MAX_BACKOFF_MS)
    }

    fun movementTriggerMeters(cachedDeviceCount: Int, activeRoute: Boolean): Double = when {
        cachedDeviceCount <= 0 -> 75.0
        activeRoute -> 200.0
        else -> 500.0
    }

    fun shouldRefreshDeviceSnapshot(
        hasSnapshot: Boolean,
        activeRoute: Boolean,
        elapsedMillis: Long,
        movedMeters: Double
    ): Boolean {
        if (!hasSnapshot) return true
        val interval = if (activeRoute) ACTIVE_DEVICE_SNAPSHOT_MS else IDLE_DEVICE_SNAPSHOT_MS
        val movement = if (activeRoute) ACTIVE_DEVICE_SNAPSHOT_MOVE_METERS else IDLE_DEVICE_SNAPSHOT_MOVE_METERS
        return elapsedMillis.coerceAtLeast(0L) >= interval || movedMeters.coerceAtLeast(0.0) >= movement
    }

    fun shouldRetryHttp(code: Int): Boolean = code == 408 || code == 425 || code == 429 || code in 500..599

    fun retryDelayMs(attempt: Int, retryAfterMs: Long? = null): Long {
        val computed = when (attempt.coerceAtLeast(0)) {
            0 -> 500L
            1 -> 1_500L
            else -> 3_500L
        }
        return (retryAfterMs ?: computed).coerceIn(250L, 15_000L)
    }
}
