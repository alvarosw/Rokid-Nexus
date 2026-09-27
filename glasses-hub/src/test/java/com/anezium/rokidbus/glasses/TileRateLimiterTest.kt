package com.anezium.rokidbus.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileRateLimiterTest {
    @Test
    fun `burst up to capacity then drops`() {
        var now = 0L
        val limiter = TileRateLimiter(capacity = 3, refillIntervalMs = 60_000L, nowMs = { now })
        assertTrue(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
    }

    @Test
    fun `refills over time`() {
        var now = 0L
        val limiter = TileRateLimiter(capacity = 1, refillIntervalMs = 1_000L, nowMs = { now })
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
        now += 1_000L
        assertTrue(limiter.tryAcquire("a"))
    }

    @Test
    fun `one plugin flooding does not throttle another`() {
        var now = 0L
        val limiter = TileRateLimiter(capacity = 1, refillIntervalMs = 60_000L, nowMs = { now })
        assertTrue(limiter.tryAcquire("flooder"))
        assertFalse(limiter.tryAcquire("flooder"))
        assertTrue(limiter.tryAcquire("quiet"))
    }

    @Test
    fun `clear resets all buckets`() {
        var now = 0L
        val limiter = TileRateLimiter(capacity = 1, refillIntervalMs = 60_000L, nowMs = { now })
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
        limiter.clear()
        assertTrue(limiter.tryAcquire("a"))
    }
}
