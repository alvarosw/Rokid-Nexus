package com.anezium.rokidbus.hudtiles

import android.graphics.drawable.ColorDrawable
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.WeatherContract
import com.anezium.rokidbus.shared.tile.SystemWidgets
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
class WeatherWidgetLayoutTest {
    private val reading = WeatherContract.Reading(
        location = "Lisbon",
        temperature = 19,
        unit = WeatherContract.TemperatureUnit.CELSIUS,
        code = 3,
        condition = "Overcast",
        high = 23,
        low = 17,
        hourly = listOf(
            WeatherContract.Hour("11:00", 20, 3),
            WeatherContract.Hour("12:00", 21, 0),
            WeatherContract.Hour("13:00", 22, 0),
            WeatherContract.Hour("14:00", 23, 1),
            WeatherContract.Hour("15:00", 23, 1),
            WeatherContract.Hour("16:00", 22, 0),
        ),
        daily = listOf(
            WeatherContract.Day("Fri", 27, 17, 3),
            WeatherContract.Day("Sat", 26, 20, 3),
            WeatherContract.Day("Sun", 23, 20, 96),
            WeatherContract.Day("Mon", 25, 19, 45),
            WeatherContract.Day("Tue", 22, 19, 95),
            WeatherContract.Day("Wed", 22, 18, 45),
        ),
    )

    private fun weather(reading: WeatherContract.Reading? = this.reading, ageMs: Long? = 5 * 60_000L) =
        SystemWidgetContent.Weather(reading, ageMs)

    private fun input(content: SystemWidgetContent, now: Long = 10_000L) =
        SystemWidgetInput(name = "Weather", icon = ColorDrawable(0), content = content, nowElapsed = now)

    private fun layout(size: TileSize, content: SystemWidgetContent = weather()) = SystemWidgetRenderer.layout(input(content), size)

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
    fun `every size stays inside its tile, fresh, stale and empty`() {
        TileSize.entries.forEach { size ->
            assertInside(layout(size), "fresh $size")
            assertInside(layout(size, weather(ageMs = null)), "unknown age $size")
            assertInside(layout(size, weather(ageMs = 3 * 3_600_000L)), "stale $size")
            assertInside(layout(size, weather(reading = null, ageMs = null)), "empty $size")
            assertInside(
                layout(size, weather(reading.copy(location = "Llanfairpwllgwyngyll Station", condition = "Storm with hail", temperature = -104, high = -100, low = -120))),
                "long $size",
            )
        }
    }

    @Test
    fun `the temperature is the display data value with its unit on one baseline`() {
        SystemWidgets.WEATHER.supportedSizes.forEach { size ->
            val layout = layout(size)
            assertEquals("WEATHER", layout.texts(TilePart.NAME).single().text)
            val value = layout.texts(TilePart.VALUE).single()
            val unit = layout.texts(TilePart.UNIT).single()
            assertEquals("19", value.text)
            assertEquals(TileTextStyle.DATA_DISPLAY, value.style)
            assertEquals(RokidHudTokens.TEXT_PRIMARY, value.color)
            assertEquals("°C", unit.text)
            assertEquals(value.baseline, unit.baseline, 0.01f)
            assertEquals(listOf("Overcast"), texts(layout, TilePart.TITLE))
            assertEquals(listOf("H 23°  L 17°"), texts(layout, TilePart.BADGE))
        }
        val fahrenheit = layout(TileSize.SMALL, weather(reading.copy(unit = WeatherContract.TemperatureUnit.FAHRENHEIT)))
        assertEquals("°F", fahrenheit.texts(TilePart.UNIT).single().text)
    }

