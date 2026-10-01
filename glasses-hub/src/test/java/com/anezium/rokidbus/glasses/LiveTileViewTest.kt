package com.anezium.rokidbus.glasses

import android.graphics.drawable.GradientDrawable
import com.anezium.rokidbus.hudtiles.TileOp
import com.anezium.rokidbus.hudtiles.TilePart
import com.anezium.rokidbus.hudtiles.TileTextStyle
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveTileViewTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun snapshot(tone: TileTone) = TileSnapshot(
        pluginId = "transit",
        contentKey = "eta",
        title = "12",
        unit = "min",
        tone = tone,
    )

    @Test
    fun `every tone renders an outline drawable, never a filled background`() {
        TileTone.entries.forEach { tone ->
            val view = LiveTileView(context, TileSize.SMALL)
            view.bind(snapshot(tone), stale = false)
            assertTrue(view.background is GradientDrawable)
        }
    }

    @Test
    fun `critical emphasis hook fires only for the critical tone`() {
        val view = LiveTileView(context, TileSize.SMALL)
        var fired = 0
        view.criticalEmphasis = { fired++ }
        view.bind(snapshot(TileTone.OK), stale = false)
        assertEquals(0, fired)
        view.bind(snapshot(TileTone.CRITICAL), stale = false)
        assertEquals(1, fired)
    }

    @Test
    fun `numeric title renders as DataReadout mono value, not plain text`() {
        val view = LiveTileView(context, TileSize.SMALL)
        view.bind(snapshot(TileTone.OFF), stale = false)
        val value = view.layoutForTest.texts(TilePart.VALUE).single()
        assertEquals("12", value.text)
        assertEquals(TileTextStyle.DATA, value.style)
        assertTrue(view.layoutForTest.texts(TilePart.TITLE).isEmpty())
    }

    @Test
    fun `no snapshot yet shows the loader, never a blank view`() {
        val view = LiveTileView(context, TileSize.SMALL)
        // Constructed but never bound: still the mandated in-flight state, the header drawn above it.
        assertTrue(view.loaderVisibleForTest)
        assertTrue(view.layoutForTest.body.isEmpty())
        view.bind(snapshot(TileTone.OFF), stale = false)
        assertFalse(view.loaderVisibleForTest)
    }

    @Test
    fun `a warning tile's name gives way to the alert mark`() {
        val view = LiveTileView(context, TileSize.SMALL)
        view.bindEntry(GlassesHub.LauncherEntry("transit", "Transit planner with a long name"), { _, _ -> android.graphics.drawable.ColorDrawable(0) })
        view.bind(snapshot(TileTone.OK), stale = false)
        val plain = view.layoutForTest.texts(TilePart.NAME).single().text
        view.bind(snapshot(TileTone.WARN), stale = false)
        val warned = view.layoutForTest.texts(TilePart.NAME).single().text
        assertTrue("$warned vs $plain", warned.length < plain.length)
    }

    @Test
    fun `a templated snapshot draws its template's layout`() {
        val view = LiveTileView(context, TileSize.LARGE)
        view.bind(
            TileSnapshot(
                pluginId = "media",
                contentKey = "track",
                content = TileContent.Music(title = "Harbour Lights", artist = "Nova Reyes", playing = true),
            ),
            stale = false,
        )
        assertEquals(listOf("Harbour Lights"), view.layoutForTest.texts(TilePart.TITLE).map { it.text })
        assertEquals(listOf("Nova Reyes"), view.layoutForTest.texts(TilePart.ARTIST).map { it.text })
    }
}


