package com.anezium.rokidbus.glasses

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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
}
