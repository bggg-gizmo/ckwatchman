package com.bggg.watchman.routing

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.RoutePreference
import com.bggg.watchman.data.SearchResult
import java.nio.charset.StandardCharsets
import java.util.Base64

/** The exact user-selected operation that must run after route computation finishes. */
enum class PendingRouteAction {
    PREVIEW,
    TURN_BY_TURN,
    NAVIGATE,
    EXPORT_SELECTED,
    EXPORT_ALL,
    EXPORT_COMPARISON
}

/** Complete typed-route request plus its durable post-route operation. */
object PendingRouteActionPolicy {
    fun isExport(action: PendingRouteAction): Boolean = action in setOf(
        PendingRouteAction.EXPORT_SELECTED,
        PendingRouteAction.EXPORT_ALL,
        PendingRouteAction.EXPORT_COMPARISON
    )

    /** Export intent remains pending until validated prepared bytes and context are durable. */
    fun canClearImmediatelyAfterDispatch(action: PendingRouteAction): Boolean = !isExport(action)
}
data class PendingRouteRequest(
    val startQuery: String,
    val destinationQuery: String,
    val origin: SearchResult? = null,
    val destination: SearchResult? = null,
    val preference: RoutePreference? = null,
    val stage: Stage = Stage.GEOCODING,
    val postRouteAction: PendingRouteAction = PendingRouteAction.PREVIEW,
    /** Encoded ExportWorkflowContext for an already completed route result. */
    val completedWorkflowContext: String? = null
) {
    enum class Stage { GEOCODING, CHOOSE_DESTINATION, CHOOSE_MODE, PLANNING, ROUTE_READY }

    fun encode(): String {
        val fields = listOf(
            startQuery,
            destinationQuery,
            origin?.displayName.orEmpty(),
            origin?.point?.lat?.toString().orEmpty(),
            origin?.point?.lon?.toString().orEmpty(),
            destination?.displayName.orEmpty(),
            destination?.point?.lat?.toString().orEmpty(),
            destination?.point?.lon?.toString().orEmpty(),
            preference?.name.orEmpty(),
            stage.name,
            postRouteAction.name,
            completedWorkflowContext.orEmpty()
        )
        return fields.joinToString(".") { field ->
            Base64.getUrlEncoder().withoutPadding().encodeToString(field.toByteArray(StandardCharsets.UTF_8))
        }
    }

    companion object {
        fun decode(encoded: String?): PendingRouteRequest? = runCatching {
            if (encoded.isNullOrBlank()) return null
            val fields = encoded.split('.').map { field ->
                String(Base64.getUrlDecoder().decode(field), StandardCharsets.UTF_8)
            }
            // Ten fields are the superseded Gate 6 format. Migrate them as PREVIEW requests.
            if (fields.size !in 10..12 || fields[1].isBlank()) return null
            fun result(nameIndex: Int, latIndex: Int, lonIndex: Int): SearchResult? {
                val name = fields[nameIndex]
                val lat = fields[latIndex].toDoubleOrNull()
                val lon = fields[lonIndex].toDoubleOrNull()
                return if (name.isBlank() || lat == null || lon == null) null else SearchResult(name, GeoPoint(lat, lon))
            }
            PendingRouteRequest(
                startQuery = fields[0],
                destinationQuery = fields[1],
                origin = result(2, 3, 4),
                destination = result(5, 6, 7),
                preference = fields[8].takeIf { it.isNotBlank() }?.let(RoutePreference::valueOf),
                stage = Stage.valueOf(fields[9]),
                postRouteAction = fields.getOrNull(10)?.takeIf { it.isNotBlank() }
                    ?.let(PendingRouteAction::valueOf) ?: PendingRouteAction.PREVIEW,
                completedWorkflowContext = fields.getOrNull(11)?.takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }
}