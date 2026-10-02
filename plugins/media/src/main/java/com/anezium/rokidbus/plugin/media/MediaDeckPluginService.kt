package com.anezium.rokidbus.plugin.media

import android.os.SystemClock
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusMedia
import com.anezium.rokidbus.client.plugin.NexusMediaAnchor
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.client.plugin.WidgetTileSession
import com.anezium.rokidbus.media.ImageArtworkEncoder
import com.anezium.rokidbus.media.MediaDeckRuntime
import com.anezium.rokidbus.media.MediaDeckRuntimeHost
import com.anezium.rokidbus.media.MediaDeckTileHost
import com.anezium.rokidbus.media.MediaDeckTileRuntime
import com.anezium.rokidbus.media.session.MediaSessionMonitor
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.tile.TileSnapshot

class MediaDeckPluginService : NexusPluginService() {
    private var surface: NexusSurfaceSession? = null
    private var runtime: MediaDeckRuntime? = null
    private var tileRuntime: MediaDeckTileRuntime? = null
    private var tileSession: WidgetTileSession? = null

    private val runtimeHost = object : MediaDeckRuntimeHost {
        override fun supportsImage(): Boolean = nexusClient?.supportsImageSurface == true

        override fun sendCard(card: NexusCard, show: Boolean) {
            val session = surfaceSession() ?: return
            if (show) session.showCard(card) else session.updateCard(card)
        }

        override fun sendMedia(media: NexusMedia, imageBytes: ByteArray?, show: Boolean) {
            val session = surfaceSession() ?: return
            if (imageBytes == null) {
                if (show) session.showMedia(media) else session.updateMedia(media)
            } else {
                if (show) session.showMedia(media, imageBytes) else session.updateMedia(media, imageBytes)
            }
        }

        override fun updateMediaAnchor(contentKey: String, anchor: NexusMediaAnchor) {
            surfaceSession()?.updateMediaAnchor(contentKey, anchor)
        }

        override fun hideSurface() {
            surface?.hide()
        }

        override fun post(action: () -> Unit) {
            mainExecutor.execute(action)
        }
    }

    private val tileHost = object : MediaDeckTileHost {
        override fun publish(snapshot: TileSnapshot, artworkBytes: ByteArray?) {
            val session = tileSession ?: return
            if (artworkBytes == null) session.publish(snapshot) else session.publish(snapshot, artworkBytes)
        }

        override fun post(action: () -> Unit) {
            mainExecutor.execute(action)
        }
    }

    override fun onNexusOpen() {
        surfaceSession()
        ensureRuntime().open()
    }

    override fun onNexusClose() {
        runtime?.close()
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        runtime?.input(event)
    }

    override fun onNexusRegistrationState(result: Int) {
        if (result != PluginRegistrationResult.APPROVED) runtime?.close()
    }

    override fun onNexusLinkState(state: Int) {
        runtime?.imageCapabilityChanged()
    }

    override fun onNexusTileActive(active: Boolean) {
        if (active) {
            // A fresh session per lease, so the cover is sent again to a hub that may have lost it.
            tileSession = nexusWidgetTileSession(TILE_ID)
            ensureTileRuntime().start()
        } else {
            tileRuntime?.stop()
            tileSession = null
        }
    }

    override fun onNexusTileRefresh() {
        tileRuntime?.refresh()
    }

    override fun onDestroy() {
        tileRuntime?.stop()
        tileRuntime = null
        tileSession = null
        runtime?.close()
        runtime = null
        surface = null
        super.onDestroy()
    }

    private fun surfaceSession(): NexusSurfaceSession? =
        surface ?: nexusSurfaceSession(SURFACE_ID).also { surface = it }

    private fun ensureRuntime(): MediaDeckRuntime = runtime ?: MediaDeckRuntime(
        context = applicationContext,
        host = runtimeHost,
    ).also { runtime = it }

    private fun ensureTileRuntime(): MediaDeckTileRuntime = tileRuntime ?: MediaDeckTileRuntime(
        host = tileHost,
        encodeArtwork = { snapshot ->
            ImageArtworkEncoder.encode(applicationContext, snapshot.artwork, snapshot.artworkUri)
        },
        clock = SystemClock::elapsedRealtime,
        watcherFactory = { onSnapshot, onStatus -> MediaSessionMonitor(applicationContext, onSnapshot, onStatus) },
    ).also { tileRuntime = it }

    private companion object {
        const val SURFACE_ID = "media"
        const val TILE_ID = "media"
    }
}
