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
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.TileTone
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

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
        setup: (HomeLayer) -> Unit,
    ) {
        launchHost().use { scenario ->
            scenario.onActivity { activity ->
                val layer = HomeLayer(
                    activity,
                    sizeSource = { list -> list.associate { it.id to sizes[it.id] } },
                    tileSource = { live[it] },
                )
                activity.setContentView(layer, FrameLayout.LayoutParams(480, 640))
                setup(layer)
            }
            val path = "build/outputs/roborazzi/$name.png"
            onView(isRoot()).captureRoboImage(path)
            assertSingleHue(File(path))
        }
    }

    private fun assertSingleHue(file: File) {
        val image = ImageIO.read(file)
        assertEquals("$file width", 480, image.width)
        assertEquals("$file height", 640, image.height)
        var lit = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val argb = image.getRGB(x, y)
            val r = argb shr 16 and 0xFF
            val g = argb shr 8 and 0xFF
            val b = argb and 0xFF
            if (g < 4 && r < 4 && b < 4) continue
            lit++
            // #40FF5E at any intensity over black: r = 0.251 g, b = 0.369 g (anti-aliasing keeps it).
            assertTrue(
                "$file pixel ($x,$y) is not the single hue: r=$r g=$g b=$b",
                abs(r - g * 0.251) <= 4 && abs(b - g * 0.369) <= 4,
            )
        }
        assertTrue("$file lit nothing", lit > 0)
        // No large fill: lit area stays a small share of the canvas (bloom rule).
        assertTrue("$file lit share ${lit / (480f * 640)}", lit < 480 * 640 * 0.35)
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
    fun grid_empty() = capture("grid-08-empty") { it.show(HomeMode.GRID, emptyList(), null) }
}
