package com.anezium.rokidbus.glasses.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HudMotionDriverTest {
    private val clock = ManualFrameClock()
    private var reduced = false
    private var scale = 1f
    private val driver = HudMotionDriver(clock, { reduced }, { scale })
    private val seen = ArrayList<Float>()
    private val value = driver.value(0f) { seen += it }

    @Test
    fun a_value_eases_to_its_target_over_the_duration_and_lands_exactly() {
        var ended = 0
        value.animateTo(1f, 200) { ended++ }
        clock.advance(0)
        clock.advance(100)
        assertTrue("mid ${value.current}", value.current > 0.4f && value.current < 0.95f)
        assertEquals(0, ended)
        clock.advance(100)
        assertEquals(1f, value.current, 0f)
        assertEquals(1, ended)
        assertFalse(value.isRunning)
        assertTrue(driver.isIdle)
        assertFalse("no frame requested once idle", clock.hasPendingFrame)
    }

    @Test
    fun values_only_ever_move_toward_the_target() {
        value.animateTo(1f, 200)
        clock.play(200)
        assertEquals(seen.sorted(), seen)
        assertTrue(seen.all { it in 0f..1f })
    }

    @Test
    fun a_retarget_continues_from_where_the_value_is_and_drops_the_old_end_hook() {
        var firstEnded = false
        var secondEnded = false
        value.animateTo(1f, 200) { firstEnded = true }
        clock.advance(0)
        clock.advance(80)
        val middle = value.current
        assertTrue(middle > 0f && middle < 1f)

        value.animateTo(0f, 200) { secondEnded = true }
        assertEquals("no jump on retarget", middle, value.current, 0f)
        clock.advance(0)
        clock.play(300)

        assertEquals(0f, value.current, 0f)
        assertFalse(firstEnded)
        assertTrue(secondEnded)
    }

    @Test
    fun a_retarget_keeps_its_speed_instead_of_restarting_a_full_tween() {
        value.snapTo(0.9f)
        value.animateTo(1f, 200, proportional = true)
        clock.advance(0)
        clock.advance(20)
        assertEquals(1f, value.current, 0f)
    }

    @Test
    fun without_proportional_every_move_takes_the_whole_duration_whatever_the_distance() {
        value.snapTo(-300f)
        value.animateTo(-320f, 200)
        clock.advance(0)
        clock.advance(100)
        assertTrue(value.current < -300f && value.current > -320f)
        clock.advance(100)
        assertEquals(-320f, value.current, 0f)
    }

    @Test
    fun snap_and_cancel_stop_the_animation_without_its_end_hook() {
        var ended = false
        value.animateTo(1f, 200) { ended = true }
        clock.advance(0)
        clock.advance(50)
        value.snapTo(0.25f)
        clock.play(400)
        assertEquals(0.25f, value.current, 0f)
        assertFalse(ended)

        value.animateTo(1f, 200) { ended = true }
        clock.advance(0)
        clock.advance(50)
        val held = value.current
        value.cancel()
        clock.play(400)
        assertEquals(held, value.current, 0f)
        assertFalse(ended)
    }

    @Test
    fun reduced_motion_is_read_when_an_animation_starts_and_lands_without_an_intermediate_frame() {
        reduced = true
        var ended = 0
        value.animateTo(1f, 200) { ended++ }
        assertEquals(1f, value.current, 0f)
        assertEquals(listOf(1f), seen)
        assertEquals(1, ended)
        assertFalse(clock.hasPendingFrame)

        reduced = false
        value.animateTo(0f, 200)
        clock.advance(0)
        clock.advance(100)
        assertTrue("animates again once the setting is off", value.current > 0f && value.current < 1f)
    }

    @Test
    fun the_duration_scale_stretches_every_animation() {
        scale = 5f
        value.animateTo(1f, 200)
        clock.advance(0)
        clock.advance(500)
        assertTrue("still going at 500 ms of 1000", value.current > 0f && value.current < 1f)
        clock.advance(500)
        assertEquals(1f, value.current, 0f)
    }

    @Test
    fun an_end_hook_may_start_the_next_animation() {
        val other = driver.value(0f) { }
        value.animateTo(1f, 100) { other.animateTo(1f, 100) }
        clock.advance(0)
        clock.play(100)
        assertTrue(other.isRunning)
        clock.play(120)
        assertEquals(1f, other.current, 0f)
    }
}
