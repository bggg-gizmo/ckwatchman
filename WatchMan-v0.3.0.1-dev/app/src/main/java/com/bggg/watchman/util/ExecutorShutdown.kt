package com.bggg.watchman.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Interrupts every owned executor first, then shares one bounded wait budget across all of them. */
object ExecutorShutdown {
    fun shutdownAll(executors: List<ExecutorService>, totalWaitMillis: Long): Boolean {
        val owned = executors.distinct()
        owned.forEach { it.shutdownNow() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalWaitMillis.coerceAtLeast(0L))
        for (executor in owned) {
            if (executor.isTerminated) continue
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) break
            try {
                executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return owned.all { it.isTerminated }
    }
}
