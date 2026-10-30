// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

class EpisodePrefetchTest {
    private class Resource : Closeable { var closed = false; override fun close() { closed = true } }
    @Test fun queueRetainsSeveralEpisodesAndClaimTransfersOnlySelectedOwnership() {
        val queue = PreparedEpisodes<Resource>({ 10L })
        val a = Resource(); val b = Resource(); val c = Resource()
        assertTrue(queue.complete(queue.begin("a")!!, "a", a))
        assertTrue(queue.complete(queue.begin("b")!!, "b", b))
        assertTrue(queue.complete(queue.begin("c")!!, "c", c))
        assertNull(queue.begin("d"))
        assertSame(a, queue.take("a"))
        queue.clear()
        assertFalse(a.closed); assertTrue(b.closed); assertTrue(c.closed)
    }
    @Test fun skipTrimsObsoleteEpisodesButRetainsUsefulFutureEpisodes() {
        val queue = PreparedEpisodes<Resource>({ 10L })
        val a = Resource(); val b = Resource()
        queue.complete(queue.begin("a:720:avc")!!, "a:720:avc", a)
        queue.complete(queue.begin("b:720:avc")!!, "b:720:avc", b)
        queue.retain(setOf("b:720:avc"))
        assertTrue(a.closed); assertFalse(b.closed)
        assertNull(queue.take("b:1080:avc"))
        assertSame(b, queue.take("b:720:avc"))
    }
    @Test fun cancellationRejectsLateCompletionWithoutAbortingReplacementRequest() {
        val queue = PreparedEpisodes<Resource>({ 10L })
        val oldTicket = queue.begin("a")!!
        queue.abortPending("a")
        val newTicket = queue.begin("a")!!
        queue.abortPending("a", oldTicket)
        val old = Resource(); val replacement = Resource()
        assertFalse(queue.complete(oldTicket, "a", old)); assertTrue(old.closed)
        assertTrue(queue.complete(newTicket, "a", replacement))
        queue.abortPending("a")
        assertSame(replacement, queue.take("a")); assertFalse(replacement.closed)
    }
    @Test fun staleAndClockReversedEntriesAreClosedAndNeverClaimed() {
        var now = 0L
        val queue = PreparedEpisodes<Resource>({ now })
        val old = Resource()
        queue.complete(queue.begin("a")!!, "a", old)
        now = 120_001
        assertNull(queue.take("a")); assertTrue(old.closed)
        val fresh = Resource(); queue.complete(queue.begin("a")!!, "a", fresh)
        now--
        assertFalse(queue.isFresh("a")); assertTrue(fresh.closed)
    }
    @Test fun latePendingResultsAndClearedGenerationsCloseThemselves() {
        var now = 0L
        val queue = PreparedEpisodes<Resource>({ now })
        val ticket = queue.begin("a")!!; now = 120_001
        val old = Resource(); assertFalse(queue.complete(ticket, "a", old)); assertTrue(old.closed)
        queue.expire()
        val second = queue.begin("a")!!; queue.clear()
        val stale = Resource(); assertFalse(queue.complete(second, "a", stale)); assertTrue(stale.closed)
    }
    @Test fun countIsAdjustableBoundedAndConservativeOnLowMemoryDevices() {
        assertEquals(1, PrefetchPolicy.DEFAULT_COUNT)
        assertEquals(0, PrefetchPolicy.count(-1)); assertEquals(3, PrefetchPolicy.count(99))
        assertEquals(0, PrefetchPolicy.effectiveCount(0, true))
        assertEquals(1, PrefetchPolicy.effectiveCount(3, true))
        assertEquals(3, PrefetchPolicy.effectiveCount(3, false))
        val original = BackupData(emptyList(), emptyList(), emptySet(), emptyList(), BackupSettings(prefetchCount=3))
        assertEquals(3, LibraryBackup.decode(LibraryBackup.encode(original)).settings.prefetchCount)
        assertEquals(3, LibraryDisk.decode(mapOf("prefetchCount" to 3)).settings.prefetchCount)
        assertEquals(1, LibraryDisk.decode(emptyMap<String, Any>()).settings.prefetchCount)
    }
    @Test fun prefetchStartsOnlyAfterStablePlaybackWithEnoughWallClockBuffer() {
        assertFalse(PrefetchPolicy.canStart(5_000, 60_000, 1f, 7_999, 20_000))
        assertFalse(PrefetchPolicy.canStart(10_000, 60_000, 1f, 8_000, 9_999))
        assertTrue(PrefetchPolicy.canStart(10_000, 60_000, 1f, 8_000, 10_000))
        assertFalse(PrefetchPolicy.canStart(10_000, 300_000, 1f, 8_000, 30_000))
        assertTrue(PrefetchPolicy.canStart(240_000, 300_000, 1f, 8_000, 30_000))
        assertFalse(PrefetchPolicy.canStart(10_000, 60_000, 2f, 8_000, 10_000))
        assertTrue(PrefetchPolicy.canStart(10_000, 60_000, 2f, 8_000, 20_000))
        assertTrue(PrefetchPolicy.canStart(56_000, 60_000, 1f, 8_000, 4_000))
        assertFalse(PrefetchPolicy.canStart(60_000, 60_000, 1f, 8_000, 10_000))
        assertFalse(PrefetchPolicy.canStart(0, 0, 1f, 8_000, 30_000))
        assertFalse(PrefetchPolicy.canStart(10_000, 60_000, Float.NaN, 8_000, 30_000))
    }
}
