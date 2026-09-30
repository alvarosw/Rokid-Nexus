package com.anezium.rokidbus.glasses.hud

import android.content.Context
import android.graphics.Rect
import android.view.View
import com.anezium.rokidbus.client.ui.RokidHudTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The open and close morph inside the host window, driven by a hand-stepped animation clock and by
 * the real state machine through the real runner. What matters is that the views always end in the
 * static layout of the machine's screen, however the events cut into the animation, and that no
 * frame of any animation ever produces an effect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h427dp-hdpi")
class HudHostMotionTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val clock = ManualFrameClock()
    private var reduced = false
    private val motion = manualMotion(clock) { reduced }

    private val harness = HostHarness(context, clock, motion)
    private val effects get() = harness.effects
    private val host get() = harness.host
    private val runner get() = harness.runner
    private val home: HomeLayer get() = host.home

    private fun setUp(mode: HomeMode, count: Int = 8) = harness.start(mode, count)

    private fun layOut() = harness.layOut()

    private fun intent(intent: HudIntent) = harness.intent(intent)

    private fun surfaceFor(id: String) = harness.surfaceFor(id)

    private val safe: Rect get() = HudGeometry.DEFAULT.appBounds(0)
    private val panel: MorphPanelView get() = host.panelForTest

    /** Advances the animation clock by [ms] (after its first frame set the start time). */
    private fun tick(ms: Long) {
        clock.advance(0)
        clock.play(ms)
    }

    private fun assertStatic(expected: HudScreen) {
        val name = expected::class.simpleName
        assertFalse("$name: morph still active", host.morphForTest.isActive)
        assertEquals("$name: panel", View.GONE, panel.visibility)
        assertEquals("$name: backdrop", View.GONE, host.backdropForTest.visibility)
        assertEquals("$name: home alpha", 1f, home.alpha, 0f)
        assertEquals("$name: app alpha", 1f, host.app.alpha, 0f)
        assertNull("$name: cover", home.coverForTest)
        assertNull("$name: app clip", host.app.clipBounds)
        val homeShown = expected is HudScreen.Home || expected is HudScreen.Opening
        assertEquals("$name: home visible", homeShown, home.visibility == View.VISIBLE)
        assertEquals("$name: app visible", expected is HudScreen.App, host.app.visibility == View.VISIBLE)
    }

    private fun bounds(id: String): Rect = home.itemBounds(id)!!

    // ---- open ------------------------------------------------------------------------------

    private fun openMorphGrowsFromTheItemToTheSafeAreaAndStopsThere(mode: HomeMode) {
        setUp(mode)
        intent(HudIntent.Next)
        val item = Rect(bounds("plugin1"))
        intent(HudIntent.Select)
        val opening = runner.state.screen
        assertTrue(opening is HudScreen.Opening)

        // Frame 0: the panel is exactly the item, the home dimmed not at all yet.
        assertEquals(item, panel.frameForTest)
        assertEquals(0f, panel.chromeMixForTest, 0f)
        assertEquals(item, home.coverForTest)
        assertEquals(1f, home.alpha, 0.001f)

        clock.advance(0)
        var last = Rect(item)
        repeat(20) {
            clock.advance(16)
            val frame = panel.frameForTest
            assertTrue("no overshoot: $frame outside $safe", safe.contains(frame))
            assertTrue("only grows: $frame after $last", frame.contains(last))
            last = Rect(frame)
        }
        assertEquals(safe, panel.frameForTest)
        assertEquals(1f, panel.chromeMixForTest, 0f)
        assertEquals("ground stays under a dimmed home", View.VISIBLE, host.backdropForTest.visibility)
        assertEquals(HudMorph.DIM_ALPHA, home.alpha, 0.001f)
        assertEquals(safe, home.coverForTest)
        assertTrue(panel.loaderRunningForTest)
        assertEquals(View.GONE, host.app.visibility)
        assertTrue("open sent exactly once", effects.count { it is HudEffect.SendLauncherOpen } == 1)
    }

    @Test fun list_open_morph_grows_to_the_safe_area() = openMorphGrowsFromTheItemToTheSafeAreaAndStopsThere(HomeMode.LIST)

    @Test fun grid_open_morph_grows_to_the_safe_area() = openMorphGrowsFromTheItemToTheSafeAreaAndStopsThere(HomeMode.GRID)

    @Test
    fun the_surface_arriving_after_the_morph_appears_inside_the_panel_and_leaves_a_static_app() {
        setUp(HomeMode.LIST)
        intent(HudIntent.Select)
        tick(400)
        surfaceFor("plugin0")
        val app = runner.state.screen
        assertTrue(app is HudScreen.App)
        assertEquals(View.VISIBLE, host.app.visibility)
        assertEquals(safe, host.app.clipBounds)
        assertEquals(0f, host.app.alpha, 0f)

        tick(60)
        assertTrue(host.app.alpha > 0f && host.app.alpha < 1f)
        tick(100)
        assertStatic(app)
    }

    private fun surfaceArrivingMidMorphEndsStatic(mode: HomeMode) {
        setUp(mode)
        intent(HudIntent.Next)
        intent(HudIntent.Select)
        tick(90)
        val mid = Rect(panel.frameForTest)
        assertTrue(mid != safe)
        surfaceFor("plugin1")
        // The surface is clipped to the panel while it grows; it never spills outside it.
        var last = mid
        repeat(20) {
            clock.advance(16)
            val clip = host.app.clipBounds
            if (clip != null) {
                assertEquals(panel.frameForTest, clip)
                assertTrue(safe.contains(clip))
                assertTrue(clip.contains(last))
                last = Rect(clip)
            }
        }
        assertStatic(runner.state.screen)
        assertTrue(runner.state.screen is HudScreen.App)
    }

    @Test fun list_surface_arriving_mid_morph_ends_static() = surfaceArrivingMidMorphEndsStatic(HomeMode.LIST)

    @Test fun grid_surface_arriving_mid_morph_ends_static() = surfaceArrivingMidMorphEndsStatic(HomeMode.GRID)

    // ---- interruption ----------------------------------------------------------------------

    private fun tapThenBackWithin50msEndsOnAStaticHomeAndSendsOneOpen(mode: HomeMode) {
        setUp(mode)
        intent(HudIntent.Select)
        tick(48)
        assertTrue(panel.visibility == View.VISIBLE)
        intent(HudIntent.Dismiss)
        assertTrue(runner.state.screen is HudScreen.Home)
        tick(400)
        assertStatic(runner.state.screen)
        assertEquals(1, effects.count { it is HudEffect.SendLauncherOpen })
        // The item is back where it was and drawn by the home again.
        assertEquals(bounds("plugin0"), home.itemBounds("plugin0"))
    }

    @Test fun list_tap_then_back_within_50ms() = tapThenBackWithin50msEndsOnAStaticHomeAndSendsOneOpen(HomeMode.LIST)

    @Test fun grid_tap_then_back_within_50ms() = tapThenBackWithin50msEndsOnAStaticHomeAndSendsOneOpen(HomeMode.GRID)

    @Test
    fun the_same_tap_repeated_right_after_back_turns_the_panel_around_without_a_jump() {
        setUp(HomeMode.LIST)
        intent(HudIntent.Select)
        tick(200)
        intent(HudIntent.Dismiss)
        tick(60)
        val turning = Rect(panel.frameForTest)
        intent(HudIntent.Select)
        assertEquals("no jump", turning, panel.frameForTest)
        tick(400)
        assertEquals(safe, panel.frameForTest)
        assertEquals(2, effects.count { it is HudEffect.SendLauncherOpen })
    }

    @Test
    fun a_failed_open_collapses_onto_the_item_and_shows_the_failure_at_once() {
        setUp(HomeMode.GRID)
        intent(HudIntent.Next)
        intent(HudIntent.Select)
        tick(400)
        val token = (runner.state.screen as HudScreen.Opening).openToken
        runner.dispatch(HudEvent.OpenFailed(token, OpenFailure.TIMEOUT))
        layOut()
        assertTrue(runner.state.screen is HudScreen.Home)
        assertEquals("failed", home.screenForTest()!!.failureTextForTest())
        assertEquals(safe, panel.frameForTest)
        tick(60)
        assertTrue(safe.contains(panel.frameForTest) && panel.frameForTest != safe)
        tick(300)
        assertStatic(runner.state.screen)
    }

    // ---- close -----------------------------------------------------------------------------

    private fun openApp(mode: HomeMode): HudScreen {
        setUp(mode)
        intent(HudIntent.Next)
        intent(HudIntent.Select)
        tick(400)
        surfaceFor("plugin1")
        tick(400)
        assertStatic(runner.state.screen)
        return runner.state.screen
    }

    private fun closeCollapsesOntoTheItemAndEndsOnAStaticHome(mode: HomeMode) {
        openApp(mode)
        val item = Rect(bounds("plugin1"))
        intent(HudIntent.Dismiss)
        assertTrue(runner.state.screen is HudScreen.Home)
        assertEquals(View.GONE, host.app.visibility)
        assertEquals(safe, panel.frameForTest)
        clock.advance(0)
        var last = Rect(safe)
        repeat(20) {
            clock.advance(16)
            val frame = panel.frameForTest
            assertTrue("no overshoot: $frame", safe.contains(frame) && frame.contains(item))
            assertTrue("only shrinks: $frame after $last", last.contains(frame))
            last = Rect(frame)
        }
        assertEquals(item, last)
        assertStatic(runner.state.screen)
    }

    @Test fun list_close_collapses_onto_the_row() = closeCollapsesOntoTheItemAndEndsOnAStaticHome(HomeMode.LIST)

    @Test fun grid_close_collapses_onto_the_tile() = closeCollapsesOntoTheItemAndEndsOnAStaticHome(HomeMode.GRID)

    @Test
    fun a_selection_move_during_the_close_ends_it_at_once_and_the_scroll_follows_the_new_selection() {
        openApp(HomeMode.LIST)
        intent(HudIntent.Dismiss)
        tick(90)
        assertTrue(panel.visibility == View.VISIBLE)
        intent(HudIntent.Next)
        assertStatic(runner.state.screen)
        assertEquals("plugin2", (runner.state.screen as HudScreen.Home).selectedId)
        tick(400)
        assertStatic(runner.state.screen)
    }

    @Test
    fun back_during_the_close_hides_the_host_with_no_leftover_dimming() {
        openApp(HomeMode.GRID)
        intent(HudIntent.Dismiss)
        tick(90)
        intent(HudIntent.Dismiss)
        assertEquals(HudScreen.Hidden, runner.state.screen)
        assertFalse(host.isAttached)
        assertStatic(HudScreen.Hidden)
        tick(400)
        assertStatic(HudScreen.Hidden)
    }

    @Test
    fun back_during_the_open_morph_collapses_and_a_camera_launch_mid_morph_is_instant() {
        setUp(HomeMode.LIST)
        intent(HudIntent.Select)
        tick(90)
        runner.dispatch(HudEvent.ExternalStarted(ExternalKind.NATIVE_APP))
        layOut()
        assertEquals(HudScreen.External(ExternalKind.NATIVE_APP, Origin.HIDDEN), runner.state.screen)
        assertStatic(HudScreen.Hidden)
    }

    @Test
    fun the_launcher_over_a_surface_opens_with_no_panel() {
        openApp(HomeMode.LIST)
        intent(HudIntent.OpenLauncher(LauncherTrigger.BROADCAST_TOGGLE))
        assertEquals(View.VISIBLE, home.visibility)
        assertEquals(View.VISIBLE, host.app.visibility)
        assertEquals(View.GONE, panel.visibility)
        intent(HudIntent.Select)
        assertEquals(View.GONE, panel.visibility)
        assertFalse(host.morphForTest.isActive)
    }

    // ---- no callback reaches the machine ---------------------------------------------------

    @Test
    fun playing_out_every_animation_produces_no_effect_and_no_state_change() {
        openApp(HomeMode.GRID)
        intent(HudIntent.Dismiss)
        intent(HudIntent.Next)
        intent(HudIntent.Select)
        val effectsBefore = effects.size
        val stateBefore = runner.state
        tick(2_000)
        clock.play(2_000)
        assertEquals(effectsBefore, effects.size)
        assertEquals(stateBefore, runner.state)
    }

    // ---- reduced motion --------------------------------------------------------------------

    @Test
    fun reduced_motion_lands_every_transition_on_its_end_state_with_no_intermediate_frame() {
        reduced = true
        setUp(HomeMode.LIST)
        intent(HudIntent.Select)
        assertEquals(safe, panel.frameForTest)
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(HudMorph.DIM_ALPHA, home.alpha, 0.001f)
        assertFalse("no frame requested", clock.hasPendingFrame)

        surfaceFor("plugin0")
        assertStatic(runner.state.screen)

        intent(HudIntent.Dismiss)
        assertStatic(runner.state.screen)
        assertTrue(runner.state.screen is HudScreen.Home)
        assertFalse(clock.hasPendingFrame)
    }

    @Test
    fun the_panel_never_uses_more_than_the_tokens_allow() {
        setUp(HomeMode.LIST)
        intent(HudIntent.Select)
        tick(400)
        // At rest the panel is Panel style: 1 px line, radius-panel; the item chrome is gone.
        assertEquals(1f, panel.chromeMixForTest, 0f)
        assertEquals(RokidHudTokens.CONTENT_WIDTH, panel.frameForTest.width())
    }
}
