package com.bggg.watchman.net

/** Monotonic total-operation budget shared by connection, body read, and retry delay. */
class OperationDeadline(
    totalMillis: Long,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val startedNanos: Long
    private val durationNanos: Long

    init {
        require(totalMillis > 0L)
        durationNanos = totalMillis.coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L
        startedNanos = nanoTime()
    }

    fun remainingMillis(): Long {
        val elapsedNanos = (nanoTime() - startedNanos).coerceAtLeast(0L)
        val remainingNanos = durationNanos - elapsedNanos
        if (remainingNanos <= 0L) return 0L
        return ((remainingNanos + 999_999L) / 1_000_000L).coerceAtLeast(1L)
    }

    fun socketTimeoutMillis(maxMillis: Int = 60_000): Int =
        remainingMillis().coerceIn(1L, maxMillis.coerceAtLeast(1).toLong()).toInt()

    fun admittedRetryDelayMillis(requestedMillis: Long): Long? {
        val remaining = remainingMillis()
        val requested = requestedMillis.coerceAtLeast(0L)
        return requested.takeIf { remaining > it }
    }

    fun isExpired(): Boolean = remainingMillis() == 0L
}