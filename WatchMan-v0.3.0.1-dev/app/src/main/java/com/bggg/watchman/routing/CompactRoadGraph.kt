package com.bggg.watchman.routing

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.Collections

/**
 * Worker-owned, read-only compact storage adapter. Adjacency is NOT driving permission.
 * This reader is not yet wired into app route selection. A sourceRoutingReady=false dataset
 * remains development input; opening it does not waive route, phone-resource or release gates.
 */
class CompactRoadGraph private constructor(
    private val database: SQLiteDatabase, private val file: File, private val bytes: Long,
    private val modified: Long, private val contentHash: String,
    val sourceTopologyHash: String, val sourceRoutingReady: Boolean, val counts: Map<String, Long>
) : MotorcarGraphTraversal.Graph, TurnRelationCompiler.Geometry, Closeable {
    data class Candidate(val id: Long, val lengthMm: Long)
    data class Coordinate(val lonE7: Int, val latE7: Int)
    data class Segment(val id: Long, val wayId: Long, val from: Long, val to: Long,
        val fromPoint: RoadSnapGeometry.Point, val toPoint: RoadSnapGeometry.Point, val lengthMm: Long)
    data class RawRelation(val id: Long, val tags: Map<String, String>, val members: List<TurnRelationCompiler.Member>)
    data class CacheState(val tagEntries: Int, val estimatedTagBytes: Int, val wayEntries: Int, val wayReferences: Int,
        val arcEntries: Int = 0, val estimatedArcBytes: Int = 0, val nodeEntries: Int = 0,
        val estimatedNodeBytes: Int = 0, val arcHits: Long = 0, val nodeHits: Long = 0,
        val arcSqlReads: Long = 0, val nodeSqlReads: Long = 0)
    private data class TagEntry(val value: Map<String, String>, val weight: Int)
    private data class LookupEntry<T>(val value: T, val weight: Int)
    private val owner = Thread.currentThread()
    private var closed = false
    private val tags = LinkedHashMap<Long, TagEntry>(64, 0.75f, true)
    private val ways = LinkedHashMap<Long, TurnRelationCompiler.Way>(64, 0.75f, true)
    private val arcs = LinkedHashMap<Long, LookupEntry<MotorcarGraphTraversal.Arc>>(64, 0.75f, true)
    private val nodes = LinkedHashMap<Long, LookupEntry<MotorcarGraphTraversal.Node>>(64, 0.75f, true)
    private var tagWeight = 0
    private var wayReferences = 0
    private var arcWeight = 0
    private var nodeWeight = 0
    private var arcHits = 0L
    private var nodeHits = 0L
    private var arcSqlReads = 0L
    private var nodeSqlReads = 0L
    /** Nested relation geometry is not expanded; even existing nested IDs are unresolved here. */
    private val globalUnresolved = read("SELECT relation_id FROM unscoped_relations UNION " +
        "SELECT relation_id FROM members WHERE member_type='r' ORDER BY 1 LIMIT 100001") { cursor ->
        val ids = linkedSetOf<Long>()
        while (cursor.moveToNext()) { check(ids.size < 100_000) { "Global relation guard exceeds budget" }; ids += cursor.getLong(0) }
        ids.toSet()
    }

    private fun ready() {
        checkWorker()
        check(Thread.currentThread() === owner) { "Compact graph must stay on its owning worker" }
        check(!closed) { "Compact graph is closed" }
        check(file.length() == bytes && file.lastModified() == modified) { "Compact graph changed; reopen a verified revision" }
    }

    private fun <T> read(sql: String, args: Array<String> = emptyArray(), block: (Cursor) -> T): T {
        ready()
        val result = database.rawQuery(sql, args).use(block)
        ready()
        return result
    }

    override val revision: String get() { ready(); return contentHash }
    fun cacheState(): CacheState { ready(); return CacheState(tags.size, tagWeight, ways.size, wayReferences,
        arcs.size, arcWeight, nodes.size, nodeWeight, arcHits, nodeHits, arcSqlReads, nodeSqlReads) }
    fun globalUnresolvedRelationIds(): Set<Long> { ready(); return globalUnresolved.toSet() }

    private fun tags(id: Long): Map<String, String> {
        ready()
        tags[id]?.let { ready(); return it.value }
        val raw = read("SELECT raw FROM tags WHERE id=?", arrayOf(id.toString())) {
            check(it.moveToFirst()) { "Missing tag dictionary entry $id" }; it.getString(0)
        }
        check(raw.length <= 1024 * 1024) { "Tag dictionary entry exceeds bound" }
        val value = JSONObject(raw)
        check(value.length() <= 4096) { "Tag dictionary key count exceeds bound" }
        val parsed = LinkedHashMap<String, String>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            parsed[key] = value.get(key) as? String ?: error("Non-string source tag")
        }
        val frozen = Collections.unmodifiableMap(parsed)
        val weight = raw.length * 2 + parsed.size * 64
        if (weight <= TAG_CACHE_BYTES) {
            tags[id] = TagEntry(frozen, weight)
            tagWeight += weight
            while (tagWeight > TAG_CACHE_BYTES || tags.size > 512) {
                val first = tags.entries.iterator(); val removed = first.next(); tagWeight -= removed.value.weight; first.remove()
            }
        }
        return frozen
    }

    /** Charge retained strings/map/list/reference overhead independently of the tag dictionary.
     * Dictionary eviction must not let cached source objects retain unbudgeted metadata. Shared
     * immutable maps are deliberately charged more than once; this is an estimate, not heap QA.
     */
    private fun retainedWeight(sourceTags: Map<String, String>, unresolved: List<String>): Long =
        384L + sourceTags.entries.sumOf { (it.key.length.toLong() + it.value.length) * 2L + 192L } +
            unresolved.sumOf { it.length.toLong() * 2L + 96L }

    private fun <T> retain(cache: LinkedHashMap<Long, LookupEntry<T>>, id: Long, value: T,
        weight: Long, currentWeight: Int): Int {
        if (weight > LOOKUP_CACHE_BYTES) return currentWeight
        var total = currentWeight
        cache.put(id, LookupEntry(value, weight.toInt()))?.let { total -= it.weight }
        total += weight.toInt()
        while (total > LOOKUP_CACHE_BYTES || cache.size > LOOKUP_CACHE_ENTRIES) {
            val first = cache.entries.iterator(); val removed = first.next()
            total -= removed.value.weight; first.remove()
        }
        return total
    }

    override fun node(id: Long): MotorcarGraphTraversal.Node? {
        ready()
        nodes[id]?.let {
            if (nodeHits < Long.MAX_VALUE) nodeHits++
            ready(); return it.value
        }
        if (nodeSqlReads < Long.MAX_VALUE) nodeSqlReads++
        val value = read("SELECT tag_id FROM nodes WHERE osm_id=?", arrayOf(id.toString())) {
            if (it.moveToFirst()) MotorcarGraphTraversal.Node(id, tags(it.getLong(0))) else null
        } ?: return null
        nodeWeight = retain(nodes, id, value, retainedWeight(value.tags, value.unresolved), nodeWeight)
        ready(); return value
    }

    fun coordinate(id: Long): Coordinate? = read("SELECT lon_e7,lat_e7 FROM nodes WHERE osm_id=?", arrayOf(id.toString())) {
        if (!it.moveToFirst()) null else Coordinate(it.getInt(0), it.getInt(1)).also { point ->
            check(point.lonE7 in -1_800_000_000..1_800_000_000 && point.latE7 in -900_000_000..900_000_000) { "Invalid E7 coordinate" }
        }
    }

    override fun arc(id: Long): MotorcarGraphTraversal.Arc? {
        require(id > 0) { "Positive candidate arc identity required" }
        ready()
        arcs[id]?.let {
            if (arcHits < Long.MAX_VALUE) arcHits++
            ready(); return it.value
        }
        if (arcSqlReads < Long.MAX_VALUE) arcSqlReads++
        val sid = (id - 1) / 2 + 1
        val forward = id % 2 == 1L
        val value = read("SELECT w.osm_id,a.osm_id,b.osm_id,w.tag_id FROM segments s JOIN ways w ON w.wid=s.wid " +
            "JOIN nodes a ON a.nid=s.from_nid JOIN nodes b ON b.nid=s.to_nid WHERE s.sid=?", arrayOf(sid.toString())) {
            if (!it.moveToFirst()) null else MotorcarGraphTraversal.Arc(id, it.getLong(0),
                it.getLong(if (forward) 1 else 2), it.getLong(if (forward) 2 else 1),
                if (forward) MotorcarTagPolicy.Direction.FORWARD else MotorcarTagPolicy.Direction.BACKWARD, tags(it.getLong(3)))
        } ?: return null
        arcWeight = retain(arcs, id, value, retainedWeight(value.tags, value.unresolved), arcWeight)
        ready(); return value
    }

    fun lengthMm(id: Long): Long? {
        require(id > 0)
        return read("SELECT length_mm FROM segments WHERE sid=?", arrayOf(((id - 1) / 2 + 1).toString())) {
            if (!it.moveToFirst()) null else it.getLong(0).also { length -> check(length >= 0) { "Negative stored length" } }
        }
    }

    /** One-query original geometry for spatial candidates; no coordinate-based node merging. */
    fun segment(id: Long): Segment? {
        require(id in 1..Long.MAX_VALUE / 2)
        return read("SELECT w.osm_id,a.osm_id,b.osm_id,a.lon_e7,a.lat_e7,b.lon_e7,b.lat_e7,s.length_mm " +
            "FROM segments s JOIN ways w ON w.wid=s.wid JOIN nodes a ON a.nid=s.from_nid " +
            "JOIN nodes b ON b.nid=s.to_nid WHERE s.sid=?", arrayOf(id.toString())) {
            if (!it.moveToFirst()) null else Segment(id,it.getLong(0),it.getLong(1),it.getLong(2),
                RoadSnapGeometry.Point(it.getInt(3)/1e7,it.getInt(4)/1e7),
                RoadSnapGeometry.Point(it.getInt(5)/1e7,it.getInt(6)/1e7),it.getLong(7)).also { value ->
                check(value.wayId > 0 && value.from > 0 && value.to > 0 && value.from != value.to && value.lengthMm >= 0)
            }
        }
    }

    /** Bounded indexed adjacency; exceeding the limit fails instead of omitting legal alternatives. */
    fun outgoing(nodeId: Long, maximum: Int = 4096): List<Candidate> {
        require(maximum in 1..65536)
        val nid = read("SELECT nid FROM nodes WHERE osm_id=?", arrayOf(nodeId.toString())) {
            check(it.moveToFirst()) { "Missing adjacency node $nodeId" }; it.getLong(0)
        }
        return read("SELECT sid,1,length_mm FROM segments WHERE from_nid=? UNION ALL " +
            "SELECT sid,0,length_mm FROM segments WHERE to_nid=? ORDER BY 1,2 LIMIT ?",
            arrayOf(nid.toString(), nid.toString(), (maximum + 1).toString())) { cursor ->
            val result = ArrayList<Candidate>()
            while (cursor.moveToNext()) {
                check(result.size < maximum) { "Adjacency exceeds explicit budget; no candidates may be truncated" }
                val sid = cursor.getLong(0)
                check(sid in 1..Long.MAX_VALUE / 2) { "Invalid segment identity" }
                val length = cursor.getLong(2); check(length >= 0)
                result += Candidate(sid * 2 - if (cursor.getInt(1) == 1) 1 else 0, length)
            }
            result.sortedBy { it.id }
        }
    }

    override fun way(id: Long): TurnRelationCompiler.Way? {
        ready()
        ways[id]?.let { ready(); return it }
        val refs = read("SELECT node_count,node_refs FROM ways WHERE osm_id=?", arrayOf(id.toString())) {
            if (!it.moveToFirst()) null else PackedNodeReferences.decode(it.getBlob(1), it.getInt(0))
        } ?: return null
        val global = ArrayList<Long>(refs.size)
        var position = 0
        while (position < refs.size) {
            val end = minOf(position + 400, refs.size)
            val batch = (position until end).map { refs[it].toString() }.distinct()
            val mapping = read("SELECT nid,osm_id FROM nodes WHERE nid IN (${batch.joinToString { "?" }})", batch.toTypedArray()) { cursor ->
                val values = HashMap<Int, Long>()
                while (cursor.moveToNext()) values[cursor.getInt(0)] = cursor.getLong(1)
                values
            }
            for (index in position until end) global += checkNotNull(mapping[refs[index]]) { "Unresolved local geometry node ${refs[index]}" }
            position = end
        }
        val value = TurnRelationCompiler.Way(id, Collections.unmodifiableList(global))
        if (refs.size <= WAY_CACHE_REFERENCES) {
            ways[id] = value; wayReferences += refs.size
            while (wayReferences > WAY_CACHE_REFERENCES || ways.size > 128) {
                val first = ways.entries.iterator(); val removed = first.next(); wayReferences -= removed.value.nodes.size; first.remove()
            }
        }
        return value
    }

    override fun arc(wayId: Long, segmentOrdinal: Int, forward: Boolean): Long? {
        require(segmentOrdinal >= 0)
        return read("SELECT s.sid FROM segments s JOIN ways w ON w.wid=s.wid WHERE w.osm_id=? AND s.ordinal=?",
            arrayOf(wayId.toString(), segmentOrdinal.toString())) {
            if (!it.moveToFirst()) null else it.getLong(0).let { sid ->
                check(sid in 1..Long.MAX_VALUE / 2); sid * 2 - if (forward) 1 else 0
            }
        }
    }

    fun relation(id: Long): RawRelation? {
        val rawTags = read("SELECT tag_id FROM relations WHERE id=?", arrayOf(id.toString())) {
            if (it.moveToFirst()) tags(it.getLong(0)) else null
        } ?: return null
        val members = read("SELECT ordinal,member_type,member_id,role FROM members WHERE relation_id=? ORDER BY ordinal LIMIT 257", arrayOf(id.toString())) {
            val result = ArrayList<TurnRelationCompiler.Member>()
            while (it.moveToNext()) {
                check(result.size < 256 && it.getInt(0) == result.size) { "Invalid/excessive relation members $id" }
                val type = it.getString(1); check(type in setOf("n", "w", "r"))
                result += TurnRelationCompiler.Member(type.single(), it.getLong(2), it.getString(3))
            }
            result.toList()
        }
        return RawRelation(id, rawTags, members)
    }

    fun compileTurnRelations(maximumRelations: Int = 10000, maximumPathArcs: Int = 250000): List<MotorcarGraphTraversal.Relation> {
        require(maximumRelations in 1..100000 && maximumPathArcs in 1..4000000)
        check(counts.getValue("relations") <= maximumRelations) { "Relation catalogue requires a smaller region or explicit larger budget" }
        val ids = read("SELECT id FROM relations ORDER BY id LIMIT ?", arrayOf((maximumRelations + 1).toString())) {
            val result = ArrayList<Long>(); while (it.moveToNext()) { check(result.size < maximumRelations); result += it.getLong(0) }; result
        }
        val result = ArrayList<MotorcarGraphTraversal.Relation>()
        var pathArcs = 0L
        var rawBytes = 0L
        for (id in ids) {
            val source = checkNotNull(relation(id))
            if (!isTurn(source.tags)) continue
            rawBytes += source.tags.entries.sumOf { (it.key.length + it.value.length) * 2L + 64 }
            check(rawBytes <= 8 * 1024 * 1024) { "Turn catalogue source metadata exceeds budget" }
            val values = linkedSetOf<String>()
            for ((key, value) in source.tags) if (key == "restriction" || key.startsWith("restriction:")) {
                if (key.endsWith(":conditional")) {
                    val parsed = ConditionalRestriction.parse(value)
                    if (parsed.problem == null) values += parsed.clauses.map { it.value }
                } else values += value
                check(values.size <= 256) { "Relation variant count exceeds budget" }
            }
            val compiled = values.filter { it != "none" }.map { value ->
                TurnRelationCompiler.compile(id, value, source.members, this).also {
                    pathArcs += it.paths.sumOf { path -> path.size.toLong() }
                    check(pathArcs <= maximumPathArcs) { "Compiled turn paths exceed budget; no rule may be discarded" }
                }
            }
            result += MotorcarGraphTraversal.Relation(id, source.tags, compiled)
        }
        return result
    }

    override fun turnCoverage(arc: MotorcarGraphTraversal.Arc): MotorcarGraphTraversal.Coverage {
        check(this.arc(arc.id) == arc) { "Coverage requested for noncanonical arc" }
        val ids = read("SELECT DISTINCT s.relation_id FROM relation_scope s JOIN nodes n ON n.nid=s.nid " +
            "WHERE n.osm_id IN (?,?) ORDER BY s.relation_id LIMIT 4097", arrayOf(arc.from.toString(), arc.to.toString())) {
            val result = linkedSetOf<Long>()
            while (it.moveToNext()) { check(result.size < 4096) { "Relation scope exceeds budget" }; result += it.getLong(0) }
            result
        }
        val turnIds = linkedSetOf<Long>()
        val problems = ArrayList<String>()
        if (globalUnresolved.isNotEmpty()) problems += "Unscoped/nested source constraints remain unresolved: ${globalUnresolved.size} relations; inspect globalUnresolvedRelationIds"
        for (id in ids) {
            val source = relation(id) ?: error("Missing scoped relation $id")
            if (isTurn(source.tags)) turnIds += id
            else problems += "Non-turn source relation $id requires ferry/barrier/profile handling"
        }
        return MotorcarGraphTraversal.Coverage(problems.isEmpty(), turnIds, problems)
    }

    override fun close() {
        checkWorker(); check(Thread.currentThread() === owner)
        if (closed) return
        closed = true
        tags.clear(); ways.clear(); arcs.clear(); nodes.clear()
        tagWeight = 0; wayReferences = 0; arcWeight = 0; nodeWeight = 0
        database.close()
    }

    companion object {
        private const val TAG_CACHE_BYTES = 2 * 1024 * 1024
        private const val WAY_CACHE_REFERENCES = 20000
        private const val LOOKUP_CACHE_BYTES = 1024 * 1024
        private const val LOOKUP_CACHE_ENTRIES = 512
        private fun checkWorker() { check(Looper.myLooper() != Looper.getMainLooper()) { "Compact graph I/O is forbidden on the main thread" } }
        private fun isTurn(tags: Map<String, String>) = tags["type"].orEmpty().startsWith("restriction") ||
            tags.keys.any { it == "restriction" || it.startsWith("restriction:") }
        private fun sameJson(a: Any?, b: Any?, depth: Int = 0): Boolean {
            check(depth <= 32) { "Metadata nesting exceeds bound" }
            return when {
                a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet() == b.keys().asSequence().toSet() &&
                    a.keys().asSequence().all { sameJson(a.get(it), b.get(it), depth + 1) }
                a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { sameJson(a.get(it), b.get(it), depth + 1) }
                else -> a == b
            }
        }
        private fun boundedText(file: File): String = file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                check(output.size() + count <= 2 * 1024 * 1024) { "Graph manifest exceeds2MiB" }
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }

        /** expectedSha256 must come from the trusted package/evidence catalogue, not the file itself. */
        fun open(directory: File, expectedSha256: String, maximumBytes: Long = 256L * 1024 * 1024): CompactRoadGraph {
            checkWorker()
            require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}")))
            require(maximumBytes in 1..1024L * 1024 * 1024)
            val root = directory.canonicalFile
            val file = File(root, "graph.sqlite").canonicalFile
            check(file.parentFile == root && file.isFile) { "Invalid graph path" }
            val bytes = file.length(); val modified = file.lastModified()
            check(bytes in 1..maximumBytes) { "Graph exceeds storage budget" }
            val manifestFile = File(root, "manifest.json").canonicalFile
            check(manifestFile.parentFile == root) { "Invalid manifest path" }
            val manifest = JSONObject(boundedText(manifestFile))
            check(manifest.getString("format") == "watchman-compact-road-graph/1") { "Unsupported compact graph format" }
            check(manifest.getLong("live_setup_completion_credit") == 0L) { "Compact seed cannot earn live completion credit" }
            val record = manifest.getJSONObject("database")
            check(record.getString("file") == "graph.sqlite" && record.getLong("bytes") == bytes)
            check(record.getString("sha256").equals(expectedSha256, true)) { "Untrusted graph hash" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(actual.equals(expectedSha256, true) && file.length() == bytes && file.lastModified() == modified) { "Graph integrity/revision check failed" }
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            try {
                check(db.isReadOnly && db.version == 1) { "Graph database must be read-only schema1" }
                db.execSQL("PRAGMA cache_size=-4096")
                db.setMaxSqlCacheSize(32)
                db.rawQuery("PRAGMA quick_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext()) }
                db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) { "Graph foreign-key check failed" } }
                val embedded = db.rawQuery("SELECT value FROM metadata WHERE key='manifest'", null).use {
                    check(it.moveToFirst()); val raw = it.getString(0); check(raw.length <= 2 * 1024 * 1024); JSONObject(raw)
                }
                manifest.remove("database")
                check(sameJson(embedded, manifest)) { "Embedded/external graph metadata differ" }
                val inventory = linkedMapOf<String, Long>()
                val rawCounts = manifest.getJSONObject("counts")
                val tables = listOf("nodes", "ways", "segments", "relations", "members", "tags", "stamps", "relation_scope", "unresolved_members", "unscoped_relations")
                check(rawCounts.keys().asSequence().toSet() == tables.toSet()) { "Unexpected graph inventory" }
                for (table in tables) {
                    val count = rawCounts.get(table).toString().toLongOrNull() ?: error("Invalid inventory count")
                    check(count >= 0)
                    db.rawQuery("SELECT COUNT(*) FROM $table", null).use { check(it.moveToFirst() && it.getLong(0) == count) { "Graph inventory mismatch: $table" } }
                    inventory[table] = count
                }
                check(inventory.getValue("nodes") <= Int.MAX_VALUE && inventory.getValue("relations") <= 100000)
                check(inventory.getValue("segments") in 1..Long.MAX_VALUE / 2)
                check(manifest.getLong("candidate_directed_arcs") == inventory.getValue("segments") * 2)
                val source = manifest.getString("source_topology_sha256")
                check(source.matches(Regex("[a-fA-F0-9]{64}")))
                return CompactRoadGraph(db, file, bytes, modified, actual, source, manifest.getBoolean("routing_ready"),
                    Collections.unmodifiableMap(inventory))
            } catch (failure: Throwable) { db.close(); throw failure }
        }
    }
}
