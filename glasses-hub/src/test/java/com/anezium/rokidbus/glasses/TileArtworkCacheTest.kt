package com.anezium.rokidbus.glasses

import android.graphics.Bitmap
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.MediaArtworkContract
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileArtworkCacheTest {
    private val context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        TileController.stop()
        TileCache.clear(context)
        TileArtworkCache.clear(context)
    }

    private fun png(edge: Int = 32): ByteArray {
        val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun publish(key: String, bytes: ByteArray?, pluginId: String = "media"): BusEnvelope {
        val payload = WidgetTileContract.toPayload(
            TileSnapshot(pluginId = pluginId, contentKey = "track", content = TileContent.Music(title = "Track", playing = true, artworkKey = key)),
        )
        return BusEnvelope(
            path = BusPaths.TILE_PUBLISH,
            payload = bytes?.let { WidgetTileContract.withArtwork(payload, MediaArtworkContract.describe(it)!!) } ?: payload,
            binary = bytes,
        )
    }

    @Test
    fun `a publish's cover is kept under its key and decoded`() {
        TileController.start(context)
        TileController.handleTileEnvelope(context, publish("a", png()))
        val bitmap = TileArtworkCache.bitmap(context, "media", "a")
        assertNotNull(bitmap)
        assertEquals(32, bitmap!!.width)
        assertNull(TileArtworkCache.bitmap(context, "media", "b"))
        assertNull(TileArtworkCache.bitmap(context, "other", "a"))
    }

    @Test
    fun `the cover is kept even when the limiter drops its snapshot`() {
        TileController.start(context)
        TileController.setRateLimiterForTest(TileRateLimiter(capacity = 1, refillIntervalMs = 60_000L))
        TileController.handleTileEnvelope(context, publish("a", null))
        TileController.handleTileEnvelope(context, publish("b", png()))
        assertEquals("a", (TileCache.get(context, "media")?.snapshot?.content as TileContent.Music).artworkKey)
        assertNotNull(TileArtworkCache.bitmap(context, "media", "b"))
    }

    @Test
    fun `a cover that does not match its description is dropped`() {
        TileController.start(context)
        val envelope = publish("a", png())
        TileController.handleTileEnvelope(context, envelope.copy(binary = png(edge = 16)))
        assertNull(TileArtworkCache.bitmap(context, "media", "a"))
    }

    @Test
    fun `covers are persisted, pruned with their plugin and bounded`() {
        TileController.start(context)
        TileController.handleTileEnvelope(context, publish("a", png()))
        TileController.handleTileEnvelope(context, publish("g", png(), pluginId = "gone"))
        TileArtworkCache.retainOnly(context, setOf("media"))
        assertNull(TileArtworkCache.bitmap(context, "gone", "g"))
        assertNotNull(TileArtworkCache.bitmap(context, "media", "a"))
        val small = png(edge = 4)
        val metadata = (WidgetTileContract.validateArtwork(publish("k", small).payload, small) as com.anezium.rokidbus.shared.ImageSurfaceValidationResult.Valid).metadata
        (0..TileArtworkCache.MAX_ENTRIES).forEach { TileArtworkCache.put(context, "p$it", "k", metadata, small) }
        val stored = context.getSharedPreferences("tile_artwork", 0).all.size
        assertEquals(TileArtworkCache.MAX_ENTRIES, stored)
        assertNull(TileArtworkCache.bitmap(context, "media", "a"))
        assertNotNull(TileArtworkCache.bitmap(context, "p${TileArtworkCache.MAX_ENTRIES}", "k"))
    }
}
