package com.anezium.rokidbus.glasses

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileSize
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real pixel screenshots of [GridLauncherView], for the visual sanity check a plain assertion
 * can't give: does a custom tile layout actually look right. Plain Robolectric NATIVE-graphics
 * `view.draw(canvas)` calls don't composite multi-child view trees correctly in this Robolectric
 * version — confirmed with a minimal reproduction outside this codebase, independent of anything
 * built here — so this drives the capture through Roborazzi's Espresso/`ActivityScenario`
 * window-attached rendering path instead, which is the supported way to get real pixels out of a
 * Robolectric test.
 *
 * These are not assertions: [TileLayoutIntegrationTest] already proves the order/size/position
 * logic correct by inspecting the packer's real output. This class exists purely so a screenshot
 * can be produced and looked at.
 */
// 480x400 at mdpi (1dp = 1px): the HUD's real physical canvas, per the design system's
// display-width/display-height tokens — not the 352 AIUI reference viewport, since this is meant
// to show what actually fits on the real display, not just the design-time canvas.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w480dp-h400dp-mdpi")
class GridLauncherScreenshotTest {
    private fun launcherEntry(id: String, name: String) = GlassesHub.LauncherEntry(id = id, displayName = name)

    // Explicit-intent launch: the host activity carries no MAIN/LAUNCHER intent-filter, only a
    // manifest entry, so ActivityScenario.launch(Class) (which synthesizes a MAIN/LAUNCHER intent)
    // can't resolve it — this overload doesn't need that.
    private fun launchHost() = ActivityScenario.launch<GridLauncherScreenshotHostActivity>(
        Intent(ApplicationProvider.getApplicationContext(), GridLauncherScreenshotHostActivity::class.java),
    )

    @Test
    fun customLayoutWithAWideATallAndAJustInstalledPlugin() {
        launchHost().use { scenario ->
            scenario.onActivity { activity ->
                TileLayoutStore.setEntries(
                    activity,
                    listOf(
                        TileLayoutEntry("weather", TileSize.WIDE),
                        TileLayoutEntry("clock", TileSize.SMALL),
                        TileLayoutEntry("timer", TileSize.TALL),
                    ),
                )
                val installOrder = listOf(
                    launcherEntry("clock", "Clock"),
                    launcherEntry("weather", "Weather"),
                    launcherEntry("timer", "Timer"),
                    launcherEntry("newplugin", "Just Installed"),
                )
                val ordered = TileLayoutStore.applyOrder(activity, installOrder)

                val palette = listOf(
                    0xFF3D6B4A.toInt(),
                    0xFF2F5238.toInt(),
                    0xFF264429.toInt(),
                    0xFF1B331E.toInt(),
                )
                val view = GridLauncherView(activity).apply {
                    setIconLoaderForTest { _, entry -> ColorDrawable(palette[ordered.indexOf(entry) % palette.size]) }
                    setBackgroundColor(Color.BLACK)
                }
                view.render(ordered, selectedIndex = 0)
                activity.setContentView(view)
            }
            onView(isRoot()).captureRoboImage("build/outputs/roborazzi/grid-launcher-custom-layout.png")
        }
    }

    @Test
    fun emptyState() {
        launchHost().use { scenario ->
            scenario.onActivity { activity ->
                TileLayoutStore.setEntries(activity, emptyList())
                val view = GridLauncherView(activity).apply { setBackgroundColor(Color.BLACK) }
                view.render(emptyList(), selectedIndex = 0)
                activity.setContentView(view)
            }
            onView(isRoot()).captureRoboImage("build/outputs/roborazzi/grid-launcher-empty-state.png")
        }
    }
}
