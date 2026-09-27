package com.anezium.rokidbus.glasses

import android.content.Context
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileSize
import org.json.JSONArray
import org.json.JSONObject

/**
 * The wearer's chosen tile order and size, as last pushed by the phone.
 *
 * Persisted here, not just held in memory, for the same reason as [HudModeStore]: it has to
 * survive a hub restart or a stretch with no phone connected. An empty list is the valid default —
 * no custom layout, every plugin falls back to Delivery 1's auto-pack default (install order,
 * [TileSize.SMALL]).
 */
internal object TileLayoutStore {
    private const val PREFS_NAME = "hud_tile_layout"
    private const val KEY_ENTRIES = "entries"

    fun setEntries(context: Context, entries: List<TileLayoutEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("pluginId", entry.pluginId)
                    .put("size", entry.size.wireValue)
                    .put("col", entry.col)
                    .put("row", entry.row),
            )
        }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    fun getEntries(context: Context): List<TileLayoutEntry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val pluginId = item.optString("pluginId")
                val size = TileSize.fromWireValue(item.optString("size"))
                if (pluginId.isBlank() || size == null) continue
                add(TileLayoutEntry(pluginId = pluginId, size = size, col = item.optInt("col"), row = item.optInt("row")))
            }
        }
    }

    /**
     * Reorders [entries] to match the stored layout order, appending anything not placed yet (a
     * newly installed plugin) after the custom-ordered ones, in [entries]' own order — "missing
     * config isn't an error, it's the default", applied without disturbing already-placed tiles.
     */
    fun applyOrder(context: Context, entries: List<GlassesHub.LauncherEntry>): List<GlassesHub.LauncherEntry> {
        val order = getEntries(context).map { it.pluginId }
        if (order.isEmpty()) return entries
        val byId = entries.associateBy { it.id }
        val ordered = order.mapNotNull { byId[it] }
        val remaining = entries.filterNot { it.id in order }
        return ordered + remaining
    }

    fun sizeFor(context: Context, pluginId: String): TileSize? =
        getEntries(context).firstOrNull { it.pluginId == pluginId }?.size

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
