package com.bggg.watchman.alerts

/**
 * Process-local, explicit foreground/background tone handoff. The previous owner's stop callback
 * runs before the new owner becomes eligible, preventing overlapping audio during lifecycle moves.
 */
object ProximityAudioOwnership {
    enum class Owner { NONE, ACTIVITY, SERVICE }

    private val lock = Any()
    private val stopCallbacks = HashMap<Owner, () -> Unit>()
    private var currentOwner = Owner.NONE
    private var generation = 0L

    data class Snapshot(val owner: Owner, val generation: Long)

    fun register(owner: Owner, stop: () -> Unit) {
        require(owner != Owner.NONE)
        synchronized(lock) { stopCallbacks[owner] = stop }
    }

    fun unregister(owner: Owner) {
        synchronized(lock) {
            if (currentOwner == owner) {
                stopCallbacks[owner]?.invoke()
                currentOwner = Owner.NONE
                generation++
            }
            stopCallbacks.remove(owner)
        }
    }

    fun transferTo(owner: Owner): Snapshot = synchronized(lock) {
        if (currentOwner != owner) {
            val oldOwner = currentOwner
            if (oldOwner != Owner.NONE) stopCallbacks[oldOwner]?.invoke()
            currentOwner = owner
            generation++
        }
        Snapshot(currentOwner, generation)
    }

    fun release(owner: Owner): Snapshot = synchronized(lock) {
        if (currentOwner == owner) {
            stopCallbacks[owner]?.invoke()
            currentOwner = Owner.NONE
            generation++
        }
        Snapshot(currentOwner, generation)
    }

    fun isOwner(owner: Owner): Boolean = synchronized(lock) { currentOwner == owner }

    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(currentOwner, generation) }

    /** Test/reset hook; production lifecycle does not need it. */
    fun resetForTest() = synchronized(lock) {
        if (currentOwner != Owner.NONE) stopCallbacks[currentOwner]?.invoke()
        currentOwner = Owner.NONE
        generation++
        stopCallbacks.clear()
    }
}
