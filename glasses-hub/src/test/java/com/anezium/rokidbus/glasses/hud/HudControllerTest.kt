package com.anezium.rokidbus.glasses.hud

import android.accessibilityservice.AccessibilityService
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import android.os.Looper

class TestAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}

/** The effects runner against a real (Robolectric) main looper and a window manager we control. */
@RunWith(RobolectricTestRunner::class)
class HudControllerTest {
    private class ProbeWindowManager(
        private val delegate: WindowManager,
        private val refuseAdd: Boolean = false,
    ) : WindowManager by delegate {
        var added = 0
        var removed = 0
        override fun addView(view: View, params: android.view.ViewGroup.LayoutParams) {
            if (refuseAdd) throw WindowManager.BadTokenException("refused")
            added++
            delegate.addView(view, params)
        }
        override fun removeView(view: View) {
            removed++
            delegate.removeView(view)
        }
    }

    private val services = ArrayList<ServiceController<TestAccessibilityService>>()
    private val connected = ArrayList<AccessibilityService>()

    private fun newService(): AccessibilityService =
        Robolectric.buildService(TestAccessibilityService::class.java).create().also { services += it }.get()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @After
    fun tearDown() {
        connected.forEach { HudController.onServiceDestroyed(it) }
        HudController.hostFactory = { service, manager -> HudHost(service, manager) }
        idle()
    }

    private fun connect(service: AccessibilityService = newService()): AccessibilityService {
        HudController.onServiceConnected(service)
        connected += service
        idle()
        return service
    }

    @Test
    fun a_launcher_whose_window_is_refused_falls_back_to_hidden_and_frees_the_ring() {
        val windows = ProbeWindowManager(
            Robolectric.buildService(TestAccessibilityService::class.java).create().get()
                .getSystemService(WindowManager::class.java),
            refuseAdd = true,
        )
        HudController.hostFactory = { service, _ -> HudHost(service, windows) }
        connect()
        assertTrue(HudController.openLauncher(LauncherTrigger.APP_ICON))
        idle()
        assertEquals(HudScreen.Hidden, HudController.state.screen)
        assertFalse(HudController.state.ringFocus())
        assertFalse(HudController.isLauncherShown())
    }

    @Test
    fun a_second_connect_without_a_destroy_releases_the_first_connections_window() {
        val first = newService()
        val second = newService()
        val windows = ProbeWindowManager(first.getSystemService(WindowManager::class.java))
        HudController.hostFactory = { service, _ -> HudHost(service, windows) }
        connect(first)
        HudController.openLauncher(LauncherTrigger.APP_ICON)
        idle()
        assertTrue(HudController.isLauncherShown())
        assertEquals(1, windows.added)

        connect(second)
        assertEquals("the first host window is removed", 1, windows.removed)
        assertEquals(HudScreen.Hidden, HudController.state.screen)
        assertTrue(HudController.isServiceConnected())
        // The old service's destroy arrives late and must not tear down the new connection.
        HudController.onServiceDestroyed(first)
        assertTrue(HudController.isServiceConnected())
    }

    @Test
    fun the_broadcast_toggle_from_another_thread_runs_on_the_main_looper() {
        connect()
        var result: String? = null
        val thread = Thread { result = HudController.toggleLauncherFromBroadcast() }
        thread.start()
        thread.join()
        assertEquals("queued", result)
        assertEquals("nothing moved off the main thread", HudScreen.Hidden, HudController.state.screen)
        idle()
        assertTrue(HudController.isLauncherShown())
    }
}
