package com.bggg.watchman.history

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.data.RoutePreference
import com.bggg.watchman.data.RouteStep
import com.bggg.watchman.data.LaneGuidance
import com.bggg.watchman.data.SearchResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class RouteHistoryEntry(
    val id: Long,
    val routeKey: String,
    val startedAt: Long,
    val endedAt: Long?,
    val originName: String,
    val origin: GeoPoint,
    val destinationName: String,
    val destination: GeoPoint,
    val preference: RoutePreference,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val cameraCount: Int,
    val provider: String,
    val alternativeIndex: Int,
    val pointsJson: String,
    val stepsJson: String
) {
    fun toRoute(): Route = Route(
        points = decodePoints(pointsJson),
        steps = decodeSteps(stepsJson),
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        destination = SearchResult(destinationName, destination),
        provider = provider,
        alternativeIndex = alternativeIndex
    )

    companion object {
        private fun decodePoints(raw: String): List<GeoPoint> {
            val arr = JSONArray(raw)
            val out = ArrayList<GeoPoint>(arr.length())
            for (i in 0 until arr.length()) {
                val pair = arr.getJSONArray(i)
                out += GeoPoint(pair.getDouble(0), pair.getDouble(1))
            }
            return out
        }

        private fun decodeSteps(raw: String): List<RouteStep> {
            val arr = JSONArray(raw)
            val out = ArrayList<RouteStep>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val laneArray = o.optJSONArray("lanes")
                val lanes = ArrayList<LaneGuidance>()
                if (laneArray != null) {
                    for (laneIndex in 0 until laneArray.length()) {
                        val lane = laneArray.optJSONObject(laneIndex) ?: continue
                        val indications = ArrayList<String>()
                        val indicationArray = lane.optJSONArray("indications")
                        if (indicationArray != null) {
                            for (j in 0 until indicationArray.length()) {
                                indicationArray.optString(j).takeIf { it.isNotBlank() }?.let(indications::add)
                            }
                        }
                        lanes += LaneGuidance(indications, lane.optBoolean("valid", false), lane.optBoolean("active", false))
                    }
                }
                out += RouteStep(
                    instruction = o.optString("instruction", "Continue"),
                    maneuver = GeoPoint(o.getDouble("lat"), o.getDouble("lon")),
                    distanceMeters = o.optDouble("distanceMeters", 0.0),
                    maneuverType = o.optString("maneuverType", "continue"),
                    maneuverModifier = o.optString("maneuverModifier", ""),
                    roadName = o.optString("roadName", ""),
                    roadRef = o.optString("roadRef", ""),
                    destinations = o.optString("destinations", ""),
                    exits = o.optString("exits", ""),
                    rotaryName = o.optString("rotaryName", ""),
                    drivingSide = o.optString("drivingSide", ""),
                    bearingBefore = if (o.has("bearingBefore")) o.optDouble("bearingBefore") else null,
                    bearingAfter = if (o.has("bearingAfter")) o.optDouble("bearingAfter") else null,
                    lanes = lanes
                )
            }
            return out
        }
    }
}

data class RouteFrequencySummary(
    val representative: RouteHistoryEntry,
    val tripCount: Int,
    val lastStartedAt: Long
)

data class RouteTrackPoint(
    val tripId: Long,
    val recordedAt: Long,
    val point: GeoPoint,
    val accuracyMeters: Float,
    val speedMps: Float?
)

data class CameraEncounter(
    val tripId: Long,
    val encounteredAt: Long,
    val cameraId: String,
    val cameraType: CameraType,
    val name: String?,
    val point: GeoPoint,
    val source: String
)

data class RouteAnalyticsSnapshot(
    val entries: List<RouteHistoryEntry>,
    val tracks: Map<Long, List<RouteTrackPoint>>,
    val encounters: List<CameraEncounter>,
    val sinceMillis: Long?
)

