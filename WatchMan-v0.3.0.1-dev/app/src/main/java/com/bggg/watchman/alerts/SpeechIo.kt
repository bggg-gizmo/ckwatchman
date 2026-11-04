package com.bggg.watchman.alerts

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Ordered engine IPC, bounded backlog, no caller-thread fallback. Idle thread retires after 30s. */
class SpeechIo(
    private val dispatch: (() -> Unit) -> Unit,
    private val executor: Executor = ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(16), { runnable ->
            Thread(runnable, "watchman-speech-io").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy())
) {
    private val owner = Thread.currentThread()

    fun <T> submit(action: () -> T, complete: (Result<T>) -> Unit) {
        check(Thread.currentThread() === owner) { "SpeechIo submissions must use the owner thread" }
        try {
            executor.execute {
                val result = runCatching {
                    check(Thread.currentThread() !== owner) { "Speech engine IPC cannot run on the owner thread" }
                    action()
                }
                dispatch { complete(result) }
            }
        } catch (error: RejectedExecutionException) {
            dispatch { complete(Result.failure(error)) }
        }
    }
}
