package com.bggg.watchman.export

import android.content.res.AssetManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.data.RoutePreference
import com.bggg.watchman.data.RoutedDeviceExposure
import com.bggg.watchman.history.RouteHistoryEntry
import com.bggg.watchman.util.Geo
import com.bggg.watchman.ui.MapDeviceDeclutter
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt


data class RouteExportSelection(
    val route: Route,
    val originName: String,
    val origin: GeoPoint,
    val preference: RoutePreference,
    /** Complete nearby-awareness population in the traveled route corridor. */
    val cameras: List<CameraPoi>,
    val selected: Boolean,
    val label: String,
    /** Broader regional devices available to the rendered map viewport. */
    val mapCameras: List<CameraPoi> = cameras,
    val routedExposure: RoutedDeviceExposure? = null
)

/**
 * Printable navigation package. Maps use OpenStreetMap tiles already cached by the interactive
 * map, with bundled offline road context where available. Maneuver glyphs are
 * native WatchMan artwork.
 */
object DirectionsExporter {
    private const val PAGE_W = 612
    private const val PAGE_H = 792
    private const val MARGIN = 38f

    fun buildDirectionsPdf(
        selections: List<RouteExportSelection>,
        mode: DirectionsExportMode = DirectionsExportMode.SELECTED_ROUTE,
        tileCacheDirectory: File? = null,
        assets: AssetManager? = null
    ): ByteArray {
        require(selections.isNotEmpty()) { "No route selected for export" }
        val preparedAt=System.currentTimeMillis()
        val frozenSelections=selections.map { selection ->
            val exposure=selection.routedExposure
            if(exposure!=null && (preparedAt<exposure.assessedAtMillis ||
                    exposure.validUntilMillis?.let { preparedAt>=it }==true)) selection.copy(routedExposure=null) else selection
        }
        val pdf = PdfDocument()
        val maps = ExportMapRenderer(tileCacheDirectory, assets)
        var pageNumber = 1
        try {
            drawCover(pdf, maps, frozenSelections, mode, pageNumber++)
            if (mode == DirectionsExportMode.ROUTE_COMPARISON) {
                drawComparisonTable(pdf, maps, frozenSelections, pageNumber++)
            } else {
                frozenSelections.forEach { selection ->
                    drawRouteOverview(pdf, maps, selection, pageNumber++)
                    pageNumber = drawSteps(pdf, selection, pageNumber)
                    pageNumber = drawCameras(pdf, selection, pageNumber)
                }
            }
            val out = ByteArrayOutputStream()
            pdf.writeTo(out)
            return out.toByteArray()
        } finally {
            maps.close()
            pdf.close()
        }
    }

    /** Compatibility entry point for saved-route exports. */
    fun buildDirectionsPdf(route: Route, originName: String, origin: GeoPoint, cameras: List<CameraPoi>): ByteArray =
        buildDirectionsPdf(
            listOf(
                RouteExportSelection(
                    route = route,
                    originName = originName,
                    origin = origin,
                    preference = RoutePreference.FASTEST,
                    cameras = cameras,
                    selected = true,
                    label = "Selected route"
                )
            )
        )

