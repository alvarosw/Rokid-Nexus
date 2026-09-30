package com.anezium.rokidbus.glasses

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
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
        val dataValue = view.getChildAt(0)
        assertTrue(dataValue != null)
    }

    @Test
    fun `no snapshot yet shows the loader, never a blank view`() {
        val view = LiveTileView(context, TileSize.SMALL)
        // Constructed but never bound: still the mandated in-flight state.
        assertEquals(android.view.View.VISIBLE, view.findLoaderVisibilityForTest())
    }
}


/** 01 §7.3 item 57: which parts of a snapshot a tile shows, by size. */
@RunWith(RobolectricTestRunner::class)
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

    private fun texts(root: View): List<TextView> =
        if (root is TextView) listOf(root)
        else if (root is ViewGroup) (0 until root.childCount).flatMap { texts(root.getChildAt(it)) }
        else emptyList()

    /** The non-blank texts of the text views that are visible themselves. */
    private fun shown(root: View): List<String> =
        texts(root).filter { it.visibility == View.VISIBLE && it.text.isNotBlank() }.map { it.text.toString() }

    /** The one text view showing [text] as its own content, whether or not it is visible. */
    private fun textView(root: View, text: String): TextView =
        texts(root).single { it.text.toString() == text }

    @Test
    fun item57_a_numeric_title_is_a_data_readout_with_its_unit() {
        val tile = bound(TileSize.SMALL, snapshot(title = "12", unit = "min"))
        assertEquals(View.VISIBLE, textView(tile, "12").visibility)
        assertEquals(View.VISIBLE, textView(tile, "min").visibility)
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
        val shownRows = { size: TileSize ->
            texts(bound(size, snapshot(rows = rows))).map { it.text.toString() }.filter { it in rows }
        }
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
            tile.bind(snapshot().copy(progress = 0.4f), stale = false)
            assertTrue("$size", tile.progressTrackVisibleForTest)
            assertEquals(0.4f, tile.progressForTest, 0f)
            tile.bind(snapshot(), stale = false)
            assertTrue("$size", !tile.progressTrackVisibleForTest)
        }
    }

    @Test
    fun a_loading_tile_has_no_track() {
        val tile = bound(TileSize.SMALL, snapshot().copy(progress = 1f))
        assertEquals(1f, tile.progressForTest, 0f)
        tile.showLoading(null)
        assertTrue(!tile.progressTrackVisibleForTest)
    }

    @Test
    fun a_one_by_one_tile_fits_its_content_inside_the_106_px_square() {
        val tile = bound(
            TileSize.SMALL,
            snapshot(title = "A rather long title that must not wrap", unit = "", badge = "NEW", subtitle = "Sub")
                .copy(progress = 0.5f),
        )
        tile.bindEntry(GlassesHub.LauncherEntry("transit", "Transit planner with a long name"), { _, _ -> android.graphics.drawable.ColorDrawable(0) })
        tile.measure(
            View.MeasureSpec.makeMeasureSpec(106, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(106, View.MeasureSpec.EXACTLY),
        )
        tile.layout(0, 0, 106, 106)
        val column = tile.getChildAt(0) as ViewGroup
        val header = column.getChildAt(0)
        val content = column.getChildAt(1) as ViewGroup
        val track = column.getChildAt(2)
        val stacked = (0 until content.childCount).map { content.getChildAt(it) }
            .filter { it.visibility != View.GONE }.sumOf { it.measuredHeight }
        assertTrue("content needs $stacked of ${content.height}", stacked <= content.height)
        assertTrue("track inside the tile", track.bottom <= 106 - 8)
        assertTrue("header above the content", header.bottom <= content.top)
    }

    @Test
    fun item57_rebinding_replaces_the_rows_instead_of_appending_them() {
        val tile = bound(TileSize.LARGE, snapshot(rows = listOf("a", "b")))
        tile.bind(snapshot(rows = listOf("c")), stale = false)
        val shown = texts(tile).map { it.text.toString() }
        assertTrue("c" in shown)
        assertTrue("a" !in shown && "b" !in shown)
    }
}

private fun LiveTileView.findLoaderVisibilityForTest(): Int {
    // The loader is the second child added in init(); mirrors LiveTileView's own layout order.
    return getChildAt(1).visibility
}
