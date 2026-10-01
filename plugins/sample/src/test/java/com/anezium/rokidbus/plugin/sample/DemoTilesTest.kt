package com.anezium.rokidbus.plugin.sample

import com.anezium.rokidbus.shared.tile.TileContent
import org.junit.Assert.assertEquals
import org.junit.Test

class DemoTilesTest {
    @Test
    fun `the demo publishes each of the four templates once`() {
        assertEquals(
            listOf(TileContent.Template.GENERIC, TileContent.Template.MUSIC, TileContent.Template.LINES, TileContent.Template.LIST),
            DemoTiles.all.map { it.content.template },
        )
        assertEquals(DemoTiles.all.size, DemoTiles.all.map { it.contentKey }.toSet().size)
    }

    @Test
    fun `the demo music tile names its cover`() {
        val music = DemoTiles.all.single { it.content is TileContent.Music }.content as TileContent.Music
        assertEquals(DemoTiles.COVER_KEY, music.artworkKey)
    }
}
