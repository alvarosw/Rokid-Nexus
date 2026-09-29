package com.anezium.rokidbus.glasses.hud

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.anezium.rokidbus.glasses.TileCache
import com.anezium.rokidbus.glasses.TileController
import com.anezium.rokidbus.shared.tile.TileTone
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class HomeLayerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val live = HashMap<String, HomeTile>()
    private val layer = HomeLayer(
        context,
        iconLoader = flatIcons,
        sizeSource = sizesOf(),
        tileSource = { live[it] },
        motion = HudMotionDriver.instant(),
    )

    private val list get() = layer.screenForTest() as ListHome
    private val grid get() = layer.screenForTest() as GridHome

    @Test
    fun the_rendering_follows_the_mode_and_is_kept_while_the_mode_stays() {
        layer.show(HomeMode.LIST, entries(3), "plugin0")
        val first = layer.screenForTest()
        assertTrue(first is ListHome)

        layer.show(HomeMode.LIST, entries(3), "plugin1")
        assertSame(first, layer.screenForTest())

        layer.show(HomeMode.GRID, entries(3), "plugin0")
        assertTrue(layer.screenForTest() is GridHome)
        assertEquals(1, layer.childCount)
    }

    @Test
    fun opening_puts_a_loader_on_the_selected_row_and_tile_only() {
        layer.show(HomeMode.LIST, entries(4), "plugin1")
        layer.showOpening("plugin1")
        assertEquals(listOf("plugin1"), list.rowsForTest().filterValues { it.homeOpening }.keys.toList())

        layer.show(HomeMode.GRID, entries(4), "plugin2")
        layer.showOpening("plugin2")
        assertTrue(grid.tileViewForTest("plugin2").let { (it as HomeItemView).homeOpening })
        assertFalse((grid.tileViewForTest("plugin1") as HomeItemView).homeOpening)
    }

    @Test
    fun a_failure_shows_a_status_until_the_next_input_and_never_before_a_new_show() {
        layer.show(HomeMode.LIST, entries(3), "plugin0")
        layer.showOpening("plugin0")
        layer.showStatus("Could not open Lyrics: no answer")
        assertEquals("Could not open Lyrics: no answer", list.failureTextForTest())
        assertTrue(list.rowsForTest().values.none { it.homeOpening })

        layer.select("plugin1")
        assertNull(list.failureTextForTest())

        layer.showStatus("x")
        layer.show(HomeMode.LIST, entries(3), "plugin0")
        assertNull(list.failureTextForTest())
    }

    @Test
    fun a_failure_expires_on_its_own_after_a_few_seconds() {
        layer.show(HomeMode.LIST, entries(3), "plugin0")
        layer.showStatus("Could not open Lyrics: no answer")
        shadowOf(Looper.getMainLooper()).idleFor(HomeLayer.FAILURE_MS - 100, TimeUnit.MILLISECONDS)
        assertEquals("Could not open Lyrics: no answer", list.failureTextForTest())
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        assertNull(list.failureTextForTest())
    }

    @Test
    fun clear_drops_the_status_and_the_rendered_entries() {
        layer.show(HomeMode.GRID, entries(3), "plugin0")
        layer.showStatus("x")
        layer.clear()
        assertNull(grid.failureTextForTest())
        assertTrue(grid.tileIdsForTest().isEmpty())
        assertEquals(HomeStatus.None, layer.currentModel.status)
    }

    @Test
    fun a_tile_data_write_refreshes_an_open_grid_in_place() {
        layer.show(HomeMode.GRID, entries(3), "plugin0")
        val before = grid.tileViewForTest("plugin1")
        val untouched = grid.tileViewForTest("plugin2")

        live["plugin1"] = HomeTile(snapshot("plugin1"), stale = false)
        layer.onTileChanged("plugin1")
        val after = grid.tileViewForTest("plugin1")
        assertNotSame(before, after)
        assertSame(untouched, grid.tileViewForTest("plugin2"))

        // A second publish rebinds the live tile itself.
        live["plugin1"] = HomeTile(snapshot("plugin1", title = "13"), stale = false)
        layer.onTileChanged("plugin1")
        assertSame(after, grid.tileViewForTest("plugin1"))
    }

    @Test
    fun the_tile_cache_observer_reaches_an_attached_layer() {
        TileController.start(context)
        try {
            val entries = entries(2)
            val cache = HomeLayer(context, flatIcons, sizesOf(), motion = HudMotionDriver.instant())
            val host = android.widget.FrameLayout(context)
            val windowed = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
            windowed.setContentView(host)
            host.addView(cache)
            cache.show(HomeMode.GRID, entries, "plugin0")
            val before = (cache.screenForTest() as GridHome).tileViewForTest("plugin1")

            TileCache.put(context, snapshot("plugin1", tone = TileTone.WARN), 0L)
            shadowOf(Looper.getMainLooper()).idle()

            assertNotSame(before, (cache.screenForTest() as GridHome).tileViewForTest("plugin1"))
        } finally {
            TileController.stop()
            TileCache.clear(context)
        }
    }

    @Test
    fun tile_data_is_ignored_in_list_mode() {
        layer.show(HomeMode.LIST, entries(2), "plugin0")
        live["plugin1"] = HomeTile(snapshot("plugin1"), stale = false)
        layer.onTileChanged("plugin1")
        assertTrue(layer.currentModel.tileData.isEmpty())
    }

    @Test
    fun the_home_layer_has_no_scroll_view() {
        fun walk(view: View): Boolean =
            view is ScrollView || (view is ViewGroup && (0 until view.childCount).any { walk(view.getChildAt(it)) })
        layer.show(HomeMode.LIST, entries(10), "plugin9")
        assertFalse(walk(layer))
        layer.show(HomeMode.GRID, entries(14), "plugin13")
        assertFalse(walk(layer))
    }
}
