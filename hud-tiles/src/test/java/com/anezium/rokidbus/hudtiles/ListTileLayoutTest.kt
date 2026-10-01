package com.anezium.rokidbus.hudtiles

import android.graphics.drawable.ColorDrawable
import com.anezium.rokidbus.shared.tile.TileContent.ListContent
import com.anezium.rokidbus.shared.tile.TileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32])
class ListTileLayoutTest {
    private val relay = ListContent(
        sections = listOf(
            ListContent.Section(
                items = listOf(
                    ListContent.Item("Ana Ribeiro", "WhatsApp", "Leaving now, ten minutes away. Want me to grab bread on the way?", 1 * MIN, ListContent.Leading.Initials("AR")),
                    ListContent.Item("Family", "WhatsApp", "Dad: dinner moved to 8, bring the speaker", 6 * MIN, ListContent.Leading.Initials("FA")),
                    ListContent.Item("Tomás Prado", "Telegram", "Did the files come through?", 18 * MIN, ListContent.Leading.Initials("TP")),
                ),
            ),
        ),
        summary = "3 new",
        summaryShort = "3",
    )

    private val news = ListContent(
        sections = listOf(
            ListContent.Section("Civic Wire", "5", listOf(ListContent.Item("Regional rail strike called off after late-night deal", ageMs = 4 * MIN))),
            ListContent.Section("Harbor Ledger", "6", listOf(ListContent.Item("Night bus routes extended through the winter", ageMs = 12 * MIN))),
        ),
        summary = "14 unread",
        summaryShort = "14",
        overflow = 12,
    )

    private fun layout(size: TileSize, content: ListContent = relay, since: Long = 0) =
        TileRenderer.layout(templateInput(content, "Relay", since), size)

    @Test
    fun `the summary is in the header, short at width 1, never in the body`() {
        assertEquals(listOf("3"), layout(TileSize.SMALL).text(TilePart.SUMMARY))
        assertEquals(TileTextStyle.DATA, layout(TileSize.SMALL).texts(TilePart.SUMMARY).single().style)
        (ALL_SIZES - TileSize.SMALL - TileSize.TALL).forEach { size ->
            assertEquals("$size", listOf("3 NEW"), layout(size).text(TilePart.SUMMARY))
            assertTrue("$size", layout(size).body.filterIsInstance<TileOp.Text>().none { it.text.contains("3 new") })
        }
    }

    @Test
    fun `each size shows the artboard's fields`() {
        val small = layout(TileSize.SMALL)
        assertEquals(listOf("Ana Ribeiro"), small.text(TilePart.ITEM_TITLE))
        assertEquals(2, small.texts(TilePart.PARAGRAPH).size)
        assertTrue(small.text(TilePart.ITEM_META).isEmpty())
        assertEquals(listOf("1 min"), small.text(TilePart.MORE))

        assertEquals(listOf("1 min"), layout(TileSize.WIDE).text(TilePart.ITEM_META))
        assertEquals(listOf("WhatsApp · 1 min"), layout(TileSize.BANNER).text(TilePart.ITEM_META))

        val tall = layout(TileSize.TALL)
        assertEquals(listOf("1m", "6m", "18m"), tall.text(TilePart.ITEM_META))
        assertTrue(tall.ops.none { it is TileOp.InitialsBox })

        val large = layout(TileSize.LARGE)
        assertEquals(listOf("AR", "FA", "TP"), large.body.filterIsInstance<TileOp.InitialsBox>().map { it.text })
        assertEquals(listOf("1 min", "6 min", "18 min"), large.text(TilePart.ITEM_META))
        assertEquals(2, large.body.filterIsInstance<TileOp.Separator>().size)
        assertEquals(listOf("WhatsApp · 1 min", "WhatsApp · 6 min", "Telegram · 18 min"), layout(TileSize.PANEL).text(TilePart.ITEM_META))
        ALL_SIZES.forEach { size ->
            assertInside(layout(size), "relay $size")
            assertInside(layout(size, news), "news $size")
        }
    }

    @Test
    fun `a headline without paragraph carries its section's title and age on a meta line`() {
        val wide = layout(TileSize.WIDE, news)
        assertEquals(listOf("Civic Wire · 4 min"), wide.text(TilePart.ITEM_META))
        assertEquals(listOf("4m"), layout(TileSize.TALL, news).text(TilePart.ITEM_META).take(1))
        val jumbo = layout(TileSize.JUMBO, news)
        assertEquals(listOf("Civic Wire", "Harbor Ledger"), jumbo.text(TilePart.SECTION_TITLE))
        assertEquals(listOf("5", "6"), jumbo.text(TilePart.SECTION_DETAIL))
        assertEquals(listOf("4 min", "12 min"), jumbo.text(TilePart.ITEM_META))
    }

    @Test
    fun `paragraph lines are capped by the plugin and by the size`() {
        val long = "word ".repeat(50).trim()
        val content = ListContent(listOf(ListContent.Section(items = listOf(ListContent.Item("A", paragraph = long)))), paragraphLines = 6)
        assertEquals(6, layout(TileSize.TALL, content).texts(TilePart.PARAGRAPH).size)
        assertEquals(3, layout(TileSize.LARGE, content).texts(TilePart.PARAGRAPH).size)
        assertEquals(1, layout(TileSize.TALL, content.copy(paragraphLines = 1)).texts(TilePart.PARAGRAPH).size)
        assertTrue(layout(TileSize.TALL, content).texts(TilePart.PARAGRAPH).last().text.endsWith("…"))
    }

