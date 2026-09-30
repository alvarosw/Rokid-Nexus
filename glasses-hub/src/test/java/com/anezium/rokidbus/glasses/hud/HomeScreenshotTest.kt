package com.anezium.rokidbus.glasses.hud

import android.content.Intent
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.glasses.HomeScreenshotHostActivity
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.TileTone
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real pixels of the home layer on the glasses screen: 480x640 px at 240 dpi (`w320dp-h427dp-hdpi`).
 * Roborazzi captures through the window-attached render path, since plain Robolectric
 * `view.draw(canvas)` does not composite multi-child trees. Besides producing the screenshots this
 * checks the design system's hard rules on the pixels: the PNG is exactly 480x640 and every lit
 * pixel is the one green hue (black composited with #40FF5E at some intensity).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h427dp-hdpi")
class HomeScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    init {
        // These PNGs are the review artifact and the input of the single-hue check below, so they are
        // always written, not only under -Proborazzi.test.record=true.
        System.setProperty("roborazzi.test.record", "true")
    }

    private val icons = listOf("music", "disc", "map", "bus", "send", "terminal", "lens", "bolt")

    private fun withIcons(count: Int): List<GlassesHub.LauncherEntry> =
        entries(count).mapIndexed { i, e -> e.copy(iconKey = icons[i % icons.size]) }

    private fun launchHost() = ActivityScenario.launch<HomeScreenshotHostActivity>(
        Intent(context, HomeScreenshotHostActivity::class.java),
    )

    private fun capture(
        name: String,
        sizes: Map<String, TileSize> = emptyMap(),
        live: Map<String, HomeTile> = emptyMap(),
        stored: List<TileLayoutEntry>? = null,
        setup: (HomeLayer) -> Unit,
    ) {
        launchHost().use { scenario ->
            scenario.onActivity { activity ->
                val layer = HomeLayer(
                    activity,
                    placementSource = stored?.let(::placementsOf) ?: placementsOf(*sizes.toList().toTypedArray()),
                    tileSource = { live[it] },
                    motion = HudMotionDriver.instant(),
                )
                activity.setContentView(layer, FrameLayout.LayoutParams(480, 640))
                setup(layer)
            }
            val path = "build/outputs/roborazzi/$name.png"
            onView(isRoot()).captureRoboImage(path)
            assertSingleHue(File(path))
        }
    }

    @Test
    fun list_top() = capture("list-01-top") { it.show(HomeMode.LIST, withIcons(8), "plugin0"); it.layoutOnCanvas() }

    @Test
    fun list_scrolled_middle() = capture("list-02-middle-scrolled") {
        it.show(HomeMode.LIST, withIcons(20), "plugin0")
        it.select("plugin13"); it.select("plugin14")
    }

    @Test
    fun list_last_selected() = capture("list-03-last-selected") {
        it.show(HomeMode.LIST, withIcons(20), "plugin19")
    }

    @Test
    fun list_empty() = capture("list-04-empty") { it.show(HomeMode.LIST, emptyList(), null) }

    @Test
    fun list_opening() = capture("list-05-opening") {
        it.show(HomeMode.LIST, withIcons(8), "plugin2"); it.showOpening("plugin2")
    }

    @Test
    fun list_failure() = capture("list-06-failure") {
        it.show(HomeMode.LIST, withIcons(8), "plugin3"); it.showStatus("Could not open Transit: no answer")
    }

    @Test
    fun grid_default_eight() = capture("grid-01-default-8") {
        it.show(HomeMode.GRID, withIcons(8), "plugin0")
    }

    private val customSizes = mapOf(
        "plugin0" to TileSize.WIDE, "plugin1" to TileSize.TALL, "plugin4" to TileSize.LARGE,
    )

    @Test
    fun grid_custom_sizes() = capture("grid-02-custom-sizes", sizes = customSizes) {
        it.show(HomeMode.GRID, withIcons(9), "plugin4")
    }

    @Test
    fun grid_fourteen_plugins_fit_without_scrolling() = capture("grid-03-14-plugins") {
        it.show(HomeMode.GRID, withIcons(14), "plugin13")
    }

    @Test
    fun grid_scrolled() = capture("grid-04-scrolled", sizes = customSizes) {
        it.show(HomeMode.GRID, withIcons(30), "plugin0")
        it.select("plugin29")
    }

    private val liveTiles = mapOf(
        "plugin1" to HomeTile(snapshot("plugin1", title = "12", unit = "min", tone = TileTone.OK), false),
        "plugin3" to HomeTile(snapshot("plugin3", title = "Late", unit = "", tone = TileTone.WARN), false),
        "plugin5" to HomeTile(snapshot("plugin5", title = "3", unit = "new", tone = TileTone.CRITICAL), true),
    )

    @Test
    fun grid_live_tile_focused() = capture("grid-05-live-focused", live = liveTiles) {
        it.show(HomeMode.GRID, withIcons(8), "plugin1")
    }

    @Test
    fun grid_live_tile_warn_focused() = capture("grid-05b-live-warn-focused", live = liveTiles) {
        it.show(HomeMode.GRID, withIcons(8), "plugin3")
    }

    @Test
    fun grid_opening() = capture("grid-06-opening", live = liveTiles) {
        it.show(HomeMode.GRID, withIcons(8), "plugin2"); it.showOpening("plugin2")
    }

    @Test
    fun grid_opening_live() = capture("grid-06b-opening-live", live = liveTiles) {
        it.show(HomeMode.GRID, withIcons(8), "plugin1"); it.showOpening("plugin1")
    }

    @Test
    fun grid_failure() = capture("grid-07-failure") {
        it.show(HomeMode.GRID, withIcons(8), "plugin3"); it.showStatus("Could not open Transit: no answer")
    }

    @Test
    fun list_notice_owns_the_ring_selection_at_rest() = capture("list-07-notice-owns-ring") {
        it.show(HomeMode.LIST, withIcons(8), "plugin2"); it.setNoticeOwnsRing(true)
    }

    @Test
    fun grid_notice_owns_the_ring_selection_at_rest() = capture("grid-09-notice-owns-ring", live = liveTiles) {
        it.show(HomeMode.GRID, withIcons(8), "plugin1"); it.setNoticeOwnsRing(true)
    }

    @Test
    fun grid_empty() = capture("grid-08-empty") { it.show(HomeMode.GRID, emptyList(), null) }

    // ---- live tiles: identity, sizes and the one-critical rule ----------------------------

    private val sizedLive = mapOf(
        "plugin0" to HomeTile(snapshot("plugin0", title = "12", unit = "min", tone = TileTone.OK, subtitle = "Next bus"), false),
        "plugin1" to HomeTile(snapshot("plugin1", title = "Late", unit = "", tone = TileTone.INFO, subtitle = "Line 4"), false),
        "plugin2" to HomeTile(snapshot("plugin2", title = "3", unit = "new", tone = TileTone.WARN), false),
        "plugin3" to HomeTile(snapshot("plugin3", title = "21", unit = "C", tone = TileTone.OFF), false),
        "plugin4" to HomeTile(
            snapshot("plugin4", title = "3", unit = "tasks", tone = TileTone.OK, subtitle = "Today")
                .copy(rows = listOf("Call Ana", "Buy milk", "Send report")),
            false,
        ),
        "plugin5" to HomeTile(snapshot("plugin5", title = "94", unit = "%", tone = TileTone.CRITICAL), false),
    )
    private val sizedLayout = mapOf(
        "plugin0" to TileSize.WIDE, "plugin1" to TileSize.TALL, "plugin4" to TileSize.LARGE,
    )

    @Test
    fun grid_live_tiles_all_sizes_carry_their_plugin_name() =
        capture("grid-10-live-sizes-labeled", sizes = sizedLayout, live = sizedLive) {
            it.show(HomeMode.GRID, withIcons(9), "plugin8")
        }

    @Test
    fun grid_critical_focused_focus_wins_and_the_alert_icon_says_critical() =
        capture("grid-11-critical-focused", sizes = sizedLayout, live = sizedLive) {
            it.show(HomeMode.GRID, withIcons(9), "plugin5")
            settleBlink()
        }

    @Test
    fun grid_critical_unfocused_settled_to_a_non_focus_treatment() =
        capture("grid-12-critical-unfocused-settled", sizes = sizedLayout, live = sizedLive) {
            it.show(HomeMode.GRID, withIcons(9), "plugin0")
            settleBlink()
        }

    @Test
    fun grid_critical_unfocused_mid_blink() =
        capture("grid-13-critical-unfocused-blink-off", sizes = sizedLayout, live = sizedLive) {
            it.show(HomeMode.GRID, withIcons(9), "plugin0")
            // The icon is dimmed in the first 200 ms step.
        }

    @Test
    fun grid_two_criticals_only_the_first_is_critical() =
        capture(
            "grid-14-two-criticals",
            live = sizedLive + ("plugin6" to HomeTile(snapshot("plugin6", title = "7", unit = "!", tone = TileTone.CRITICAL), false)),
        ) {
            it.show(HomeMode.GRID, withIcons(8), "plugin0")
            settleBlink()
        }

    // ---- free placement: holes, the 3-wide sizes and the progress track ---------------------

    private fun at(id: Int, size: TileSize, col: Int, row: Int) = TileLayoutEntry("plugin$id", size, col, row)

    private val freeLayout = listOf(
        at(0, TileSize.BANNER, 0, 0),
        at(1, TileSize.SMALL, 3, 0),
        at(2, TileSize.PANEL, 1, 2),
        at(3, TileSize.SMALL, 0, 3),
        at(4, TileSize.JUMBO, 0, 5),
    )

    private val freeLive = mapOf(
        "plugin0" to HomeTile(
            snapshot("plugin0", title = "Next bus in 12 min", unit = "", tone = TileTone.OK, subtitle = "Line 4 to Central"),
            false,
        ),
        "plugin1" to HomeTile(snapshot("plugin1", title = "7", unit = "new", tone = TileTone.INFO), false),
        "plugin2" to HomeTile(
            snapshot("plugin2", title = "3", unit = "tasks", tone = TileTone.OK, subtitle = "Today")
                .copy(rows = listOf("Call Ana", "Buy milk", "Send report"), progress = 0.66f),
            false,
        ),
        "plugin4" to HomeTile(
            snapshot("plugin4", title = "Sync", unit = "", tone = TileTone.OFF, subtitle = "Photos")
                .copy(rows = listOf("IMG_0412", "IMG_0413", "IMG_0414", "IMG_0415"), progress = 0.3f, badge = "42%"),
            false,
        ),
    )

    @Test
    fun grid_free_layout_with_holes() =
        capture("grid-15-free-layout-holes", stored = freeLayout.take(4)) {
            it.show(HomeMode.GRID, withIcons(4), "plugin0")
        }

    @Test
    fun grid_banner_panel_jumbo_fallback() =
        capture("grid-16-banner-panel-jumbo", stored = freeLayout) {
            it.show(HomeMode.GRID, withIcons(5), "plugin2")
        }

    @Test
    fun grid_banner_panel_jumbo_live_with_progress() =
        capture("grid-17-wide-live-progress", stored = freeLayout, live = freeLive) {
            it.show(HomeMode.GRID, withIcons(5), "plugin2")
        }

    // A focused JUMBO alone is about 36 % of the canvas, past the single-hue check's bloom limit, so
    // the scrolled capture focuses the small tile under it.
    @Test
    fun grid_scrolled_to_a_tile_below_a_jumbo_and_a_hole() =
        capture(
            "grid-18-scrolled-below-jumbo",
            stored = listOf(at(0, TileSize.JUMBO, 0, 0), at(1, TileSize.PANEL, 1, 3), at(2, TileSize.SMALL, 0, 6)),
            live = freeLive,
        ) {
            it.show(HomeMode.GRID, withIcons(3), "plugin2")
        }

    @Test
    fun grid_one_by_one_live_tile_with_progress_and_badge() =
        capture(
            "grid-19-small-live-progress",
            stored = listOf(at(0, TileSize.SMALL, 0, 0), at(1, TileSize.SMALL, 1, 0)),
            live = mapOf(
                "plugin0" to HomeTile(
                    snapshot("plugin0", title = "Transit planner", unit = "", tone = TileTone.OK)
                        .copy(progress = 0.5f, badge = "NEW"),
                    false,
                ),
                "plugin1" to HomeTile(snapshot("plugin1", title = "88", unit = "%").copy(progress = 0.88f), false),
            ),
        ) {
            it.show(HomeMode.GRID, withIcons(2), "plugin0")
        }

    private fun settleBlink() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_500))
    }
}
