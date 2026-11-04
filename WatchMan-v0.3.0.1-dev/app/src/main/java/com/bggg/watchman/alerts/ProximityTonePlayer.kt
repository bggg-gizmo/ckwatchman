package com.bggg.watchman.alerts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/** Renders every cadence and sustained state from one generated PCM warning tone family. */
class ProximityTonePlayer {
    private val handler = Handler(Looper.getMainLooper())
    private var pulseTrack: AudioTrack? = null
    private var continuousTrack: AudioTrack? = null
    private var volumePercent = -1
    private var selection: ProximityAudioPolicy.Selection? = null
    private var released = false

    private val pulse = object : Runnable {
        override fun run() {
            if (released) return
            val current = selection ?: return
            if (current.continuous) {
                startContinuous()
                return
            }
            ensureTracks()
            runCatching {
                pulseTrack?.stop()
                pulseTrack?.reloadStaticData()
                pulseTrack?.play()
            }
            val interval = current.pulseIntervalMs.coerceAtLeast(current.pulseDurationMs + 20L)
            handler.postDelayed(this, interval)
        }
    }

    fun update(
        next: ProximityAudioPolicy.Selection?,
        enabled: Boolean,
        requestedVolumePercent: Int
    ) {
        if (released) return
        val normalizedVolume = requestedVolumePercent.coerceIn(0, 100)
        val effective = next.takeIf { enabled && normalizedVolume > 0 }
        val modeChanged = effective?.continuous != selection?.continuous ||
            effective?.deviceId != selection?.deviceId ||
            normalizedVolume != volumePercent
        selection = effective
        if (modeChanged) {
            stopPlayback()
            if (volumePercent != normalizedVolume) {
                releaseTracks()
                volumePercent = normalizedVolume
            }
            if (effective != null) handler.post(pulse)
        }
    }

    fun stop() {
        selection = null
        stopPlayback()
    }

    fun release() {
        if (released) return
        released = true
        selection = null
        stopPlayback()
        releaseTracks()
    }

    private fun startContinuous() {
        ensureTracks()
        runCatching {
            continuousTrack?.stop()
            continuousTrack?.reloadStaticData()
            continuousTrack?.setLoopPoints(0, ProximityToneWaveform.continuousLoop().size, -1)
            continuousTrack?.play()
        }
    }

    private fun ensureTracks() {
        if (volumePercent <= 0) return
        if (pulseTrack == null) pulseTrack = buildStaticTrack(ProximityToneWaveform.pulse(), loop = false)
        if (continuousTrack == null) continuousTrack = buildStaticTrack(ProximityToneWaveform.continuousLoop(), loop = true)
    }

    private fun buildStaticTrack(samples: ShortArray, loop: Boolean): AudioTrack? = runCatching {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(ProximityToneWaveform.SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        require(track.write(samples, 0, samples.size) == samples.size)
        track.setVolume(volumePercent.coerceIn(0, 100) / 100f)
        if (loop) track.setLoopPoints(0, samples.size, -1)
        track
    }.getOrNull()

    private fun stopPlayback() {
        handler.removeCallbacks(pulse)
        runCatching { pulseTrack?.stop() }
        runCatching { continuousTrack?.stop() }
    }

    private fun releaseTracks() {
        runCatching { pulseTrack?.release() }
        runCatching { continuousTrack?.release() }
        pulseTrack = null
        continuousTrack = null
    }
}