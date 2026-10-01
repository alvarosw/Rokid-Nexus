package com.anezium.rokidbus.shared.tile

import com.anezium.rokidbus.shared.tile.TileContentRules.TitleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileContentRulesTest {
    private fun snapshot(
        title: String = "12",
        unit: String = "min",
        subtitle: String = "",
        badge: String = "",
        rows: List<String> = emptyList(),
        progress: Float? = null,
    ) = TileContent.Generic(
        title = title, unit = unit, subtitle = subtitle,
        badge = badge, rows = rows, progress = progress,
    )

    private val fourRows = listOf("r1", "r2", "r3", "r4")

    @Test
    fun the_header_is_one_line_at_every_size() {
        TileSize.entries.forEach { assertEquals(1, TileContentRules.contentFor(it, null).nameLines) }
    }

    @Test
    fun without_a_snapshot_only_the_header_remains() {
        val content = TileContentRules.contentFor(TileSize.JUMBO, null)
        assertEquals(TitleStyle.NONE, content.titleStyle)
        assertFalse(content.subtitleVisible || content.badgeVisible || content.progressVisible)
        assertEquals(0, content.rowCount)
    }

    @Test
    fun a_numeric_title_is_a_data_value_with_its_unit_and_anything_else_is_text() {
        val numeric = TileContentRules.contentFor(TileSize.SMALL, snapshot(title = "12.5", unit = "min"))
        assertEquals(TitleStyle.DATA_VALUE, numeric.titleStyle)
        assertTrue(numeric.showUnit)
        assertFalse(TileContentRules.contentFor(TileSize.SMALL, snapshot(title = "7", unit = "")).showUnit)

        val text = TileContentRules.contentFor(TileSize.SMALL, snapshot(title = "On time", unit = "min"))
        assertEquals(TitleStyle.TEXT, text.titleStyle)
        assertFalse("the unit belongs to a numeric value only", text.showUnit)
        assertEquals(TitleStyle.NONE, TileContentRules.contentFor(TileSize.SMALL, snapshot(title = "")).titleStyle)
    }

    @Test
    fun title_and_subtitle_take_one_line_at_height_one_and_two_from_height_two() {
        TileSize.entries.forEach { size ->
            val content = TileContentRules.contentFor(size, snapshot(title = "T", subtitle = "S"))
            val expected = if (size.rows == 1) 1 else 2
            assertEquals("$size", expected, content.titleMaxLines)
            assertEquals("$size", expected, content.subtitleMaxLines)
        }
    }

    @Test
    fun the_subtitle_shows_when_the_tile_is_wider_or_taller_than_one_by_one() {
        TileSize.entries.forEach { size ->
            val content = TileContentRules.contentFor(size, snapshot(subtitle = "Line 4"))
            assertEquals("$size", size != TileSize.SMALL, content.subtitleVisible)
            assertFalse("$size", TileContentRules.contentFor(size, snapshot(subtitle = "")).subtitleVisible)
        }
    }

    @Test
    fun the_badge_shows_at_every_size_when_present() {
        TileSize.entries.forEach { size ->
            assertTrue("$size", TileContentRules.contentFor(size, snapshot(badge = "NEW")).badgeVisible)
            assertFalse("$size", TileContentRules.contentFor(size, snapshot()).badgeVisible)
        }
    }

    @Test
    fun rows_follow_the_height() {
        val rowsAt = { size: TileSize -> TileContentRules.contentFor(size, snapshot(rows = fourRows)).rowCount }
        listOf(TileSize.SMALL, TileSize.WIDE, TileSize.BANNER).forEach { assertEquals("$it", 0, rowsAt(it)) }
        listOf(TileSize.TALL, TileSize.LARGE, TileSize.PANEL).forEach { assertEquals("$it", 3, rowsAt(it)) }
        assertEquals(WidgetTileContract.MAX_ROWS, rowsAt(TileSize.JUMBO))
        assertEquals(2, TileContentRules.contentFor(TileSize.PANEL, snapshot(rows = fourRows.take(2))).rowCount)
    }

    @Test
    fun progress_shows_only_when_present_and_is_clamped() {
        assertNull(TileContentRules.contentFor(TileSize.SMALL, snapshot()).progress)
        assertEquals(0.4f, TileContentRules.contentFor(TileSize.SMALL, snapshot(progress = 0.4f)).progress!!, 0f)
        assertTrue(TileContentRules.contentFor(TileSize.JUMBO, snapshot(progress = 0f)).progressVisible)
    }
}
