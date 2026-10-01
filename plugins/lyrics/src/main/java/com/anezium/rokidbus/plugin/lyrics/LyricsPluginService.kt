package com.anezium.rokidbus.plugin.lyrics

import android.os.SystemClock
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPlaybackAnchor
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.client.plugin.NexusTimedLines
import com.anezium.rokidbus.client.plugin.WidgetTileSession
import com.anezium.rokidbus.lyrics.LyricsRuntime
import com.anezium.rokidbus.lyrics.LyricsRuntimeGraph
import com.anezium.rokidbus.lyrics.LyricsRuntimeHost
import com.anezium.rokidbus.lyrics.LyricsTileRuntime
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

class LyricsPluginService : NexusPluginService() {
    private var surface: NexusSurfaceSession? = null
    private val runtime by lazy { LyricsRuntime(runtimeHost) }
    private var surfaceOpen = false
    private var tileLeaseActive = false
    private var tileSession: WidgetTileSession? = null
    private val tileRuntime by lazy {
        LyricsTileRuntime(
            publish = { snapshot -> tileSession?.publish(snapshot) },
            subscribe = { listener -> LyricsRuntimeGraph.stateStore.subscribe(listener) },
            clock = SystemClock::elapsedRealtime,
        )
    }

    private val runtimeHost = object : LyricsRuntimeHost {
        override fun sendCard(card: NexusCard, show: Boolean) {
            val session = surfaceSession() ?: return
            if (show) session.showCard(card) else session.updateCard(card)
        }

        override fun sendTimedLines(lines: NexusTimedLines, show: Boolean) {
            val session = surfaceSession() ?: return
            if (show) session.showTimedLines(lines) else session.updateTimedLines(lines)
        }

        override fun updateTimedLinesAnchor(contentKey: String, anchor: NexusPlaybackAnchor) {
            surfaceSession()?.updateTimedLinesAnchor(contentKey, anchor)
        }

        override fun hideSurface() {
            surface?.hide()
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtime.register()
    }

    override fun onNexusOpen() {
        surfaceSession()
        surfaceOpen = true
        LyricsRuntimeGraph.start(applicationContext)
        runtime.open()
    }

    override fun onNexusClose() {
        runtime.close()
        surfaceOpen = false
        releaseGraph()
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        runtime.input(event)
    }

    override fun onNexusRegistrationState(result: Int) {
        if (result == PluginRegistrationResult.APPROVED) {
            runtime.registrationApproved()
        } else {
            runtime.close()
            surfaceOpen = false
            releaseGraph()
            surface = null
        }
    }

    override fun onNexusTileActive(active: Boolean) {
        if (active) {
            tileLeaseActive = true
            tileSession = nexusWidgetTileSession(TILE_ID)
            LyricsRuntimeGraph.start(applicationContext)
            tileRuntime.start()
        } else {
            tileRuntime.stop()
            tileSession = null
            tileLeaseActive = false
            releaseGraph()
        }
    }

    override fun onDestroy() {
        tileRuntime.stop()
        tileSession = null
        runtime.unregister()
        LyricsRuntimeGraph.stop()
        surface = null
        super.onDestroy()
    }

    private fun surfaceSession(): NexusSurfaceSession? =
        surface ?: nexusSurfaceSession(SURFACE_ID).also { surface = it }

    /** The open surface and the tile lease share the graph; it stops when neither needs it. */
    private fun releaseGraph() {
        if (!surfaceOpen && !tileLeaseActive) LyricsRuntimeGraph.stop()
    }

    private companion object {
        const val SURFACE_ID = "lyrics"
        const val TILE_ID = "lyrics"
    }
}
