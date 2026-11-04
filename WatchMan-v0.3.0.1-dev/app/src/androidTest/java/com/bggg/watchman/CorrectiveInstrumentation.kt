package com.bggg.watchman

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.IdentityHashMap

/** Runs the real Activity and view hierarchy on Android with the full packaged data assets. */
class CorrectiveInstrumentation : Instrumentation() {
    private var suite = "startup"
    private var suiteArguments = Bundle()
    private data class DrawEvent(val at: Long, val screen: String)
    private val frames = IdentityHashMap<Activity, MutableList<DrawEvent>>()

    override fun callActivityOnCreate(activity: Activity, savedInstanceState: Bundle?) {
        super.callActivityOnCreate(activity, savedInstanceState)
        if (suite != "startup" || activity !is MainActivity) return
        val events = mutableListOf<DrawEvent>()
        synchronized(frames) { frames[activity] = events }
        // Observe traversal before startActivitySync's idle wait returns. Looking for a
        // transient intro only after that wait can miss it on a busy first launch.
        activity.window.decorView.viewTreeObserver.addOnDrawListener {
            val screen = when {
                findText(activity, "Isaiah 21:6")?.isShown == true -> "verse"
                findText(activity, "Jump to Nav")?.isShown == true -> "choice"
                else -> "other"
            }
            synchronized(frames) {
                if (events.lastOrNull()?.screen != screen) events.add(DrawEvent(SystemClock.elapsedRealtime(), screen))
            }
        }
    }

    private fun drawEvents(activity: Activity): List<DrawEvent> =
        synchronized(frames) { frames[activity]?.toList().orEmpty() }

    private fun frameEvidence(): String = synchronized(frames) {
        frames.values.joinToString("\n") { events -> events.joinToString { "${it.at}:${it.screen}" } }
    }
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        suite = arguments?.getString("suite") ?: "startup"
        suiteArguments = arguments?.let { Bundle(it) } ?: Bundle()
        start()
    }

    override fun onStart() {
        val results = Bundle()
        try {
            if (suite == "graph_stationarity") {
                results.putString("graph_stationarity", AndroidSourceModelStationarityCheck.run(this))
                results.putString("result", "PASS")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (suite == "full_road_planner") {
                results.putString("full_road_planner", AndroidFullRoadPlannerCheck.run(this))
                results.putString("result", "PASS")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (suite == "device_region") {
                results.putString("device_region", AndroidDeviceRegionCheck.run(this))
                results.putString("result", "PASS")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (suite == "route_publication") {
                results.putString("route_publication", AndroidRoutePublicationCheck.run(this))
                results.putString("result", "PASS")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (suite == "speech") {
                val evidence = AndroidSpeechCheck.run(this)
                val passed = evidence.startsWith("PASS:")
                results.putString("speech", evidence)
                results.putString("result", if (passed) "PASS" else "INCOMPLETE")
                finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
                return
            }
            if (suite == "snapshot") {
                results.putString("snapshot", AndroidSnapshotCheck.run(this))
                results.putString("result", "PASS")
                finish(Activity.RESULT_OK, results)
                return
            }
            check(suite == "startup") { "Unknown corrective suite: $suite" }
            val prefs = targetContext.getSharedPreferences("watchman", Context.MODE_PRIVATE)
            // Prior choices must not suppress a subsequent launcher entry.
            prefs.edit().putString("v026_first_run_decision", "nav").putBoolean("activeNav", false).commit()
            repeat(2) { round ->
                val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClass(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val activity = startActivitySync(launch)
                waitUntil(10000L) { drawEvents(activity).any { it.screen == "choice" } }
                val drawn = drawEvents(activity)
                check(drawn.firstOrNull()?.screen == "verse") { "Launch $round did not draw Isaiah first: $drawn" }
                val elapsed = drawn.first { it.screen == "choice" }.at - drawn.first().at
                // Permit at most 100ms traversal scheduling tolerance, not a shorter intro.
                check(elapsed >= 2900L) { "Verse drawn for less than three seconds: ${elapsed}ms; $drawn" }
                check(visibleText(activity, "Jump to Nav"))
                check(visibleText(activity, "Run Setup (Recommended But Takes a While)"))
                runOnMainSync { checkNotNull(findText(activity, "Jump to Nav")).performClick() }
                check(!visibleText(activity, "Isaiah 21:6"))
                val monitor = addMonitor(MainActivity::class.java.name, null, false)
                runOnMainSync { activity.recreate() }
                val recreated = waitForMonitorWithTimeout(monitor, 10000L)
                    ?: error("Activity recreation did not complete")
                removeMonitor(monitor)
                check(!visibleText(recreated, "Isaiah 21:6")) { "Recreation interrupted the chosen navigation screen" }
                check(prefs.getString("v026_first_run_decision", null) == "nav")
                runOnMainSync { recreated.finish() }
                waitForIdleSync()
                results.putString("launch_$round", "PASS: verse, three-second choice, Jump to Nav, recreation")
            }
            results.putString("draw_trace", frameEvidence())
            results.putString("result", "PASS")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("result", "FAIL")
            results.putString("failure", error.stackTraceToString())
            results.putString("draw_trace", frameEvidence())
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    private fun visibleText(activity: Activity, text: String): Boolean {
        var visible = false
        runOnMainSync { visible = findText(activity, text)?.isShown == true }
        return visible
    }

    private fun findText(activity: Activity, text: String): TextView? {
        fun search(view: View): TextView? {
            if (view is TextView && view.text.toString() == text) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                search(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return search(activity.findViewById(android.R.id.content))
    }

    private fun waitUntil(timeout: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for expected Android view" }
            SystemClock.sleep(50L)
        }
    }
}
