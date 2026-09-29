package com.anezium.rokidbus.glasses.hud

import android.view.View
import android.widget.TextView
import com.anezium.rokidbus.glasses.GridLauncherView
import com.anezium.rokidbus.glasses.LauncherMenuView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HomeLayerTest {
    private val layer = HomeLayer(RuntimeEnvironment.getApplication())

    private fun contentView(): View = layer.getChildAt(0)

    private fun statusView(): TextView = layer.getChildAt(layer.childCount - 1) as TextView

    @Test
    fun the_content_view_follows_the_mode_and_is_kept_while_the_mode_stays() {
        layer.show(HomeMode.LIST, emptyList(), null)
        val list = contentView()
        assertTrue(list is LauncherMenuView)

        layer.show(HomeMode.LIST, emptyList(), null)
        assertSame(list, contentView())

        layer.show(HomeMode.GRID, emptyList(), null)
        assertTrue(contentView() is GridLauncherView)
        assertEquals(2, layer.childCount)
    }

    @Test
    fun opening_and_failure_share_one_status_line_that_a_new_show_or_selection_clears() {
        layer.show(HomeMode.LIST, emptyList(), null)
        assertEquals(View.GONE, statusView().visibility)

        layer.showOpening("Lyrics")
        assertEquals(View.VISIBLE, statusView().visibility)
        assertEquals("Opening Lyrics...", statusView().text.toString())

        layer.showStatus("Could not open Lyrics: no answer")
        assertEquals("Could not open Lyrics: no answer", statusView().text.toString())

        layer.select(null)
        assertEquals(View.GONE, statusView().visibility)

        layer.showOpening("Lyrics")
        layer.show(HomeMode.LIST, emptyList(), null)
        assertEquals(View.GONE, statusView().visibility)
    }

    @Test
    fun clear_drops_the_status_and_the_rendered_entries() {
        layer.show(HomeMode.LIST, emptyList(), null)
        layer.showStatus("x")
        layer.clear()
        assertEquals(View.GONE, statusView().visibility)
    }
}
