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
 * Source-scoped camera cache. v4 separated the cache primary key from the upstream record id;
 * v5 migrates legacy SafeTOC-oriented labels to their actual direct upstream identities without
 * discarding cached startup data.
 */
class CameraDatabase(context: Context) : SQLiteOpenHelper(context, "watchman.db", null, 6) {
    private val selectColumns = "id,type,lat,lon,name,road,direction,operator,manufacturer,source_name,source_id,source_url,source_record_id,confidence,tags_json,last_updated"

    override fun onCreate(db: SQLiteDatabase) { createV4(db); RoutingCacheIndex.create(db, "cameras") }

    private fun createV4(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE cameras (
              cache_key TEXT PRIMARY KEY,
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
        db.execSQL("CREATE INDEX idx_cameras_lat_lon ON cameras(lat, lon)")
        db.execSQL("CREATE INDEX idx_cameras_source_id ON cameras(source_id)")
        db.execSQL("CREATE INDEX idx_cameras_record ON cameras(source_id, source_record_id)")
        db.execSQL("CREATE INDEX idx_cameras_updated ON cameras(last_updated)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            db.beginTransaction()
            try {
                runCatching { db.execSQL("ALTER TABLE cameras RENAME TO cameras_legacy") }
                createV4(db)
                runCatching {
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO cameras(
                          cache_key,id,type,lat,lon,name,road,direction,operator,manufacturer,
                          source_name,source_id,source_url,source_record_id,confidence,tags_json,last_updated
                        )
                        SELECT
                          COALESCE(NULLIF(source_id,''),'legacy') || '|' || id,
                          id,type,lat,lon,name,road,direction,operator,manufacturer,
                          source_name,COALESCE(NULLIF(source_id,''),'legacy'),source_url,source_record_id,
                          COALESCE(confidence,70),tags_json,last_updated
                        FROM cameras_legacy
                        """.trimIndent()
                    )
                }
                runCatching { db.execSQL("DROP TABLE cameras_legacy") }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        if (oldVersion < 5) {
            db.beginTransaction()
            try {
                migrateSourceIdentity(
                    db,
                    "safetoc_alpr",
                    "osm_overpass_alpr",
                    "OpenStreetMap / Overpass — ALPR / Flock"
                )
                migrateSourceIdentity(
                    db,
                    "safetoc_traffic",
                    "osm_overpass_traffic_devices",
                    "OpenStreetMap / Overpass — traffic devices"
                )
                migrateSourceIdentity(
                    db,
                    "safetoc_nsw_traffic",
                    "nsw_live_traffic",
                    "Transport for NSW — Live Traffic Cameras"
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        if (oldVersion < 6) RoutingCacheIndex.create(db, "cameras")
    }

    private fun migrateSourceIdentity(db: SQLiteDatabase, oldId: String, newId: String, newName: String) {
        db.execSQL(
            """
            UPDATE cameras
               SET source_id=?,
                   source_name=?,
                   cache_key=? || '|' || COALESCE(NULLIF(source_record_id,''), id)
             WHERE source_id=?
            """.trimIndent(),
            arrayOf(newId, newName, newId, oldId)
        )
    }

    /** Atomic successful-source replacement. An empty successful result intentionally clears that source. */
    @Synchronized
    fun replaceSourceSnapshot(sourceId: String, items: List<CameraPoi>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val routing = RoutingCacheIndex.beforeWrite(db)
            db.delete("cameras", "source_id=?", arrayOf(sourceId))
            insertAll(db, items.map { if (it.sourceId == sourceId) it else it.copy(sourceId = sourceId) })
            RoutingCacheIndex.afterWrite(db, "cameras", routing)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Compatibility helper for non-snapshot callers. */
    @Synchronized
    fun upsertAll(items: List<CameraPoi>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val routing = RoutingCacheIndex.beforeWrite(db)
            insertAll(db, items)
            RoutingCacheIndex.afterWrite(db, "cameras", routing)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Add observations without replacing stronger previously known sensor evidence.
     * The source key is retained, no source snapshot is cleared, and cancellation rolls
     * back both records and routing-index writes together. Does not award setup credit.
     */
    @Synchronized
    fun upsertObservations(items: List<CameraPoi>, checkActive: () -> Unit = {}) {
        if(items.isEmpty()) return
        require(items.size<=12000)
        checkActive()
        val db=writableDatabase
        db.beginTransaction()
        try {
            val merged=items.map { incoming ->
                checkActive()
                val previous=db.rawQuery("SELECT $selectColumns FROM cameras WHERE cache_key=? LIMIT 1",
                    arrayOf(cacheKey(incoming))).use { if(it.moveToFirst()) readCamera(it,strictMetadata=true) else null }
                if(previous==null) incoming else DeviceRecordMerge.enrich(previous,incoming)
            }
            val routing=RoutingCacheIndex.beforeWrite(db)
            insertAll(db,merged,checkActive)
            RoutingCacheIndex.afterWrite(db,"cameras",routing)
            checkActive()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun insertAll(db: SQLiteDatabase, items: List<CameraPoi>, checkActive: () -> Unit = {}) {
        if (items.isEmpty()) return
        val statement = db.compileStatement(
            "INSERT OR REPLACE INTO cameras(cache_key,id,type,lat,lon,name,road,direction,operator,manufacturer,source_name,source_id,source_url,source_record_id,confidence,tags_json,last_updated) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        )
        items.forEach { p ->
            checkActive()
            statement.clearBindings()
            statement.bindString(1, cacheKey(p))
            statement.bindString(2, p.id)
            statement.bindString(3, p.type.name)
            statement.bindDouble(4, p.point.lat)
            statement.bindDouble(5, p.point.lon)
            bindNullable(statement, 6, p.name)
            bindNullable(statement, 7, p.road)
            bindNullable(statement, 8, p.direction)
            bindNullable(statement, 9, p.operator)
            bindNullable(statement, 10, p.manufacturer)
            bindNullable(statement, 11, p.source)
            statement.bindString(12, p.sourceId.ifBlank { "unknown" })
            bindNullable(statement, 13, p.sourceUrl)
            bindNullable(statement, 14, p.sourceRecordId)
            statement.bindLong(15, p.confidence.toLong())
            bindNullable(statement, 16, if (p.tags.isEmpty()) null else JSONObject(p.tags as Map<*, *>).toString())
            statement.bindLong(17, p.lastUpdatedMillis)
            statement.executeInsert()
            RoutingCacheIndex.put(db, cacheKey(p), p.copy(sourceId=p.sourceId.ifBlank { "unknown" }))
        }
    }

    private fun cacheKey(p: CameraPoi): String = "${p.sourceId.ifBlank { "unknown" }}|${p.sourceRecordId ?: p.id}"

    private fun bindNullable(statement: SQLiteStatement, index: Int, value: String?) {
        if (value == null) statement.bindNull(index) else statement.bindString(index, value)
    }

    /** Bounding-box index first, exact radial filter second, then identity-only deduplication. */
    fun nearby(center: GeoPoint, radiusMeters: Double): List<CameraPoi> {
        val latDelta = radiusMeters / 111320.0
        val lonDelta = radiusMeters / (111320.0 * cos(Math.toRadians(center.lat)).coerceAtLeast(0.15))
        return conservativeDedup(queryBounds(center.lat - latDelta, center.lon - lonDelta, center.lat + latDelta, center.lon + lonDelta))
            .map { Geo.distanceMeters(center, it.point) to it }
            .filter { it.first <= radiusMeters }
            .sortedBy { it.first }
            .map { it.second }
    }

    fun nearRoutes(routes: List<Route>, corridorMeters: Double = 180.0): List<CameraPoi> {
        val matches = LinkedHashMap<String, CameraPoi>()
        RouteCorridor.windows(routes, corridorMeters).forEach { window ->
            queryBounds(window.south, window.west, window.north, window.east).forEach { camera ->
                if (window.contains(camera.point, corridorMeters)) {
                    val identity = camera.sourceId + "|" + (camera.sourceRecordId ?: camera.id)
                    matches.putIfAbsent(identity, camera)
                }
            }
        }
        return conservativeDedup(matches.values.toList())
    }

    private fun conservativeDedup(items: List<CameraPoi>): List<CameraPoi> {
        val out = LinkedHashMap<String, CameraPoi>()
        items.forEach { p ->
            // Cross-feed merging is allowed only for a stable identical OSM identity. No spatial-only merging.
            val isOsm = (p.sourceUrl ?: "").contains("openstreetmap.org") || p.id.startsWith("node/") || p.id.startsWith("way/") || p.id.startsWith("relation/")
            val key = if (isOsm && !p.sourceRecordId.isNullOrBlank()) "osm:${p.sourceRecordId}" else "source:${p.sourceId}:${p.sourceRecordId ?: p.id}"
            val old = out[key]
            if (old == null) out[key] = p else {
                val preferred = if (CameraClassifier.priority(p.type) > CameraClassifier.priority(old.type)) p else old
                val other = if (preferred === p) old else p
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

    fun prepareRoutingIndex(maximumRows: Int = 500, cancelled: () -> Boolean = { false }): RoutingCacheIndex.Progress {
        RoutingCacheIndex.requireWorker(cancelled)
        return RoutingCacheIndex.prepare(writableDatabase, "cameras", maximumRows, cancelled)
    }
    fun freezeRoutingEpoch(cancelled: () -> Boolean = { false }): RoutingCacheIndex.Epoch {
        RoutingCacheIndex.requireWorker(cancelled)
        return RoutingCacheIndex.freeze(readableDatabase, "cameras", cancelled)
    }
    fun routingObservations(epoch: RoutingCacheIndex.Epoch, keys: Set<String>, maximumRows: Int = 5000,
        maximumMetadataCharacters: Long = 8000000, cancelled: () -> Boolean = { false }): List<CameraPoi> {
        RoutingCacheIndex.requireWorker(cancelled)
        check(epoch.database === readableDatabase && epoch.table == "cameras")
        return RoutingCacheIndex.rows(epoch, keys, null, maximumRows, maximumMetadataCharacters, cancelled) { readCamera(it,true) }
    }
    fun routingWindow(epoch: RoutingCacheIndex.Epoch, window: DeviceSearchRegion.Window, maximumRows: Int = 5000,
        maximumMetadataCharacters: Long = 8000000, cancelled: () -> Boolean = { false }): List<CameraPoi> {
        RoutingCacheIndex.requireWorker(cancelled)
        check(epoch.database === readableDatabase && epoch.table == "cameras")
        return RoutingCacheIndex.rows(epoch, null, window, maximumRows, maximumMetadataCharacters, cancelled) { readCamera(it,true) }
    }

    fun withinRegion(region: DeviceSearchRegion, maximumRows: Int = 50000,
        cancelled: () -> Boolean = { false }): List<CameraPoi> =
        region.collect(maximumRows, cancelled) { window, remaining ->
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Search-region device I/O is forbidden on the main thread" }
            queryBounds(window.south, window.west, window.north, window.east, remaining, cancelled)
        }

    private fun queryBounds(south: Double, west: Double, north: Double, east: Double,
        maximumRows: Int? = null, cancelled: () -> Boolean = { false }): List<CameraPoi> {
        check(!cancelled()) { "Live device query cancelled" }
        require(maximumRows == null || maximumRows in 0..500000)
        val cursor = readableDatabase.rawQuery(
            "SELECT $selectColumns FROM cameras WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?" +
                if (maximumRows == null) "" else " LIMIT ?",
            arrayOf(south.toString(), north.toString(), west.toString(), east.toString()) +
                (maximumRows?.let { arrayOf((it + 1).toString()) } ?: emptyArray())
        )
        val out = ArrayList<CameraPoi>()
        cursor.use { while (it.moveToNext()) {
            check(!cancelled()) { "Live device query cancelled" }
            check(maximumRows == null || out.size < maximumRows) { "Live device row budget exceeded; partial data refused" }
            out += readCamera(it, strictMetadata = maximumRows != null)
        } }
        check(!cancelled()) { "Live device query cancelled" }
        return out
    }

    private fun readCamera(cursor: Cursor, strictMetadata: Boolean = false): CameraPoi = CameraPoi(
        id = cursor.getString(0),
        type = readType(cursor, strictMetadata),
        point = GeoPoint(cursor.getDouble(2), cursor.getDouble(3)),
        name = cursor.getString(4), road = cursor.getString(5), direction = cursor.getString(6),
        operator = cursor.getString(7), manufacturer = cursor.getString(8),
        source = cursor.getString(9) ?: "Unknown source", sourceId = cursor.getString(10) ?: "unknown",
        sourceUrl = cursor.getString(11), sourceRecordId = cursor.getString(12),
        confidence = readConfidence(cursor, strictMetadata), tags = parseTags(cursor.getString(14), strictMetadata),
        lastUpdatedMillis = cursor.getLong(15)
    )

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
            val obj = JSONObject(raw); val map = LinkedHashMap<String, String>(); val keys = obj.keys()
            check(!strict || obj.length() <= 4096) { "Device metadata key budget exceeded" }
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = if (strict) obj.get(key) as? String ?: error("Device metadata is not a source string") else obj.optString(key)
            }
            map
        } catch (failure: Exception) {
            if (strict) throw IllegalStateException("Live search-region device metadata unavailable", failure)
            emptyMap()
        }
    }
}
