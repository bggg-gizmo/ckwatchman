package com.bggg.watchman

import com.bggg.watchman.data.*
import com.bggg.watchman.storage.DeviceRecordMerge
import com.bggg.watchman.storage.RoutingDeviceSnapshot

fun deviceRecordMergeSmoke() {
    val old=CameraPoi("node/101",CameraType.TRAFFIC,GeoPoint(0.0,0.0),null,null,null,null,null,
        sourceId="synthetic-seed",sourceRecordId="node/101",confidence=78,
        tags=mapOf("highway" to "traffic_signals","old_detail" to "retained"),lastUpdatedMillis=100)
    val fresh=old.copy(point=GeoPoint(0.001,0.001),sourceId="osm_road_observation",
        tags=mapOf("highway" to "traffic_signals","fresh_detail" to "observed"),lastUpdatedMillis=200)
    for(merged in listOf(DeviceRecordMerge.enrich(old,fresh),DeviceRecordMerge.enrich(fresh,old))) {
        check(merged.point==fresh.point && merged.lastUpdatedMillis==200L)
        check(merged.tags["old_detail"]=="retained" && merged.tags["fresh_detail"]=="observed")
        check(merged.tags["watchman:merged_sources"]=="osm_road_observation,synthetic-seed")
        check(RoutingDeviceSnapshot.freeze(listOf(merged),"synthetic-merge").entries.single().weight==1L)
    }
    val rf=old.copy(type=CameraType.RF_SENSOR,manufacturer="SignalTrace",confidence=99,
        tags=old.tags+("product" to "EOC+"))
    for(merged in listOf(DeviceRecordMerge.enrich(rf,fresh),DeviceRecordMerge.enrich(fresh,rf))) {
        check(merged.type==CameraType.RF_SENSOR && merged.manufacturer=="SignalTrace" && merged.confidence==99)
        check(merged.point==fresh.point && merged.tags["product"]=="EOC+")
    }
    val stale=fresh.copy(lastUpdatedMillis=50)
    check(DeviceRecordMerge.enrich(old,stale).point==old.point)
    val generic=fresh.copy(sourceId="synthetic-generic",confidence=20)
    check(DeviceRecordMerge.enrich(rf,generic).point==rf.point) // No generic lower-rank location override.
    check(runCatching { DeviceRecordMerge.enrich(old,fresh.copy(id="node/102",sourceRecordId="node/102")) }.isFailure)
    println("Device merge: fresh equal-rank/source-node coordinates, stronger sensor retention, original timestamps/provenance and identity-only boundaries PASS")
}
