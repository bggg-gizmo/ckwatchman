package com.bggg.watchman.routing

import kotlin.math.*

/** Spherical geometry only. A close segment is not a legal/connected endpoint or permission. */
object RoadSnapGeometry {
    const val EARTH_METRES = 6_371_008.8
    private const val E7 = 10_000_000.0
    data class Point(val longitude: Double, val latitude: Double) {
        init { require(longitude.isFinite() && latitude.isFinite() && longitude in -180.0..180.0 && latitude in -90.0..90.0) }
    }
    data class Box(val minLonE7: Int, val maxLonE7: Int, val minLatE7: Int, val maxLatE7: Int)
    data class Projection(val point: Point, val fraction: Double, val distanceMetres: Double,
        val segmentMetres: Double, val degenerate: Boolean)
    data class Result(val projection: Projection?, val problem: String?)
    private data class Vec(val x: Double, val y: Double, val z: Double) {
        fun dot(b: Vec) = x*b.x+y*b.y+z*b.z
        fun cross(b: Vec) = Vec(y*b.z-z*b.y, z*b.x-x*b.z, x*b.y-y*b.x)
        fun norm() = sqrt(dot(this))
        operator fun times(k: Double) = Vec(x*k,y*k,z*k)
        operator fun plus(b: Vec) = Vec(x+b.x,y+b.y,z+b.z)
    }
    private fun vector(p: Point): Vec {
        val lon = Math.toRadians(p.longitude); val lat = Math.toRadians(p.latitude)
        return Vec(cos(lat)*cos(lon),cos(lat)*sin(lon),sin(lat))
    }
    private fun angle(a: Vec,b: Vec) = atan2(a.cross(b).norm(),a.dot(b))
    private fun point(v: Vec) = Point(Math.toDegrees(atan2(v.y,v.x)),Math.toDegrees(atan2(v.z,hypot(v.x,v.y))))
    fun distance(a: Point,b: Point) = angle(vector(a),vector(b))*EARTH_METRES

    /** Interpolation on the same original minor arc used by project(), never a new road node. */
    fun pointAt(from: Point, to: Point, fraction: Double): Point {
        require(fraction.isFinite() && fraction in 0.0..1.0)
        if (fraction == 0.0) return from
        if (fraction == 1.0) return to
        val a = vector(from); val b = vector(to); val total = angle(a, b)
        require(PI - total >= 1e-10) { "Antipodal segment cannot be interpolated reliably" }
        if (total < 1e-14) return from
        val tangent = a.cross(b).cross(a) * (1.0 / a.cross(b).norm())
        return point(a * cos(total * fraction) + tangent * sin(total * fraction))
    }

    /** Fraction is relative to original source ordering; reversed candidate arcs use 1-fraction. */
    fun project(query: Point, from: Point, to: Point): Result {
        val a=vector(from); val b=vector(to); val q=vector(query)
        val normal=a.cross(b); val total=angle(a,b)
        if (PI-total < 1e-10) return Result(null,"Antipodal/near-antipodal segment has no reliable unique minor-arc projection")
        if (total < 1e-14) return Result(Projection(from,0.0,distance(query,from),total*EARTH_METRES,true),null)
        val tangent=normal.cross(a)*(1.0/normal.norm())
        val phase=atan2(q.dot(tangent),q.dot(a))
        var best=0.0; var position=a; var separation=angle(q,a)
        val endDistance=angle(q,b)
        if (endDistance < separation) { best=total; position=b; separation=endDistance }
        if (phase > 0.0 && phase < total) {
            val projected=a*cos(phase)+tangent*sin(phase)
            val candidate=angle(q,projected)
            if (candidate < separation) { best=phase; position=projected; separation=candidate }
        }
        // At a great-circle pole every point is equally near. Deterministic earliest
        // fraction is geometry tie-breaking, never a topology merge or entrance choice.
        val chosen=when(best) { 0.0 -> from; total -> to; else -> point(position) }
        return Result(Projection(chosen,(best/total).coerceIn(0.0,1.0),separation*EARTH_METRES,total*EARTH_METRES,false),null)
    }

    /** Conservative spherical query cap, split at +/-180 so an Alaska query needn't span the world. */
    fun queryBoxes(query: Point, radiusMetres: Double): List<Box> {
        require(radiusMetres.isFinite() && radiusMetres in 0.0..PI*EARTH_METRES)
        val degrees=Math.toDegrees(radiusMetres/EARTH_METRES)
        val south=max(-900_000_000.0,floor((query.latitude-degrees)*E7)-2).toInt()
        val north=min(900_000_000.0,ceil((query.latitude+degrees)*E7)+2).toInt()
        fun box(left: Long,right: Long)=Box(left.coerceAtLeast(-1_800_000_000L).toInt(),
            right.coerceAtMost(1_800_000_000L).toInt(),south,north)
        if (south == -900_000_000 || north == 900_000_000) return listOf(box(-1_800_000_000,1_800_000_000))
        val width=degrees/cos(Math.toRadians(max(abs(south.toDouble()),abs(north.toDouble()))/E7))
        if (width >= 180.0) return listOf(box(-1_800_000_000,1_800_000_000))
        val west=floor((query.longitude-width)*E7).toLong()-2
        val east=ceil((query.longitude+width)*E7).toLong()+2
        return when {
            west < -1_800_000_000L -> listOf(box(-1_800_000_000,east),box(west+3_600_000_000L,1_800_000_000))
            east > 1_800_000_000L -> listOf(box(-1_800_000_000,east-3_600_000_000L),box(west,1_800_000_000))
            else -> listOf(box(west,east))
        }
    }
}
