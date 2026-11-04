package com.bggg.watchman.routing

import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.data.RouteAnalysis
import com.bggg.watchman.data.RoutePreference
import com.bggg.watchman.data.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteScorerTest {
    private fun route(index: Int, meters: Double, seconds: Double) = Route(
        points = listOf(GeoPoint(40.0, -75.0), GeoPoint(40.0, -74.99)),
        steps = emptyList(),
        distanceMeters = meters,
        durationSeconds = seconds,
        destination = SearchResult("Destination", GeoPoint(40.0, -74.99)),
        alternativeIndex = index
    )

    @Test fun routePreferencesSelectExpectedAlternative() {
        val analyses = listOf(
            RouteAnalysis(route(0, 12000.0, 900.0), 8, 3.0),
            RouteAnalysis(route(1, 9000.0, 1100.0), 4, 5.0),
            RouteAnalysis(route(2, 11000.0, 1000.0), 1, 4.0),
            RouteAnalysis(route(3, 12500.0, 1050.0), 5, 12.0)
        )
        assertEquals(0, RouteScorer.select(RoutePreference.FASTEST, analyses).route.alternativeIndex)
        assertEquals(1, RouteScorer.select(RoutePreference.SHORTEST, analyses).route.alternativeIndex)
        assertEquals(2, RouteScorer.select(RoutePreference.FEWEST_CAMERAS, analyses).route.alternativeIndex)
        assertEquals(0, RouteScorer.select(RoutePreference.MOST_CAMERAS, analyses).route.alternativeIndex)
        assertEquals(3, RouteScorer.select(RoutePreference.SCENIC, analyses).route.alternativeIndex)
    }

    @Test fun validatedTrafficDeviceRemainsCountedInRouting() {
        val signal = CameraPoi(
            id = "node/1",
            type = CameraType.TRAFFIC,
            point = GeoPoint(40.0, -74.995),
            name = null,
            road = null,
            direction = null,
            operator = null,
            manufacturer = null,
            tags = mapOf("highway" to "traffic_signals")
        )
        val camera = signal.copy(
            id = "node/2",
            type = CameraType.ALPR,
            tags = mapOf("surveillance:type" to "ALPR")
        )
        assertTrue(RouteAnalyzer.isCameraOrSensorForRouting(signal))
        assertTrue(RouteAnalyzer.isCameraOrSensorForRouting(camera))
        assertEquals(2, RouteAnalyzer.countKnownCameras(route(0, 1000.0, 100.0), listOf(signal, camera)))
    }
}
