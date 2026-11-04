package com.bggg.watchman.alerts

import kotlin.math.roundToLong

/** Pure deterministic proximity policy shared by foreground and background navigation. */
object ProximityAudioPolicy {
    const val TONE_FREQUENCY_HZ = 880.0
    const val SLOW_INTERVAL_MS = 1_800L
    const val VISUAL_INTERVAL_MS = 320L
    const val MIN_PULSE_INTERVAL_MS = 140L
    const val PULSE_DURATION_MS = 110

    enum class Stage {
        OUTSIDE,
        CLOSE,
        APPROACHING,
        VISUAL_RANGE,
        FOV,
        PASSED
    }

    enum class FovBasis {
        NONE,
        CONFIRMED_METADATA,
        DIRECTION_APPROXIMATION,
        DISTANCE_FALLBACK
    }

    data class Profile(
        val broadAlertMeters: Double,
        val proximityStartMeters: Double,
        val visualRangeMeters: Double,
        val fovFallbackMeters: Double,
        val directionOnlyHalfAngleDegrees: Double = 55.0
    ) {
        fun normalized(): Profile {
            val broad = broadAlertMeters.takeIf { it.isFinite() }?.coerceAtLeast(1.0) ?: 1.0
            val start = proximityStartMeters.takeIf { it.isFinite() }?.coerceIn(1.0, broad) ?: broad
            val visual = visualRangeMeters.takeIf { it.isFinite() }?.coerceIn(1.0, start) ?: start
            val fov = fovFallbackMeters.takeIf { it.isFinite() }?.coerceIn(1.0, visual) ?: visual
            val halfAngle = directionOnlyHalfAngleDegrees.takeIf { it.isFinite() }
                ?.coerceIn(10.0, 90.0) ?: 55.0
            return Profile(broad, start, visual, fov, halfAngle)
        }

        fun isOrdered(): Boolean = fovFallbackMeters <= visualRangeMeters &&
            visualRangeMeters <= proximityStartMeters &&
            proximityStartMeters <= broadAlertMeters &&
            fovFallbackMeters > 0.0
    }

    data class FovDecision(
        val active: Boolean,
        val basis: FovBasis,
        val supported: Boolean
    )

    data class Observation(
        val deviceId: String,
        val distanceMeters: Double,
        val profile: Profile,
        val priority: Int,
        val fovDecision: FovDecision = FovDecision(false, FovBasis.NONE, false),
        val passed: Boolean = false
    )

    data class Selection(
        val deviceId: String,
        val distanceMeters: Double,
        val stage: Stage,
        val pulseIntervalMs: Long,
        val pulseDurationMs: Int,
        val continuous: Boolean,
        val fovBasis: FovBasis,
        val devicesInRange: Int
    )

    fun stage(
        distanceMeters: Double,
        profileInput: Profile,
        fovActive: Boolean = false,
        passed: Boolean = false
    ): Stage {
        if (passed) return Stage.PASSED
        val profile = profileInput.normalized()
        if (!distanceMeters.isFinite() || distanceMeters < 0.0 || distanceMeters > profile.proximityStartMeters) {
            return Stage.OUTSIDE
        }
        if (fovActive) return Stage.FOV
        if (distanceMeters <= profile.visualRangeMeters) return Stage.VISUAL_RANGE
        val approachBoundary = (profile.proximityStartMeters + profile.visualRangeMeters) / 2.0
        return if (distanceMeters > approachBoundary) Stage.CLOSE else Stage.APPROACHING
    }

