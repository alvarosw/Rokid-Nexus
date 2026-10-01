package com.anezium.rokidbus.hudtiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class TileRendererTest {
    private fun generic(
        title: String = "12",
        unit: String = "min",
        subtitle: String = "",
        badge: String = "",
        progress: Float? = null,
        rows: List<String> = emptyList(),
    ) = TileContent.Generic(title = title, subtitle = subtitle, badge = badge, progress = progress, unit = unit, rows = rows)

    private fun input(content: TileContent?, name: String = "Transit") =
        TileRenderInput(name = name, icon = ColorDrawable(0), content = content)

    private fun layout(size: TileSize, content: TileContent?) = TileRenderer.layout(input(content), size)

    private fun texts(layout: TileLayout, part: TilePart) = layout.texts(part).map { it.text }

    @Test
    fun `tiles take their real glasses size`() {
        assertEquals(106, TileRenderer.widthOf(TileSize.SMALL))
        assertEquals(220, TileRenderer.widthOf(TileSize.LARGE))
        assertEquals(334, TileRenderer.widthOf(TileSize.JUMBO))
        assertEquals(106, TileRenderer.heightOf(TileSize.BANNER))
        assertEquals(220, TileRenderer.heightOf(TileSize.PANEL))
        val layout = layout(TileSize.PANEL, generic())
        assertEquals(334, layout.width)
        assertEquals(220, layout.height)
    }

    @Test
    fun `the header is the icon and the uppercase name, top-left, at every size`() {
        TileSize.entries.forEach { size ->
            val layout = layout(size, null)
            val icon = layout.header.filterIsInstance<TileOp.Icon>().single()
            assertEquals("$size", 8f, icon.left)
            assertEquals("$size", 8f, icon.top)
            assertEquals("$size", 16f, icon.size)
            val name = layout.texts(TilePart.NAME).single()
            assertEquals("TRANSIT", name.text)
            assertEquals(TileTextStyle.LABEL, name.style)
            assertEquals(28f, name.left, 0.01f)
            assertTrue(name.top >= 8f && name.bottom <= 24f)
        }
    }

    @Test
    fun `no content draws the header only`() {
        val layout = layout(TileSize.JUMBO, null)
        assertTrue(layout.body.isEmpty())
        assertTrue(layout.footer.isEmpty())
    }

    @Test
    fun `a long name ellipsizes and gives way to the host's alert mark`() {
        val long = "Transit planner with a long name"
        val plain = TileRenderer.layout(input(null, long), TileSize.SMALL).texts(TilePart.NAME).single().text
        assertTrue(plain, plain.endsWith("…"))
        val inset = TileRenderer.layout(input(null, long).copy(headerEndInset = 20), TileSize.SMALL)
            .texts(TilePart.NAME).single().text
        assertTrue("$inset vs $plain", inset.length < plain.length)
    }

    @Test
    fun `a numeric title is a data value with its unit on one baseline`() {
        val layout = layout(TileSize.SMALL, generic(title = "12", unit = "min"))
        val value = layout.texts(TilePart.VALUE).single()
        val unit = layout.texts(TilePart.UNIT).single()
        assertEquals("12", value.text)
        assertEquals(TileTextStyle.DATA, value.style)
        assertEquals("min", unit.text)
        assertEquals(TileTextStyle.MONO, unit.style)
        assertEquals(value.baseline, unit.baseline, 0f)
        assertTrue(unit.left > value.left + 4f)
        assertTrue(texts(layout, TilePart.TITLE).isEmpty())
    }

    @Test
    fun `a numeric title without a unit shows no unit, a text title never shows one`() {
        assertTrue(layout(TileSize.SMALL, generic(title = "7", unit = "")).texts(TilePart.UNIT).isEmpty())
        val text = layout(TileSize.SMALL, generic(title = "On time", unit = "min"))
        assertEquals(listOf("On time"), texts(text, TilePart.TITLE))
        assertEquals(TileTextStyle.BODY, text.texts(TilePart.TITLE).single().style)
        assertTrue(text.texts(TilePart.UNIT).isEmpty())
        assertTrue(text.texts(TilePart.VALUE).isEmpty())
    }

    @Test
    fun `a text title takes one line at height 1 and two from height 2`() {
        val long = generic(title = "Downtown express via the old harbour bridge and the market", unit = "")
        assertEquals(1, layout(TileSize.WIDE, long).texts(TilePart.TITLE).size)
        val two = layout(TileSize.TALL, long).texts(TilePart.TITLE)
        assertEquals(2, two.size)
        assertTrue(two.last().text.endsWith("…"))
        assertTrue(two[1].top >= two[0].bottom)
    }

    @Test
    fun `the subtitle shows on every size but 1x1, and only when present`() {
        assertTrue(layout(TileSize.SMALL, generic(subtitle = "Line 4")).texts(TilePart.SUBTITLE).isEmpty())
        (TileSize.entries - TileSize.SMALL).forEach { size ->
            assertEquals("$size", listOf("Line 4"), texts(layout(size, generic(subtitle = "Line 4")), TilePart.SUBTITLE))
            assertTrue("$size", layout(size, generic()).texts(TilePart.SUBTITLE).isEmpty())
        }
    }

    @Test
    fun `the badge shows at every size when present`() {
        TileSize.entries.forEach { size ->
            assertEquals("$size", listOf("NEW"), texts(layout(size, generic(badge = "NEW")), TilePart.BADGE))
            assertTrue("$size", layout(size, generic()).texts(TilePart.BADGE).isEmpty())
        }
    }

    @Test
    fun `rows follow the tile height`() {
        val rows = listOf("r1", "r2", "r3", "r4")
        listOf(TileSize.SMALL, TileSize.WIDE, TileSize.BANNER).forEach { size ->
            assertEquals("$size", emptyList<String>(), texts(layout(size, generic(rows = rows)), TilePart.ROW))
        }
        listOf(TileSize.TALL, TileSize.LARGE, TileSize.PANEL).forEach { size ->
            assertEquals("$size", listOf("r1", "r2", "r3"), texts(layout(size, generic(rows = rows)), TilePart.ROW))
        }
        assertEquals(rows, texts(layout(TileSize.JUMBO, generic(rows = rows)), TilePart.ROW))
    }

    @Test
    fun `content stacks top-down below the header`() {
        val layout = layout(TileSize.JUMBO, generic(title = "Hello", subtitle = "Sub", badge = "NEW", rows = listOf("a", "b")))
        val order = listOf(TilePart.NAME, TilePart.TITLE, TilePart.SUBTITLE, TilePart.BADGE, TilePart.ROW)
            .map { part -> layout.texts(part).first() }
        order.zipWithNext().forEach { (above, below) -> assertTrue("$above above $below", above.bottom <= below.top) }
        assertEquals(layout.texts(TilePart.NAME).single().bottom.coerceAtLeast(24f), layout.bodyClip.top.toFloat())
    }

    @Test
    fun `the progress track sits at the foot and the content box ends above it`() {
        TileSize.entries.forEach { size ->
            assertTrue("$size", layout(size, generic()).footer.isEmpty())
            val layout = layout(size, generic(progress = 0.4f))
            val track = layout.footer.filterIsInstance<TileOp.Track>().single()
            assertEquals(0.4f, track.progress, 0f)
            assertEquals(layout.height - 8f, track.bottom)
            assertEquals(3f, track.bottom - track.top)
            assertEquals(8f, track.left)
            assertEquals(layout.width - 8f, track.right)
            assertEquals(track.top.toInt() - 4, layout.bodyClip.bottom)
        }
    }

    @Test
    fun `a one by one tile fits its content inside the 106 px square`() {
        val layout = TileRenderer.layout(
            input(generic(title = "A rather long title that must not wrap", unit = "", badge = "NEW", subtitle = "Sub", progress = 0.5f), "Transit planner with a long name"),
            TileSize.SMALL,
        )
        val texts = layout.body.filterIsInstance<TileOp.Text>()
        assertTrue(texts.isNotEmpty())
        texts.forEach { assertTrue("$it inside the content box ${layout.bodyClip}", it.bottom <= layout.bodyClip.bottom) }
        assertTrue(layout.footer.filterIsInstance<TileOp.Track>().single().bottom <= 106 - 8)
    }

    @Test
    fun `a stale tile dims its content and track, never its header`() {
        val content = generic(title = "Hello", progress = 0.5f)
        val fresh = TileRenderer.layout(input(content), TileSize.LARGE)
        val stale = TileRenderer.layout(input(content).copy(stale = true), TileSize.LARGE)
        assertEquals(fresh.texts(TilePart.NAME).single().color, stale.texts(TilePart.NAME).single().color)
        val alpha = { color: Int -> color ushr 24 }
        assertTrue(alpha(stale.texts(TilePart.TITLE).single().color) < alpha(fresh.texts(TilePart.TITLE).single().color))
        val track = { layout: TileLayout -> layout.footer.filterIsInstance<TileOp.Track>().single() }
        assertTrue(alpha(track(stale).fillColor) < alpha(track(fresh).fillColor))
    }

    @Test
    fun `focus brightens the header and the title to focus, not the subtitle`() {
        val content = generic(title = "Hello", subtitle = "Sub")
        val focused = TileRenderer.layout(input(content).copy(focusAmount = 1f), TileSize.LARGE)
        assertEquals(RokidHudTokens.FOCUS, focused.texts(TilePart.NAME).single().color)
        assertEquals(RokidHudTokens.FOCUS, focused.texts(TilePart.TITLE).single().color)
        assertEquals(RokidHudTokens.FOCUS, focused.header.filterIsInstance<TileOp.Icon>().single().color)
        assertEquals(RokidHudTokens.TEXT_SECONDARY, focused.texts(TilePart.SUBTITLE).single().color)
        val rest = TileRenderer.layout(input(content), TileSize.LARGE)
        assertEquals(RokidHudTokens.TEXT_SECONDARY, rest.texts(TilePart.NAME).single().color)
        assertEquals(RokidHudTokens.TEXT_PRIMARY, rest.texts(TilePart.TITLE).single().color)
    }

    @Test
    fun `nothing in a static tile changes on its own`() {
        TileSize.entries.forEach { size ->
            assertNull(TileRenderer.nextChangeAtElapsed(input(null), size))
            assertNull(TileRenderer.nextChangeAtElapsed(input(generic()), size))
            assertNull(TileRenderer.nextChangeAtElapsed(input(TileContent.Music(title = "x", playing = false, positionMs = 0, durationMs = 1_000)), size))
        }
    }

    @Test
    fun `the header summary shows at the right end, short at width 1`() {
        val wide = TileHeader.layout(input(null, "Relay"), 220, cols = 2, summary = "3 new", summaryShort = "3")
        val summary = wide.ops.filterIsInstance<TileOp.Text>().single { it.part == TilePart.SUMMARY }
        assertEquals("3 NEW", summary.text)
        assertEquals(TileTextStyle.LABEL, summary.style)
        assertTrue(summary.left > 110f)
        val small = TileHeader.layout(input(null, "Relay"), 106, cols = 1, summary = "3 new", summaryShort = "3")
        val short = small.ops.filterIsInstance<TileOp.Text>().single { it.part == TilePart.SUMMARY }
        assertEquals("3", short.text)
        assertEquals(TileTextStyle.DATA, short.style)
        val name = small.ops.filterIsInstance<TileOp.Text>().single { it.part == TilePart.NAME }
        assertTrue(name.left < short.left)
    }

    @Test
    fun `a layout draws onto a canvas`() {
        val layout = layout(TileSize.LARGE, generic(title = "12", subtitle = "Downtown", progress = 0.5f, rows = listOf("a")))
        val bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(bitmap))
        var lit = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (bitmap.getPixel(x, y) ushr 24 != 0) lit++
        assertTrue(lit > 0)
        assertNotNull(layout.footer.single())
    }
}
