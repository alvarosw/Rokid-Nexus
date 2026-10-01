package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TileControllerTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun envelopeFor(pluginId: String) = BusEnvelope(
        path = BusPaths.TILE_PUBLISH,
        payload = WidgetTileContract.toPayload(
            TileSnapshot(pluginId = pluginId, contentKey = "eta", title = "12"),
        ),
    )

    @After
    fun tearDown() {
        TileController.stop()
        TileCache.clear(context)
    }

    @Test
    fun `an unrelated path is not consumed`() {
        assertFalse(TileController.handleTileEnvelope(context, BusEnvelope(path = "/surface/show")))
    }

    @Test
    fun `while inactive, publish is consumed but never cached (list mode gate)`() {
        TileCache.clear(context)
        val consumed = TileController.handleTileEnvelope(context, envelopeFor("transit"))
        assertTrue(consumed)
        assertFalse(TileController.isActive)
        assertNull(TileCache.get(context, "transit"))
    }

    @Test
    fun `once started, a valid publish is cached`() {
        TileController.start(context)
        assertTrue(TileController.handleTileEnvelope(context, envelopeFor("transit")))
        assertEquals("12", (TileCache.get(context, "transit")?.snapshot?.content as? TileContent.Generic)?.title)
    }

    @Test
    fun `malformed payload is dropped without crashing`() {
        TileController.start(context)
        val bad = BusEnvelope(path = BusPaths.TILE_PUBLISH, payload = JSONObject())
        assertTrue(TileController.handleTileEnvelope(context, bad))
        assertNull(TileCache.get(context, ""))
    }

    @Test
    fun `publishing above the rate ceiling is dropped silently, no crash`() {
        TileController.start(context)
        TileController.setRateLimiterForTest(TileRateLimiter(capacity = 1, refillIntervalMs = 60_000L))
        assertTrue(TileController.handleTileEnvelope(context, envelopeFor("transit")))
        val firstReceivedAt = TileCache.get(context, "transit")?.receivedAtElapsedRealtime
        // A second publish beyond the bucket's capacity must not throw and must not overwrite.
        assertTrue(TileController.handleTileEnvelope(context, envelopeFor("transit")))
        assertEquals(firstReceivedAt, TileCache.get(context, "transit")?.receivedAtElapsedRealtime)
    }

    @Test
    fun `stop clears the rate limiter but leaves the on-disk cache alone`() {
        TileController.start(context)
        TileController.handleTileEnvelope(context, envelopeFor("transit"))
        TileController.stop()
        assertFalse(TileController.isActive)
        assertEquals("12", (TileCache.get(context, "transit")?.snapshot?.content as? TileContent.Generic)?.title)
    }
}
