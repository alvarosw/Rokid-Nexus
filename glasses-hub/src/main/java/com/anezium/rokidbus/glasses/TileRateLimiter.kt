package com.anezium.rokidbus.glasses

/**
 * A per-`pluginId` token bucket: publishes past the ceiling are dropped silently, not queued,
 * matching the "give up quietly rather than retry-looping" ethos already stated for
 * `SURFACE_BUSY` in `docs/PLUGINS.md`. One plugin flooding its bucket must not affect another's
 * normal cadence, hence the per-key bucket rather than one shared counter.
 */
internal class TileRateLimiter(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val refillIntervalMs: Long = DEFAULT_REFILL_INTERVAL_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Bucket(var tokens: Double, var lastRefillMs: Long)

    private val buckets = mutableMapOf<String, Bucket>()

    @Synchronized
    fun tryAcquire(pluginId: String): Boolean {
        val now = nowMs()
        val bucket = buckets.getOrPut(pluginId) { Bucket(capacity.toDouble(), now) }
        val elapsed = (now - bucket.lastRefillMs).coerceAtLeast(0)
        val refillRatePerMs = capacity.toDouble() / refillIntervalMs
        bucket.tokens = (bucket.tokens + elapsed * refillRatePerMs).coerceAtMost(capacity.toDouble())
        bucket.lastRefillMs = now
        if (bucket.tokens < 1.0) return false
        bucket.tokens -= 1.0
        return true
    }

    @Synchronized
    fun clear() = buckets.clear()

    companion object {
        const val DEFAULT_CAPACITY = 5
        const val DEFAULT_REFILL_INTERVAL_MS = 60_000L
    }
}
