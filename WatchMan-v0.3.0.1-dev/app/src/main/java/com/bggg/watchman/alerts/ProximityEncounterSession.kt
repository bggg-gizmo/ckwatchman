package com.bggg.watchman.alerts

/** One process-wide encounter state shared by Activity and foreground service. */
object ProximityEncounterSession {
    const val PREF_STATE_KEY = "proximityEncounterStateV1"
    val tracker = ProximityEncounterTracker()

    private var restored = false

    @Synchronized
    fun restoreOnce(encoded: String?) {
        if (restored) return
        tracker.restore(encoded)
        restored = true
    }

    fun encode(): String = tracker.encode()

    @Synchronized
    fun reset() {
        tracker.reset()
        restored = true
    }

    @Synchronized
    fun resetForTest() {
        tracker.reset()
        restored = false
    }
}