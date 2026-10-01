package com.anezium.rokidbus.hudtiles

import android.graphics.drawable.ColorDrawable
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.tile.TileSize
import java.util.Locale
import java.util.TimeZone
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
class SystemWidgetRendererTest {
    // Thursday 2026-10-01 14:32:10 UTC.
    private val epoch = 1_790_865_130_000L

    private fun clock(use24Hour: Boolean = true) =
        SystemWidgetContent.Clock(epoch, TimeZone.getTimeZone("UTC"), Locale.US, use24Hour)

    private fun status(
        glasses: SystemWidgetContent.Battery? = SystemWidgetContent.Battery(82, charging = true),
        phone: SystemWidgetContent.Battery? = SystemWidgetContent.Battery(64, charging = false),
        linked: Boolean = true,
    ) = SystemWidgetContent.Status(glasses, phone, linked)

    private fun input(content: SystemWidgetContent, name: String = "Clock", now: Long = 5_000L) =
        SystemWidgetInput(name = name, icon = ColorDrawable(0), content = content, nowElapsed = now)

    private fun layout(content: SystemWidgetContent, size: TileSize) = SystemWidgetRenderer.layout(input(content), size)

    private fun texts(layout: TileLayout, part: TilePart) = layout.texts(part).map { it.text }

    private fun assertInside(layout: TileLayout, label: String) {
        val bottom = layout.height - TileRenderer.PADDING
        layout.ops.filterIsInstance<TileOp.Text>().forEach { text ->
            assertTrue("$label '${text.text}' bottom ${text.bottom} > $bottom", text.bottom <= bottom)
            assertTrue("$label '${text.text}' left ${text.left}", text.left >= TileRenderer.PADDING)
            val right = text.left + TileText.desiredWidth(text.text, text.style)
            assertTrue("$label '${text.text}' right $right", right <= layout.width - TileRenderer.PADDING + 0.5f)
        }
    }

    @Test
    fun `the clock carries the plugin tile header and takes its real size`() {
        TileSize.entries.forEach { size ->
            val layout = layout(clock(), size)
            assertEquals(TileRenderer.widthOf(size), layout.width)
            assertEquals(TileRenderer.heightOf(size), layout.height)
            assertEquals("CLOCK", layout.texts(TilePart.NAME).single().text)
            assertEquals(8f, layout.header.filterIsInstance<TileOp.Icon>().single().left)
        }
    }

    @Test
    fun `the clock shows the 24-hour time in the display data face`() {
        val layout = layout(clock(), TileSize.SMALL)
        val time = layout.texts(TilePart.VALUE).single()
        assertEquals("14:32", time.text)
        assertEquals(TileTextStyle.DATA_DISPLAY, time.style)
        assertEquals(RokidHudTokens.TEXT_PRIMARY, time.color)
        assertTrue(texts(layout, TilePart.UNIT).isEmpty())
    }

    @Test
    fun `a 12-hour clock adds the marker on the time's baseline`() {
        val layout = layout(clock(use24Hour = false), TileSize.SMALL)
        val time = layout.texts(TilePart.VALUE).single()
        val marker = layout.texts(TilePart.UNIT).single()
        assertEquals("2:32", time.text)
        assertEquals("PM", marker.text)
        assertEquals(time.baseline, marker.baseline, 0.01f)
        assertEquals(RokidHudTokens.TEXT_SECONDARY, marker.color)
    }

    @Test
    fun `the date grows with the tile and a two-row tile gives the weekday its own line`() {
        val small = texts(layout(clock(), TileSize.SMALL), TilePart.SUBTITLE).single()
        assertTrue(small, small.contains("Thu") && small.contains("1") && !small.contains("Oct"))
        assertEquals("Thu, Oct 1", texts(layout(clock(), TileSize.WIDE), TilePart.SUBTITLE).single())
        assertEquals("Thursday, October 1", texts(layout(clock(), TileSize.BANNER), TilePart.SUBTITLE).single())
        val large = layout(clock(), TileSize.LARGE)
        assertEquals("Thursday", texts(large, TilePart.TITLE).single())
        assertEquals("October 1, 2026", texts(large, TilePart.SUBTITLE).single())
        val weekday = large.texts(TilePart.TITLE).single()
        val date = large.texts(TilePart.SUBTITLE).single()
        assertTrue(weekday.bottom <= date.top)
    }

