package com.bggg.watchman.routing

import com.bggg.watchman.cameras.CameraClassifier
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import org.json.JSONObject

/** Additive device acquisition from actually returned source nodes. Way vertices and
 * ordinary infrastructure are not camera discoveries. No setup/state completion credit.
 */
object RoadSourceDeviceNormalizer {
    fun normalize(receipt: RoadCoverageSource.NodeReceipt): CameraPoi? {
        val node=JSONObject(receipt.rawJson)
        val rawId=node.get("id")
        require((rawId is Int || rawId is Long) && (rawId as Number).toLong()==receipt.id && receipt.id>0 &&
            receipt.receivedAtMillis>=0 && node.getString("type")=="node")
        val rawTags=node.optJSONObject("tags") ?: return null
        require(rawTags.length()<=4096)
        val tags=LinkedHashMap<String,String>()
        val keys=rawTags.keys()
        while(keys.hasNext()) {
            val key=keys.next()
            val value=rawTags.getString(key).trim()
            require(key.length<=256 && value.length<=16384)
            if(value.isNotEmpty()) tags[key]=value
        }
        require(tags.entries.sumOf { it.key.length.toLong()+it.value.length }<=131072)
        val classification=CameraClassifier.classify(tags)
        val explicit = tags["highway"] in setOf("traffic_signals","speed_camera","toll_gantry") ||
            tags["barrier"]=="toll_booth" || tags["man_made"] in setOf("surveillance","monitoring_station") ||
            tags.keys.any { key -> listOf("camera","surveillance","enforcement","sensor","detector","monitoring:traffic")
                .any { key==it || key.startsWith("$it:") } }
        val vendor = classification.type in setOf(CameraType.ALPR,CameraType.FLOCK,CameraType.ACOUSTIC,CameraType.RF_SENSOR)
        if(!explicit && !vendor) return null
        val point=GeoPoint(node.getDouble("lat"),node.getDouble("lon"))
        require(point.lat.isFinite() && point.lat in -90.0..90.0 && point.lon.isFinite() && point.lon in -180.0..180.0)
        val record="node/${receipt.id}"
        tags["data_source"]="OpenStreetMap"
        tags["retrieved_via"]="OSM API 0.6 source-road observation"
        val device=CameraPoi(record,classification.type,point,
            tags["name"] ?: tags["ref"],tags["addr:street"] ?: tags["road"],
            tags["direction"] ?: tags["camera:direction"],tags["operator"],tags["manufacturer"] ?: tags["brand"],
            source="OpenStreetMap / source-road observation",sourceId="osm_road_observation",
            sourceUrl="https://www.openstreetmap.org/$record",sourceRecordId=record,
            confidence=classification.confidence,tags=tags,lastUpdatedMillis=receipt.receivedAtMillis)
        return device.takeIf(RouteAnalyzer::isCameraOrSensorForRouting)
    }
}
