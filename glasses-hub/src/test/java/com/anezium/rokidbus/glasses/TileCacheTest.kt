package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TileCacheTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun snapshot(pluginId: String = "transit") =
        TileSnapshot(pluginId = pluginId, contentKey = "eta", title = "12")

    @Test
    fun `set then get round trips`() {
        TileCache.clear(context)
        TileCache.put(context, snapshot(), nowElapsedRealtime = 1_000L)
        val cached = TileCache.get(context, "transit")
        assertEquals(snapshot(), cached?.snapshot)
        assertEquals(1_000L, cached?.receivedAtElapsedRealtime)
    }

    @Test
    fun `persists across a simulated process restart`() {
        TileCache.clear(context)
        TileCache.put(context, snapshot(), nowElapsedRealtime = 5_000L)
        // Simulate a restart by reading through a brand new Context reference to the same
        // SharedPreferences file, exactly what a real hub restart would do.
        val reconstructed = TileCache.get(RuntimeEnvironment.getApplication(), "transit")
        assertEquals(snapshot(), reconstructed?.snapshot)
    }

    @Test
    fun `staleness boundary is just under and just over the threshold`() {
        TileCache.clear(context)
        TileCache.put(context, snapshot(), nowElapsedRealtime = 0L)
        val cached = TileCache.get(context, "transit")!!
        val threshold = WidgetTileContract.DEFAULT_STALE_AFTER_MS
        assertTrue(!TileCache.isStale(cached, nowElapsedRealtime = threshold - 1))
        assertTrue(TileCache.isStale(cached, nowElapsedRealtime = threshold))
    }

    @Test
    fun `staleness follows the snapshot's own staleAfterMs`() {
        TileCache.clear(context)
        val shortLived = TileSnapshot(
            pluginId = "transit",
            contentKey = "eta",
            content = TileContent.Generic(title = "12"),
            staleAfterMs = 2 * 60_000L,
        )
        TileCache.put(context, shortLived, nowElapsedRealtime = 0L)
        val cached = TileCache.get(context, "transit")!!
        assertTrue(!TileCache.isStale(cached, nowElapsedRealtime = 2 * 60_000L - 1))
        assertTrue(TileCache.isStale(cached, nowElapsedRealtime = 2 * 60_000L))
    }

    @Test
    fun `an entry from a prior boot is reported exactly at its stale boundary`() {
        TileCache.clear(context)
        val longLived = TileSnapshot(
            pluginId = "transit",
            contentKey = "eta",
            content = TileContent.Generic(title = "12"),
            staleAfterMs = 60 * 60_000L,
        )
        TileCache.put(context, longLived, nowElapsedRealtime = 500_000L)
        val cached = TileCache.get(context, "transit")!!
        assertEquals(60 * 60_000L, TileCache.ageMs(cached, nowElapsedRealtime = 1_000L))
        assertTrue(TileCache.isStale(cached, nowElapsedRealtime = 1_000L))
    }

    @Test
    fun `missing entry returns null`() {
        TileCache.clear(context)
        assertNull(TileCache.get(context, "nobody"))
    }

    @Test
    fun `entries are isolated per pluginId`() {
        TileCache.clear(context)
        TileCache.put(context, snapshot("a"), 0L)
        TileCache.put(context, snapshot("b"), 0L)
        assertEquals("a", TileCache.get(context, "a")?.snapshot?.pluginId)
        assertEquals("b", TileCache.get(context, "b")?.snapshot?.pluginId)
    }

    @Test
    fun `retainOnly drops plugins that left the launcher list`() {
        TileCache.clear(context)
        TileCache.put(context, snapshot("a"), 0L)
        TileCache.put(context, snapshot("b"), 0L)
        TileCache.retainOnly(context, setOf("b", "c"))
        assertNull(TileCache.get(context, "a"))
        assertEquals("b", TileCache.get(context, "b")?.snapshot?.pluginId)
    }

    @Test
    fun `retainOnly with an empty launcher list empties the cache`() {
        TileCache.clear(context)
        TileCache.put(context, snapshot("a"), 0L)
        TileCache.retainOnly(context, emptySet())
        assertNull(TileCache.get(context, "a"))
    }
}
