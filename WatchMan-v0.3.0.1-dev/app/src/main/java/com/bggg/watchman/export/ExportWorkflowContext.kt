package com.bggg.watchman.export

import java.nio.charset.StandardCharsets
import java.util.Base64
import com.bggg.watchman.data.RoutedDeviceExposure

enum class DirectionsExportMode {
    SELECTED_ROUTE,
    ALL_ALTERNATIVES,
    ROUTE_COMPARISON
}

enum class ExportArtifactType(val label: String) {
    DIRECTIONS_SELECTED("turn-by-turn + map PDF"),
    DIRECTIONS_ALTERNATIVES("route alternatives PDF"),
    DIRECTIONS_COMPARISON("route comparison PDF"),
    ANALYTICS_PDF("route analytics PDF"),
    ANALYTICS_CSV("analytics CSV"),
    ROUTE_HISTORY_CSV("route history CSV"),
    SAVED_ROUTE_PDF("saved-route directions PDF"),
    GENERIC("export");

    companion object {
        fun forDirections(mode: DirectionsExportMode): ExportArtifactType = when (mode) {
            DirectionsExportMode.SELECTED_ROUTE -> DIRECTIONS_SELECTED
            DirectionsExportMode.ALL_ALTERNATIVES -> DIRECTIONS_ALTERNATIVES
            DirectionsExportMode.ROUTE_COMPARISON -> DIRECTIONS_COMPARISON
        }
    }
}

enum class ExportWorkflowOrigin {
    PREVIEW,
    TURN_BY_TURN,
    SAVED_ROUTE,
    ANALYTICS_PERIOD,
    DIRECTIONS_MENU,
    ROUTES,
    EXPORTS
}

data class PreviewRouteWorkflowState(
    /** ActiveRouteStateCodec output. Kept opaque here so the codec remains host-testable. */
    val routeState: String,
    val knownCameraCount: Int,
    val scenicScore: Double,
    val routedExposure: RoutedDeviceExposure? = null
)

/**
 * Durable export origin. Unlike the old broad enum, this contains enough immutable state to
 * reconstruct the exact preview, focused turn, saved history row, or analytics period that
 * launched Android's document picker without replanning or silently selecting another route.
 */
