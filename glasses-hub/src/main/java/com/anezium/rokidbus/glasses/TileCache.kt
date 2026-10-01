package com.anezium.rokidbus.glasses

import android.content.Context
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import java.util.concurrent.CopyOnWriteArrayList
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

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /**
     * Called with the plugin id after every [put], on the thread that wrote it, so an open grid can
     * refresh that one tile instead of waiting for the next selection move (F-10).
     */
    fun observe(listener: (String) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun put(context: Context, snapshot: TileSnapshot, nowElapsedRealtime: Long) {
        val entry = JSONObject()
            .put("snapshot", WidgetTileContract.toPayload(snapshot))
            .put("receivedAtElapsedRealtime", nowElapsedRealtime)
        prefs(context).edit().putString(KEY_PREFIX + snapshot.pluginId, entry.toString()).apply()
        listeners.forEach { runCatching { it(snapshot.pluginId) } }
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

    /**
     * Drops every cached tile whose plugin is not in [pluginIds], the launcher list the phone just
     * sent: an uninstalled, revoked or disabled plugin leaves that list, and its last tile must not
     * linger on disk for a plugin that may never publish again.
     */
    fun retainOnly(context: Context, pluginIds: Set<String>) {
        val prefs = prefs(context)
        val stale = prefs.all.keys.filter { key ->
            key.startsWith(KEY_PREFIX) && key.removePrefix(KEY_PREFIX) !in pluginIds
        }
        if (stale.isEmpty()) return
        prefs.edit().apply { stale.forEach(::remove) }.apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * A cached snapshot's `receivedAtElapsedRealtime` was measured against a clock that resets on
     * reboot. If the hub's own uptime is younger than that recorded timestamp, the process that
     * wrote it is gone (a prior boot), so the entry is reported at its own `staleAfterMs` — the
     * "stale" boundary — rather than computing a negative or meaningless age.
     */
    fun ageMs(cached: CachedTile, nowElapsedRealtime: Long): Long =
        if (cached.receivedAtElapsedRealtime > nowElapsedRealtime) {
            cached.snapshot.staleAfterMs
        } else {
            nowElapsedRealtime - cached.receivedAtElapsedRealtime
        }

    /** Stale once the snapshot is as old as the `staleAfterMs` its plugin published with it. */
    fun isStale(cached: CachedTile, nowElapsedRealtime: Long): Boolean =
        ageMs(cached, nowElapsedRealtime) >= cached.snapshot.staleAfterMs

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
