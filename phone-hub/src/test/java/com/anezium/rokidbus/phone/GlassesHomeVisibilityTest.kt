package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.HomeVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesHomeVisibilityTest {
    private class FakeScheduler : ExternalPluginScheduler {
        val actions = linkedMapOf<String, () -> Unit>()
        val delays = linkedMapOf<String, Long>()
        override fun schedule(key: String, delayMs: Long, action: () -> Unit) {
            actions[key] = action
            delays[key] = delayMs
        }
        override fun cancel(key: String) {
            actions.remove(key)
            delays.remove(key)
        }
        fun fire() = actions.values.toList().forEach { it() }
    }

    private val scheduler = FakeScheduler()
    private var graceElapsed = 0
    private val state = GlassesHomeVisibility(scheduler) { graceElapsed++ }

    private fun report(screenOn: Boolean, home: Boolean) = state.onReport(HomeVisibility(screenOn, home))

    @Test
    fun `unknown is neither hidden nor off`() {
        assertFalse(state.hidden)
        assertFalse(state.screenOff)
        assertFalse(state.screenOffLong)
        assertNull(state.snapshot.homeVisible)
    }

    @Test
    fun `reports set hidden and screen state and expose the edges`() {
        val first = report(screenOn = true, home = true)
        assertTrue(first.homeBecameVisible)
        assertTrue(first.screenBecameOn)
        assertFalse(state.hidden)

        val covered = report(screenOn = true, home = false)
        assertFalse(covered.homeBecameVisible)
        assertFalse(covered.screenBecameOn)
        assertTrue(state.hidden)

        assertTrue(report(screenOn = true, home = true).homeBecameVisible)
        assertFalse(report(screenOn = true, home = true).homeBecameVisible)
    }

    @Test
    fun `link down returns to unknown`() {
        report(screenOn = false, home = false)
        state.onLinkDown()
        assertFalse(state.hidden)
        assertFalse(state.screenOff)
        assertTrue(scheduler.actions.isEmpty())
    }

    @Test
    fun `the screen staying off for the grace period ends leases once`() {
        report(screenOn = false, home = false)
        assertEquals(GlassesHomeVisibility.SCREEN_OFF_LEASE_GRACE_MS, scheduler.delays.values.single())
        assertFalse(state.screenOffLong)

        scheduler.fire()
        assertTrue(state.screenOffLong)
        assertEquals(1, graceElapsed)
    }

    @Test
    fun `repeating the screen off report does not restart the grace`() {
        report(screenOn = false, home = false)
        val first = scheduler.actions.values.single()
        report(screenOn = false, home = false)
        assertTrue(first === scheduler.actions.values.single())
    }

    @Test
    fun `the screen coming back before the grace cancels it`() {
        report(screenOn = false, home = false)
        report(screenOn = true, home = false)
        assertTrue(scheduler.actions.isEmpty())
        assertFalse(state.screenOffLong)
        assertEquals(0, graceElapsed)
    }

    @Test
    fun `screen on clears the long flag and a link drop does too`() {
        report(screenOn = false, home = false)
        scheduler.fire()
        assertTrue(state.screenOffLong)
        assertTrue(report(screenOn = true, home = false).screenBecameOn)
        assertFalse(state.screenOffLong)

        report(screenOn = false, home = false)
        scheduler.fire()
        state.onLinkDown()
        assertFalse(state.screenOffLong)
    }

    @Test
    fun `a stale grace timer after screen on does nothing`() {
        report(screenOn = false, home = false)
        val stale = scheduler.actions.values.single()
        report(screenOn = true, home = true)
        stale()
        assertFalse(state.screenOffLong)
        assertEquals(0, graceElapsed)
    }

    @Test
    fun `pending tile publishes are marked cleared drained and pruned`() {
        val pending = PendingTilePublishes()
        pending.mark("a")
        pending.mark("b")
        pending.mark("a")
        pending.mark("c")
        pending.clear("b")
        pending.remove("c")
        assertTrue(pending.contains("a"))
        assertEquals(listOf("a"), pending.drain())
        assertTrue(pending.drain().isEmpty())

        pending.mark("a")
        pending.mark("b")
        pending.retainOnly(setOf("b"))
        assertEquals(listOf("b"), pending.drain())
    }
}