data class ExportWorkflowContext(
    val artifactType: ExportArtifactType,
    val origin: ExportWorkflowOrigin,
    /** ActiveRouteStateCodec output for the exact selected route/focused maneuver. */
    val routeState: String? = null,
    val previewRoutes: List<PreviewRouteWorkflowState> = emptyList(),
    val recommendedRouteIdentity: String? = null,
    val selectedRouteIdentity: String? = null,
    val savedRouteId: Long? = null,
    val analyticsSinceMillis: Long? = null,
    val analyticsPeriodLabel: String? = null
) {
    val label: String get() = artifactType.label

    fun encode(): String = listOf(
        FORMAT_VERSION,
        artifactType.name,
        origin.name,
        encodeNullable(routeState),
        previewRoutes.joinToString("~") { preview ->
            val exposure=preview.routedExposure?.let {
                "${it.encounters}:${it.conservativeEncounters}:${it.assessedAtMillis}:${it.validUntilMillis ?: NULL_TOKEN}:${it.snapshotRevision}"
            } ?: NULL_TOKEN
            "${encodeRequired(preview.routeState)},${preview.knownCameraCount},${java.lang.Double.doubleToLongBits(preview.scenicScore)},$exposure"
        },
        encodeNullable(recommendedRouteIdentity),
        encodeNullable(selectedRouteIdentity),
        savedRouteId?.toString() ?: NULL_TOKEN,
        analyticsSinceMillis?.toString() ?: NULL_TOKEN,
        encodeNullable(analyticsPeriodLabel)
    ).joinToString(".")

    companion object {
        private const val FORMAT_VERSION = "3"
        private const val NULL_TOKEN = "-"

        fun generic(): ExportWorkflowContext = ExportWorkflowContext(
            artifactType = ExportArtifactType.GENERIC,
            origin = ExportWorkflowOrigin.EXPORTS
        )

        fun decode(value: String?): ExportWorkflowContext {
            if (value.isNullOrBlank()) return generic()
            return decodeV2(value) ?: decodeLegacy(value)
        }

        private fun decodeV2(value: String): ExportWorkflowContext? = runCatching {
            val fields = value.split('.')
            require(fields.size == 10 && fields[0] in setOf("2",FORMAT_VERSION))
            val previews = if (fields[4].isBlank()) emptyList() else fields[4].split('~').map { encoded ->
                val parts = encoded.split(',')
                require(parts.size == if(fields[0]=="2") 3 else 4)
                val exposure=parts.getOrNull(3)?.takeUnless { it==NULL_TOKEN }?.split(':')?.let {
                    require(it.size==5)
                    RoutedDeviceExposure(it[0].toInt(),it[1].toInt(),it[2].toLong(),
                        it[3].takeUnless { value -> value==NULL_TOKEN }?.toLong(),it[4])
                }
                PreviewRouteWorkflowState(
                    routeState = decodeRequired(parts[0]),
                    knownCameraCount = parts[1].toInt(),
                    scenicScore = java.lang.Double.longBitsToDouble(parts[2].toLong()),
                    routedExposure = exposure
                )
            }
            ExportWorkflowContext(
                artifactType = ExportArtifactType.valueOf(fields[1]),
                origin = ExportWorkflowOrigin.valueOf(fields[2]),
                routeState = decodeNullable(fields[3]),
                previewRoutes = previews,
                recommendedRouteIdentity = decodeNullable(fields[5]),
                selectedRouteIdentity = decodeNullable(fields[6]),
                savedRouteId = fields[7].takeUnless { it == NULL_TOKEN }?.toLong(),
                analyticsSinceMillis = fields[8].takeUnless { it == NULL_TOKEN }?.toLong(),
                analyticsPeriodLabel = decodeNullable(fields[9])
            )
        }.getOrNull()

        /** Read old Gate 6 values without claiming they contain exact recovery state. */
        private fun decodeLegacy(value: String): ExportWorkflowContext = when (value) {
            "DIRECTIONS_SELECTED" -> ExportWorkflowContext(ExportArtifactType.DIRECTIONS_SELECTED, ExportWorkflowOrigin.DIRECTIONS_MENU)
            "DIRECTIONS_ALTERNATIVES" -> ExportWorkflowContext(ExportArtifactType.DIRECTIONS_ALTERNATIVES, ExportWorkflowOrigin.DIRECTIONS_MENU)
            "DIRECTIONS_COMPARISON" -> ExportWorkflowContext(ExportArtifactType.DIRECTIONS_COMPARISON, ExportWorkflowOrigin.DIRECTIONS_MENU)
            "ANALYTICS_PDF" -> ExportWorkflowContext(ExportArtifactType.ANALYTICS_PDF, ExportWorkflowOrigin.ANALYTICS_PERIOD)
            "ANALYTICS_CSV" -> ExportWorkflowContext(ExportArtifactType.ANALYTICS_CSV, ExportWorkflowOrigin.ANALYTICS_PERIOD)
            "ROUTE_HISTORY_CSV" -> ExportWorkflowContext(ExportArtifactType.ROUTE_HISTORY_CSV, ExportWorkflowOrigin.ROUTES)
            "SAVED_ROUTE_PDF" -> ExportWorkflowContext(ExportArtifactType.SAVED_ROUTE_PDF, ExportWorkflowOrigin.ROUTES)
            else -> generic()
        }

        private fun encodeRequired(value: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

        private fun decodeRequired(value: String): String = String(
            Base64.getUrlDecoder().decode(value),
            StandardCharsets.UTF_8
        )

        private fun encodeNullable(value: String?): String = value?.let(::encodeRequired) ?: NULL_TOKEN

        private fun decodeNullable(value: String): String? = value.takeUnless { it == NULL_TOKEN }?.let(::decodeRequired)
    }
}
