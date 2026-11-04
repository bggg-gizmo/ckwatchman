package com.bggg.watchman

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.bggg.watchman.data.*
import com.bggg.watchman.routing.RoutePlanningPolicy
import com.bggg.watchman.storage.AuthoritativeDeviceRepository
import com.bggg.watchman.storage.CameraDatabase
import com.bggg.watchman.storage.NationalCameraCache
import com.bggg.watchman.util.CoalescingWorker
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual Activity, repository load and publisher; synthetic geometry is NOT route acceptance. */
object AndroidRoutePublicationCheck {
    fun run(test: Instrumentation): String {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Route publication fixtures are emulator-only" }
        val context=test.targetContext
        val source="instrumentation_route_publication_${UUID.randomUUID()}"
        val points=listOf(GeoPoint(-80.0,0.0),GeoPoint(-79.995,0.0),GeoPoint(-79.99,0.0))
        val origin=SearchResult("Synthetic publication origin",points.first())
        val destination=SearchResult("Synthetic publication destination",points.last())
        fun route(vertices:List<GeoPoint>,seconds:Double)=Route(vertices,
            listOf(RouteStep("Synthetic departure",vertices.first(),1000.0),
                RouteStep("Synthetic arrival",vertices.last(),0.0,"arrive")),
            1000.0,seconds,destination,"Synthetic controller fixture")
        val first=route(points,60.0)
        val second=route(points.dropLast(1)+GeoPoint(-79.989,0.01),80.0)
        fun analyses(a:Route,b:Route)=listOf(RouteAnalysis(a,RoutePlanningPolicy.EXPOSURE_PENDING,0.0),
            RouteAnalysis(b,RoutePlanningPolicy.EXPOSURE_PENDING,0.0))
        val camera=CameraPoi(source,CameraType.ALPR,GeoPoint(-79.9975,0.0),
            "PUBLICATION FIXTURE",null,null,null,null,sourceId=source,sourceRecordId=source,
            source="Synthetic instrumentation only",tags=mapOf("watchman:test_fixture" to "true"))
        val db=CameraDatabase(context)
        val background=Executors.newSingleThreadExecutor()
        val began=CountDownLatch(1)
        val release=CountDownLatch(1)
        val violations=ConcurrentLinkedQueue<String>()
        var activity:Activity?=null
        var wrapped:CoalescingWorker<Any,Any>?=null
        var originalWorker:Any?=null
        var failLoad=false
        val prefs=context.getSharedPreferences("watchman",Context.MODE_PRIVATE)
        val previousDecision=prefs.getString("v026_first_run_decision",null)
        val previousActive=prefs.getBoolean("activeNav",false)
        try {
            db.replaceSourceSnapshot(source,listOf(camera))
            checkIsolatedReadFailure(context,first)
            prefs.edit().putString("v026_first_run_decision","nav").putBoolean("activeNav",false).commit()
            val current=test.startActivitySync(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity=current
            val content=current.findViewById<View>(android.R.id.content)
            fun ui(action:()->Unit)=test.runOnMainSync {
                val previous=StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites()
                    .penaltyListener(java.util.concurrent.Executor { it.run() }) { violations.add(it.stackTraceToString()) }.build())
                try { action() } finally { StrictMode.setThreadPolicy(previous) }
            }
            waitUntil {
                var ready=false
                test.runOnMainSync { ready=views(content).any { it.text.toString()=="Jump to Nav" && it.isShown } }
                ready
            }
            test.runOnMainSync { checkNotNull(views(content).firstOrNull { it.text.toString()=="Jump to Nav" }).performClick() }
            val workerField=field("routeExposureWorker")
            val analysisField=field("lastRouteAnalyses")
            val previewField=field("previewRoute")
            val currentField=field("currentRoute")
            val show=MainActivity::class.java.getDeclaredMethod("showRouteAlternatives",List::class.java,
                RouteAnalysis::class.java,RoutePreference::class.java,SearchResult::class.java,
                Int::class.javaObjectType).apply { isAccessible=true }
            val refresh=MainActivity::class.java.getDeclaredMethod("refreshRouteExposureAnalyses").apply { isAccessible=true }
            fun workerIdle(worker:Any):Boolean= !worker.javaClass.getDeclaredField("running")
                .apply { isAccessible=true }.getBoolean(worker)
            waitUntil {
                var idle=false
                test.runOnMainSync { idle=workerIdle(workerField.get(current)) }
                idle
            }
            ui {
                val production=workerField.get(current)
                originalWorker=production
                @Suppress("UNCHECKED_CAST")
                val load=production.javaClass.getDeclaredField("load").apply { isAccessible=true }.get(production) as (Any)->Any
                @Suppress("UNCHECKED_CAST")
                val publish=production.javaClass.getDeclaredField("publish").apply { isAccessible=true }.get(production) as (Any,Result<Any>)->Unit
                var firstLoad=true
                wrapped=CoalescingWorker(background,{ Handler(Looper.getMainLooper()).post(it) }, { request ->
                    check(Looper.myLooper()!=Looper.getMainLooper())
                    if(firstLoad) { firstLoad=false;began.countDown();check(release.await(15,TimeUnit.SECONDS)) }
                    if(failLoad)error("Synthetic cache load failure")
                    load(request) // Actual repository/count implementation, not manufactured test counts.
                },publish)
                workerField.set(current,wrapped)
                val initial=analyses(first,second)
                show.invoke(current,initial,initial.first(),RoutePreference.FASTEST,origin,1)
                check(previewField.get(current)===second && currentField.get(current)==null)
                check(views(content).count { it.text.contains("Camera/sensor counts pending") }==2)
                check(views(content).any { it.text.toString()=="VIEW TURN-BY-TURN DIRECTIONS" })
                refresh.invoke(current)
            }
            check(began.await(5,TimeUnit.SECONDS))
            val responsive=CountDownLatch(1)
            current.runOnUiThread { responsive.countDown() }
            check(responsive.await(2,TimeUnit.SECONDS)) { "Camera load blocked owner thread" }
            val revisedFirst=first.copy(provider="Synthetic revised controller fixture")
            val revisedSecond=second.copy(provider="Synthetic revised controller fixture")
            val revised=analyses(revisedFirst,revisedSecond)
            ui { show.invoke(current,revised,revised.first(),RoutePreference.SHORTEST,origin.copy(displayName="Revised origin"),1) }
            release.countDown()
            fun idle():Boolean {
                var value=false
                test.runOnMainSync { value=workerIdle(checkNotNull(wrapped)) }
                return value
            }
            waitUntil(::idle)
            ui {
                check(analysisField.get(current)===revised) { "Stale exposure replaced current analyses" }
                check(previewField.get(current)===revisedSecond && currentField.get(current)==null)
                check(views(content).count { it.text.contains("Camera/sensor counts pending") }==2)
                refresh.invoke(current)
            }
            waitUntil(::idle)
            ui {
                @Suppress("UNCHECKED_CAST")
                val enriched=analysisField.get(current) as List<RouteAnalysis>
                check(enriched.map { it.knownCameraCount }==listOf(1,1))
                check(enriched[0].route===revisedFirst && enriched[1].route===revisedSecond)
                check(previewField.get(current)===revisedSecond && currentField.get(current)==null)
                check(views(content).count { it.text.contains("1 known cam") }==2)
                failLoad=true;refresh.invoke(current)
            }
            waitUntil(::idle)
            ui {
                check(views(content).count { it.text.contains("counts unavailable") }==2)
                @Suppress("UNCHECKED_CAST")
                check((analysisField.get(current) as List<RouteAnalysis>).all { it.knownCameraCount==1 })
                check(previewField.get(current)===revisedSecond)
                failLoad=false;refresh.invoke(current)
            }
            waitUntil(::idle)
            ui { check(views(content).count { it.text.contains("1 known cam") }==2);check(previewField.get(current)===revisedSecond) }
            check(violations.isEmpty()) { "Route publication direct UI disk I/O:\n${violations.joinToString("\n")}" }
            return "PASS: actual SQLite cache failure distinguished from empty, production off-main count load/publisher, " +
                "pending options and responsive owner, stale-route rejection, selected alternative preserved, " +
                "count failure/retry without counterfeit zero, zero direct UI disk violations. " +
                "Synthetic controller geometry; full planner, GPS, route legality, PDF and physical acceptance remain separate."
        } finally {
            release.countDown()
            activity?.let { current -> test.runOnMainSync {
                wrapped?.close()
                originalWorker?.let { field("routeExposureWorker").set(current,it) }
                current.finish()
            } }
            background.shutdownNow()
            check(background.awaitTermination(5,TimeUnit.SECONDS))
            try { db.replaceSourceSnapshot(source,emptyList()) } finally { db.close() }
            prefs.edit().apply {
                if(previousDecision==null)remove("v026_first_run_decision") else putString("v026_first_run_decision",previousDecision)
                putBoolean("activeNav",previousActive)
            }.commit()
        }
    }

