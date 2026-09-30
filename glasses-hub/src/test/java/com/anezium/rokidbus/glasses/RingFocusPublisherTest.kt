package com.anezium.rokidbus.glasses

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** 01 §7.4 item 78: what the R08 access bridge is told, byte for byte. */
@RunWith(RobolectricTestRunner::class)
class RingFocusPublisherTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun broadcasts(): List<Intent> = shadowOf(context).broadcastIntents

    @Test
    fun item78_a_focus_edge_is_one_explicit_broadcast_to_the_bridge_with_focused_and_ts() {
        val before = System.currentTimeMillis()
        RingFocusPublisher.publish(context, focused = true)
        val after = System.currentTimeMillis()

        val intent = broadcasts().single()
        assertEquals("com.anezium.r08accessbridge.action.NEXUS_RING_FOCUS", intent.action)
        assertEquals("com.anezium.r08accessbridge", intent.`package`)
        assertNull("explicit by package, never a global broadcast", intent.component)
        assertTrue(intent.getBooleanExtra("focused", false))
        val ts = intent.getLongExtra("ts", -1L)
        assertTrue("ts is wall-clock millis", ts in before..after)
        assertEquals(setOf("focused", "ts"), intent.extras!!.keySet())
    }

    @Test
    fun item78_a_release_carries_focused_false() {
        RingFocusPublisher.publish(context, focused = false)
        val intent = broadcasts().single()
        assertTrue(intent.hasExtra("focused"))
        assertFalse(intent.getBooleanExtra("focused", true))
    }
}
