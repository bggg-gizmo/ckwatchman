package com.bggg.watchman.storage

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.routing.RouteCorridor
import com.bggg.watchman.util.Geo
import org.json.JSONObject
import kotlin.math.cos

/**
 * Persistent national camera/sensor cache.
 *
 * v0.2.7 organizes completion as state -> county-equivalent -> SafeTOC-sized locale -> source
 * family. SafeTOC parity source families use their exact v1.3.4 acquisition contract. Camera rows
 * are source-snapshot scoped, so a successful family is committed immediately and a failed family
 * never erases unrelated data.
 */
class NationalCameraCache(context: Context) : SQLiteOpenHelper(context, "watchman_national.db", null, 6) {
    data class RegionState(val regionId: String, val ok: Boolean, val count: Int, val updatedAt: Long, val error: String?)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE national_cameras (
              cache_key TEXT PRIMARY KEY,
              region_id TEXT NOT NULL,
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
        db.execSQL("CREATE INDEX idx_national_region ON national_cameras(region_id)")
        db.execSQL("CREATE INDEX idx_national_lat_lon ON national_cameras(lat, lon)")
        db.execSQL("CREATE INDEX idx_national_source_record ON national_cameras(source_id, source_record_id)")
        db.execSQL(
            """
            CREATE TABLE national_regions (
              region_id TEXT PRIMARY KEY,
              ok INTEGER NOT NULL,
              count INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL,
              error TEXT
            )
            """.trimIndent()
        )
        createLegacyTileTable(db)
        createCountyTables(db)
        RoutingCacheIndex.create(db, "national_cameras")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createLegacyTileTable(db)
        if (oldVersion < 3) {
            createCountyTables(db)
            db.execSQL(
                "UPDATE national_regions SET ok=0, error='v0.2.5 county/locale national refresh required', updated_at=?",
                arrayOf(System.currentTimeMillis())
            )
        }
        if (oldVersion < 4) {
            // v0.2.6 changes national acquisition semantics to the exact SafeTOC v1.3.4
            // canonical GET contract. Preserve previously cached camera rows as fallback, but
            // invalidate completion state so every county/locale is re-earned under that contract.
            val now = System.currentTimeMillis()
            db.execSQL("UPDATE national_regions SET ok=0, error='v0.2.6 exact SafeTOC refresh required', updated_at=?", arrayOf(now))
            db.execSQL("UPDATE national_counties SET ok=0, error='v0.2.6 exact SafeTOC refresh required', updated_at=?", arrayOf(now))
            db.execSQL("UPDATE national_locales SET ok=0, error='v0.2.6 exact SafeTOC refresh required', updated_at=?", arrayOf(now))
            db.execSQL("UPDATE national_sources SET ok=0, error='v0.2.6 exact SafeTOC refresh required', updated_at=?", arrayOf(now))
        }
        if (oldVersion < 5) {
            // v0.2.7 fixes the completion boundary: exact SafeTOC ALPR + traffic source success
            // earns locale/county/state completion. Existing source snapshots remain authoritative
            // and are deliberately preserved so upgrades do not throw away good cached data.
            val now = System.currentTimeMillis()
            db.execSQL("UPDATE national_regions SET ok=0, error='v0.2.7 SafeTOC completion refresh required', updated_at=?", arrayOf(now))
            db.execSQL("UPDATE national_counties SET ok=0, error='v0.2.7 SafeTOC completion refresh required', updated_at=?", arrayOf(now))
            db.execSQL("UPDATE national_locales SET ok=0, error='v0.2.7 SafeTOC completion refresh required', updated_at=?", arrayOf(now))
        }
        if (oldVersion < 6) RoutingCacheIndex.create(db, "national_cameras")
    }

    private fun createLegacyTileTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS national_tiles (
              tile_id TEXT PRIMARY KEY,
              region_id TEXT NOT NULL,
              ok INTEGER NOT NULL,
              count INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL,
              error TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_national_tiles_region ON national_tiles(region_id)")
    }

