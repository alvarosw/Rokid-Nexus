package com.anezium.rokidbus.glasses.hud

import android.content.Context
import android.view.View
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The window contract of [HudHost] (docs/ui-rewrite/00-architecture §2.3): one window, added once,
 * layers switched by visibility, removed only when the machine leaves the display.
 */
@RunWith(RobolectricTestRunner::class)
class HudHostTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private class CountingWindowManager(private val delegate: WindowManager) : WindowManager by delegate {
        var added = 0
        var removed = 0
        var updated = 0
        override fun addView(view: View, params: android.view.ViewGroup.LayoutParams) {
            added++
            delegate.addView(view, params)
        }
        override fun removeView(view: View) {
            removed++
            delegate.removeView(view)
        }
        override fun updateViewLayout(view: View, params: android.view.ViewGroup.LayoutParams) {
            updated++
            delegate.updateViewLayout(view, params)
        }
    }

    private val windows = CountingWindowManager(context.getSystemService(WindowManager::class.java))
    private val host = HudHost(context, windows)

    private val home = HudScreen.Home(HomeMode.LIST, "a")
    private val app = HudScreen.App(SurfaceInfo("s", "s"), Origin.HOME)

    @Test
    fun the_window_is_added_once_and_never_re_laid_out_between_home_opening_and_app() {
        assertTrue(host.attach())
        assertTrue(host.attach())
        host.sync(home)
        host.sync(HudScreen.Opening("a", 1, 10_000, home))
        host.sync(app)
        host.sync(home)
        assertEquals(1, windows.added)
        assertEquals(0, windows.removed)
        assertEquals(0, windows.updated)
        host.detach()
        host.detach()
        assertEquals(1, windows.removed)
        assertFalse(host.isAttached)
    }

    @Test
    fun layers_follow_the_screen_and_the_app_stays_under_a_launcher_opened_over_it() {
        host.attach()
        host.sync(home)
        assertEquals(View.VISIBLE, host.home.visibility)
        assertEquals(View.GONE, host.app.visibility)

        host.sync(HudScreen.Opening("a", 1, 10_000, home))
        assertEquals(View.VISIBLE, host.home.visibility)
        assertEquals(View.GONE, host.app.visibility)

        host.sync(app)
        assertEquals(View.GONE, host.home.visibility)
        assertEquals(View.VISIBLE, host.app.visibility)

        host.sync(HudScreen.Home(HomeMode.LIST, "a", beneath = app))
        assertEquals(View.VISIBLE, host.home.visibility)
        assertEquals(View.VISIBLE, host.app.visibility)

        host.sync(HudScreen.Hidden)
        assertEquals(View.GONE, host.home.visibility)
        assertEquals(View.GONE, host.app.visibility)
    }

    @Test
    fun item12_detach_is_guarded_when_the_host_is_not_attached() {
        host.detach()
        assertEquals("nothing to remove from a window manager that never had it", 0, windows.removed)
        assertFalse(host.isAttached)

        host.attach()
        host.detach()
        host.detach()
        assertEquals("a second hide is a no-op", 1, windows.removed)
        // A guarded detach leaves the host usable.
        assertTrue(host.attach())
        assertEquals(2, windows.added)
    }

    @Test
    fun a_second_attach_after_detach_adds_a_fresh_window() {
        host.attach()
        host.detach()
        host.attach()
        assertEquals(2, windows.added)
        assertTrue(host.isAttached)
    }

    @Test
    fun the_viewport_is_the_one_configured_value() {
        val custom = HudGeometry(HudGeometry.Viewport(left = 0, top = 144, width = 480, height = 352))
        val params = custom.viewportLayoutParams()
        assertEquals(480, params.width)
        assertEquals(352, params.height)
        assertEquals(144, params.topMargin)
        val default = HudGeometry.DEFAULT.viewportLayoutParams()
        assertEquals(0, default.topMargin)
        assertEquals(0, default.leftMargin)
    }
}
