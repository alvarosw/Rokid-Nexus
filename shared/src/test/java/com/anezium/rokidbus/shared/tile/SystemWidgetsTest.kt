package com.anezium.rokidbus.shared.tile

import com.anezium.rokidbus.shared.TileLayoutContract
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemWidgetsTest {
    private fun at(id: String, size: TileSize, col: Int, row: Int) = TileLayoutEntry(id, size, col, row)

    @Test
    fun `no plugin can take a system widget id`() {
        listOf("sys:clock", "sys:status", "sys:weather", "sys:future").forEach { id ->
            assertTrue(id, SystemWidgets.isSystemId(id))
            assertFalse(id, PluginDescriptor.isValidId(id))
        }
        SystemWidgets.all.forEach { assertTrue(it.id, it.id.startsWith(SystemWidgets.ID_PREFIX)) }
        assertFalse(SystemWidgets.isSystemId("clock"))
    }

    @Test
    fun `every widget supports its default size`() {
        SystemWidgets.all.forEach { assertTrue(it.id, it.defaultSize in it.supportedSizes) }
        assertEquals(SystemWidgets.all.size, SystemWidgets.all.map { it.id }.toSet().size)
    }

    @Test
    fun `placedIn keeps known widgets in stored order once each`() {
        val stored = listOf(
            at("a", TileSize.SMALL, 0, 0),
            at("sys:status", TileSize.WIDE, 1, 0),
            at("sys:unknown", TileSize.SMALL, 3, 0),
            at("sys:clock", TileSize.SMALL, 0, 1),
            at("sys:status", TileSize.SMALL, 2, 2),
        )
        assertEquals(listOf(SystemWidgets.STATUS, SystemWidgets.CLOCK), SystemWidgets.placedIn(stored))
        assertNull(SystemWidgets.byId("sys:unknown"))
    }

    @Test
    fun `a stored widget is present at its stored cell and plugins fill around it`() {
        val stored = listOf(
            at("sys:clock", TileSize.WIDE, 0, 0),
            at("a", TileSize.SMALL, 2, 0),
        )
        val placements = TileGridLayout.resolveWithWidgets(listOf("a" to null, "b" to null), stored)
        assertEquals(
            listOf(
                TilePlacement("a", TileSize.SMALL, 2, 0),
                TilePlacement("b", TileSize.SMALL, 3, 0),
                TilePlacement("sys:clock", TileSize.WIDE, 0, 0),
            ),
            placements,
        )
    }

    @Test
    fun `a widget with no stored entry is absent and an unknown sys id is dropped`() {
        val stored = listOf(at("sys:weather-v9", TileSize.SMALL, 0, 0), at("a", TileSize.SMALL, 1, 0))
        val placements = TileGridLayout.resolveWithWidgets(listOf("a" to null), stored)
        assertEquals(listOf(TilePlacement("a", TileSize.SMALL, 1, 0)), placements)
    }

    @Test
    fun `a rejected widget cell is re-placed first-fit at its stored size`() {
        val stored = listOf(
            at("a", TileSize.LARGE, 0, 0),
            at("sys:status", TileSize.BANNER, 1, 1),
        )
        val placements = TileGridLayout.resolveWithWidgets(listOf("a" to null), stored)
        assertEquals(TilePlacement("sys:status", TileSize.BANNER, 0, 2), placements.single { it.pluginId == "sys:status" })
    }

    @Test
    fun `plain resolve still ignores widgets, as a hub that predates them does`() {
        val stored = listOf(at("sys:clock", TileSize.SMALL, 0, 0))
        assertEquals(
            listOf(TilePlacement("a", TileSize.SMALL, 0, 0)),
            TileGridLayout.resolve(listOf("a" to null), stored),
        )
    }

    @Test
    fun `widget entries survive the layout config wire`() {
        val entries = listOf(at("sys:clock", TileSize.BANNER, 0, 0), at("relay", TileSize.SMALL, 3, 0))
        assertEquals(entries, TileLayoutContract.entriesFromConfig(TileLayoutContract.configToJson(entries)))
    }
}
