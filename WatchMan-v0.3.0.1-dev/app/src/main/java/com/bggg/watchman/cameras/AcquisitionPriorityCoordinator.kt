package com.bggg.watchman.cameras

import java.io.Closeable

/**
 * Cooperative acquisition priority without destroying resumable national state.
 *
 * National work waits only between public requests. Active requests finish normally. Higher
 * priority leases are reference-counted so every success, failure, timeout, and cancellation path
 * can release independently without resuming national work too early.
 */
class AcquisitionPriorityCoordinator {
    enum class Work(val rank: Int) {
        NATIONAL(0),
        GPS_STATE(1),
        ROUTE_CORRIDOR(2)
    }

    private val monitor = Object()
    private val leaseCounts = IntArray(Work.values().size)

    @Volatile
    var activeWork: Work = Work.NATIONAL
        private set

    fun acquire(work: Work): Lease {
        require(work != Work.NATIONAL)
        synchronized(monitor) {
            leaseCounts[work.ordinal]++
            recomputeLocked()
            monitor.notifyAll()
        }
        return Lease(work)
    }

    @Throws(InterruptedException::class)
    fun awaitNationalTurn(shouldStop: () -> Boolean) {
        synchronized(monitor) {
            while (activeWork != Work.NATIONAL && !shouldStop()) {
                monitor.wait(WAIT_SLICE_MS)
            }
            if (shouldStop()) throw InterruptedException("National acquisition stopped")
        }
    }

    fun signalStateChanged() {
        synchronized(monitor) { monitor.notifyAll() }
    }

    inner class Lease internal constructor(private val work: Work) : Closeable {
        private var closed = false

        override fun close() {
            synchronized(monitor) {
                if (closed) return
                closed = true
                leaseCounts[work.ordinal] = (leaseCounts[work.ordinal] - 1).coerceAtLeast(0)
                recomputeLocked()
                monitor.notifyAll()
            }
        }
    }

    private fun recomputeLocked() {
        activeWork = Work.values()
            .filter { it == Work.NATIONAL || leaseCounts[it.ordinal] > 0 }
            .maxBy { it.rank }
    }

    companion object {
        private const val WAIT_SLICE_MS = 250L
    }
}
