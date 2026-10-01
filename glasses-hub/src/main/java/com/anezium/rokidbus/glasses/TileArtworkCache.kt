package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.ImageSurfaceMetadata
import org.json.JSONObject

/**
 * The cover of each plugin's music tile, next to [TileCache] and persisted for the same reason: the
 * grid draws the last tile right after a hub restart, and the phone sends a cover only once per
 * `artworkKey`. One entry per plugin (a new key replaces the old one), each within the media
 * artwork limits ([ImageSurfaceContract.MAX_IMAGE_BYTES]), at most [MAX_ENTRIES], and pruned with
 * the tile when its plugin leaves the launcher list.
 */
internal object TileArtworkCache {
    private const val PREFS = "tile_artwork"
    private const val KEY_PREFIX = "art."
    const val MAX_ENTRIES = 32

    private val decoded = HashMap<String, Pair<String, Bitmap?>>()

    fun put(context: Context, pluginId: String, key: String, metadata: ImageSurfaceMetadata, bytes: ByteArray) {
        if (bytes.size > ImageSurfaceContract.MAX_IMAGE_BYTES) return
        val prefs = prefs(context)
        val sequence = prefs.all.values.mapNotNull { raw -> (raw as? String)?.let(::sequenceOf) }.maxOrNull()?.plus(1) ?: 0L
        val entry = JSONObject()
            .put("key", key)
            .put("mimeType", metadata.mimeType)
            .put("pixelWidth", metadata.pixelWidth)
            .put("pixelHeight", metadata.pixelHeight)
            .put("sha256", metadata.sha256)
            .put("sequence", sequence)
            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val editor = prefs.edit().putString(KEY_PREFIX + pluginId, entry.toString())
        // The oldest covers go first once the cap is reached.
        val evicted = prefs.all.entries
            .filter { it.key.startsWith(KEY_PREFIX) && it.key != KEY_PREFIX + pluginId }
            .sortedByDescending { (it.value as? String)?.let(::sequenceOf) ?: -1L }
            .drop(MAX_ENTRIES - 1)
            .map { it.key }
        evicted.forEach(editor::remove)
        editor.apply()
        synchronized(decoded) {
            decoded.remove(pluginId)
            evicted.forEach { decoded.remove(it.removePrefix(KEY_PREFIX)) }
        }
    }

    /** The decoded cover of [pluginId] when it is the one stored under [key]; decoded once. */
    fun bitmap(context: Context, pluginId: String, key: String): Bitmap? {
        if (key.isBlank()) return null
        synchronized(decoded) { decoded[pluginId]?.takeIf { it.first == key }?.let { return it.second } }
        val raw = prefs(context).getString(KEY_PREFIX + pluginId, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (json.optString("key") != key) return null
        val bytes = runCatching { Base64.decode(json.optString("data"), Base64.NO_WRAP) }.getOrNull() ?: return null
        val metadata = SurfaceImageMetadata(
            version = ImageSurfaceContract.VERSION,
            contentKey = key,
            mimeType = json.optString("mimeType"),
            pixelWidth = json.optInt("pixelWidth"),
            pixelHeight = json.optInt("pixelHeight"),
            sha256 = json.optString("sha256"),
            caption = "",
        )
        val bitmap = ImageHudView.decodeRgb565(bytes, metadata)
        synchronized(decoded) { decoded[pluginId] = key to bitmap }
        return bitmap
    }

    /** Keeps only the covers of [pluginIds], as [TileCache.retainOnly] does for the snapshots. */
    fun retainOnly(context: Context, pluginIds: Set<String>) {
        val prefs = prefs(context)
        val gone = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) && it.removePrefix(KEY_PREFIX) !in pluginIds }
        synchronized(decoded) { decoded.keys.retainAll(pluginIds) }
        if (gone.isEmpty()) return
        prefs.edit().apply { gone.forEach(::remove) }.apply()
    }

    fun clear(context: Context) {
        synchronized(decoded) { decoded.clear() }
        prefs(context).edit().clear().apply()
    }

    private fun sequenceOf(raw: String): Long? = runCatching { JSONObject(raw).getLong("sequence") }.getOrNull()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
