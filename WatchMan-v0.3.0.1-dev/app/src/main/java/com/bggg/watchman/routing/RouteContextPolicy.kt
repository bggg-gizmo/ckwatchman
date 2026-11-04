package com.bggg.watchman.routing

import com.bggg.watchman.data.Route

/** Exact route-context rules shared by visible-route controls and workflow recovery. */
object RouteContextPolicy {
    fun visibleRoute(activeRoute: Route?, previewRoute: Route?): Route? = previewRoute ?: activeRoute

    fun exactSingleIndex(identities: List<String>, requiredIdentity: String): Int? {
        if (requiredIdentity.isBlank()) return null
        val matches = identities.indices.filter { identities[it] == requiredIdentity }
        return matches.singleOrNull()
    }
}
