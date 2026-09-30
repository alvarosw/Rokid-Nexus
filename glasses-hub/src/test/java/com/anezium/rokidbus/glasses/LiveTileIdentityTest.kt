package com.anezium.rokidbus.glasses

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import com.anezium.rokidbus.glasses.hud.HomeMode
import com.anezium.rokidbus.glasses.hud.HomeTile
import com.anezium.rokidbus.glasses.hud.HudMotionDriver
import com.anezium.rokidbus.glasses.hud.HomeLayer
import com.anezium.rokidbus.glasses.hud.GridHome
import com.anezium.rokidbus.glasses.hud.HudIconView
import com.anezium.rokidbus.glasses.hud.layoutOnCanvas
import com.anezium.rokidbus.glasses.hud.placementsOf
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
import org.robolectric.annotation.Config

/**
 * A live tile names its plugin like a fallback tile does, and the screen has at most one
 * full-intensity frame (focus) and one critical (docs/ui-rewrite/00-architecture.md, U5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h427dp-hdpi")
class LiveTileIdentityTest {
    private val context = RuntimeEnvironment.getApplication()
    private val entry = GlassesHub.LauncherEntry("transit", "Transit", null)
    private val icons: (android.content.Context, GlassesHub.LauncherEntry) -> android.graphics.drawable.Drawable =
        { _, _ -> ColorDrawable(Color.BLACK) }

    private fun snapshot(tone: TileTone, title: String = "12") = TileSnapshot(
        pluginId = "transit",
        contentKey = "eta",
        title = title,
        unit = "min",
        tone = tone,
        subtitle = "Line 4",
        rows = listOf("a", "b", "c"),
    )

    private fun tile(size: TileSize, tone: TileTone = TileTone.OK): LiveTileView =
        LiveTileView(context, size).apply {
            bindEntry(entry, icons)
            bind(snapshot(tone), stale = false)
        }

    private fun pixels(size: TileSize): Pair<Int, Int> {
        val unit = GridHome.UNIT
        return (size.cols * unit + (size.cols - 1) * 8) to (size.rows * unit + (size.rows - 1) * 8)
    }

    private fun LiveTileView.layOut(size: TileSize) {
        val (w, h) = pixels(size)
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        layout(0, 0, w, h)
    }

    @Test
    fun every_size_carries_the_plugin_name_top_left_above_the_live_value() {
        TileSize.entries.forEach { size ->
            val view = tile(size)
            view.layOut(size)
            assertEquals("$size", "TRANSIT", view.nameForTest)
            val column = view.getChildAt(0) as android.view.ViewGroup
            val header = column.getChildAt(0)
            val content = column.getChildAt(1)
            assertEquals("$size header at the left of the inner area", 0, header.left)
            assertEquals(0, header.top)
            assertTrue("$size header inside the tile", header.bottom <= view.height - 2 * 8)
            assertTrue("$size value below the header", content.top >= header.bottom)
            assertTrue("$size header keeps its 16 px icon", (header as android.view.ViewGroup).getChildAt(0).layoutParams.width == 16)
        }
    }

    @Test
    fun the_name_is_present_before_a_snapshot_arrives_too() {
        val view = LiveTileView(context, TileSize.SMALL)
        view.bindEntry(entry, icons)
        assertEquals("TRANSIT", view.nameForTest)
    }

    @Test
    fun only_a_warning_or_critical_tile_shows_the_alert_icon_and_a_demoted_critical_reads_as_warn() {
        assertFalse(tile(TileSize.SMALL, TileTone.OK).alertMarkVisibleForTest)
        assertTrue(tile(TileSize.SMALL, TileTone.WARN).alertMarkVisibleForTest)
        val critical = tile(TileSize.SMALL, TileTone.CRITICAL)
        assertTrue(critical.alertMarkVisibleForTest)
        critical.setCriticalPrimary(false)
        assertTrue("still an alert, at warn intensity", critical.alertMarkVisibleForTest)
    }

    @Test
    fun the_critical_blink_runs_once_per_episode_and_only_on_the_primary() {
        val view = LiveTileView(context, TileSize.SMALL)
        var fired = 0
        view.criticalEmphasis = { fired++ }
        view.bindEntry(entry, icons)
        view.bind(snapshot(TileTone.CRITICAL), false)
        view.bind(snapshot(TileTone.CRITICAL, title = "13"), false)
        assertEquals("a republish does not restart it", 1, fired)

        view.setCriticalPrimary(false)
        view.bind(snapshot(TileTone.CRITICAL, title = "14"), false)
        assertEquals("a demoted tile does not blink", 1, fired)
        view.setCriticalPrimary(true)
        assertEquals("promoted again: a new blink", 2, fired)

        view.bind(snapshot(TileTone.OK), false)
        view.bind(snapshot(TileTone.CRITICAL), false)
        assertEquals("a new episode blinks again", 3, fired)
    }

    private fun gridWith(tones: Map<String, TileTone>, selected: String): GridHome {
        val live = tones.mapValues { (id, tone) -> HomeTile(snapshot(tone).copy(pluginId = id), false) }
        val layer = HomeLayer(
            context,
            iconLoader = icons,
            placementSource = placementsOf(),
            tileSource = { live[it] },
            motion = HudMotionDriver.instant(),
        )
        layer.show(HomeMode.GRID, (0 until 5).map { GlassesHub.LauncherEntry("p$it", "Plugin $it", null) }, selected)
        layer.layoutOnCanvas()
        return layer.screenForTest() as GridHome
    }

    private fun primaries(grid: GridHome) = grid.tileIdsForTest().filter {
        (grid.tileViewForTest(it) as? LiveTileView)?.criticalPrimary == true &&
            (grid.tileViewForTest(it) as LiveTileView).alertMarkVisibleForTest
    }

    @Test
    fun only_one_critical_per_screen_the_focused_one_if_it_is_critical_else_the_first() {
        val tones = mapOf("p1" to TileTone.CRITICAL, "p3" to TileTone.CRITICAL, "p4" to TileTone.OK)
        val grid = gridWith(tones, selected = "p0")
        assertEquals(listOf("p1"), primaries(grid))

        val focused = gridWith(tones, selected = "p3")
        assertEquals(listOf("p3"), primaries(focused))
        assertTrue(focused.isTileFocusedForTest("p3"))
    }
}
