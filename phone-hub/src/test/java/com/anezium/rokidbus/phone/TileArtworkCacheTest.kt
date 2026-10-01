package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TileArtworkCacheTest {
    @After
    fun clear() = TileArtworkCache.clear()

    private val bytes = byteArrayOf(1, 2, 3)
    private val description = JSONObject().put("encoding", "binary").put("mimeType", "image/jpeg")

    private fun publish(key: String, title: String = "Track") = BusEnvelope(
        path = BusPaths.TILE_PUBLISH,
        payload = WidgetTileContract.toPayload(
            TileSnapshot(pluginId = "media", contentKey = "k", content = TileContent.Music(title = title, playing = true, artworkKey = key)),
        ),
    )

    @Test
    fun `the publish that crosses carries the cover until it is delivered`() {
        TileArtworkCache.put("media", TileArtworkCache.Artwork("a", description, bytes))
        // A later publish replaced the held one that came with the bytes: it still carries them.
        val sent = TileArtworkCache.forDelivery("media", publish("a", title = "Newer"))
        assertArrayEquals(bytes, sent.binary)
        assertEquals("image/jpeg", sent.payload.getJSONObject(WidgetTileContract.ARTWORK_FIELD).getString("mimeType"))
        TileArtworkCache.markDelivered("media", "a")
        val again = publish("a")
        assertSame(again, TileArtworkCache.forDelivery("media", again))
    }

    @Test
    fun `a link-up sends each cover once more`() {
        TileArtworkCache.put("media", TileArtworkCache.Artwork("a", description, bytes))
        TileArtworkCache.markDelivered("media", "a")
        TileArtworkCache.forgetDelivered()
        assertArrayEquals(bytes, TileArtworkCache.forDelivery("media", publish("a")).binary)
    }

    @Test
    fun `a snapshot naming another key or none goes without bytes`() {
        TileArtworkCache.put("media", TileArtworkCache.Artwork("a", description, bytes))
        assertNull(TileArtworkCache.forDelivery("media", publish("b")).binary)
        assertNull(TileArtworkCache.forDelivery("media", publish("")).binary)
        assertNull(TileArtworkCache.forDelivery("other", publish("a")).binary)
        assertFalse(TileArtworkCache.forDelivery("media", publish("b")).payload.has(WidgetTileContract.ARTWORK_FIELD))
    }

    @Test
    fun `covers are pruned with their plugin and bounded`() {
        TileArtworkCache.put("media", TileArtworkCache.Artwork("a", description, bytes))
        TileArtworkCache.put("gone", TileArtworkCache.Artwork("b", description, bytes))
        TileArtworkCache.retainOnly(setOf("media"))
        assertNull(TileArtworkCache.get("gone"))
        TileArtworkCache.remove("media")
        assertNull(TileArtworkCache.get("media"))
        (0..TileArtworkCache.MAX_ENTRIES).forEach { TileArtworkCache.put("p$it", TileArtworkCache.Artwork("k", description, bytes)) }
        assertNull(TileArtworkCache.get("p0"))
        assertTrue(TileArtworkCache.get("p${TileArtworkCache.MAX_ENTRIES}") != null)
    }
}
