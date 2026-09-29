package com.anezium.rokidbus.glasses.hud

import android.content.Intent
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anezium.rokidbus.glasses.HomeScreenshotHostActivity
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The open and close morph at 0 / 25 / 50 / 75 / 100 % of its duration on the 480x640 screen, driven
 * by a hand-stepped animation clock through the real machine and the real host. Each frame is
 * checked against the single-hue rule; the app layer is empty here (surface content is drawn by the
 * emulator run), so the frames show the panel, the dimming and the cover hole.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h427dp-hdpi")
class MotionScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val clock = ManualFrameClock()
    private val harness = HostHarness(context, clock, manualMotion(clock), manualLayout = false, useWindow = false)

    init {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun run(name: String, mode: HomeMode, closing: Boolean) {
        ActivityScenario.launch<HomeScreenshotHostActivity>(Intent(context, HomeScreenshotHostActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                harness.start(mode, 8)
                activity.setContentView(harness.host.contentViewForTest, FrameLayout.LayoutParams(480, 640))
            }
            scenario.onActivity { harness.intent(HudIntent.Next); harness.intent(HudIntent.Next) }
            if (closing) {
                scenario.onActivity { harness.intent(HudIntent.Select) }
                scenario.onActivity { clock.advance(0); clock.play(400) }
                scenario.onActivity { harness.surfaceFor("plugin2") }
                scenario.onActivity { clock.advance(0); clock.play(400) }
                scenario.onActivity { harness.intent(HudIntent.Dismiss) }
            } else {
                scenario.onActivity { harness.intent(HudIntent.Select) }
            }
            scenario.onActivity { clock.advance(0) }
            var elapsed = 0L
            for (percent in listOf(0, 25, 50, 75, 100)) {
                val target = STRUCTURAL_MS * percent / 100
                scenario.onActivity { clock.advance(target - elapsed) }
                elapsed = target
                val path = "build/outputs/roborazzi/$name-${percent.toString().padStart(3, '0')}.png"
                onView(isRoot()).captureRoboImage(path)
                assertSingleHue(File(path))
            }
        }
    }

    @Test fun open_list() = run("morph-open-list", HomeMode.LIST, closing = false)

    @Test fun open_grid() = run("morph-open-grid", HomeMode.GRID, closing = false)

    @Test fun close_list() = run("morph-close-list", HomeMode.LIST, closing = true)

    @Test fun close_grid() = run("morph-close-grid", HomeMode.GRID, closing = true)

    private companion object {
        const val STRUCTURAL_MS = com.anezium.rokidbus.client.ui.RokidHudTokens.DURATION_STRUCTURAL_MS
    }
}
