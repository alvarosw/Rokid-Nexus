package com.anezium.rokidbus.hudtiles

import android.graphics.Bitmap
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32])
class MusicTileLayoutTest {
    private val track = TileContent.Music(
        title = "Low Tide Static", artist = "Marta Velez", album = "Salt Rooms", source = "Spotify",
        playing = true, positionMs = 102_000, durationMs = 236_000, artworkKey = "art",
    )
    private val art = Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565)

    private fun layout(size: TileSize, content: TileContent.Music = track, since: Long = 0, artwork: Bitmap? = art) =
        TileRenderer.layout(templateInput(content, "Media Deck", since).copy(artwork = artwork), size)

    @Test
    fun `each size shows the artboard's fields`() {
        val small = layout(TileSize.SMALL)
        assertEquals(listOf("Low Tide Static"), small.text(TilePart.TITLE))
        assertEquals(listOf("Marta Velez"), small.text(TilePart.ARTIST))
        assertTrue(small.text(TilePart.TIME).isEmpty() && small.text(TilePart.ELAPSED).isEmpty())
        assertTrue(small.ops.none { it is TileOp.Artwork })

        assertEquals(listOf("1:42 / 3:56"), layout(TileSize.WIDE).text(TilePart.TIME))
        val banner = layout(TileSize.BANNER)
        assertEquals(listOf("Marta Velez · Salt Rooms"), banner.text(TilePart.ARTIST))
        assertEquals(listOf("1:42 / 3:56"), banner.text(TilePart.TIME))
        assertEquals(
            banner.texts(TilePart.TITLE).single().baseline,
            banner.texts(TilePart.TIME).single().baseline,
            1f,
        )
        assertEquals(listOf("PLAYING", "· SPOTIFY"), banner.text(TilePart.SUMMARY))

        listOf(TileSize.TALL, TileSize.LARGE, TileSize.PANEL, TileSize.JUMBO).forEach { size ->
            val tile = layout(size)
            assertEquals("$size", listOf("1:42"), tile.text(TilePart.ELAPSED))
            assertEquals("$size", listOf("3:56"), tile.text(TilePart.DURATION))
        }
        assertEquals(listOf("SPOTIFY"), layout(TileSize.LARGE).text(TilePart.SOURCE))
        assertEquals(listOf("Salt Rooms"), layout(TileSize.PANEL).text(TilePart.ALBUM))
        val jumbo = layout(TileSize.JUMBO)
        assertEquals(listOf("SOURCE"), jumbo.text(TilePart.SOURCE_CAPTION))
        assertEquals(listOf("Spotify"), jumbo.text(TilePart.SOURCE))
        assertEquals(TileTextStyle.HEADING, jumbo.texts(TilePart.TITLE).first().style)
        assertEquals(TileTextStyle.FIGURE, jumbo.texts(TilePart.ELAPSED).single().style)
        ALL_SIZES.forEach { assertInside(layout(it), "$it") }
    }

    @Test
    fun `the art box takes the artboard's edge and the text sits beside it`() {
        val edges = mapOf(TileSize.WIDE to 48f, TileSize.BANNER to 48f, TileSize.TALL to 80f, TileSize.LARGE to 96f, TileSize.PANEL to 120f, TileSize.JUMBO to 200f)
        edges.forEach { (size, edge) ->
            val tile = layout(size)
            val box = tile.body.filterIsInstance<TileOp.Artwork>().single()
            assertEquals("$size", edge, box.size)
            val title = tile.texts(TilePart.TITLE).first()
            if (size == TileSize.TALL) assertTrue("$size", title.top >= box.top + box.size) else assertTrue("$size", title.left >= box.left + box.size)
        }
    }

    @Test
    fun `without artwork the box is absent and the text takes the width`() {
        TileSize.entries.forEach { size ->
            val tile = layout(size, artwork = null)
            assertTrue("$size", tile.ops.none { it is TileOp.Artwork })
            assertEquals("$size", TileRenderer.PADDING.toFloat(), tile.texts(TilePart.TITLE).first().left, 0.5f)
            assertInside(tile, "$size text-only")
        }
    }

    @Test
    fun `the header shows the play state, labelled from width 2`() {
        assertTrue(layout(TileSize.SMALL).header.filterIsInstance<TileOp.PlayState>().single().playing)
        assertTrue(layout(TileSize.SMALL).text(TilePart.SUMMARY).isEmpty())
        assertEquals(listOf("PLAYING"), layout(TileSize.LARGE).text(TilePart.SUMMARY))
        val paused = layout(TileSize.LARGE, track.copy(playing = false))
        assertEquals(listOf("PAUSED"), paused.text(TilePart.SUMMARY))
        assertTrue(!paused.header.filterIsInstance<TileOp.PlayState>().single().playing)
    }

    @Test
    fun `the position advances while playing and is clamped to the duration`() {
        val later = layout(TileSize.LARGE, since = 5_400)
        assertEquals(listOf("1:47"), later.text(TilePart.ELAPSED))
        val progress = later.footer.filterIsInstance<TileOp.Track>().single().progress
        assertEquals(107_400f / 236_000f, progress, 0.001f)
        assertEquals(listOf("1:42"), layout(TileSize.LARGE, track.copy(playing = false), since = 60_000).text(TilePart.ELAPSED))
        val ended = layout(TileSize.LARGE, since = 600_000)
        assertEquals(listOf("3:56"), ended.text(TilePart.ELAPSED))
        assertEquals(1f, ended.footer.filterIsInstance<TileOp.Track>().single().progress, 0f)
    }

    @Test
    fun `no position draws no track and no times`() {
        val tile = layout(TileSize.LARGE, track.copy(positionMs = null))
        assertTrue(tile.footer.isEmpty())
    }

    @Test
    fun `the next change is the next whole second while playing`() {
        val input = templateInput(track, sinceReceiptMs = 300)
        assertEquals(input.nowElapsed + 700, TileRenderer.nextChangeAtElapsed(input, TileSize.SMALL))
        assertNull(TileRenderer.nextChangeAtElapsed(templateInput(track.copy(playing = false)), TileSize.SMALL))
        assertNull(TileRenderer.nextChangeAtElapsed(templateInput(track, sinceReceiptMs = 600_000), TileSize.SMALL))
    }
}
