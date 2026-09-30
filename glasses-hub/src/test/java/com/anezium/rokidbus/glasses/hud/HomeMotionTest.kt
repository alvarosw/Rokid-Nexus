package com.anezium.rokidbus.glasses.hud

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Focus ring and scroll offset move at duration-default on a selection move, and only then. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h427dp-hdpi")
class HomeMotionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val clock = ManualFrameClock()
    private var reduced = false
    private val layer = HomeLayer(
        context,
        iconLoader = flatIcons,
        sizeSource = sizesOf(),
        tileSource = { null },
        motion = manualMotion(clock) { reduced },
    )
    private val list get() = layer.screenForTest() as ListHome
    private val grid get() = layer.screenForTest() as GridHome

    private fun showList(count: Int, selected: Int) {
        layer.show(HomeMode.LIST, entries(count), "plugin$selected")
        layer.layoutOnCanvas()
    }

    @Test
    fun a_selection_move_scrolls_at_duration_default_and_lands_on_the_offset() {
        showList(20, 0)
        layer.select("plugin19")
        val target = -list.offsetForTest.toFloat()
        assertTrue(target < 0)
        assertEquals("state changes at once, the strip has not moved yet", 0f, list.stripForTest().translationY, 0f)

        clock.advance(0)
        clock.advance(100)
        val middle = list.stripForTest().translationY
        assertTrue("mid $middle", middle < 0f && middle > target)
        clock.advance(100)
        assertEquals(target, list.stripForTest().translationY, 0f)
        assertTrue(clock.hasPendingFrame.not())
    }

    @Test
    fun a_second_move_mid_scroll_retargets_from_where_the_strip_is() {
        showList(20, 0)
        layer.select("plugin19")
        clock.advance(0)
        clock.advance(80)
        val here = list.stripForTest().translationY
        layer.select("plugin0")
        assertEquals("no jump", here, list.stripForTest().translationY, 0f)
        clock.advance(0)
        clock.play(400)
        assertEquals(0f, list.stripForTest().translationY, 0f)
        assertEquals(0, list.offsetForTest)
    }

    @Test
    fun every_change_that_is_not_a_selection_move_lands_at_once() {
        showList(20, 0)
        layer.show(HomeMode.LIST, entries(20), "plugin19")
        assertEquals(-list.offsetForTest.toFloat(), list.stripForTest().translationY, 0f)
        layer.update(entries(20), "plugin2")
        assertEquals(-list.offsetForTest.toFloat(), list.stripForTest().translationY, 0f)
        assertFalse(clock.hasPendingFrame)
    }

    @Test
    fun settle_ends_the_scroll_and_the_focus_ring_on_their_end_states() {
        showList(20, 0)
        layer.select("plugin19")
        clock.advance(0)
        clock.advance(60)
        layer.settleMotion()
        assertEquals(-list.offsetForTest.toFloat(), list.stripForTest().translationY, 0f)
        val row = list.rowsForTest().getValue("plugin19")
        assertTrue(row.background is GradientDrawable)
    }

    @Test
    fun the_focus_ring_cross_fades_between_the_two_rows_and_ends_as_plain_chrome() {
        showList(4, 0)
        layer.select("plugin1")
        val old = list.rowsForTest().getValue("plugin0")
        val new = list.rowsForTest().getValue("plugin1")
        assertTrue(new.homeFocused && !old.homeFocused)
        clock.advance(0)
        clock.advance(80)
        assertTrue("mid-fade is a blend of the two frames", new.background is LayerDrawable && old.background is LayerDrawable)
        clock.advance(200)
        assertTrue(new.background is GradientDrawable && old.background is GradientDrawable)
    }

    @Test
    fun the_grid_scrolls_by_whole_rows_with_the_same_motion() {
        layer.show(HomeMode.GRID, entries(30), "plugin0")
        layer.layoutOnCanvas()
        layer.select("plugin29")
        assertEquals(0f, grid.stripForTestTranslation(), 0f)
        clock.advance(0)
        clock.advance(100)
        val mid = grid.stripForTestTranslation()
        assertTrue(mid < 0f)
        clock.advance(200)
        assertEquals(-(grid.offsetRowForTest * GridHome.PITCH).toFloat(), grid.stripForTestTranslation(), 0f)
    }

    @Test
    fun reduced_motion_lands_a_selection_move_with_no_intermediate_frame() {
        reduced = true
        showList(20, 0)
        layer.select("plugin19")
        assertEquals(-list.offsetForTest.toFloat(), list.stripForTest().translationY, 0f)
        assertTrue(list.rowsForTest().getValue("plugin19").background is GradientDrawable)
        assertFalse(clock.hasPendingFrame)
    }
}

private fun GridHome.stripForTestTranslation(): Float = tileViewForTest(tileIdsForTest().first())!!.parent.let { (it as android.view.View).translationY }
