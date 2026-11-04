package com.bggg.watchman.ui

/** Per-launch presentation. Persistent acquisition preferences never gate the launch choice. */
class LaunchPresentation(
    stageName: String? = null,
    remainingMillis: Long = INTRO_MILLIS
) {
    enum class Stage { INTRO, CHOICE, SETUP, NAVIGATION }
    var stage: Stage = Stage.values().firstOrNull { it.name == stageName } ?: Stage.INTRO
        private set
    private var remaining = remainingMillis.coerceIn(0L, INTRO_MILLIS)
    private var resumedAt: Long? = null

    fun resume(nowMillis: Long): Long {
        if (stage != Stage.INTRO) return 0L
        if (resumedAt == null) resumedAt = nowMillis
        return remainingMillis(nowMillis)
    }

    fun remainingMillis(nowMillis: Long): Long =
        (remaining - (resumedAt?.let { (nowMillis - it).coerceAtLeast(0L) } ?: 0L)).coerceAtLeast(0L)

    fun pause(nowMillis: Long) {
        remaining = remainingMillis(nowMillis)
        resumedAt = null
    }

    fun advance(nowMillis: Long): Boolean {
        if (stage != Stage.INTRO || remainingMillis(nowMillis) > 0L) return false
        stage = Stage.CHOICE
        remaining = 0L
        resumedAt = null
        return true
    }

    fun showSetup() { stage = Stage.SETUP; resumedAt = null }
    fun showNavigation() { stage = Stage.NAVIGATION; resumedAt = null }

    companion object { const val INTRO_MILLIS = 3_000L }
}
