package com.anezium.rokidbus.glasses.hud

import android.graphics.Rect
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.FallbackTileView
import com.anezium.rokidbus.glasses.LiveTileView
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TilePlacement
import com.anezium.rokidbus.shared.tile.TileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h427dp-hdpi")
class GridHomeTest {
    private val context = RuntimeEnvironment.getApplication()
    private var sizes: (List<com.anezium.rokidbus.glasses.GlassesHub.LauncherEntry>) -> List<TilePlacement> = placementsOf()
    private val live = HashMap<String, HomeTile>()
    private val layer = HomeLayer(
        context,
        iconLoader = flatIcons,
        placementSource = { sizes(it) },
        tileSource = { live[it] },
        motion = HudMotionDriver.instant(),
    )
    private val grid get() = layer.screenForTest() as GridHome

    private val bodyTop = RokidHudTokens.SAFE_Y + HomeHeaderView.HEIGHT + HomeScreenView.GAP
    private val bodyBottom = bodyTop + 5 * 106 + 4 * 8

    private fun show(count: Int, selected: Int) {
        layer.show(HomeMode.GRID, entries(count), "plugin$selected")
        layer.layoutOnCanvas()
    }

    private fun inside(id: String): Boolean {
        val r = layer.itemBounds(id)!!
        return r.top >= bodyTop && r.bottom <= bodyBottom && r.left >= RokidHudTokens.SAFE_X &&
            r.right <= RokidHudTokens.SAFE_X + RokidHudTokens.CONTENT_WIDTH
    }

    @Test
    fun four_columns_fit_the_448_px_content_width_exactly() {
        assertEquals(106, GridHome.UNIT)
        show(8, 0)
        val first = layer.itemBounds("plugin0")!!
        val fourth = layer.itemBounds("plugin3")!!
        assertEquals(Rect(16, bodyTop, 16 + 106, bodyTop + 106), first)
        assertEquals(16 + 448, fourth.right)
        assertEquals(bodyTop + 114, layer.itemBounds("plugin4")!!.top)
        assertEquals(2, grid.visibleRowsForTest)
        (0 until 8).forEach { assertTrue("tile $it inside the safe area", inside("plugin$it")) }
    }

    @Test
    fun the_body_takes_every_whole_grid_row_that_fits_and_leaves_room_for_the_status_slot() {
        show(30, 0)
        assertEquals(5, grid.visibleRowsForTest)
        assertTrue(bodyBottom + HomeScreenView.GAP + HudStatusView.HEIGHT <= 640 - RokidHudTokens.SAFE_Y)
    }

    @Test
    fun default_eight_plugins_fill_two_rows_of_small_tiles() {
        show(8, 0)
        assertEquals(
            (0 until 8).map { TilePlacement("plugin$it", TileSize.SMALL, it % 4, it / 4) },
            grid.placementsForTest(),
        )
    }

    @Test
    fun wide_tall_and_large_tiles_use_the_layout_store_sizes() {
        sizes = placementsOf("plugin0" to TileSize.WIDE, "plugin1" to TileSize.TALL, "plugin2" to TileSize.LARGE)
        show(6, 0)
        assertEquals(TileSize.WIDE, grid.placementsForTest()[0].size)
        val wide = layer.itemBounds("plugin0")!!
        assertEquals(2 * 106 + 8, wide.width())
        val tall = layer.itemBounds("plugin1")!!
        assertEquals(2 * 106 + 8, tall.height())
        val large = layer.itemBounds("plugin2")!!
        assertEquals(2 * 106 + 8, large.width())
        assertEquals(2 * 106 + 8, large.height())
        listOf("plugin0", "plugin1", "plugin2").forEach { assertTrue(it, inside(it)) }
    }

    @Test
    fun selection_order_is_reading_order() {
        sizes = placementsOf("plugin0" to TileSize.WIDE, "plugin1" to TileSize.TALL, "plugin2" to TileSize.LARGE)
        show(9, 0)
        assertEquals(entries(9).map { it.id }, grid.placementsForTest().map { it.pluginId })
        assertEquals(entries(9).map { it.id }, grid.tileIdsForTest())
    }

    private fun stored(vararg entries: TileLayoutEntry) = placementsOf(entries.toList())

