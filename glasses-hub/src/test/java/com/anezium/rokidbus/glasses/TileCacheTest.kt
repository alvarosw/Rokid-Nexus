package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.tile.TileSnapshot
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
        assertTrue(!TileCache.isStale(cached, nowElapsedRealtime = TileCache.STALENESS_THRESHOLD_MS - 1))
        assertTrue(TileCache.isStale(cached, nowElapsedRealtime = TileCache.STALENESS_THRESHOLD_MS))
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
}
