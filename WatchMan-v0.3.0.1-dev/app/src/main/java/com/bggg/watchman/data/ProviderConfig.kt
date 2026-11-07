package com.bggg.watchman.data

object ProviderConfig {
    const val NOMINATIM_BASE = "https://nominatim.openstreetmap.org"
    const val OBSTACLE_ROUTING_BASE = "https://valhalla1.openstreetmap.de"

    // Two independent OSRM-compatible services. The first is the project demo;
    // the second is the FOSSGIS/OpenStreetMap.de routed-car service.
    val OSRM_BASES = listOf(
        "https://router.project-osrm.org",
        "https://routing.openstreetmap.de/routed-car"
    )

    // Public Overpass services can be temporarily saturated. Camera source queries
    // rotate their first-choice endpoint, then fail over independently.
    val OVERPASS_ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.nchc.org.tw/api/interpreter"
    )

    const val OSM_TILE_BASE = "https://tile.openstreetmap.org"
    const val USER_AGENT = "WatchMan/0.3.0 (Background Gremlin Group)"
}
