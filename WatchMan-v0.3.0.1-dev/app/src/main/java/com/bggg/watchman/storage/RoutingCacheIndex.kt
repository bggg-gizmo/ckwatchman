package com.bggg.watchman.storage

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import java.util.Collections
import java.util.UUID

/** Reusable persisted identity/revision groundwork, NOT a global snapshot or ready router.
 * Migration creates empty index tables only; original observations remain untouched. Explicit
 * worker batches index legacy rows. Every original/sidecar mutation invalidates an epoch, even
 * direct SQL writes; rollback rolls back its revision too. Readers own no cursor or transaction
 * beyond one bounded call and never block acquisition for a whole route.
 */
object RoutingCacheIndex {
    private const val STATE = "routing_cache_state"
    private const val KEYS = "routing_cache_keys"
    private const val COLUMNS = "id,type,lat,lon,name,road,direction,operator,manufacturer,source_name,source_id,source_url,source_record_id,confidence,tags_json,last_updated"
    data class Progress(val processed: Int, val ready: Boolean, val indexedRows: Long)
    internal data class WriteState(val ready: Boolean, val checkpoint: String)
    private data class State(val generation: String, val revision: Long, val ready: Boolean,
        val checkpoint: String, val sourceRows: Long, val indexedRows: Long)
    class Epoch internal constructor(internal val database: SQLiteDatabase, internal val table: String,
        internal val thread: Thread, val generation: String, val revision: Long, val rows: Long) {
        val identity: String get() = "routing-cache-index/1:$generation:$revision:$rows"
    }
    private fun table(name: String) = name.also {
        require(it in setOf("cameras", "national_cameras", "regional_cameras"))
    }
    private fun worker(cancelled: () -> Boolean) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Routing index/read work is forbidden on main thread" }
        check(!cancelled()) { "Routing cache operation cancelled" }
    }
    internal fun requireWorker(cancelled: () -> Boolean = { false }) = worker(cancelled)
    /** No row scan, identity backfill, original-table alteration or large index construction. */
    fun create(db: SQLiteDatabase, sourceTable: String) {
        val source = table(sourceTable)
        db.execSQL("CREATE TABLE IF NOT EXISTS $STATE(singleton INTEGER PRIMARY KEY CHECK(singleton=1),generation TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>=0),ready INTEGER NOT NULL CHECK(ready IN(0,1)),checkpoint TEXT NOT NULL,source_rows INTEGER NOT NULL,indexed_rows INTEGER NOT NULL CHECK(indexed_rows>=0))")
        db.execSQL("INSERT OR IGNORE INTO $STATE VALUES(1,?,0,0,'',-1,0)", arrayOf(UUID.randomUUID().toString()))
        db.execSQL("CREATE TABLE IF NOT EXISTS $KEYS(cache_key TEXT PRIMARY KEY,logical_key TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_routing_logical ON $KEYS(logical_key)")
        fun revision() = "SELECT CASE WHEN (SELECT revision FROM $STATE WHERE singleton=1)>=9223372036854775807 THEN RAISE(ABORT,'routing revision exhausted') END; UPDATE $STATE SET revision=revision+1,ready=0,checkpoint='',source_rows=-1 WHERE singleton=1;"
        for (event in listOf("INSERT", "UPDATE", "DELETE")) {
            val keys = when(event) {
                "INSERT" -> "DELETE FROM $KEYS WHERE cache_key=NEW.cache_key;"
                "UPDATE" -> "DELETE FROM $KEYS WHERE cache_key=OLD.cache_key OR cache_key=NEW.cache_key;"
                else -> "DELETE FROM $KEYS WHERE cache_key=OLD.cache_key;"
            }
            db.execSQL("CREATE TRIGGER IF NOT EXISTS routing_source_${event.lowercase()} AFTER $event ON $source BEGIN $keys ${revision()} END")
        }
        for (event in listOf("INSERT", "UPDATE", "DELETE")) {
            val delta = when(event) { "INSERT" -> "+1"; "DELETE" -> "-1"; else -> "" }
            db.execSQL("CREATE TRIGGER IF NOT EXISTS routing_key_${event.lowercase()} AFTER $event ON $KEYS BEGIN ${revision()} UPDATE $STATE SET indexed_rows=indexed_rows$delta WHERE singleton=1; END")
        }
    }
    private fun state(db: SQLiteDatabase): State = db.rawQuery(
        "SELECT generation,revision,ready,checkpoint,source_rows,indexed_rows FROM $STATE WHERE singleton=1", emptyArray()).use {
        check(it.moveToFirst() && it.count == 1)
        check(it.getType(0) == Cursor.FIELD_TYPE_STRING && it.getType(3) == Cursor.FIELD_TYPE_STRING)
        for (index in listOf(1,2,4,5)) check(it.getType(index) == Cursor.FIELD_TYPE_INTEGER)
        val generation = it.getString(0); check(runCatching { UUID.fromString(generation) }.isSuccess)
        val revision = it.getLong(1); val ready = it.getLong(2); val source = it.getLong(4); val indexed = it.getLong(5)
        check(revision >= 0 && ready in 0..1 && source >= -1 && indexed >= 0)
        State(generation, revision, ready == 1L, it.getString(3), source, indexed)
    }
    internal fun beforeWrite(db: SQLiteDatabase): WriteState {
        check(db.inTransaction()) { "Indexed source writes must share the existing atomic transaction" }
        val state = state(db)
        return WriteState(state.ready, state.checkpoint)
    }
    private fun scalar(db: SQLiteDatabase, sql: String) = db.rawQuery(sql, emptyArray()).use {
        check(it.moveToFirst()); it.getLong(0)
    }
    internal fun put(db: SQLiteDatabase, cacheKey: String, observation: CameraPoi) {
        check(db.inTransaction()); require(cacheKey.isNotEmpty())
        val identity = DeviceIdentity.logicalKey(observation)
        require(identity.isNotBlank() && identity.length <= 4096)
        db.delete(KEYS, "cache_key=?", arrayOf(cacheKey))
        db.execSQL("INSERT INTO $KEYS(cache_key,logical_key) VALUES(?,?)", arrayOf(cacheKey, identity))
    }
    internal fun afterWrite(db: SQLiteDatabase, sourceTable: String, previous: WriteState) {
        check(db.inTransaction())
        if (previous.ready) publishReady(db, table(sourceTable))
        else db.execSQL("UPDATE $STATE SET ready=0,checkpoint=? WHERE singleton=1", arrayOf(previous.checkpoint))
    }
    private fun publishReady(db: SQLiteDatabase, source: String) {
        val count = scalar(db, "SELECT COUNT(*) FROM $source")
        val keys = scalar(db, "SELECT COUNT(*) FROM $KEYS")
        val current = state(db)
        check(keys == count && keys == current.indexedRows) { "Routing index/source inventory differs" }
        db.execSQL("UPDATE $STATE SET ready=1,checkpoint='',source_rows=? WHERE singleton=1", arrayOf(count))
    }
    /** Explicit bounded/resumable WORKER migration. Completeness follows ordered source-key
     * scanning; concurrent raw writes reset its checkpoint, normal writes maintain exact keys.
     * Cancellation/failure rolls back the entire batch. No acquisition completion is changed.
     */
    fun prepare(db: SQLiteDatabase, sourceTable: String, maximumRows: Int = 500,
        cancelled: () -> Boolean = { false }): Progress {
        worker(cancelled); val source = table(sourceTable); require(maximumRows in 1..2000)
        db.beginTransaction()
        try {
            val previous = state(db)
            if (previous.ready) {
                check(previous.sourceRows == previous.indexedRows)
                db.setTransactionSuccessful(); return Progress(0, true, previous.indexedRows)
            }
            val rows = ArrayList<Pair<String, String>>()
            var more = false
            db.rawQuery("SELECT cache_key,id,source_id,source_url,source_record_id FROM $source WHERE cache_key>? ORDER BY cache_key LIMIT ?",
                arrayOf(previous.checkpoint, (maximumRows+1).toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    worker(cancelled)
                    if (rows.size == maximumRows) { more = true; break }
                    for (index in 0..4) check(cursor.isNull(index) || cursor.getType(index) == Cursor.FIELD_TYPE_STRING)
                    val cacheKey = cursor.getString(0); check(cacheKey.isNotEmpty())
                    val identity = DeviceIdentity.logicalKey(CameraPoi(cursor.getString(1), CameraType.OTHER, GeoPoint(0.0,0.0),null,null,null,null,null,
                        sourceId = cursor.getString(2), sourceUrl = cursor.getString(3), sourceRecordId = cursor.getString(4)))
                    require(identity.isNotBlank() && identity.length <= 4096)
                    rows += cacheKey to identity
                }
            }
            for ((key, logical) in rows) {
                worker(cancelled); db.delete(KEYS,"cache_key=?", arrayOf(key))
                db.execSQL("INSERT INTO $KEYS VALUES(?,?)", arrayOf(key,logical))
            }
            worker(cancelled)
            if (more) db.execSQL("UPDATE $STATE SET checkpoint=? WHERE singleton=1", arrayOf(rows.last().first))
            else publishReady(db, source)
            val final = state(db)
            db.setTransactionSuccessful(); return Progress(rows.size, final.ready, final.indexedRows)
        } finally { db.endTransaction() }
    }
    fun freeze(db: SQLiteDatabase, sourceTable: String, cancelled: () -> Boolean = { false }): Epoch {
        worker(cancelled); table(sourceTable)
        val value = state(db)
        check(value.ready && value.sourceRows == value.indexedRows) { "Routing key index is incomplete; no partial snapshot may be used" }
        worker(cancelled)
        return Epoch(db, sourceTable, Thread.currentThread(), value.generation, value.revision, value.sourceRows)
            .also { requireCurrent(it, cancelled) }
    }
    fun requireCurrent(epoch: Epoch, cancelled: () -> Boolean = { false }) {
        worker(cancelled); check(Thread.currentThread() === epoch.thread && epoch.database.isOpen)
        val now = state(epoch.database)
        check(now.ready && now.generation == epoch.generation && now.revision == epoch.revision &&
            now.sourceRows == epoch.rows && now.indexedRows == epoch.rows) { "Routing cache epoch changed or index is incomplete" }
        worker(cancelled)
    }
    fun rows(epoch: Epoch, logicalKeys: Set<String>?, window: DeviceSearchRegion.Window?,
        maximumRows: Int, maximumMetadataCharacters: Long, cancelled: () -> Boolean,
        decode: (Cursor) -> CameraPoi): List<CameraPoi> {
        require((logicalKeys == null) != (window == null))
        require(maximumRows in 1..500000 && maximumMetadataCharacters > 0)
        requireCurrent(epoch,cancelled)
        val args: Array<String>
        val where: String
        if (logicalKeys != null) {
            require(logicalKeys.size in 1..400 && logicalKeys.all { it.isNotBlank() && it.length <= 4096 })
            where = "k.logical_key IN (${logicalKeys.joinToString(",") { "?" }})"
            args = logicalKeys.sorted().toTypedArray()
        } else {
            val bounds = checkNotNull(window)
            where = "o.lat BETWEEN ? AND ? AND o.lon BETWEEN ? AND ?"
            args = arrayOf(bounds.south.toString(),bounds.north.toString(),bounds.west.toString(),bounds.east.toString())
        }
        val columns = COLUMNS.split(',').joinToString(",") { "o.$it" }
        val output = ArrayList<CameraPoi>(); var characters = 0L
        epoch.database.rawQuery("SELECT $columns,o.cache_key,k.logical_key FROM ${table(epoch.table)} o JOIN $KEYS k ON k.cache_key=o.cache_key WHERE $where ORDER BY o.cache_key LIMIT ?",
            args + (maximumRows+1).toString()).use { cursor ->
            while (cursor.moveToNext()) {
                worker(cancelled)
                check(output.size < maximumRows) { "Routing observation budget exceeded; partial rows refused" }
                for (column in 0 until cursor.columnCount) if (cursor.getType(column) == Cursor.FIELD_TYPE_STRING) {
                    characters = Math.addExact(characters,cursor.getString(column).length.toLong())
                    check(characters <= maximumMetadataCharacters) { "Routing observation metadata budget exceeded" }
                }
                for (column in listOf(2,3)) check(cursor.getType(column) in setOf(Cursor.FIELD_TYPE_INTEGER,Cursor.FIELD_TYPE_FLOAT))
                val decoded = decode(cursor)
                check(decoded.point.lat.isFinite() && decoded.point.lat in -90.0..90.0 && decoded.point.lon.isFinite() && decoded.point.lon in -180.0..180.0)
                check(DeviceIdentity.logicalKey(decoded) == cursor.getString(17)) { "Stored routing identity disagrees with original observation" }
                check(window == null || window.contains(decoded.point))
                output += decoded.copy(tags=Collections.unmodifiableMap(java.util.TreeMap(decoded.tags)))
            }
        }
        requireCurrent(epoch,cancelled)
        return Collections.unmodifiableList(output)
    }
}
