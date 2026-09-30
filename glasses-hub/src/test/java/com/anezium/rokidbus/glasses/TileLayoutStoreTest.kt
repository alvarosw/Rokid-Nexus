package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TilePlacement
import com.anezium.rokidbus.shared.tile.TileSize
import org.junit.Assert.assertEquals
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
    fun `an empty layout auto-packs the given order at SMALL`() {
        TileLayoutStore.setEntries(context, emptyList())
        assertEquals(
            listOf(
                TilePlacement("a", TileSize.SMALL, col = 0, row = 0),
                TilePlacement("b", TileSize.SMALL, col = 1, row = 0),
                TilePlacement("c", TileSize.SMALL, col = 2, row = 0),
            ),
            TileLayoutStore.placements(context, entries("a", "b", "c")),
        )
    }

    @Test
    fun `stored positions are authoritative and holes stay`() {
        TileLayoutStore.setEntries(
            context,
            listOf(
                TileLayoutEntry("c", TileSize.PANEL, col = 1, row = 2),
                TileLayoutEntry("a", TileSize.SMALL, col = 3, row = 0),
            ),
        )
        assertEquals(
            listOf(
                TilePlacement("a", TileSize.SMALL, col = 3, row = 0),
                TilePlacement("c", TileSize.PANEL, col = 1, row = 2),
            ),
            TileLayoutStore.placements(context, entries("a", "c")).sortedBy { it.pluginId },
        )
    }

    @Test
    fun `a plugin the layout does not place fills the first free cell, stored entries for absent plugins are ignored`() {
        TileLayoutStore.setEntries(
            context,
            listOf(
                TileLayoutEntry("a", TileSize.WIDE, col = 0, row = 0),
                TileLayoutEntry("gone", TileSize.SMALL, col = 2, row = 0),
            ),
        )
        assertEquals(
            listOf(
                TilePlacement("a", TileSize.WIDE, col = 0, row = 0),
                TilePlacement("b", TileSize.SMALL, col = 2, row = 0),
            ),
            TileLayoutStore.placements(context, entries("a", "b")),
        )
    }
}