    @Test
    fun stored_positions_are_kept_and_holes_stay_empty() {
        sizes = stored(
            TileLayoutEntry("plugin0", TileSize.SMALL, col = 0, row = 0),
            TileLayoutEntry("plugin1", TileSize.SMALL, col = 3, row = 0),
            TileLayoutEntry("plugin2", TileSize.SMALL, col = 1, row = 2),
        )
        show(3, 0)
        assertEquals(
            listOf(
                TilePlacement("plugin0", TileSize.SMALL, 0, 0),
                TilePlacement("plugin1", TileSize.SMALL, 3, 0),
                TilePlacement("plugin2", TileSize.SMALL, 1, 2),
            ),
            grid.placementsForTest(),
        )
        assertEquals(16 + 3 * 114, layer.itemBounds("plugin1")!!.left)
        assertEquals(bodyTop + 2 * 114, layer.itemBounds("plugin2")!!.top)
        assertEquals(1, grid.tileIdsForTest().count { it == "plugin2" })
    }

    @Test
    fun the_three_wide_sizes_span_three_columns_and_their_rows() {
        sizes = stored(
            TileLayoutEntry("plugin0", TileSize.BANNER, col = 0, row = 0),
            TileLayoutEntry("plugin1", TileSize.PANEL, col = 1, row = 1),
            TileLayoutEntry("plugin2", TileSize.JUMBO, col = 0, row = 3),
        )
        show(3, 0)
        val banner = layer.itemBounds("plugin0")!!
        assertEquals(3 * 106 + 2 * 8, banner.width())
        assertEquals(106, banner.height())
        val panel = layer.itemBounds("plugin1")!!
        assertEquals(3 * 106 + 2 * 8, panel.width())
        assertEquals(2 * 106 + 8, panel.height())
        assertEquals(16 + 114, panel.left)
        assertEquals(16 + 448, panel.right)
        val jumbo = layer.itemBounds("plugin2")!!
        assertEquals(3 * 106 + 2 * 8, jumbo.width())
        assertEquals(3 * 106 + 2 * 8, jumbo.height())
        assertTrue("plugin0", inside("plugin0"))
        assertTrue("plugin1", inside("plugin1"))
    }

    @Test
    fun the_grid_ends_at_the_bottom_of_the_lowest_tile_and_scrolls_to_a_tile_below_empty_rows() {
        sizes = stored(
            TileLayoutEntry("plugin0", TileSize.SMALL, col = 0, row = 0),
            TileLayoutEntry("plugin1", TileSize.WIDE, col = 2, row = 6),
        )
        show(2, 0)
        assertEquals(0, grid.offsetRowForTest)

        layer.select("plugin1")
        layer.layoutOnCanvas()
        // Rows 0..6 hold one tile in row 0; the lowest tile ends at row 7, so the last 5 rows show.
        assertEquals(7 - grid.visibleRowsForTest, grid.offsetRowForTest)
        assertTrue(inside("plugin1"))

        layer.select("plugin0")
        layer.layoutOnCanvas()
        assertEquals(0, grid.offsetRowForTest)
        assertTrue(inside("plugin0"))
    }

    @Test
    fun a_layout_change_that_keeps_the_reading_order_moves_the_tiles() {
        sizes = stored(
            TileLayoutEntry("plugin0", TileSize.SMALL, col = 0, row = 0),
            TileLayoutEntry("plugin1", TileSize.SMALL, col = 1, row = 0),
        )
        show(2, 0)
        val before = layer.itemBounds("plugin1")!!

        sizes = stored(
            TileLayoutEntry("plugin0", TileSize.SMALL, col = 0, row = 0),
            TileLayoutEntry("plugin1", TileSize.SMALL, col = 3, row = 1),
        )
        layer.update(entries(2), "plugin0")
        layer.layoutOnCanvas()

        val after = layer.itemBounds("plugin1")!!
        assertEquals(16 + 3 * 114, after.left)
        assertEquals(before.top + 114, after.top)
    }

    @Test
    fun more_than_two_rows_scroll_by_whole_rows_and_keep_the_selected_tile_visible() {
        sizes = placementsOf("plugin4" to TileSize.LARGE, "plugin9" to TileSize.TALL, "plugin20" to TileSize.LARGE)
        val count = 30
        show(count, 0)
        for (selected in 0 until count) {
            layer.select("plugin$selected")
            layer.layoutOnCanvas()
            assertTrue("tile $selected visible", inside("plugin$selected"))
        }
        layer.select("plugin29")
        assertTrue(grid.offsetRowForTest > 0)
        layer.select("plugin0")
        assertEquals(0, grid.offsetRowForTest)
    }

    @Test
    fun a_selection_move_changes_focus_on_two_tiles_and_keeps_every_view() {
        show(8, 0)
        val views = grid.tileIdsForTest().map { grid.tileViewForTest(it) }
        layer.select("plugin5")
        assertEquals(views, grid.tileIdsForTest().map { grid.tileViewForTest(it) })
        assertEquals(1, grid.tileIdsForTest().count { grid.isTileFocusedForTest(it) })
        assertTrue(grid.isTileFocusedForTest("plugin5"))
    }

