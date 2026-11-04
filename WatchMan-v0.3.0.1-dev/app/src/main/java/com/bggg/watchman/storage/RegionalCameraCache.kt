package com.bggg.watchman.storage

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import com.bggg.watchman.cameras.CameraClassifier
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.routing.RouteCorridor
import com.bggg.watchman.util.Geo
import org.json.JSONObject
import kotlin.math.cos

/**
 * Separate export-only cache for broad regional route coverage.
 * It never reads, deletes, or replaces the v0.1.8 live/startup camera database.
 */
class RegionalCameraCache(context: Context) : SQLiteOpenHelper(context, "watchman_export_region.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE regional_cameras (
              cache_key TEXT PRIMARY KEY,
              tile_id TEXT NOT NULL,
              id TEXT NOT NULL,
              type TEXT NOT NULL,
              lat REAL NOT NULL,
              lon REAL NOT NULL,
              name TEXT,
              road TEXT,
              direction TEXT,
              operator TEXT,
              manufacturer TEXT,
              source_name TEXT,
              source_id TEXT NOT NULL,
              source_url TEXT,
              source_record_id TEXT,
              confidence INTEGER NOT NULL DEFAULT 70,
              tags_json TEXT,
              last_updated INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_regional_tile ON regional_cameras(tile_id)")
        db.execSQL("CREATE INDEX idx_regional_lat_lon ON regional_cameras(lat, lon)")
        db.execSQL("CREATE INDEX idx_regional_source_record ON regional_cameras(source_id, source_record_id)")
        RoutingCacheIndex.create(db, "regional_cameras")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) RoutingCacheIndex.create(db, "regional_cameras")
    }

    @Synchronized
    fun replaceSnapshot(snapshotId: String, cameras: List<CameraPoi>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val routing = RoutingCacheIndex.beforeWrite(db)
            db.delete("regional_cameras", "tile_id=?", arrayOf(snapshotId))
            insertAll(db, snapshotId, cameras)
            RoutingCacheIndex.afterWrite(db, "regional_cameras", routing)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun withinRoutes(routes: List<Route>, radiusMeters: Double): List<CameraPoi> {
        val matches = LinkedHashMap<String, CameraPoi>()
        RouteCorridor.windows(routes, radiusMeters).forEach { window ->
            queryBox(window.south, window.west, window.north, window.east).forEach { camera ->
                if (window.contains(camera.point, radiusMeters)) {
                    val identity = camera.sourceId + "|" + (camera.sourceRecordId ?: camera.id)
                    matches.putIfAbsent(identity, camera)
                }
            }
        }
        return deduplicate(matches.values.toList())
    }

    fun withinRegion(region: DeviceSearchRegion, maximumRows: Int = 50000,
        cancelled: () -> Boolean = { false }): List<CameraPoi> =
        region.collect(maximumRows, cancelled) { window, remaining ->
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Search-region device I/O is forbidden on the main thread" }
            queryBox(window.south, window.west, window.north, window.east, remaining, cancelled)
        }

    private fun queryBox(south: Double, west: Double, north: Double, east: Double,
        maximumRows: Int? = null, cancelled: () -> Boolean = { false }): List<CameraPoi> {
        check(!cancelled()) { "Regional device query cancelled" }
        require(maximumRows == null || maximumRows in 0..500000)
        val c = readableDatabase.rawQuery(
            "SELECT id,type,lat,lon,name,road,direction,operator,manufacturer,source_name,source_id,source_url,source_record_id,confidence,tags_json,last_updated " +
                "FROM regional_cameras WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?" +
                if (maximumRows == null) "" else " LIMIT ?",
            arrayOf(south.toString(), north.toString(), west.toString(), east.toString()) +
                (maximumRows?.let { arrayOf((it + 1).toString()) } ?: emptyArray())
        )
        val candidates = ArrayList<CameraPoi>()
        c.use {
            while (it.moveToNext()) {
                check(!cancelled()) { "Regional device query cancelled" }
                check(maximumRows == null || candidates.size < maximumRows) { "Regional device row budget exceeded; partial data refused" }
                candidates += readCamera(it, maximumRows != null)
            }
        }
        check(!cancelled()) { "Regional device query cancelled" }
        return candidates
    }

    private fun insertAll(db: SQLiteDatabase, snapshotId: String, cameras: List<CameraPoi>) {
        if (cameras.isEmpty()) return
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO regional_cameras(cache_key,tile_id,id,type,lat,lon,name,road,direction,operator,manufacturer,source_name,source_id,source_url,source_record_id,confidence,tags_json,last_updated) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        )
        cameras.forEach { camera ->
            stmt.clearBindings()
            stmt.bindString(1, "$snapshotId|${camera.sourceRecordId ?: camera.id}")
            stmt.bindString(2, snapshotId)
            stmt.bindString(3, camera.id)
            stmt.bindString(4, camera.type.name)
            stmt.bindDouble(5, camera.point.lat)
            stmt.bindDouble(6, camera.point.lon)
            bindNullable(stmt, 7, camera.name)
            bindNullable(stmt, 8, camera.road)
            bindNullable(stmt, 9, camera.direction)
            bindNullable(stmt, 10, camera.operator)
            bindNullable(stmt, 11, camera.manufacturer)
            bindNullable(stmt, 12, camera.source)
            stmt.bindString(13, camera.sourceId.ifBlank { snapshotId })
            bindNullable(stmt, 14, camera.sourceUrl)
            bindNullable(stmt, 15, camera.sourceRecordId)
            stmt.bindLong(16, camera.confidence.toLong())
            bindNullable(stmt, 17, if (camera.tags.isEmpty()) null else JSONObject(camera.tags as Map<*, *>).toString())
            stmt.bindLong(18, camera.lastUpdatedMillis)
            stmt.executeInsert()
            RoutingCacheIndex.put(db, "$snapshotId|${camera.sourceRecordId ?: camera.id}", camera.copy(sourceId=camera.sourceId.ifBlank { snapshotId }))
        }
    }

    private fun bindNullable(stmt: SQLiteStatement, index: Int, value: String?) {
        if (value == null) stmt.bindNull(index) else stmt.bindString(index, value)
    }

    private fun readCamera(c: Cursor, strict: Boolean): CameraPoi = CameraPoi(
        id=c.getString(0), type=readType(c,strict), point=GeoPoint(c.getDouble(2),c.getDouble(3)),
        name=c.getString(4), road=c.getString(5), direction=c.getString(6), operator=c.getString(7), manufacturer=c.getString(8),
        source=c.getString(9) ?: "Unknown source", sourceId=c.getString(10) ?: "unknown", sourceUrl=c.getString(11),
        sourceRecordId=c.getString(12), confidence=readConfidence(c,strict), tags=parseTags(c.getString(14),strict=strict), lastUpdatedMillis=c.getLong(15))
    fun prepareRoutingIndex(maximumRows: Int = 500, cancelled: () -> Boolean = { false }): RoutingCacheIndex.Progress {
        RoutingCacheIndex.requireWorker(cancelled)
        return RoutingCacheIndex.prepare(writableDatabase, "regional_cameras", maximumRows, cancelled)
    }
    fun freezeRoutingEpoch(cancelled: () -> Boolean = { false }): RoutingCacheIndex.Epoch {
        RoutingCacheIndex.requireWorker(cancelled)
        return RoutingCacheIndex.freeze(readableDatabase, "regional_cameras", cancelled)
    }
    fun routingObservations(epoch: RoutingCacheIndex.Epoch, keys: Set<String>, maximumRows: Int = 5000,
        maximumMetadataCharacters: Long = 8000000, cancelled: () -> Boolean = { false }): List<CameraPoi> {
        RoutingCacheIndex.requireWorker(cancelled)
        check(epoch.database === readableDatabase && epoch.table == "regional_cameras")
        return RoutingCacheIndex.rows(epoch, keys, null, maximumRows, maximumMetadataCharacters, cancelled) { readCamera(it,true) }
    }
    fun routingWindow(epoch: RoutingCacheIndex.Epoch, window: DeviceSearchRegion.Window, maximumRows: Int = 5000,
        maximumMetadataCharacters: Long = 8000000, cancelled: () -> Boolean = { false }): List<CameraPoi> {
        RoutingCacheIndex.requireWorker(cancelled)
        check(epoch.database === readableDatabase && epoch.table == "regional_cameras")
        return RoutingCacheIndex.rows(epoch, null, window, maximumRows, maximumMetadataCharacters, cancelled) { readCamera(it,true) }
    }

    /** Only bounded full-region queries require exact stored evidence. Legacy map/corridor
     * reads deliberately keep their existing fallback/coercion behavior.
     */
    private fun readType(cursor: Cursor, strict: Boolean): CameraType {
        if (!strict) return runCatching { CameraType.valueOf(cursor.getString(1)) }.getOrDefault(CameraType.OTHER)
        check(cursor.getType(1) == Cursor.FIELD_TYPE_STRING) { "Search-region device type is not stored text" }
        return try { CameraType.valueOf(cursor.getString(1)) }
        catch (failure: IllegalArgumentException) {
            throw IllegalStateException("Search-region device type is unknown; complete evidence unavailable", failure)
        }
    }

    private fun readConfidence(cursor: Cursor, strict: Boolean): Int {
        if (!strict) return cursor.getInt(13).coerceIn(0, 100)
        check(cursor.getType(13) == Cursor.FIELD_TYPE_INTEGER) { "Search-region device confidence is not stored INTEGER" }
        val stored = cursor.getLong(13)
        check(stored in 0L..100L) { "Search-region device confidence is outside0..100" }
        return stored.toInt()
    }

    private fun parseTags(raw: String?, strict: Boolean = false): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            check(!strict || raw.length <= 1024 * 1024) { "Device metadata exceeds row budget" }
            val obj = JSONObject(raw)
            check(!strict || obj.length() <= 4096) { "Device metadata key budget exceeded" }
            val out = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = if (strict) obj.get(key) as? String ?: error("Device metadata is not a source string") else obj.optString(key)
            }
            out
        } catch (failure: Exception) {
            if (strict) throw IllegalStateException("Regional search-region device metadata unavailable", failure)
            emptyMap()
        }
    }

    private fun deduplicate(items: List<CameraPoi>): List<CameraPoi> {
        val out = LinkedHashMap<String, CameraPoi>()
        items.forEach { camera ->
            val osm = (camera.sourceUrl ?: "").contains("openstreetmap.org") ||
                camera.id.startsWith("node/") || camera.id.startsWith("way/") || camera.id.startsWith("relation/")
            val key = if (osm && !camera.sourceRecordId.isNullOrBlank()) {
                "osm:${camera.sourceRecordId}"
            } else {
                "source:${camera.sourceId}:${camera.sourceRecordId ?: camera.id}"
            }
            val old = out[key]
            if (old == null) {
                out[key] = camera
            } else {
                val preferred = if (CameraClassifier.priority(camera.type) > CameraClassifier.priority(old.type)) camera else old
                val other = if (preferred === camera) old else camera
                out[key] = preferred.copy(
                    name = preferred.name ?: other.name,
                    road = preferred.road ?: other.road,
                    direction = preferred.direction ?: other.direction,
                    operator = preferred.operator ?: other.operator,
                    manufacturer = preferred.manufacturer ?: other.manufacturer,
                    confidence = maxOf(preferred.confidence, other.confidence),
                    tags = LinkedHashMap<String, String>().apply { putAll(other.tags); putAll(preferred.tags) }
                )
            }
        }
        return out.values.toList()
    }
}