    @Test
    fun `every clock and status layout stays inside its tile`() {
        TileSize.entries.forEach { size ->
            assertInside(layout(clock(), size), "clock $size")
            assertInside(layout(clock(use24Hour = false), size), "clock 12h $size")
            assertInside(layout(status(), size), "status $size")
            assertInside(layout(status(glasses = null, phone = null, linked = false), size), "status unknown $size")
        }
    }

    @Test
    fun `the clock redraws at the next minute and the status never on its own`() {
        // 14:32:10 -> 14:33:00 is 50 s after the sample.
        assertEquals(5_000L + 50_000L, SystemWidgetRenderer.nextChangeAtElapsed(input(clock())))
        val halfHourZone = SystemWidgetContent.Clock(epoch, TimeZone.getTimeZone("Asia/Kolkata"), Locale.US, true)
        assertEquals(5_000L + 50_000L, SystemWidgetRenderer.nextChangeAtElapsed(input(halfHourZone)))
        assertNull(SystemWidgetRenderer.nextChangeAtElapsed(input(status(), name = "Status")))
    }

    @Test
    fun `status is one row per reading up to two columns, the value right-aligned`() {
        listOf(TileSize.SMALL, TileSize.WIDE).forEach { size ->
            val layout = SystemWidgetRenderer.layout(input(status(), name = "Status"), size)
            assertEquals("STATUS", layout.texts(TilePart.NAME).single().text)
            assertEquals(listOf("GLASSES", "PHONE", "LINK"), texts(layout, TilePart.SUBTITLE))
            assertEquals(listOf("82%+", "64%", "UP"), texts(layout, TilePart.VALUE))
            val labels = layout.texts(TilePart.SUBTITLE)
            val values = layout.texts(TilePart.VALUE)
            labels.zip(values).forEach { (label, value) ->
                assertEquals(label.baseline, value.baseline, 0.01f)
                assertEquals(TileTextStyle.LABEL, label.style)
                assertEquals(RokidHudTokens.TEXT_SECONDARY, label.color)
                assertEquals(TileTextStyle.DATA, value.style)
                assertEquals(RokidHudTokens.TEXT_PRIMARY, value.color)
                val right = value.left + TileText.desiredWidth(value.text, value.style)
                assertEquals((layout.width - TileRenderer.PADDING).toFloat(), right, 1f)
            }
            assertTrue(labels.zipWithNext().all { (a, b) -> a.bottom <= b.top })
        }
    }

    @Test
    fun `a three-wide status puts the readings side by side`() {
        val layout = SystemWidgetRenderer.layout(input(status(), name = "Status"), TileSize.BANNER)
        val labels = layout.texts(TilePart.SUBTITLE)
        val values = layout.texts(TilePart.VALUE)
        assertEquals(listOf("GLASSES", "PHONE", "LINK"), labels.map { it.text })
        assertEquals(1, labels.map { it.top }.toSet().size)
        assertTrue(labels.zipWithNext().all { (a, b) -> a.left < b.left })
        labels.zip(values).forEach { (label, value) ->
            assertEquals(label.left, value.left, 0.01f)
            assertTrue(label.bottom <= value.top)
        }
    }

    @Test
    fun `unknown charge and a lost link read as such`() {
        val layout = layout(status(glasses = null, phone = null, linked = false), TileSize.SMALL)
        assertEquals(listOf("--", "--", "DOWN"), texts(layout, TilePart.VALUE))
    }
}