    /** Deliberately missing table ONLY in newly created UUID-owned private scratch databases. */
    private fun checkIsolatedReadFailure(context:Context,route:Route) {
        val root=File(context.cacheDir,"route-publication-${UUID.randomUUID()}")
        check(root.mkdir())
        val isolated=object:ContextWrapper(context) {
            override fun getApplicationContext():Context=this
            override fun getDatabasePath(name:String):File {
                require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
                return File(root,name).also { check(it.canonicalFile.parentFile==root.canonicalFile) }
            }
            override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?):SQLiteDatabase=
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,
                errorHandler:android.database.DatabaseErrorHandler?):SQLiteDatabase=
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath,factory,errorHandler)
        }
        val live=CameraDatabase(isolated)
        val national=NationalCameraCache(isolated)
        try {
            val repository=AuthoritativeDeviceRepository(live,national)
            check(repository.alongRoutes(listOf(route),180.0).requireAvailable().devices.isEmpty())
            live.writableDatabase.execSQL("DROP TABLE cameras")
            val failed=repository.alongRoutes(listOf(route),180.0)
            check(failed.unavailableSources==setOf("live") && failed.devices.isEmpty())
            check(runCatching { failed.requireAvailable() }.isFailure)
            check(runCatching { failed.freezeForRouting("Synthetic missing-cache fixture") }.isFailure)
        } finally {
            live.close();national.close()
            check(root.canonicalFile.parentFile==context.cacheDir.canonicalFile)
            check(root.deleteRecursively())
        }
    }
    private fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
    private fun views(view:View):List<TextView> = when(view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun waitUntil(predicate:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+30000
        while(!predicate()) { check(SystemClock.elapsedRealtime()<deadline) { "Publication test timed out" };SystemClock.sleep(50) }
    }
}
