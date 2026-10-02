package com.anezium.rokidbus.phone

import android.content.Intent
import android.os.BatteryManager
import com.anezium.rokidbus.shared.BusEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class PhoneBatteryReporterTest {
    private val context = RuntimeEnvironment.getApplication()
    private val sent = ArrayList<BusEnvelope>()
    private var screenOn = true

    private fun reporter(enabled: Boolean = true) = PhoneBatteryReporter(
        context = context,
        send = {
            sent += it
            null
        },
        log = {},
        initiallyEnabled = enabled,
        isScreenOn = { screenOn },
    )

    private fun battery(level: Int) = Intent(Intent.ACTION_BATTERY_CHANGED)
        .putExtra(BatteryManager.EXTRA_LEVEL, level)
        .putExtra(BatteryManager.EXTRA_SCALE, 100)
        .putExtra(BatteryManager.EXTRA_PLUGGED, 0)

    private fun level(envelope: BusEnvelope): Int = envelope.payload.optInt("level", -1)

    @Test
    fun `a change while the screen is off is tracked but not sent, and screen on sends it`() {
        val reporter = reporter()
        context.sendStickyBroadcast(battery(50))
        reporter.start()
        assertEquals(listOf(50), sent.map(::level))

        screenOn = false
        context.sendStickyBroadcast(battery(49))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(1, sent.size)

        reporter.resend("link_up")
        assertEquals("a resend is deferred too", 1, sent.size)

        screenOn = true
        reporter.resend("screen_on")
        assertEquals(listOf(50, 49), sent.map(::level))
        reporter.stop()
    }

    @Test
    fun `toggling off while the screen is off is deferred and screen on sends hidden`() {
        val reporter = reporter(enabled = true)
        context.sendStickyBroadcast(battery(50))
        reporter.start()
        sent.clear()

        screenOn = false
        reporter.setEnabled(false)
        assertTrue(sent.isEmpty())

        screenOn = true
        reporter.resend("screen_on")
        assertEquals(1, sent.size)
        assertEquals(-1, level(sent.single()))
        reporter.stop()
    }

    @Test
    fun `toggling on while the screen is off sends the reading on screen on`() {
        val reporter = reporter(enabled = false)
        context.sendStickyBroadcast(battery(61))
        reporter.start()
        sent.clear()

        screenOn = false
        reporter.setEnabled(true)
        assertTrue(sent.isEmpty())
        screenOn = true
        reporter.resend("screen_on")
        assertEquals(listOf(61), sent.map(::level))
        reporter.stop()
    }
}
