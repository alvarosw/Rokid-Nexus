package com.anezium.rokidbus.glasses.hud

import com.anezium.rokidbus.glasses.hud.AmbientLayer.ACTIVITY
import com.anezium.rokidbus.glasses.hud.AmbientLayer.BADGE
import com.anezium.rokidbus.glasses.hud.AmbientLayer.HOST
import com.anezium.rokidbus.glasses.hud.AmbientLayer.NOTICE
import com.anezium.rokidbus.glasses.hud.AmbientLayer.PIN
import com.anezium.rokidbus.glasses.hud.AmbientLayer.POINTER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** A window manager reduced to its one relevant property: windows stack in the order they were added. */
private class FakeWindowManager {
    val order = ArrayList<AmbientLayer>()
    val readds = HashMap<AmbientLayer, Int>()
    var failNextReadd: AmbientLayer? = null
}

private class FakeAmbientTimer : AmbientTimer {
    class Task(val at: Long, val action: () -> Unit)

    var now = 0L
    val tasks = ArrayList<Task>()

    override fun postDelayed(delayMs: Long, action: () -> Unit): () -> Unit {
        val task = Task(now + delayMs, action)
        tasks += task
        return { tasks.remove(task) }
    }

    fun advance(ms: Long) {
        now += ms
        tasks.filter { it.at <= now }.forEach { task ->
            if (tasks.remove(task)) task.action()
        }
    }
}

private class StackRig(val failures: MutableList<String> = ArrayList(), scale: Float = 1f) {
    val wm = FakeWindowManager()
    val timer = FakeAmbientTimer()
    val stack = AmbientStack(timer, { failures += it }, durationScale = { scale })
    val animating = HashSet<AmbientLayer>()
    private val windows = HashMap<AmbientLayer, AmbientWindow>()

    private fun window(layer: AmbientLayer) = windows.getOrPut(layer) {
        object : AmbientWindow {
            override val layer = layer
            override val isAnimating get() = layer in animating
            override fun readd(): Boolean {
                wm.readds.merge(layer, 1, Int::plus)
                wm.order.remove(layer)
                if (wm.failNextReadd == layer) {
                    wm.failNextReadd = null
                    return false
                }
                wm.order += layer
                return true
            }
        }
    }

    fun add(layer: AmbientLayer) {
        wm.order.remove(layer)
        wm.order += layer
        stack.added(window(layer))
    }

    fun remove(layer: AmbientLayer) {
        wm.order.remove(layer)
        stack.removed(layer)
    }

    fun idle(layer: AmbientLayer) {
        animating -= layer
        stack.animationEnded()
    }

    fun readds(layer: AmbientLayer) = wm.readds[layer] ?: 0
    val totalReadds get() = wm.readds.values.sum()
}

class AmbientStackTest {

    private val declared = AmbientLayer.entries.toList()

    @Test
    fun the_declared_order_is_host_badge_pin_activity_notice_pointer() {
        assertEquals(listOf(HOST, BADGE, PIN, ACTIVITY, NOTICE, POINTER), declared)
        // Pin is always below notice (architecture section 3, item 10), and only the host may hold focus.
        assertTrue(PIN.ordinal < NOTICE.ordinal)
        assertEquals(listOf(HOST), declared.filter { it.focusable })
    }

    @Test
    fun every_add_order_of_the_six_windows_ends_in_the_declared_order() {
        var permutations = 0
        fun permute(rest: List<AmbientLayer>, chosen: List<AmbientLayer>) {
            if (rest.isEmpty()) {
                permutations++
                val rig = StackRig()
                chosen.forEach(rig::add)
                assertEquals("add order $chosen", declared, rig.wm.order)
                assertEquals("model for $chosen", declared, rig.stack.realOrder())
                assertTrue(rig.stack.isSettled())
                assertTrue("nothing to wait for $chosen", rig.timer.tasks.isEmpty())
                return
            }
            rest.forEach { permute(rest - it, chosen + it) }
        }
        permute(declared, emptyList())
        assertEquals(720, permutations)
    }

