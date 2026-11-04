package com.bggg.watchman.search

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.ProviderConfig
import com.bggg.watchman.data.SearchResult
import com.bggg.watchman.net.HttpClient
import com.bggg.watchman.storage.SearchResultEvidenceJson
import org.json.JSONArray

interface SearchProvider { fun search(query: String): List<SearchResult> }

class NominatimSearchProvider : SearchProvider {
    override fun search(query: String): List<SearchResult> {
        val url = "${ProviderConfig.NOMINATIM_BASE}/search?format=jsonv2&addressdetails=1&limit=5&q=${HttpClient.enc(query)}"
        val arr = JSONArray(HttpClient.get(url, timeoutMs = 10_000, attempts = 1))
        val out = ArrayList<SearchResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val lat = o.optString("lat").toDoubleOrNull() ?: continue
            val lon = o.optString("lon").toDoubleOrNull() ?: continue
            if (!lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) continue
            val point = GeoPoint(lat, lon)
            out += SearchResult(o.optString("display_name", query), point,
                SearchResultEvidenceJson.fromNominatim(o, url, point))
        }
        return out
    }
}
