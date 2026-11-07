package com.bggg.watchman.data

data class GeoPoint(val lat: Double, val lon: Double)

data class SearchResult(
    val displayName: String,
    val point: GeoPoint,
    /** Optional raw geocoder observation, never a legal entrance or access permission. */
    val geocoderEvidence: GeocoderEvidence? = null
)

/** Immutable, bounded observation of a geocoder response. OSM identity and a building/POI
 * coordinate do not prove any connection to a road, facility entrance or private access grant.
 * Keep responsePoint bound explicitly: moving/copying a SearchResult must not silently rebind
 * provenance to a different coordinate. No geocoder freshness or validation date is invented.
 */
class GeocoderEvidence(val providerId: String, val requestUrl: String, val responsePoint: GeoPoint,
    val osmType: String?, val osmId: Long?, val featureClass: String?, val featureType: String?,
    address: Map<String, String>, val bounds: Bounds?, val rawRecord: String) {
    val address: Map<String, String> = java.util.Collections.unmodifiableMap(LinkedHashMap(address))
    data class Bounds(val south: Double, val north: Double, val west: Double, val east: Double) {
        init {
            require(listOf(south, north, west, east).all { it.isFinite() })
            require(south in -90.0..90.0 && north in -90.0..90.0 && south <= north)
            require(west in -180.0..180.0 && east in -180.0..180.0)
        }
    }
    init {
        require(providerId.isNotBlank() && providerId.length <= 64)
        require(requestUrl.isNotBlank() && requestUrl.length <= 8192)
        require(responsePoint.lat.isFinite() && responsePoint.lat in -90.0..90.0 &&
            responsePoint.lon.isFinite() && responsePoint.lon in -180.0..180.0)
        require(osmType == null || (osmType.isNotBlank() && osmType.length <= 64))
        require(osmId == null || osmId > 0)
        require(listOf(featureClass, featureType).all { it == null || (it.isNotBlank() && it.length <= 256) })
        require(this.address.size <= 128 && this.address.all { (key, value) ->
            key.isNotBlank() && key.length <= 128 && value.length <= 2048
        } && this.address.entries.sumOf { it.key.length.toLong() + it.value.length } <= 16384)
        require(rawRecord.isNotBlank() && rawRecord.length <= 65536)
    }
    fun binds(point: GeoPoint): Boolean = point == responsePoint
    override fun equals(other: Any?): Boolean = other is GeocoderEvidence &&
        providerId == other.providerId && requestUrl == other.requestUrl && responsePoint == other.responsePoint &&
        osmType == other.osmType && osmId == other.osmId && featureClass == other.featureClass &&
        featureType == other.featureType && address == other.address && bounds == other.bounds && rawRecord == other.rawRecord
    override fun hashCode(): Int = listOf(providerId, requestUrl, responsePoint, osmType, osmId,
        featureClass, featureType, address, bounds, rawRecord).hashCode()
}

data class LaneGuidance(
    val indications: List<String>,
    val valid: Boolean,
    val active: Boolean = false
)

data class RouteStep(
    val instruction: String,
    val maneuver: GeoPoint,
    val distanceMeters: Double,
    val maneuverType: String = "continue",
    val maneuverModifier: String = "",
    val roadName: String = "",
    val roadRef: String = "",
    val destinations: String = "",
    val exits: String = "",
    val rotaryName: String = "",
    val drivingSide: String = "",
    val bearingBefore: Double? = null,
    val bearingAfter: Double? = null,
    val lanes: List<LaneGuidance> = emptyList()
)

data class Route(
    val points: List<GeoPoint>,
    val steps: List<RouteStep>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val destination: SearchResult,
    val provider: String = "OSRM",
    val alternativeIndex: Int = 0,
    /** Original requested endpoint for exposure accounting, never proof of legal road access. */
    val requestedOrigin: GeoPoint? = null
)

enum class RoutePreference(val label: String, val description: String) {
    FASTEST("Fastest", "Lowest estimated travel time"),
    SHORTEST("Shortest", "Lowest road distance"),
    FEWEST_CAMERAS("No cams", "Fewest known cameras and sensors along the route"),
    MOST_CAMERAS("Most cams", "Most known cameras and sensors along the route"),
    SCENIC("Scenic", "Longest reasonable road route within bounded detour limits")
}

data class RouteAnalysis(
    val route: Route,
    val knownCameraCount: Int,
    val scenicScore: Double
)

data class ScenicFeature(
    val id: String,
    val point: GeoPoint,
    val kind: String,
    val name: String?,
    val weight: Double
)

enum class CameraType(val label: String) {
    RED_LIGHT("Red-light camera"),
    SPEED("Speed camera"),
    TRAFFIC("Traffic device"),
    HIGHWAY("Highway camera"),
    ALPR("ALPR / LPR"),
    FLOCK("Flock Safety"),
    ACOUSTIC("Acoustic / gunshot sensor"),
    RF_SENSOR("RF / electronic-signature sensor"),
    SURVEILLANCE("Surveillance camera"),
    TOLL("Toll / gantry"),
    SENSOR("Traffic sensor"),
    OTHER("Roadside camera")
}

data class CameraPoi(
    val id: String,
    val type: CameraType,
    val point: GeoPoint,
    val name: String?,
    val road: String?,
    val direction: String?,
    val operator: String?,
    val manufacturer: String?,
    val source: String = "OpenStreetMap / Overpass",
    val sourceId: String = "osm_overpass",
    val sourceUrl: String? = null,
    val sourceRecordId: String? = null,
    val confidence: Int = 70,
    val tags: Map<String, String> = emptyMap(),
    val lastUpdatedMillis: Long = System.currentTimeMillis()
)

data class CameraSourceResult(
    val cameras: List<CameraPoi>,
    val status: CameraSourceStatus
)

data class CameraSourceStatus(
    val sourceId: String,
    val sourceName: String,
    val ok: Boolean,
    val count: Int,
    val endpoint: String?,
    val error: String?,
    val elapsedMs: Long
)

data class CameraSyncResult(
    val cameras: List<CameraPoi>,
    val sources: List<CameraSourceStatus>
) {
    val successfulSources: Int get() = sources.count { it.ok }
    val failedSources: Int get() = sources.size - successfulSources
    val totalSources: Int get() = sources.size
}