    @Test
    fun adding_in_the_declared_order_never_re_adds_anything() {
        val rig = StackRig()
        declared.forEach(rig::add)
        assertEquals(0, rig.totalReadds)
    }

    @Test
    fun a_new_window_re_adds_only_the_windows_that_belong_above_it() {
        val rig = StackRig()
        listOf(HOST, PIN, ACTIVITY, NOTICE, POINTER).forEach(rig::add)
        // A badge sits above the host but below all of those: the pin, activity, notice and pointer
        // move, the host does not.
        rig.add(BADGE)
        assertEquals(declared, rig.wm.order)
        assertEquals(0, rig.readds(HOST))
        assertEquals(listOf(PIN, ACTIVITY, NOTICE, POINTER).map { 1 }, listOf(PIN, ACTIVITY, NOTICE, POINTER).map(rig::readds))
    }

    @Test
    fun F17_a_pin_created_while_a_notice_is_visible_ends_below_it_and_the_notice_is_not_touched_by_a_notice_add() {
        val rig = StackRig()
        rig.add(HOST)
        rig.add(NOTICE)
        rig.add(PIN)
        assertEquals(listOf(HOST, PIN, NOTICE), rig.wm.order)
        // The reverse creation order needs no re-add at all.
        val other = StackRig()
        other.add(HOST)
        other.add(PIN)
        other.add(NOTICE)
        assertEquals(listOf(HOST, PIN, NOTICE), other.wm.order)
        assertEquals(0, other.totalReadds)
    }

    @Test
    fun a_notice_created_while_the_pointer_is_visible_ends_below_the_pointer() {
        val rig = StackRig()
        rig.add(POINTER)
        rig.add(NOTICE)
        assertEquals(listOf(NOTICE, POINTER), rig.wm.order)
        assertEquals(1, rig.readds(POINTER))
        assertEquals(0, rig.readds(NOTICE))
    }

    @Test
    fun the_host_attaching_puts_every_visible_ambient_window_back_above_it_once() {
        val rig = StackRig()
        listOf(PIN, ACTIVITY, NOTICE, POINTER).forEach(rig::add)
        rig.add(HOST)
        assertEquals(declared.filter { it != BADGE }, rig.wm.order)
        assertEquals(4, rig.totalReadds)
        assertEquals(listOf(1, 1, 1, 1), listOf(PIN, ACTIVITY, NOTICE, POINTER).map(rig::readds))
    }

    @Test
    fun an_animating_window_is_not_re_added_mid_animation() {
        val rig = StackRig()
        rig.add(HOST)
        rig.add(NOTICE)
        rig.animating += NOTICE
        rig.add(PIN)
        assertEquals("the sliding notice is left alone", 0, rig.readds(NOTICE))
        assertEquals(listOf(HOST, NOTICE, PIN), rig.wm.order)
        assertFalse(rig.stack.isSettled())

        rig.idle(NOTICE)
        assertEquals(1, rig.readds(NOTICE))
        assertEquals(listOf(HOST, PIN, NOTICE), rig.wm.order)
        assertTrue(rig.stack.isSettled())
        assertTrue("the backstop is cancelled", rig.timer.tasks.isEmpty())
    }

    @Test
    fun windows_above_an_animating_window_wait_behind_it_and_are_re_added_once() {
        val rig = StackRig()
        listOf(NOTICE, POINTER).forEach(rig::add)
        rig.animating += NOTICE
        rig.add(HOST)
        assertEquals(0, rig.totalReadds)
        assertEquals(listOf(NOTICE, POINTER, HOST), rig.wm.order)

        rig.idle(NOTICE)
        assertEquals(listOf(HOST, NOTICE, POINTER), rig.wm.order)
        assertEquals(1, rig.readds(NOTICE))
        assertEquals(1, rig.readds(POINTER))
    }

