package com.bggg.watchman

import com.bggg.watchman.routing.RoadSnapGeometry as G
import com.bggg.watchman.routing.RoadSnapGeometry.Point
import kotlin.math.*

fun roadSnapGeometrySmoke() {
    fun near(actual: Double, expected: Double, tolerance: Double=1e-7) = check(abs(actual-expected)<=tolerance) { "$actual != $expected" }
    fun projection(q:Point,a:Point,b:Point)=checkNotNull(G.project(q,a,b).projection)
    val middle=projection(Point(1.0,1.0),Point(0.0,0.0),Point(2.0,0.0))
    near(middle.fraction,0.5); near(middle.point.latitude,0.0); near(middle.point.longitude,1.0)
    near(middle.distanceMetres,Math.PI/180*G.EARTH_METRES,1e-6)
    check(projection(Point(-1.0,0.0),Point(0.0,0.0),Point(2.0,0.0)).fraction==0.0)
    check(projection(Point(3.0,0.0),Point(0.0,0.0),Point(2.0,0.0)).fraction==1.0)
    val seam=projection(Point(180.0,1.0),Point(179.0,0.0),Point(-179.0,0.0))
    near(seam.fraction,0.5); near(abs(seam.point.longitude),180.0)
    val polar=projection(Point(0.0,90.0),Point(-45.0,60.0),Point(45.0,60.0))
    near(polar.fraction,0.5); check(polar.point.latitude>67.0)
    val same=projection(Point(1.0,1.0),Point(0.0,0.0),Point(0.0,0.0))
    check(same.degenerate && same.fraction==0.0 && same.segmentMetres==0.0)
    check(G.project(Point(1.0,1.0),Point(0.0,0.0),Point(180.0,0.0)).let { it.projection==null && it.problem!=null })
    val short=projection(Point(0.00000005,0.0),Point(0.0,0.0),Point(0.0000001,0.0))
    near(short.fraction,0.5); check(!short.degenerate)
    fun contained(p:Point,boxes:List<G.Box>)=boxes.any {
        p.longitude*1e7>=it.minLonE7 && p.longitude*1e7<=it.maxLonE7 && p.latitude*1e7>=it.minLatE7 && p.latitude*1e7<=it.maxLatE7
    }
    check(G.queryBoxes(Point(179.99,0.0),5000.0).size==2)
    check(G.queryBoxes(Point(0.0,89.99),5000.0).single().minLonE7 == -1_800_000_000)
    check(contained(Point(-180.0,0.0),G.queryBoxes(Point(180.0,0.0),0.0)))
    val random=java.util.Random(719331)
    fun nextPoint()=Point(random.nextDouble()*360-180,random.nextDouble()*178-89)
    // Independent normalized-chord candidates approximate every minor arc. Exact
    // projection must be no farther away than any sampled point, in either direction.
    fun vector(p:Point):DoubleArray {
        val lon=Math.toRadians(p.longitude); val lat=Math.toRadians(p.latitude)
        return doubleArrayOf(cos(lat)*cos(lon),cos(lat)*sin(lon),sin(lat))
    }
    repeat(400) {
        val a=nextPoint();val b=nextPoint();val q=nextPoint()
        val result=projection(q,a,b);val reverse=projection(q,b,a)
        near(result.distanceMetres,reverse.distanceMetres,1e-5)
        near(result.fraction,1-reverse.fraction,1e-8)
        val av=vector(a);val bv=vector(b);val qv=vector(q)
        for (step in 0..1000) {
            val t=step/1000.0
            val p=DoubleArray(3){av[it]*(1-t)+bv[it]*t}
            val norm=sqrt(p.sumOf{it*it})
            val dot=(p.indices.sumOf{p[it]*qv[it]}/norm).coerceIn(-1.0,1.0)
            val sampled=acos(dot)*G.EARTH_METRES
            check(result.distanceMetres<=sampled+1e-4)
        }
        // Independent destination-bearing construction supplies points on a query cap.
        val radius=random.nextDouble()*100_000
        val boxes=G.queryBoxes(q,radius)
        val lat=Math.toRadians(q.latitude);val lon=Math.toRadians(q.longitude)
        repeat(32) {
            val theta=random.nextDouble()*2*PI
            val arc=random.nextDouble()*radius/G.EARTH_METRES
            val targetLat=asin(sin(lat)*cos(arc)+cos(lat)*sin(arc)*cos(theta))
            val targetLon=lon+atan2(sin(theta)*sin(arc)*cos(lat),cos(arc)-sin(lat)*sin(targetLat))
            val normalized=((Math.toDegrees(targetLon)+540)%360)-180
            check(contained(Point(normalized,Math.toDegrees(targetLat)),boxes))
        }
    }
    for (bad in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0)) check(runCatching { G.queryBoxes(Point(0.0,0.0),bad) }.isFailure)
    check(runCatching { Point(181.0,0.0) }.isFailure)
    check(runCatching { Point(0.0,Double.NaN) }.isFailure)
    println("Road snap geometry: exact minor-arc projection, endpoint/reverse fractions, zero length/antipodal refusal, polar/dateline caps,400400 independent sampled competitors and12800 cap points PASS; not legal endpoint acceptance")
}