    @Test
    fun `a trailing media marker takes its own line where the size has rows`() {
        val content = ListContent(listOf(ListContent.Section(items = listOf(ListContent.Item("Inês", paragraph = "Fixed the fog light. [photo]")))))
        assertEquals(listOf("[photo]"), layout(TileSize.LARGE, content).text(TilePart.MARKER))
        assertEquals(listOf("Fixed the fog light."), layout(TileSize.LARGE, content).text(TilePart.PARAGRAPH))
        assertTrue(layout(TileSize.WIDE, content).text(TilePart.MARKER).isEmpty())
    }

    @Test
    fun `items fill while they fit wholly and the rest is counted as more`() {
        val many = ListContent(
            listOf(ListContent.Section(items = (1..6).map { ListContent.Item("Sender $it", paragraph = "A message long enough to wrap onto a second line here", ageMs = it * MIN) })),
            overflow = 4,
        )
        val tall = layout(TileSize.TALL, many)
        val shown = tall.texts(TilePart.ITEM_TITLE).size
        assertTrue(shown in 1..5)
        assertEquals(listOf("+${4 + 6 - shown} more"), tall.text(TilePart.MORE))
        // A title is never shown without at least one paragraph line under it.
        assertTrue(tall.texts(TilePart.PARAGRAPH).size >= shown)
        assertTrue(layout(TileSize.WIDE, many).text(TilePart.MORE).isEmpty())
    }

    @Test
    fun `when the first item does not fit, details then section headers drop, never the title`() {
        val crowded = ListContent(
            listOf(ListContent.Section("Outlet", "9", listOf(ListContent.Item("Headline", "Detail", "Paragraph text")))),
        )
        val input = templateInput(crowded)
        assertEquals(ListTileLayout.Drop.NOTHING to listOf("Headline"), ListTileLayout.dropFor(input, crowded, TileSize.JUMBO, 60))
        // Without its paragraph the item is a headline with a detail line; without the detail, it fits.
        assertEquals(ListTileLayout.Drop.DETAILS to listOf("Headline"), ListTileLayout.dropFor(input, crowded, TileSize.JUMBO, 45))
        assertEquals(ListTileLayout.Drop.SECTION_HEADERS to listOf("Headline"), ListTileLayout.dropFor(input, crowded, TileSize.JUMBO, 30))
        val full = layout(TileSize.JUMBO, crowded)
        assertEquals(listOf("Outlet"), full.text(TilePart.SECTION_TITLE))
        assertEquals(listOf("Paragraph text"), full.text(TilePart.PARAGRAPH))
        ALL_SIZES.forEach { size -> assertEquals("$size", listOf("Headline"), layout(size, crowded).text(TilePart.ITEM_TITLE)) }
    }

    @Test
    fun `a glyph leading comes from the plugin's glyphs, and is omitted when unknown`() {
        val content = ListContent(listOf(ListContent.Section(items = listOf(ListContent.Item("A", paragraph = "p", leading = ListContent.Leading.Glyph("bell"))))))
        val known = TileRenderer.layout(templateInput(content).copy(glyph = { if (it == "bell") ColorDrawable(0) else null }), TileSize.LARGE)
        assertEquals(1, known.body.filterIsInstance<TileOp.GlyphBox>().size)
        assertTrue(known.texts(TilePart.ITEM_TITLE).single().left > 30f)
        val unknown = layout(TileSize.LARGE, content)
        assertTrue(unknown.body.none { it is TileOp.GlyphBox })
        assertEquals(TileRenderer.PADDING.toFloat(), unknown.texts(TilePart.ITEM_TITLE).single().left, 0.5f)
    }

    @Test
    fun `ages are rendered relative and kept current`() {
        assertEquals(listOf("6 min"), layout(TileSize.WIDE, relay.copy(sections = listOf(relay.sections[0].copy(items = relay.sections[0].items.drop(1)))), since = 30_000).text(TilePart.ITEM_META))
        assertEquals("2 min", layout(TileSize.LARGE, since = 60_000).text(TilePart.ITEM_META).first())
        val input = templateInput(relay, sinceReceiptMs = 20_000)
        // Ana's 1 min turns 2 min 40 s later; nothing turns sooner.
        assertEquals(input.nowElapsed + 40_000, TileRenderer.nextChangeAtElapsed(input, TileSize.LARGE))
    }

    @Test
    fun `the jumbo foot says when the list was received`() {
        val tile = layout(TileSize.JUMBO, relay, since = 12_000)
        assertEquals(listOf("Updated 10 s ago"), tile.text(TilePart.FOOTER))
        assertEquals(listOf("Fresh"), layout(TileSize.JUMBO, relay.copy(footer = "Fresh")).text(TilePart.FOOTER))
        assertTrue(layout(TileSize.LARGE, relay).text(TilePart.FOOTER).isEmpty())
        val input = templateInput(relay, sinceReceiptMs = 12_000)
        assertEquals(input.nowElapsed + 8_000, TileRenderer.nextChangeAtElapsed(input, TileSize.JUMBO))
    }
}