    /** Monotonic cadence: decreasing distance can never increase the pulse interval. */
    fun pulseIntervalMs(distanceMeters: Double, profileInput: Profile): Long {
        val profile = profileInput.normalized()
        if (!distanceMeters.isFinite() || distanceMeters > profile.proximityStartMeters) return 0L
        if (distanceMeters <= profile.fovFallbackMeters) return MIN_PULSE_INTERVAL_MS
        val interval = if (distanceMeters >= profile.visualRangeMeters) {
            interpolate(
                distanceMeters,
                profile.visualRangeMeters,
                profile.proximityStartMeters,
                VISUAL_INTERVAL_MS.toDouble(),
                SLOW_INTERVAL_MS.toDouble()
            )
        } else {
            interpolate(
                distanceMeters,
                profile.fovFallbackMeters,
                profile.visualRangeMeters,
                MIN_PULSE_INTERVAL_MS.toDouble(),
                VISUAL_INTERVAL_MS.toDouble()
            )
        }
        return interval.roundToLong().coerceIn(MIN_PULSE_INTERVAL_MS, SLOW_INTERVAL_MS)
    }

    /**
     * Prefer supported optical metadata. Direction-only data is explicitly an approximation.
     * If no usable direction exists, the configured close-range threshold is an explicit fallback.
     */
    fun fovDecision(
        distanceMeters: Double,
        profileInput: Profile,
        cameraDirectionDegrees: Double?,
        horizontalFovDegrees: Double?,
        bearingFromDeviceToUserDegrees: Double?,
        travelBearingDegrees: Double?
    ): FovDecision {
        val profile = profileInput.normalized()
        val direction = normalizeDegrees(cameraDirectionDegrees)
        val fov = horizontalFovDegrees?.takeIf { it.isFinite() && it in 5.0..180.0 }
        val deviceToUser = normalizeDegrees(bearingFromDeviceToUserDegrees)
        if (direction != null && fov != null && deviceToUser != null) {
            val inside = angleDelta(direction, deviceToUser) <= fov / 2.0
            return FovDecision(
                active = inside && distanceMeters <= profile.visualRangeMeters,
                basis = FovBasis.CONFIRMED_METADATA,
                supported = true
            )
        }
        if (direction != null && deviceToUser != null) {
            val aligned = angleDelta(direction, deviceToUser) <= profile.directionOnlyHalfAngleDegrees
            return FovDecision(
                active = aligned && distanceMeters <= profile.fovFallbackMeters,
                basis = FovBasis.DIRECTION_APPROXIMATION,
                supported = true
            )
        }
        return FovDecision(
            active = distanceMeters <= profile.fovFallbackMeters,
            basis = FovBasis.DISTANCE_FALLBACK,
            supported = false
        )
    }

    /** Nearest relevant device controls audio; ties use taxonomy priority then stable ID. */
    fun select(observations: Collection<Observation>): Selection? {
        val inRange = observations.mapNotNull { observation ->
            val stage = stage(
                observation.distanceMeters,
                observation.profile,
                observation.fovDecision.active,
                observation.passed
            )
            if (stage == Stage.OUTSIDE || stage == Stage.PASSED) null else observation to stage
        }
        val selected = inRange.minWithOrNull(
            compareBy<Pair<Observation, Stage>> { it.first.distanceMeters }
                .thenByDescending { it.first.priority }
                .thenBy { it.first.deviceId }
        ) ?: return null
        val observation = selected.first
        val stage = selected.second
        return Selection(
            deviceId = observation.deviceId,
            distanceMeters = observation.distanceMeters,
            stage = stage,
            pulseIntervalMs = if (stage == Stage.FOV) 0L else pulseIntervalMs(observation.distanceMeters, observation.profile),
            pulseDurationMs = PULSE_DURATION_MS,
            continuous = stage == Stage.FOV,
            fovBasis = observation.fovDecision.basis,
            devicesInRange = inRange.size
        )
    }

    private fun interpolate(value: Double, low: Double, high: Double, atLow: Double, atHigh: Double): Double {
        if (high <= low) return atLow
        val fraction = ((value - low) / (high - low)).coerceIn(0.0, 1.0)
        return atLow + (atHigh - atLow) * fraction
    }

    private fun normalizeDegrees(value: Double?): Double? = value
        ?.takeIf { it.isFinite() }
        ?.let { ((it % 360.0) + 360.0) % 360.0 }

    private fun angleDelta(a: Double, b: Double): Double {
        var delta = kotlin.math.abs(a - b) % 360.0
        if (delta > 180.0) delta = 360.0 - delta
        return delta
    }
}