    @Test
    fun a_window_that_stays_animating_is_put_back_after_the_backstop() {
        val rig = StackRig()
        rig.add(HOST)
        rig.add(ACTIVITY)
        rig.animating += ACTIVITY
        rig.add(PIN)
        rig.timer.advance(AmbientStack.MAX_DEFER_MS - 1)
        assertEquals(0, rig.readds(ACTIVITY))
        rig.timer.advance(1)
        assertEquals(listOf(HOST, PIN, ACTIVITY), rig.wm.order)
        assertTrue(rig.stack.isSettled())
    }

    private fun backstopFires(scale: Float, expectedMs: Long) {
        val rig = StackRig(scale = scale)
        rig.add(HOST)
        rig.add(ACTIVITY)
        rig.animating += ACTIVITY
        rig.add(PIN)
        rig.timer.advance(expectedMs - 1)
        assertEquals("scale $scale fired early", 0, rig.readds(ACTIVITY))
        rig.timer.advance(1)
        assertEquals("scale $scale", listOf(HOST, PIN, ACTIVITY), rig.wm.order)
    }

    @Test
    fun the_backstop_is_stretched_by_the_animator_duration_scale() {
        backstopFires(1f, 2_000L)
        backstopFires(0.5f, 2_000L)
        backstopFires(0f, 2_000L)
        backstopFires(6f, 12_000L)
        backstopFires(10f, 20_000L)
    }

    @Test
    fun removing_the_animating_window_releases_the_windows_waiting_behind_it() {
        val rig = StackRig()
        listOf(NOTICE, POINTER).forEach(rig::add)
        rig.animating += NOTICE
        rig.add(HOST)
        rig.remove(NOTICE)
        assertEquals(listOf(HOST, POINTER), rig.wm.order)
        assertEquals(1, rig.readds(POINTER))
        assertTrue(rig.stack.isSettled())
        assertTrue(rig.timer.tasks.isEmpty())
    }

    @Test
    fun a_window_animating_when_nothing_moved_is_left_alone() {
        val rig = StackRig()
        rig.add(HOST)
        rig.add(NOTICE)
        rig.animating += NOTICE
        rig.add(POINTER)
        assertEquals(0, rig.totalReadds)
        assertTrue(rig.timer.tasks.isEmpty())
    }

    @Test
    fun a_failed_re_add_forgets_the_window_and_reports_it() {
        val rig = StackRig()
        rig.add(NOTICE)
        rig.wm.failNextReadd = NOTICE
        rig.add(HOST)
        assertEquals(listOf(HOST), rig.stack.realOrder())
        assertEquals(1, rig.failures.size)
        assertTrue(rig.stack.isSettled())
        assertTrue(rig.timer.tasks.isEmpty())
    }

    @Test
    fun a_window_added_twice_is_tracked_once() {
        val rig = StackRig()
        rig.add(HOST)
        rig.add(PIN)
        rig.add(PIN)
        assertEquals(listOf(HOST, PIN), rig.stack.realOrder())
    }

    @Test
    fun random_add_remove_and_animation_sequences_settle_to_the_declared_order() {
        val random = Random(20260929)
        repeat(2_000) { round ->
            val rig = StackRig()
            repeat(40) {
                val layer = declared[random.nextInt(declared.size)]
                when (random.nextInt(5)) {
                    0, 1 -> rig.add(layer)
                    2 -> if (layer in rig.wm.order) rig.remove(layer)
                    3 -> if (layer in rig.wm.order) rig.animating += layer
                    else -> rig.idle(layer)
                }
                val expected = rig.wm.order
                assertEquals("round $round model", expected, rig.stack.realOrder())
            }
            rig.animating.clear()
            rig.stack.animationEnded()
            val settled = rig.wm.order
            assertEquals("round $round", settled.sortedBy { it.ordinal }, settled)
            assertTrue(rig.stack.isSettled())
        }
    }
}
