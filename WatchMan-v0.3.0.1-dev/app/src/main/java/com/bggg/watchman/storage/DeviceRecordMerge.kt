package com.bggg.watchman.storage

import com.bggg.watchman.cameras.CameraClassifier
import com.bggg.watchman.data.CameraPoi

/** Identity-only merge shared by all cache layers. A fresher equal-rank observation must
 * replace old coordinates/tags, without downgrading a stronger observed sensor family.
 */
object DeviceRecordMerge {
    fun enrich(first: CameraPoi, second: CameraPoi): CameraPoi {
        require(DeviceIdentity.logicalKey(first)==DeviceIdentity.logicalKey(second))
        val firstPriority=CameraClassifier.priority(first.type)*100+first.confidence
        val secondPriority=CameraClassifier.priority(second.type)*100+second.confidence
        val preferred=if(secondPriority>firstPriority ||
            (secondPriority==firstPriority && second.lastUpdatedMillis>first.lastUpdatedMillis)) second else first
        val other=if(preferred===first) second else first
        // A directly observed current OSM node can move without losing older sensor
        // evidence. Keep the stronger family, but bind its location to that fresh receipt.
        val coordinate=when {
            second.sourceId=="osm_road_observation" && second.lastUpdatedMillis>=first.lastUpdatedMillis -> second
            first.sourceId=="osm_road_observation" && first.lastUpdatedMillis>=second.lastUpdatedMillis -> first
            else -> preferred
        }
        return preferred.copy(
            point=coordinate.point,
            name=preferred.name ?: other.name,
            road=preferred.road ?: other.road,
            direction=preferred.direction ?: other.direction,
            operator=preferred.operator ?: other.operator,
            manufacturer=preferred.manufacturer ?: other.manufacturer,
            sourceUrl=preferred.sourceUrl ?: other.sourceUrl,
            sourceRecordId=preferred.sourceRecordId ?: other.sourceRecordId,
            confidence=maxOf(preferred.confidence,other.confidence),
            tags=LinkedHashMap<String,String>().apply {
                putAll(other.tags); putAll(preferred.tags)
                val observedSources=(listOf(first.sourceId,second.sourceId)+
                    first.tags["watchman:merged_sources"].orEmpty().split(",")+
                    second.tags["watchman:merged_sources"].orEmpty().split(","))
                    .filter { it.isNotBlank() }.distinct().sorted()
                put("watchman:merged_sources",observedSources.joinToString(","))
            },
            lastUpdatedMillis=maxOf(preferred.lastUpdatedMillis,other.lastUpdatedMillis)
        )
    }
}
