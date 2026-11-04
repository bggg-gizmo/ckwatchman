package com.bggg.watchman.storage

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import kotlin.math.max

/**
 * Keeps background national acquisition from exhausting device storage or competing with live
 * navigation under memory pressure. Checks occur only between public requests; in-flight work is
 * allowed to finish and previously committed snapshots remain intact.
 */
class AcquisitionHeadroomGuard(context: Context) {
    data class Snapshot(
        val availableStorageBytes: Long,
        val requiredStorageBytes: Long,
        val appHeapHeadroomBytes: Long,
        val systemLowMemory: Boolean,
        val recentTrimPressure: Boolean
    ) {
        val storageReady: Boolean get() = availableStorageBytes >= requiredStorageBytes
        val memoryReady: Boolean
            get() = !systemLowMemory && !recentTrimPressure && appHeapHeadroomBytes >= MIN_APP_HEAP_HEADROOM_BYTES
        val ready: Boolean get() = storageReady && memoryReady

        fun pauseReason(): String = when {
            !storageReady -> "storage headroom"
            !memoryReady -> "memory headroom"
            else -> "resource headroom"
        }
    }

    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    @Volatile private var trimPressureUntilElapsedMs = 0L

    fun snapshot(): Snapshot {
        val stat = StatFs(appContext.filesDir.absolutePath)
        val totalStorage = stat.totalBytes.coerceAtLeast(0L)
        val requiredStorage = storageReserveBytes(totalStorage) + NEXT_BATCH_ALLOWANCE_BYTES
        val runtime = Runtime.getRuntime()
        val usedHeap = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val appHeapHeadroom = (runtime.maxMemory() - usedHeap).coerceAtLeast(0L)
        val memoryInfo = ActivityManager.MemoryInfo()
        val systemLow = runCatching {
            activityManager?.getMemoryInfo(memoryInfo)
            activityManager != null && memoryInfo.lowMemory
        }.getOrDefault(false)
        return Snapshot(
            availableStorageBytes = stat.availableBytes.coerceAtLeast(0L),
            requiredStorageBytes = requiredStorage,
            appHeapHeadroomBytes = appHeapHeadroom,
            systemLowMemory = systemLow,
            recentTrimPressure = SystemClock.elapsedRealtime() < trimPressureUntilElapsedMs
        )
    }

    fun noteTrimMemory(level: Int) {
        val pressure = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (pressure) {
            trimPressureUntilElapsedMs = max(
                trimPressureUntilElapsedMs,
                SystemClock.elapsedRealtime() + TRIM_PRESSURE_HOLD_MS
            )
        }
    }

    /**
     * Waits without holding a database transaction or acquisition-priority monitor. Returns false
     * only when the owner is stopping or the thread is interrupted.
     */
    fun awaitSafeToAcquire(
        shouldStop: () -> Boolean,
        onWaiting: (Snapshot) -> Unit = {},
        onResumed: () -> Unit = {}
    ): Boolean {
        var waited = false
        var lastReason: String? = null
        var lastReportAt = 0L
        while (!shouldStop()) {
            val current = snapshot()
            if (current.ready) {
                if (waited) onResumed()
                return true
            }
            waited = true
            val now = SystemClock.elapsedRealtime()
            val reason = current.pauseReason()
            if (reason != lastReason || now - lastReportAt >= WAIT_STATUS_REFRESH_MS) {
                onWaiting(current)
                lastReason = reason
                lastReportAt = now
            }
            try {
                Thread.sleep(HEADROOM_RECHECK_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    companion object {
        const val MIN_APP_HEAP_HEADROOM_BYTES = 48L * 1024L * 1024L
        const val NEXT_BATCH_ALLOWANCE_BYTES = 32L * 1024L * 1024L
        const val MIN_STORAGE_RESERVE_BYTES = 384L * 1024L * 1024L
        const val MAX_STORAGE_RESERVE_BYTES = 1536L * 1024L * 1024L
        const val STORAGE_RESERVE_PERCENT = 10L
        const val HEADROOM_RECHECK_MS = 2_000L
        const val WAIT_STATUS_REFRESH_MS = 30_000L
        const val TRIM_PRESSURE_HOLD_MS = 60_000L

        fun storageReserveBytes(totalBytes: Long): Long =
            (totalBytes.coerceAtLeast(0L) * STORAGE_RESERVE_PERCENT / 100L)
                .coerceIn(MIN_STORAGE_RESERVE_BYTES, MAX_STORAGE_RESERVE_BYTES)
    }
}
