package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePublishCoalescerTest {
    private var now = 0L
    private val coalescer = TilePublishCoalescer<String>(capacity = 2, refillIntervalMs = 20_000L) { now }

    @Test
    fun `publishes within the burst are sent at once`() {
        assertEquals(TilePublishCoalescer.Decision.Send("a1"), coalescer.offer("a", "a1"))
        assertEquals(TilePublishCoalescer.Decision.Send("a2"), coalescer.offer("a", "a2"))
        assertNull(coalescer.nextFlushAtMs())
    }

    @Test
    fun `a publish past the budget is held until a token refills`() {
        coalescer.offer("a", "a1")
        coalescer.offer("a", "a2")
        assertEquals(TilePublishCoalescer.Decision.Held(10_000L), coalescer.offer("a", "a3"))
        assertEquals(10_000L, coalescer.nextFlushAtMs())
        now = 9_999L
        assertTrue(coalescer.drainDue().isEmpty())
        now = 10_000L
        assertEquals(listOf("a" to "a3"), coalescer.drainDue())
        assertNull(coalescer.nextFlushAtMs())
    }

    @Test
    fun `the latest held publish wins and the final state is never dropped`() {
        coalescer.offer("a", "a1")
        coalescer.offer("a", "a2")
        coalescer.offer("a", "a3")
        coalescer.offer("a", "a4")
        coalescer.offer("a", "a5")
        now = 10_000L
        assertEquals(listOf("a" to "a5"), coalescer.drainDue())
        assertTrue(coalescer.drainDue().isEmpty())
    }

    @Test
    fun `a publish arriving while one is held never overtakes it`() {
        coalescer.offer("a", "a1")
        coalescer.offer("a", "a2")
        coalescer.offer("a", "a3")
        now = 10_000L
        // A token is back, but the held publish is still waiting for its flush: the new one
        // replaces it and goes out through the flush, never ahead of it.
        assertTrue(coalescer.offer("a", "a4") is TilePublishCoalescer.Decision.Held)
        assertEquals(listOf("a" to "a4"), coalescer.drainDue())
    }

    @Test
    fun `plugins have independent budgets`() {
        coalescer.offer("a", "a1")
        coalescer.offer("a", "a2")
        assertTrue(coalescer.offer("a", "a3") is TilePublishCoalescer.Decision.Held)
        assertEquals(TilePublishCoalescer.Decision.Send("b1"), coalescer.offer("b", "b1"))
    }

    @Test
    fun `a removed plugin's held publish is discarded`() {
        coalescer.offer("a", "a1")
        coalescer.offer("a", "a2")
        coalescer.offer("a", "a3")
        coalescer.remove("a")
        now = 60_000L
        assertTrue(coalescer.drainDue().isEmpty())
        assertNull(coalescer.nextFlushAtMs())
    }

    @Test
    fun `retainOnly discards held publishes of other plugins`() {
        listOf("a", "b").forEach { id -> repeat(3) { coalescer.offer(id, "$id$it") } }
        coalescer.retainOnly(setOf("b"))
        now = 10_000L
        assertEquals(listOf("b" to "b2"), coalescer.drainDue())
    }

    @Test
    fun `the default budget stays inside the glasses' rate limiter`() {
        val phoneRate = TilePublishCoalescer.DEFAULT_CAPACITY.toDouble() /
            TilePublishCoalescer.DEFAULT_REFILL_INTERVAL_MS
        // Mirrors glasses TileRateLimiter's defaults: 5 per 60 s.
        val glassesRate = 5.0 / 60_000L
        assertTrue(TilePublishCoalescer.DEFAULT_CAPACITY < 5)
        assertTrue(phoneRate < glassesRate)
    }
}
