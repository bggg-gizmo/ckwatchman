package com.bggg.watchman

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.storage.*
import java.io.File
import java.util.UUID

/** Actual three-cache SQL migrations/read epochs on new test-owned database files ONLY.
 * No app camera rows/data sets are changed or scanned. Root owns execution/dispatcher wiring.
 */
object AndroidRoutingCacheIndexCheck {
    private class Isolated(base: Context, val directory: File) : ContextWrapper(base) {
        override fun getDatabasePath(name: String) = File(directory,name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            openOrCreateDatabase(name,mode,factory,null)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openDatabase(getDatabasePath(name).absolutePath,factory,SQLiteDatabase.CREATE_IF_NECESSARY,errorHandler)
    }
    private class Cache(val file: File, val table: String, val oldVersion: Int, val db: SQLiteDatabase,
        val write: (List<CameraPoi>) -> Unit, val close: () -> Unit,
        val prepare: (Int,()->Boolean) -> RoutingCacheIndex.Progress,
        val freeze: (()->Boolean) -> RoutingCacheIndex.Epoch,
        val keys: (RoutingCacheIndex.Epoch,Set<String>,Int,Long,()->Boolean)->List<CameraPoi>,
        val window: (RoutingCacheIndex.Epoch,DeviceSearchRegion.Window,Int,Long,()->Boolean)->List<CameraPoi>)
    fun run(instrumentation: Instrumentation): String {
        val token = "routing-index-" + UUID.randomUUID().toString()
        val folder = File(instrumentation.targetContext.cacheDir,token)
        check(folder.mkdir())
        val context = Isolated(instrumentation.targetContext,folder)
        val open = ArrayList<Cache>()
        fun create(kind: Int): Cache = when(kind) {
            0 -> CameraDatabase(context).let { cache -> Cache(context.getDatabasePath("watchman.db"),"cameras",5,cache.writableDatabase,
                { cache.replaceSourceSnapshot("fixture",it) }, { cache.close() },
                { size,cancel -> cache.prepareRoutingIndex(size,cancel) }, { cancel -> cache.freezeRoutingEpoch(cancel) },
                { epoch,keys,max,chars,cancel -> cache.routingObservations(epoch,keys,max,chars,cancel) },
                { epoch,box,max,chars,cancel -> cache.routingWindow(epoch,box,max,chars,cancel) }) }
            1 -> NationalCameraCache(context).let { cache -> Cache(context.getDatabasePath("watchman_national.db"),"national_cameras",5,cache.writableDatabase,
                { cache.replaceSeedShard("fixture",it) }, { cache.close() },
                { size,cancel -> cache.prepareRoutingIndex(size,cancel) }, { cancel -> cache.freezeRoutingEpoch(cancel) },
                { epoch,keys,max,chars,cancel -> cache.routingObservations(epoch,keys,max,chars,cancel) },
                { epoch,box,max,chars,cancel -> cache.routingWindow(epoch,box,max,chars,cancel) }) }
            else -> RegionalCameraCache(context).let { cache -> Cache(context.getDatabasePath("watchman_export_region.db"),"regional_cameras",1,cache.writableDatabase,
                { cache.replaceSnapshot("fixture",it) }, { cache.close() },
                { size,cancel -> cache.prepareRoutingIndex(size,cancel) }, { cancel -> cache.freezeRoutingEpoch(cancel) },
                { epoch,keys,max,chars,cancel -> cache.routingObservations(epoch,keys,max,chars,cancel) },
                { epoch,box,max,chars,cancel -> cache.routingWindow(epoch,box,max,chars,cancel) }) }
        }
        fun reject(action:()->Unit) = check(runCatching(action).isFailure)
        fun scalar(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql,emptyArray()).use { check(it.moveToFirst());it.getLong(0) }
        val collected = ArrayList<List<CameraPoi>>()
        var assertions = 0
        try {
            for (kind in 0..2) {
                var cache = create(kind); open += cache
                val fixtures = (0 until 301).map { index -> CameraPoi("fixture-$kind-$index",CameraType.TRAFFIC,
                    GeoPoint(-87.5,175.0),null,null,null,null,null,sourceId="source-$kind",sourceRecordId="record-$index",
                    tags=mapOf("highway" to "traffic_signals", "fixture" to token),lastUpdatedMillis=1000) }.toMutableList()
                fixtures[0] = fixtures[0].copy(sourceRecordId="shared-raven",sourceUrl="https://OPENSTREETMAP.ORG/node/fixture",
                    type=when(kind) { 0 -> CameraType.ACOUSTIC;1 -> CameraType.TRAFFIC;else -> CameraType.FLOCK },
                    point=GeoPoint(-87.5,if(kind==0)160.0 else 175.0),manufacturer="Flock Safety",
                    tags=if(kind==0) mapOf("model" to "Flock Raven","manufacturer" to "Flock Safety","fixture" to token)
                        else mapOf("manufacturer" to "Flock Safety","highway" to "traffic_signals","fixture" to token))
                fixtures[1] = fixtures[1].copy(id="node/fixture-blank",sourceRecordId="\u2003")
                cache.write(fixtures)
                check(scalar(cache.db,"SELECT COUNT(*) FROM ${cache.table}") == 301L)
                // Recreate a genuine previous-version database, preserving every source row.
                cache.close(); open.remove(cache)
                SQLiteDatabase.openDatabase(cache.file.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use { old ->
                    val names = ArrayList<String>()
                    old.rawQuery("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'routing_%'",emptyArray()).use {
                        while(it.moveToNext()) names += it.getString(0)
                    }
                    names.forEach { old.execSQL("DROP TRIGGER $it") }
                    old.execSQL("DROP TABLE routing_cache_keys");old.execSQL("DROP TABLE routing_cache_state")
                    old.version = cache.oldVersion
                }
                cache = create(kind);open += cache
                check(scalar(cache.db,"SELECT COUNT(*) FROM ${cache.table}") == 301L)
                check(scalar(cache.db,"SELECT COUNT(*) FROM routing_cache_keys") == 0L)
                reject { cache.freeze { false } }
                val first = cache.prepare(17) { false }
                check(first.processed == 17 && !first.ready && first.indexedRows == 17L)
                reject { cache.freeze { false } }
                val beforeCancel = scalar(cache.db,"SELECT revision FROM routing_cache_state")
                var cancellationReads=0
                reject { cache.prepare(17) { ++cancellationReads >= 8 } }
                check(scalar(cache.db,"SELECT revision FROM routing_cache_state") == beforeCancel)
                check(scalar(cache.db,"SELECT COUNT(*) FROM routing_cache_keys") == 17L)
                var batch = first
                var batches = 0
                while(!batch.ready) { batch=cache.prepare(17) { false };check(batch.processed<=17);check(++batches<30) }
                val epoch = cache.freeze { false }
                check(epoch.rows == 301L)
                check(cache.prepare(17) { false }.let { it.ready && it.processed==0 })
                val shared = cache.keys(epoch,setOf("osm:shared-raven"),10,100000) { false }
                check(shared.single() == fixtures[0].copy(sourceId=if(kind==0)"fixture" else fixtures[0].sourceId))
                check(shared.single().tags["fixture"] == token)
                val blank = fixtures[1].copy(sourceId=if(kind==0)"fixture" else fixtures[1].sourceId)
                check(cache.keys(epoch,setOf(DeviceIdentity.logicalKey(blank)),10,100000) { false }.single() == blank)
                val box = DeviceSearchRegion.Window(-88.0,170.0,-87.0,180.0)
                check(cache.window(epoch,box,500,1000000) { false }.size == if(kind==0)300 else 301)
                // Identity lookup deliberately retrieves stronger metadata OUTSIDE the box.
                if(kind==0) check(shared.single().point.lon < box.west)
                collected += shared
                reject { cache.window(epoch,box,1,1000000) { false } }
                reject { cache.keys(epoch,setOf("osm:shared-raven"),10,1) { false } }
                reject { cache.keys(epoch,setOf("osm:shared-raven"),10,100000) { true } }
                var mainRejected=false
                instrumentation.runOnMainSync { mainRejected=runCatching { cache.freeze { false } }.isFailure }
                check(mainRejected)
                var threadRejected=false
                val foreign=Thread { threadRejected=runCatching { RoutingCacheIndex.requireCurrent(epoch) }.isFailure }
                foreign.start();foreign.join();check(threadRejected)
                // Rollback restores revision AND readiness/key state; no phantom invalidation.
                cache.db.beginTransaction()
                try { cache.db.execSQL("UPDATE ${cache.table} SET confidence=50 WHERE id=?",arrayOf(fixtures[0].id)) }
                finally { cache.db.endTransaction() }
                RoutingCacheIndex.requireCurrent(epoch)
                check(cache.keys(epoch,setOf("osm:shared-raven"),10,100000) { false }.single().confidence == fixtures[0].confidence)
                // Normal atomic replacement maintains keys; old epoch still refuses publication.
                cache.write(fixtures)
                reject { RoutingCacheIndex.requireCurrent(epoch) }
                val replacement=cache.freeze { false };check(replacement.revision>epoch.revision && replacement.rows==301L)
                // Direct SQL mutation cannot bypass invalidation, even on otherwise valid rows.
                cache.db.execSQL("UPDATE ${cache.table} SET confidence=50 WHERE id=?",arrayOf(fixtures[0].id))
                reject { RoutingCacheIndex.requireCurrent(replacement) };reject { cache.freeze { false } }
                while(!cache.prepare(100) { false }.ready) { }
                val changed=cache.freeze { false }
                check(cache.keys(changed,setOf("osm:shared-raven"),10,100000) { false }.single().confidence==50)
                // Strict decoder still refuses bad original metadata after identity reindex.
                cache.db.execSQL("UPDATE ${cache.table} SET type='CORRUPT_ENUM' WHERE id=?",arrayOf(fixtures[0].id))
                while(!cache.prepare(100) { false }.ready) { }
                reject { cache.keys(cache.freeze { false },setOf("osm:shared-raven"),10,100000) { false } }
                cache.write(fixtures)
                val closed=cache.freeze { false }
                cache.close();open.remove(cache)
                reject { RoutingCacheIndex.requireCurrent(closed) }
                assertions += 25
            }
            val canonical = AuthoritativeDeviceRepository.merge(*collected.toTypedArray()).single()
            check(canonical.type==CameraType.ACOUSTIC && canonical.tags["model"]=="Flock Raven")
            check(canonical.tags["watchman:merged_sources"]?.split(',')?.toSet()==setOf("fixture","source-1","source-2"))
            return "PASS three real cache migrations/resumable identity indexes/frozen strict epochs; >=${assertions+3}checks; no app data changed"
        } finally {
            open.forEach { runCatching { it.close() } }
            // Only this newly-created UUID-owned scratch directory; never user's camera stores.
            check(folder.name==token && folder.parentFile.canonicalFile==instrumentation.targetContext.cacheDir.canonicalFile)
            folder.walkBottomUp().forEach { check(it.delete()) { "Could not remove test-owned routing-index scratch file" } }
        }
    }
}
