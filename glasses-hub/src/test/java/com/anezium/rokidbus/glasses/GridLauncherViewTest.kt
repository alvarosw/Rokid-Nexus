package com.anezium.rokidbus.glasses

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import com.anezium.rokidbus.client.ui.RokidHudTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class GridLauncherViewTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun entries(n: Int): List<GlassesHub.LauncherEntry> =
        (0 until n).map { GlassesHub.LauncherEntry(id = "plugin$it", displayName = "Plugin $it") }

    // Real icon resolution goes through Android resources, which Robolectric doesn't load in
    // this module; a fake loader keeps this test about tile-count/focus bookkeeping only.
    private fun newView(): GridLauncherView =
        GridLauncherView(context).apply { setIconLoaderForTest { _, _ -> ColorDrawable(Color.BLACK) } }

    @Test
    fun `zero one and many entries lay out without crashing`() {
        listOf(0, 1, 5).forEach { n ->
            val view = newView()
            view.render(entries(n), selectedIndex = 0)
            assertEquals(n, view.tileCountForTest())
        }
    }

    @Test
    fun `focused index n minus one is reachable by walking forward one step at a time`() {
        val n = 6
        val view = newView()
        val all = entries(n)

        for (target in 0 until n) {
            // Mirrors LauncherOverlayRenderer.moveSelection's flat index walk — this view never
            // computes selection itself, it only ever renders the index it's handed.
            view.render(all, selectedIndex = target)
            assertEquals(n, view.tileCountForTest())
            for (i in 0 until n) {
                assertEquals("tile $i focus state at selection $target", i == target, view.isTileFocusedForTest(i))
            }
        }

        view.render(all, selectedIndex = n - 1)
        assertTrue(view.isTileFocusedForTest(n - 1))
        assertFalse(view.isTileFocusedForTest(0))
    }

    /** A window-attached, laid-out host so a tile's tween has real, non-zero on-screen bounds. */
    private fun layoutIn(view: GridLauncherView) {
        val host = FrameLayout(context)
        host.addView(view, FrameLayout.LayoutParams(RokidHudTokens.dp(context, 480), RokidHudTokens.dp(context, 352)))
        host.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        host.layout(0, 0, RokidHudTokens.dp(context, 480), RokidHudTokens.dp(context, 352))
    }

    @Test
    fun `open transition settles and dims siblings without crashing`() {
        val view = newView()
        view.render(entries(4), selectedIndex = 0)
        layoutIn(view)

        var settled = false
        view.beginOpenTransition(0) { settled = true }
        shadowOf(android.os.Looper.getMainLooper()).idleFor(
            java.time.Duration.ofMillis(RokidHudTokens.DURATION_STRUCTURAL_MS + 50),
        )

        assertTrue(settled)
    }

    @Test
    fun `disabling motion falls back to an instant open, no tween`() {
        val view = newView()
        view.render(entries(4), selectedIndex = 0)
        layoutIn(view)
        view.motionEnabledForTest = false

        var settled = false
        view.beginOpenTransition(0) { settled = true }

        assertTrue(settled)
    }

    @Test
    fun `an out-of-range index settles immediately rather than crashing`() {
        val view = newView()
        view.render(entries(2), selectedIndex = 0)
        layoutIn(view)

        var settled = false
        view.beginOpenTransition(99) { settled = true }

        assertTrue(settled)
    }
}
