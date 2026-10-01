package com.anezium.rokidbus.phone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import org.json.JSONObject

/**
 * The last music-tile cover each plugin sent with `/tile/publish`, keyed by its `artworkKey`. A
 * plugin sends the bytes once per key; this keeps them so that whichever publish actually crosses
 * the link (pacing may hold and replace publishes) carries them while the glasses lack that key,
 * and so the layout editor can draw the cover. In memory and bounded, like [TileSnapshotCache].
 */
internal object TileArtworkCache {
    const val MAX_ENTRIES = 16

    class Artwork(val key: String, val description: JSONObject, val bytes: ByteArray)

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, Artwork>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Artwork>): Boolean = size > MAX_ENTRIES
    }

    /** The key the glasses hold for each plugin, as far as this hub has delivered it since link-up. */
    private val delivered = HashMap<String, String>()
    private val decoded = HashMap<String, Pair<String, Bitmap?>>()

    fun put(pluginId: String, artwork: Artwork) = synchronized(lock) {
        entries[pluginId] = artwork
        decoded.remove(pluginId)
    }

    fun get(pluginId: String): Artwork? = synchronized(lock) { entries[pluginId] }

    /**
     * [envelope] with the cover attached when its snapshot names a key this hub holds the bytes of
     * and has not delivered yet; otherwise [envelope] itself.
     */
    fun forDelivery(pluginId: String, envelope: BusEnvelope): BusEnvelope = synchronized(lock) {
        val key = WidgetTileContract.artworkKeyOf(envelope.payload)
        val artwork = entries[pluginId]?.takeIf { key.isNotBlank() && it.key == key } ?: return envelope
        if (delivered[pluginId] == key) return envelope
        envelope.copy(payload = WidgetTileContract.withArtwork(envelope.payload, artwork.description), binary = artwork.bytes)
    }

    fun markDelivered(pluginId: String, key: String) = synchronized(lock) { delivered[pluginId] = key }

    /** The link came up: the glasses may have lost what they held, so each cover goes once more. */
    fun forgetDelivered() = synchronized(lock) { delivered.clear() }

    /** The decoded cover for the editor's preview, when this hub holds [key]'s bytes. */
    fun bitmap(pluginId: String, key: String): Bitmap? = synchronized(lock) {
        if (key.isBlank()) return null
        val artwork = entries[pluginId]?.takeIf { it.key == key } ?: return null
        decoded[pluginId]?.takeIf { it.first == key }?.let { return it.second }
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(artwork.bytes, 0, artwork.bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
        }.getOrNull()
        decoded[pluginId] = key to bitmap
        bitmap
    }

    fun remove(pluginId: String) = synchronized(lock) {
        entries.remove(pluginId)
        delivered.remove(pluginId)
        decoded.remove(pluginId)
    }

    fun retainOnly(pluginIds: Set<String>) = synchronized(lock) {
        entries.keys.retainAll(pluginIds)
        delivered.keys.retainAll(pluginIds)
        decoded.keys.retainAll(pluginIds)
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        delivered.clear()
        decoded.clear()
    }
}
