package com.bggg.watchman.routing

import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import org.json.JSONObject
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Worker-owned candidate lookup bound to one exact immutable graph revision. NOT legal snapping. */
class CompactRoadSpatial private constructor(private val graph: CompactRoadGraph, private val database: SQLiteDatabase,
    private val file: File, private val bytes: Long, private val modified: Long,
    val sourceRevision: String, val revision: String) : Closeable {
    data class Candidate(val segment: CompactRoadGraph.Segment, val projection: RoadSnapGeometry.Projection,
        val sourceRevision:String) {
        val forwardArcId: Long get() = segment.id*2-1
        val backwardArcId: Long get() = segment.id*2
        /** Both geometric orientations; traversal decides whether either is legally usable. */
        fun anchors():List<RoadEndpointAnchor> = listOf(
            RoadEndpointAnchor(sourceRevision,forwardArcId,segment.from,segment.to,projection.fraction),
            RoadEndpointAnchor(sourceRevision,backwardArcId,segment.to,segment.from,1.0-projection.fraction))
    }
    private val owner=Thread.currentThread()
    private var closed=false
    private fun ready() {
        worker()
        check(Thread.currentThread() === owner) { "Spatial queries must stay on the owning worker" }
        check(!closed) { "Spatial index is closed" }
        check(file.length()==bytes && file.lastModified()==modified) { "Spatial revision changed" }
        check(graph.revision==sourceRevision) { "Spatial source graph changed" }
    }

    /** All exact-distance candidates, not just the first geometrically close or presumed legal road.
     * Exceeding the coarse index budget rejects the whole query, never silently truncates.
     */
    fun candidates(point: RoadSnapGeometry.Point, radiusMetres: Double, maximumRows: Int=8192): List<Candidate> {
        ready()
        require(maximumRows in 1..100000)
        val ids=PackedSpatialTree.query(RoadSnapGeometry.queryBoxes(point,radiusMetres),maximumRows) { id ->
            readPage(database,id)
        }.ids
        val result=ArrayList<Candidate>()
        for(id in ids) {
            val segment=checkNotNull(graph.segment(id)) { "Spatial segment absent from source graph" }
            val projected=RoadSnapGeometry.project(point,segment.fromPoint,segment.toPoint)
            val projection=checkNotNull(projected.projection) { "Unresolved source segment $id: ${projected.problem}" }
            if(projection.distanceMetres<=radiusMetres) result+=Candidate(segment,projection,sourceRevision)
        }
        ready()
        return result.sortedWith(compareBy<Candidate>{it.projection.distanceMetres}.thenBy{it.segment.id})
    }

    override fun close() {
        worker();check(Thread.currentThread() === owner)
        if(closed)return
        closed=true;database.close()
    }

    companion object {
        private fun readPage(db:SQLiteDatabase,id:Long):ByteArray? =
            db.rawQuery("SELECT length(data),data FROM segment_bounds_node WHERE nodeno=?",arrayOf(id.toString())).use {
                if(!it.moveToFirst()) null else {
                    check(it.getLong(0) in 4..65536) { "Spatial page storage bound exceeded" }
                    val bytes=it.getBlob(1)
                    check(!it.moveToNext()) { "Duplicate spatial page identity" }
                    bytes
                }
            }
        private fun worker() { check(Looper.myLooper()!=Looper.getMainLooper()) { "Spatial I/O forbidden on main thread" } }
        private fun normalized(value:Any?,depth:Int=0):Any? {
            check(depth<=32) { "Spatial metadata nesting exceeds bound" }
            return if(value is JSONObject) value.keys().asSequence().sorted().associateWith{normalized(value.get(it),depth+1)} else value
        }

        fun open(graph:CompactRoadGraph,directory:File,expectedSha256:String,maximumBytes:Long=128L*1024*1024):CompactRoadSpatial {
            worker()
            require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}")))
            require(maximumBytes in 1..1024L*1024*1024)
            val source=graph.revision
            val root=directory.canonicalFile
            val file=File(root,"spatial.sqlite").canonicalFile
            val metadata=File(root,"manifest.json").canonicalFile
            check(file.parentFile==root && metadata.parentFile==root && file.isFile && metadata.isFile) { "Invalid spatial package path" }
            val bytes=file.length();val modified=file.lastModified()
            check(bytes in 1..maximumBytes) { "Spatial storage budget exceeded" }
            // Bound reads themselves, not only a racy pre-read length observation.
            val raw=metadata.inputStream().use { input ->
                val output=ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(true) {
                    val count=input.read(buffer);if(count<0)break
                    check(output.size()+count<=2*1024*1024) { "Spatial manifest too large" }
                    output.write(buffer,0,count)
                }
                output.toString("UTF-8")
            }
            val manifest=JSONObject(raw)
            check(manifest.getString("format")=="watchman-road-spatial/1")
            check(manifest.getString("source_graph_sha256")==source) { "Spatial source revision mismatch" }
            check(manifest.getString("envelope")=="endpoint-centered-distance-cap/v1")
            check(manifest.getLong("segments")==graph.counts.getValue("segments"))
            check(!manifest.getBoolean("routing_ready") && manifest.getLong("live_setup_completion_credit")==0L)
            val record=manifest.getJSONObject("database")
            check(record.getString("file")=="spatial.sqlite" && record.getLong("bytes")==bytes)
            check(record.getString("sha256").equals(expectedSha256,true)) { "Untrusted spatial hash" }
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer=ByteArray(65536)
                while(true) { val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count) }
            }
            val actual=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
            check(actual.equals(expectedSha256,true) && file.length()==bytes && file.lastModified()==modified) { "Spatial integrity/revision failure" }
            val db=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            try {
                check(db.isReadOnly && db.version==1)
                db.execSQL("PRAGMA cache_size=-2048");db.setMaxSqlCacheSize(16)
                db.rawQuery("PRAGMA quick_check",null).use { check(it.moveToFirst() && it.getString(0)=="ok" && !it.moveToNext()) }
                // API36 test runtime lacks rtree_i32 itself. Traverse the exact audited
                // packed tree through ordinary shadow-table primary-key reads. Host
                // rtreecheck plus the independently trusted SHA still bind the full tree.
                db.rawQuery("SELECT sql FROM sqlite_master WHERE name='segment_bounds'",null).use {
                    check(it.moveToFirst() && it.getString(0).lowercase(java.util.Locale.ROOT).contains("using rtree_i32"))
                }
                db.rawQuery("SELECT COUNT(*) FROM segment_bounds_rowid",null).use { check(it.moveToFirst() && it.getLong(0)==manifest.getLong("segments")) }
                PackedSpatialTree.decode(checkNotNull(readPage(db,1L)) { "Missing spatial root" },true)
                db.rawQuery("EXPLAIN QUERY PLAN SELECT data FROM segment_bounds_node WHERE nodeno=?",arrayOf("1")).use { cursor ->
                    var indexed=false
                    while(cursor.moveToNext()) {
                        if(cursor.getString(3).uppercase(java.util.Locale.ROOT).contains("INTEGER PRIMARY KEY")) indexed=true
                    }
                    check(indexed) { "Spatial page primary-key lookup unavailable" }
                }
                manifest.remove("database")
                db.rawQuery("SELECT value FROM metadata WHERE key='manifest'",null).use {
                    check(it.moveToFirst());val embedded=it.getString(0);check(embedded.length<=2*1024*1024)
                    check(normalized(JSONObject(embedded))==normalized(manifest)) { "Spatial metadata mismatch" }
                }
                return CompactRoadSpatial(graph,db,file,bytes,modified,source,actual).also { it.ready() }
            }catch(failure:Throwable){db.close();throw failure}
        }
    }
}