/** 01 §7.3 item 57: which parts of a snapshot a tile shows, by size. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveTileViewBindingTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun snapshot(
        title: String = "12",
        unit: String = "min",
        subtitle: String = "",
        badge: String = "",
        rows: List<String> = emptyList(),
    ) = TileSnapshot(
        pluginId = "transit",
        contentKey = "eta",
        title = title,
        unit = unit,
        tone = TileTone.OK,
        subtitle = subtitle,
        badge = badge,
        rows = rows,
    )

    private fun bound(size: TileSize, snapshot: TileSnapshot) =
        LiveTileView(context, size).apply { bind(snapshot, stale = false) }

    /** The texts the tile draws below its header. */
    private fun shown(tile: LiveTileView): List<String> =
        tile.layoutForTest.body.filterIsInstance<TileOp.Text>().map { it.text }

    private fun progressed(progress: Float?) = TileSnapshot(
        pluginId = "transit",
        contentKey = "eta",
        title = "12",
        unit = "min",
        tone = TileTone.OK,
        progress = progress,
    )

    @Test
    fun item57_a_numeric_title_is_a_data_readout_with_its_unit() {
        val tile = bound(TileSize.SMALL, snapshot(title = "12", unit = "min"))
        assertEquals(listOf("12"), tile.layoutForTest.texts(TilePart.VALUE).map { it.text })
        assertEquals(listOf("min"), tile.layoutForTest.texts(TilePart.UNIT).map { it.text })
    }

    @Test
    fun item57_a_numeric_title_without_a_unit_shows_no_unit_text() {
        val tile = bound(TileSize.SMALL, snapshot(title = "7", unit = ""))
        assertTrue("7" in shown(tile))
        assertTrue("min" !in shown(tile))
    }

    @Test
    fun item57_a_non_numeric_title_is_plain_text_and_shows_no_unit() {
        val tile = bound(TileSize.SMALL, snapshot(title = "On time", unit = "min"))
        assertTrue("On time" in shown(tile))
        assertTrue("the unit belongs to a numeric readout only", "min" !in shown(tile))
        // The previous bind's title is put away, not left showing beside the readout.
        tile.bind(snapshot(title = "9", unit = "min"), stale = false)
        assertTrue("On time" !in shown(tile))
        assertTrue("9" in shown(tile) && "min" in shown(tile))
    }

    @Test
    fun item57_the_subtitle_shows_only_for_non_small_tiles_and_only_when_present() {
        assertTrue("Line 4" !in shown(bound(TileSize.SMALL, snapshot(subtitle = "Line 4"))))
        (TileSize.entries - TileSize.SMALL).forEach { size ->
            assertTrue("$size", "Line 4" in shown(bound(size, snapshot(subtitle = "Line 4"))))
        }
        val tile = bound(TileSize.WIDE, snapshot(subtitle = "Line 4"))
        tile.bind(snapshot(subtitle = ""), stale = false)
        assertTrue("Line 4" !in shown(tile))
    }

    @Test
    fun item57_the_badge_shows_when_non_empty_at_every_size_and_goes_when_empty() {
        TileSize.entries.forEach { size ->
            assertTrue("$size", "NEW" in shown(bound(size, snapshot(badge = "NEW"))))
        }
        val tile = bound(TileSize.SMALL, snapshot(badge = "NEW"))
        tile.bind(snapshot(badge = ""), stale = false)
        assertTrue("NEW" !in shown(tile))
    }

    @Test
    fun item57_rows_follow_the_tile_height_and_the_snapshot_itself_caps_them_at_four() {
        // The view keeps its own cap; the snapshot contract refuses a fifth row before it gets there.
        assertTrue(runCatching { snapshot(rows = listOf("r1", "r2", "r3", "r4", "r5")) }.isFailure)
        val rows = listOf("r1", "r2", "r3", "r4")
        val shownRows = { size: TileSize -> bound(size, snapshot(rows = rows)).rowTextsForTest }
        listOf(TileSize.SMALL, TileSize.WIDE, TileSize.BANNER).forEach { size ->
            assertEquals("$size", emptyList<String>(), shownRows(size))
        }
        listOf(TileSize.TALL, TileSize.LARGE, TileSize.PANEL).forEach { size ->
            assertEquals("$size", listOf("r1", "r2", "r3"), shownRows(size))
        }
        assertEquals(rows, shownRows(TileSize.JUMBO))
    }

    @Test
    fun the_progress_track_shows_only_while_the_snapshot_carries_a_progress() {
        TileSize.entries.forEach { size ->
            val tile = bound(size, snapshot())
            assertTrue("$size", !tile.progressTrackVisibleForTest)
            tile.bind(progressed(0.4f), stale = false)
            assertTrue("$size", tile.progressTrackVisibleForTest)
            assertEquals(0.4f, tile.progressForTest, 0f)
            tile.bind(snapshot(), stale = false)
            assertTrue("$size", !tile.progressTrackVisibleForTest)
        }
    }

    @Test
    fun a_loading_tile_has_no_track() {
        val tile = bound(TileSize.SMALL, progressed(1f))
        assertEquals(1f, tile.progressForTest, 0f)
        tile.showLoading(null)
        assertTrue(!tile.progressTrackVisibleForTest)
    }

    @Test
    fun a_one_by_one_tile_fits_its_content_inside_the_106_px_square() {
        val tile = LiveTileView(context, TileSize.SMALL)
        tile.bindEntry(GlassesHub.LauncherEntry("transit", "Transit planner with a long name"), { _, _ -> android.graphics.drawable.ColorDrawable(0) })
        tile.bind(
            TileSnapshot(
                pluginId = "transit",
                contentKey = "eta",
                title = "A rather long title that must not wrap",
                badge = "NEW",
                subtitle = "Sub",
                progress = 0.5f,
            ),
            stale = false,
        )
        val layout = tile.layoutForTest
        val header = layout.texts(TilePart.NAME).single()
        val content = layout.body.filterIsInstance<TileOp.Text>()
        content.forEach { assertTrue("$it inside the content box ${layout.bodyClip}", it.bottom <= layout.bodyClip.bottom) }
        assertTrue("track inside the tile", layout.footer.filterIsInstance<TileOp.Track>().single().bottom <= 106 - 8)
        assertTrue("header above the content", content.all { header.bottom <= it.top })
    }

    @Test
    fun item57_rebinding_replaces_the_rows_instead_of_appending_them() {
        val tile = bound(TileSize.LARGE, snapshot(rows = listOf("a", "b")))
        tile.bind(snapshot(rows = listOf("c")), stale = false)
        assertEquals(listOf("c"), tile.rowTextsForTest)
    }
}