    fun routeHistoryCsv(entries: List<RouteHistoryEntry>): ByteArray {
        val counts = entries.groupingBy { it.routeKey }.eachCount()
        val sb = StringBuilder()
        sb.append("trip_id,started_at,ended_at,origin,destination,route_mode,distance_m,duration_s,camera_count,frequency,provider\n")
        entries.forEach { e ->
            sb.append(e.id).append(',')
                .append(csv(iso(e.startedAt))).append(',')
                .append(csv(e.endedAt?.let(::iso) ?: "")).append(',')
                .append(csv(e.originName)).append(',')
                .append(csv(e.destinationName)).append(',')
                .append(csv(e.preference.label)).append(',')
                .append(String.format(Locale.US, "%.1f", e.distanceMeters)).append(',')
                .append(String.format(Locale.US, "%.1f", e.durationSeconds)).append(',')
                .append(e.cameraCount).append(',')
                .append(counts[e.routeKey] ?: 1).append(',')
                .append(csv(e.provider)).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun drawCover(
        pdf: PdfDocument,
        maps: ExportMapRenderer,
        selections: List<RouteExportSelection>,
        mode: DirectionsExportMode,
        pageNumber: Int
    ) {
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
        val c = page.canvas
        c.drawColor(Color.WHITE)
        val first = selections.firstOrNull { it.selected } ?: selections.first()
        drawHeader(c, when (mode) {
            DirectionsExportMode.SELECTED_ROUTE -> "WatchMan Directions"
            DirectionsExportMode.ALL_ALTERNATIVES -> "WatchMan Route Alternatives"
            DirectionsExportMode.ROUTE_COMPARISON -> "WatchMan Route Comparison"
        }, "Navigation package · generated ${iso(System.currentTimeMillis())}")

        val h = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(32, 34, 38); textSize = 12f; isFakeBoldText = true }
        val body = bodyPaint()
        c.drawText("FROM", MARGIN, 93f, h)
        c.drawText(trim(first.originName, 82), MARGIN, 111f, body)
        c.drawText("TO", MARGIN, 140f, h)
        c.drawText(trim(first.route.destination.displayName, 82), MARGIN, 158f, body)

        val mapRect = RectF(MARGIN, 184f, PAGE_W - MARGIN, 430f)
        val unionCameras = selections.flatMap { selection ->
            MapDeviceDeclutter.select(selection.route, selection.mapCameras, MapDeviceDeclutter.PDF_ROUTE_LIMIT)
        }.distinctBy { "${it.sourceId}:${it.sourceRecordId ?: it.id}" }
            .take(MapDeviceDeclutter.PDF_MULTI_ROUTE_LIMIT)
        maps.draw(
            c,
            mapRect,
            selections.map { ExportMapRenderer.RouteLine(it.route.points, it.selected, label = it.label) },
            unionCameras,
            first.origin,
            first.route.destination.point,
            showCameraLabels = false
        )

        var y = 460f
        for ((index, s) in selections.withIndex()) {
            val marker = if (s.selected) "SELECTED" else "ALT ${index + 1}"
            c.drawText("$marker · ${s.label}", MARGIN, y, h)
            c.drawText(
                "${Geo.distanceText(s.route.distanceMeters)} · ${Geo.etaText(s.route.durationSeconds)} · ${s.cameras.size} nearby / ${routedCount(s)} routed · ${s.preference.label}",
                MARGIN, y + 17f, body
            )
            y += 38f
            if (y > 690f) break
        }

        drawLegend(c, MARGIN, 708f)
        footer(c, pageNumber, "Nearby includes awareness devices. Routed counts retain conservative unknowns; '-' means not verified.")
        pdf.finishPage(page)
    }

    private fun drawRouteOverview(
        pdf: PdfDocument,
        maps: ExportMapRenderer,
        selection: RouteExportSelection,
        pageNumber: Int
    ) {
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
        val c = page.canvas; c.drawColor(Color.WHITE)
        drawHeader(c, selection.label, "${selection.preference.label} · ${Geo.distanceText(selection.route.distanceMeters)} · ${Geo.etaText(selection.route.durationSeconds)}")
        val body = bodyPaint(); val strong = bodyPaint().apply { isFakeBoldText = true }
        c.drawText("From: ${trim(selection.originName, 77)}", MARGIN, 91f, body)
        c.drawText("To: ${trim(selection.route.destination.displayName, 80)}", MARGIN, 108f, body)

        maps.draw(
            c,
            RectF(MARGIN, 128f, PAGE_W - MARGIN, 430f),
            listOf(ExportMapRenderer.RouteLine(selection.route.points, true, label = selection.label)),
            MapDeviceDeclutter.select(selection.route, selection.mapCameras, MapDeviceDeclutter.PDF_ROUTE_LIMIT),
            selection.origin,
            selection.route.destination.point,
            showCameraLabels = selection.mapCameras.size <= 35
        )

        c.drawText("Route at a glance", MARGIN, 463f, sectionPaint())
        val counts = selection.cameras.groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }
        var y = 489f
        c.drawText("Turn maneuvers", MARGIN, y, strong); c.drawText(selection.route.steps.size.toString(), MARGIN + 120f, y, body); y += 19f
        c.drawText("Nearby devices", MARGIN, y, strong); c.drawText(selection.cameras.size.toString(), MARGIN + 120f, y, body); y += 19f
        c.drawText("Routed encounters", MARGIN, y, strong); c.drawText(routedCount(selection), MARGIN + 120f, y, body); y += 23f
        counts.take(8).forEach { (type, count) ->
            c.drawCircle(MARGIN + 4f, y - 4f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cameraColor(type) })
            c.drawText(type.label, MARGIN + 14f, y, body)
            c.drawText(count.toString(), MARGIN + 210f, y, strong)
            y += 17f
        }
        if(counts.size>8) {
            c.drawText("Other device categories",MARGIN+14f,y,body)
            c.drawText(counts.drop(8).sumOf { it.value }.toString(),MARGIN+210f,y,strong)
        }
        c.drawText("The following pages list each maneuver in travel order with a WatchMan maneuver sign and distance.", MARGIN, 714f, body)
        footer(c, pageNumber, "Map © OpenStreetMap contributors")
        pdf.finishPage(page)
    }

    private fun drawSteps(pdf: PdfDocument, selection: RouteExportSelection, startPage: Int): Int {
        val steps = selection.route.steps
        var pageNumber = startPage
        var index = 0
        if (steps.isEmpty()) return pageNumber
        while (index < steps.size) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
            val c = page.canvas; c.drawColor(Color.WHITE)
            drawHeader(c, "Turn-by-turn directions", "${selection.label} · ${selection.originName.take(42)} → ${selection.route.destination.displayName.take(42)}")
            var y = 92f
            while (index < steps.size && y < PAGE_H - 90f) {
                val step = steps[index]
                val instructionLines = wrap(step.instruction, 58)
                val roadSign = stepRoadSign(step)
                val laneText = if (step.lanes.isEmpty()) "" else step.lanes.joinToString("   ") { lane ->
                    val indication = lane.indications.joinToString("/").ifBlank { "straight" }
                    if (lane.valid) "[${indication.uppercase(Locale.US)}]" else indication.lowercase(Locale.US)
                }
                val stepCameras = camerasForStep(selection.route, index, selection.cameras)
                val cameraLines = stepCameras.take(3).map { camera ->
                    val name = camera.name?.takeIf { it.isNotBlank() } ?: camera.type.label
                    "${camera.type.label}: ${trim(name, 48)}"
                }
                val extraCameraLine = if (stepCameras.size > 3) listOf("+${stepCameras.size - 3} more camera/sensor devices before next maneuver") else emptyList()
                val camLines = cameraLines + extraCameraLine
                val cardH = 70f +
                    (instructionLines.size - 1).coerceAtLeast(0) * 14f +
                    (if (roadSign.isNotBlank()) 13f else 0f) +
                    (if (laneText.isNotBlank()) 16f else 0f) +
                    camLines.size * 14f
                if (y + cardH > PAGE_H - 54f && y > 105f) break
                val card = RectF(MARGIN, y, PAGE_W - MARGIN, y + cardH)
                c.drawRoundRect(card, 8f, 8f, Paint().apply { color = if (index % 2 == 0) Color.rgb(249, 249, 247) else Color.WHITE })
                ManeuverIconRenderer.draw(c, RectF(MARGIN + 9f, y + 11f, MARGIN + 57f, y + 59f), step)

                val left = MARGIN + 68f
                var textY = y + 16f
                if (roadSign.isNotBlank()) {
                    val signPaint = bodyPaint().apply { textSize = 8.8f; isFakeBoldText = true; color = Color.rgb(139, 94, 20) }
                    c.drawText(trim(roadSign, 58), left, textY, signPaint)
                    textY += 14f
                }
                val number = bodyPaint().apply { textSize = 8.5f; color = Color.rgb(112, 112, 112); isFakeBoldText = true }
                c.drawText("${index + 1} · ${Geo.distanceText(step.distanceMeters)}", left, textY, number)
                textY += 16f
                val instructionPaint = bodyPaint().apply { textSize = 12f; isFakeBoldText = true; color = Color.rgb(35, 37, 40) }
                instructionLines.forEach { line -> c.drawText(line, left, textY, instructionPaint); textY += 14f }

                val detailParts = ArrayList<String>()
                if (step.roadName.isNotBlank() && !step.instruction.contains(step.roadName, ignoreCase = true)) detailParts += step.roadName
                if (step.destinations.isNotBlank() && !step.instruction.contains(step.destinations, ignoreCase = true)) detailParts += "toward ${step.destinations}"
                if (detailParts.isNotEmpty()) {
                    c.drawText(trim(detailParts.joinToString(" · "), 68), left, textY, bodyPaint().apply { textSize = 9.2f; color = Color.rgb(92, 94, 98) })
                    textY += 13f
                }
                if (laneText.isNotBlank()) {
                    c.drawText(trim("Lanes: $laneText", 72), left, textY, bodyPaint().apply { textSize = 8.8f; isFakeBoldText = true; color = Color.rgb(45, 115, 69) })
                    textY += 14f
                }
                if (camLines.isNotEmpty()) {
                    c.drawText("Nearby cameras / sensors before next maneuver", left, textY, bodyPaint().apply { textSize = 8.8f; isFakeBoldText = true; color = Color.rgb(178, 109, 28) })
                    textY += 13f
                    camLines.forEach { line ->
                        c.drawText(trim("• $line", 72), left, textY, bodyPaint().apply { textSize = 8.4f; color = Color.rgb(91, 69, 42) })
                        textY += 13f
                    }
                }
                y += cardH + 7f
                index++
            }
            footer(c, pageNumber, "Turn-by-turn · ${index} of ${steps.size} maneuvers listed · route devices are grouped by maneuver leg")
            pdf.finishPage(page)
            pageNumber++
        }
        return pageNumber
    }

    private fun drawCameras(pdf: PdfDocument, selection: RouteExportSelection, startPage: Int): Int {
        var pageNumber = startPage
        val cameras = selection.cameras.sortedBy { cameraDistanceAlong(selection.route, it) }
        if (cameras.isEmpty()) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
            page.canvas.drawColor(Color.WHITE)
            drawHeader(page.canvas, "Nearby cameras / sensors", selection.label)
            page.canvas.drawText("No cached cameras or sensors were found inside the route corridor at export time.", MARGIN, 105f, bodyPaint())
            footer(page.canvas, pageNumber, "Camera coverage depends on available public sources and current cache state.")
            pdf.finishPage(page)
            return pageNumber + 1
        }

        var index = 0
        while (index < cameras.size) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
            val c = page.canvas; c.drawColor(Color.WHITE)
            drawHeader(c, "Nearby cameras / sensors", "${selection.label} · awareness, ordered from route start")
            var y = 92f
            while (index < cameras.size && y < PAGE_H - 70f) {
                val cam = cameras[index]
                val along = cameraDistanceAlong(selection.route, cam)
                c.drawCircle(MARGIN + 7f, y - 4f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cameraColor(cam.type) })
                val strong = bodyPaint().apply { isFakeBoldText = true; textSize = 10.5f }
                c.drawText("${index + 1}. ${cam.type.label}${cam.name?.let { " · ${trim(it, 43)}" } ?: ""}", MARGIN + 20f, y, strong)
                c.drawText(
                    "${Geo.distanceText(along)} from start${cam.road?.let { " · ${trim(it, 42)}" } ?: ""}",
                    MARGIN + 20f, y + 15f, bodyPaint().apply { textSize = 9.5f }
                )
                val meta = listOfNotNull(cam.direction?.let { "dir $it" }, cam.operator, cam.manufacturer).joinToString(" · ")
                if (meta.isNotBlank()) c.drawText(trim(meta, 76), MARGIN + 20f, y + 29f, bodyPaint().apply { textSize = 8.7f; color = Color.rgb(98, 98, 98) })
                y += if (meta.isBlank()) 38f else 50f
                index++
            }
            footer(c, pageNumber, "${cameras.size} cameras/sensors in this route export")
            pdf.finishPage(page)
            pageNumber++
        }
        return pageNumber
    }

    private fun drawComparisonTable(
        pdf: PdfDocument,
        maps: ExportMapRenderer,
        selections: List<RouteExportSelection>,
        pageNumber: Int
    ) {
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
        val c = page.canvas; c.drawColor(Color.WHITE)
        drawHeader(c, "Route comparison", "Distance, ETA and known camera/sensor exposure")
        val first = selections.firstOrNull { it.selected } ?: selections.first()
        maps.draw(
            c,
            RectF(MARGIN, 86f, PAGE_W - MARGIN, 354f),
            selections.map { ExportMapRenderer.RouteLine(it.route.points, it.selected, label = it.label) },
            selections.flatMap { selection ->
                MapDeviceDeclutter.select(selection.route, selection.mapCameras, MapDeviceDeclutter.PDF_ROUTE_LIMIT)
            }.distinctBy { "${it.sourceId}:${it.sourceRecordId ?: it.id}" }
                .take(MapDeviceDeclutter.PDF_MULTI_ROUTE_LIMIT),
            first.origin,
            first.route.destination.point
        )
        var y = 387f
        val header = bodyPaint().apply { isFakeBoldText = true; textSize = 9.5f }
        val body = bodyPaint().apply { textSize = 9.2f }
        c.drawText("ROUTE", MARGIN, y, header)
        c.drawText("DISTANCE", 260f, y, header)
        c.drawText("ETA", 350f, y, header)
        c.drawText("NEAR / ROUTED", 430f, y, header)
        y += 18f
        selections.forEachIndexed { idx, s ->
            val row = RectF(MARGIN, y - 13f, PAGE_W - MARGIN, y + 18f)
            if (idx % 2 == 0) c.drawRect(row, Paint().apply { color = Color.rgb(248, 248, 246) })
            val label = (if (s.selected) "★ " else "") + s.label
            c.drawText(trim(label, 29), MARGIN + 4f, y + 5f, if (s.selected) header else body)
            c.drawText(Geo.distanceText(s.route.distanceMeters), 260f, y + 5f, body)
            c.drawText(Geo.etaText(s.route.durationSeconds), 350f, y + 5f, body)
            c.drawText("${s.cameras.size} / ${routedCount(s)}", 440f, y + 5f, body)
            y += 34f
        }
        y += 12f
        c.drawText("Camera / sensor category comparison", MARGIN, y, sectionPaint()); y += 24f
        val types = CameraType.values().filter { t -> selections.any { s -> s.cameras.any { it.type == t } } }
        types.take(9).forEach { type ->
            c.drawCircle(MARGIN + 5f, y - 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cameraColor(type) })
            c.drawText(type.label, MARGIN + 17f, y, body)
            selections.take(3).forEachIndexed { idx, s ->
                val count = s.cameras.count { it.type == type }
                c.drawText("R${idx + 1}: $count", 320f + idx * 65f, y, body)
            }
            y += 17f
        }
        footer(c, pageNumber, "★ selected · nearby is awareness; routed includes conservative unknowns. '-' is not verified.")
        pdf.finishPage(page)
    }

    private fun camerasForStep(route: Route, stepIndex: Int, cameras: List<CameraPoi>): List<CameraPoi> {
        if (route.steps.isEmpty() || cameras.isEmpty()) return emptyList()
        val start = if (stepIndex <= 0) 0.0 else {
            Geo.projectOntoRoute(route.steps[stepIndex].maneuver, route.points)?.distanceAlongMeters ?: 0.0
        }
        val end = if (stepIndex >= route.steps.lastIndex) route.distanceMeters + 100.0 else {
            Geo.projectOntoRoute(route.steps[stepIndex + 1].maneuver, route.points)?.distanceAlongMeters ?: route.distanceMeters
        }
        val lo = minOf(start, end) - 30.0
        val hi = maxOf(start, end) + 30.0
        return cameras.filter { camera ->
            val along = Geo.projectOntoRoute(camera.point, route.points)?.distanceAlongMeters ?: return@filter false
            along in lo..hi
        }.sortedBy { cameraDistanceAlong(route, it) }
    }

    private fun stepRoadSign(step: com.bggg.watchman.data.RouteStep): String = buildString {
        if (step.exits.isNotBlank()) append("EXIT ${step.exits}")
        if (step.roadRef.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append(step.roadRef)
        }
        if (step.rotaryName.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append(step.rotaryName)
        }
    }

    private fun cameraDistanceAlong(route: Route, camera: CameraPoi): Double =
        Geo.projectOntoRoute(camera.point, route.points)?.distanceAlongMeters ?: Double.MAX_VALUE

    private fun drawHeader(c: Canvas, title: String, subtitle: String) {
        c.drawText(title, MARGIN, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(29, 31, 34); textSize = 22f; isFakeBoldText = true })
        c.drawText(trim(subtitle, 92), MARGIN, 67f, bodyPaint().apply { textSize = 9.5f; color = Color.rgb(100, 101, 104) })
        c.drawLine(MARGIN, 76f, PAGE_W - MARGIN, 76f, Paint().apply { color = Color.rgb(184, 134, 43); strokeWidth = 1.4f })
    }

    private fun drawLegend(c: Canvas, x: Float, y: Float) {
        val body = bodyPaint().apply { textSize = 8.7f }
        var dx = x
        listOf(
            CameraType.SPEED to "Speed",
            CameraType.RED_LIGHT to "Red-light",
            CameraType.ALPR to "ALPR/LPR",
            CameraType.SENSOR to "Sensor",
            CameraType.TRAFFIC to "Traffic"
        ).forEach { (type, label) ->
            c.drawCircle(dx + 4f, y - 3f, 3.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cameraColor(type) })
            c.drawText(label, dx + 11f, y, body)
            dx += 94f
        }
    }

    private fun footer(c: Canvas, page: Int, text: String) {
        val p = bodyPaint().apply { textSize = 7.6f; color = Color.rgb(112, 112, 112) }
        c.drawLine(MARGIN, PAGE_H - 38f, PAGE_W - MARGIN, PAGE_H - 38f, Paint().apply { color = Color.rgb(225, 225, 222); strokeWidth = 0.7f })
        c.drawText(trim(text, 86), MARGIN, PAGE_H - 22f, p)
        p.textAlign = Paint.Align.RIGHT
        c.drawText("WatchMan · $page", PAGE_W - MARGIN, PAGE_H - 22f, p)
    }

    private fun sectionPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(39, 41, 44); textSize = 15f; isFakeBoldText = true }
    private fun bodyPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(57, 59, 63); textSize = 10.5f }

    private fun routedCount(selection: RouteExportSelection): String {
        val exposure=selection.routedExposure ?: return "-"
        return exposure.encounters.toString()
    }

    private fun cameraColor(type: CameraType): Int = when (type) {
        CameraType.RED_LIGHT -> Color.rgb(194, 49, 45)
        CameraType.SPEED -> Color.rgb(215, 110, 32)
        CameraType.TRAFFIC, CameraType.HIGHWAY -> Color.rgb(54, 112, 178)
        CameraType.ALPR, CameraType.FLOCK -> Color.rgb(116, 73, 155)
        CameraType.TOLL -> Color.rgb(39, 130, 109)
        CameraType.SENSOR -> Color.rgb(77, 143, 77)
        CameraType.ACOUSTIC -> Color.rgb(181, 91, 44)
        CameraType.RF_SENSOR -> Color.rgb(28, 132, 147)
        CameraType.SURVEILLANCE -> Color.rgb(105, 93, 145)
        CameraType.OTHER -> Color.rgb(90, 90, 90)
    }

    private fun wrap(text: String, width: Int): List<String> {
        if (text.length <= width) return listOf(text)
        val words = text.split(Regex("\\s+")); val lines = ArrayList<String>(); var line = StringBuilder()
        for (word in words) {
            if (line.isNotEmpty() && line.length + 1 + word.length > width) { lines += line.toString(); line = StringBuilder() }
            if (line.isNotEmpty()) line.append(' '); line.append(word)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    private fun trim(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1) + "…"
    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
    private fun csv(v: String): String = "\"${v.replace("\"", "\"\"")}\""
}
