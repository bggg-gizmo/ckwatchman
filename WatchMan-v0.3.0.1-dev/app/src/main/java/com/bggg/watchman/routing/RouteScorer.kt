package com.bggg.watchman.routing

import com.bggg.watchman.data.RouteAnalysis
import com.bggg.watchman.data.RoutePreference

object RouteScorer {
    const val SCENIC_MAX_DURATION_RATIO = 1.75
    const val SCENIC_MAX_DISTANCE_RATIO = 1.85

    fun select(preference: RoutePreference, analyses: List<RouteAnalysis>): RouteAnalysis {
        require(analyses.isNotEmpty()) { "No route alternatives" }
        return when (preference) {
            RoutePreference.FASTEST -> analyses.minWith(compareBy<RouteAnalysis> { it.route.durationSeconds }.thenBy { it.route.distanceMeters })
            RoutePreference.SHORTEST -> analyses.minWith(compareBy<RouteAnalysis> { it.route.distanceMeters }.thenBy { it.route.durationSeconds })
            RoutePreference.FEWEST_CAMERAS -> analyses.minWith(compareBy<RouteAnalysis> { it.knownCameraCount }.thenBy { it.route.durationSeconds })
            RoutePreference.MOST_CAMERAS -> analyses.maxWith(compareBy<RouteAnalysis> { it.knownCameraCount }.thenByDescending { it.route.durationSeconds })
            RoutePreference.SCENIC -> {
                val fastest = analyses.minOf { it.route.durationSeconds }.coerceAtLeast(1.0)
                val shortest = analyses.minOf { it.route.distanceMeters }.coerceAtLeast(1.0)
                val reasonable = analyses.filter { analysis ->
                    analysis.route.durationSeconds <= fastest * SCENIC_MAX_DURATION_RATIO &&
                        analysis.route.distanceMeters <= shortest * SCENIC_MAX_DISTANCE_RATIO
                }
                (reasonable.ifEmpty { analyses }).maxWith(
                    compareBy<RouteAnalysis> { it.route.distanceMeters }
                        .thenBy { -it.route.durationSeconds }
                )
            }
        }
    }
}
