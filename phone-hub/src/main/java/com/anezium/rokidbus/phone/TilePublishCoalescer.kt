package com.anezium.rokidbus.phone

import kotlin.math.ceil

/**
 * Per-plugin pacing for `/tile/publish` before it crosses the link. The glasses keep their own
 * `TileRateLimiter`, which drops what exceeds its bucket; pacing here, inside that budget, means a
 * burst never reaches it. Unlike the glasses' limiter this one never loses the final state: a
 * publish that arrives with the budget spent is held, a newer one replaces it (a tile only ever
 * shows its latest snapshot), and the held one is released as soon as a token refills.
 *
 * The default budget is one token under the glasses' burst and a slower refill (one per 15 s
 * against their 12 s), so link jitter compressing two sends cannot push the glasses over theirs.
 */
internal class TilePublishCoalescer<T>(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val refillIntervalMs: Long = DEFAULT_REFILL_INTERVAL_MS,
    private val nowMs: () -> Long,
) {
    sealed interface Decision<out T> {
        data class Send<T>(val item: T) : Decision<T>
        data class Held(val flushAtMs: Long) : Decision<Nothing>
    }

    private inner class Bucket(var tokens: Double, var lastRefillMs: Long) {
        var held: T? = null
        var hasHeld = false
    }

    private val buckets = mutableMapOf<String, Bucket>()

    @Synchronized
    fun offer(pluginId: String, item: T): Decision<T> {
        val now = nowMs()
        val bucket = refill(pluginId, now)
        if (!bucket.hasHeld && bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            return Decision.Send(item)
        }
        bucket.held = item
        bucket.hasHeld = true
        return Decision.Held(flushAt(bucket, now))
    }

    /** Releases every held publish whose plugin has a token again. */
    @Synchronized
    fun drainDue(): List<Pair<String, T>> {
        val now = nowMs()
        val due = mutableListOf<Pair<String, T>>()
        buckets.keys.toList().forEach { pluginId ->
            val bucket = refill(pluginId, now)
            if (!bucket.hasHeld || bucket.tokens < 1.0) return@forEach
            bucket.tokens -= 1.0
            @Suppress("UNCHECKED_CAST")
            due += pluginId to (bucket.held as T)
            bucket.held = null
            bucket.hasHeld = false
        }
        return due
    }

    /** When the earliest held publish can go, or null when nothing is held. */
    @Synchronized
    fun nextFlushAtMs(): Long? {
        val now = nowMs()
        return buckets.keys.toList()
            .mapNotNull { pluginId -> refill(pluginId, now).takeIf { it.hasHeld }?.let { flushAt(it, now) } }
            .minOrNull()
    }

    @Synchronized
    fun remove(pluginId: String) {
        buckets.remove(pluginId)
    }

    @Synchronized
    fun retainOnly(pluginIds: Set<String>) {
        buckets.keys.retainAll(pluginIds)
    }

    private fun refill(pluginId: String, now: Long): Bucket {
        val bucket = buckets.getOrPut(pluginId) { Bucket(capacity.toDouble(), now) }
        val elapsed = (now - bucket.lastRefillMs).coerceAtLeast(0)
        bucket.tokens = (bucket.tokens + elapsed * refillRatePerMs).coerceAtMost(capacity.toDouble())
        bucket.lastRefillMs = now
        return bucket
    }

    private fun flushAt(bucket: Bucket, now: Long): Long {
        if (bucket.tokens >= 1.0) return now
        return now + ceil((1.0 - bucket.tokens) / refillRatePerMs).toLong()
    }

    private val refillRatePerMs: Double
        get() = capacity.toDouble() / refillIntervalMs

    companion object {
        const val DEFAULT_CAPACITY = 4
        const val DEFAULT_REFILL_INTERVAL_MS = 60_000L
    }
}
