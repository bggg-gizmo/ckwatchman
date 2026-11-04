package com.bggg.watchman.cameras

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.net.HttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * U.S. Census TIGERweb county/county-equivalent boundary catalog.
 *
 * County boundaries are used only to organize the national bootstrap into stable state -> county ->
 * SafeTOC-sized locale work units. Camera/sensor data still comes from the existing live public
 * camera providers (primarily OpenStreetMap/Overpass), not from Census.
 */
class CountyCatalogProvider {
    data class County(
        val geoid: String,
        val stateFips: String,
        val countyFips: String,
        val name: String,
        val stateName: String,
        val stateSourceId: String,
        val rings: List<List<GeoPoint>>
    ) {
        val countyId: String get() = "us_county_$geoid"
    }

    fun fetchNationalCountyCount(): Int {
        val url = TIGER_COUNTY_QUERY + "?where=" + HttpClient.enc("1=1") +
            "&returnCountOnly=true&f=json"
        val root = JSONObject(HttpClient.get(url, timeoutMs = CATALOG_TIMEOUT_MS, attempts = 2))
        return root.optInt("count", 0).takeIf { it > 0 }
            ?: throw IllegalStateException("Census county count unavailable")
    }

    fun fetchCounties(region: NationalCameraBootstrapProvider.Region): List<County> {
        val where = "STATE='${region.fips}'"
        val url = TIGER_COUNTY_QUERY +
            "?where=" + HttpClient.enc(where) +
            "&outFields=" + HttpClient.enc("STATE,COUNTY,GEOID,NAME,BASENAME") +
            "&returnGeometry=true&outSR=4326&geometryPrecision=4&f=geojson"
        val root = JSONObject(HttpClient.get(url, timeoutMs = CATALOG_TIMEOUT_MS, attempts = 2))
        val features = root.optJSONArray("features") ?: throw IllegalStateException("Census county features unavailable")
        val out = ArrayList<County>()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val props = feature.optJSONObject("properties") ?: JSONObject()
            val geoid = props.optString("GEOID").trim()
            val state = props.optString("STATE").trim()
            val county = props.optString("COUNTY").trim()
            if (geoid.isBlank() || state.isBlank() || county.isBlank()) continue
            val name = props.optString("NAME").trim().ifBlank {
                props.optString("BASENAME").trim().ifBlank { "County $county" }
            }
            val rings = parseOuterRings(feature.optJSONObject("geometry"))
            if (rings.isEmpty()) continue
            out += County(
                geoid = geoid,
                stateFips = state,
                countyFips = county,
                name = name,
                stateName = region.name,
                stateSourceId = region.sourceId,
                rings = rings
            )
        }
        if (out.isEmpty()) throw IllegalStateException("No county-equivalent boundaries returned for ${region.name}")
        return out.sortedWith(compareBy<County> { it.countyFips }.thenBy { it.name })
    }

    private fun parseOuterRings(geometry: JSONObject?): List<List<GeoPoint>> {
        if (geometry == null) return emptyList()
        val coordinates = geometry.optJSONArray("coordinates") ?: return emptyList()
        return when (geometry.optString("type")) {
            "Polygon" -> listOfNotNull(parseRing(coordinates.optJSONArray(0)))
            "MultiPolygon" -> buildList {
                for (i in 0 until coordinates.length()) {
                    val polygon = coordinates.optJSONArray(i) ?: continue
                    parseRing(polygon.optJSONArray(0))?.let(::add)
                }
            }
            else -> emptyList()
        }
    }

    private fun parseRing(raw: JSONArray?): List<GeoPoint>? {
        if (raw == null || raw.length() < 3) return null
        val points = ArrayList<GeoPoint>(raw.length())
        for (i in 0 until raw.length()) {
            val pair = raw.optJSONArray(i) ?: continue
            if (pair.length() < 2) continue
            val lon = pair.optDouble(0, Double.NaN)
            val lat = pair.optDouble(1, Double.NaN)
            if (lat.isFinite() && lon.isFinite()) points += GeoPoint(lat, lon)
        }
        return points.takeIf { it.size >= 3 }
    }

    companion object {
        private const val CATALOG_TIMEOUT_MS = 35_000
        private const val TIGER_COUNTY_QUERY =
            "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/State_County/MapServer/13/query"
        const val FALLBACK_COUNTY_EQUIVALENT_TOTAL = 3143
    }
}
