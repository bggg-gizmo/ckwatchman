package com.bggg.watchman

import android.app.Instrumentation
import android.os.SystemClock
import com.bggg.watchman.data.*
import com.bggg.watchman.storage.ActiveRouteState
import com.bggg.watchman.storage.ActiveRouteStateCodec
import com.bggg.watchman.storage.SearchResultEvidenceJson
import org.json.JSONArray
import org.json.JSONObject

/** Real Android JSON/route codec regression. No network calls or application data mutations. */
object AndroidGeocoderEvidenceCheck {
    fun run(instrumentation: Instrumentation): String {
        val started = SystemClock.elapsedRealtime()
        val point = GeoPoint(0.0, 0.0)
        val url = "https://example.invalid/search?q=public-fixture"
        fun record() = JSONObject().put("lat", "0.0").put("lon", "0.0")
            .put("display_name", "Public synthetic place").put("osm_type", "way")
            .put("osm_id", 1234567890123456789L).put("class", "building").put("type", "retail")
            .put("address", JSONObject().put("road", "Public fixture road").put("state", "Fixture Region"))
            .put("boundingbox", JSONArray().put("-0.1").put("0.1").put("-0.1").put("0.1"))
            .put("extra_raw_metadata", JSONObject().put("retained", true))
        val raw = record()
        val evidence = checkNotNull(SearchResultEvidenceJson.fromNominatim(raw, url, point))
        check(evidence.osmId == 1234567890123456789L && evidence.osmType == "way" &&
            evidence.featureClass == "building" && evidence.featureType == "retail")
        check(evidence.address["road"] == "Public fixture road" && evidence.bounds?.west == -0.1)
        check(JSONObject(evidence.rawRecord).getJSONObject("extra_raw_metadata").getBoolean("retained"))
        raw.getJSONObject("address").put("road", "Caller mutated source JSON")
        check(evidence.address["road"] == "Public fixture road")
        check(SearchResultEvidenceJson.decode(SearchResultEvidenceJson.encode(evidence, point), point) == evidence)
        check(SearchResultEvidenceJson.encode(evidence, GeoPoint(4.0, 4.0)) == null)
        check(SearchResultEvidenceJson.fromNominatim(record(), url, GeoPoint(4.0, 4.0)) == null)
        val destination = SearchResult("Public synthetic place", point, evidence)
        val origin = SearchResult("Synthetic road start", GeoPoint(0.0001, -0.0001))
        val route = Route(listOf(origin.point, point), listOf(RouteStep("Continue", point, 15.0)),
            15.0, 2.0, destination)
        val state = ActiveRouteState(route, origin, RoutePreference.FEWEST_CAMERAS, 0)
        val encoded = ActiveRouteStateCodec.encode(state)
        val restored = checkNotNull(ActiveRouteStateCodec.decode(encoded))
        check(restored == state && restored.route.destination.geocoderEvidence == evidence)
        val old = JSONObject(encoded)
        old.getJSONObject("route").getJSONObject("destination").remove("geocoderEvidence")
        val oldRestored = checkNotNull(ActiveRouteStateCodec.decode(old.toString()))
        check(oldRestored.route.destination.geocoderEvidence == null && oldRestored.route.points == route.points &&
            oldRestored.route.steps == route.steps && ActiveRouteStateCodec.routeIdentity(oldRestored.route) ==
            ActiveRouteStateCodec.routeIdentity(route))
        var refused = 0
        for (bad in listOf<Any>(0, -1L, 1.5, "0", "-1", "1.0", "9223372036854775808", JSONObject.NULL)) {
            if (bad === JSONObject.NULL) continue // explicitly missing identity is retained as missing, not fabricated
            check(SearchResultEvidenceJson.fromNominatim(record().put("osm_id", bad), url, point) == null)
            refused++
        }
        for (bad in listOf<Any>(JSONArray(), JSONObject().put("road", 123), JSONObject().put("key", "x".repeat(2049)))) {
            check(SearchResultEvidenceJson.fromNominatim(record().put("address", bad), url, point) == null)
            refused++
        }
        check(SearchResultEvidenceJson.fromNominatim(record().put("osm_id", Long.MAX_VALUE.toString()), url, point)?.osmId == Long.MAX_VALUE)
        val missing = record().apply { remove("osm_id"); remove("osm_type") }
        check(SearchResultEvidenceJson.fromNominatim(missing, url, point)?.osmId == null)
        for (bad in listOf(JSONArray().put("29").put("28").put("-82").put("-80"),
            JSONArray().put("27").put("29").put("NaN").put("-80"), JSONArray().put("28"))) {
            check(SearchResultEvidenceJson.fromNominatim(record().put("boundingbox", bad), url, point) == null)
            refused++
        }
        fun malformed(rawRecord: String): JSONObject = JSONObject(encoded).apply {
            getJSONObject("route").getJSONObject("destination").getJSONObject("geocoderEvidence")
                .put("rawRecord", rawRecord)
        }
        for (bad in listOf("not JSON", "[".repeat(1000) + "0" + "]".repeat(1000),
            "x".repeat(65537), record().put("osm_id", "not-integral").toString())) {
            val preserved = checkNotNull(ActiveRouteStateCodec.decode(malformed(bad).toString()))
            check(preserved.route.destination.geocoderEvidence == null && preserved.route.points == route.points &&
                preserved.route.steps == route.steps && ActiveRouteStateCodec.routeIdentity(preserved.route) ==
                ActiveRouteStateCodec.routeIdentity(route))
            refused++
        }
        val wrongPoint = JSONObject(encoded).apply {
            getJSONObject("route").getJSONObject("destination").getJSONObject("geocoderEvidence").put("lat", 4)
        }
        check(checkNotNull(ActiveRouteStateCodec.decode(wrongPoint.toString())).route.destination.geocoderEvidence == null)
        // Exercise both endpoints without changing current route identity semantics.
        val sameCoordinateOrigin = destination.copy(displayName = "Synthetic observed origin")
        val both = state.copy(origin = sameCoordinateOrigin)
        check(ActiveRouteStateCodec.decode(ActiveRouteStateCodec.encode(both)) == both)
        check(instrumentation.targetContext.packageName == "com.bggg.watchman")
        return "GEOCODER_EVIDENCE PASS elapsed_ms=${SystemClock.elapsedRealtime() - started} refused=$refused " +
            "actual_android_json=true exact_route_restore=true legacy_compatible=true legal_entrance_claim=false"
    }
}
