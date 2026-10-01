package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ImageSurfaceValidationResult
import com.anezium.rokidbus.shared.MediaArtworkContract
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import java.util.UUID
import org.json.JSONObject

/**
 * Publishes this plugin's closed-state grid tile, per `docs/grid-hud-roadmap/03-delivery-3-tile-
 * data-pipeline.md`. Callable only from an existing legitimate wake — see the Background Policy
 * section of `docs/PLUGINS.md` — this delivery grants no plugin new background time, it only adds
 * a side effect to wakes that already exist.
 *
 * Unlike [NexusSurfaceSession], a tile is not foreground-exclusive: every plugin owns its own tile
 * slot (keyed by `pluginId`, stamped server-side, never by the local session id), so publishing
 * never produces `SURFACE_BUSY`. The hub may still drop a publish silently (rate limit) rather
 * than reject it, per the "give up quietly" ethos already used for `SURFACE_BUSY`.
 */
class NexusWidgetTileSession internal constructor(
    private val client: NexusPluginClient,
    val localSessionId: String,
) : WidgetTileSession {
    /** The last `artworkKey` whose bytes this session sent; bytes cross the bus once per key. */
    private var sentArtworkKey: String? = null

    override fun publish(snapshot: TileSnapshot): NexusSdkResult {
        val payload = checkedPayload(snapshot) ?: return rejection()
        return if (client.send(BusPaths.TILE_PUBLISH, UUID.randomUUID().toString(), payload)) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    /**
     * Publishes a [TileContent.Music] snapshot with its cover, [artworkBytes] (a JPEG or PNG within
     * the media artwork limits: 64 KiB, 256 px a side), under the snapshot's `artworkKey`. The bytes
     * are sent only when the key differs from the last one this session sent; otherwise this is a
     * plain [publish]. The hub keeps the cover with the tile and draws it in the art box.
     */
    override fun publish(snapshot: TileSnapshot, artworkBytes: ByteArray): NexusSdkResult {
        val key = (snapshot.content as? TileContent.Music)?.artworkKey.orEmpty()
        if (key.isBlank()) return NexusSdkResult.INVALID_PAYLOAD
        val payload = checkedPayload(snapshot) ?: return rejection()
        synchronized(this) {
            if (key == sentArtworkKey) return publish(snapshot)
            val artwork = MediaArtworkContract.describe(artworkBytes) ?: return NexusSdkResult.INVALID_PAYLOAD
            val withArtwork = WidgetTileContract.withArtwork(payload, artwork)
            if (WidgetTileContract.validateArtwork(withArtwork, artworkBytes) !is ImageSurfaceValidationResult.Valid) {
                return NexusSdkResult.INVALID_PAYLOAD
            }
            if (!client.sendBinary(BusPaths.TILE_PUBLISH, UUID.randomUUID().toString(), withArtwork, artworkBytes)) {
                return NexusSdkResult.NOT_REGISTERED
            }
            sentArtworkKey = key
            return NexusSdkResult.SENT
        }
    }

    private fun checkedPayload(snapshot: TileSnapshot): JSONObject? {
        if (!client.isApproved || !client.hasCapability(PluginCapability.WIDGET_TILE)) return null
        val payload = WidgetTileContract.toPayload(snapshot)
        if (payload.toString().toByteArray(Charsets.UTF_8).size > WidgetTileContract.MAX_PAYLOAD_BYTES) return null
        return payload
    }

    private fun rejection(): NexusSdkResult = when {
        !client.isApproved -> NexusSdkResult.NOT_REGISTERED
        !client.hasCapability(PluginCapability.WIDGET_TILE) -> NexusSdkResult.CAPABILITY_NOT_GRANTED
        else -> NexusSdkResult.INVALID_PAYLOAD
    }
}

interface WidgetTileSession {
    fun publish(snapshot: TileSnapshot): NexusSdkResult

    /** A music tile with its cover; see [NexusWidgetTileSession.publish]. Without support, text only. */
    fun publish(snapshot: TileSnapshot, artworkBytes: ByteArray): NexusSdkResult = publish(snapshot)
}

fun NexusPluginClient.widgetTileSession(localSessionId: String): NexusWidgetTileSession =
    NexusWidgetTileSession(this, localSessionId)
