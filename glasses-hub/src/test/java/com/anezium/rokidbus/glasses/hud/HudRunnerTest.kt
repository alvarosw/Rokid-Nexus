package com.anezium.rokidbus.glasses.hud

import com.anezium.rokidbus.glasses.hud.HudEffect.AttachHost
import com.anezium.rokidbus.glasses.hud.HudEffect.CloseApp
import com.anezium.rokidbus.glasses.hud.HudEffect.DetachHost
import com.anezium.rokidbus.glasses.hud.HudEffect.PublishRingFocus
import com.anezium.rokidbus.glasses.hud.HudEffect.SendLauncherOpen
import com.anezium.rokidbus.glasses.hud.HudEffect.ShowApp
import com.anezium.rokidbus.glasses.hud.HudEffect.ShowHome
import com.anezium.rokidbus.glasses.hud.HudEffect.ShowOpening
import com.anezium.rokidbus.glasses.hud.HudEffect.ShowStatus
import com.anezium.rokidbus.glasses.hud.HudScreen.App
import com.anezium.rokidbus.glasses.hud.HudScreen.Hidden
import com.anezium.rokidbus.glasses.hud.HudScreen.Home
import com.anezium.rokidbus.glasses.hud.HudScreen.Opening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The effects runner with a fake clock, timer and sink: the controller-level behavior that does not
 * need a window. Timeouts, queued follow-up events and the swallowed-BACK answer are what the
 * Android side depends on.
 */
class HudRunnerTest {
    private class FakeTimer : HudTimer {
        var at: Long? = null
        var task: Runnable? = null
        override fun schedule(atUptimeMs: Long, task: Runnable) {
            at = atUptimeMs
            this.task = task
        }
        override fun cancel() {
            at = null
            task = null
        }
    }

    private class Fixture(
        config: HudConfig = HudConfig(),
        val onEffect: (HudEffect, HudRunner) -> Unit = { _, _ -> },
    ) {
        var now = 0L
        val timer = FakeTimer()
        val effects = ArrayList<HudEffect>()
        val settled = ArrayList<HudScreen>()
        val errors = ArrayList<Throwable>()
        lateinit var runner: HudRunner

        init {
            runner = HudRunner(
                machine = HudStateMachine(config),
                clock = { now },
                timer = timer,
                sink = object : HudEffectSink {
                    override fun execute(effect: HudEffect) {
                        effects += effect
                        onEffect(effect, runner)
                    }
                    override fun settled(state: HudState) {
                        settled += state.screen
                    }
                },
                onError = { errors += it },
            )
            runner.dispatch(HudEvent.ServiceConnected)
            runner.dispatch(HudEvent.LauncherEntriesChanged(listOf("a", "b")))
            effects.clear()
            settled.clear()
        }

        fun intent(i: HudIntent) = runner.dispatch(HudEvent.Intent(i))
        fun fireTimer() {
            val task = timer.task ?: error("nothing scheduled")
            timer.cancel()
            task.run()
        }
    }

    @Test
    fun a_pending_open_expires_through_the_timer_and_home_comes_back_with_a_status() {
        val f = Fixture()
        f.intent(HudIntent.OpenLauncher(LauncherTrigger.TRIPLE_TAP))
        f.now = 100
        f.intent(HudIntent.Select)
        val opening = f.runner.state.screen as Opening
        assertEquals(100 + HudConfig().openTimeoutMs, f.timer.at)
        assertTrue(SendLauncherOpen("a", opening.openToken) in f.effects)
        f.effects.clear()

        f.now = 100 + HudConfig().openTimeoutMs
        f.fireTimer()

        assertTrue(f.runner.state.screen is Home)
        assertEquals(
            listOf(ShowHome(HomeMode.LIST, "a", listOf("a", "b")), ShowStatus(HudStatus.OpenFailed("a", OpenFailure.TIMEOUT))),
            f.effects,
        )
        assertNull(f.timer.task)
    }

    @Test
    fun the_surface_arriving_cancels_the_open_deadline() {
        val f = Fixture()
        f.intent(HudIntent.OpenLauncher(LauncherTrigger.TRIPLE_TAP))
        f.intent(HudIntent.Select)
        assertTrue(f.timer.task != null)
        f.runner.dispatch(HudEvent.SurfaceShown("a", "a", DisplayPath.OVERLAY))
        assertTrue(f.runner.state.screen is App)
        assertNull(f.timer.task)
    }

