package com.bggg.watchman.util

import com.bggg.watchman.data.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test fun zeroDistanceIsZero() {
        val p = GeoPoint(40.0, -75.0)
        assertEquals(0.0, Geo.distanceMeters(p, p), 0.001)
    }

    @Test fun pointToRouteSegmentUsesSegmentNotOnlyVertices() {
        val route = listOf(GeoPoint(40.0, -75.01), GeoPoint(40.0, -74.99))
        val centered = GeoPoint(40.0, -75.0)
        assertTrue(Geo.distanceToRouteMeters(centered, route) < 1.0)
        val north = GeoPoint(40.001, -75.0)
        val d = Geo.distanceToRouteMeters(north, route)
        assertTrue(d in 105.0..118.0)
    }

    @Test fun angleDeltaWrapsAcrossNorth() {
        assertEquals(20.0, Geo.angleDelta(350.0, 10.0), 0.001)
    }
}
