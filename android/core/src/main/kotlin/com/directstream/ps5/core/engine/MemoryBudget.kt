package com.directstream.ps5.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-wide cap on RAM held by in-flight transfer buffers. FIFO, cancellation-safe.
 * Requests larger than the whole budget are clamped to it.
 */
class MemoryBudget(val totalBytes: Long) {
    init { require(totalBytes > 0) }
    private val lock = Any()
    private var available = totalBytes
    private val waiters = ArrayDeque<Waiter>()
    private class Waiter(val bytes: Long) { val granted = CompletableDeferred<Unit>() }

    val inUseBytes: Long get() = synchronized(lock) { totalBytes - available }

    inner class Lease internal constructor(val bytes: Long) {
        private val released = AtomicBoolean(false)
        fun release() { if (released.compareAndSet(false, true)) giveBack(bytes) }
    }

    suspend fun acquire(requested: Long): Lease {
        val n = requested.coerceIn(1, totalBytes)
        val w: Waiter
        synchronized(lock) {
            if (waiters.isEmpty() && available >= n) { available -= n; return Lease(n) }
            w = Waiter(n); waiters.addLast(w)
        }
        try {
            w.granted.await()
        } catch (e: CancellationException) {
            synchronized(lock) {
                val stillWaiting = waiters.remove(w)
                if (!stillWaiting) available += n   // grant raced with cancellation: hand the bytes back
                grantLocked()
            }
            throw e
        }
        return Lease(n)
    }

    private fun giveBack(n: Long) = synchronized(lock) { available = minOf(totalBytes, available + n); grantLocked() }

    private fun grantLocked() {
        while (true) {
            val head = waiters.firstOrNull() ?: return
            if (available < head.bytes) return
            waiters.removeFirst(); available -= head.bytes; head.granted.complete(Unit)
        }
    }
}
