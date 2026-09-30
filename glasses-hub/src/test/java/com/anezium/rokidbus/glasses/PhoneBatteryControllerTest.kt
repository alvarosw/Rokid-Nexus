package com.anezium.rokidbus.glasses

import android.os.Looper
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.PhoneBatteryContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** 01 §7.11 item 168: sequence guard, no re-notify for an equal reading, no expiry. */
@RunWith(RobolectricTestRunner::class)
class PhoneBatteryControllerTest {
    private fun send(level: Int, charging: Boolean, seq: Long): Boolean {
        val handled = PhoneBatteryController.handleEnvelope(
            BusEnvelope(
                BusPaths.PHONE_BATTERY,
                payload = PhoneBatteryContract.toJson(PhoneBatteryContract.Reading(level, charging), seq),
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
        return handled
    }

    @Test
    fun item168_stale_sequences_are_dropped_and_equal_readings_do_not_re_notify() {
        val base = GlassesHubTestSupport.nextSeq()
        var notified = 0
        val stop = PhoneBatteryController.observe { notified++ }
        notified = 0

        assertTrue(send(80, false, base))
        assertEquals(80, PhoneBatteryController.reading()?.level)
        assertEquals(1, notified)

        send(50, false, base - 1)
        assertEquals("an older reading never replaces a fresher one", 80, PhoneBatteryController.reading()?.level)

        send(80, false, base + 1)
        assertEquals("an equal reading is not re-notified", 1, notified)

        send(79, true, base + 2)
        assertEquals(2, notified)
        assertEquals(PhoneBatteryContract.Reading(79, true), PhoneBatteryController.reading())
        stop()
        assertNotNull("there is no expiry: the reading stays", PhoneBatteryController.reading())
    }

    @Test
    fun an_unrelated_path_is_not_handled() {
        assertFalse(PhoneBatteryController.handleEnvelope(BusEnvelope("/other")))
    }

    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
}
