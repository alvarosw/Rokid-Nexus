package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import java.util.UUID

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
    override fun publish(snapshot: TileSnapshot): NexusSdkResult {
        if (!client.isApproved) return NexusSdkResult.NOT_REGISTERED
        if (!client.hasCapability(PluginCapability.WIDGET_TILE)) return NexusSdkResult.CAPABILITY_NOT_GRANTED
        val payload = WidgetTileContract.toPayload(snapshot)
        if (payload.toString().toByteArray(Charsets.UTF_8).size > WidgetTileContract.MAX_PAYLOAD_BYTES) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        return if (client.send(BusPaths.TILE_PUBLISH, UUID.randomUUID().toString(), payload)) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }
}

interface WidgetTileSession {
    fun publish(snapshot: TileSnapshot): NexusSdkResult
}

fun NexusPluginClient.widgetTileSession(localSessionId: String): NexusWidgetTileSession =
    NexusWidgetTileSession(this, localSessionId)
