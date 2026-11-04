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
import com.bggg.watchman.history.RouteAnalyticsSnapshot
import com.bggg.watchman.util.Geo
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Personal, local-first route-history analytics report. */
object AnalyticsReportExporter {
    private const val PAGE_W = 612
    private const val PAGE_H = 792
    private const val MARGIN = 38f

    fun buildPdf(snapshot: RouteAnalyticsSnapshot, periodLabel: String, tileCacheDirectory: File? = null, assets: AssetManager? = null): ByteArray {
        val report = RouteAnalyticsEngine.analyze(snapshot)
        require(report.totalTrips > 0) { "No route history in the selected period" }
        val pdf = PdfDocument()
        val maps = ExportMapRenderer(tileCacheDirectory, assets)
        try {
            drawOverview(pdf, maps, report, periodLabel, 1)
            drawActivityCharts(pdf, report, periodLabel, 2)
            drawRouteFrequency(pdf, maps, report, periodLabel, 3)
            drawCameraAnalytics(pdf, maps, report, periodLabel, 4)
            drawTripTable(pdf, report, periodLabel, 5)
            val out = ByteArrayOutputStream(); pdf.writeTo(out); return out.toByteArray()
        } finally {
            maps.close()
            pdf.close()
        }
    }

    private fun drawOverview(pdf: PdfDocument, maps: ExportMapRenderer, r: RouteAnalyticsReport, period: String, pageNo: Int) {
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        val c = page.canvas; c.drawColor(Color.WHITE)
        header(c, "My WatchMan Route Analytics", "$period · generated ${dateTime(System.currentTimeMillis())}")
        metric(c, 38f, 91f, 124f, "TRIPS", r.totalTrips.toString())
        metric(c, 171f, 91f, 124f, "DISTANCE", distance(r.totalDistanceMeters))
        metric(c, 304f, 91f, 124f, "DRIVE TIME", duration(r.totalDurationMillis))
        metric(c, 437f, 91f, 137f, "CAM/SENSOR PASSES", r.actualCameraPasses.toString())

        val lines = r.routeFrequencies.take(24).mapIndexedNotNull { index, f ->
            val path = r.paths[f.representative.id] ?: runCatching { f.representative.toRoute().points }.getOrNull()
            path?.takeIf { it.size >= 2 }?.let {
                ExportMapRenderer.RouteLine(it, selected = index == 0, weight = (1f + (f.tripCount - 1) * 0.18f).coerceAtMost(2.2f), label = "${f.tripCount} trips")
            }
        }
        val cams = r.cameraFrequencies.take(120).map { it.toCameraPoi() }
        maps.draw(c, RectF(MARGIN, 154f, PAGE_W - MARGIN, 474f), lines, cams)

        c.drawText("What this report represents", MARGIN, 510f, section())
        val body = body()
        val note = if (r.trackedTripCount == r.totalTrips) {
            "All trips in this period contain actual GPS breadcrumb paths recorded locally by WatchMan."
        } else {
            "${r.trackedTripCount} of ${r.totalTrips} trips contain actual GPS breadcrumb paths. Older records without v0.2.0 breadcrumbs use their saved planned route geometry on maps."
        }
        wrap(note, 87).forEachIndexed { i, line -> c.drawText(line, MARGIN, 535f + i * 15f, body) }
        val passNote = if (r.actualCameraPasses > 0) {
            "Camera/sensor pass analytics use physical proximity encounters recorded during live trips; they are not inferred from corporate telemetry or uploaded history."
        } else {
            "No actual camera/sensor pass identities are stored for this period yet. v0.2.0 begins recording those locally for future analytics; older history only stored a route-start camera count."
        }
        wrap(passNote, 87).forEachIndexed { i, line -> c.drawText(line, MARGIN, 590f + i * 15f, body) }
        c.drawText("Local-first privacy", MARGIN, 652f, section())
        wrap("This report is generated from WatchMan's on-device SQLite history. Creating it does not upload route history to an analytics provider.", 87)
            .forEachIndexed { i, line -> c.drawText(line, MARGIN, 676f + i * 15f, body) }
        footer(c, pageNo, "Your history · your report · generated on-device")
        pdf.finishPage(page)
    }

