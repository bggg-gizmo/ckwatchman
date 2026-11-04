package com.bggg.watchman.storage

import com.bggg.watchman.data.CameraPoi

/** The existing repository identity, shared with graph routing. Not proximity-based merging. */
object DeviceIdentity {
    fun logicalKey(device: CameraPoi): String {
        val osm = (device.sourceUrl ?: "").contains("openstreetmap.org", ignoreCase = true) ||
            device.id.startsWith("node/") || device.id.startsWith("way/") || device.id.startsWith("relation/")
        return if (osm && !device.sourceRecordId.isNullOrBlank()) {
            "osm:${device.sourceRecordId}"
        } else {
            "source:${device.sourceId}:${device.sourceRecordId ?: device.id}"
        }
    }
}
