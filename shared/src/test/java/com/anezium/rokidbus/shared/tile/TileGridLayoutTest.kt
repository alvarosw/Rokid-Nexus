package com.anezium.rokidbus.shared.tile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TileGridLayoutTest {
    private fun r(col: Int, row: Int, cols: Int = 1, rows: Int = 1) = GridRect(col, row, cols, rows)

    private fun assertNoOverlap(layout: Map<String, GridRect>) {
        val list = layout.values.toList()
        for (i in list.indices) for (j in i + 1 until list.size) {
            assertFalse("${list[i]} overlaps ${list[j]}", list[i].overlaps(list[j]))
        }
        list.forEach { assertTrue(TileGridLayout.fits(it, emptyList())) }
    }

    @Test
    fun `placing on an empty cell moves only that tile`() {
        val layout = mapOf("a" to r(0, 0), "b" to r(1, 0))
        val out = TileGridLayout.placeAt(layout, "a", r(3, 2))!!
        assertEquals(r(3, 2), out["a"])
        assertEquals(r(1, 0), out["b"])
    }

    @Test
    fun `a covered tile moves to the nearest free spot`() {
        val layout = mapOf("a" to r(0, 0), "b" to r(1, 0))
        val out = TileGridLayout.placeAt(layout, "a", r(1, 0))!!
        assertEquals(r(1, 0), out["a"])
        // Candidates at distance 1: (0,0) costs 1; (2,0) costs 1; (0,0) wins the tie by scan order.
        assertEquals(r(0, 0), out["b"])
        assertNoOverlap(out)
    }

    @Test
    fun `vertical distance costs one and a half columns`() {
        // b sits at (1,1); a lands on it. (1,0) is 1.5, (0,1) and (2,1) are 1.0.
        val layout = mapOf("a" to r(3, 3), "b" to r(1, 1))
        val out = TileGridLayout.placeAt(layout, "a", r(1, 1))!!
        assertEquals(r(0, 1), out["b"])
    }

    @Test
    fun `displaced tiles are handled in reading order of their old position`() {
        // a (2x1) drops onto b (1,0) and c (2,0); b is handled first, then c takes what is left.
        val layout = mapOf("a" to r(0, 2, 2, 1), "b" to r(1, 0), "c" to r(2, 0), "d" to r(0, 0))
        val out = TileGridLayout.placeAt(layout, "a", r(1, 0, 2, 1))!!
        assertEquals(r(1, 0, 2, 1), out["a"])
        assertNoOverlap(out)
        // d at (0,0) is untouched; b goes under a (1.5), c slides right (1.0).
        assertEquals(r(0, 0), out["d"])
        assertEquals(r(1, 1), out["b"])
        assertEquals(r(3, 0), out["c"])
    }

    @Test
    fun `out of bounds targets are rejected`() {
        val layout = mapOf("a" to r(0, 0))
        assertNull(TileGridLayout.placeAt(layout, "a", r(-1, 0)))
        assertNull(TileGridLayout.placeAt(layout, "a", r(0, -1)))
        assertNull(TileGridLayout.placeAt(layout, "a", r(4, 0)))
        assertNull(TileGridLayout.placeAt(layout, "a", r(3, 0, 2, 1)))
        assertNull(TileGridLayout.placeAt(layout, "a", r(0, TileGridLayout.MAX_ROWS)))
        assertNotNull(TileGridLayout.placeAt(layout, "a", r(3, TileGridLayout.MAX_ROWS - 1)))
    }

    @Test
    fun `no room for a displaced tile gives null`() {
        // Fill the whole 4x8 grid with 1x1 tiles, then drop a 2x2 over four of them: no room left.
        val layout = LinkedHashMap<String, GridRect>()
        for (row in 0 until TileGridLayout.MAX_ROWS) for (col in 0 until TileGridLayout.COLUMNS) {
            layout["t$row$col"] = r(col, row)
        }
        layout["t00"] = r(0, 0, 2, 1)
        layout.remove("t01")
        val wide = layout.getValue("t00")
        assertEquals(r(0, 0, 2, 1), wide)
        // Grow t00 to 2x2: covers (0,1) and (1,1), which have nowhere to go.
        assertNull(TileGridLayout.resize(layout, "t00", TileSize.LARGE))
    }

    @Test
    fun `packAll closes holes in reading order`() {
        val layout = mapOf("a" to r(3, 0), "b" to r(0, 2, 2, 1), "c" to r(1, 5))
        val out = TileGridLayout.packAll(layout)
        assertEquals(r(0, 0), out["a"])
        assertEquals(r(1, 0, 2, 1), out["b"])
        assertEquals(r(3, 0), out["c"])
        assertNoOverlap(out)
    }

    @Test
    fun `packAll returns its input when a tile cannot fit`() {
        // Seven 3x3 tiles need 21 rows.
        val layout = (0 until 4).associate { "t$it" to r(0, it, 3, 3) }
        assertSame(layout, TileGridLayout.packAll(layout))
    }

    @Test
    fun `resize grows in place and displaces the neighbour`() {
        val layout = mapOf("a" to r(0, 0), "b" to r(1, 0))
        val out = TileGridLayout.resize(layout, "a", TileSize.WIDE)!!
        assertEquals(r(0, 0, 2, 1), out["a"])
        assertEquals(r(2, 0), out["b"])
        assertNoOverlap(out)
    }

    @Test
    fun `resize clamps the tile back inside the columns and rows`() {
        val layout = mapOf("a" to r(3, 7))
        val out = TileGridLayout.resize(layout, "a", TileSize.JUMBO)!!
        assertEquals(r(1, 5, 3, 3), out["a"])
    }

    @Test
    fun `resize of an unknown id is null`() {
        assertNull(TileGridLayout.resize(mapOf("a" to r(0, 0)), "x", TileSize.WIDE))
    }

    @Test
    fun `resolve with an empty store equals the packer`() {
        val entries = listOf(
            "camera" to null,
            "a" to TileSize.WIDE,
            "b" to TileSize.SMALL,
            "c" to TileSize.JUMBO,
            "d" to TileSize.TALL,
            "e" to TileSize.PANEL,
            "f" to TileSize.BANNER,
            "g" to TileSize.LARGE,
        )
        assertEquals(TileGridPacker.pack(entries), TileGridLayout.resolve(entries, emptyList()))
    }

    @Test
    fun `resolve keeps holes from the stored layout`() {
        val stored = listOf(
            TileLayoutEntry("a", TileSize.SMALL, col = 3, row = 0),
            TileLayoutEntry("b", TileSize.WIDE, col = 0, row = 4),
        )
        val out = TileGridLayout.resolve(listOf("a" to null, "b" to null), stored)
        assertEquals(
            listOf(
                TilePlacement("a", TileSize.SMALL, 3, 0),
                TilePlacement("b", TileSize.WIDE, 0, 4),
            ),
            out,
        )
    }

    @Test
    fun `resolve pins rows beyond the editor bound`() {
        val stored = listOf(TileLayoutEntry("a", TileSize.SMALL, col = 0, row = 20))
        assertEquals(TilePlacement("a", TileSize.SMALL, 0, 20), TileGridLayout.resolve(listOf("a" to null), stored).single())
    }

    @Test
    fun `resolve rejects overlapping and out-of-column stored entries and re-places them`() {
        val stored = listOf(
            TileLayoutEntry("a", TileSize.LARGE, col = 0, row = 0),
            TileLayoutEntry("b", TileSize.SMALL, col = 1, row = 1),
            TileLayoutEntry("c", TileSize.BANNER, col = 2, row = 3),
            TileLayoutEntry("d", TileSize.SMALL, col = -1, row = 0),
            TileLayoutEntry("e", TileSize.SMALL, col = 0, row = -1),
        )
        val out = TileGridLayout.resolve(listOf("a", "b", "c", "d", "e").map { it to null }, stored)
            .associateBy { it.pluginId }
        assertEquals(TilePlacement("a", TileSize.LARGE, 0, 0), out["a"])
        // b overlapped a: first free cell, keeping its stored size.
        assertEquals(TilePlacement("b", TileSize.SMALL, 2, 0), out["b"])
        // c spills out of the columns: a 3x1 fits at row 1 only from col 2? (2..4) no, so row 2.
        assertEquals(TilePlacement("c", TileSize.BANNER, 0, 2), out["c"])
        assertEquals(TilePlacement("d", TileSize.SMALL, 3, 0), out["d"])
        assertEquals(TilePlacement("e", TileSize.SMALL, 2, 1), out["e"])
        val rects = out.values.map { GridRect(it.col, it.row, it.size.cols, it.size.rows) }
        for (i in rects.indices) for (j in i + 1 until rects.size) assertFalse(rects[i].overlaps(rects[j]))
    }

    @Test
    fun `resolve ignores stored ids that are not installed`() {
        val stored = listOf(
            TileLayoutEntry("gone", TileSize.LARGE, col = 0, row = 0),
            TileLayoutEntry("a", TileSize.SMALL, col = 1, row = 1),
        )
        val out = TileGridLayout.resolve(listOf("a" to null), stored)
        assertEquals(listOf(TilePlacement("a", TileSize.SMALL, 1, 1)), out)
    }

    @Test
    fun `resolve fills new ids first-fit in entries order around pinned tiles`() {
        val stored = listOf(TileLayoutEntry("a", TileSize.WIDE, col = 0, row = 0))
        val entries = listOf("camera" to null, "a" to null, "n1" to TileSize.WIDE, "n2" to null)
        val out = TileGridLayout.resolve(entries, stored).associateBy { it.pluginId }
        assertEquals(TilePlacement("camera", TileSize.SMALL, 2, 0), out["camera"])
        assertEquals(TilePlacement("n1", TileSize.WIDE, 0, 1), out["n1"])
        assertEquals(TilePlacement("n2", TileSize.SMALL, 3, 0), out["n2"])
        assertEquals(listOf("camera", "a", "n1", "n2"), TileGridLayout.resolve(entries, stored).map { it.pluginId })
    }

    @Test
    fun `resolve takes the declared size only when nothing is stored`() {
        val stored = listOf(TileLayoutEntry("a", TileSize.TALL, col = 2, row = 0))
        val out = TileGridLayout.resolve(listOf("a" to TileSize.WIDE, "b" to TileSize.WIDE), stored)
        assertEquals(TileSize.TALL, out[0].size)
        assertEquals(TileSize.WIDE, out[1].size)
    }

    @Test
    fun `readingOrder sorts by row then column`() {
        val placements = listOf(
            TilePlacement("c", TileSize.SMALL, 0, 1),
            TilePlacement("b", TileSize.SMALL, 3, 0),
            TilePlacement("a", TileSize.SMALL, 1, 0),
        )
        assertEquals(listOf("a", "b", "c"), TileGridLayout.readingOrder(placements).map { it.pluginId })
    }
}