    @Test
    fun events_raised_by_effects_run_after_the_current_transition_in_order() {
        val order = ArrayList<String>()
        val f = Fixture(onEffect = { effect, runner ->
            order += effect::class.simpleName.orEmpty()
            // The machine closes an app and the surface controller reports the hide from inside it.
            if (effect is CloseApp) runner.dispatch(HudEvent.SurfaceHidden(effect.surfaceId))
        })
        f.runner.dispatch(HudEvent.SurfaceShown("s", "s", DisplayPath.OVERLAY))
        order.clear()
        f.intent(HudIntent.Dismiss)
        // CloseApp, then the rest of the dismiss (DetachHost, ring focus) before the queued hide.
        assertEquals(listOf("CloseApp", "DetachHost", "PublishRingFocus"), order)
        assertEquals(Hidden, f.runner.state.screen)
    }

    @Test
    fun a_failed_send_reported_from_the_sink_returns_to_home_with_the_failure() {
        val f = Fixture(onEffect = { effect, runner ->
            if (effect is SendLauncherOpen) runner.dispatch(HudEvent.OpenFailed(effect.token))
        })
        f.intent(HudIntent.OpenLauncher(LauncherTrigger.TRIPLE_TAP))
        f.effects.clear()
        f.intent(HudIntent.Select)
        assertTrue(f.runner.state.screen is Home)
        assertTrue(ShowOpening("a") in f.effects)
        assertTrue(ShowStatus(HudStatus.OpenFailed("a", OpenFailure.SEND_FAILED)) in f.effects)
        assertNull(f.timer.task)
    }

    @Test
    fun the_host_is_attached_once_and_stays_through_home_opening_and_app() {
        val f = Fixture()
        f.intent(HudIntent.OpenLauncher(LauncherTrigger.TRIPLE_TAP))
        f.intent(HudIntent.Select)
        f.runner.dispatch(HudEvent.SurfaceShown("a", "a", DisplayPath.OVERLAY))
        f.intent(HudIntent.Dismiss)
        assertTrue(f.runner.state.screen is Home)
        f.intent(HudIntent.Dismiss)
        assertEquals(Hidden, f.runner.state.screen)
        assertEquals(1, f.effects.count { it == AttachHost })
        assertEquals(1, f.effects.count { it == DetachHost })
        assertTrue(ShowApp("a") in f.effects)
    }

    @Test
    fun screens_settle_after_every_event_including_ones_with_no_effects() {
        val f = Fixture()
        f.intent(HudIntent.Next)
        assertEquals(listOf<HudScreen>(Hidden), f.settled)
    }

    @Test
    fun swallow_back_is_reported_to_the_caller_and_not_to_the_sink() {
        val f = Fixture(HudConfig(unclaimedBackGuardMs = 500))
        f.intent(HudIntent.OpenLauncher(LauncherTrigger.TRIPLE_TAP))
        f.now = 1_000
        f.intent(HudIntent.Dismiss)
        f.now = 1_200
        f.effects.clear()
        assertTrue(f.intent(HudIntent.Dismiss))
        assertFalse(f.effects.any { it == HudEffect.SwallowBack })
        f.now = 5_000
        assertFalse(f.intent(HudIntent.Dismiss))
    }

    @Test
    fun the_default_config_never_swallows_back() {
        val f = Fixture()
        f.intent(HudIntent.OpenLauncher(LauncherTrigger.TRIPLE_TAP))
        f.intent(HudIntent.Dismiss)
        assertFalse(f.intent(HudIntent.Dismiss))
        assertEquals(0L, HudConfig().unclaimedBackGuardMs)
    }

    @Test
    fun ring_focus_is_published_only_on_edges_through_the_runner() {
        val f = Fixture()
        f.intent(HudIntent.OpenLauncher(LauncherTrigger.TRIPLE_TAP))
        f.intent(HudIntent.Next)
        f.intent(HudIntent.Dismiss)
        assertEquals(listOf(true, false), f.effects.filterIsInstance<PublishRingFocus>().map { it.focused })
    }

    @Test
    fun a_failing_effect_does_not_wedge_the_runner() {
        val f = Fixture(onEffect = { effect, _ -> if (effect is AttachHost) error("window refused") })
        f.intent(HudIntent.OpenLauncher(LauncherTrigger.TRIPLE_TAP))
        assertEquals(1, f.errors.size)
        // The machine already moved on; later events are still handled.
        assertTrue(f.runner.state.screen is Home)
        f.intent(HudIntent.Dismiss)
        assertEquals(Hidden, f.runner.state.screen)
    }

    @Test
    fun geometry_keeps_the_viewport_in_one_value() {
        val geometry = HudGeometry()
        assertEquals(HudGeometry.Viewport(0, 0, 480, 640), geometry.viewport)
        assertEquals(HudGeometry.DEFAULT, geometry)
    }
}
