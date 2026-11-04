package com.bggg.watchman.service

/** Bounds durable encounter work without reducing the foreground GPS/navigation cadence. */
object NavigationAnalyticsPolicy {
    const val ENCOUNTER_SCAN_INTERVAL_MS = 1_000L
    const val ENCOUNTER_SCAN_MOVE_METERS = 10.0

    fun shouldScanEncounters(
        nowMillis: Long,
        lastScanMillis: Long,
        movedMeters: Double
    ): Boolean = lastScanMillis <= 0L ||
        nowMillis - lastScanMillis >= ENCOUNTER_SCAN_INTERVAL_MS ||
        movedMeters >= ENCOUNTER_SCAN_MOVE_METERS
}