    @Test
    fun live_and_fallback_tiles_show_selection_and_focus_alike() {
        live["plugin1"] = HomeTile(snapshot("plugin1"), stale = false)
        show(3, 1)
        assertTrue(grid.tileViewForTest("plugin1") is LiveTileView)
        assertTrue(grid.tileViewForTest("plugin0") is FallbackTileView)
        assertTrue(grid.isTileFocusedForTest("plugin1"))
        layer.select("plugin0")
        assertTrue(grid.isTileFocusedForTest("plugin0"))
        assertFalse(grid.isTileFocusedForTest("plugin1"))
        layer.select("plugin1")
        assertTrue(grid.isTileFocusedForTest("plugin1"))
    }

    @Test
    fun tile_data_updates_swap_only_the_changed_tile_and_keep_its_focus() {
        show(4, 2)
        val others = listOf("plugin0", "plugin1", "plugin3").map { grid.tileViewForTest(it) }
        val before = grid.tileViewForTest("plugin2")

        live["plugin2"] = HomeTile(snapshot("plugin2"), stale = false)
        layer.onTileChanged("plugin2")

        assertNotSame(before, grid.tileViewForTest("plugin2"))
        assertTrue(grid.isTileFocusedForTest("plugin2"))
        assertEquals(others, listOf("plugin0", "plugin1", "plugin3").map { grid.tileViewForTest(it) })

        live.remove("plugin2")
        layer.onTileChanged("plugin2")
        assertTrue(grid.tileViewForTest("plugin2") is FallbackTileView)
        assertTrue(grid.isTileFocusedForTest("plugin2"))
    }

    @Test
    fun a_layout_change_adds_removes_and_resizes_by_id() {
        show(4, 0)
        val kept = grid.tileViewForTest("plugin1")
        sizes = placementsOf("plugin3" to TileSize.WIDE)
        layer.update(entries(5).filterNot { it.id == "plugin0" }, "plugin1")
        layer.layoutOnCanvas()
        assertSame(kept, grid.tileViewForTest("plugin1"))
        assertEquals(listOf("plugin1", "plugin2", "plugin3", "plugin4"), grid.tileIdsForTest())
        assertEquals(TileSize.WIDE, grid.placementsForTest()[2].size)
    }

    @Test
    fun opening_shows_the_loader_on_a_live_tile_too() {
        live["plugin0"] = HomeTile(snapshot("plugin0"), stale = false)
        show(2, 0)
        layer.showOpening("plugin0")
        assertTrue((grid.tileViewForTest("plugin0") as HomeItemView).homeOpening)
        layer.showStatus("Could not open")
        assertFalse((grid.tileViewForTest("plugin0") as HomeItemView).homeOpening)
    }

    @Test
    fun a_hidden_layer_binds_no_live_data_and_catches_up_when_it_is_shown() {
        layer.visibility = android.view.View.GONE
        live["plugin0"] = HomeTile(snapshot("plugin0", tone = com.anezium.rokidbus.shared.tile.TileTone.CRITICAL), stale = false)
        show(2, 0)
        assertTrue(grid.tileViewForTest("plugin0") is FallbackTileView)
        live["plugin1"] = HomeTile(snapshot("plugin1"), stale = false)
        layer.onTileChanged("plugin1")
        assertTrue("a write under a hidden layer is not bound", grid.tileViewForTest("plugin1") is FallbackTileView)

        layer.visibility = android.view.View.VISIBLE
        assertTrue(grid.tileViewForTest("plugin0") is LiveTileView)
        assertTrue(grid.tileViewForTest("plugin1") is LiveTileView)
    }

    @Test
    fun while_a_notice_owns_the_ring_no_tile_draws_the_focus_chrome_and_it_returns_after() {
        live["plugin0"] = HomeTile(snapshot("plugin0"), stale = false)
        show(3, 0)
        val focused = grid.tileViewForTest("plugin0") as HomeItemView
        val fallback = grid.tileViewForTest("plugin1") as HomeItemView
        assertTrue(focused.homeFocused)
        layer.setNoticeOwnsRing(true)
        assertFalse(focused.homeFocused)
        layer.select("plugin1")
        assertFalse("a selection move under a notice stays at rest", fallback.homeFocused)
        layer.setNoticeOwnsRing(false)
        assertTrue(fallback.homeFocused)
        assertFalse(focused.homeFocused)
    }
}
