package com.anezium.rokidbus.phone

import android.content.Context
import android.content.SharedPreferences
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileSize
import org.json.JSONArray
import org.json.JSONObject

/**
 * The owner's wearer-chosen tile order and size, overriding Delivery 1's auto-pack default.
 *
 * The layout lives on the glasses — the phone is exactly what is absent at boot — so this store is
 * the phone's copy of record: the layout editor writes it, and the hub re-pushes it on every
 * glasses capabilities announce, so an edit made while the link was down still lands. An empty
 * list is the valid default: no custom layout, every plugin falls back to the auto-pack default.
 */
class TileLayoutSettingsStore private constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE),
    )

    fun getEntries(): List<TileLayoutEntry> {
        val raw = preferences.getString(KEY_ENTRIES, null) ?: return emptyList()
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

    fun setEntries(entries: List<TileLayoutEntry>) {
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
        preferences.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    companion object {
        private const val KEY_ENTRIES = "hud_tile_layout_entries"
    }
}
