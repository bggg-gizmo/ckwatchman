package com.bggg.watchman

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.StrictMode
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.data.SearchResult
import com.bggg.watchman.storage.CameraDatabase
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

/** Real SQLite/Activity smoke test; synthetic locations and preview geometry are not route acceptance. */
object AndroidSnapshotCheck {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val source = "instrumentation_snapshot_${SystemClock.elapsedRealtime()}"
        val first = GeoPoint(-30.0, -150.0)
        val second = GeoPoint(-31.0, -150.05)
        // Within the 25km snapshot radius, outside ordinary nearby audio thresholds.
        val firstCamera = GeoPoint(first.lat + 0.05, first.lon)
        val secondCamera = GeoPoint(second.lat + 0.05, second.lon)
        fun fixture(id: String, point: GeoPoint) = CameraPoi(
            id, CameraType.ALPR, point, "INSTRUMENTATION ONLY", null, null, null, null,
            source = "Synthetic Android test fixture, not public acquisition", sourceId = source,
            sourceRecordId = id, tags = mapOf("watchman:test_fixture" to "true"))
        val db = CameraDatabase(context)
        val violations = ConcurrentLinkedQueue<String>()
        val dispatchTimes = mutableListOf<Long>()
        var activity: Activity? = null
        try {
            db.replaceSourceSnapshot(source, listOf(fixture("fixture-A", firstCamera), fixture("fixture-B", secondCamera)))
            context.getSharedPreferences("watchman", Context.MODE_PRIVATE).edit()
                .putString("v026_first_run_decision", "nav").putBoolean("activeNav", false).commit()
            val current = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = current
            fun findText(view: View, value: String): TextView? {
                if (view is TextView && view.text.toString() == value && view.isShown) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) findText(view.getChildAt(index), value)?.let { return it }
                return null
            }
            waitUntil(15000L) {
                var shown = false
                instrumentation.runOnMainSync { shown = findText(current.findViewById(android.R.id.content), "Jump to Nav") != null }
                shown
            }
            instrumentation.runOnMainSync { checkNotNull(findText(current.findViewById(android.R.id.content), "Jump to Nav")).performClick() }
            val onLocation = MainActivity::class.java.getDeclaredMethod("onLocation", Location::class.java).apply { isAccessible = true }
            val cameras = MainActivity::class.java.getDeclaredField("currentCameras").apply { isAccessible = true }
            fun location(point: GeoPoint) {
                val fix = Location("watchman-instrumentation").apply {
                    latitude = point.lat; longitude = point.lon; accuracy = 1f
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                instrumentation.runOnMainSync {
                    val original = StrictMode.getThreadPolicy()
                    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites()
                        .penaltyListener(Executor { it.run() }) { violations.add(it.stackTraceToString()) }.build())
                    try {
                        val began = SystemClock.elapsedRealtimeNanos()
                        onLocation.invoke(current, fix)
                        dispatchTimes.add((SystemClock.elapsedRealtimeNanos() - began) / 1_000_000L)
                    } finally { StrictMode.setThreadPolicy(original) }
                }
            }
            fun displayedFixtureIds(): Set<String> {
                var ids = emptySet<String>()
                instrumentation.runOnMainSync {
                    @Suppress("UNCHECKED_CAST")
                    val snapshot = cameras.get(current) as List<CameraPoi>
                    ids = snapshot.filter { it.sourceId == source }.map { it.id }.toSet()
                }
                return ids
            }
            repeat(20) { location(first) }
            waitUntil(30000L) { displayedFixtureIds() == setOf("fixture-A") }
            repeat(20) { location(second) }
            waitUntil(30000L) { displayedFixtureIds() == setOf("fixture-B") }
            // These are minimal geometry fixtures for repository/context isolation, not computed road routes.
            fun preview(point: GeoPoint) = Route(listOf(point, GeoPoint(point.lat + 0.001, point.lon)), emptyList(),
                111.0, 20.0, SearchResult("INSTRUMENTATION preview", point))
            val previewA = preview(firstCamera)
            val previewB = preview(secondCamera)
            val previewField = MainActivity::class.java.getDeclaredField("previewRoute").apply { isAccessible = true }
            val updatePreview = MainActivity::class.java.getDeclaredMethod("updatePreviewCameraLayer").apply { isAccessible = true }
            instrumentation.runOnMainSync {
                previewField.set(current, previewA)
                updatePreview.invoke(current)
            }
            waitUntil(30000L) { displayedFixtureIds() == setOf("fixture-A") }
            instrumentation.runOnMainSync {
                repeat(20) { index ->
                    previewField.set(current, if (index % 2 == 0) previewA else previewB)
                    updatePreview.invoke(current)
                }
            }
            waitUntil(30000L) { displayedFixtureIds() == setOf("fixture-B") }
            instrumentation.waitForIdleSync()
            check(violations.isEmpty()) { "Location callbacks performed UI-thread disk I/O:\n${violations.joinToString("\n")}" }
            val sorted = dispatchTimes.sorted()
            val uiResult = AndroidUiNoOpCheck.run(instrumentation, current)
            return "PASS: 40 injected Location callbacks, A/B SQLite publication, 20-preview burst final B, " +
                "zero direct callback disk violations; callback p95=${sorted[(sorted.size - 1) * 95 / 100]}ms max=${sorted.last()}ms. " +
                "$uiResult Not full frame, GPS-provider, routing, or physical-device acceptance."
        } finally {
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            db.replaceSourceSnapshot(source, emptyList())
            db.close()
        }
    }

    private fun waitUntil(timeout: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for expected Activity/database snapshot" }
            SystemClock.sleep(50L)
        }
    }
}