    private fun drawActivityCharts(pdf: PdfDocument, r: RouteAnalyticsReport, period: String, pageNo: Int) {
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        val c = page.canvas; c.drawColor(Color.WHITE)
        header(c, "Travel activity", period)
        val days = r.days.takeLast(31)
        c.drawText("Trips over time", MARGIN, 105f, section())
        barChart(c, RectF(MARGIN, 122f, PAGE_W - MARGIN, 278f), days.map { it.label to it.trips.toDouble() }, "trips")
        c.drawText("Distance over time", MARGIN, 315f, section())
        barChart(c, RectF(MARGIN, 332f, PAGE_W - MARGIN, 488f), days.map { it.label to (it.distanceMeters / 1609.344) }, "mi")
        c.drawText("Route-mode usage", MARGIN, 528f, section())
        horizontalBars(c, RectF(MARGIN, 547f, PAGE_W - MARGIN, 702f), r.modeCounts.entries.map { it.key.label to it.value.toDouble() }, "trips")
        footer(c, pageNo, "Charts use only saved WatchMan trip history")
        pdf.finishPage(page)
    }

    private fun drawRouteFrequency(pdf: PdfDocument, maps: ExportMapRenderer, r: RouteAnalyticsReport, period: String, pageNo: Int) {
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        val c = page.canvas; c.drawColor(Color.WHITE)
        header(c, "Route frequency", period)
        val lines = r.routeFrequencies.take(30).mapIndexedNotNull { index, f ->
            val path = r.paths[f.representative.id] ?: runCatching { f.representative.toRoute().points }.getOrNull()
            path?.takeIf { it.size >= 2 }?.let { ExportMapRenderer.RouteLine(it, index == 0, (1f + f.tripCount * 0.16f).coerceAtMost(2.4f)) }
        }
        maps.draw(c, RectF(MARGIN, 92f, PAGE_W - MARGIN, 392f), lines)
        c.drawText("Most frequently used", MARGIN, 426f, section())
        routeRows(c, r.routeFrequencies.take(6), 451f)
        c.drawText("Least frequently used", MARGIN, 606f, section())
        routeRows(c, r.routeFrequencies.sortedWith(compareBy<AnalyticsRouteFrequency> { it.tripCount }.thenByDescending { it.representative.startedAt }).take(4), 630f)
        footer(c, pageNo, "Line weight indicates route frequency; ★ marks the most-used corridor")
        pdf.finishPage(page)
    }

