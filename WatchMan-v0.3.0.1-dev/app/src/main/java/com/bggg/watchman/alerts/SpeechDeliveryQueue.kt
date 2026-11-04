package com.bggg.watchman.alerts

/** Single utterance in flight. Completion is acknowledged only by the matching onDone callback. */
class SpeechDeliveryQueue {
    enum class Kind(val priority: Int) { PREVIEW(1), CAMERA(2), MANEUVER(3), TEST(4) }
    data class Request(
        val key: String, val text: String, val kind: Kind, val expiresAt: Long,
        val relevant: () -> Boolean = { true }, val delivered: () -> Unit = {}
    )
    data class Attempt(val request: Request, val utteranceId: String, val queuedAt: Long)
    private data class Pending(val request: Request, var retries: Int = 0, var after: Long = 0L)
    private val waiting = LinkedHashMap<String, Pending>()
    private var sequence = 0L
    var active: Attempt? = null
        private set
    private var activePending: Pending? = null

    fun offer(request: Request): Boolean {
        if (active?.request?.key == request.key || waiting.containsKey(request.key)) return false
        if (waiting.size >= 64) return false // caller remains eligible and retries after room becomes available
        waiting[request.key] = Pending(request)
        return true
    }

    fun next(now: Long, ready: Boolean): Attempt? {
        waiting.entries.removeAll { it.value.request.expiresAt <= now || !it.value.request.relevant() }
        if (!ready || active != null) return null
        val pending = waiting.values.filter { it.after <= now }
            .maxByOrNull { it.request.kind.priority } ?: return null
        waiting.remove(pending.request.key)
        val attempt = Attempt(pending.request, "watchman-speech-${++sequence}", now)
        active = attempt
        activePending = pending
        return attempt
    }

    fun done(id: String): Boolean {
        val attempt = active?.takeIf { it.utteranceId == id } ?: return false
        active = null
        activePending = null
        attempt.request.delivered()
        return true
    }

    fun failed(id: String, now: Long): Boolean {
        val attempt = active?.takeIf { it.utteranceId == id } ?: return false
        val pending = activePending
        active = null
        activePending = null
        if (pending != null && attempt.request.expiresAt > now && attempt.request.relevant()) {
            pending.retries++
            pending.after = now + minOf(5000L, 500L * pending.retries)
            waiting[attempt.request.key] = pending
        }
        return true
    }

    fun cancelExpired(now: Long): String? {
        val attempt = active ?: return null
        if (attempt.request.expiresAt > now && attempt.request.relevant()) return null
        active = null
        activePending = null
        return attempt.utteranceId
    }

    fun clear() { active = null; activePending = null; waiting.clear() }
    fun hasWork(): Boolean = active != null || waiting.isNotEmpty()
}
