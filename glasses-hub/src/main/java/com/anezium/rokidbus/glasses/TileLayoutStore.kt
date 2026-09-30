package com.anezium.rokidbus.glasses

import android.content.Context
import com.anezium.rokidbus.shared.tile.TileGridLayout
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TilePlacement
import com.anezium.rokidbus.shared.tile.TileSize
import org.json.JSONArray
import org.json.JSONObject

/**
 * The wearer's chosen tile positions and sizes, as last pushed by the phone.
 *
 * Persisted here, not just held in memory, for the same reason as [HudModeStore]: it has to
 * survive a hub restart or a stretch with no phone connected. An empty list is the valid default —
 * no custom layout, every plugin falls back to the auto-pack default (install order,
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
     * Where each of [entries] sits on the grid: the stored positions are authoritative, and anything
     * the layout does not place (a newly installed plugin, the camera entry before the phone ever
     * saw it) fills the first free cells in [entries]' own order. With no stored layout that is the
     * plain row-major auto-pack, every tile [TileSize.SMALL].
     */
    fun placements(context: Context, entries: List<GlassesHub.LauncherEntry>): List<TilePlacement> =
        TileGridLayout.resolve(entries.map { it.id to null }, getEntries(context))

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
