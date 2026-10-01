package com.anezium.rokidbus.glasses.hud

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.hudtiles.SystemWidgetContent
import com.anezium.rokidbus.hudtiles.TilePart
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileSize
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** System widgets on the home grid: placed and drawn like tiles, never entries, never selected. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h427dp-hdpi")
class SystemWidgetGridTest {
    private val context = RuntimeEnvironment.getApplication()
    private val source = FakeWidgetSource()
    private var stored: List<TileLayoutEntry> = emptyList()
    private val layer = HomeLayer(
        context,
        iconLoader = flatIcons,
        placementSource = { placementsOf(stored)(it) },
        tileSource = { null },
        motion = HudMotionDriver.instant(),
        widgetSource = source,
    )
    private val grid get() = layer.screenForTest() as GridHome

    private val bodyTop = RokidHudTokens.SAFE_Y + HomeHeaderView.HEIGHT + HomeScreenView.GAP
    private val bodyBottom = bodyTop + 5 * 106 + 4 * 8

    private fun plugin(index: Int, size: TileSize, col: Int, row: Int) = TileLayoutEntry("plugin$index", size, col, row)

    private fun clock(size: TileSize, col: Int, row: Int) = TileLayoutEntry("sys:clock", size, col, row)

    private fun status(size: TileSize, col: Int, row: Int) = TileLayoutEntry("sys:status", size, col, row)

    private fun weather(size: TileSize, col: Int, row: Int) = TileLayoutEntry("sys:weather", size, col, row)

    private fun showGrid(count: Int, selected: String?) {
        layer.show(HomeMode.GRID, entries(count), selected)
        layer.layoutOnCanvas()
    }

    /** A widget's top edge on screen, scroll offset included. */
    private fun widgetTop(id: String): Float {
        val view = grid.widgetViewForTest(id)!!
        return bodyTop + view.top + (view.parent as View).translationY
    }

    @Test
    fun widgets_are_drawn_at_their_stored_cells_but_are_not_entries() {
        stored = listOf(
            clock(TileSize.WIDE, 0, 0),
            plugin(0, TileSize.SMALL, 2, 0),
            plugin(1, TileSize.SMALL, 3, 0),
            status(TileSize.SMALL, 0, 1),
        )
        showGrid(2, "plugin0")

        assertEquals(listOf("plugin0", "plugin1"), grid.tileIdsForTest())
        assertEquals(listOf("plugin0", "plugin1"), grid.placementsForTest().map { it.pluginId })
        assertEquals(listOf("sys:clock", "sys:status"), layer.currentModel.widgets.map { it.pluginId })
        val clock = grid.widgetViewForTest("sys:clock")!!
        val status = grid.widgetViewForTest("sys:status")!!
        assertEquals(0, clock.left)
        assertEquals(0, clock.top)
        assertEquals(220, clock.width)
        assertEquals(GridHome.PITCH, status.top)
        assertEquals("14:32", clock.layoutForTest!!.texts(TilePart.VALUE).single().text)
        assertEquals(listOf("82%+", "64%", "UP"), status.layoutForTest!!.texts(TilePart.VALUE).map { it.text })
        // Not a home item: no bounds for the morph, no focus.
        assertNull(layer.itemBounds("sys:clock"))
        assertFalse(clock.isFocusable)
    }

    @Test
    fun list_mode_draws_no_widgets() {
        stored = listOf(clock(TileSize.SMALL, 0, 0), plugin(0, TileSize.SMALL, 1, 0))
        layer.show(HomeMode.LIST, entries(1), "plugin0")
        assertTrue(layer.currentModel.widgets.isEmpty())
        assertTrue(layer.screenForTest() is ListHome)
    }

