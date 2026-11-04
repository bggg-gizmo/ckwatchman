package com.bggg.watchman.geo

import android.content.Context
import com.bggg.watchman.data.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Offline official Census jurisdiction lookup for acquisition ordering and route eligibility. */
class StateBoundaryLookup(context: Context) {
    data class Jurisdiction(
        val fips: String,
        val abbreviation: String,
        val name: String,
        val polygons: List<StateBoundaryGeometry.Polygon>
    ) {
        val nationalSourceId: String
            get() = "osm_national_us_" + abbreviation.lowercase(Locale.US)
    }

    private val assets = context.applicationContext.assets
    @Volatile private var cached: List<Jurisdiction>? = null

    fun preload(): Boolean = runCatching { jurisdictions().isNotEmpty() }.getOrDefault(false)

    fun find(point: GeoPoint): Jurisdiction? =
        jurisdictions().firstOrNull { jurisdiction ->
            jurisdiction.polygons.any { StateBoundaryGeometry.contains(it, point) }
        }

    fun findIfLoaded(point: GeoPoint): Jurisdiction? =
        cached?.firstOrNull { jurisdiction ->
            jurisdiction.polygons.any { StateBoundaryGeometry.contains(it, point) }
        }

    fun isLoaded(): Boolean = cached != null

    @Synchronized
    private fun jurisdictions(): List<Jurisdiction> {
        cached?.let { return it }
        val root = assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use {
            JSONObject(it.readText())
        }
        val features = root.getJSONArray("features")
        val parsed = ArrayList<Jurisdiction>(features.length())
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val properties = feature.optJSONObject("properties") ?: continue
            val fips = properties.optString("STATE").trim()
            val abbreviation = properties.optString("STUSAB").trim()
            val name = properties.optString("NAME").trim()
            val geometry = feature.optJSONObject("geometry") ?: continue
            val polygons = parseGeometry(geometry)
            if (fips.isNotBlank() && abbreviation.isNotBlank() && name.isNotBlank() && polygons.isNotEmpty()) {
                parsed += Jurisdiction(fips, abbreviation, name, polygons)
            }
        }
        require(parsed.size >= MIN_JURISDICTIONS) {
            "State boundary asset incomplete: " + parsed.size + " jurisdictions"
        }
        return parsed.sortedBy { it.fips }.also { cached = it }
    }

    private fun parseGeometry(geometry: JSONObject): List<StateBoundaryGeometry.Polygon> {
        val coordinates = geometry.optJSONArray("coordinates") ?: return emptyList()
        return when (geometry.optString("type")) {
            "Polygon" -> listOfNotNull(parsePolygon(coordinates))
            "MultiPolygon" -> buildList {
                for (index in 0 until coordinates.length()) {
                    parsePolygon(coordinates.optJSONArray(index))?.let(::add)
                }
            }
            else -> emptyList()
        }
    }

    private fun parsePolygon(raw: JSONArray?): StateBoundaryGeometry.Polygon? {
        if (raw == null || raw.length() == 0) return null
        val rings = ArrayList<StateBoundaryGeometry.Ring>(raw.length())
        for (index in 0 until raw.length()) {
            parseRing(raw.optJSONArray(index))?.let(rings::add)
        }
        if (rings.isEmpty()) return null
        return StateBoundaryGeometry.Polygon(rings.first(), rings.drop(1))
    }

    private fun parseRing(raw: JSONArray?): StateBoundaryGeometry.Ring? {
        if (raw == null || raw.length() < 3) return null
        val points = ArrayList<GeoPoint>(raw.length())
        for (index in 0 until raw.length()) {
            val pair = raw.optJSONArray(index) ?: continue
            val lon = pair.optDouble(0, Double.NaN)
            val lat = pair.optDouble(1, Double.NaN)
            if (lat.isFinite() && lon.isFinite()) points += GeoPoint(lat, lon)
        }
        return points.takeIf { it.size >= 3 }?.let(StateBoundaryGeometry.Ring::from)
    }

    companion object {
        const val ASSET_NAME = "us_state_boundaries_v028.geojson"
        const val MIN_JURISDICTIONS = 56
        const val MAX_GENERALIZATION_OFFSET_DEGREES = 0.001
    }
}
