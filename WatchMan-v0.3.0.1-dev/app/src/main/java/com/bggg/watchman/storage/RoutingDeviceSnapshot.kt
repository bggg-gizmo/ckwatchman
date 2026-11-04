package com.bggg.watchman.storage

import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.routing.RouteAnalyzer
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.util.Collections

/** Immutable query result, not a certificate of regional/worldwide acquisition completeness.
 * Current route analysis counts each logical relevant device once: retain unit weights here.
 * Alert/classification priority is NOT a previously accepted routing exposure weight.
 */
class RoutingDeviceSnapshot private constructor(
    val entries: List<Entry>, val revision: String, val queryScope: String
) {
    data class Entry(val logicalId: String, val device: CameraPoi, val weight: Long)
    val exposurePolicyId: String get() = POLICY_ID
    val corridorMeters: Double get() = RouteAnalyzer.DEFAULT_CORRIDOR_METERS

    companion object {
        const val POLICY_ID = "validated-device-unit-count-180m-v1"
        fun freeze(mergedDevices: Collection<CameraPoi>, queryScope: String,
                   maximumDevices: Int = 50_000, maximumMetadataCharacters: Long = 8_000_000): RoutingDeviceSnapshot {
            require(queryScope.isNotBlank() && queryScope.length <= 4096)
            require(maximumDevices in 1..500_000 && maximumMetadataCharacters > 0)
            require(mergedDevices.size <= maximumDevices) { "Device snapshot exceeds explicit budget" }
            val seen = HashSet<String>()
            var characters = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            fun field(value: String?) {
                if (value == null) { digest.update(ByteBuffer.allocate(4).putInt(-1).array()); return }
                characters = Math.addExact(characters, value.length.toLong())
                require(characters <= maximumMetadataCharacters) { "Device metadata exceeds explicit budget" }
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
            }
            field(POLICY_ID); field(queryScope)
            val frozen = ArrayList<Entry>(mergedDevices.size)
            for (device in mergedDevices.sortedBy(DeviceIdentity::logicalKey)) {
                val id = DeviceIdentity.logicalKey(device)
                require(id.isNotBlank() && id.length <= 512 && seen.add(id)) { "Invalid or unmerged logical device identity" }
                require(device.point.lat.isFinite() && device.point.lat in -90.0..90.0 &&
                    device.point.lon.isFinite() && device.point.lon in -180.0..180.0) { "Invalid device coordinate" }
                require(device.tags.size <= 4096) { "Device tag count exceeds explicit budget" }
                val tags = Collections.unmodifiableMap(java.util.TreeMap(device.tags))
                val copy = device.copy(tags = tags)
                field(id); field(copy.id); field(copy.type.name)
                field(java.lang.Double.toHexString(copy.point.lat)); field(java.lang.Double.toHexString(copy.point.lon))
                field(copy.name); field(copy.road); field(copy.direction); field(copy.operator); field(copy.manufacturer)
                field(copy.source); field(copy.sourceId); field(copy.sourceUrl); field(copy.sourceRecordId)
                field(copy.confidence.toString()); field(copy.lastUpdatedMillis.toString()); field(tags.size.toString())
                tags.forEach { (key, value) -> field(key); field(value) }
                frozen += Entry(id, copy, if (RouteAnalyzer.isCameraOrSensorForRouting(copy)) 1L else 0L)
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            return RoutingDeviceSnapshot(Collections.unmodifiableList(frozen), hash, queryScope)
        }
    }
}
