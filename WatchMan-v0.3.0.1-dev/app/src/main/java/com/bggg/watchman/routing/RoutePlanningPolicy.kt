package com.bggg.watchman.routing

import com.bggg.watchman.data.RoutePreference
import com.bggg.watchman.data.CameraPoi

/** Deterministic wall-clock and literal camera-mode acceptance rules. */
object RoutePlanningPolicy {
    const val SIMPLE_ROUTE_TARGET_MS = 10_000L
    const val CAMERA_ROUTE_TARGET_MS = 20_000L
    const val HARD_DEADLINE_MS = 25_000L
    const val CAMERA_COVERAGE_MAX_WAIT_MS = 8_000L
    const val SHAPED_ROUTE_RESERVE_MS = 10_000L
    const val MIN_CAMERA_COVERAGE_WAIT_MS = 1_000L
    const val MAX_SHAPED_REQUESTS = 3
    const val EXPOSURE_PENDING = -1

    /** Non-camera objectives must not execute even cached device work before publication. */
    fun cameraSnapshotBeforePublication(preference: RoutePreference, load: () -> List<CameraPoi>): List<CameraPoi> =
        if (isCameraSensitive(preference)) load() else emptyList()

    fun exposureLabel(count: Int): String {
        require(count >= EXPOSURE_PENDING)
        return if (count == EXPOSURE_PENDING) "Camera/sensor counts pending"
            else "$count known cam" + if (count == 1) "" else "s"
    }

    fun isCameraSensitive(preference: RoutePreference): Boolean =
        preference == RoutePreference.FEWEST_CAMERAS || preference == RoutePreference.MOST_CAMERAS

    fun remainingMillis(startedAtMillis: Long, nowMillis: Long): Long =
        (HARD_DEADLINE_MS - (nowMillis - startedAtMillis).coerceAtLeast(0L)).coerceAtLeast(0L)

    fun cameraCoverageWaitMillis(
        preference: RoutePreference,
        startedAtMillis: Long,
        nowMillis: Long
    ): Long {
        if (!isCameraSensitive(preference)) return 0L
        val available = (remainingMillis(startedAtMillis, nowMillis) - SHAPED_ROUTE_RESERVE_MS)
            .coerceAtLeast(0L)
            .coerceAtMost(CAMERA_COVERAGE_MAX_WAIT_MS)
        return available.takeIf { it >= MIN_CAMERA_COVERAGE_WAIT_MS } ?: 0L
    }

    fun acceptsShapedCameraCandidate(
        preference: RoutePreference,
        baselineCounts: Collection<Int>,
        candidateCount: Int
    ): Boolean {
        if (baselineCounts.isEmpty()) return false
        return when (preference) {
            RoutePreference.FEWEST_CAMERAS -> candidateCount < baselineCounts.min()
            RoutePreference.MOST_CAMERAS -> candidateCount > baselineCounts.max()
            else -> true
        }
    }

    fun cameraFallbackMessage(
        preference: RoutePreference,
        shapingDeviceCount: Int,
        shapedCandidateCount: Int,
        acceptedCandidateCount: Int
    ): String? {
        if (!isCameraSensitive(preference)) return null
        if (shapingDeviceCount == 0) {
            return "No known camera/sensor coverage was available after bounded route-corridor acquisition; showing the best valid road route from retained data."
        }
        if (shapedCandidateCount == 0) {
            return "No valid shaped road alternative returned before the planning deadline; showing the best cached-exposure route."
        }
        if (acceptedCandidateCount == 0) {
            return "No shaped alternative measurably improved camera/sensor exposure; showing the best cached-exposure route."
        }
        return null
    }
}
