package com.bggg.watchman.preferences

import android.content.SharedPreferences
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.bggg.watchman.alerts.ProximityAudioPolicy
import com.bggg.watchman.util.Geo

object UserPreferences {
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"
    const val DEFAULT_BROAD_ALERT_FEET = 2_000
    const val DEFAULT_PROXIMITY_START_FEET = 1_200
    const val DEFAULT_VISUAL_RANGE_FEET = 350
    const val DEFAULT_FOV_FALLBACK_FEET = 120

    fun isLight(prefs: SharedPreferences): Boolean = prefs.getString("appearance", THEME_DARK) == THEME_LIGHT

    fun alertVolume(prefs: SharedPreferences): Float =
        (prefs.getInt("alertVolume", 85).coerceIn(0, 100) / 100f)

    fun ttsBundle(prefs: SharedPreferences): Bundle = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, alertVolume(prefs))
    }

    fun proximityToneEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean("proximityToneEnabled", true)

    fun proximityVolumePercent(prefs: SharedPreferences): Int =
        prefs.getInt("proximityVolume", prefs.getInt("alertVolume", 85)).coerceIn(0, 100)

    fun proximityProfile(prefs: SharedPreferences): ProximityAudioPolicy.Profile {
        val broadFeet = prefs.getInt("alertFeet", DEFAULT_BROAD_ALERT_FEET).coerceIn(100, 20_000)
        val startFeet = prefs.getInt("proximityStartFeet", DEFAULT_PROXIMITY_START_FEET)
        val visualFeet = prefs.getInt("proximityVisualFeet", DEFAULT_VISUAL_RANGE_FEET)
        val fallbackFeet = prefs.getInt("proximityFovFallbackFeet", DEFAULT_FOV_FALLBACK_FEET)
        return profileFromFeet(broadFeet, startFeet, visualFeet, fallbackFeet)
    }

    fun profileFromFeet(
        broadFeet: Int,
        proximityStartFeet: Int,
        visualFeet: Int,
        fovFallbackFeet: Int
    ): ProximityAudioPolicy.Profile = ProximityAudioPolicy.Profile(
        broadAlertMeters = broadFeet / Geo.FEET_PER_METER,
        proximityStartMeters = proximityStartFeet / Geo.FEET_PER_METER,
        visualRangeMeters = visualFeet / Geo.FEET_PER_METER,
        fovFallbackMeters = fovFallbackFeet / Geo.FEET_PER_METER
    ).normalized()

    fun vibrationEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean("vibrationEnabled", true)

    fun vibrationPatternName(prefs: SharedPreferences): String =
        prefs.getString("vibrationPattern", "double") ?: "double"

    fun vibrationPattern(prefs: SharedPreferences): LongArray = when (vibrationPatternName(prefs)) {
        "short" -> longArrayOf(0, 160)
        "triple" -> longArrayOf(0, 100, 70, 120, 70, 160)
        "long" -> longArrayOf(0, 420)
        else -> longArrayOf(0, 140, 90, 190)
    }
}