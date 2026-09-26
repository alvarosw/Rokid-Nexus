package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileSize
import org.json.JSONArray
import org.json.JSONObject

/**
 * The wearer's chosen grid-tile order and size per plugin, steered from the phone — Delivery 4's
 * override of Delivery 1's auto-pack default.
 *
 * The layout lives on the glasses because that is exactly what has to survive a hub restart or a
 * stretch with the phone disconnected. The phone holds the settings screen and is the copy of
 * record; the hub re-pushes it on every glasses capabilities announce, so a layout edited while
 * the link was down still lands. See `phone-hub/.../GlassesRepairSettingsStore.kt` for the exact
 * mechanism this mirrors.
 */
object TileLayoutContract {
    const val VERSION = 1

    fun configToJson(entries: List<TileLayoutEntry>): JSONObject = JSONObject()
        .put("version", VERSION)
        .put(
            "entries",
            JSONArray().also { array ->
                entries.forEach { entry ->
                    array.put(
                        JSONObject()
                            .put("pluginId", entry.pluginId)
                            .put("size", entry.size.wireValue)
                            .put("col", entry.col)
                            .put("row", entry.row),
                    )
                }
            },
        )

    /**
     * Null for anything this build does not recognise — the glasses' stored layout then stands.
     * A malformed individual entry is dropped rather than failing the whole config, mirroring how
     * a malformed launcher-list item degrades in [BusPaths.LAUNCHER_LIST].
     */
    fun entriesFromConfig(payload: JSONObject?): List<TileLayoutEntry>? {
        val json = payload ?: return null
        if (json.optInt("version", 0) < 1) return null
        val array = json.optJSONArray("entries") ?: return null
        val entries = mutableListOf<TileLayoutEntry>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val pluginId = item.optString("pluginId")
            val size = TileSize.fromWireValue(item.optString("size"))
            if (pluginId.isBlank() || size == null) continue
            entries += TileLayoutEntry(
                pluginId = pluginId,
                size = size,
                col = item.optInt("col"),
                row = item.optInt("row"),
            )
        }
        return entries
    }
}
