package com.anezium.rokidbus.glasses.hud

import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.AmbientMotionValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The idle signal `AmbientStack` waits on before it re-adds a window (docs/ui-rewrite/00-architecture §6, U6). */
@RunWith(RobolectricTestRunner::class)
class AmbientMotionValueTest {
    private val clock = ManualFrameClock()
    private var reduced = false
    private var idles = 0
    private val seen = ArrayList<Float>()
    private val value = AmbientMotionValue(manualMotion(clock) { reduced }, 0f, { seen += it }, { idles++ })

    @Test
    fun an_animation_reports_idle_once_when_it_ends() {
        var ended = 0
        value.animateTo(1f, RokidHudTokens.DURATION_STRUCTURAL_MS) { ended++ }
        clock.advance(0)
        assertTrue(value.isRunning)
        clock.advance(100)
        assertEquals(0, idles)
        clock.advance(RokidHudTokens.DURATION_STRUCTURAL_MS)
        assertEquals(1f, value.current, 0f)
        assertEquals(1, ended)
        assertEquals(1, idles)
        assertFalse(value.isRunning)
    }

    @Test
    fun a_retarget_is_not_idle_and_the_superseded_end_hook_never_runs() {
        var firstEnded = false
        value.animateTo(1f, 200) { firstEnded = true }
        clock.advance(0)
        clock.advance(80)
        value.animateTo(0f, 200)
        assertEquals(0, idles)
        clock.advance(0)
        clock.play(300)
        assertFalse(firstEnded)
        assertEquals(0f, value.current, 0f)
        assertEquals(1, idles)
    }

    @Test
    fun a_snap_or_cancel_of_a_running_animation_reports_idle_and_an_idle_one_does_not() {
        value.snapTo(0.5f)
        assertEquals(0, idles)
        value.animateTo(1f, 200)
        value.snapTo(0f)
        assertEquals(1, idles)
        value.animateTo(1f, 200)
        value.cancel()
        assertEquals(2, idles)
        value.cancel()
        assertEquals(2, idles)
    }

    @Test
    fun reduced_motion_lands_on_the_target_inside_the_call_and_reports_idle() {
        reduced = true
        var ended = false
        value.animateTo(1f, 200) { ended = true }
        assertEquals(1f, value.current, 0f)
        assertTrue(ended)
        assertEquals(1, idles)
        assertFalse(clock.hasPendingFrame)
    }
}
