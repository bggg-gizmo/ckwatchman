package com.bggg.watchman

import com.bggg.watchman.routing.PackedSpatialTree
import com.bggg.watchman.routing.RoadSnapGeometry.Box
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

fun packedSpatialTreeSmoke() {
    fun page(depth:Int,vararg cells:Pair<Long,Box>):ByteArray {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(depth);out.writeShort(cells.size)
            for((id,b) in cells) { out.writeLong(id);out.writeInt(b.minLonE7);out.writeInt(b.maxLonE7);out.writeInt(b.minLatE7);out.writeInt(b.maxLatE7) }
            while(bytes.size()<1228) out.writeByte(0)
        }
        return bytes.toByteArray()
    }
    val west=Box(-1800000000,-100,-900000000,900000000)
    val east=Box(100,1800000000,-900000000,900000000)
    val world=Box(-1800000000,1800000000,-900000000,900000000)
    val pages=mapOf(1L to page(2,2L to west,3L to east),
        2L to page(65535,4L to west),3L to page(0,5L to east),
        4L to page(0,4294967297L to Box(-200,-100,-10,10)),
        5L to page(0,2L to Box(100,200,-10,10),3L to Box(1800000000,1800000000,0,0)))
    val visits=ArrayList<Long>()
    val first=PackedSpatialTree.query(listOf(Box(-200,-100,0,0))) { visits+=it;pages[it] }
    check(first.ids==listOf(4294967297L) && first.pagesRead==3 && visits==listOf(1L,2L,4L))
    val union=PackedSpatialTree.query(listOf(west,east)) { pages[it] }
    check(union.ids==listOf(2L,3L,4294967297L) && union.pagesRead==5)
    check(PackedSpatialTree.query(listOf(world,world)){pages[it]}==union)
    check(PackedSpatialTree.query(listOf(Box(-99,99,-10,10))){pages[it]}.pagesRead==1)
    check(PackedSpatialTree.query(listOf(world)){page(0)}.ids.isEmpty())
    fun reject(block:()->Unit) { check(runCatching(block).isFailure) { "Expected malformed/budget rejection" } }
    reject { PackedSpatialTree.query(listOf(world),1){pages[it]} }
    reject { PackedSpatialTree.query(listOf(world),100,2){pages[it]} }
    reject { PackedSpatialTree.query(listOf(world)){if(it==5L)null else pages[it]} }
    reject { PackedSpatialTree.query(listOf(world)){if(it==4L)pages[it]!!.copyOf(100) else pages[it]} }
    reject { PackedSpatialTree.query(listOf(world)){if(it==2L)page(0,1L to west) else pages[it]} }
    reject { PackedSpatialTree.query(listOf(world)){if(it==3L)page(0,4L to east) else pages[it]} }
    reject { PackedSpatialTree.query(listOf(world)){if(it==4L)page(0,22L to east) else pages[it]} }
    reject { PackedSpatialTree.query(listOf(world)){if(it==4L)page(0,2L to west) else pages[it]} }
    reject { PackedSpatialTree.query(listOf(world)){page(0,Long.MAX_VALUE to world)} }
    reject { PackedSpatialTree.query(emptyList()){pages[it]} }
    reject { PackedSpatialTree.query(listOf(Box(5,4,0,0))){pages[it]} }
    for(bad in listOf(ByteArray(3),ByteArray(65537),page(40),page(1),
        page(0,0L to world),page(0,-1L to world),page(0,1L to world,1L to world),
        page(0,1L to Box(0,0,-900000001,0)),page(0,1L to Box(2,1,0,0)),
        page(0,1L to world).also{it[2]=0;it[3]=52},page(0,1L to world).copyOf(27))) {
        reject { PackedSpatialTree.decode(bad,true) }
    }
    reject { PackedSpatialTree.decode(page(0),false) }
    // Generated random rectangles compared to independent brute-force inclusive overlap.
    val random=java.util.Random(20260918)
    val entries=(1L..500L).map { id ->
        val x=random.nextInt(3600000)-1800000;val y=random.nextInt(1800000)-900000
        id to Box(x,x+random.nextInt(20000),y,y+random.nextInt(20000))
    }
    val randomPages=HashMap<Long,ByteArray>()
    val branches=entries.chunked(25).mapIndexed { i,cells ->
        val id=i+2L
        val box=Box(cells.minOf{it.second.minLonE7},cells.maxOf{it.second.maxLonE7},
            cells.minOf{it.second.minLatE7},cells.maxOf{it.second.maxLatE7})
        randomPages[id]=page(0,*cells.toTypedArray());id to box
    }
    randomPages[1]=page(1,*branches.toTypedArray())
    repeat(1000) {
        val x=random.nextInt(4000000)-2000000;val y=random.nextInt(2000000)-1000000
        val box=Box(x,x+random.nextInt(200000),y,y+random.nextInt(200000))
        val expected=entries.filter { (_,b)->!(b.maxLonE7<box.minLonE7 || b.minLonE7>box.maxLonE7 || b.maxLatE7<box.minLatE7 || b.minLatE7>box.maxLatE7) }.map{it.first}
        check(PackedSpatialTree.query(listOf(box)){randomPages[it]}.ids==expected)
    }
    println("Packed spatial tree: literal big-endian/64-bit identities, multilevel pruning/union, malformed/cycle/containment/budget guards, 1000 independent random rectangle comparisons PASS")
    System.getenv("WATCHMAN_SPATIAL_PAGES_FIXTURE")?.let { path ->
        val fixture=File(path);check(fixture.length() in 1..64L*1024*1024)
        DataInputStream(fixture.inputStream().buffered()).use { input ->
            check(input.readInt()==0x57535031)
            val count=input.readInt();check(count in 1..100000)
            val actualPages=HashMap<Long,ByteArray>()
            repeat(count) { val id=input.readLong();val size=input.readInt();check(size in 4..65536)
                val bytes=ByteArray(size);input.readFully(bytes);check(actualPages.put(id,bytes)==null) }
            val queries=input.readInt();check(queries in 1..10000)
            var read=0;var candidates=0
            repeat(queries) {
                val boxes=List(input.readInt().also { check(it in 1..2) }) { Box(input.readInt(),input.readInt(),input.readInt(),input.readInt()) }
                val expected=List(input.readInt().also { check(it in 0..100000) }) { input.readLong() }
                val result=PackedSpatialTree.query(boxes,100000){actualPages[it]}
                check(result.ids==expected) { "Native SQLite spatial query differs at query$it" }
                read+=result.pagesRead;candidates+=result.ids.size
            }
            check(input.read()==-1)
            println("Actual SQLite packed pages: $count nodes, $queries native-index query comparisons, $candidates candidate observations, $read visited pages PASS")
        }
    }
}
