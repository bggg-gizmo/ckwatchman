package com.bggg.watchman.storage

import com.bggg.watchman.cameras.CameraClassifier
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route

/**
 * One logical device policy for routing, maps, navigation, alerts, history and exports.
 * Live data enriches the bundled/national baseline; an empty/failed live refresh cannot erase it.
 */
class AuthoritativeDeviceRepository(
    private val live: CameraDatabase,
    private val national: NationalCameraCache,
    private val regional: RegionalCameraCache? = null
) {
    data class Snapshot(
        val devices: List<CameraPoi>,
        val liveCount: Int,
        val nationalCount: Int,
        val regionalCount: Int = 0,
        val unavailableSources: Set<String> = emptySet()
    ) {
        val sourceSummary: String
            get() = "merged=${devices.size}, live=$liveCount, national=$nationalCount, regional=$regionalCount"

        /** A failed cache read is not a successful empty known-device snapshot. Normal map
         * callers may still use available additive layers; exact counts/search evidence may not.
         */
        fun requireAvailable(): Snapshot = apply {
            check(unavailableSources.isEmpty()) { "Device cache unavailable: ${unavailableSources.sorted().joinToString()}" }
        }

        /** Caller describes the actual queried search region, not merely a baseline-route corridor.
         * This freezes observed data only; acquisition completeness must be established separately.
         */
        fun freezeForRouting(queryScope: String): RoutingDeviceSnapshot =
            RoutingDeviceSnapshot.freeze(requireAvailable().devices, queryScope)
    }

    fun nearby(point: GeoPoint, radiusMeters: Double): Snapshot {
        val liveResult = runCatching { live.nearby(point, radiusMeters) }
        val nationalResult = runCatching { national.nearby(point, radiusMeters) }
        val liveDevices = liveResult.getOrDefault(emptyList())
        val nationalDevices = nationalResult.getOrDefault(emptyList())
        return Snapshot(
            devices = merge(nationalDevices, liveDevices),
            liveCount = liveDevices.size,
            nationalCount = nationalDevices.size,
            unavailableSources = setOfNotNull("live".takeIf { liveResult.isFailure },
                "national".takeIf { nationalResult.isFailure })
        )
    }

    fun alongRoutes(
        routes: List<Route>,
        corridorMeters: Double,
        includeRegional: Boolean = false
    ): Snapshot {
        if (routes.isEmpty()) return Snapshot(emptyList(), 0, 0, 0)
        val liveResult = runCatching { live.nearRoutes(routes, corridorMeters) }
        val nationalResult = runCatching { national.withinRoutes(routes, corridorMeters) }
        val regionalResult = runCatching { if (includeRegional)
            regional?.withinRoutes(routes, corridorMeters).orEmpty() else emptyList() }
        val liveDevices = liveResult.getOrDefault(emptyList())
        val nationalDevices = nationalResult.getOrDefault(emptyList())
        val regionalDevices = regionalResult.getOrDefault(emptyList())
        return Snapshot(
            devices = merge(nationalDevices, liveDevices, regionalDevices),
            liveCount = liveDevices.size,
            nationalCount = nationalDevices.size,
            regionalCount = regionalDevices.size,
            unavailableSources = setOfNotNull("live".takeIf { liveResult.isFailure },
                "national".takeIf { nationalResult.isFailure }, "regional".takeIf { regionalResult.isFailure })
        )
    }

    /** ALL observed devices in an explicit full-road search region, not shaped/baseline corridors.
     * A failed/overflowed/cancelled layer is named unavailable; freezeForRouting refuses it.
     * No network acquisition or setup completion is performed here. Layers are queried in order;
     * this is not an assertion of a single cross-database transaction/acquisition epoch.
     */
    fun withinRegion(region: DeviceSearchRegion, maximumRowsPerLayer: Int = 50000,
        cancelled: () -> Boolean = { false }): Snapshot {
        require(maximumRowsPerLayer in 1..500000)
        val liveResult = runCatching { live.withinRegion(region, maximumRowsPerLayer, cancelled) }
        val nationalResult = runCatching { national.withinRegion(region, maximumRowsPerLayer, cancelled) }
        val regionalResult = runCatching { regional?.withinRegion(region, maximumRowsPerLayer, cancelled).orEmpty() }
        val liveDevices = liveResult.getOrDefault(emptyList())
        val nationalDevices = nationalResult.getOrDefault(emptyList())
        val regionalDevices = regionalResult.getOrDefault(emptyList())
        return Snapshot(merge(nationalDevices, liveDevices, regionalDevices), liveDevices.size,
            nationalDevices.size, regionalDevices.size, setOfNotNull(
                "live".takeIf { liveResult.isFailure }, "national".takeIf { nationalResult.isFailure },
                "regional".takeIf { regionalResult.isFailure }))
    }

    companion object {
        fun merge(vararg layers: List<CameraPoi>): List<CameraPoi> {
            val merged = LinkedHashMap<String, CameraPoi>()
            layers.forEach { layer ->
                layer.forEach { incoming ->
                    val key = DeviceIdentity.logicalKey(incoming)
                    val existing = merged[key]
                    merged[key] = if (existing == null) incoming else enrich(existing, incoming)
                }
            }
            return merged.values.toList()
        }

        private fun enrich(first: CameraPoi, second: CameraPoi): CameraPoi {
            val firstPriority = CameraClassifier.priority(first.type) * 100 + first.confidence
            val secondPriority = CameraClassifier.priority(second.type) * 100 + second.confidence
            val preferred = if (secondPriority > firstPriority) second else first
            val other = if (preferred === first) second else first
            return preferred.copy(
                name = preferred.name ?: other.name,
                road = preferred.road ?: other.road,
                direction = preferred.direction ?: other.direction,
                operator = preferred.operator ?: other.operator,
                manufacturer = preferred.manufacturer ?: other.manufacturer,
                sourceUrl = preferred.sourceUrl ?: other.sourceUrl,
                sourceRecordId = preferred.sourceRecordId ?: other.sourceRecordId,
                confidence = maxOf(preferred.confidence, other.confidence),
                tags = LinkedHashMap<String, String>().apply {
                    putAll(other.tags)
                    putAll(preferred.tags)
                    val observedSources = (listOf(first.sourceId, second.sourceId) +
                        first.tags["watchman:merged_sources"].orEmpty().split(",") +
                        second.tags["watchman:merged_sources"].orEmpty().split(","))
                        .filter { it.isNotBlank() }.distinct().sorted()
                    put("watchman:merged_sources", observedSources.joinToString(","))
                },
                lastUpdatedMillis = maxOf(preferred.lastUpdatedMillis, other.lastUpdatedMillis)
            )
        }
    }
}