    @Test
    fun selecting_the_last_entry_scrolls_to_the_end_so_a_widget_below_it_shows() {
        stored = (0 until 4).map { plugin(it, TileSize.SMALL, 0, it) } + status(TileSize.BANNER, 0, 6)
        showGrid(4, "plugin0")
        assertEquals(0, grid.offsetRowForTest)

        layer.select("plugin3")
        // plugin3 alone fits at offset 0; the last entry scrolls on to the content's end.
        assertEquals(2, grid.offsetRowForTest)
        val top = widgetTop("sys:status")
        assertTrue("status at $top", top >= bodyTop && top + 106 <= bodyBottom)

        layer.select("plugin0")
        assertEquals(0, grid.offsetRowForTest)
    }

    @Test
    fun selecting_the_first_entry_scrolls_back_so_a_widget_above_it_shows() {
        stored = listOf(clock(TileSize.LARGE, 0, 0)) + (0 until 6).map { plugin(it, TileSize.SMALL, 0, it + 2) }
        showGrid(6, "plugin5")
        assertEquals(3, grid.offsetRowForTest)

        // The ring wraps from the last entry to the first; plugin0 alone would rest at offset 2.
        layer.select("plugin0")
        assertEquals(0, grid.offsetRowForTest)
        assertEquals(bodyTop.toFloat(), widgetTop("sys:clock"))
    }

    @Test
    fun the_scroll_never_hides_the_selected_entry() {
        // The last entry is at the top and a tall stack of widget rows sits below: it stays in view.
        stored = listOf(plugin(0, TileSize.SMALL, 0, 0), plugin(1, TileSize.SMALL, 1, 0), status(TileSize.SMALL, 0, 7))
        showGrid(2, "plugin1")
        assertEquals(0, grid.offsetRowForTest)
    }

    @Test
    fun a_grid_of_widgets_only_has_no_selection_and_no_empty_line() {
        stored = listOf(clock(TileSize.LARGE, 0, 0), status(TileSize.WIDE, 2, 0))
        showGrid(0, null)

        assertNull(layer.currentModel.selectedId)
        assertTrue(grid.tileIdsForTest().isEmpty())
        assertNotNull(grid.widgetViewForTest("sys:clock"))
        assertNotNull(grid.widgetViewForTest("sys:status"))
        assertNull(grid.emptyTextForTest())
        assertEquals("", grid.counterTextForTest())

        // A selection move with nothing to select changes nothing.
        layer.select(null)
        assertEquals(0, grid.offsetRowForTest)

        // Nothing at all on the grid still waits for the phone.
        stored = emptyList()
        showGrid(0, null)
        assertEquals("WAITING FOR PHONE", grid.counterTextForTest())
        assertEquals(HomeScreenView.EMPTY_TEXT, grid.emptyTextForTest())
    }

    @Test
    fun ring_steps_scroll_a_grid_of_widgets_only_one_row_at_a_time_within_its_content() {
        // Seven rows of widgets on a five-row screen: two rows below the fold.
        stored = listOf(
            clock(TileSize.LARGE, 0, 0),
            weather(TileSize.WIDE, 0, 4),
            status(TileSize.BANNER, 0, 6),
        )
        showGrid(0, null)
        assertEquals(0, grid.offsetRowForTest)

        layer.scrollRows(1)
        assertEquals(1, grid.offsetRowForTest)
        layer.scrollRows(1)
        assertEquals(2, grid.offsetRowForTest)
        val top = widgetTop("sys:status")
        assertTrue("status at $top", top >= bodyTop && top + 106 <= bodyBottom)
        layer.scrollRows(1)
        assertEquals("bounded at the content's end", 2, grid.offsetRowForTest)

        layer.scrollRows(-1)
        layer.scrollRows(-1)
        layer.scrollRows(-1)
        assertEquals("bounded at the start", 0, grid.offsetRowForTest)
        assertNull(layer.currentModel.selectedId)

        // A model refresh keeps the scrolled offset: there is no selection to pull it back.
        layer.scrollRows(1)
        layer.update(entries(0), null)
        assertEquals(1, grid.offsetRowForTest)
    }

