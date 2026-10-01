package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import java.io.InputStream
import org.json.JSONObject

/**
 * A plugin's declared sample tile (`TILE_PREVIEW`): one `/tile/publish` payload in a raw resource
 * of the plugin's own package, read cross-package as its glyphs are. Anything unreadable, oversized
 * or invalid yields null; a sample is never fatal to anything.
 */
internal object PluginTilePreviewReader {
    fun read(context: Context, principal: PhonePluginPrincipal): TileSnapshot? {
        val resourceId = principal.descriptor.tilePreviewResId ?: return null
        val bytes = runCatching {
            context.createPackageContext(principal.packageName, 0)
                .resources
                .openRawResource(resourceId)
                .use { it.readAtMost(WidgetTileContract.MAX_PAYLOAD_BYTES + 1) }
        }.getOrNull() ?: return null
        return parse(principal.descriptor.id, bytes)
    }

    /** Stamped with [pluginId], like a live publish; the sample's own `pluginId` is ignored. */
    fun parse(pluginId: String, bytes: ByteArray): TileSnapshot? {
        if (bytes.size > WidgetTileContract.MAX_PAYLOAD_BYTES) return null
        val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return null
        return WidgetTileContract.fromPayload(json.put("pluginId", pluginId))
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var size = 0
        while (size < limit) {
            val read = read(buffer, size, limit - size)
            if (read < 0) break
            size += read
        }
        return buffer.copyOf(size)
    }
}
