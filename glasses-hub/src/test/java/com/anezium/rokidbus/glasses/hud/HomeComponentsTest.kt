package com.anezium.rokidbus.glasses.hud

import android.app.Activity
import android.provider.Settings
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeComponentsTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun attached(loader: HudLoaderView): HudLoaderView {
        val host = FrameLayout(activity)
        activity.setContentView(host)
        host.addView(loader, FrameLayout.LayoutParams(48, 4))
        return loader
    }

    @Test
    fun the_loader_animates_only_while_active_and_on_screen() {
        val loader = attached(HudLoaderView(activity))
        assertFalse(loader.isAnimating)
        loader.setActive(true)
        assertTrue(loader.isAnimating)
        loader.setActive(false)
        assertFalse(loader.isAnimating)
    }

    @Test
    fun the_loader_is_static_under_reduced_motion() {
        Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val loader = attached(HudLoaderView(activity))
        loader.setActive(true)
        assertFalse(loader.isAnimating)
    }

    @Test
    fun the_loader_stops_when_it_leaves_the_window() {
        val loader = attached(HudLoaderView(activity))
        loader.setActive(true)
        (loader.parent as FrameLayout).removeView(loader)
        assertFalse(loader.isAnimating)
    }

    @Test
    fun a_warn_status_and_an_off_status_are_distinct_shapes_with_their_own_text() {
        val status = HudStatusView(activity)
        assertEquals(android.view.View.GONE, status.visibility)
        status.show(HudStatusView.Kind.WARN, "Could not open Lyrics: no answer")
        assertEquals(HudStatusView.Kind.WARN, status.kind)
        assertEquals("Could not open Lyrics: no answer", status.message)
        status.show(HudStatusView.Kind.OFF, HomeScreenView.EMPTY_TEXT)
        assertEquals(HudStatusView.Kind.OFF, status.kind)
        status.hide()
        assertEquals(android.view.View.GONE, status.visibility)
    }
}