class RouteHistoryDatabase(context: Context) : SQLiteOpenHelper(context, "watchman_routes.db", null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        createRouteHistory(db)
        createAnalyticsTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createAnalyticsTables(db)
    }

    private fun createRouteHistory(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS route_history (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              route_key TEXT NOT NULL,
              started_at INTEGER NOT NULL,
              ended_at INTEGER,
              origin_name TEXT NOT NULL,
              origin_lat REAL NOT NULL,
              origin_lon REAL NOT NULL,
              destination_name TEXT NOT NULL,
              destination_lat REAL NOT NULL,
              destination_lon REAL NOT NULL,
              preference TEXT NOT NULL,
              distance_m REAL NOT NULL,
              duration_s REAL NOT NULL,
              camera_count INTEGER NOT NULL DEFAULT 0,
              provider TEXT NOT NULL,
              alternative_index INTEGER NOT NULL DEFAULT 0,
              points_json TEXT NOT NULL,
              steps_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_route_history_started ON route_history(started_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_route_history_key ON route_history(route_key)")
    }

    private fun createAnalyticsTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS route_track_points (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              trip_id INTEGER NOT NULL,
              recorded_at INTEGER NOT NULL,
              lat REAL NOT NULL,
              lon REAL NOT NULL,
              accuracy_m REAL NOT NULL DEFAULT 0,
              speed_mps REAL,
              UNIQUE(trip_id, recorded_at) ON CONFLICT IGNORE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_route_track_trip ON route_track_points(trip_id, recorded_at)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS camera_encounters (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              trip_id INTEGER NOT NULL,
              encountered_at INTEGER NOT NULL,
              camera_id TEXT NOT NULL,
              camera_type TEXT NOT NULL,
              name TEXT,
              lat REAL NOT NULL,
              lon REAL NOT NULL,
              source TEXT NOT NULL,
              UNIQUE(trip_id, camera_id) ON CONFLICT IGNORE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_camera_encounter_trip ON camera_encounters(trip_id, encountered_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_camera_encounter_camera ON camera_encounters(camera_id)")
    }

    @Synchronized
    fun startTrip(route: Route, origin: SearchResult, preference: RoutePreference, cameraCount: Int): Long {
        val db = writableDatabase
        val stmt = db.compileStatement(
            """
            INSERT INTO route_history(
              route_key,started_at,origin_name,origin_lat,origin_lon,destination_name,destination_lat,destination_lon,
              preference,distance_m,duration_s,camera_count,provider,alternative_index,points_json,steps_json
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent()
        )
        stmt.bindString(1, routeKey(origin.point, route.destination.point))
        stmt.bindLong(2, System.currentTimeMillis())
        stmt.bindString(3, origin.displayName.ifBlank { "Route origin" })
        stmt.bindDouble(4, origin.point.lat)
        stmt.bindDouble(5, origin.point.lon)
        stmt.bindString(6, route.destination.displayName)
        stmt.bindDouble(7, route.destination.point.lat)
        stmt.bindDouble(8, route.destination.point.lon)
        stmt.bindString(9, preference.name)
        stmt.bindDouble(10, route.distanceMeters)
        stmt.bindDouble(11, route.durationSeconds)
        stmt.bindLong(12, cameraCount.toLong())
        stmt.bindString(13, route.provider)
        stmt.bindLong(14, route.alternativeIndex.toLong())
        stmt.bindString(15, encodePoints(route.points))
        stmt.bindString(16, encodeSteps(route.steps))
        return stmt.executeInsert()
    }

    @Synchronized
    fun finishTrip(id: Long, endedAt: Long = System.currentTimeMillis()) {
        if (id <= 0L) return
        writableDatabase.execSQL("UPDATE route_history SET ended_at=? WHERE id=? AND ended_at IS NULL", arrayOf(endedAt, id))
    }

    /**
     * Record an actual GPS breadcrumb for a live trip. Sampling/throttling is intentionally handled
     * by the foreground service so this database remains a simple durable append-only store.
     */
    @Synchronized
    fun appendTrackPoint(tripId: Long, location: Location) {
        if (tripId <= 0L) return
        val stmt = writableDatabase.compileStatement(
            "INSERT OR IGNORE INTO route_track_points(trip_id,recorded_at,lat,lon,accuracy_m,speed_mps) VALUES(?,?,?,?,?,?)"
        )
        stmt.bindLong(1, tripId)
        stmt.bindLong(2, location.time.takeIf { it > 0L } ?: System.currentTimeMillis())
        stmt.bindDouble(3, location.latitude)
        stmt.bindDouble(4, location.longitude)
        stmt.bindDouble(5, location.accuracy.toDouble())
        if (location.hasSpeed()) stmt.bindDouble(6, location.speed.toDouble()) else stmt.bindNull(6)
        stmt.executeInsert()
    }

    /** Record a physical camera/sensor pass once per trip. */
    @Synchronized
    fun recordCameraEncounter(tripId: Long, camera: CameraPoi, atMillis: Long = System.currentTimeMillis()) {
        if (tripId <= 0L) return
        val stmt = writableDatabase.compileStatement(
            """
            INSERT OR IGNORE INTO camera_encounters(
              trip_id,encountered_at,camera_id,camera_type,name,lat,lon,source
            ) VALUES(?,?,?,?,?,?,?,?)
            """.trimIndent()
        )
        stmt.bindLong(1, tripId)
        stmt.bindLong(2, atMillis)
        stmt.bindString(3, camera.id)
        stmt.bindString(4, camera.type.name)
        if (camera.name.isNullOrBlank()) stmt.bindNull(5) else stmt.bindString(5, camera.name)
        stmt.bindDouble(6, camera.point.lat)
        stmt.bindDouble(7, camera.point.lon)
        stmt.bindString(8, camera.source)
        stmt.executeInsert()
    }

    fun recent(limit: Int = 100): List<RouteHistoryEntry> = recentSince(null, limit)

    @Synchronized
    fun countByRouteKey(routeKey: String): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM route_history WHERE route_key=?",
            arrayOf(routeKey)
        )
        cursor.use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun findById(id: Long): RouteHistoryEntry? {
        if (id <= 0L) return null
        val cursor = readableDatabase.rawQuery(
            "SELECT ${columns()} FROM route_history WHERE id=? LIMIT 1",
            arrayOf(id.toString())
        )
        cursor.use { return if (it.moveToFirst()) read(it) else null }
    }

    fun recentSince(sinceMillis: Long?, limit: Int = 1000): List<RouteHistoryEntry> {
        val where = if (sinceMillis == null) "" else " WHERE started_at>=?"
        val args = if (sinceMillis == null) arrayOf(limit.coerceIn(1, 5000).toString())
        else arrayOf(sinceMillis.toString(), limit.coerceIn(1, 5000).toString())
        val cursor = readableDatabase.rawQuery(
            "SELECT ${columns()} FROM route_history$where ORDER BY started_at DESC LIMIT ?",
            args
        )
        val out = ArrayList<RouteHistoryEntry>()
        cursor.use { while (it.moveToNext()) out += read(it) }
        return out
    }

    fun frequency(descending: Boolean, limit: Int = 100): List<RouteFrequencySummary> {
        val entries = recent(1000)
        val grouped = entries.groupBy { it.routeKey }.map { (_, trips) ->
            val representative = trips.maxByOrNull { it.startedAt } ?: trips.first()
            RouteFrequencySummary(representative, trips.size, trips.maxOf { it.startedAt })
        }
        val ordered = if (descending) {
            grouped.sortedWith(compareByDescending<RouteFrequencySummary> { it.tripCount }.thenByDescending { it.lastStartedAt })
        } else {
            grouped.sortedWith(compareBy<RouteFrequencySummary> { it.tripCount }.thenByDescending { it.lastStartedAt })
        }
        return ordered.take(limit.coerceIn(1, 1000))
    }

    fun analyticsSnapshot(sinceMillis: Long? = null): RouteAnalyticsSnapshot {
        val entries = recentSince(sinceMillis, 5000)
        if (entries.isEmpty()) return RouteAnalyticsSnapshot(emptyList(), emptyMap(), emptyList(), sinceMillis)
        return RouteAnalyticsSnapshot(
            entries = entries,
            tracks = allTrackPoints(sinceMillis),
            encounters = allCameraEncounters(sinceMillis),
            sinceMillis = sinceMillis
        )
    }

    private fun allTrackPoints(sinceMillis: Long?): Map<Long, List<RouteTrackPoint>> {
        val where = if (sinceMillis == null) "" else " WHERE r.started_at>=?"
        val args = if (sinceMillis == null) emptyArray() else arrayOf(sinceMillis.toString())
        val c = readableDatabase.rawQuery(
            "SELECT t.trip_id,t.recorded_at,t.lat,t.lon,t.accuracy_m,t.speed_mps " +
                "FROM route_track_points t INNER JOIN route_history r ON r.id=t.trip_id$where " +
                "ORDER BY t.trip_id,t.recorded_at",
            args
        )
        val out = LinkedHashMap<Long, MutableList<RouteTrackPoint>>()
        c.use {
            while (it.moveToNext()) {
                val point = RouteTrackPoint(
                    tripId = it.getLong(0),
                    recordedAt = it.getLong(1),
                    point = GeoPoint(it.getDouble(2), it.getDouble(3)),
                    accuracyMeters = it.getFloat(4),
                    speedMps = if (it.isNull(5)) null else it.getFloat(5)
                )
                out.getOrPut(point.tripId) { ArrayList() }.add(point)
            }
        }
        return out
    }

    private fun allCameraEncounters(sinceMillis: Long?): List<CameraEncounter> {
        val where = if (sinceMillis == null) "" else " WHERE r.started_at>=?"
        val args = if (sinceMillis == null) emptyArray() else arrayOf(sinceMillis.toString())
        val c = readableDatabase.rawQuery(
            "SELECT e.trip_id,e.encountered_at,e.camera_id,e.camera_type,e.name,e.lat,e.lon,e.source " +
                "FROM camera_encounters e INNER JOIN route_history r ON r.id=e.trip_id$where " +
                "ORDER BY e.encountered_at",
            args
        )
        val out = ArrayList<CameraEncounter>()
        c.use {
            while (it.moveToNext()) {
                out += CameraEncounter(
                    tripId = it.getLong(0),
                    encounteredAt = it.getLong(1),
                    cameraId = it.getString(2),
                    cameraType = runCatching { CameraType.valueOf(it.getString(3)) }.getOrDefault(CameraType.OTHER),
                    name = if (it.isNull(4)) null else it.getString(4),
                    point = GeoPoint(it.getDouble(5), it.getDouble(6)),
                    source = it.getString(7)
                )
            }
        }
        return out
    }

    fun trackPoints(tripId: Long): List<RouteTrackPoint> {
        if (tripId <= 0L) return emptyList()
        val c = readableDatabase.rawQuery(
            "SELECT trip_id,recorded_at,lat,lon,accuracy_m,speed_mps FROM route_track_points WHERE trip_id=? ORDER BY recorded_at",
            arrayOf(tripId.toString())
        )
        val out = ArrayList<RouteTrackPoint>()
        c.use {
            while (it.moveToNext()) {
                out += RouteTrackPoint(
                    tripId = it.getLong(0),
                    recordedAt = it.getLong(1),
                    point = GeoPoint(it.getDouble(2), it.getDouble(3)),
                    accuracyMeters = it.getFloat(4),
                    speedMps = if (it.isNull(5)) null else it.getFloat(5)
                )
            }
        }
        return out
    }

    fun cameraEncounters(tripId: Long): List<CameraEncounter> {
        if (tripId <= 0L) return emptyList()
        val c = readableDatabase.rawQuery(
            "SELECT trip_id,encountered_at,camera_id,camera_type,name,lat,lon,source FROM camera_encounters WHERE trip_id=? ORDER BY encountered_at",
            arrayOf(tripId.toString())
        )
        val out = ArrayList<CameraEncounter>()
        c.use {
            while (it.moveToNext()) {
                out += CameraEncounter(
                    tripId = it.getLong(0),
                    encounteredAt = it.getLong(1),
                    cameraId = it.getString(2),
                    cameraType = runCatching { CameraType.valueOf(it.getString(3)) }.getOrDefault(CameraType.OTHER),
                    name = if (it.isNull(4)) null else it.getString(4),
                    point = GeoPoint(it.getDouble(5), it.getDouble(6)),
                    source = it.getString(7)
                )
            }
        }
        return out
    }

    fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("route_track_points", null, null)
            db.delete("camera_encounters", null, null)
            db.delete("route_history", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun columns() = "id,route_key,started_at,ended_at,origin_name,origin_lat,origin_lon,destination_name,destination_lat,destination_lon,preference,distance_m,duration_s,camera_count,provider,alternative_index,points_json,steps_json"

    private fun read(c: Cursor): RouteHistoryEntry = RouteHistoryEntry(
        id = c.getLong(0),
        routeKey = c.getString(1),
        startedAt = c.getLong(2),
        endedAt = if (c.isNull(3)) null else c.getLong(3),
        originName = c.getString(4),
        origin = GeoPoint(c.getDouble(5), c.getDouble(6)),
        destinationName = c.getString(7),
        destination = GeoPoint(c.getDouble(8), c.getDouble(9)),
        preference = runCatching { RoutePreference.valueOf(c.getString(10)) }.getOrDefault(RoutePreference.FASTEST),
        distanceMeters = c.getDouble(11),
        durationSeconds = c.getDouble(12),
        cameraCount = c.getInt(13),
        provider = c.getString(14),
        alternativeIndex = c.getInt(15),
        pointsJson = c.getString(16),
        stepsJson = c.getString(17)
    )

    private fun routeKey(origin: GeoPoint, destination: GeoPoint): String = String.format(
        Locale.US, "%.4f,%.4f->%.4f,%.4f", origin.lat, origin.lon, destination.lat, destination.lon
    )

    private fun encodePoints(points: List<GeoPoint>): String = JSONArray().apply {
        points.forEach { put(JSONArray().put(it.lat).put(it.lon)) }
    }.toString()

    private fun encodeSteps(steps: List<RouteStep>): String = JSONArray().apply {
        steps.forEach { step ->
            put(JSONObject().apply {
                put("instruction", step.instruction)
                put("lat", step.maneuver.lat)
                put("lon", step.maneuver.lon)
                put("distanceMeters", step.distanceMeters)
                put("maneuverType", step.maneuverType)
                put("maneuverModifier", step.maneuverModifier)
                put("roadName", step.roadName)
                put("roadRef", step.roadRef)
                put("destinations", step.destinations)
                put("exits", step.exits)
                put("rotaryName", step.rotaryName)
                put("drivingSide", step.drivingSide)
                step.bearingBefore?.let { put("bearingBefore", it) }
                step.bearingAfter?.let { put("bearingAfter", it) }
                put("lanes", JSONArray().apply {
                    step.lanes.forEach { lane ->
                        put(JSONObject().apply {
                            put("indications", JSONArray().apply { lane.indications.forEach(::put) })
                            put("valid", lane.valid)
                            put("active", lane.active)
                        })
                    }
                })
            })
        }
    }.toString()

    private companion object {
        const val DB_VERSION = 2
    }
}
