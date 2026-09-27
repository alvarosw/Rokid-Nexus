package com.anezium.rokidbus.glasses

import android.content.Context
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import org.json.JSONObject

/**
 * The last [TileSnapshot] published by each plugin, keyed by `pluginId`, plus when it arrived
 * (`SystemClock.elapsedRealtime()` at receipt, not wall-clock — see [receivedAtElapsedRealtime]).
 *
 * Unlike `SurfaceController`, which holds no state once a plugin goes dormant, this persists to
 * disk: the grid has to show *something* immediately after the glasses hub itself restarts,
 * before any plugin has re-published. `elapsedRealtime` does not survive a reboot, so a snapshot
 * loaded from a prior process is deliberately treated as maximally stale (see [CachedTile.ageMs])
 * rather than pretending to know how old it really is.
 */
internal object TileCache {
    private const val PREFS = "tile_cache"
    private const val KEY_PREFIX = "tile."

    data class CachedTile(val snapshot: TileSnapshot, val receivedAtElapsedRealtime: Long)

    fun put(context: Context, snapshot: TileSnapshot, nowElapsedRealtime: Long) {
        val entry = JSONObject()
            .put("snapshot", WidgetTileContract.toPayload(snapshot))
            .put("receivedAtElapsedRealtime", nowElapsedRealtime)
        prefs(context).edit().putString(KEY_PREFIX + snapshot.pluginId, entry.toString()).apply()
    }

    fun get(context: Context, pluginId: String): CachedTile? {
        val raw = prefs(context).getString(KEY_PREFIX + pluginId, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val snapshot = WidgetTileContract.fromPayload(json.optJSONObject("snapshot")) ?: return null
        val receivedAt = if (json.has("receivedAtElapsedRealtime")) {
            json.optLong("receivedAtElapsedRealtime")
        } else {
            return null
        }
        return CachedTile(snapshot, receivedAt)
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * A cached snapshot's `receivedAtElapsedRealtime` was measured against a clock that resets on
     * reboot. If the hub's own uptime is younger than that recorded timestamp, the process that
     * wrote it is gone (a prior boot), so the entry is reported at [STALENESS_THRESHOLD_MS] — the
     * "stale" boundary — rather than computing a negative or meaningless age.
     */
    fun ageMs(cached: CachedTile, nowElapsedRealtime: Long): Long =
        if (cached.receivedAtElapsedRealtime > nowElapsedRealtime) {
            STALENESS_THRESHOLD_MS
        } else {
            nowElapsedRealtime - cached.receivedAtElapsedRealtime
        }

    fun isStale(cached: CachedTile, nowElapsedRealtime: Long): Boolean =
        ageMs(cached, nowElapsedRealtime) >= STALENESS_THRESHOLD_MS

    /** Proposed default per the roadmap; not yet validated against a wearer's real sense of "stale". */
    const val STALENESS_THRESHOLD_MS = 10 * 60 * 1000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
