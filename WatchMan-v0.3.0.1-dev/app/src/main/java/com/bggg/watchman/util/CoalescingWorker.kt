package com.bggg.watchman.util

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Owner-thread queue with one active load and one replaceable pending input. */
class CoalescingWorker<I : Any, O>(
    private val executor: Executor,
    private val dispatch: (() -> Unit) -> Unit,
    private val load: (I) -> O,
    private val publish: (I, Result<O>) -> Unit
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var pending: I? = null
    private var running = false
    private var closed = false

    fun request(input: I): Boolean {
        checkOwner()
        if (closed) return false
        pending = input
        startNext()
        return true
    }

    private fun startNext() {
        checkOwner()
        if (closed || running) return
        val input = pending ?: return
        pending = null
        running = true
        try {
            executor.execute {
                val result = runCatching { load(input) }
                dispatch { complete(input, result) }
            }
        } catch (error: RejectedExecutionException) {
            complete(input, Result.failure(error))
        }
    }

    private fun complete(input: I, result: Result<O>) {
        checkOwner()
        running = false
        try {
            if (!closed) publish(input, result)
        } finally {
            startNext()
        }
    }

    override fun close() {
        checkOwner()
        closed = true
        pending = null
    }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "CoalescingWorker state must be used on its owner thread" }
}