    @Test
    fun `each offered size shows what fits and drops the place first on one-wide`() {
        val small = layout(TileSize.SMALL)
        assertTrue(texts(small, TilePart.SUBTITLE).isEmpty())
        assertTrue(texts(small, TilePart.ROW).isEmpty())
        // Stacked: value, condition, range top to bottom.
        val value = small.texts(TilePart.VALUE).single()
        val condition = small.texts(TilePart.TITLE).single()
        val range = small.texts(TilePart.BADGE).single()
        assertTrue(value.bottom <= condition.top && condition.bottom <= range.top)

        val wide = layout(TileSize.WIDE)
        assertEquals(listOf("Lisbon"), texts(wide, TilePart.SUBTITLE))
        assertTrue(texts(wide, TilePart.ROW).isEmpty())
        // Condition beside the temperature, the place under both.
        assertTrue(wide.texts(TilePart.TITLE).single().left > wide.texts(TilePart.UNIT).single().left)
        assertTrue(wide.texts(TilePart.SUBTITLE).single().top >= wide.texts(TilePart.BADGE).single().bottom)

        val banner = layout(TileSize.BANNER)
        assertEquals(listOf("11:00", "20°", "12:00", "21°", "13:00", "22°"), texts(banner, TilePart.ROW))
        val firstHour = banner.texts(TilePart.ROW).first()
        assertTrue(firstHour.left > banner.texts(TilePart.TITLE).single().left)
        assertEquals(banner.texts(TilePart.VALUE).single().top, firstHour.top, 12f)

        val large = layout(TileSize.LARGE)
        assertEquals(
            listOf("11:00", "20°", "12:00", "21°", "13:00", "22°", "14:00", "23°") +
                listOf("FRI", "27°", "17°", "SAT", "26°", "20°", "SUN", "23°", "20°", "MON", "25°", "19°"),
            texts(large, TilePart.ROW),
        )
        assertEquals(2, large.body.filterIsInstance<TileOp.Separator>().size)

        val panel = layout(TileSize.PANEL)
        assertEquals(12 + 18, texts(panel, TilePart.ROW).size)
        val hours = panel.texts(TilePart.ROW).filter { it.style == TileTextStyle.LABEL }.take(6)
        assertTrue("hours left to right", hours.zipWithNext().all { (a, b) -> a.left < b.left && a.top == b.top })
    }

    @Test
    fun `forecast rows that would not fit are dropped whole`() {
        val tall = layout(TileSize.TALL)
        // Two per column of width: two hours and two days on a one-wide two-row tile.
        assertEquals(listOf("11:00", "20°", "12:00", "21°", "FRI", "27°", "17°", "SAT", "26°", "20°"), texts(tall, TilePart.ROW))
        assertEquals(listOf("Lisbon"), texts(tall, TilePart.SUBTITLE))
        val noForecast = layout(TileSize.LARGE, weather(reading.copy(hourly = emptyList(), daily = emptyList())))
        assertTrue(texts(noForecast, TilePart.ROW).isEmpty())
        assertTrue(noForecast.body.none { it is TileOp.Separator })
    }

    @Test
    fun `a stale reading dims its body and puts its age in the header`() {
        val fresh = layout(TileSize.WIDE)
        assertTrue(texts(fresh, TilePart.SUMMARY).isEmpty())

        val stale = layout(TileSize.WIDE, weather(ageMs = 3 * 3_600_000L + 5_000L))
        assertEquals(listOf("3H AGO"), texts(stale, TilePart.SUMMARY))
        val dimmed = RokidHudTokens.scaleAlpha(RokidHudTokens.TEXT_PRIMARY, GenericTileLayout.STALE_ALPHA)
        assertEquals(dimmed, stale.texts(TilePart.VALUE).single().color)
        assertEquals(dimmed, stale.texts(TilePart.TITLE).single().color)

        assertEquals(listOf("3H"), texts(layout(TileSize.SMALL, weather(ageMs = 3 * 3_600_000L)), TilePart.SUMMARY))
        assertEquals(listOf("2D AGO"), texts(layout(TileSize.WIDE, weather(ageMs = 50 * 3_600_000L)), TilePart.SUMMARY))
        assertEquals(listOf("OLD AGO"), texts(layout(TileSize.WIDE, weather(ageMs = null)), TilePart.SUMMARY))
    }

    @Test
    fun `no reading yet shows the header and one waiting line`() {
        val empty = layout(TileSize.WIDE, weather(reading = null, ageMs = null))
        assertEquals("WEATHER", empty.texts(TilePart.NAME).single().text)
        assertEquals(listOf("No weather from the phone yet"), texts(empty, TilePart.SUBTITLE))
        assertTrue(empty.texts(TilePart.VALUE).isEmpty())
        assertTrue(texts(empty, TilePart.SUMMARY).isEmpty())
    }

    @Test
    fun `the widget redraws when the reading turns stale and then as its age ticks over`() {
        // 5 min old: stale in 1 h 55 min.
        assertEquals(10_000L + 115 * 60_000L, SystemWidgetRenderer.nextChangeAtElapsed(input(weather())))
        // 3 h 10 min old: the label reads 3H until 4 h.
        assertEquals(10_000L + 50 * 60_000L, SystemWidgetRenderer.nextChangeAtElapsed(input(weather(ageMs = 190 * 60_000L))))
        assertNull(SystemWidgetRenderer.nextChangeAtElapsed(input(weather(ageMs = null))))
        assertNull(SystemWidgetRenderer.nextChangeAtElapsed(input(weather(reading = null, ageMs = null))))
    }
}
