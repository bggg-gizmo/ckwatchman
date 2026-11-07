package com.bggg.watchman.routing

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.util.Geo

/** Represent EVERY accumulated road blocker within both backend budgets. A blocker can be
 * omitted from exclude_locations only when safely inside a retained exclusion polygon.
 * This is constraint compression, not truncation or relaxation of the camera encounter test.
 */
object RoadExclusionEncoding {
    data class Encoded(val polygons: List<List<GeoPoint>>, val roadPoints: List<GeoPoint>)

    fun encode(rings: List<List<GeoPoint>>, roads: List<GeoPoint>,
        perimeterBudgetMeters: Double = 9_000.0, maximumLocations: Int = 50,
        maximumPolygonVertices: Int = 100, additionalRings: List<List<GeoPoint>> = emptyList(),
        cancelled: () -> Boolean = { false }): Encoded {
        check(!cancelled()) { "Road exclusion encoding cancelled or deadline reached" }
        require(perimeterBudgetMeters > 0 && maximumLocations > 0 && maximumPolygonVertices >= 4)
        fun perimeter(ring: List<GeoPoint>) = ring.zipWithNext().sumOf { Geo.distanceMeters(it.first, it.second) }
        if (rings.sumOf(::perimeter) <= perimeterBudgetMeters && rings.sumOf { it.size } <= maximumPolygonVertices) {
            // A small polygon payload does not establish that it covers every road blocker.
            // Inset projections or independently supplied blockers may lie outside all rings.
            val uncovered = roads.filter { point ->
                check(!cancelled()) { "Road exclusion encoding cancelled or deadline reached" }
                rings.none { containsInterior(it, point) }
            }
            if (uncovered.size <= maximumLocations) return Encoded(rings, uncovered)
        }
        check(roads.isNotEmpty()) { "Backend polygon perimeter budget exceeded; encountered-road exclusions unavailable" }
        // Original convex rings remain useful when a complete merged outline has too many
        // vertices. Every candidate is an actual exclusion region, never a convex hull.
        val choices = (rings + additionalRings).distinct()
        val lengths = choices.map(::perimeter)
        val uncovered = roads.indices.toMutableSet()
        val candidates = choices.indices.toMutableSet()
        val selected = ArrayList<List<GeoPoint>>()
        val coverage = choices.map { ring ->
            check(!cancelled()) { "Road exclusion encoding cancelled or deadline reached" }
            roads.indices.filter { containsInterior(ring, roads[it]) }.toSet()
        }
        var remaining = perimeterBudgetMeters
        var remainingVertices = maximumPolygonVertices
        while (candidates.isNotEmpty()) {
            check(!cancelled()) { "Road exclusion encoding cancelled or deadline reached" }
            val best = candidates.filter { lengths[it] <= remaining && lengths[it] > 0.0 && choices[it].size <= remainingVertices }
                .map { index -> index to coverage[index].count { it in uncovered } }
                .filter { it.second > 0 }
                .maxWithOrNull(compareBy<Pair<Int, Int>> {
                    it.second / (lengths[it.first] / perimeterBudgetMeters + choices[it.first].size.toDouble() / maximumPolygonVertices)
                }.thenBy { -it.first }) ?: break
            candidates.remove(best.first)
            selected += choices[best.first]
            remaining -= lengths[best.first]
            remainingVertices -= choices[best.first].size
            uncovered.removeAll(coverage[best.first])
        }
        check(uncovered.size <= maximumLocations) {
            "Road backend cannot represent all accumulated exclusions within its polygon vertex/perimeter/location budgets"
        }
        return Encoded(selected, roads.filterIndexed { index, _ -> index in uncovered })
    }

    /** The half-metre interior margin avoids relying on boundary-rounding or snapping ties. */
    internal fun containsInterior(ring: List<GeoPoint>, point: GeoPoint): Boolean {
        var inside = false
        for (i in 0 until ring.lastIndex) {
            val a = ring[i]; val b = ring[i + 1]
            if (Geo.pointToSegmentMeters(point, a, b) <= 0.5) return false
            if ((a.lat > point.lat) != (b.lat > point.lat) && point.lon <
                (b.lon - a.lon) * (point.lat - a.lat) / (b.lat - a.lat) + a.lon) inside = !inside
        }
        return inside
    }
}
