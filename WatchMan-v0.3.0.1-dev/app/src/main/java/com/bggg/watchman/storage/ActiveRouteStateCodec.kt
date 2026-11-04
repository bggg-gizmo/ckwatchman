package com.bggg.watchman.storage

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.LaneGuidance
import com.bggg.watchman.data.Route
import com.bggg.watchman.data.RoutePreference
import com.bggg.watchman.data.RouteStep
import com.bggg.watchman.data.SearchResult
import org.json.JSONArray
import org.json.JSONObject

data class ActiveRouteState(
    val route: Route,
    val origin: SearchResult,
    val preference: RoutePreference,
    val currentStep: Int
)

/** Durable, versioned serialization for the exact user-selected route and its maneuvers. */
object ActiveRouteStateCodec {
    const val PREF_STATE_KEY = "active_route_state_v1"
    const val PREF_STEP_KEY = "active_route_step_v1"
    private const val FORMAT_VERSION = 1

    fun encode(state: ActiveRouteState): String {
        val route = state.route
        return JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("routeIdentity", routeIdentity(route))
            .put("preference", state.preference.name)
            .put("currentStep", state.currentStep.coerceAtLeast(0))
            .put("origin", JSONObject()
                .put("displayName", state.origin.displayName)
                .put("point", pointJson(state.origin.point))
                .apply { SearchResultEvidenceJson.encode(state.origin.geocoderEvidence, state.origin.point)
                    ?.let { put("geocoderEvidence", it) } }
            )
            .put("route", JSONObject()
                .put("points", JSONArray().apply { route.points.forEach { put(pointJson(it)) } })
                .put("steps", JSONArray().apply { route.steps.forEach { put(stepJson(it)) } })
                .put("distanceMeters", route.distanceMeters)
                .put("durationSeconds", route.durationSeconds)
                .put("provider", route.provider)
                .put("alternativeIndex", route.alternativeIndex)
                .put("destination", JSONObject()
                    .put("displayName", route.destination.displayName)
                    .put("point", pointJson(route.destination.point))
                    .apply { SearchResultEvidenceJson.encode(route.destination.geocoderEvidence, route.destination.point)
                        ?.let { put("geocoderEvidence", it) } }
                )
            )
            .toString()
    }

    fun decode(raw: String?): ActiveRouteState? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(raw)
            require(root.getInt("formatVersion") == FORMAT_VERSION)
            val routeJson = root.getJSONObject("route")
            val points = routeJson.getJSONArray("points").toPoints()
            require(points.size >= 2)
            val destinationJson = routeJson.getJSONObject("destination")
            val route = Route(
                points = points,
                steps = routeJson.getJSONArray("steps").toSteps(),
                distanceMeters = routeJson.getDouble("distanceMeters"),
                durationSeconds = routeJson.getDouble("durationSeconds"),
                destination = destinationJson.toSearchResult(),
                provider = routeJson.optString("provider", "OSRM"),
                alternativeIndex = routeJson.optInt("alternativeIndex", 0)
            )
            require(root.getString("routeIdentity") == routeIdentity(route))
            ActiveRouteState(
                route = route,
                origin = root.getJSONObject("origin").let { originJson ->
                    originJson.toSearchResult()
                },
                preference = RoutePreference.valueOf(root.getString("preference")),
                currentStep = root.optInt("currentStep", 0).coerceIn(0, route.steps.lastIndex.coerceAtLeast(0))
            )
        }.getOrNull()
    }


    fun routeIdentity(route: Route): String = RouteIdentity.sha256(route)


    private fun pointJson(point: GeoPoint): JSONObject =
        JSONObject().put("lat", point.lat).put("lon", point.lon)

    private fun JSONObject.toPoint(): GeoPoint = GeoPoint(getDouble("lat"), getDouble("lon"))

    private fun JSONObject.toSearchResult(): SearchResult {
        val point = getJSONObject("point").toPoint()
        return SearchResult(getString("displayName"), point,
            SearchResultEvidenceJson.decode(opt("geocoderEvidence"), point))
    }

    private fun stepJson(step: RouteStep): JSONObject = JSONObject()
        .put("instruction", step.instruction)
        .put("maneuver", pointJson(step.maneuver))
        .put("distanceMeters", step.distanceMeters)
        .put("maneuverType", step.maneuverType)
        .put("maneuverModifier", step.maneuverModifier)
        .put("roadName", step.roadName)
        .put("roadRef", step.roadRef)
        .put("destinations", step.destinations)
        .put("exits", step.exits)
        .put("rotaryName", step.rotaryName)
        .put("drivingSide", step.drivingSide)
        .apply {
            step.bearingBefore?.let { put("bearingBefore", it) }
            step.bearingAfter?.let { put("bearingAfter", it) }
        }
        .put("lanes", JSONArray().apply {
            step.lanes.forEach { lane ->
                put(JSONObject()
                    .put("indications", JSONArray().apply { lane.indications.forEach { indication -> put(indication) } })
                    .put("valid", lane.valid)
                    .put("active", lane.active)
                )
            }
        })

    private fun JSONArray.toPoints(): List<GeoPoint> = buildList {
        for (index in 0 until length()) add(getJSONObject(index).toPoint())
    }

    private fun JSONArray.toSteps(): List<RouteStep> = buildList {
        for (index in 0 until length()) {
            val json = getJSONObject(index)
            val lanesJson = json.optJSONArray("lanes") ?: JSONArray()
            val lanes = buildList {
                for (laneIndex in 0 until lanesJson.length()) {
                    val laneJson = lanesJson.getJSONObject(laneIndex)
                    val indicationsJson = laneJson.optJSONArray("indications") ?: JSONArray()
                    val indications = buildList {
                        for (indicationIndex in 0 until indicationsJson.length()) {
                            add(indicationsJson.getString(indicationIndex))
                        }
                    }
                    add(LaneGuidance(
                        indications = indications,
                        valid = laneJson.optBoolean("valid", false),
                        active = laneJson.optBoolean("active", false)
                    ))
                }
            }
            add(RouteStep(
                instruction = json.getString("instruction"),
                maneuver = json.getJSONObject("maneuver").toPoint(),
                distanceMeters = json.getDouble("distanceMeters"),
                maneuverType = json.optString("maneuverType", "continue"),
                maneuverModifier = json.optString("maneuverModifier", ""),
                roadName = json.optString("roadName", ""),
                roadRef = json.optString("roadRef", ""),
                destinations = json.optString("destinations", ""),
                exits = json.optString("exits", ""),
                rotaryName = json.optString("rotaryName", ""),
                drivingSide = json.optString("drivingSide", ""),
                bearingBefore = json.optionalDouble("bearingBefore"),
                bearingAfter = json.optionalDouble("bearingAfter"),
                lanes = lanes
            ))
        }
    }

    private fun JSONObject.optionalDouble(key: String): Double? =
        if (has(key) && !isNull(key)) getDouble(key) else null
}
