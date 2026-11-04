package com.bggg.watchman.routing

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read-only two-dimensional rtree_i32 pages, independent of Android SQLite extensions.
 * Format: https://sqlite.org/src/file/ext/rtree/rtree.c (Database Format of R-Tree Tables).
 * Call only on separately trusted, source-bound, host-audited immutable bytes. This query
 * validates visited pages, not unvisited subtrees; it is NOT a replacement for the host audit.
 */
object PackedSpatialTree {
    data class Cell(val id: Long, val bounds: RoadSnapGeometry.Box)
    data class Page(val depth: Int, val cells: List<Cell>, val bytes: Int)
    data class Result(val ids: List<Long>, val pagesRead: Int)

    private fun valid(box: RoadSnapGeometry.Box) {
        require(box.minLonE7 in -1800000000..1800000000 && box.maxLonE7 in box.minLonE7..1800000000 &&
            box.minLatE7 in -900000000..900000000 && box.maxLatE7 in box.minLatE7..900000000) { "Invalid spatial bounds" }
    }
    private fun overlaps(a: RoadSnapGeometry.Box,b: RoadSnapGeometry.Box) =
        a.minLonE7<=b.maxLonE7 && a.maxLonE7>=b.minLonE7 && a.minLatE7<=b.maxLatE7 && a.maxLatE7>=b.minLatE7
    private fun contains(a: RoadSnapGeometry.Box,b: RoadSnapGeometry.Box) =
        a.minLonE7<=b.minLonE7 && a.maxLonE7>=b.maxLonE7 && a.minLatE7<=b.minLatE7 && a.maxLatE7>=b.maxLatE7

    fun decode(bytes: ByteArray, root: Boolean): Page {
        require(bytes.size in 4..65536) { "Spatial page size out of bounds" }
        val input=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val header=input.short.toInt() and 65535
        val depth=if(root) header else 0 // Non-root first two bytes are unused, not a depth.
        require(depth<40) { "Spatial tree depth out of bounds" }
        val count=input.short.toInt() and 65535
        require(count<=51 && 4+count*24<=bytes.size) { "Invalid spatial page cell count" }
        require(count>0 || (root && depth==0)) { "Empty non-leaf spatial page" }
        val ids=HashSet<Long>()
        val cells=ArrayList<Cell>(count)
        repeat(count) {
            val id=input.long
            require(id>0 && ids.add(id)) { "Invalid/duplicate spatial cell identity" }
            val bounds=RoadSnapGeometry.Box(input.int,input.int,input.int,input.int)
            valid(bounds)
            cells+=Cell(id,bounds)
        }
        return Page(depth,cells,bytes.size)
    }

    /** Multi-box union visits each intersecting page once. Overflow rejects the whole query. */
    fun query(boxes: List<RoadSnapGeometry.Box>, maximumRows: Int=8192, maximumPages: Int=8192,
        load: (Long)->ByteArray?): Result {
        require(boxes.size in 1..2) { "One or two spatial query boxes required" }
        boxes.forEach(::valid)
        require(maximumRows in 1..100000 && maximumPages in 1..100000)
        val visited=HashSet<Long>()
        val matches=HashSet<Long>()
        var pageBytes=0
        fun visit(id: Long, level: Int?, parent: RoadSnapGeometry.Box?) {
            check(visited.add(id)) { "Spatial page cycle or multiple parents" }
            check(visited.size<=maximumPages) { "Spatial page budget exceeded; incomplete results cannot be returned" }
            val page=decode(checkNotNull(load(id)) { "Missing spatial page $id" },id==1L)
            if(id==1L) pageBytes=page.bytes else check(page.bytes==pageBytes) { "Inconsistent spatial page size" }
            val remaining=level ?: page.depth
            for(cell in page.cells) {
                check(parent==null || contains(parent,cell.bounds)) { "Spatial child escapes parent envelope" }
                if(remaining==0) check(cell.id<=Long.MAX_VALUE/2) { "Spatial segment identity overflow" }
                if(!boxes.any { overlaps(it,cell.bounds) }) continue
                if(remaining==0) {
                    check(matches.add(cell.id)) { "Duplicate spatial segment across pages" }
                    check(matches.size<=maximumRows) { "Spatial candidate budget exceeded; incomplete results cannot be returned" }
                } else {
                    check(cell.id!=1L) { "Spatial page cycle references root" }
                    visit(cell.id,remaining-1,cell.bounds)
                }
            }
        }
        visit(1L,null,null)
        return Result(matches.sorted(),visited.size)
    }
}
