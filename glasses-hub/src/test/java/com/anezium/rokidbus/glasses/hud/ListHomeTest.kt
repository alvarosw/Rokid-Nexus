package com.anezium.rokidbus.glasses.hud

import android.view.View
import com.anezium.rokidbus.client.ui.RokidHudTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 480x640 px at hdpi: the tokens are pixels, so nothing below may depend on the 1.5 density. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h427dp-hdpi")
class ListHomeTest {
    private val context = RuntimeEnvironment.getApplication()
    private val layer = HomeLayer(context, iconLoader = flatIcons, sizeSource = sizesOf(), tileSource = { null })
    private val list get() = layer.screenForTest() as ListHome

    private val bodyTop = RokidHudTokens.SAFE_Y + HomeHeaderView.HEIGHT + HomeScreenView.GAP
    private val bodyHeight = 14 * 32 + 13 * 8
    private val bodyBottom = bodyTop + bodyHeight

    private fun show(count: Int, selected: Int) {
        layer.show(HomeMode.LIST, entries(count), "plugin$selected")
        layer.layoutOnCanvas()
    }

    @Test
    fun rows_are_32_px_with_a_20_px_icon_across_the_448_px_content_width() {
        show(8, 0)
        val row = list.rowsForTest().getValue("plugin0")
        assertEquals(32, row.height)
        assertEquals(448, row.width)
        val bounds = layer.itemBounds("plugin0")!!
        assertEquals(RokidHudTokens.SAFE_X, bounds.left)
        assertEquals(RokidHudTokens.SAFE_X + 448, bounds.right)
        assertEquals(bodyTop, bounds.top)
        assertEquals(20, (row.getChildAt(0)).layoutParams.width)
        // Row pitch is 32 + space-2.
        assertEquals(bodyTop + 40, layer.itemBounds("plugin1")!!.top)
    }

    @Test
    fun the_body_takes_every_whole_row_that_fits_between_the_header_and_the_status_slot() {
        show(20, 0)
        assertEquals(14, list.visibleRowsForTest)
        val visible = (0 until 20).filter {
            val r = layer.itemBounds("plugin$it")!!
            r.top >= bodyTop && r.bottom <= bodyBottom
        }
        assertEquals((0 until 14).toList(), visible)
        // The status slot below the body stays inside the bottom safe margin.
        assertTrue(bodyBottom + HomeScreenView.GAP + HudStatusView.HEIGHT <= 640 - RokidHudTokens.SAFE_Y)
    }

    @Test
    fun a_top_inset_takes_rows_away_instead_of_pushing_the_body_off_the_screen() {
        layer.setHudTopInsetDp(40)
        show(20, 0)
        val inset = (40 * 1.5f).toInt()
        assertTrue(list.visibleRowsForTest < 14)
        val last = list.visibleRowsForTest - 1
        assertTrue(layer.itemBounds("plugin$last")!!.bottom + HomeScreenView.GAP + HudStatusView.HEIGHT <= 640 - RokidHudTokens.SAFE_Y)
        assertEquals(bodyTop + inset, layer.itemBounds("plugin0")!!.top)
    }

    @Test
    fun the_selected_row_is_fully_visible_at_every_position_and_scrolling_is_our_own_offset() {
        val count = 20
        show(count, 0)
        for (selected in 0 until count) {
            layer.select("plugin$selected")
            layer.layoutOnCanvas()
            val r = layer.itemBounds("plugin$selected")!!
            assertTrue("row $selected top ${r.top}", r.top >= bodyTop)
            assertTrue("row $selected bottom ${r.bottom}", r.bottom <= bodyBottom)
        }
        // Walking forward keeps a row of context below the selection while there is one.
        layer.select("plugin0")
        layer.select("plugin5")
        assertEquals(0, list.offsetForTest)
        layer.select("plugin13")
        layer.layoutOnCanvas()
        assertTrue(layer.itemBounds("plugin14")!!.bottom <= bodyBottom)
        layer.select("plugin19")
        assertEquals(count * 40 - 8 - bodyHeight, list.offsetForTest)
        layer.select("plugin0")
        assertEquals(0, list.offsetForTest)
    }

    @Test
    fun a_selection_move_changes_focus_on_two_rows_and_never_rebuilds_the_tree() {
        show(10, 0)
        val rows = list.rowsForTest().values.toList()
        val strip = list.stripForTest() as android.view.ViewGroup
        val children = (0 until strip.childCount).map { strip.getChildAt(it) }

        layer.select("plugin1")
        assertEquals(children, (0 until strip.childCount).map { strip.getChildAt(it) })
        assertEquals(rows, list.rowsForTest().values.toList())
        assertEquals(listOf("plugin1"), list.rowsForTest().filterValues { it.homeFocused }.keys.toList())
    }

    @Test
    fun entries_diff_by_id_and_the_selection_follows_the_id() {
        show(4, 2)
        val kept = list.rowsForTest().getValue("plugin3")
        val goneRow = list.rowsForTest().getValue("plugin0")

        // plugin0 removed, a new plugin inserted first: order changes, rows are reused by id.
        val next = listOf(entries(6)[5]) + entries(4).drop(1)
        layer.update(next, "plugin2")
        layer.layoutOnCanvas()

        assertSame(kept, list.rowsForTest().getValue("plugin3"))
        assertFalse(list.rowsForTest().containsKey("plugin0"))
        assertTrue(goneRow.parent == null)
        val byPosition = next.map { it.id }.sortedBy { layer.itemBounds(it)!!.top }
        assertEquals(listOf("plugin5", "plugin1", "plugin2", "plugin3"), byPosition)
        assertEquals(listOf("plugin2"), list.rowsForTest().filterValues { it.homeFocused }.keys.toList())
    }

    @Test
    fun the_position_indicator_shows_only_when_rows_overflow() {
        show(14, 0)
        assertEquals(View.INVISIBLE, list.trackForTest().visibility)
        show(20, 0)
        assertEquals(View.VISIBLE, list.trackForTest().visibility)
        assertEquals(bodyHeight / (20f * 40 - 8), list.trackForTest().thumbSize, 0.01f)
        layer.select("plugin19")
        assertTrue(list.trackForTest().thumbStart > 0.25f)
    }

    @Test
    fun the_header_counts_position_and_the_empty_state_is_a_status() {
        show(10, 3)
        assertNull(list.emptyTextForTest())
        layer.show(HomeMode.LIST, emptyList(), null)
        assertEquals(HomeScreenView.EMPTY_TEXT, list.emptyTextForTest())
        assertTrue(list.rowsForTest().isEmpty())
    }

    @Test
    fun the_focused_row_is_the_one_focus_element() {
        show(3, 1)
        val focused = list.rowsForTest().values.filter { it.homeFocused }
        assertEquals(1, focused.size)
        assertNotNull(focused.single().background)
        assertNotSame(focused.single().background, list.rowsForTest().getValue("plugin0").background)
    }
}
