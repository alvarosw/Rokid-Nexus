package com.anezium.rokidbus.hudtiles

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
class LinesTileLayoutTest {
    private val texts = listOf("one", "two", "three", "four", "five", "six", "seven")
    private val timed = TileContent.Lines(
        lines = texts.mapIndexed { i, text -> TileContent.Lines.Line(text, startMs = 80_000L + i * 6_000L) },
        current = 0,
        title = "Low Tide Static",
        subtitle = "Marta Velez",
        source = "LrcLib",
        positionMs = 102_000,
        durationMs = 236_000,
        playing = true,
    )
    private val untimed = TileContent.Lines(texts.map { TileContent.Lines.Line(it) }, current = 3, title = "Steps")

    private fun layout(size: TileSize, content: TileContent.Lines = timed, since: Long = 0) =
        TileRenderer.layout(templateInput(content, "Lyrics", since), size)

    @Test
    fun `the current line comes from the position when the lines are timed`() {
        assertEquals(listOf("four"), layout(TileSize.SMALL).text(TilePart.CURRENT_LINE))
        assertEquals(listOf("five"), layout(TileSize.SMALL, since = 2_000).text(TilePart.CURRENT_LINE))
        assertEquals(listOf("four"), layout(TileSize.SMALL, timed.copy(playing = false), since = 2_000).text(TilePart.CURRENT_LINE))
        assertEquals(listOf("four"), layout(TileSize.SMALL, untimed).text(TilePart.CURRENT_LINE))
    }

    @Test
    fun `context lines per size, around the current line`() {
        assertTrue(layout(TileSize.SMALL).text(TilePart.CONTEXT_LINE).isEmpty())
        assertEquals(listOf("five"), layout(TileSize.WIDE).text(TilePart.CONTEXT_LINE))
        assertEquals(listOf("three", "five"), layout(TileSize.TALL).text(TilePart.CONTEXT_LINE))
        assertEquals(listOf("two", "three", "five", "six"), layout(TileSize.PANEL).text(TilePart.CONTEXT_LINE))
        assertEquals(listOf("one", "two", "three", "five", "six", "seven"), layout(TileSize.JUMBO).text(TilePart.CONTEXT_LINE))
        val jumbo = layout(TileSize.JUMBO)
        val current = jumbo.texts(TilePart.CURRENT_LINE).single()
        assertEquals(TileTextStyle.HEADING, current.style)
        jumbo.texts(TilePart.CONTEXT_LINE).forEach { line ->
            assertTrue(line.bottom <= current.top || line.top >= current.bottom)
        }
        ALL_SIZES.forEach { assertInside(layout(it), "$it") }
    }

    @Test
    fun `from two rows high the current line keeps the middle, even at the first line`() {
        val first = timed.copy(positionMs = 80_000)
        listOf(TileSize.TALL, TileSize.LARGE, TileSize.PANEL, TileSize.JUMBO).forEach { size ->
            val start = layout(size, first).texts(TilePart.CURRENT_LINE).first()
            val middle = layout(size).texts(TilePart.CURRENT_LINE).first()
            assertEquals("$size", middle.top, start.top, 0f)
        }
        assertEquals(listOf("one"), layout(TileSize.TALL, first).text(TilePart.CURRENT_LINE))
    }

    @Test
    fun `header and foot meta per size`() {
        assertEquals(listOf("Low Tide Static · Marta Velez"), layout(TileSize.BANNER).text(TilePart.SUMMARY))
        assertEquals(listOf("SYNCED"), layout(TileSize.LARGE).text(TilePart.SUMMARY))
        assertEquals(listOf("SYNCED · LRCLIB"), layout(TileSize.PANEL).text(TilePart.SUMMARY))
        assertEquals(listOf("LRCLIB"), layout(TileSize.PANEL, untimed.copy(source = "LrcLib")).text(TilePart.SUMMARY))
        assertTrue(layout(TileSize.WIDE).footer.isEmpty())
        assertEquals(1, layout(TileSize.TALL).footer.filterIsInstance<TileOp.Track>().size)
        assertEquals(listOf("Low Tide Static · Marta Velez"), layout(TileSize.LARGE).text(TilePart.TRACK_META))
        assertEquals(listOf("1:42 / 3:56"), layout(TileSize.PANEL).text(TilePart.TIME))
        val jumbo = layout(TileSize.JUMBO)
        assertEquals(listOf("Low Tide Static"), jumbo.text(TilePart.TRACK_TITLE))
        assertEquals(listOf("1:42"), jumbo.text(TilePart.ELAPSED))
        assertEquals(1, jumbo.footer.filterIsInstance<TileOp.Separator>().size)
    }

    @Test
    fun `the next change is the next line start, or the next second where the foot moves`() {
        val input = templateInput(timed, sinceReceiptMs = 500)
        // Position 102.5 s: line five starts at 104 s, the second turns at 103 s.
        assertEquals(input.nowElapsed + 1_500, TileRenderer.nextChangeAtElapsed(input, TileSize.WIDE))
        assertEquals(input.nowElapsed + 500, TileRenderer.nextChangeAtElapsed(input, TileSize.PANEL))
        assertNull(TileRenderer.nextChangeAtElapsed(templateInput(timed.copy(playing = false)), TileSize.PANEL))
        assertNull(TileRenderer.nextChangeAtElapsed(templateInput(untimed), TileSize.WIDE))
    }
}
