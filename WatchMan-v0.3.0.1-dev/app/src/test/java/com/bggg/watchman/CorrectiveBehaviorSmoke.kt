package com.bggg.watchman

import com.bggg.watchman.ui.LaunchPresentation
import com.bggg.watchman.alerts.SpeechDeliveryQueue
import com.bggg.watchman.alerts.SpeechIo
import com.bggg.watchman.alerts.SpeechInitializationGate
import com.bggg.watchman.alerts.BritishVoicePolicy
import com.bggg.watchman.alerts.ProximityEncounterTracker
import com.bggg.watchman.alerts.ProximityAudioPolicy
import com.bggg.watchman.export.MapLabelLayout
import com.bggg.watchman.util.CoalescingWorker
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

fun main() {
    roadAwareExposureSmoke()
    routeProximityIndexRegressions()
    noCamsObstaclePlannerSmoke()
    geocoderEvidenceSmoke()
    sourceMappedRoadModelSmoke()
    fullRoadPlanningBudgetSmoke()
    deviceSearchRegionSmoke()
    sourceRoadTimingSmoke()
    roadPolicyTimelineSmoke()
    graphRouteMaterializerSmoke()
    roadSpeedEstimateSmoke()
    roadDeviceExposureSmoke()
    noCamsGraphSearchSmoke()
    endpointAccessPolicySmoke()
    partialRoadTraversalSmoke()
    packedSpatialTreeSmoke()
    roadSnapGeometrySmoke()
    packedNodeReferencesSmoke()
    motorcarGraphTraversalSmoke()
    turnRestrictionPolicySmoke()
    motorcarRoadProfileSmoke()
    vehicleLimitPolicySmoke()
    turnRelationCompilerSmoke()
    turnRestrictionMachineSmoke()
    motorcarTagPolicySmoke()
    restrictionConditionSmoke()
    conditionalRestrictionSmoke()
    sharedAssetGateSmoke()
    val owner = Thread.currentThread()
    val background = Executors.newSingleThreadExecutor()
    val callbacks = LinkedBlockingQueue<() -> Unit>()
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val loaded = java.util.Collections.synchronizedList(mutableListOf<Int>())
    val delivered = mutableListOf<Pair<Int, Result<Int>>>()
    val worker = CoalescingWorker<Int, Int>(background, { callbacks.put(it) }, { input ->
        check(Thread.currentThread() !== owner)
        loaded.add(input)
        if (input == 1) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        if (input == 4) error("Expected query failure")
        input * 10
    }, { input, result ->
        check(Thread.currentThread() === owner)
        delivered.add(input to result)
    })
    fun deliverNext() = checkNotNull(callbacks.poll(5, TimeUnit.SECONDS)) { "Worker did not dispatch completion" }.invoke()
    try {
        check(worker.request(1))
        check(started.await(5, TimeUnit.SECONDS))
        for (input in 2..100) check(worker.request(input))
        check(loaded.toList() == listOf(1)) // burst does not create concurrent reads or an unbounded queue
        release.countDown()
        deliverNext()
        deliverNext()
        check(loaded.toList() == listOf(1, 100))
        check(delivered.map { it.second.getOrThrow() } == listOf(10, 1000))
        worker.request(4)
        worker.request(5)
        deliverNext()
        deliverNext()
        check(delivered[2].second.isFailure && delivered[3].second.getOrThrow() == 50)
        worker.request(6)
        val afterClose = checkNotNull(callbacks.poll(5, TimeUnit.SECONDS))
        worker.request(7)
        worker.close()
        afterClose()
        check(!worker.request(8))
        check(delivered.size == 4 && 7 !in loaded && 8 !in loaded)
    } finally {
        release.countDown()
        worker.close()
        background.shutdownNow()
        check(background.awaitTermination(5, TimeUnit.SECONDS))
    }
    var rejected = 0
    val rejecting = CoalescingWorker<Int, Int>(java.util.concurrent.Executor { throw RejectedExecutionException("closed executor") },
        { it() }, { it }, { _, result -> check(result.exceptionOrNull() is RejectedExecutionException); rejected++ })
    rejecting.request(1)
    rejecting.request(2)
    check(rejected == 2)
    rejecting.close()
    println("Snapshot worker: off-owner load, owner publication, 100-request coalescing, query failure/recovery, close and executor rejection PASS")
    val speechExecutor = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(2))
    val speechCallbacks = LinkedBlockingQueue<() -> Unit>()
    val speechStarted = CountDownLatch(1)
    val releaseSpeech = CountDownLatch(1)
    val engineOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
    val engineResults = mutableListOf<Result<String>>()
    val speechIo = SpeechIo({ speechCallbacks.put(it) }, speechExecutor)
    fun collectSpeech() {
        checkNotNull(speechCallbacks.poll(5, TimeUnit.SECONDS)) { "Speech IO completion missing" }()
    }
    try {
        speechIo.submit({
            check(Thread.currentThread() !== owner)
            speechStarted.countDown()
            check(releaseSpeech.await(5, TimeUnit.SECONDS))
            engineOrder.add("enumerate"); "voice"
        }) { check(Thread.currentThread() === owner); engineResults.add(it) }
        check(speechStarted.await(5, TimeUnit.SECONDS))
        // Owner still runs while the simulated binder call is blocked. Stop precedes new speech.
        speechIo.submit({ engineOrder.add("stop"); "stopped" }) { engineResults.add(it) }
        speechIo.submit({ engineOrder.add("speak"); "spoken" }) { engineResults.add(it) }
        speechIo.submit({ error("Rejected task must never run on caller") }) { engineResults.add(it) }
        collectSpeech()
        check(engineResults.single().exceptionOrNull() is RejectedExecutionException)
        check(engineOrder.isEmpty())
        releaseSpeech.countDown()
        repeat(3) { collectSpeech() }
        check(engineOrder.toList() == listOf("enumerate", "stop", "speak"))
        check(engineResults.drop(1).map { it.getOrThrow() } == listOf("voice", "stopped", "spoken"))
        speechIo.submit<String>({ error("Engine failure") }) { engineResults.add(it) }
        collectSpeech()
        check(engineResults.last().exceptionOrNull() is IllegalStateException)
        speechIo.submit({ "recovered" }) { engineResults.add(it) }
        collectSpeech()
        check(engineResults.last().getOrThrow() == "recovered")
    } finally {
        releaseSpeech.countDown()
        speechExecutor.shutdownNow()
        check(speechExecutor.awaitTermination(5, TimeUnit.SECONDS))
    }
    println("Speech IO: off-owner serialized engine calls, responsive owner during blocked call, stop-before-speak ordering, bounded rejection and failure recovery PASS")
    val initGate = SpeechInitializationGate()
    val early = initGate.begin()
    check(runCatching { initGate.begin() }.isFailure)
    check(initGate.callback(early, 0, false) == null && initGate.waiting)
    check(initGate.callback(early, -1, false) == null) // retain first callback
    check(initGate.published(early) == 0 && !initGate.waiting)
    check(initGate.callback(early, -1, true) == null && initGate.published(early) == null)
    val late = initGate.begin()
    check(initGate.published(late) == null && initGate.waiting)
    check(initGate.callback(late, -1, true) == -1 && !initGate.waiting)
    val timedOut = initGate.begin()
    check(initGate.callback(timedOut, 0, false) == null)
    initGate.cancel()
    val replacement = initGate.begin()
    check(initGate.published(timedOut) == null)
    check(initGate.callback(timedOut, 0, true) == null)
    check(initGate.waiting && initGate.generation == replacement)
    check(initGate.published(replacement) == null)
    check(initGate.callback(replacement, 0, true) == 0)
    initGate.cancel()
    check(initGate.callback(replacement, 0, true) == null)
    println("Speech initialization: callback/constructor order, single in-flight attempt, duplicate callback, failure, timeout and stale-generation rejection PASS")
    val labels = MapLabelLayout(0f, 0f, 300f, 100f)
    checkNotNull(labels.place(80f, 20f, 70f, -8f, 2f))
    check(labels.place(100f, 20f, 70f, -8f, 2f) == null) // different cells, overlapping text
    check(labels.place(100f, 24f, 70f, -8f, 2f) == null) // vertically adjacent cells still overlap
    checkNotNull(labels.place(185f, 20f, 70f, -8f, 2f))
    val boundaries = MapLabelLayout(0f, 0f, 300f, 100f)
    val clamped = checkNotNull(boundaries.place(-100f, -100f, 30f, -8f, 2f))
    check(clamped.x == 0f && clamped.top == 0f)
    val edge = checkNotNull(boundaries.place(1000f, 1000f, 30f, -8f, 2f))
    check(edge.right == 300f && edge.bottom == 100f)
    check(boundaries.place(299f, 99f, 30f, -8f, 2f) == null)
    check(labels.place(0f, 50f, 301f, -8f, 2f) == null)
    check(labels.place(Float.NaN, 50f, 30f, -8f, 2f) == null)
    check(labels.place(0f, 50f, 30f, -80f, 30f) == null)
    check(MapLabelLayout(5f, 5f, 4f, 4f).place(0f, 0f, 1f, -1f, 1f) == null)
    println("PDF labels: adjacent-cell collisions, page bounds, clamping, oversized and invalid input PASS")
    repeat(3) {
        val launch = LaunchPresentation()
        check(launch.stage == LaunchPresentation.Stage.INTRO)
        check(launch.remainingMillis(12000L) == 3000L) // time before first draw earns no credit
        check(launch.resume(100L) == 3000L)
        check(!launch.advance(3099L))
        check(launch.advance(3100L))
        check(launch.stage == LaunchPresentation.Stage.CHOICE)
        launch.showNavigation()
        check(launch.resume(4000L) == 0L)
        check(launch.stage == LaunchPresentation.Stage.NAVIGATION)
    }
    val interrupted = LaunchPresentation()
    interrupted.resume(1000L)
    interrupted.pause(2000L)
    check(interrupted.resume(20000L) == 2000L)
    check(interrupted.resume(20500L) == 1500L) // duplicate resume does not restart timer
    check(!interrupted.advance(21999L))
    check(interrupted.advance(22000L))
    val rotating = LaunchPresentation()
    rotating.resume(0L)
    val restored = LaunchPresentation(rotating.stage.name, rotating.remainingMillis(750L))
    check(restored.resume(5000L) == 2250L)
    check(restored.advance(7250L))
    restored.showSetup()
    check(LaunchPresentation(restored.stage.name).stage == LaunchPresentation.Stage.SETUP)
    restored.showNavigation()
    check(LaunchPresentation(restored.stage.name).stage == LaunchPresentation.Stage.NAVIGATION)
    check(LaunchPresentation("invalid").stage == LaunchPresentation.Stage.INTRO)
    println("Launch behavior: repeated launch, exact 3s, pause/resume, recreation, setup/navigation recovery PASS")
    val voices = listOf(
        BritishVoicePolicy.Candidate("us", "en", "US", 500, 100, false, true),
        BritishVoicePolicy.Candidate("gb-network", "en", "GB", 500, 100, true, true),
        BritishVoicePolicy.Candidate("gb-offline", "en", "GB", 400, 200, false, true),
        BritishVoicePolicy.Candidate("gb-missing", "en", "GB", 500, 100, false, false)
    )
    check(BritishVoicePolicy.select(voices)?.name == "gb-offline")
    check(BritishVoicePolicy.select(voices.take(1)) == null)
    check(BritishVoicePolicy.select(voices.takeLast(1)) == null)
    val queue = SpeechDeliveryQueue()
    var deliveries = 0
    val camera = SpeechDeliveryQueue.Request("camera", "Flock plate reader ahead", SpeechDeliveryQueue.Kind.CAMERA,
        60_000L, delivered = { deliveries++ })
    check(queue.offer(camera))
    check(!queue.offer(camera))
    check(queue.next(0, false) == null) // initialization does not consume warning
    val first = checkNotNull(queue.next(0, true))
    check(queue.failed(first.utteranceId, 100))
    check(deliveries == 0)
    check(queue.next(599, true) == null)
    val retry = checkNotNull(queue.next(600, true))
    check(retry.utteranceId != first.utteranceId)
    check(!queue.done(first.utteranceId)) // late callback cannot acknowledge a different attempt
    check(queue.offer(SpeechDeliveryQueue.Request("nav", "Turn left", SpeechDeliveryQueue.Kind.MANEUVER, 5000)))
    check(queue.next(700, true) == null) // maneuver does not flush active camera warning
    check(queue.done(retry.utteranceId))
    check(deliveries == 1)
    check(!queue.done(retry.utteranceId))
    check(queue.next(800, true)?.request?.key == "nav")
    queue.clear()
    var relevant = true
    queue.offer(camera.copy(relevant = { relevant }))
    val expired = checkNotNull(queue.next(1000, true))
    relevant = false
    check(queue.cancelExpired(1100) == expired.utteranceId)
    check(!queue.done(expired.utteranceId))
    check(deliveries == 1)
    queue.offer(camera)
    queue.offer(SpeechDeliveryQueue.Request("preview", "In one mile", SpeechDeliveryQueue.Kind.PREVIEW, 5000))
    queue.offer(SpeechDeliveryQueue.Request("nav2", "Turn right", SpeechDeliveryQueue.Kind.MANEUVER, 5000))
    check(queue.next(1200, true)?.request?.key == "nav2")
    val tracker = ProximityEncounterTracker()
    fun input(id: String, delta: Double = 100.0, now: Long = 1000L) = ProximityEncounterTracker.Input(
        id, kotlin.math.abs(delta), true, delta, ProximityAudioPolicy.Stage.CLOSE, 50.0, 200.0, now)
    val a = tracker.update(input("a"))
    val b = tracker.update(input("b"))
    check(a.speechKey != null && b.speechKey != null && a.speechKey != b.speechKey)
    check(tracker.update(input("a")).speechKey == a.speechKey)
    check(!tracker.update(input("a")).newSpokenAlert) // banner/vibration stays once per encounter
    tracker.acknowledgeSpeech("a", checkNotNull(a.speechKey))
    check(tracker.update(input("a")).speechKey == null)
    check(tracker.update(input("b")).speechKey == b.speechKey) // overlapping device not consumed
    val restoredTracker = ProximityEncounterTracker()
    check(restoredTracker.restore(tracker.encode()))
    check(restoredTracker.update(input("a")).speechKey == null)
    check(restoredTracker.update(input("b")).speechKey == b.speechKey)
    val encodedV2 = tracker.encode()
    check(encodedV2.lineSequence().first() == "watchman-proximity-v2")
    val legacyExtended = encodedV2.replaceFirst("watchman-proximity-v2", "watchman-proximity-v1")
    val migratedExtended = ProximityEncounterTracker()
    check(migratedExtended.restore(legacyExtended))
    check(migratedExtended.update(input("a")).speechKey == null) // acknowledgment retained
    check(migratedExtended.update(input("b")).speechKey == b.speechKey) // pending identity retained
    repeat(3) {
        val persisted = migratedExtended.encode()
        check(migratedExtended.restore(persisted))
        check(migratedExtended.update(input("a")).speechKey == null)
        check(migratedExtended.update(input("b")).speechKey == b.speechKey)
    }
    val legacySeven = legacyExtended.lines().mapIndexed { index, line ->
        if (index == 0) line else line.split('|').take(7).joinToString("|")
    }.joinToString("\n")
    val migratedSeven = ProximityEncounterTracker()
    check(migratedSeven.restore(legacySeven))
    check(!migratedSeven.update(input("a")).newSpokenAlert) // prior visual alert remains consumed
    val migratedPending = checkNotNull(migratedSeven.update(input("a")).speechKey)
    check(migratedSeven.restore(migratedSeven.encode()))
    check(migratedSeven.update(input("a")).speechKey == migratedPending)
    val beforeUnknown = migratedSeven.encode()
    check(!migratedSeven.restore(beforeUnknown.replaceFirst("watchman-proximity-v2", "watchman-proximity-v99")))
    check(migratedSeven.encode() == beforeUnknown) // unknown format cannot destroy loaded state
    val malformed = ProximityEncounterTracker()
    check(malformed.restore(legacySeven.replaceFirst("watchman-proximity-v1", "watchman-proximity-v2")))
    check(malformed.state("a") == ProximityEncounterTracker.State.UNSEEN) // v2 requires all nine fields
    val firstRow = encodedV2.lines()[1].split('|')
    for ((field, invalid) in listOf(7 to "invalid", 8 to "", 8 to "key|separator")) {
        val row = firstRow.toMutableList().apply { set(field, invalid) }.joinToString("|")
        check(malformed.restore("watchman-proximity-v2\n$row"))
        check(malformed.encode() == "watchman-proximity-v2")
    }
    println("Proximity codec: v1 seven/nine-field migration, v2 round trips, delivered/pending identity, unknown-version preservation and malformed extended records PASS")
    tracker.update(input("b", -30.0))
    check(!tracker.speechPending("b", checkNotNull(b.speechKey)))
    tracker.update(input("b", -250.0, 12000))
    val newEncounter = tracker.update(input("b", 100.0, 13000))
    check(newEncounter.speechKey != b.speechKey)
    tracker.acknowledgeSpeech("b", b.speechKey)
    check(tracker.speechPending("b", checkNotNull(newEncounter.speechKey)))
    println("Speech behavior: en-GB selection, missing voice, initialization delay, retry/backoff, stale callbacks, arbitration, overlapping encounters, persistence and pass/reset PASS")
}
