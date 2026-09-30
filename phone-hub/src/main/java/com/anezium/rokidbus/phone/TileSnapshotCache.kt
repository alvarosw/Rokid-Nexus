package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import org.json.JSONObject

/**
 * The last live tile each plugin published through this phone hub, kept only so the layout editor
 * can preview real tile content. In memory and bounded: never persisted, and a restart simply
 * leaves the preview on the fallback tile until the plugin publishes again.
 */
internal object TileSnapshotCache {
    const val MAX_ENTRIES = 64

    private val lock = Any()
    private val snapshots = object : LinkedHashMap<String, TileSnapshot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TileSnapshot>): Boolean =
            size > MAX_ENTRIES
    }

    /**
     * Records [payload] for [pluginId], the authenticated sender stamped by the hub. The payload's
     * own `pluginId` is ignored: a plugin must not be able to fill another plugin's preview.
     */
    fun record(pluginId: String, payload: JSONObject) {
        val decoded = WidgetTileContract.fromPayload(JSONObject(payload.toString()).put("pluginId", pluginId))
            ?: return
        synchronized(lock) { snapshots[pluginId] = decoded }
    }

    fun get(pluginId: String): TileSnapshot? = synchronized(lock) { snapshots[pluginId] }

    fun clear() = synchronized(lock) { snapshots.clear() }
}