    @Test
    fun ring_steps_never_scroll_a_grid_that_has_entries() {
        stored = (0 until 4).map { plugin(it, TileSize.SMALL, 0, it) } + status(TileSize.BANNER, 0, 6)
        showGrid(4, "plugin0")
        layer.scrollRows(1)
        assertEquals(0, grid.offsetRowForTest)
    }

    @Test
    fun an_unknown_widget_id_is_dropped_and_a_moved_widget_is_redrawn_in_place() {
        stored = listOf(clock(TileSize.SMALL, 0, 0), TileLayoutEntry("sys:radar", TileSize.SMALL, 1, 0))
        showGrid(1, "plugin0")
        assertEquals(listOf("sys:clock"), layer.currentModel.widgets.map { it.pluginId })
        val before = grid.widgetViewForTest("sys:clock")

        stored = listOf(clock(TileSize.SMALL, 3, 1))
        layer.update(entries(1), "plugin0")
        layer.layoutOnCanvas()
        val after = grid.widgetViewForTest("sys:clock")!!
        assertTrue("same view, moved", before === after)
        assertEquals(3 * GridHome.PITCH, after.left)

        stored = listOf(clock(TileSize.WIDE, 0, 1))
        layer.update(entries(1), "plugin0")
        assertEquals(220, (grid.widgetViewForTest("sys:clock")!!.layoutParams as FrameLayout.LayoutParams).width)

        stored = emptyList()
        layer.update(entries(1), "plugin0")
        assertNull(grid.widgetViewForTest("sys:clock"))
    }

    @Test
    fun widgets_observe_and_redraw_only_while_the_home_is_visible() {
        stored = listOf(clock(TileSize.SMALL, 0, 0), status(TileSize.SMALL, 1, 0), plugin(0, TileSize.SMALL, 2, 0))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(layer, FrameLayout.LayoutParams(480, 640))
        val looper = shadowOf(Looper.getMainLooper())
        // Robolectric leaves the window GONE; a real home window is visible, which is what lets
        // visibility changes reach the widgets at all.
        ReflectionHelpers.callInstanceMethod<Unit>(
            activity.window.decorView.parent,
            "dispatchAppVisibility",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        looper.idleFor(Duration.ofMillis(100))
        layer.show(HomeMode.GRID, entries(1), "plugin0")
        looper.idleFor(Duration.ofMillis(100))
        assertEquals(2, source.observers)

        // The clock redraws on the minute: 14:32:10 -> 14:33:00, and reads fresh data then.
        val clock = grid.widgetViewForTest("sys:clock")!!
        source.epochMs += 50_000L
        val reads = source.reads
        looper.idleFor(Duration.ofMillis(50_000))
        assertTrue(source.reads > reads)
        assertEquals("14:33", clock.layoutForTest!!.texts(TilePart.VALUE).single().text)

        // A data change redraws the status at once.
        source.status = source.status.copy(phoneLinked = false)
        source.changed()
        val status = grid.widgetViewForTest("sys:status")!!
        assertEquals("DOWN", status.layoutForTest!!.texts(TilePart.VALUE).last().text)

        layer.visibility = View.GONE
        assertEquals(0, source.observers)
        assertFalse(clock.observingForTest)
        val hiddenReads = source.reads
        looper.idleFor(Duration.ofMinutes(3))
        assertEquals("no redraw while hidden", hiddenReads, source.reads)

        layer.visibility = View.VISIBLE
        assertEquals(2, source.observers)
        assertTrue(clock.observingForTest)
    }

    @Test
    fun a_lost_link_and_unknown_charges_read_as_such() {
        source.status = SystemWidgetContent.Status(glasses = null, phone = null, phoneLinked = false)
        stored = listOf(status(TileSize.SMALL, 0, 0))
        showGrid(0, null)
        assertEquals(
            listOf("--", "--", "DOWN"),
            grid.widgetViewForTest("sys:status")!!.layoutForTest!!.texts(TilePart.VALUE).map { it.text },
        )
    }
}
