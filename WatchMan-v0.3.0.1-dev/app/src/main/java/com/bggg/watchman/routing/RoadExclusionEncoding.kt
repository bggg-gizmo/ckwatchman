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
        require(roads.size<=NoCamsObstaclePlanner.MAX_ROAD_BLOCKS && roads.all {
            it.lat.isFinite() && it.lat in -90.0..90.0 && it.lon.isFinite() && it.lon in -180.0..180.0 })
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
        // Original device areas remain useful when a merged outline exceeds its budget.
        // Small local boxes can represent multiple nearby road blockers, for example the
        // approaches to one junction. They may conservatively block extra roads; they
        // never clear exposure or replace final whole-candidate validation. No wide hull.
        val localBoxes = if(roads.size>maximumLocations) localRoadBoxes(roads,cancelled) else emptyList()
        val choices = (rings + additionalRings + localBoxes).distinct()
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
            if(localBoxes.isNotEmpty() && uncovered.size<=maximumLocations) break
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

    /** At most384 proposals from128 actual blocked-road locations. Each box is at most
     *50m across, with an interior margin; seam/polar geometry is refused, never wrapped
     * into a world-spanning region. The selector still verifies coverage for every point.
     */
    private fun localRoadBoxes(roads: List<GeoPoint>, cancelled: () -> Boolean): List<List<GeoPoint>> {
        val boxes=LinkedHashSet<List<GeoPoint>>()
        val latitudeMargin=Math.toDegrees(1.5/6_371_000.0)
        for(center in roads.distinct().sortedWith(compareBy<GeoPoint> { it.lat }.thenBy { it.lon })) {
            check(!cancelled()) { "Road exclusion encoding cancelled or deadline reached" }
            if(kotlin.math.abs(center.lat)>=85.0) continue
            for(radius in listOf(10.0,15.0,20.0)) {
                val group=roads.filter {
                    check(!cancelled()) { "Road exclusion encoding cancelled or deadline reached" }
                    Geo.distanceMeters(center,it)<=radius
                }
                if(group.size<2 || group.any { kotlin.math.abs(it.lat)>=85.0 }) continue
                val longitudeMargin=latitudeMargin/kotlin.math.cos(Math.toRadians(center.lat))
                val south=group.minOf { it.lat }-latitudeMargin
                val north=group.maxOf { it.lat }+latitudeMargin
                val west=group.minOf { it.lon }-longitudeMargin
                val east=group.maxOf { it.lon }+longitudeMargin
                if(south<=-85.0 || north>=85.0 || west<=-180.0 || east>=180.0 || east-west>180.0) continue
                val southwest=GeoPoint(south,west)
                val northeast=GeoPoint(north,east)
                if(Geo.distanceMeters(southwest,northeast)>50.0) continue
                boxes+=listOf(southwest,GeoPoint(south,east),northeast,GeoPoint(north,west),southwest)
            }
        }
        return boxes.toList()
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
