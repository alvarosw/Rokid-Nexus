package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.tile.TileSize
import org.junit.Assert.assertEquals
import org.junit.Test

class TileSizeOptionsTest {
    @Test
    fun `a plugin with no declared sizes is offered all seven sizes in picker order`() {
        assertEquals(TileSize.PICKER_ORDER, TileSizeOptions.forPlugin(emptySet()))
    }

    @Test
    fun `a plugin's declared sizes are offered in picker order, nothing else`() {
        assertEquals(
            listOf(TileSize.SMALL, TileSize.TALL),
            TileSizeOptions.forPlugin(setOf(TileSize.TALL, TileSize.SMALL)),
        )
    }

    @Test
    fun `the new three-wide sizes follow picker order, not enum order`() {
        assertEquals(
            listOf(TileSize.WIDE, TileSize.BANNER, TileSize.PANEL),
            TileSizeOptions.forPlugin(setOf(TileSize.PANEL, TileSize.BANNER, TileSize.WIDE)),
        )
    }

    @Test
    fun `a single declared size offers only that size`() {
        assertEquals(listOf(TileSize.WIDE), TileSizeOptions.forPlugin(setOf(TileSize.WIDE)))
    }
}
