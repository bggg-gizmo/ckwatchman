package com.bggg.watchman.alerts

/** Owner-thread rendezvous for an asynchronous constructor and its potentially earlier callback. */
class SpeechInitializationGate {
    var generation = 0L
        private set
    var waiting = false
        private set
    private var pendingResult: Int? = null

    fun begin(): Long {
        check(!waiting) { "Speech initialization already in flight" }
        generation++
        waiting = true
        pendingResult = null
        return generation
    }

    fun callback(token: Long, result: Int, enginePublished: Boolean): Int? {
        if (token != generation || !waiting) return null
        if (!enginePublished) {
            if (pendingResult == null) pendingResult = result
            return null
        }
        waiting = false
        pendingResult = null
        return result
    }

    fun published(token: Long): Int? {
        if (token != generation || !waiting) return null
        val result = pendingResult ?: return null
        pendingResult = null
        waiting = false
        return result
    }

    fun cancel() {
        generation++
        waiting = false
        pendingResult = null
    }
}
