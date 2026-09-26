package com.anezium.rokidbus.shared.tile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileGridPackerTest {
    @Test
    fun `no plugins packs to nothing`() {
        assertEquals(emptyList<TilePlacement>(), TileGridPacker.pack(emptyList()))
    }

    @Test
    fun `single plugin lands at the origin`() {
        val placements = TileGridPacker.pack(listOf("a" to null))
        assertEquals(listOf(TilePlacement("a", TileSize.SMALL, col = 0, row = 0)), placements)
    }

    @Test
    fun `absent or invalid size falls back to small`() {
        val placements = TileGridPacker.pack(listOf("a" to null), columns = 2)
        assertEquals(TileSize.SMALL, placements.single().size)
    }

    @Test
    fun `fills row-major before wrapping to the next row`() {
        val placements = TileGridPacker.pack(
            listOf("a" to null, "b" to null, "c" to null),
            columns = 2,
        )
        assertEquals(
            listOf(
                TilePlacement("a", TileSize.SMALL, col = 0, row = 0),
                TilePlacement("b", TileSize.SMALL, col = 1, row = 0),
                TilePlacement("c", TileSize.SMALL, col = 0, row = 1),
            ),
            placements,
        )
    }

    @Test
    fun `is deterministic for a given input order`() {
        val entries = listOf("a" to TileSize.WIDE, "b" to TileSize.SMALL, "c" to TileSize.TALL, "d" to null)
        val first = TileGridPacker.pack(entries)
        val second = TileGridPacker.pack(entries)
        assertEquals(first, second)
    }

    @Test
    fun `mixed sizes do not overlap`() {
        val entries = listOf(
            "wide" to TileSize.WIDE,
            "small-1" to TileSize.SMALL,
            "large" to TileSize.LARGE,
            "tall" to TileSize.TALL,
            "small-2" to TileSize.SMALL,
        )
        val placements = TileGridPacker.pack(entries, columns = 4)

        fun cellsOf(p: TilePlacement): List<Pair<Int, Int>> =
            (p.row until p.row + p.size.rows).flatMap { r -> (p.col until p.col + p.size.cols).map { c -> r to c } }

        val allCells = placements.flatMap(::cellsOf)
        assertEquals(allCells.size, allCells.toSet().size)
        placements.forEach { p -> assertTrue(p.col + p.size.cols <= 4) }
    }
}
