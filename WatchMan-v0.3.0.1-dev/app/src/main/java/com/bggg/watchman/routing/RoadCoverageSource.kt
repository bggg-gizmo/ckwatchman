package com.bggg.watchman.routing

import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.ProviderConfig
import com.bggg.watchman.data.Route
import com.bggg.watchman.net.HttpClient
import com.bggg.watchman.storage.RouteIdentity
import com.bggg.watchman.storage.RoutingDeviceSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Worker-only, bounded source observations. Missing coverage is UNKNOWN, never zero devices.
 * Official OSM reads contain public camera/road identities, not the user's GPS or address.
 * Exact route attribution goes only to the existing configured routing provider. This reader
 * establishes neither lawful vehicle access nor physical FOV/unavoidability.
 */
class RoadCoverageSource(
    cacheDirectory: File,
    private val get: (String, Int) -> String = { url, ms ->
        String(HttpClient.getBytes(url, ms, 1_000_000, "application/json"), Charsets.UTF_8)
    },
    private val trace: (String, Int) -> String = { body, ms ->
        String(HttpClient.postJsonBytes("${ProviderConfig.OBSTACLE_ROUTING_BASE}/trace_attributes", body, ms,
            2_000_000, mapOf("X-Client-Id" to "bggg-gizmo/ckwatchman")), Charsets.UTF_8)
    },
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = { System.nanoTime()/1_000_000L },
    private val pause: (Long) -> Unit = Thread::sleep
) {
    /** Exact successfully observed public node body and ORIGINAL receipt time. App acquisition
     * may normalize relevant devices additively; ordinary way vertices are not cameras.
     */
    data class NodeReceipt(val id: Long, val rawJson: String, val receivedAtMillis: Long)
    data class Result(val observation: RoadAwareExposure.Observation?, val reason: String?,
        val traceRequests: Int, val metadataRequests: Int, val sourceNodes: List<NodeReceipt> = emptyList(),
        /** Exact edge layout only; no parents/source geometry and therefore NO exemptions. */
        val attribution: RoadAwareExposure.Observation? = null)
    private data class Receipt(val body: String, val receivedAt: Long)
    private val cache = Cache(cacheDirectory)
    private val pacingLock = Any()
    private var lastApiStart: Long? = null

    fun observe(route: Route, snapshot: RoutingDeviceSnapshot, remainingMillis: Long,
        allowNetwork: Boolean = true, cancelled: () -> Boolean = { false },
        onTraceRequest: () -> Unit = {}, onMetadataRequest: () -> Unit = {}): Result {
        val start = monotonic()
        var traceRequests = 0
        var metadataRequests = 0
        var chargedBytes = 0L
        var oldest = Long.MAX_VALUE
        val receipts = HashMap<String, Receipt>()
        var attribution: RoadAwareExposure.Observation? = null
        fun active() {
            check(!cancelled() && !Thread.currentThread().isInterrupted) { "Road coverage cancelled" }
            val elapsed = monotonic()-start
            check(remainingMillis in 1..25000 && elapsed >= 0 && elapsed < remainingMillis) { "Road coverage deadline reached" }
        }
        fun timeout(maximum: Long): Int {
            active()
            val left = remainingMillis-(monotonic()-start)
            check(left >= 1000) { "Insufficient road-coverage request time" }
            return minOf(left,maximum).toInt()
        }
        fun charge(key: String, r: Receipt): Receipt {
            val previous=receipts[key]
            if (previous==null || r.receivedAt>previous.receivedAt) {
                chargedBytes = Math.addExact(chargedBytes,r.body.toByteArray(Charsets.UTF_8).size.toLong()-
                    (previous?.body?.toByteArray(Charsets.UTF_8)?.size ?: 0))
                check(chargedBytes <= 8_000_000) { "Road metadata processing budget exceeded" }
                receipts[key]=r
            }
            oldest=minOf(oldest,r.receivedAt)
            active()
            return r
        }
        fun apiPace() {
            while (true) {
                active()
                val delay = synchronized(pacingLock) {
                    val now=monotonic()
                    val previous=lastApiStart
                    if (previous==null || now-previous>=600) { lastApiStart=now; 0L }
                    else 600-(now-previous)
                }
                if (delay<=0) return
                pause(minOf(delay,100L))
            }
        }
        fun load(key: String, path: String? = null, traceBody: String? = null): Receipt {
            active()
            receipts[key]?.let { return it }
            cache.read(key,wallTime())?.let { return charge(key,it) }
            check(allowNetwork) { "Road coverage is not cached" }
            val response = if (traceBody!=null) {
                check(traceRequests==0) { "Attribution request budget exceeded" }
                onTraceRequest(); traceRequests++
                trace(traceBody,timeout(8000))
            } else {
                check(metadataRequests<32) { "Metadata request budget exceeded" }
                apiPace(); onMetadataRequest(); metadataRequests++
                get("$OSM_BASE/${checkNotNull(path)}",timeout(4000))
            }
            check(response.toByteArray(Charsets.UTF_8).size <= if(traceBody==null) 1_000_000 else 2_000_000) {
                "Road source response byte budget exceeded"
            }
            active()
            val parsed=JSONObject(response)
            check(!parsed.has("error") && !parsed.has("remark")) { "Source response is incomplete" }
            if(traceBody==null) parsed.getJSONArray("elements") else {
                parsed.get("shape"); parsed.getJSONArray("edges")
            }
            val r=Receipt(response,wallTime())
            cache.write(key,r)
            return charge(key,r)
        }
        fun elements(receipt: Receipt): List<JSONObject> {
            val root=JSONObject(receipt.body)
            check(!root.has("error") && !root.has("remark")) { "Road metadata source returned an incomplete result" }
            val rows=root.getJSONArray("elements")
            check(rows.length()<=22048)
            return (0 until rows.length()).map { i ->
                if(i%64==0) active()
                rows.getJSONObject(i).also { check(it.optBoolean("visible",true)) { "Deleted source element" } }
            }
        }
        fun child(kind: String,e: JSONObject,parent: Receipt): Receipt {
            val key="$kind/${id(e.get("id"))}"
            val r=Receipt(JSONObject().put("elements",JSONArray().put(e)).toString(),parent.receivedAt)
            val newer=cache.read(key,wallTime())?.takeIf { it.receivedAt>=r.receivedAt } ?: r.also { cache.write(key,it) }
            return charge(key,newer)
        }
        fun batch(kind: String,ids: Set<Long>): Map<Long,JSONObject> {
            val result=LinkedHashMap<Long,JSONObject>()
            val missing=ArrayList<Long>()
            for(n in ids.sorted()) {
                active()
                val r=receipts["$kind/$n"] ?: cache.read("$kind/$n",wallTime())
                if(r==null) missing+=n else {
                    val e=elements(charge("$kind/$n",r)).single()
                    check(e.getString("type")==kind && id(e.get("id"))==n)
                    result[n]=e
                }
            }
            for(group in missing.chunked(350)) {
                val plural="${kind}s"
                val path="$plural.json?$plural=${group.joinToString(",")}"
                val r=load("batch/$path",path)
                val rows=try {
                    elements(r).also { rows ->
                        check(rows.size==group.size && rows.map { id(it.get("id")) }.toSet()==group.toSet()) { "Incomplete source batch" }
                    }
                } catch(error: Exception) { cache.invalidate("batch/$path"); throw error }
                for(e in rows) {
                    check(e.getString("type")==kind)
                    val actual=elements(child(kind,e,r)).single()
                    result[id(actual.get("id"))]=actual
                }
            }
            return result
        }
        return try {
            active()
            check(route.provider=="Valhalla camera exclusions") { "Exact attribution requires the original Valhalla route" }
            check(route.points.size in 2..16000) { "Candidate exceeds this attribution provider's shape budget" }
            val index=RouteProximityIndex(route,snapshot.corridorMeters,::active)
            val signalIds=LinkedHashSet<Long>()
            for(entry in snapshot.entries) {
                active()
                val camera=entry.device
                if(entry.weight<=0 || camera.type!=CameraType.TRAFFIC || camera.tags["highway"]!="traffic_signals") continue
                val n=camera.sourceRecordId?.takeIf { NODE_RECORD.matches(it) }?.substringAfter('/')?.toLongOrNull() ?: continue
                val segments=index.withinSegments(camera.point)
                if(segments.isNotEmpty()) signalIds+=n
            }
            check(signalIds.isNotEmpty() && signalIds.size<=512) { "No supported source-road query within budget" }
            val body=JSONObject().put("shape",JSONArray().apply { route.points.forEach {
                put(JSONObject().put("lat",it.lat).put("lon",it.lon))
            } }).put("shape_match","edge_walk").put("costing","auto").put("shape_format","polyline6")
                .put("costing_options",JSONObject().put("auto",JSONObject().put("ignore_restrictions",false)
                    .put("ignore_oneways",false).put("ignore_access",false)))
                .put("filters",JSONObject().put("action","include").put("attributes",JSONArray(listOf(
                    "edge.way_id","edge.begin_shape_index","edge.end_shape_index","shape","osm_changeset")))).toString()
            val traceKey="trace/${RouteIdentity.sha256(route)}"
            val t=load(traceKey,traceBody=body)
            val layout=try {
                RoadExposureObservationJson.decode(t.body,"{\"elements\":[]}",emptySet(),t.receivedAt,t.receivedAt+TTL,::active)
                    .also { check(RoadAwareExposure.verify(route,it,wallTime(),::active)!=null) { "Exact attribution does not cover this candidate" } }
            } catch(error: Exception) { cache.invalidate(traceKey); throw error }
            attribution=layout
            val wantedWays=HashSet<Long>()
            val completeSignals=HashSet<Long>()
            val coveredSegments=HashSet<Int>()
            for(n in signalIds) {
                val parentKey="parents/$n"
                // Cache-only recovery may keep valid coverage for previously observed
                // devices while a newly acquired device remains conservative UNKNOWN.
                if(!allowNetwork && cache.read(parentKey,wallTime())==null) continue
                val r=load(parentKey,"node/$n/ways.json")
                val parents=try { elements(r).also { rows -> rows.forEach { e ->
                    check(e.getString("type")=="way")
                    val refs=e.getJSONArray("nodes")
                    check((0 until refs.length()).any { id(refs.get(it))==n }) { "Unbound source parent response" }
                } } } catch(error: Exception) { cache.invalidate(parentKey); throw error }
                for(e in parents) {
                    wantedWays+=id(e.get("id"))
                    check(wantedWays.size<=2048) { "Parent-way metadata budget exceeded" }
                    child("way",e,r)
                }
                completeSignals+=n
                snapshot.entries.filter { it.device.sourceRecordId=="node/$n" }.forEach {
                    coveredSegments.addAll(index.withinSegments(it.device.point))
                }
            }
            check(completeSignals.isNotEmpty()) { "No fresh parent-road coverage is cached" }
            val routeWays=layout.spans.filter { span -> (span.first until span.last).any { it in coveredSegments } }
                .mapTo(HashSet()) { it.wayId }
            check(routeWays.size<=512)
            wantedWays.addAll(routeWays)
            check(wantedWays.size<=2048)
            val ways=batch("way",wantedWays)
            val wantedNodes=HashSet(completeSignals)
            for(way in ways.values) {
                active()
                val refs=way.getJSONArray("nodes")
                check(refs.length() in 2..100000)
                for(i in 0 until refs.length()) {
                    if(i%128==0) active()
                    wantedNodes+=id(refs.get(i))
                    check(wantedNodes.size<=12000) { "Source coordinate budget exceeded" }
                }
            }
            val nodes=batch("node",wantedNodes)
            val source=JSONObject().put("provider","osm_api_0.6")
                .put("observationModel","Per-object provider receipts; not an atomic planet snapshot or access certificate")
                .put("elements",JSONArray().apply {
                    nodes.toSortedMap().values.forEach { put(it) }; ways.toSortedMap().values.forEach { put(it) }
                }).toString()
            val observation=RoadExposureObservationJson.decode(t.body,source,completeSignals,oldest,oldest+TTL,::active)
            active()
            val sourceNodes=nodes.toSortedMap().map { (n,value) ->
                NodeReceipt(n,value.toString(),checkNotNull(receipts["node/$n"]).receivedAt)
            }
            Result(observation,null,traceRequests,metadataRequests,java.util.Collections.unmodifiableList(sourceNodes),attribution)
        } catch(error: Exception) {
            if(error is InterruptedException) Thread.currentThread().interrupt()
            Result(null,(error.message ?: error.javaClass.simpleName).replace('\n',' ').replace('\r',' ').take(240),
                traceRequests,metadataRequests,attribution=attribution)
        } finally {
            // One snapshot write, not hundreds of per-record directory scans/fsync calls.
            // Partial successful reads remain available for the next finite attempt.
            if(allowNetwork && !cancelled() && !Thread.currentThread().isInterrupted) cache.flush()
        }
    }

    private class Cache(directory: File) {
        private val root=directory.canonicalFile
        private class State {
            val entries=LinkedHashMap<String,Receipt>(16,0.75f,true)
            var weight=0L
            var loaded=false
            var dirty=false
        }
        private val state=synchronized(STATES) {
            STATES.entries.removeAll { it.value.get()==null }
            STATES[root.path]?.get() ?: State().also { STATES[root.path]=WeakReference(it) }
        }
        fun read(key: String,now: Long): Receipt? = synchronized(state) {
            load()
            val r=state.entries[key] ?: return@synchronized null
            if(now<r.receivedAt || now-r.receivedAt>=TTL) {
                state.entries.remove(key); state.weight-=weight(r); state.dirty=true
                return@synchronized null
            }
            r
        }
        fun write(key: String,r: Receipt) = synchronized(state) {
            load(); retain(key,r); state.dirty=true
        }
        fun invalidate(key: String) = synchronized(state) {
            state.entries.remove(key)?.let { state.weight-=weight(it); state.dirty=true }
        }
        fun flush() = synchronized(state) {
            if(!state.dirty) return@synchronized
            runCatching {
                check(root.isDirectory || root.mkdirs())
                val records=JSONArray()
                state.entries.forEach { (key,r) -> records.put(JSONObject().put("key",key).put("receivedAt",r.receivedAt)
                    .put("sha256",hash(r.body)).put("body",r.body)) }
                val payload=JSONObject().put("format",1).put("records",records).toString().toByteArray(Charsets.UTF_8)
                check(payload.size<=8_000_000 && root.usableSpace>=32_000_000+payload.size)
                val target=file()
                val temporary=File(root,".road-coverage-${java.util.UUID.randomUUID()}.tmp")
                try {
                    FileOutputStream(temporary).use { it.write(payload); it.fd.sync() }
                    try { Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
                    catch(_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
                    }
                    state.dirty=false
                } finally {
                    if(temporary.exists()) temporary.delete()
                }
            }
        }
        private fun load() {
            if(state.loaded) return
            state.loaded=true
            runCatching {
                val file=file()
                check(file.isFile && file.length()<=8_000_000)
                val json=JSONObject(file.readText(Charsets.UTF_8))
                check(json.getInt("format")==1)
                val records=json.getJSONArray("records")
                check(records.length()<=4096)
                for(i in 0 until records.length()) runCatching {
                    val entry=records.getJSONObject(i)
                    val key=entry.getString("key"); check(key.length<=8192)
                    val body=entry.getString("body")
                    check(body.length<=2_000_000 && entry.getString("sha256")==hash(body))
                    retain(key,Receipt(body,entry.getLong("receivedAt")))
                }
            }
        }
        private fun weight(r: Receipt)=r.body.length*2L+512
        private fun retain(key: String,r: Receipt) {
            state.entries.put(key,r)?.let { state.weight-=weight(it) }
            state.weight+=weight(r)
            val iterator=state.entries.iterator()
            while((state.weight>4_000_000 || state.entries.size>4096) && iterator.hasNext()) {
                state.weight-=weight(iterator.next().value); iterator.remove()
            }
        }
        private fun file()=File(root,"observations-v1.json").also { check(it.canonicalFile.parentFile==root) }
        companion object {
            private val STATES=HashMap<String,WeakReference<State>>()
        }
    }
    companion object {
        private const val TTL=3_600_000L
        private const val OSM_BASE="https://api.openstreetmap.org/api/0.6"
        private val NODE_RECORD=Regex("node/[1-9][0-9]{0,18}")
        private fun hash(s: String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun id(raw: Any): Long = when(raw) {
            is Int -> raw.toLong(); is Long -> raw; else -> error("Non-integral source identity")
        }.also { check(it>0) }
    }
}