    private fun drawCameraAnalytics(pdf: PdfDocument, maps: ExportMapRenderer, r: RouteAnalyticsReport, period: String, pageNo: Int) {
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        val c = page.canvas; c.drawColor(Color.WHITE)
        header(c, "Camera & sensor encounters", period)
        if (r.actualCameraPasses == 0) {
            c.drawText("No physical camera/sensor encounters are stored for this period.", MARGIN, 112f, section())
            wrap("v0.2.0 records actual passed devices once per trip when live navigation comes within the local pass radius. This makes future reports identify the devices you really passed rather than merely counting every device near a planned route.", 88)
                .forEachIndexed { i, line -> c.drawText(line, MARGIN, 144f + i * 16f, body()) }
            c.drawText("Historical route-start camera total", MARGIN, 245f, section())
            c.drawText(r.knownCameraCountAtTripStart.toString(), MARGIN, 285f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(184, 134, 43); textSize = 32f; isFakeBoldText = true })
            footer(c, pageNo, "Pass identity analytics start accumulating with v0.2.0 live trips")
            pdf.finishPage(page); return
        }
        val pathLines = r.routeFrequencies.take(24).mapIndexedNotNull { index, f ->
            val path = r.paths[f.representative.id] ?: runCatching { f.representative.toRoute().points }.getOrNull()
            path?.takeIf { it.size >= 2 }?.let {
                ExportMapRenderer.RouteLine(it, selected = index == 0, weight = (1f + f.tripCount * 0.12f).coerceAtMost(2.0f))
            }
        }
        maps.draw(c, RectF(MARGIN, 90f, PAGE_W - MARGIN, 344f), pathLines, r.cameraFrequencies.take(160).map { it.toCameraPoi() })
        c.drawText("Passes by device category", MARGIN, 378f, section())
        horizontalBars(c, RectF(MARGIN, 397f, PAGE_W - MARGIN, 548f), r.cameraTypeCounts.entries.map { it.key.label to it.value.toDouble() }, "passes")
        c.drawText("Most frequently passed devices", MARGIN, 585f, section())
        var y = 608f
        r.cameraFrequencies.take(7).forEachIndexed { idx, cam ->
            val label = cam.name?.takeIf { it.isNotBlank() } ?: cam.type.label
            c.drawText("${idx + 1}. ${trim(label, 52)}", MARGIN, y, body().apply { isFakeBoldText = true })
            c.drawText("${cam.passCount} pass${if (cam.passCount == 1) "" else "es"} · ${cam.type.label}", 383f, y, body())
            y += 18f
        }
        footer(c, pageNo, "Encounter counts are de-duplicated per camera/sensor per trip")
        pdf.finishPage(page)
    }

    private fun drawTripTable(pdf: PdfDocument, r: RouteAnalyticsReport, period: String, startPage: Int) {
        var pageNo = startPage
        var index = 0
        val entries = r.snapshot.entries.sortedByDescending { it.startedAt }
        while (index < entries.size) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            val c = page.canvas; c.drawColor(Color.WHITE)
            header(c, "Trip detail", "$period · newest first")
            var y = 100f
            while (index < entries.size && y < 720f) {
                val e = entries[index]
                val track = r.snapshot.tracks[e.id].orEmpty()
                val actual = if (track.size >= 2) Geo.routeLengthMeters(track.map { it.point }) else e.distanceMeters
                c.drawText("${dateTime(e.startedAt)} · ${e.preference.label}", MARGIN, y, body().apply { isFakeBoldText = true })
                c.drawText("${trim(e.originName, 35)} → ${trim(e.destinationName, 35)}", MARGIN, y + 15f, body())
                val passCount = r.snapshot.encounters.count { it.tripId == e.id }
                val source = if (track.size >= 2) "GPS path" else "planned route"
                c.drawText("${distance(actual)} · $source · $passCount passed cams/sensors", MARGIN, y + 30f, body().apply { textSize = 9f; color = Color.rgb(100, 100, 100) })
                y += 53f; index++
            }
            footer(c, pageNo, "${index} of ${entries.size} trips listed")
            pdf.finishPage(page); pageNo++
        }
    }

    private fun routeRows(c: Canvas, rows: List<AnalyticsRouteFrequency>, startY: Float) {
        var y = startY
        rows.forEachIndexed { idx, f ->
            val e = f.representative
            c.drawText("${idx + 1}. ${trim(e.originName, 29)} → ${trim(e.destinationName, 29)}", MARGIN, y, body().apply { isFakeBoldText = true })
            c.drawText("${f.tripCount} trip${if (f.tripCount == 1) "" else "s"}", 470f, y, body())
            y += 22f
        }
    }

    private fun metric(c: Canvas, x: Float, y: Float, w: Float, label: String, value: String) {
        val rect = RectF(x, y, x + w, y + 48f)
        c.drawRoundRect(rect, 8f, 8f, Paint().apply { color = Color.rgb(247, 244, 235) })
        c.drawText(label, x + 8f, y + 15f, body().apply { textSize = 7.5f; color = Color.rgb(112, 98, 72); isFakeBoldText = true })
        c.drawText(trim(value, 18), x + 8f, y + 35f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 36, 39); textSize = 15f; isFakeBoldText = true })
    }

    private fun barChart(c: Canvas, rect: RectF, data: List<Pair<String, Double>>, unit: String) {
        c.drawRect(rect, Paint().apply { color = Color.rgb(249, 249, 247) })
        if (data.isEmpty()) { c.drawText("No data", rect.left + 10f, rect.centerY(), body()); return }
        val maxV = max(1.0, data.maxOf { it.second })
        val barW = rect.width() / data.size.coerceAtMost(31)
        data.takeLast(31).forEachIndexed { idx, item ->
            val h = ((item.second / maxV) * (rect.height() - 31f)).toFloat()
            val left = rect.left + idx * barW + 2f
            c.drawRect(left, rect.bottom - 22f - h, left + max(2f, barW - 4f), rect.bottom - 22f, Paint().apply { color = Color.rgb(184, 134, 43) })
            if (data.size <= 10 || idx % 3 == 0) c.drawText(item.first.take(6), left, rect.bottom - 8f, body().apply { textSize = 6.5f })
        }
        c.drawText("max ${String.format(Locale.US, "%.1f", maxV)} $unit", rect.left + 5f, rect.top + 11f, body().apply { textSize = 7f; color = Color.rgb(100, 100, 100) })
    }

    private fun horizontalBars(c: Canvas, rect: RectF, data: List<Pair<String, Double>>, unit: String) {
        c.drawRect(rect, Paint().apply { color = Color.rgb(249, 249, 247) })
        if (data.isEmpty()) { c.drawText("No data", rect.left + 10f, rect.centerY(), body()); return }
        val visible = data.take(7); val maxV = max(1.0, visible.maxOf { it.second }); val rowH = rect.height() / visible.size
        visible.forEachIndexed { idx, item ->
            val y = rect.top + idx * rowH
            c.drawText(trim(item.first, 26), rect.left + 5f, y + rowH * 0.62f, body().apply { textSize = 8f })
            val start = rect.left + 155f
            val width = ((item.second / maxV) * (rect.width() - 215f)).toFloat()
            c.drawRect(start, y + rowH * 0.25f, start + width, y + rowH * 0.72f, Paint().apply { color = Color.rgb(73, 115, 153) })
            c.drawText("${item.second.toInt()} $unit", rect.right - 57f, y + rowH * 0.62f, body().apply { textSize = 7.5f })
        }
    }

    private fun AnalyticsCameraFrequency.toCameraPoi() = CameraPoi(
        id = cameraId,
        type = type,
        point = point,
        name = name,
        road = null,
        direction = null,
        operator = null,
        manufacturer = null,
        source = source,
        sourceId = "history_analytics",
        sourceRecordId = cameraId
    )

    private fun header(c: Canvas, title: String, subtitle: String) {
        c.drawText(title, MARGIN, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(29, 31, 34); textSize = 22f; isFakeBoldText = true })
        c.drawText(trim(subtitle, 90), MARGIN, 67f, body().apply { textSize = 9.5f; color = Color.rgb(100, 101, 104) })
        c.drawLine(MARGIN, 76f, PAGE_W - MARGIN, 76f, Paint().apply { color = Color.rgb(184, 134, 43); strokeWidth = 1.4f })
    }

    private fun footer(c: Canvas, page: Int, text: String) {
        val p = body().apply { textSize = 7.5f; color = Color.rgb(112, 112, 112) }
        c.drawLine(MARGIN, PAGE_H - 38f, PAGE_W - MARGIN, PAGE_H - 38f, Paint().apply { color = Color.rgb(225, 225, 222); strokeWidth = 0.7f })
        c.drawText(trim(text, 87), MARGIN, PAGE_H - 22f, p)
        p.textAlign = Paint.Align.RIGHT; c.drawText("WatchMan · $page", PAGE_W - MARGIN, PAGE_H - 22f, p)
    }

    private fun section() = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(39, 41, 44); textSize = 14f; isFakeBoldText = true }
    private fun body() = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(57, 59, 63); textSize = 10f }
    private fun trim(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
    private fun wrap(text: String, width: Int): List<String> {
        val words = text.split(Regex("\\s+")); val out = ArrayList<String>(); var line = StringBuilder()
        words.forEach { word ->
            if (line.isNotEmpty() && line.length + word.length + 1 > width) { out += line.toString(); line = StringBuilder() }
            if (line.isNotEmpty()) line.append(' '); line.append(word)
        }
        if (line.isNotEmpty()) out += line.toString(); return out
    }
    private fun distance(m: Double) = String.format(Locale.US, "%.1f mi", m / 1609.344)
    private fun duration(ms: Long): String { val mins = ms / 60000; return if (mins < 60) "${mins}m" else "${mins / 60}h ${mins % 60}m" }
    private fun dateTime(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
}
