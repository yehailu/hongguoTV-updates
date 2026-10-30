// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.Closeable

/** A bounded speculative queue. Claim transfers ownership, cancellation rejects late completions. */
class PreparedEpisodes<T : Closeable>(private val clock: () -> Long, private val capacity: Int = 3, private val ttl: Long = 120_000) {
    private data class Entry<T>(val ticket: Long, val startedAt: Long, var readyAt: Long = 0, var value: T? = null)
    private val entries = linkedMapOf<String, Entry<T>>()
    private var revision = 0L
    init { require(capacity > 0 && ttl > 0) }

    @Synchronized fun begin(key: String): Long? {
        expire()
        if (key in entries || entries.size >= capacity) return null
        val ticket = ++revision
        entries[key] = Entry(ticket, clock())
        return ticket
    }
    @Synchronized fun complete(ticket: Long, key: String, result: T): Boolean {
        val entry = entries[key]
        if (entry == null || entry.ticket != ticket || entry.value != null || clock() - entry.startedAt !in 0..ttl) {
            result.close(); return false
        }
        entry.value = result; entry.readyAt = clock()
        return true
    }
    @Synchronized fun abort(key: String) { entries.remove(key)?.value?.close() }
    @Synchronized fun abortPending(key: String, ticket: Long? = null) {
        val entry = entries[key] ?: return
        if (entry.value == null && (ticket == null || entry.ticket == ticket)) entries.remove(key)
    }
    @Synchronized fun take(key: String): T? {
        expire()
        return entries.remove(key)?.value
    }
    @Synchronized fun isFresh(key: String): Boolean { expire(); return entries[key]?.value != null }
    /** Trim after a skip/quality change without discarding other still-useful future episodes. */
    @Synchronized fun retain(keys: Set<String>) {
        entries.keys.toList().filter { it !in keys }.forEach(::abort)
        expire()
    }
    @Synchronized fun expire() {
        val now = clock()
        entries.filterValues { now - (if (it.value == null) it.startedAt else it.readyAt) !in 0..ttl }
            .keys.toList().forEach(::abort)
    }
    @Synchronized fun clear() { entries.keys.toList().forEach(::abort) }
}
