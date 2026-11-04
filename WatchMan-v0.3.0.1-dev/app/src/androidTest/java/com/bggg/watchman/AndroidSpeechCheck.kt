package com.bggg.watchman

import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import com.bggg.watchman.alerts.WatchManSpeech

/** Installed Android engine/callback evidence, not proof of physical audibility or service handoff. */
object AndroidSpeechCheck {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("watchman", Context.MODE_PRIVATE)
        val originalVoice = if (prefs.contains("voice")) prefs.getBoolean("voice", true) else null
        val originalVolume = if (prefs.contains("alertVolume")) prefs.getInt("alertVolume", 85) else null
        val owner = "instrumentation-speech-${SystemClock.elapsedRealtime()}"
        var acquireMillis = 0L
        try {
            prefs.edit().putBoolean("voice", true).putInt("alertVolume", 30)
                .remove("speechDiagnostics").remove("britishVoiceStatus").commit()
            instrumentation.runOnMainSync {
                val began = SystemClock.elapsedRealtime()
                WatchManSpeech.acquire(context, owner)
                WatchManSpeech.testVoice()
                // Force early refresh calls before the posted onInit callback can run.
                repeat(10) { WatchManSpeech.refresh() }
                acquireMillis = SystemClock.elapsedRealtime() - began
            }
            val deadline = SystemClock.elapsedRealtime() + 45_000L
            var diagnostic = ""
            var status = ""
            while (SystemClock.elapsedRealtime() < deadline) {
                diagnostic = prefs.getString("speechDiagnostics", "").orEmpty()
                status = prefs.getString("britishVoiceStatus", "").orEmpty()
                val queued = Regex("queued (watchman-speech-[0-9]+) TEST").findAll(diagnostic).map { it.groupValues[1] }.toList()
                if (queued.any { diagnostic.contains("delivered $it\n") || diagnostic.endsWith("delivered $it") }) {
                    check(diagnostic.contains("engine_constructed_off_main generation=")) {
                        "No background construction evidence: $diagnostic"
                    }
                    check(diagnostic.contains("voice_prepared thread=watchman-speech-io")) {
                        "Voice selection did not report the dedicated worker: $diagnostic"
                    }
                    check(status.startsWith("British English ready:")) { "Unexpected selected-voice status: $status" }
                    check(diagnostic.contains("locale=en-GB")) { "No explicit en-GB voice evidence: $diagnostic" }
                    return "PASS: real Android engine prepared off-main; early refresh burst; Test Voice onDone callback. " +
                        "Acquire/submit/refresh dispatch=${acquireMillis}ms. $status\n$diagnostic\n" +
                        "Not physical audibility, camera encounter playback or Activity/service handoff acceptance."
                }
                if (status.contains("voice unavailable") || status.contains("Speech engine unavailable")) break
                SystemClock.sleep(100L)
            }
            return "INCOMPLETE: installed engine did not deliver Test Voice within the bounded test. " +
                "Acquire/submit/refresh dispatch=${acquireMillis}ms. $status\n$diagnostic"
        } finally {
            instrumentation.runOnMainSync { WatchManSpeech.release(owner) }
            SystemClock.sleep(2200L) // existing Activity/service ownership grace period
            prefs.edit().apply {
                if (originalVoice == null) remove("voice") else putBoolean("voice", originalVoice)
                if (originalVolume == null) remove("alertVolume") else putInt("alertVolume", originalVolume)
            }.commit()
        }
    }
}
