package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.HomeVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeVisibilityReportPolicyTest {
    private class Clock : HomeVisibilityScheduler {
        var now = 0L
        private class Entry(val at: Long, val task: Runnable) { var cancelled = false }
        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMs: Long, task: Runnable): () -> Unit {
            val entry = Entry(now + delayMs, task)
            entries += entry
            return { entry.cancelled = true }
        }

        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val due = entries.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                entries.remove(due)
                now = due.at
                due.task.run()
            }
            now = target
        }
    }

    private val clock = Clock()
    private val sent = mutableListOf<HomeVisibility>()
    private var sendOk = true
    private val policy = HomeVisibilityReportPolicy(clock) { value ->
        if (sendOk) sent += value
        sendOk
    }

    private val home = HomeVisibility(screenOn = true, homeVisible = true)
    private val covered = HomeVisibility(screenOn = true, homeVisible = false)
    private val dark = HomeVisibility(screenOn = false, homeVisible = false)

    @Test
    fun `transport up sends the current value`() {
        policy.update(screenOn = true, homeShown = true)
        policy.onTransportUp()
        assertEquals(listOf(home), sent)
    }

    @Test
    fun `transport up sends again even when nothing changed`() {
        policy.update(true, true)
        policy.onTransportUp()
        policy.onTransportUp()
        assertEquals(listOf(home, home), sent)
    }

    @Test
    fun `a change is sent once after the debounce`() {
        policy.update(true, true)
        policy.onTransportUp()
        sent.clear()
        policy.update(true, false)
        clock.advance(HomeVisibilityReportPolicy.DEBOUNCE_MS - 1)
        assertTrue(sent.isEmpty())
        clock.advance(1)
        assertEquals(listOf(covered), sent)
    }

    @Test
    fun `a flap sends only the settled value`() {
        policy.update(true, true)
        policy.onTransportUp()
        sent.clear()
        policy.update(true, false)
        clock.advance(100)
        policy.update(true, true)
        clock.advance(100)
        policy.update(false, false)
        clock.advance(HomeVisibilityReportPolicy.DEBOUNCE_MS)
        assertEquals(listOf(dark), sent)
    }

    @Test
    fun `a flap that settles back on the sent value sends nothing`() {
        policy.update(true, true)
        policy.onTransportUp()
        sent.clear()
        policy.update(true, false)
        clock.advance(100)
        policy.update(true, true)
        clock.advance(1_000)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `homeVisible on a dark screen is reported as hidden`() {
        policy.update(true, true)
        policy.onTransportUp()
        sent.clear()
        policy.update(screenOn = false, homeShown = true)
        clock.advance(1_000)
        assertEquals(listOf(dark), sent)
    }

    @Test
    fun `nothing is sent or queued while the link is down`() {
        policy.update(true, true)
        clock.advance(1_000)
        assertTrue(sent.isEmpty())
        policy.onTransportUp()
        policy.onLinkDown()
        sent.clear()
        policy.update(true, false)
        clock.advance(1_000)
        assertTrue(sent.isEmpty())
        policy.onTransportUp()
        assertEquals(listOf(covered), sent)
    }

    @Test
    fun `link down cancels a pending report`() {
        policy.update(true, true)
        policy.onTransportUp()
        sent.clear()
        policy.update(true, false)
        policy.onLinkDown()
        clock.advance(1_000)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a failed send is not remembered as sent`() {
        policy.update(true, true)
        policy.onTransportUp()
        sent.clear()
        sendOk = false
        policy.update(true, false)
        clock.advance(1_000)
        sendOk = true
        policy.update(true, true)
        clock.advance(1_000)
        // Back on the value the phone already has: nothing more to say.
        assertEquals(emptyList<HomeVisibility>(), sent)
    }
}
