package com.bggg.watchman.navigation

/**
 * Serializes the final commit edge of asynchronous route activation/recovery work.
 * Expensive preparation may run without this lock, but stale generations may never
 * persist or publish a route after a newer activation, stop, or lifecycle teardown.
 */
class NavigationActivationCoordinator {
    private var generation: Long = 0L

    @Synchronized
    fun begin(): Long {
        generation += 1L
        return generation
    }

    @Synchronized
    fun cancel() {
        generation += 1L
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = token == generation

    @Synchronized
    fun runIfCurrent(token: Long, action: () -> Unit): Boolean {
        if (token != generation) return false
        action()
        return true
    }
}
