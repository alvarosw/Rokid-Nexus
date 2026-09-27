package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TileLayoutStoreTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun entries(vararg ids: String) = ids.map { GlassesHub.LauncherEntry(id = it, displayName = it) }

    @Test
    fun `no custom layout by default and entries round-trip in order`() {
        assertEquals(emptyList<TileLayoutEntry>(), TileLayoutStore.getEntries(context))

        val stored = listOf(
            TileLayoutEntry("weather", TileSize.WIDE, col = 0, row = 0),
            TileLayoutEntry("clock", TileSize.SMALL, col = 2, row = 0),
        )
        TileLayoutStore.setEntries(context, stored)
        assertEquals(stored, TileLayoutStore.getEntries(context))

        TileLayoutStore.setEntries(context, emptyList())
        assertEquals(emptyList<TileLayoutEntry>(), TileLayoutStore.getEntries(context))
    }

    @Test
    fun `an empty layout leaves the given order untouched`() {
        TileLayoutStore.setEntries(context, emptyList())
        assertEquals(entries("a", "b", "c"), TileLayoutStore.applyOrder(context, entries("a", "b", "c")))
    }

    @Test
    fun `a custom order is applied, and a newly installed plugin is appended after it`() {
        TileLayoutStore.setEntries(
            context,
            listOf(TileLayoutEntry("c", TileSize.SMALL), TileLayoutEntry("a", TileSize.SMALL)),
        )
        // "b" was installed after the layout was saved and never placed; it lands after the
        // custom-ordered tiles without disturbing their order.
        assertEquals(entries("c", "a", "b"), TileLayoutStore.applyOrder(context, entries("a", "b", "c")))
    }

    @Test
    fun `size lookup answers per plugin and is null for anything unplaced`() {
        TileLayoutStore.setEntries(context, listOf(TileLayoutEntry("weather", TileSize.WIDE)))
        assertEquals(TileSize.WIDE, TileLayoutStore.sizeFor(context, "weather"))
        assertNull(TileLayoutStore.sizeFor(context, "clock"))
    }
}
