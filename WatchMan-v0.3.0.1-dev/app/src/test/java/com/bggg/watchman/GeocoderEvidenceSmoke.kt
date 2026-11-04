package com.bggg.watchman

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.GeocoderEvidence
import com.bggg.watchman.data.SearchResult

fun geocoderEvidenceSmoke() {
    val point = GeoPoint(0.0, 0.0)
    val address = linkedMapOf("road" to "Public fixture road", "state" to "Fixture Region")
    fun evidence(id: Long? = 123L, place: GeoPoint = point, fields: Map<String, String> = address,
        raw: String = "{\"lat\":\"0\",\"lon\":\"0\"}") =
        GeocoderEvidence("nominatim", "https://example.invalid/search?q=public-fixture", place,
            "way", id, "building", "retail", fields, GeocoderEvidence.Bounds(-1.0, 1.0, -1.0, 1.0), raw)
    fun reject(action: () -> Unit) { check(runCatching(action).isFailure) }
    val observed = evidence()
    address["road"] = "Caller changed its input map"
    check(observed.address["road"] == "Public fixture road")
    reject { (observed.address as MutableMap<String, String>)["road"] = "Mutable output" }
    check(observed.binds(point) && !observed.binds(GeoPoint(0.0001, 0.0)))
    val legacy = SearchResult("Existing result", point)
    check(legacy.geocoderEvidence == null && legacy.component1() == "Existing result" && legacy.component2() == point)
    val enriched = legacy.copy(geocoderEvidence = observed)
    check(enriched.displayName == legacy.displayName && enriched.point == legacy.point)
    check(enriched.copy(point = GeoPoint(4.0, 4.0)).geocoderEvidence?.binds(GeoPoint(4.0, 4.0)) == false)
    val equal = evidence(fields = observed.address)
    check(observed == equal && observed.hashCode() == equal.hashCode())
    for (id in listOf(0L, -1L, Long.MIN_VALUE)) reject { evidence(id = id) }
    check(evidence(id = Long.MAX_VALUE).osmId == Long.MAX_VALUE)
    reject { evidence(place = GeoPoint(Double.NaN, 0.0)) }
    reject { evidence(place = GeoPoint(91.0, 0.0)) }
    reject { evidence(place = GeoPoint(0.0, Double.POSITIVE_INFINITY)) }
    reject { evidence(fields = (0..128).associate { "key$it" to "value" }) }
    reject { evidence(fields = mapOf("" to "value")) }
    reject { evidence(fields = mapOf("key" to "x".repeat(2049))) }
    reject { evidence(fields = (0..9).associate { "key$it" to "x".repeat(2048) }) }
    reject { evidence(raw = "x".repeat(65537)) }
    reject { GeocoderEvidence.Bounds(1.0, 0.0, -1.0, 1.0) }
    reject { GeocoderEvidence.Bounds(-1.0, 1.0, -181.0, 1.0) }
    check(GeocoderEvidence.Bounds(-1.0, 1.0, 179.0, -179.0).west > 0)
    println("Geocoder evidence model: legacy constructors, immutable bounded values, coordinate binding and positive integral IDs PASS; not legal entrance evidence")
}
