package com.bggg.watchman.util

import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** One immutable packaged-asset validation per process, with no blocking reader or strong view owner. */
class SharedAssetGate(private val executor: Executor) {
    interface Listener { fun onAssetReady(valid: Boolean) }
    data class Snapshot(val valid: Boolean?, val inFlight: Boolean, val loads: Int, val threadName: String?)

    private val lock = Any()
    private val listeners = ArrayList<WeakReference<Listener>>()
    @Volatile private var valid: Boolean? = null
    private var inFlight = false
    private var loads = 0
    private var threadName: String? = null

    /** null means pending (or rejected submission); false is a completed fail-closed validation. */
    fun observe(listener: Listener, load: () -> Boolean): Boolean? {
        valid?.let { return it }
        val start = synchronized(lock) {
            valid?.let { return it }
            listeners.removeAll { it.get() == null }
            if (listeners.none { it.get() === listener }) listeners += WeakReference(listener)
            if (inFlight) false else { inFlight = true; true }
        }
        if (start) {
            try {
                executor.execute {
                    synchronized(lock) { loads++; threadName = Thread.currentThread().name }
                    val result = runCatching(load).getOrDefault(false)
                    val notify = synchronized(lock) {
                        valid = result
                        inFlight = false
                        listeners.mapNotNull { it.get() }.also { listeners.clear() }
                    }
                    // A detached view checks its active flag as well; one bad callback cannot starve others.
                    notify.forEach { runCatching { it.onAssetReady(result) } }
                }
            } catch (_: RejectedExecutionException) {
                // Keep the manifest unknown, not permanently invalid. A later observe can resubmit.
                synchronized(lock) { inFlight = false }
            }
        }
        return valid
    }

    fun forget(listener: Listener) = synchronized(lock) {
        listeners.removeAll { it.get() == null || it.get() === listener }
        Unit
    }

    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(valid, inFlight, loads, threadName) }
}
