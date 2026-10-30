// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Call
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Cancels only the work owned by one navigation/playback intent, including body reads. */
class RequestScope {
    private var cancelled = false
    private val calls = mutableSetOf<Call>()

    @Synchronized fun cancel() {
        cancelled = true
        calls.forEach { it.cancel() }
        calls.clear()
    }

    @Synchronized private fun attach(call: Call) {
        if (cancelled || Thread.currentThread().isInterrupted) {
            call.cancel()
            throw IOException("请求已取消")
        }
        calls += call
    }

    @Synchronized private fun detach(call: Call) { calls -= call }
    @Synchronized private fun isCancelled() = cancelled

    fun <T> run(action: () -> T): T {
        val previous = current.get()
        current.set(this)
        try {
            ensureActive()
            val result=action()
            ensureActive()
            return result
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    companion object {
        private val current = ThreadLocal<RequestScope>()

        /** Check between requests and after caught failures so cancellation cannot turn into retries. */
        fun ensureActive() {
            if (Thread.currentThread().isInterrupted || current.get()?.isCancelled() == true) {
                throw IOException("请求已取消")
            }
        }

        /** A scope cancellation interrupts retry backoff within 50ms, even without Future.cancel(). */
        internal fun pause(delayMs: Long) {
            ensureActive()
            val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(delayMs.coerceAtLeast(0))
            while(true) {
                val remaining=deadline-System.nanoTime()
                if(remaining<=0) return
                try { Thread.sleep(TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1,50)) }
                catch(problem: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("请求已取消",problem)
                }
                ensureActive()
            }
        }

        fun <T> execute(call: Call, consume: (Response) -> T): T {
            val owner = current.get()
            owner?.attach(call)
            try { return call.execute().use(consume) }
            finally { owner?.detach(call) }
        }
    }
}
