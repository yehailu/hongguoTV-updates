// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

object PrefetchPolicy {
    const val DEFAULT_COUNT = 1
    const val MAX_COUNT = 3
    fun count(value: Int) = value.coerceIn(0, MAX_COUNT)
    fun effectiveCount(value: Int, lowMemory: Boolean) = minOf(count(value), if (lowMemory) 1 else MAX_COUNT)

    /** Keep signed URLs young; start earlier for short dramas, but never warm at cold start. */
    fun canStart(position: Long, duration: Long, speed: Float, stableMillis: Long, bufferedMillis: Long): Boolean {
        if (duration <= 0 || position < 0 || position >= duration || !speed.isFinite() || speed <= 0 || stableMillis < 8_000) return false
        val remaining = (duration - position) / speed
        val buffered = bufferedMillis / speed
        return remaining <= 90_000f && buffered >= minOf(10_000f, remaining)
    }

    /** Wall-clock time to the end matters: long episodes must not outlive the slot TTL. */
    fun nearEnd(position: Long, duration: Long, speed: Float): Boolean {
        if (duration <= 0 || position < 0 || position >= duration || !speed.isFinite() || speed <= 0) return false
        return (duration - position) / speed <= 30_000f
    }
}