    private fun createCountyTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS national_counties (
              county_id TEXT PRIMARY KEY,
              state_id TEXT NOT NULL,
              name TEXT NOT NULL,
              ok INTEGER NOT NULL,
              count INTEGER NOT NULL DEFAULT 0,
              locale_total INTEGER NOT NULL DEFAULT 0,
              locale_completed INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL,
              error TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_national_counties_state ON national_counties(state_id,ok)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS national_locales (
              locale_id TEXT PRIMARY KEY,
              county_id TEXT NOT NULL,
              ok INTEGER NOT NULL,
              count INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL,
              error TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_national_locales_county ON national_locales(county_id,ok)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS national_sources (
              snapshot_id TEXT PRIMARY KEY,
              locale_id TEXT NOT NULL,
              county_id TEXT NOT NULL,
              state_id TEXT NOT NULL,
              family TEXT NOT NULL,
              ok INTEGER NOT NULL,
              count INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL,
              error TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_national_sources_locale ON national_sources(locale_id,ok)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_national_sources_county ON national_sources(county_id,ok)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_national_sources_state ON national_sources(state_id,ok)")
    }

    @Synchronized
    fun replaceSourceSnapshot(
        stateId: String,
        countyId: String,
        localeId: String,
        snapshotId: String,
        family: String,
        cameras: List<CameraPoi>,
        complete: Boolean,
        error: String?
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val routing = RoutingCacheIndex.beforeWrite(db)
            db.delete("national_cameras", "region_id=?", arrayOf(snapshotId))
            insertAll(db, snapshotId, cameras)
            upsertSourceState(db, stateId, countyId, localeId, snapshotId, family, complete, cameras.size, error)
            RoutingCacheIndex.afterWrite(db, "national_cameras", routing)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Replaces one bundled jurisdiction shard without writing any live source, locale, county, or
     * state completion rows. Seed knowledge is immediately queryable but always earns zero setup
     * completion credit.
     */
    @Synchronized
    fun replaceSeedShard(jurisdiction: String, cameras: List<CameraPoi>) {
        require(jurisdiction.matches(Regex("[a-z0-9-]{2,40}")))
        val snapshotId = "seed:$jurisdiction"
        val db = writableDatabase
        db.beginTransaction()
        try {
            val routing = RoutingCacheIndex.beforeWrite(db)
            db.delete("national_cameras", "region_id=?", arrayOf(snapshotId))
            insertAll(db, snapshotId, cameras)
            RoutingCacheIndex.afterWrite(db, "national_cameras", routing)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun markSourceFailure(stateId: String, countyId: String, localeId: String, snapshotId: String, family: String, error: String?) {
        val db = writableDatabase
        upsertSourceState(db, stateId, countyId, localeId, snapshotId, family, false, snapshotCount(snapshotId), error)
    }

    @Synchronized
    fun markLocaleSuccess(countyId: String, localeId: String) {
        val db = writableDatabase
        val count = localeCameraCount(db, localeId)
        upsertLocaleState(db, countyId, localeId, true, count, null)
    }

    @Synchronized
    fun markLocaleFailure(countyId: String, localeId: String, error: String?) {
        val db = writableDatabase
        val count = localeCameraCount(db, localeId)
        upsertLocaleState(db, countyId, localeId, false, count, error)
    }

    @Synchronized
    fun markCountySuccess(stateId: String, countyId: String, name: String, localeTotal: Int) {
        val db = writableDatabase
        val count = countyCameraCount(db, countyId)
        upsertCountyState(db, stateId, countyId, name, true, count, localeTotal, localeTotal, null)
    }

    @Synchronized
    fun markCountyFailure(stateId: String, countyId: String, name: String, localeTotal: Int, localeCompleted: Int, error: String?) {
        val db = writableDatabase
        val count = countyCameraCount(db, countyId)
        upsertCountyState(db, stateId, countyId, name, false, count, localeTotal, localeCompleted, error)
    }

    @Synchronized
    fun markRegionSuccess(regionId: String) {
        val db = writableDatabase
        upsertRegionState(db, regionId, true, stateCameraCount(db, regionId), null)
    }

    @Synchronized
    fun markRegionFailure(regionId: String, error: String?) {
        val db = writableDatabase
        upsertRegionState(db, regionId, false, stateCameraCount(db, regionId), error)
    }

    fun successfulSourceIds(): Set<String> = queryIdSet("SELECT snapshot_id FROM national_sources WHERE ok=1")
    fun successfulLocaleIds(): Set<String> = queryIdSet("SELECT locale_id FROM national_locales WHERE ok=1")
    fun successfulCountyIds(): Set<String> = queryIdSet("SELECT county_id FROM national_counties WHERE ok=1")
    fun successfulCountyCount(): Int = scalarInt("SELECT COUNT(*) FROM national_counties WHERE ok=1")

    fun regionStates(): List<RegionState> {
        val out = ArrayList<RegionState>()
        readableDatabase.rawQuery("SELECT region_id,ok,count,updated_at,error FROM national_regions ORDER BY region_id", emptyArray()).use { c ->
            while (c.moveToNext()) out += RegionState(c.getString(0), c.getInt(1) != 0, c.getInt(2), c.getLong(3), c.getString(4))
        }
        return out
    }

    fun successfulRegionIds(): Set<String> = regionStates().filter { it.ok }.mapTo(LinkedHashSet()) { it.regionId }
    fun successfulRegionCount(): Int = scalarInt("SELECT COUNT(*) FROM national_regions WHERE ok=1")

    fun totalCameraCount(): Int = scalarInt("SELECT COUNT(DISTINCT COALESCE(source_record_id, cache_key)) FROM national_cameras")

    fun nearby(center: GeoPoint, radiusMeters: Double): List<CameraPoi> {
        val latPad = radiusMeters / 111_320.0
        val lonPad = radiusMeters / (111_320.0 * cos(Math.toRadians(center.lat)).coerceAtLeast(0.15))
        val candidates = queryBox(
            (center.lat - latPad).coerceAtLeast(-89.9),
            (center.lon - lonPad).coerceAtLeast(-179.9),
            (center.lat + latPad).coerceAtMost(89.9),
            (center.lon + lonPad).coerceAtMost(179.9)
        )
        return deduplicate(candidates.filter { Geo.distanceMeters(center, it.point) <= radiusMeters })
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

    private fun queryIdSet(sql: String): Set<String> {
        val out = LinkedHashSet<String>()
        readableDatabase.rawQuery(sql, emptyArray()).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    private fun scalarInt(sql: String): Int = readableDatabase.rawQuery(sql, emptyArray()).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun snapshotCount(snapshotId: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM national_cameras WHERE region_id=?", arrayOf(snapshotId)
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun localeCameraCount(db: SQLiteDatabase, localeId: String): Int = db.rawQuery(
        "SELECT COUNT(DISTINCT COALESCE(c.source_record_id,c.cache_key)) FROM national_cameras c JOIN national_sources s ON c.region_id=s.snapshot_id WHERE s.locale_id=?",
        arrayOf(localeId)
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun countyCameraCount(db: SQLiteDatabase, countyId: String): Int = db.rawQuery(
        "SELECT COUNT(DISTINCT COALESCE(c.source_record_id,c.cache_key)) FROM national_cameras c JOIN national_sources s ON c.region_id=s.snapshot_id WHERE s.county_id=?",
        arrayOf(countyId)
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun stateCameraCount(db: SQLiteDatabase, stateId: String): Int = db.rawQuery(
        "SELECT COUNT(DISTINCT COALESCE(c.source_record_id,c.cache_key)) FROM national_cameras c JOIN national_sources s ON c.region_id=s.snapshot_id WHERE s.state_id=?",
        arrayOf(stateId)
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun upsertSourceState(db: SQLiteDatabase, stateId: String, countyId: String, localeId: String, snapshotId: String, family: String, ok: Boolean, count: Int, error: String?) {
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO national_sources(snapshot_id,locale_id,county_id,state_id,family,ok,count,updated_at,error) VALUES(?,?,?,?,?,?,?,?,?)"
        )
        stmt.bindString(1, snapshotId); stmt.bindString(2, localeId); stmt.bindString(3, countyId); stmt.bindString(4, stateId); stmt.bindString(5, family)
        stmt.bindLong(6, if (ok) 1L else 0L); stmt.bindLong(7, count.toLong()); stmt.bindLong(8, System.currentTimeMillis()); bindNullable(stmt, 9, error?.take(500)); stmt.executeInsert()
    }

    private fun upsertLocaleState(db: SQLiteDatabase, countyId: String, localeId: String, ok: Boolean, count: Int, error: String?) {
        val stmt = db.compileStatement("INSERT OR REPLACE INTO national_locales(locale_id,county_id,ok,count,updated_at,error) VALUES(?,?,?,?,?,?)")
        stmt.bindString(1, localeId); stmt.bindString(2, countyId); stmt.bindLong(3, if (ok) 1L else 0L); stmt.bindLong(4, count.toLong()); stmt.bindLong(5, System.currentTimeMillis()); bindNullable(stmt, 6, error?.take(500)); stmt.executeInsert()
    }

    private fun upsertCountyState(db: SQLiteDatabase, stateId: String, countyId: String, name: String, ok: Boolean, count: Int, localeTotal: Int, localeCompleted: Int, error: String?) {
        val stmt = db.compileStatement("INSERT OR REPLACE INTO national_counties(county_id,state_id,name,ok,count,locale_total,locale_completed,updated_at,error) VALUES(?,?,?,?,?,?,?,?,?)")
        stmt.bindString(1, countyId); stmt.bindString(2, stateId); stmt.bindString(3, name); stmt.bindLong(4, if (ok) 1L else 0L); stmt.bindLong(5, count.toLong()); stmt.bindLong(6, localeTotal.toLong()); stmt.bindLong(7, localeCompleted.toLong()); stmt.bindLong(8, System.currentTimeMillis()); bindNullable(stmt, 9, error?.take(500)); stmt.executeInsert()
    }

    private fun upsertRegionState(db: SQLiteDatabase, regionId: String, ok: Boolean, count: Int, error: String?) {
        val stmt = db.compileStatement("INSERT OR REPLACE INTO national_regions(region_id,ok,count,updated_at,error) VALUES(?,?,?,?,?)")
        stmt.bindString(1, regionId); stmt.bindLong(2, if (ok) 1L else 0L); stmt.bindLong(3, count.toLong()); stmt.bindLong(4, System.currentTimeMillis()); bindNullable(stmt, 5, error?.take(500)); stmt.executeInsert()
    }

    fun withinRegion(region: DeviceSearchRegion, maximumRows: Int = 50000,
        cancelled: () -> Boolean = { false }): List<CameraPoi> =
        region.collect(maximumRows, cancelled) { window, remaining ->
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Search-region device I/O is forbidden on the main thread" }
            queryBox(window.south, window.west, window.north, window.east, remaining, cancelled)
        }

    private fun queryBox(south: Double, west: Double, north: Double, east: Double,
        maximumRows: Int? = null, cancelled: () -> Boolean = { false }): List<CameraPoi> {
        check(!cancelled()) { "National device query cancelled" }
        require(maximumRows == null || maximumRows in 0..500000)
        val out = ArrayList<CameraPoi>()
        readableDatabase.rawQuery(
            "SELECT id,type,lat,lon,name,road,direction,operator,manufacturer,source_name,source_id,source_url,source_record_id,confidence,tags_json,last_updated FROM national_cameras WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?" +
                if (maximumRows == null) "" else " LIMIT ?",
            arrayOf(south.toString(), north.toString(), west.toString(), east.toString()) +
                (maximumRows?.let { arrayOf((it + 1).toString()) } ?: emptyArray())
        ).use { c ->
            while (c.moveToNext()) {
                check(!cancelled()) { "National device query cancelled" }
                check(maximumRows == null || out.size < maximumRows) { "National device row budget exceeded; partial data refused" }
                out += readCamera(c, maximumRows != null)
            }
        }
        check(!cancelled()) { "National device query cancelled" }
        return out
    }

    private fun insertAll(db: SQLiteDatabase, snapshotId: String, cameras: List<CameraPoi>) {
        if (cameras.isEmpty()) return
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO national_cameras(cache_key,region_id,id,type,lat,lon,name,road,direction,operator,manufacturer,source_name,source_id,source_url,source_record_id,confidence,tags_json,last_updated) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        )
        cameras.forEach { camera ->
            stmt.clearBindings()
            stmt.bindString(1, "$snapshotId|${camera.sourceRecordId ?: camera.id}")
            stmt.bindString(2, snapshotId); stmt.bindString(3, camera.id); stmt.bindString(4, camera.type.name); stmt.bindDouble(5, camera.point.lat); stmt.bindDouble(6, camera.point.lon)
            bindNullable(stmt, 7, camera.name); bindNullable(stmt, 8, camera.road); bindNullable(stmt, 9, camera.direction); bindNullable(stmt, 10, camera.operator); bindNullable(stmt, 11, camera.manufacturer)
            bindNullable(stmt, 12, camera.source); stmt.bindString(13, camera.sourceId.ifBlank { snapshotId }); bindNullable(stmt, 14, camera.sourceUrl); bindNullable(stmt, 15, camera.sourceRecordId)
            stmt.bindLong(16, camera.confidence.toLong()); bindNullable(stmt, 17, if (camera.tags.isEmpty()) null else JSONObject(camera.tags as Map<*, *>).toString()); stmt.bindLong(18, camera.lastUpdatedMillis)
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
        source=c.getString(9) ?: "OpenStreetMap / Overpass — national cache", sourceId=c.getString(10) ?: "osm_national",
        sourceUrl=c.getString(11), sourceRecordId=c.getString(12), confidence=readConfidence(c,strict),
        tags=parseTags(c.getString(14), strict=strict), lastUpdatedMillis=c.getLong(15))
    fun prepareRoutingIndex(maximumRows: Int = 500, cancelled: () -> Boolean = { false }): RoutingCacheIndex.Progress {
        RoutingCacheIndex.requireWorker(cancelled)
        return RoutingCacheIndex.prepare(writableDatabase, "national_cameras", maximumRows, cancelled)
    }
    fun freezeRoutingEpoch(cancelled: () -> Boolean = { false }): RoutingCacheIndex.Epoch {
        RoutingCacheIndex.requireWorker(cancelled)
        return RoutingCacheIndex.freeze(readableDatabase, "national_cameras", cancelled)
    }
    fun routingObservations(epoch: RoutingCacheIndex.Epoch, keys: Set<String>, maximumRows: Int = 5000,
        maximumMetadataCharacters: Long = 8000000, cancelled: () -> Boolean = { false }): List<CameraPoi> {
        RoutingCacheIndex.requireWorker(cancelled)
        check(epoch.database === readableDatabase && epoch.table == "national_cameras")
        return RoutingCacheIndex.rows(epoch, keys, null, maximumRows, maximumMetadataCharacters, cancelled) { readCamera(it,true) }
    }
    fun routingWindow(epoch: RoutingCacheIndex.Epoch, window: DeviceSearchRegion.Window, maximumRows: Int = 5000,
        maximumMetadataCharacters: Long = 8000000, cancelled: () -> Boolean = { false }): List<CameraPoi> {
        RoutingCacheIndex.requireWorker(cancelled)
        check(epoch.database === readableDatabase && epoch.table == "national_cameras")
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
            if (strict) throw IllegalStateException("National search-region device metadata unavailable", failure)
            emptyMap()
        }
    }

    private fun deduplicate(items: List<CameraPoi>): List<CameraPoi> {
        val out = LinkedHashMap<String, CameraPoi>()
        items.forEach { camera ->
            val osm = (camera.sourceUrl ?: "").contains("openstreetmap.org") || camera.id.startsWith("node/") || camera.id.startsWith("way/") || camera.id.startsWith("relation/")
            val key = if (osm && !camera.sourceRecordId.isNullOrBlank()) "osm:${camera.sourceRecordId}" else "source:${camera.sourceId}:${camera.sourceRecordId ?: camera.id}"
            val old = out[key]
            if (old == null || camera.lastUpdatedMillis > old.lastUpdatedMillis) out[key] = camera
        }
        return out.values.toList()
    }
}
