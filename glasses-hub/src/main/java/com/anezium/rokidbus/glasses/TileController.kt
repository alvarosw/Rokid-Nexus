package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.SystemClock
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ImageSurfaceValidationResult
import com.anezium.rokidbus.shared.tile.WidgetTileContract

/**
 * Dispatch and lifecycle for the closed-state tile-data pipeline (`/tile/publish`).
 *
 * Gated on grid mode, per the roadmap's "the subsystem is genuinely absent in list mode" battery
 * requirement: [start] is only ever called while [HudModeStore] says grid, and while [isActive]
 * is false, [handleTileEnvelope] does not touch [TileCache] or [TileRateLimiter] at all — no
 * listener registration, no cache warm-up. [GlassesHub] owns calling [start]/[stop] as the mode
 * flag changes; this object holds no opinion about *why* it's running, only *whether*.
 */
internal object TileController {
    @Volatile private var active = false
    private var rateLimiter = TileRateLimiter()

    val isActive: Boolean get() = active

    fun start(context: Context) {
        if (active) return
        active = true
    }

    fun stop() {
        active = false
        rateLimiter.clear()
    }

    /** Returns true if this envelope was this subsystem's to handle, whether or not it was kept. */
    fun handleTileEnvelope(context: Context, envelope: BusEnvelope): Boolean {
        if (envelope.path != BusPaths.TILE_PUBLISH) return false
        if (!active) return true
        val snapshot = WidgetTileContract.fromPayload(envelope.payload) ?: return true
        envelope.binary?.let { bytes -> keepArtwork(context, snapshot.pluginId, envelope, bytes) }
        if (!rateLimiter.tryAcquire(snapshot.pluginId)) return true
        TileCache.put(context, snapshot, SystemClock.elapsedRealtime())
        return true
    }

    /**
     * A cover rides a publish once per `artworkKey`, so it is kept even when the limiter drops its
     * snapshot: the tile draws it as soon as a snapshot naming that key is kept.
     */
    private fun keepArtwork(context: Context, pluginId: String, envelope: BusEnvelope, bytes: ByteArray) {
        val validation = WidgetTileContract.validateArtwork(envelope.payload, bytes)
        if (validation !is ImageSurfaceValidationResult.Valid) return
        TileArtworkCache.put(context, pluginId, WidgetTileContract.artworkKeyOf(envelope.payload), validation.metadata, bytes)
    }

    /** Test-only: lets a test swap in a fake clock without touching the hub's real dispatch. */
    internal fun setRateLimiterForTest(limiter: TileRateLimiter) {
        rateLimiter = limiter
    }
}
