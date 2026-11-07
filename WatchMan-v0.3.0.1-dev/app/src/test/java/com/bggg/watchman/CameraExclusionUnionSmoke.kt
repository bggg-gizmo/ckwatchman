package com.bggg.watchman

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.routing.CameraExclusionUnion
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import java.util.Random

fun cameraExclusionUnionSmoke() {
    fun circle(x: Double, y: Double, radius: Double = 0.002) = (0..16).map { i ->
        val angle = 2.0 * Math.PI * (i % 16) / 16
        GeoPoint(y + radius * sin(angle), x + radius * cos(angle))
    }
    // Independent ray-crossing oracle; fixtures have no relationship to real geography.
    fun inside(point: GeoPoint, ring: List<GeoPoint>): Boolean {
        var result = false
        for (i in 0 until ring.lastIndex) {
            val a = ring[i]; val b = ring[i + 1]
            if ((a.lat > point.lat) != (b.lat > point.lat) && point.lon <
                (b.lon - a.lon) * (point.lat - a.lat) / (b.lat - a.lat) + a.lon) result = !result
        }
        return result
    }
    fun compare(original: List<List<GeoPoint>>, merged: List<List<GeoPoint>>) {
        check(merged.all { it.first() == it.last() && it.size >= 4 })
        for (x in -25..25) for (y in -25..25) {
            val point = GeoPoint(y * 0.00047 + 0.000012345, x * 0.00047 + 0.000023456)
            check(original.any { inside(point, it) } == merged.any { inside(point, it) }) {
                "Union changed an independent sampled exclusion membership"
            }
        }
    }
    fun perimeter(rings: List<List<GeoPoint>>) = rings.sumOf { ring -> ring.zipWithNext().sumOf {
        hypot(it.second.lon - it.first.lon, it.second.lat - it.first.lat)
    } }
    val one = circle(0.0, 0.0)
    check(CameraExclusionUnion.merge(listOf(one, one, one)).size == 1)
    val overlap = listOf(one, circle(0.002, 0.0))
    val union = CameraExclusionUnion.merge(overlap)
    check(union.size == 1 && perimeter(union) < perimeter(overlap))
    compare(overlap, union)
    val disjoint = listOf(circle(-0.004, 0.0), circle(0.004, 0.0))
    check(CameraExclusionUnion.merge(disjoint).size == 2)
    compare(disjoint, CameraExclusionUnion.merge(disjoint))
    val contained = listOf(one, circle(0.0, 0.0, 0.0007))
    check(CameraExclusionUnion.merge(contained).size == 1)
    compare(contained, CameraExclusionUnion.merge(contained))
    val chain = (0..12).map { circle(it * 0.001 - 0.006, sin(it.toDouble()) * 0.0005) }
    val chainUnion = CameraExclusionUnion.merge(chain)
    check(chainUnion.size == 1 && perimeter(chainUnion) < perimeter(chain) / 2)
    compare(chain, chainUnion)
    val withHole = (0..7).map { i -> circle(0.003 * cos(i * Math.PI / 4), 0.003 * sin(i * Math.PI / 4), 0.0013) }
    val holeResult = CameraExclusionUnion.merge(withHole)
    check(holeResult.size == withHole.size && holeResult.none { inside(GeoPoint(0.0, 0.0), it) })
    compare(withHole, holeResult)
    fun square(x: Double, y: Double) = listOf(GeoPoint(y, x), GeoPoint(y, x + 0.002),
        GeoPoint(y + 0.002, x + 0.002), GeoPoint(y + 0.002, x), GeoPoint(y, x))
    val touching = listOf(square(0.0, 0.0), square(0.002, 0.002))
    compare(touching, CameraExclusionUnion.merge(touching))
    val adjacent = listOf(square(0.0, 0.0), square(0.002, 0.0))
    compare(adjacent, CameraExclusionUnion.merge(adjacent))
    val random = Random(91)
    repeat(30) {
        val sample = (0..7).map { circle(random.nextDouble() * 0.014 - 0.007, random.nextDouble() * 0.014 - 0.007) }
        compare(sample, CameraExclusionUnion.merge(sample))
    }
    println("Camera exclusion union: overlap/containment/disjoint/chain, hole and touching fallback, 30 independent membership grids PASS")
}
