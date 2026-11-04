package com.bggg.watchman.alerts

import kotlin.math.PI
import kotlin.math.sin

/** One PCM warning identity used for every pulsed and sustained proximity state. */
object ProximityToneWaveform {
    const val SAMPLE_RATE_HZ = 16_000
    const val FREQUENCY_HZ = 880.0
    private const val AMPLITUDE = 0.28
    private const val ATTACK_RELEASE_MS = 8.0

    fun pulse(durationMs: Int = ProximityAudioPolicy.PULSE_DURATION_MS): ShortArray =
        generate(durationMs.coerceAtLeast(20), envelope = true)

    /** One exact-second loop: 880 complete cycles, so the loop boundary is phase-continuous. */
    fun continuousLoop(): ShortArray = generate(1_000, envelope = false)

    fun estimatedFrequencyHz(samples: ShortArray): Double {
        if (samples.size < 3) return 0.0
        var positiveCrossings = 0
        for (index in 1 until samples.size) {
            if (samples[index - 1] <= 0 && samples[index] > 0) positiveCrossings++
        }
        return positiveCrossings * SAMPLE_RATE_HZ.toDouble() / samples.size
    }

    private fun generate(durationMs: Int, envelope: Boolean): ShortArray {
        val count = (SAMPLE_RATE_HZ * durationMs / 1_000).coerceAtLeast(1)
        val edgeSamples = (SAMPLE_RATE_HZ * ATTACK_RELEASE_MS / 1_000.0).toInt().coerceAtLeast(1)
        return ShortArray(count) { index ->
            val gain = if (!envelope) 1.0 else {
                val attack = (index.toDouble() / edgeSamples).coerceIn(0.0, 1.0)
                val release = ((count - 1 - index).toDouble() / edgeSamples).coerceIn(0.0, 1.0)
                minOf(attack, release)
            }
            val phase = 2.0 * PI * FREQUENCY_HZ * index / SAMPLE_RATE_HZ
            (sin(phase) * gain * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
