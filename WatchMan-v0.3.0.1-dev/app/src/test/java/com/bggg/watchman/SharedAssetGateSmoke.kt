package com.bggg.watchman

import com.bggg.watchman.util.SharedAssetGate
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun sharedAssetGateSmoke() {
    val owner = Thread.currentThread()
    val background = Executors.newSingleThreadExecutor { Thread(it, "asset-test-loader") }
    val callers = Executors.newFixedThreadPool(4)
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val notified = CountDownLatch(40)
    val counts = List(50) { AtomicInteger() }
    val listeners = counts.map { counter -> object : SharedAssetGate.Listener {
        override fun onAssetReady(valid: Boolean) {
            check(valid)
            counter.incrementAndGet()
            notified.countDown()
        }
    } }
    val gate = SharedAssetGate(background)
    try {
        check(gate.snapshot() == SharedAssetGate.Snapshot(null, false, 0, null))
        check(gate.observe(listeners[0]) {
            check(Thread.currentThread() !== owner)
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
        } == null)
        check(started.await(5, TimeUnit.SECONDS))
        callers.invokeAll((0 until 500).map { index -> Callable {
            check(gate.observe(listeners[index % 50]) { error("Duplicate load") } == null)
        } }).forEach { it.get() }
        check(gate.snapshot().loads == 1 && gate.snapshot().inFlight)
        listeners.take(10).forEach(gate::forget)
        // Detach/reopen before completion must be one fresh subscription, not two callbacks.
        gate.forget(listeners[10])
        gate.observe(listeners[10]) { error("Reopen must share the in-flight load") }
        release.countDown()
        check(notified.await(5, TimeUnit.SECONDS))
        check(counts.take(10).all { it.get() == 0 })
        check(counts.drop(10).all { it.get() == 1 })
        check(gate.snapshot() == SharedAssetGate.Snapshot(true, false, 1, "asset-test-loader"))
        listeners.forEach { check(gate.observe(it) { error("Warm asset reread") } == true) }
        check(counts.drop(10).all { it.get() == 1 }) // warm return, no duplicate callback

        val attempts = AtomicInteger()
        val recovered = CountDownLatch(1)
        val retry = SharedAssetGate(Executor {
            if (attempts.getAndIncrement() == 0) throw RejectedExecutionException("First attempt rejected")
            background.execute(it)
        })
        val retryListener = object : SharedAssetGate.Listener {
            override fun onAssetReady(valid: Boolean) { check(valid); recovered.countDown() }
        }
        check(retry.observe(retryListener) { error("Rejected load executed") } == null)
        check(retry.snapshot() == SharedAssetGate.Snapshot(null, false, 0, null))
        retry.observe(retryListener) { true }
        check(recovered.await(5, TimeUnit.SECONDS))
        check(retry.snapshot().loads == 1 && attempts.get() == 2)
    } finally {
        release.countDown()
        callers.shutdownNow(); background.shutdownNow()
        check(callers.awaitTermination(5, TimeUnit.SECONDS))
        check(background.awaitTermination(5, TimeUnit.SECONDS))
    }

    // Deterministic queued executor isolates invalid/error caching and callback failure isolation.
    for (throws in listOf(false, true)) {
        val tasks = ArrayDeque<Runnable>()
        val invalid = SharedAssetGate(Executor { tasks.addLast(it) })
        var callbacks = 0
        val bad = object : SharedAssetGate.Listener {
            override fun onAssetReady(valid: Boolean) { error("Expected broken listener") }
        }
        val good = object : SharedAssetGate.Listener {
            override fun onAssetReady(valid: Boolean) { check(!valid); callbacks++ }
        }
        check(invalid.observe(bad) { if (throws) error("Unreadable asset") else false } == null)
        invalid.observe(good) { error("Duplicate invalid load") }
        check(tasks.size == 1)
        tasks.removeFirst().run()
        check(callbacks == 1)
        check(invalid.observe(good) { error("Invalid immutable asset reread") } == false)
        check(invalid.snapshot().loads == 1 && !invalid.snapshot().inFlight && tasks.isEmpty())
    }
    println("Shared asset gate: off-owner cold load, 500 concurrent observations/one load, weak deduplicated listeners, detach/reopen, warm reuse, invalid/error cache, callback isolation and executor rejection recovery PASS")
}
