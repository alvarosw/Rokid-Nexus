package com.anezium.rokidbus.glasses.hud

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HudKeyEventAdapterTest {
    @Test
    fun a_raw_event_keeps_the_identity_of_its_press_in_both_directions() {
        val framework = KeyEvent(900L, 1_000L, KeyEvent.ACTION_UP, 66, 0, 0, 7, 0)
        val raw = HudKeyEventAdapter.from(framework)
        assertEquals(900L, raw.downTime)
        assertEquals(7, raw.deviceId)

        val back = HudKeyEventAdapter.toKeyEvent(raw)
        assertEquals(900L, back.downTime)
        assertEquals(1_000L, back.eventTime)
        assertEquals(7, back.deviceId)
        assertEquals(KeyEvent.ACTION_UP, back.action)
    }

    @Test
    fun an_injected_down_and_up_share_a_press_so_the_notice_settles_it_once() {
        val down = RawKeyEvent(66, RawKeyEvent.ACTION_DOWN, 0, 2_000L, DeviceClass.KEYBOARD_DPAD, downTime = 2_000L, deviceId = -1)
        val up = RawKeyEvent(66, RawKeyEvent.ACTION_UP, 0, 2_050L, DeviceClass.KEYBOARD_DPAD, downTime = 2_000L, deviceId = -1)
        val a = HudKeyEventAdapter.toKeyEvent(down)
        val b = HudKeyEventAdapter.toKeyEvent(up)
        assertEquals(a.downTime, b.downTime)
        assertEquals(a.deviceId, b.deviceId)
    }
}
