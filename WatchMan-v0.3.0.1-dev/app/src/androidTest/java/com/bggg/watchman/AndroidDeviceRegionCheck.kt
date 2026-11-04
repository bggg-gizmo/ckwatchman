package com.bggg.watchman

import android.app.Instrumentation
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.data.SearchResult
import com.bggg.watchman.storage.AuthoritativeDeviceRepository
import com.bggg.watchman.storage.CameraDatabase
import com.bggg.watchman.storage.DeviceSearchRegion
import com.bggg.watchman.storage.DeviceIdentity
import com.bggg.watchman.storage.NationalCameraCache
import com.bggg.watchman.storage.RegionalCameraCache
import java.util.UUID

/** Real local SQLite reads. Only uniquely scoped synthetic fixture rows are inserted/removed. */
object AndroidDeviceRegionCheck {
    fun run(instrumentation: Instrumentation): String {
        val start = SystemClock.elapsedRealtime()
        val context = instrumentation.targetContext
        // Full UUID entropy, within the national seed-shard lowercase2..40character contract.
        val token = "dr-" + UUID.randomUUID().toString().replace("-", "")
        check(token.matches(Regex("[a-z0-9-]{2,40}")))
        val liveSource = "test-$token"
        val region = DeviceSearchRegion.bounds("synthetic-whole-road-graph", "Synthetic Antarctic antimeridian query, not real acquisition",
            -88.0, 170.0, -87.0, -170.0)
        val live = CameraDatabase(context)
        val national = NationalCameraCache(context)
        val regional = RegionalCameraCache(context)
        val repository = AuthoritativeDeviceRepository(live, national, regional)
        fun poi(id: String, point: GeoPoint, source: String, type: CameraType = CameraType.ALPR) = CameraPoi(
            "$token-$id", type, point, "INSTRUMENTATION ONLY", null, null, null, null,
            source = "Synthetic device-region fixture", sourceId = source, sourceRecordId = "$token-$id",
            tags = mapOf("watchman:test_fixture" to token))
        val west = poi("west", GeoPoint(-87.5, -175.0), "test-national-$token")
        val east = poi("east", GeoPoint(-87.5, 175.0), liveSource)
        val edge = poi("edge", GeoPoint(-88.0, 180.0), "test-regional-$token")
        val outside = poi("outside", GeoPoint(-87.5, 0.0), liveSource)
        val signedWest = poi("signed-west", GeoPoint(-88.0, -180.0), "test-regional-$token")
        val northEast = poi("north-east", GeoPoint(-87.0, 170.0), "test-regional-$token")
        val northWest = poi("north-west", GeoPoint(-87.0, -170.0), "test-regional-$token")
        // Synthetic upstream identity, not a real OSM object. Deliberately newer weaker
        // observations must enrich, not erase, older explicit product/model evidence.
        val canonicalRecord = "synthetic-$token-raven"
        val raven = poi("raven", GeoPoint(-87.4, 175.0), "test-national-$token", CameraType.ACOUSTIC).copy(
            sourceUrl = "https://www.openstreetmap.org/node/$canonicalRecord",
            sourceRecordId = canonicalRecord, manufacturer = "Flock Safety", confidence = 99,
            lastUpdatedMillis = 1000L,
            tags = mapOf("watchman:test_fixture" to token, "model" to "Flock Raven",
                "manufacturer" to "Flock Safety", "sensor:type" to "acoustic", "national:evidence" to "model"))
        val newerSignal = raven.copy(id = "$token-newer-signal", type = CameraType.TRAFFIC,
            sourceId = liveSource, confidence = 100, lastUpdatedMillis = 2000L,
            tags = mapOf("watchman:test_fixture" to token, "highway" to "traffic_signals", "live:evidence" to "signal"))
        val manufacturerOnly = raven.copy(id = "$token-manufacturer-only", type = CameraType.FLOCK,
            sourceId = "test-regional-$token", confidence = 100, lastUpdatedMillis = 3000L,
            tags = mapOf("watchman:test_fixture" to token, "manufacturer" to "Flock Safety", "regional:evidence" to "manufacturer"))
        var metadataRefusals = 0
        var typedRefusals = 0
        try {
            live.replaceSourceSnapshot(liveSource, listOf(east, outside, newerSignal))
            national.replaceSeedShard(token, listOf(west, east.copy(sourceId = "test-national-$token"), raven))
            regional.replaceSnapshot(token, listOf(edge, signedWest, northEast, northWest, manufacturerOnly))
            check(national.regionStates().none { it.regionId == token })
            check(national.successfulSourceIds().none { it.contains(token) })
            val actual = repository.withinRegion(region).requireAvailable()
            val fixtures = actual.devices.filter { it.tags["watchman:test_fixture"] == token }
            val fixtureIds = fixtures.map { it.id }.toSet()
            // Different upstream source IDs remain distinct; repeated same visible ID is not
            // permission to spatially merge unrelated observations.
            check(fixtureIds == setOf(west.id, east.id, edge.id, signedWest.id, northEast.id, northWest.id, raven.id) &&
                actual.devices.none { it.id == outside.id })
            check(fixtures.size == 8) { "Same display ID from two unrelated source identities must remain two devices" }
            check(fixtures.map(DeviceIdentity::logicalKey).toSet().size == fixtures.size)
            check(fixtures.count { it.id == east.id } == 2)
            val mergedRaven = fixtures.single { DeviceIdentity.logicalKey(it) == "osm:$canonicalRecord" }
            check(mergedRaven.type == CameraType.ACOUSTIC && mergedRaven.tags["model"] == "Flock Raven")
            check(mergedRaven.lastUpdatedMillis == 3000L && mergedRaven.tags["highway"] == "traffic_signals")
            check(mergedRaven.tags["national:evidence"] == "model" && mergedRaven.tags["live:evidence"] == "signal" &&
                mergedRaven.tags["regional:evidence"] == "manufacturer")
            check(mergedRaven.tags["watchman:merged_sources"]?.split(",")?.toSet() ==
                setOf(raven.sourceId, newerSignal.sourceId, manufacturerOnly.sourceId)) {
                "Three-layer enrichment must preserve every observed source, not only the final pair"
            }
            check(actual.liveCount >= 2 && actual.nationalCount >= 3 && actual.regionalCount >= 5)
            val frozen = actual.freezeForRouting(region.queryScope)
            check(frozen.queryScope == region.queryScope && frozen.entries.any { it.device.id == edge.id })
            check(frozen.entries.single { it.logicalId == "osm:$canonicalRecord" }.weight == 1L)
            check(frozen.revision == actual.freezeForRouting(region.queryScope).revision)
            check(frozen.revision != actual.freezeForRouting(region.queryScope + "; changed-declared-evidence").revision)
            val overflow = repository.withinRegion(region, maximumRowsPerLayer = 1)
            check(overflow.unavailableSources == setOf("live", "national", "regional") &&
                runCatching { overflow.freezeForRouting(region.queryScope) }.isFailure)
            check(runCatching { live.withinRegion(region, maximumRows = 1) }.isFailure)
            check(runCatching { national.withinRegion(region, maximumRows = 1) }.isFailure)
            check(runCatching { regional.withinRegion(region, maximumRows = 1) }.isFailure)
            val cancelled = repository.withinRegion(region, cancelled = { true })
            check(cancelled.unavailableSources == setOf("live", "national", "regional"))
            check(runCatching { cancelled.freezeForRouting(region.queryScope) }.isFailure)
            var cancellationChecks = 0
            val cancelledDuringRead = repository.withinRegion(region, cancelled = { ++cancellationChecks >= 5 })
            check(cancellationChecks >= 5 && cancelledDuringRead.unavailableSources == setOf("live", "national", "regional"))
            check(runCatching { cancelledDuringRead.requireAvailable() }.isFailure)

            // Never corrupt user rows. Each update addresses exactly one UUID-scoped row,
            // restores it even if an assertion fails, and verifies independent layer refusal.
            fun corruptFixture(db: SQLiteDatabase, table: String, row: CameraPoi, layer: String, raw: String) {
                val record = requireNotNull(row.sourceRecordId)
                check(row.sourceId.contains(token) && record.contains(token))
                val old = db.rawQuery("SELECT tags_json FROM $table WHERE source_id=? AND source_record_id=?",
                    arrayOf(row.sourceId, record)).use {
                    check(it.moveToFirst()); val saved = it.getString(0); check(!it.moveToNext()); saved
                }
                fun replace(value: String?) {
                    val values = ContentValues().apply { if (value == null) putNull("tags_json") else put("tags_json", value) }
                    check(db.update(table, values, "source_id=? AND source_record_id=?",
                        arrayOf(row.sourceId, record)) == 1)
                }
                try {
                    replace(raw)
                    val refused = repository.withinRegion(region)
                    check(refused.unavailableSources == setOf(layer)) { "Corrupt $layer metadata must refuse that layer, not claim empty coverage" }
                    check(runCatching { refused.freezeForRouting(region.queryScope) }.isFailure)
                    metadataRefusals++
                } finally { replace(old) }
                check(repository.withinRegion(region).requireAvailable().freezeForRouting(region.queryScope).revision == frozen.revision)
            }
            val invalidMetadata = listOf("{", "[]", "{\"manufacturer\":7}", "{\"model\":null}",
                "{\"model\":true}", "{\"model\":{\"nested\":\"Raven\"}}", "{\"model\":[\"Raven\"]}",
                "{\"model\":\"" + "x".repeat(1024 * 1024) + "\"}",
                (0..4096).joinToString(prefix = "{", postfix = "}") { "\"k$it\":\"v\"" })
            invalidMetadata.forEach { raw ->
                corruptFixture(live.writableDatabase, "cameras", east, "live", raw)
                corruptFixture(national.writableDatabase, "national_cameras", west, "national", raw)
                corruptFixture(regional.writableDatabase, "regional_cameras", edge, "regional", raw)
            }
            fun legacyRow(layer: String, row: CameraPoi): CameraPoi {
                val records = when (layer) {
                    "live" -> live.nearby(row.point, 100.0)
                    "national" -> national.nearby(row.point, 100.0)
                    else -> regional.withinRoutes(listOf(Route(
                        listOf(GeoPoint(row.point.lat - .0001, row.point.lon), GeoPoint(row.point.lat + .0001, row.point.lon)),
                        emptyList(), 22.0, 3.0, SearchResult("Synthetic legacy corridor", row.point), "INSTRUMENTATION ONLY")), 100.0)
                }
                return records.single { it.sourceId == row.sourceId && it.sourceRecordId == row.sourceRecordId }
            }
            fun corruptTypedField(db: SQLiteDatabase, table: String, row: CameraPoi, layer: String,
                field: String, bad: Any, reject: Boolean = true) {
                check(field == "type" || field == "confidence")
                val record = requireNotNull(row.sourceRecordId)
                check(row.sourceId.contains(token) && record.contains(token))
                val old = db.rawQuery("SELECT type,confidence FROM $table WHERE source_id=? AND source_record_id=?",
                    arrayOf(row.sourceId, record)).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getType(0) == Cursor.FIELD_TYPE_STRING &&
                        cursor.getType(1) == Cursor.FIELD_TYPE_INTEGER)
                    val original = cursor.getString(0) to cursor.getLong(1)
                    check(!cursor.moveToNext()); original
                }
                fun replace(value: Any) {
                    val values = ContentValues().apply {
                        when (value) {
                            is String -> put(field, value)
                            is Long -> put(field, value)
                            is Double -> put(field, value)
                            is ByteArray -> put(field, value)
                            else -> error("Unsupported typed corruption fixture")
                        }
                    }
                    check(db.update(table, values, "source_id=? AND source_record_id=?", arrayOf(row.sourceId, record)) == 1)
                }
                try {
                    replace(bad)
                    val observed = repository.withinRegion(region)
                    if (reject) {
                        check(observed.unavailableSources == setOf(layer)) { "Corrupt $layer $field must make that layer unavailable" }
                        check(runCatching { observed.freezeForRouting(region.queryScope) }.isFailure)
                        typedRefusals++
                        // These are compatibility assertions, not approval of coercing corrupted
                        // evidence in the new routing query. Existing unbounded flows stay intact.
                        if (field == "type" && bad is String) check(legacyRow(layer, row).type == CameraType.OTHER)
                        if (field == "confidence" && bad is Long) check(legacyRow(layer, row).confidence == bad.toInt().coerceIn(0, 100))
                    } else {
                        val exact = observed.requireAvailable().devices.single {
                            it.sourceId == row.sourceId && it.sourceRecordId == row.sourceRecordId
                        }
                        if (field == "type") check(exact.type == CameraType.OTHER)
                        else check(exact.confidence == (bad as Long).toInt())
                    }
                } finally { replace(if (field == "type") old.first else old.second) }
                check(repository.withinRegion(region).requireAvailable().freezeForRouting(region.queryScope).revision == frozen.revision)
            }
            val invalidTypedFields = listOf<Pair<String, Any>>(
                "type" to "NOT_A_CAMERA_TYPE", "type" to "alpr", "type" to "", "type" to byteArrayOf(65, 76, 80, 82),
                "confidence" to -1L, "confidence" to 101L, "confidence" to 4294967296L,
                "confidence" to Long.MAX_VALUE, "confidence" to Long.MIN_VALUE,
                "confidence" to 70.5, "confidence" to "not-an-integer", "confidence" to byteArrayOf(55, 48))
            val layers = listOf(Triple(live.writableDatabase, "cameras", east to "live"),
                Triple(national.writableDatabase, "national_cameras", west to "national"),
                Triple(regional.writableDatabase, "regional_cameras", northEast to "regional"))
            for ((db, table, scoped) in layers) {
                for ((field, bad) in invalidTypedFields) corruptTypedField(db, table, scoped.first, scoped.second, field, bad)
                corruptTypedField(db, table, scoped.first, scoped.second, "type", CameraType.OTHER.name, reject = false)
                corruptTypedField(db, table, scoped.first, scoped.second, "confidence", 0L, reject = false)
                corruptTypedField(db, table, scoped.first, scoped.second, "confidence", 100L, reject = false)
            }
            check(typedRefusals == 36)
            var rejectedMainLayers: Set<String> = emptySet()
            instrumentation.runOnMainSync {
                check(runCatching { live.withinRegion(region) }.isFailure)
                check(runCatching { national.withinRegion(region) }.isFailure)
                check(runCatching { regional.withinRegion(region) }.isFailure)
                rejectedMainLayers = repository.withinRegion(region).unavailableSources
            }
            check(rejectedMainLayers == setOf("live", "national", "regional")) { "Full-region device query must reject main-thread I/O" }
            check(national.regionStates().none { it.regionId == token })
            check(national.successfulSourceIds().none { it.contains(token) })
        } finally {
            live.writableDatabase.delete("cameras", "source_id=?", arrayOf(liveSource))
            national.writableDatabase.delete("national_cameras", "region_id=?", arrayOf("seed:$token"))
            regional.writableDatabase.delete("regional_cameras", "tile_id=?", arrayOf(token))
            live.close(); national.close(); regional.close()
        }
        return "PASS: real SQLite all-layer full-region device reads, inclusive corners/both signed180degree edges, distinct upstream identities and canonical Raven model preservation against newer weaker duplicates, frozen declared scope, outside rejection, all-layer overflow/immediate+mid-read cancellation, ${metadataRefusals} malformed/non-string/metadata-budget and ${typedRefusals} unknown-enum/confidence-range/storage-type refusals with restoration, valid OTHER/0/100 and preserved legacy map/corridor fallback, no live setup credit and all-layer main-thread guards; elapsedMs=${SystemClock.elapsedRealtime()-start}. Synthetic region, not real acquisition or application graph-region integration."
    }
}
