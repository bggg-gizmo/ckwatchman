package com.bggg.watchman.alerts

object BritishVoicePolicy {
    data class Candidate(val name: String, val language: String, val country: String,
                         val quality: Int, val latency: Int, val networkRequired: Boolean,
                         val installed: Boolean)
    fun select(voices: List<Candidate>): Candidate? = voices.asSequence()
        .filter { it.language.equals("en", true) && it.country.equals("GB", true) && it.installed }
        .sortedWith(compareBy<Candidate> { it.networkRequired }
            .thenByDescending { it.quality }.thenBy { it.latency }.thenBy { it.name })
        .firstOrNull()
}
