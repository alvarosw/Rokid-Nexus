package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.tile.TileSize
import org.junit.Assert.assertEquals
import org.junit.Test

class TileSizeOptionsTest {
    @Test
    fun `a plugin with no declared sizes is offered the full fixed enum`() {
        assertEquals(TileSize.entries, TileSizeOptions.forPlugin(emptySet()))
    }

    @Test
    fun `a plugin's declared sizes are offered in fixed enum order, nothing else`() {
        assertEquals(
            listOf(TileSize.SMALL, TileSize.TALL),
            TileSizeOptions.forPlugin(setOf(TileSize.TALL, TileSize.SMALL)),
        )
    }

    @Test
    fun `a single declared size offers only that size`() {
        assertEquals(listOf(TileSize.WIDE), TileSizeOptions.forPlugin(setOf(TileSize.WIDE)))
    }
}
