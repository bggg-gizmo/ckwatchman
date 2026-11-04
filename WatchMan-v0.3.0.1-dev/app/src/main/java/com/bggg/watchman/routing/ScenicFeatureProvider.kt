package com.bggg.watchman.routing

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.ProviderConfig
import com.bggg.watchman.data.Route
import com.bggg.watchman.data.ScenicFeature
import com.bggg.watchman.net.HttpClient
import com.bggg.watchman.util.Geo
import org.json.JSONObject
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max

class ScenicFeatureProvider {
    @Volatile var lastError: String? = null
        private set

    fun fetchNearRoutes(routes: List<Route>): List<ScenicFeature> {
        if (routes.isEmpty()) return emptyList()
        val boxes = coverageBoxes(routes)
        val out = LinkedHashMap<String, ScenicFeature>()
        var anySuccess = false
        var failure: Exception? = null

        for (batch in boxes.chunked(8)) {
            val body = buildString {
                for (bbox in batch) {
                    append("nwr[\"tourism\"=\"viewpoint\"]($bbox);\n")
                    append("nwr[\"natural\"=\"peak\"]($bbox);\n")
                    append("nwr[\"natural\"=\"water\"]($bbox);\n")
                    append("nwr[\"waterway\"=\"river\"]($bbox);\n")
                    append("nwr[\"leisure\"=\"park\"]($bbox);\n")
                    append("nwr[\"landuse\"=\"forest\"]($bbox);\n")
                    append("nwr[\"natural\"=\"wood\"]($bbox);\n")
                    append("nwr[\"boundary\"=\"national_park\"]($bbox);\n")
                    append("nwr[\"scenic\"=\"yes\"]($bbox);\n")
                }
            }
            val query = "[out:json][timeout:25];($body);out body center;"
            var batchSuccess = false
            for (endpoint in ProviderConfig.OVERPASS_ENDPOINTS) {
                try {
                    val json = HttpClient.postForm(endpoint, mapOf("data" to query), timeoutMs = 25000, attempts = 1)
                    for (feature in parse(json)) out[feature.id] = feature
                    anySuccess = true
                    batchSuccess = true
                    break
                } catch (e: Exception) {
                    failure = e
                }
            }
            if (!batchSuccess) continue
        }

        lastError = if (anySuccess) null else failure?.message ?: "Scenic data unavailable"
        return out.values.toList()
    }

    fun score(route: Route, features: List<ScenicFeature>): Double {
        if (route.points.isEmpty() || features.isEmpty()) return 0.0
        var score = 0.0
        for (feature in features) {
            val nearIndex = Geo.nearestRouteIndex(feature.point, route.points)
            val d = Geo.distanceToRouteMeters(feature.point, route.points, nearIndex)
            if (d > 1800.0) continue
            val proximity = 1.0 / (1.0 + d / 400.0)
            score += feature.weight * proximity
        }
        // Normalize mildly by route length so a longer route does not win solely because
        // it intersects more square kilometers of scenery.
        val lengthKm = (route.distanceMeters / 1000.0).coerceAtLeast(1.0)
        return score / Math.sqrt(lengthKm)
    }

    private fun parse(json: String): List<ScenicFeature> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("elements")
        val out = ArrayList<ScenicFeature>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val osmType = e.optString("type", "node")
            val osmId = e.optLong("id", -1L)
            if (osmId < 0) continue
            val lat: Double
            val lon: Double
            if (e.has("lat") && e.has("lon")) {
                lat = e.getDouble("lat")
                lon = e.getDouble("lon")
            } else {
                val center = e.optJSONObject("center") ?: continue
                lat = center.optDouble("lat", Double.NaN)
                lon = center.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
            }
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val kindAndWeight = when {
                tags.optString("tourism") == "viewpoint" -> "Viewpoint" to 9.0
                tags.optString("boundary") == "national_park" -> "National park" to 8.0
                tags.optString("scenic") == "yes" -> "Scenic road/feature" to 8.0
                tags.optString("natural") == "water" -> "Water" to 6.5
                tags.optString("waterway") == "river" -> "River" to 6.0
                tags.optString("natural") == "peak" -> "Peak" to 6.0
                tags.optString("landuse") == "forest" || tags.optString("natural") == "wood" -> "Forest" to 5.0
                tags.optString("leisure") == "park" -> "Park" to 4.0
                else -> "Scenic feature" to 2.0
            }
            out += ScenicFeature(
                id = "$osmType/$osmId",
                point = GeoPoint(lat, lon),
                kind = kindAndWeight.first,
                name = tags.optString("name").takeIf { it.isNotBlank() },
                weight = kindAndWeight.second
            )
        }
        return out
    }

    private fun coverageBoxes(routes: List<Route>): List<String> {
        val all = routes.flatMap { it.points }
        if (all.isEmpty()) return emptyList()
        val south = all.minOf { it.lat }
        val north = all.maxOf { it.lat }
        val west = all.minOf { it.lon }
        val east = all.maxOf { it.lon }
        val diag = Geo.distanceMeters(GeoPoint(south, west), GeoPoint(north, east))
        if (diag <= 90000.0) {
            val center = GeoPoint((south + north) / 2.0, (west + east) / 2.0)
            val radius = (diag / 2.0 + 5000.0).toInt().coerceAtLeast(6000)
            return listOf(bbox(center, radius))
        }

        val boxes = LinkedHashMap<String, String>()
        for (route in routes) {
            for (p in Geo.sampleRoutePoints(route.points, 30000.0)) {
                val key = String.format(Locale.US, "%.2f:%.2f", p.lat, p.lon)
                boxes.putIfAbsent(key, bbox(p, 7000))
            }
        }
        return boxes.values.toList()
    }

    private fun bbox(center: GeoPoint, radiusMeters: Int): String {
        val km = radiusMeters / 1000.0
        val dLat = max(0.005, km / 111.0)
        val dLng = max(0.005, km / max(20.0, 111.0 * cos(Math.toRadians(center.lat))))
        return String.format(
            Locale.US,
            "%.6f,%.6f,%.6f,%.6f",
            (center.lat - dLat).coerceIn(-89.9, 89.9),
            (center.lon - dLng).coerceIn(-179.9, 179.9),
            (center.lat + dLat).coerceIn(-89.9, 89.9),
            (center.lon + dLng).coerceIn(-179.9, 179.9)
        )
    }
}
