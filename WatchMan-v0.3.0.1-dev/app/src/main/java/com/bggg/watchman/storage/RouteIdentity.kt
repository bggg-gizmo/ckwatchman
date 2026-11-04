package com.bggg.watchman.storage

import com.bggg.watchman.data.Route
import java.security.MessageDigest
import java.util.Locale

/** Exact legacy-compatible route identity without allocating one route-sized canonical String. */
object RouteIdentity {
    fun sha256(route: Route): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun update(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
        }

        update(route.provider)
        update("|")
        update(route.destination.displayName)
        update("|")
        update(String.format(Locale.US, "%.6f,%.6f", route.destination.point.lat, route.destination.point.lon))
        update("|")
        update(String.format(Locale.US, "%.1f|%.1f|", route.distanceMeters, route.durationSeconds))
        route.points.forEach { point ->
            update(String.format(Locale.US, "%.6f,%.6f;", point.lat, point.lon))
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
