package com.anezium.rokidbus.glasses.hud

import com.anezium.rokidbus.glasses.hud.HudEffect.*
import com.anezium.rokidbus.glasses.hud.HudScreen.App
import com.anezium.rokidbus.glasses.hud.HudScreen.External
import com.anezium.rokidbus.glasses.hud.HudScreen.Hidden
import com.anezium.rokidbus.glasses.hud.HudScreen.Home
import com.anezium.rokidbus.glasses.hud.HudScreen.Opening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the machine with an explicit clock and records every effect. */
internal class Harness(
    config: HudConfig = HudConfig(),
    entries: List<String> = listOf("a", "b", "c"),
    connected: Boolean = true,
) {
    private val machine = HudStateMachine(config)
    var state = HudState()
    var now = 0L
    val log = ArrayList<HudEffect>()

    init {
        if (connected) send(HudEvent.ServiceConnected)
        send(HudEvent.LauncherEntriesChanged(entries))
        log.clear()
    }

    val screen get() = state.screen

    fun send(event: HudEvent, at: Long = now): List<HudEffect> {
        now = at
        val t = machine.reduce(state, event, now)
        state = t.state
        log += t.effects
        return t.effects
    }

    fun intent(i: HudIntent, at: Long = now) = send(HudEvent.Intent(i), at)
    fun open(trigger: LauncherTrigger = LauncherTrigger.TRIPLE_TAP) = intent(HudIntent.OpenLauncher(trigger))
    fun next() = intent(HudIntent.Next)
    fun prev() = intent(HudIntent.Prev)
    fun select(at: Long = now) = intent(HudIntent.Select, at)
    fun dismiss(at: Long = now) = intent(HudIntent.Dismiss, at)
    fun raw(code: Int) = intent(HudIntent.Raw(RawKey(code)))

    fun shown(
        id: String,
        owner: String? = id,
        path: DisplayPath = DisplayPath.OVERLAY,
        handlesBack: Boolean = false,
        editable: Boolean = false,
        at: Long = now,
    ) = send(HudEvent.SurfaceShown(id, owner, path, handlesBack, editable), at)

    fun hidden(id: String) = send(HudEvent.SurfaceHidden(id))

    /** Select entry [id] from a fresh launcher and return its open token. */
    fun openEntry(id: String): Long {
        if (screen !is Home) open()
        while ((screen as Home).selectedId != id) next()
        select()
        return (screen as Opening).openToken
    }

    fun home(): Home = screen as Home
    fun app(): App = screen as App
}

class HudStateMachineTest {

    // ---- 7.2 launcher: opening, closing -----------------------------------------------------

    @Test
    fun item_18_triple_tap_opens_home_from_hidden() {
        val h = Harness()
        val fx = h.open()
        assertEquals(Home(HomeMode.LIST, "a"), h.screen)
        assertEquals(
            listOf(AttachHost, ShowHome(HomeMode.LIST, "a", listOf("a", "b", "c")), PublishRingFocus(true)),
            fx,
        )
    }

    @Test
    fun item_22_triple_tap_while_home_is_open_changes_nothing() {
        val h = Harness()
        h.open()
        h.log.clear()
        val fx = h.open(LauncherTrigger.TRIPLE_TAP)
        assertTrue(fx.isEmpty())
        assertTrue(h.screen is Home)
        assertTrue(h.open(LauncherTrigger.APP_ICON).isEmpty())
    }

    @Test
    fun item_23_triple_tap_is_ignored_over_an_editable_surface() {
        val h = Harness()
        h.shown("s1", editable = true)
        val fx = h.open(LauncherTrigger.TRIPLE_TAP)
        assertTrue(fx.isEmpty())
        assertTrue(h.screen is App)
        // The other triggers are not guarded at HEAD.
        h.open(LauncherTrigger.BROADCAST_TOGGLE)
        assertTrue(h.screen is Home)
    }

    @Test
    fun item_24_broadcast_toggles_and_a_dead_service_ignores_it() {
        val h = Harness()
        h.open(LauncherTrigger.BROADCAST_TOGGLE)
        assertTrue(h.screen is Home)
        h.open(LauncherTrigger.BROADCAST_TOGGLE)
        assertEquals(Hidden, h.screen)
        h.open(LauncherTrigger.BROADCAST_TOGGLE)
        h.send(HudEvent.ServiceDestroyed)
        val fx = h.open(LauncherTrigger.BROADCAST_TOGGLE)
        assertTrue(fx.isEmpty())
        assertEquals(Hidden, h.screen)
    }

    @Test
    fun item_25_dismiss_closes_home_and_never_reaches_the_system() {
        val h = Harness()
        h.open()
        h.log.clear()
        val fx = h.dismiss()
        assertEquals(Hidden, h.screen)
        assertEquals(listOf(DetachHost, PublishRingFocus(false)), fx)
        assertFalse(PassToSystem in fx)
    }

    @Test
    fun item_28_home_consumes_every_raw_key_except_prog_blue() {
        val h = Harness()
        h.open()
        h.log.clear()
        for (code in listOf(4, 23, 85, 87, 88, 83, 19, 24)) {
            assertTrue("key $code", h.raw(code).isEmpty())
        }
        assertEquals(listOf<HudEffect>(PassToSystem), h.raw(HudKeys.PROG_BLUE)) // item 106
        assertTrue(h.screen is Home)
    }

    @Test
    fun item_32_selection_wraps_both_ways_and_empty_list_does_not_move() {
        val h = Harness()
        h.open()
        h.prev()
        assertEquals("c", h.home().selectedId)
        h.next()
        assertEquals("a", h.home().selectedId)
        val e = Harness(entries = emptyList())
        e.open()
        assertNull(e.home().selectedId)
        assertTrue(e.next().isEmpty())
        assertTrue(e.select().isEmpty())
        assertTrue(e.screen is Home)
    }

    @Test
    fun item_33_selection_persists_across_close_and_open() {
        val h = Harness()
        h.open()
        h.next(); h.next()
        h.dismiss()
        h.open()
        assertEquals("c", h.home().selectedId)
    }

    @Test
    fun item_34_selection_survives_a_reorder() {
        val h = Harness()
        h.open()
        h.next() // b
        val fx = h.send(HudEvent.LauncherEntriesChanged(listOf("c", "b", "a")))
        assertEquals("b", h.home().selectedId)
        assertEquals(listOf<HudEffect>(RefreshHomeEntries(listOf("c", "b", "a"), "b")), fx)
    }

    @Test
    fun item_34_removed_selection_falls_back_to_the_next_survivor_then_the_previous() {
        val h = Harness(entries = listOf("a", "b", "c", "d"))
        h.open(); h.next() // b
        h.send(HudEvent.LauncherEntriesChanged(listOf("a", "c", "d")))
        assertEquals("c", h.home().selectedId)
        // c and d go: nothing after it survives, so the nearest before it does.
        h.send(HudEvent.LauncherEntriesChanged(listOf("a")))
        assertEquals("a", h.home().selectedId)
        h.send(HudEvent.LauncherEntriesChanged(listOf("x", "y")))
        assertEquals("x", h.home().selectedId)
        h.send(HudEvent.LauncherEntriesChanged(emptyList()))
        assertNull(h.home().selectedId)
        h.send(HudEvent.LauncherEntriesChanged(listOf("p", "q")))
        assertEquals("p", h.home().selectedId)
    }

    @Test
    fun item_34_selection_is_kept_by_id_while_hidden() {
        val h = Harness()
        h.open(); h.next(); h.dismiss()
        h.send(HudEvent.LauncherEntriesChanged(listOf("z", "c", "b")))
        h.open()
        assertEquals("b", h.home().selectedId)
        h.dismiss()
        h.send(HudEvent.LauncherEntriesChanged(listOf("z", "c")))
        h.open()
        assertEquals("c", h.home().selectedId)
    }

    @Test
    fun item_34_duplicate_entries_are_collapsed() {
        val h = Harness(entries = listOf("a", "a", "b"))
        assertEquals(listOf("a", "b"), h.state.entries)
    }

    @Test
    fun item_45_mode_change_applies_on_the_next_open_only() {
        val h = Harness()
        h.open()
        assertEquals(HomeMode.LIST, h.home().mode)
        assertTrue(h.send(HudEvent.ModeChanged(HomeMode.GRID)).isEmpty())
        assertEquals(HomeMode.LIST, h.home().mode)
        h.dismiss()
        h.open()
        assertEquals(HomeMode.GRID, h.home().mode)
    }

    // ---- 7.2 / 7.4 opening a plugin ---------------------------------------------------------

    @Test
    fun item_35_select_sends_launcher_open_and_home_stays_up() {
        val h = Harness()
        h.open()
        h.next()
        h.log.clear()
        val fx = h.select()
        val o = h.screen as Opening
        assertEquals("b", o.pluginId)
        assertEquals(10_000L, o.deadline)
        assertEquals(
            listOf(ShowOpening("b"), SendLauncherOpen("b", o.openToken), ScheduleDeadline(o.openToken, 10_000L)),
            fx,
        )
        // F-1: no Nexus-less gap: the host is not detached and focus is not released.
        assertFalse(DetachHost in fx)
        assertFalse(PublishRingFocus(false) in fx)
        assertTrue(h.state.ringFocus())
    }

    @Test
    fun item_37_open_failure_returns_home_with_the_same_selection_and_a_status() {
        val h = Harness()
        h.open(); h.next()
        val token = h.openEntry("b")
        val fx = h.send(HudEvent.OpenFailed(token, OpenFailure.REJECTED)) // F-2
        assertEquals(Home(HomeMode.LIST, "b"), h.screen)
        assertTrue(ShowStatus(HudStatus.OpenFailed("b", OpenFailure.REJECTED)) in fx)
        assertTrue(CancelDeadline in fx)
        assertEquals(null, h.state.deadlineToken)
    }

    @Test
    fun item_37_stale_open_failure_is_ignored() {
        val h = Harness()
        val t = h.openEntry("a")
        h.shown("a")
        assertTrue(h.send(HudEvent.OpenFailed(t)).isEmpty())
        assertTrue(h.screen is App)
    }

    @Test
    fun item_38_camera_entry_starts_the_camera_without_a_launcher_open() {
        val h = Harness(entries = listOf("camera", "a"))
        h.open()
        val fx = h.select()
        val o = h.screen as Opening
        assertTrue(StartCamera(o.openToken) in fx)
        assertTrue(fx.none { it is SendLauncherOpen })
        val started = h.send(HudEvent.ExternalStarted(ExternalKind.CAMERA))
        assertEquals(External(ExternalKind.CAMERA, Origin.HOME), h.screen)
        assertTrue(DetachHost in started)
        assertTrue(CancelDeadline in started)
        // The camera process is not a Nexus owner: no ring focus for it (HEAD).
        assertFalse(h.state.ringFocus())
    }

    @Test
    fun item_38_F5_camera_end_returns_to_the_launcher_it_was_opened_from() {
        val h = Harness(entries = listOf("camera", "a"))
        h.open(); h.select()
        h.send(HudEvent.ExternalStarted(ExternalKind.CAMERA))
        val fx = h.send(HudEvent.ExternalEnded(ExternalKind.CAMERA))
        assertEquals(Home(HomeMode.LIST, "camera"), h.screen)
        assertEquals(AttachHost, fx.first())
    }

    @Test
    fun item_38_camera_start_failure_keeps_the_launcher_open() {
        val h = Harness(entries = listOf("camera", "a"))
        h.open()
        h.select()
        val t = (h.screen as Opening).openToken
        h.send(HudEvent.OpenFailed(t, OpenFailure.SEND_FAILED))
        assertEquals(Home(HomeMode.LIST, "camera"), h.screen)
    }

    @Test
    fun item_38_back_in_camera_belongs_to_the_camera() {
        val h = Harness(entries = listOf("camera"))
        h.open(); h.select()
        h.send(HudEvent.ExternalStarted(ExternalKind.CAMERA))
        h.log.clear()
        assertEquals(listOf<HudEffect>(PassToExternal(ExternalKind.CAMERA)), h.dismiss())
        assertFalse(PassToSystem in h.log)
    }

    // ---- 7.4 handoff and return -------------------------------------------------------------

    @Test
    fun item_66_matching_show_completes_the_open_and_claims_the_return() {
        val h = Harness()
        h.openEntry("b")
        val fx = h.shown("b:main", owner = "b")
        assertEquals(App(SurfaceInfo("b:main", "b"), Origin.HOME), h.screen)
        assertEquals(listOf<HudEffect>(ShowApp("b:main"), CancelDeadline), fx)
        assertTrue(h.state.ringFocus())
    }

    @Test
    fun item_66_without_an_owner_the_surface_id_prefix_rule_matches() {
        val h = Harness()
        h.openEntry("b")
        h.shown("b:1", owner = null)
        assertEquals(Origin.HOME, h.app().origin)
        val g = Harness()
        g.openEntry("b")
        g.shown("bb", owner = null)
        assertEquals(Origin.HIDDEN, g.app().origin)
    }

    @Test
    fun item_66_a_show_for_another_plugin_takes_the_screen_without_a_return() {
        val h = Harness()
        h.openEntry("b")
        h.shown("x", owner = "x")
        assertEquals(Origin.HIDDEN, h.app().origin)
        assertNull(h.state.deadlineToken)
        h.hidden("x")
        assertEquals(Hidden, h.screen)
    }

    @Test
    fun item_67_the_claim_is_consumed_by_the_first_hide() {
        val h = Harness()
        h.openEntry("b")
        h.shown("b")
        h.hidden("b")
        assertTrue(h.screen is Home)
        h.dismiss()
        h.shown("b")
        // Not opened from the launcher this time.
        assertEquals(Origin.HIDDEN, h.app().origin)
    }

    @Test
    fun item_69_F3_pending_open_expires_and_a_late_show_claims_no_return() {
        val h = Harness()
        val t = h.openEntry("b")
        val fx = h.send(HudEvent.DeadlineElapsed(t), at = 10_000)
        assertEquals(Home(HomeMode.LIST, "b"), h.screen)
        assertTrue(ShowStatus(HudStatus.OpenFailed("b", OpenFailure.TIMEOUT)) in fx)
        assertFalse(CancelDeadline in fx) // the timer already fired
        h.dismiss()
        h.shown("b")
        assertEquals(Origin.HIDDEN, h.app().origin)
    }

    @Test
    fun item_69_a_deadline_with_a_stale_token_is_ignored() {
        val h = Harness()
        val t = h.openEntry("b")
        h.shown("b")
        assertTrue(h.send(HudEvent.DeadlineElapsed(t), at = 10_000).isEmpty())
        assertTrue(h.screen is App)
        val g = Harness()
        val t2 = g.openEntry("b")
        assertTrue(g.send(HudEvent.DeadlineElapsed(t2 + 99)).isEmpty())
        assertTrue(g.screen is Opening)
    }

    @Test
    fun item_69_F3_a_claim_does_not_survive_a_replacement_by_another_plugin() {
        val h = Harness()
        h.openEntry("b")
        h.shown("b")
        h.shown("x", owner = "x")
        assertEquals(Origin.HIDDEN, h.app().origin)
        h.hidden("x")
        assertEquals(Hidden, h.screen)
        // The same plugin replacing its own surface keeps the return.
        val g = Harness()
        g.openEntry("b"); g.shown("b:1", owner = "b"); g.shown("b:2", owner = "b")
        assertEquals(Origin.HOME, g.app().origin)
    }

    @Test
    fun item_70_hide_and_dismiss_of_a_launcher_opened_surface_return_to_home() {
        val h = Harness()
        h.openEntry("b")
        h.shown("b")
        val fx = h.hidden("b")
        assertEquals(Home(HomeMode.LIST, "b"), h.screen)
        assertEquals(listOf<HudEffect>(ShowHome(HomeMode.LIST, "b", listOf("a", "b", "c"))), fx)

        val g = Harness()
        g.openEntry("b"); g.shown("b")
        val d = g.dismiss()
        assertEquals(Home(HomeMode.LIST, "b"), g.screen)
        assertEquals(listOf(CloseApp("b", CloseReason.WEARER_DISMISSED), ShowHome(HomeMode.LIST, "b", listOf("a", "b", "c"))), d)
        assertFalse(AttachHost in d) // one persistent host
        assertFalse(DetachHost in d)
    }

    @Test
    fun item_70_return_uses_the_mode_configured_at_that_moment() {
        val h = Harness()
        h.openEntry("a"); h.shown("a")
        h.send(HudEvent.ModeChanged(HomeMode.GRID))
        h.hidden("a")
        assertEquals(HomeMode.GRID, h.home().mode)
    }

    @Test
    fun item_71_an_unsolicited_surface_hides_back_to_hidden() {
        val h = Harness()
        h.shown("s")
        assertEquals(Origin.HIDDEN, h.app().origin)
        val d = h.dismiss()
        assertEquals(Hidden, h.screen)
        assertEquals(listOf(CloseApp("s", CloseReason.WEARER_DISMISSED), DetachHost, PublishRingFocus(false)), d)
        assertFalse(PassToSystem in d)
    }

    @Test
    fun item_72_metadata_updates_never_complete_a_handoff() {
        val h = Harness()
        h.openEntry("b")
        assertTrue(h.send(HudEvent.SurfaceInfoChanged("b", handlesBack = true, editable = false)).isEmpty())
        assertTrue(h.screen is Opening)
    }

    @Test
    fun item_73_app_icon_uses_the_same_launcher_and_return() {
        val h = Harness()
        h.open(LauncherTrigger.APP_ICON)
        h.select()
        h.shown("a")
        assertEquals(Origin.HOME, h.app().origin)
    }

    @Test
    fun item_74_ring_focus_is_the_union_and_publishes_only_on_edges() {
        val h = Harness()
        assertTrue(h.send(HudEvent.NoticeOwnsRingChanged(true)) == listOf<HudEffect>(PublishRingFocus(true)))
        assertTrue(h.open().none { it is PublishRingFocus })
        assertTrue(h.dismiss().none { it is PublishRingFocus }) // notice still owns it
        assertEquals(listOf<HudEffect>(PublishRingFocus(false)), h.send(HudEvent.NoticeOwnsRingChanged(false)))
        assertTrue(h.send(HudEvent.NoticeOwnsRingChanged(false)).isEmpty())
    }

    @Test
    fun item_75_76_focus_is_held_through_the_handoff_and_released_by_the_deadline() {
        val h = Harness()
        val t = h.openEntry("b")
        assertTrue(h.log.none { it == PublishRingFocus(false) })
        h.send(HudEvent.DeadlineElapsed(t), at = 10_000)
        assertTrue(h.state.ringFocus()) // Home is still up
        h.dismiss()
        assertEquals(PublishRingFocus(false), h.log.last())
    }

    @Test
    fun item_77_service_destroy_releases_everything_and_focus() {
        val h = Harness()
        h.open()
        val fx = h.send(HudEvent.ServiceDestroyed)
        assertEquals(Hidden, h.screen)
        assertTrue(PublishRingFocus(false) in fx)
        assertTrue(DetachHost in fx)
    }

    @Test
    fun item_13_14_15_reconnect_restores_an_overlay_surface_but_not_the_launcher() {
        val h = Harness()
        h.openEntry("b"); h.shown("b")
        h.send(HudEvent.ServiceDestroyed)
        assertEquals(Hidden, h.screen)
        val fx = h.send(HudEvent.ServiceConnected)
        assertEquals(App(SurfaceInfo("b", "b"), Origin.HOME), h.screen)
        assertEquals(listOf<HudEffect>(AttachHost, ShowApp("b"), PublishRingFocus(true)), fx)

        val g = Harness()
        g.open()
        g.send(HudEvent.ServiceDestroyed)
        g.send(HudEvent.ServiceConnected)
        assertEquals(Hidden, g.screen)
    }

    @Test
    fun item_15_F25_focus_is_republished_on_reconnect_and_a_surface_hidden_meanwhile_is_forgotten() {
        val h = Harness()
        h.shown("s")
        h.send(HudEvent.ServiceDestroyed)
        h.hidden("s")
        h.send(HudEvent.ServiceConnected)
        assertEquals(Hidden, h.screen)

        val g = Harness()
        g.send(HudEvent.NoticeOwnsRingChanged(true))
        g.send(HudEvent.ServiceDestroyed)
        val fx = g.send(HudEvent.ServiceConnected)
        assertEquals(listOf<HudEffect>(PublishRingFocus(true)), fx)
    }

    @Test
    fun item_15_a_surface_shown_while_the_service_is_down_appears_on_connect() {
        val h = Harness(connected = false)
        assertTrue(h.shown("s").isEmpty())
        val fx = h.send(HudEvent.ServiceConnected)
        assertTrue(ShowApp("s") in fx)
    }

    @Test
    fun item_80_activity_path_handoff_steps_the_host_aside() {
        val h = Harness()
        h.openEntry("b")
        val fx = h.shown("b", path = DisplayPath.ACTIVITY, editable = true)
        assertEquals(External(ExternalKind.ACTIVITY_SURFACE, Origin.HOME, SurfaceInfo("b", "b", editable = true)), h.screen)
        assertTrue(DetachHost in fx)
        assertTrue(ShowActivitySurface("b") in fx)
        assertTrue(h.state.ringFocus())
        // Editable-card keys are the surface's: forwarded, not passed.
        val k = h.raw(29)
        assertEquals(listOf<HudEffect>(ForwardToApp("b", HudIntent.Raw(RawKey(29)))), k)
        // Hiding returns to the launcher and re-attaches the host.
        val back = h.hidden("b")
        assertEquals(AttachHost, back.first())
        assertTrue(h.screen is Home)
    }

    @Test
    fun item_85_F21_a_surface_moving_between_paths_keeps_its_origin() {
        val h = Harness()
        h.openEntry("b"); h.shown("b")
        val fx = h.shown("b", path = DisplayPath.ACTIVITY)
        assertEquals(Origin.HOME, (h.screen as External).origin)
        assertEquals(listOf<HudEffect>(ShowActivitySurface("b"), DetachHost), fx)
        h.shown("b", path = DisplayPath.OVERLAY)
        assertEquals(Origin.HOME, h.app().origin)
        // Same path again is an in-place update.
        assertTrue(h.shown("b").isEmpty())
    }

    @Test
    fun item_88_native_app_launch_steps_nexus_aside_and_returns_to_hidden() {
        val h = Harness()
        h.open()
        val fx = h.send(HudEvent.ExternalStarted(ExternalKind.NATIVE_APP))
        assertEquals(External(ExternalKind.NATIVE_APP, Origin.HIDDEN), h.screen)
        assertTrue(DetachHost in fx)
        assertEquals(listOf<HudEffect>(PassToExternal(ExternalKind.NATIVE_APP)), h.next())
        assertFalse(h.state.ringFocus())
        h.send(HudEvent.ExternalEnded(ExternalKind.NATIVE_APP))
        assertEquals(Hidden, h.screen)

        val g = Harness()
        g.openEntry("a"); g.shown("a")
        val f = g.send(HudEvent.ExternalStarted(ExternalKind.NATIVE_APP))
        assertTrue(CloseApp("a", CloseReason.SUPERSEDED) in f)
        assertEquals(External(ExternalKind.NATIVE_APP, Origin.HIDDEN), g.screen)
    }

    // ---- 7.4 / 7.6 BACK on a surface --------------------------------------------------------

    @Test
    fun item_112_back_on_a_handles_back_surface_forwards_and_arms_the_failsafe() {
        val h = Harness()
        h.shown("s", handlesBack = true)
        val fx = h.dismiss(at = 100)
        val token = h.app().backToken!!
        assertEquals(
            listOf<HudEffect>(ForwardToApp("s", HudIntent.Dismiss), ScheduleDeadline(token, 1_600)),
            fx,
        )
        assertTrue(h.screen is App)
        // A second BACK is forwarded again but does not re-arm.
        assertEquals(listOf<HudEffect>(ForwardToApp("s", HudIntent.Dismiss)), h.dismiss(at = 200))
        val done = h.send(HudEvent.DeadlineElapsed(token), at = 1_600)
        assertEquals(Hidden, h.screen)
        assertEquals(CloseApp("s", CloseReason.BACK_FAILSAFE), done.first())
    }

    @Test
    fun item_112_the_plugin_hiding_itself_cancels_the_failsafe() {
        val h = Harness()
        h.shown("s", handlesBack = true)
        h.dismiss()
        val fx = h.hidden("s")
        assertEquals(Hidden, h.screen)
        assertTrue(CancelDeadline in fx)
    }

    @Test
    fun item_112_an_update_of_the_surface_is_the_answer_and_disarms_the_failsafe() {
        val h = Harness()
        h.shown("s", handlesBack = true)
        h.dismiss(at = 100)
        val token = h.app().backToken!!
        val fx = h.send(HudEvent.SurfaceInfoChanged("s", handlesBack = true, editable = false), at = 200)
        assertEquals(listOf<HudEffect>(CancelDeadline), fx)
        assertNull(h.app().backToken)
        // The stale deadline that still fires later closes nothing.
        assertTrue(h.send(HudEvent.DeadlineElapsed(token), at = 1_600).none { it is CloseApp })
        assertTrue(h.screen is App)
    }

    @Test
    fun item_112_a_reshow_of_the_same_surface_disarms_the_failsafe() {
        val h = Harness()
        h.shown("s", handlesBack = true)
        h.dismiss(at = 100)
        val fx = h.shown("s", handlesBack = true)
        assertTrue(CancelDeadline in fx)
        assertNull(h.app().backToken)
    }

    @Test
    fun item_111_surface_keys_and_intents_are_forwarded_not_passed() {
        val h = Harness()
        h.shown("s")
        h.log.clear()
        h.next(); h.prev(); h.select(); h.raw(62)
        assertEquals(
            listOf<HudEffect>(
                ForwardToApp("s", HudIntent.Next),
                ForwardToApp("s", HudIntent.Prev),
                ForwardToApp("s", HudIntent.Select),
                ForwardToApp("s", HudIntent.Raw(RawKey(62))),
            ),
            h.log,
        )
    }

    // ---- 7.6 / 7.7 who owns input -----------------------------------------------------------

    @Test
    fun item_119_F14_home_over_a_surface_owns_input_exclusively() {
        val h = Harness()
        h.shown("s")
        h.open()
        assertEquals(App(SurfaceInfo("s", "s"), Origin.HIDDEN), (h.screen as Home).beneath)
        h.log.clear()
        h.next(); h.prev(); h.raw(83); h.raw(66); h.dismiss()
        assertTrue(h.log.none { it is ForwardToApp })
        // Dismissing the launcher returns to the surface, which gets input again.
        assertTrue(h.screen is App)
        h.log.clear()
        h.raw(66)
        assertEquals(listOf<HudEffect>(ForwardToApp("s", HudIntent.Raw(RawKey(66)))), h.log)
    }

    @Test
    fun item_119_selecting_over_a_surface_opens_and_the_new_surface_replaces_it() {
        val h = Harness()
        h.shown("s", owner = "s")
        h.open()
        h.select() // a
        h.shown("a")
        assertEquals(App(SurfaceInfo("a", "a"), Origin.HOME), h.screen)
    }

    @Test
    fun item_119_a_failed_open_over_a_surface_keeps_the_surface_beneath() {
        val h = Harness()
        h.shown("s")
        h.open()
        h.select()
        val t = (h.screen as Opening).openToken
        h.send(HudEvent.OpenFailed(t))
        h.dismiss()
        assertEquals("s", h.app().surfaceId)
    }

    @Test
    fun item_119_the_surface_beneath_ending_does_not_close_the_launcher() {
        val h = Harness()
        h.shown("s")
        h.open()
        h.hidden("s")
        assertEquals(Home(HomeMode.LIST, "a", null), h.screen)
        h.dismiss()
        assertEquals(Hidden, h.screen)
    }

    @Test
    fun item_119_an_unsolicited_surface_steps_the_launcher_aside_without_a_return() {
        val h = Harness()
        h.open()
        val fx = h.shown("x", owner = "x")
        assertEquals(Origin.HIDDEN, h.app().origin)
        assertEquals(listOf<HudEffect>(ShowApp("x")), fx) // host stays, home layer hides
        h.hidden("x")
        assertEquals(Hidden, h.screen)
    }

    @Test
    fun item_119_an_update_style_show_of_the_surface_beneath_does_not_step_aside() {
        val h = Harness()
        h.shown("s")
        h.open()
        assertTrue(h.shown("s").isEmpty())
        assertTrue(h.screen is Home)
    }

    @Test
    fun item_121_hidden_passes_every_key_to_the_system() {
        val h = Harness()
        for (i in listOf(HudIntent.Next, HudIntent.Prev, HudIntent.Select, HudIntent.Dismiss, HudIntent.Raw(RawKey(85)))) {
            assertEquals(listOf<HudEffect>(PassToSystem), h.intent(i))
        }
    }

    @Test
    fun item_106_prog_blue_is_passed_in_every_state() {
        val h = Harness()
        h.open()
        assertEquals(listOf<HudEffect>(PassToSystem), h.raw(HudKeys.PROG_BLUE))
        h.shown("s")
        assertEquals(listOf<HudEffect>(PassToSystem), h.raw(HudKeys.PROG_BLUE))
    }

    @Test
    fun item_184_keys_in_hidden_belong_to_the_rom_so_a_second_back_is_only_passed_through() {
        val h = Harness()
        h.open()
        assertEquals(listOf<HudEffect>(DetachHost, PublishRingFocus(false)), h.dismiss(at = 0))
        assertEquals(Hidden, h.screen)
        // Immediately, and long after: the second BACK is never swallowed or delayed.
        assertEquals(listOf<HudEffect>(PassToSystem), h.dismiss(at = 10))
        assertEquals(listOf<HudEffect>(PassToSystem), h.dismiss(at = 60_000))
        assertEquals(Hidden, h.screen)
    }

    // ---- no animation states: events mid-transition -----------------------------------------

    @Test
    fun F9_back_during_opening_cancels_it_and_a_second_select_sends_nothing() {
        val h = Harness()
        h.openEntry("b")
        h.log.clear()
        assertTrue(h.select().isEmpty())
        assertTrue(h.next().isEmpty())
        assertEquals("b", (h.screen as Opening).home.selectedId)
        val fx = h.dismiss()
        assertEquals(Home(HomeMode.LIST, "b"), h.screen)
        assertTrue(fx.none { it is SendLauncherOpen })
        assertTrue(CancelDeadline in fx)
        assertFalse(PassToSystem in fx)
        assertTrue(h.state.ringFocus())
        // The next BACK closes the launcher, still ours.
        assertFalse(PassToSystem in h.dismiss())
        assertEquals(Hidden, h.screen)
    }

    @Test
    fun F9_dismiss_during_opening_returns_to_the_same_home_over_the_same_surface() {
        val h = Harness()
        h.shown("s")
        h.open()
        h.next()
        h.select()
        h.dismiss()
        assertEquals(Home(HomeMode.LIST, "b", App(SurfaceInfo("s", "s"), Origin.HIDDEN)), h.screen)
    }

    @Test
    fun item_69_F3_a_late_show_for_a_cancelled_open_is_closed_unseen() {
        val h = Harness()
        val t = h.openEntry("b")
        h.dismiss(at = 500)
        assertTrue(h.send(HudEvent.DeadlineElapsed(t), at = 600).isEmpty())
        val fx = h.shown("b")
        assertEquals(listOf<HudEffect>(CloseApp("b", CloseReason.OPEN_CANCELLED)), fx)
        // The launcher the wearer went back to is untouched and the ring stays ours.
        assertEquals(Home(HomeMode.LIST, "b"), h.screen)
        assertTrue(fx.none { it is AttachHost || it is ShowApp || it is ShowActivitySurface })
        assertTrue(h.state.ringFocus())
    }

    @Test
    fun a_late_show_is_closed_after_the_wearer_left_the_launcher_too() {
        val h = Harness()
        h.openEntry("b")
        h.dismiss(at = 500)
        h.dismiss(at = 900)
        assertEquals(Hidden, h.screen)
        val fx = h.shown("b:late", owner = "b", at = 2_500)
        assertEquals(listOf<HudEffect>(CloseApp("b:late", CloseReason.OPEN_CANCELLED)), fx)
        assertEquals(Hidden, h.screen)
        assertFalse(AttachHost in fx)
    }

    @Test
    fun a_cancelled_open_also_closes_an_activity_path_show() {
        val h = Harness()
        h.openEntry("b")
        h.dismiss(at = 500)
        val fx = h.shown("b", path = DisplayPath.ACTIVITY, at = 2_500)
        assertEquals(listOf<HudEffect>(CloseApp("b", CloseReason.OPEN_CANCELLED)), fx)
    }

    @Test
    fun a_show_of_another_plugin_is_not_swallowed_by_a_cancelled_open() {
        val h = Harness()
        h.openEntry("b")
        h.dismiss(at = 500)
        h.shown("x", owner = "x", at = 2_500)
        assertEquals(Origin.HIDDEN, h.app().origin)
    }

    @Test
    fun item_69_F3_a_show_after_the_original_deadline_is_unsolicited_as_before() {
        val h = Harness()
        h.openEntry("b")
        h.dismiss(at = 500)
        h.dismiss(at = 900)
        h.shown("b", at = 10_000)
        assertEquals(Origin.HIDDEN, h.app().origin)
        assertNull(h.state.cancelledOpen)
    }

    @Test
    fun opening_the_plugin_again_makes_its_show_the_answer_not_a_late_one() {
        val h = Harness()
        h.openEntry("b")
        h.dismiss(at = 500)
        h.select(at = 700)
        assertNull(h.state.cancelledOpen)
        h.shown("b", at = 900)
        assertEquals(Origin.HOME, h.app().origin)
    }

    @Test
    fun a_late_show_of_the_surface_already_beneath_the_launcher_is_an_update_not_a_close() {
        val h = Harness()
        h.shown("b")
        h.open()
        h.next()
        h.select()
        h.dismiss(at = 500)
        val fx = h.shown("b", at = 900)
        assertTrue(fx.none { it is CloseApp })
        assertEquals(Home(HomeMode.LIST, "b", App(SurfaceInfo("b", "b"), Origin.HIDDEN)), h.screen)
    }

    @Test
    fun a_late_show_is_closed_while_another_plugin_is_opening() {
        val h = Harness()
        h.openEntry("b")
        h.dismiss(at = 500)
        h.next()
        h.select(at = 700)
        val fx = h.shown("b", at = 900)
        assertEquals(listOf<HudEffect>(CloseApp("b", CloseReason.OPEN_CANCELLED)), fx)
        assertTrue(h.screen is Opening)
    }

    @Test
    fun F1_opening_toggle_from_the_broadcast_also_cancels() {
        val h = Harness()
        h.openEntry("b")
        h.open(LauncherTrigger.BROADCAST_TOGGLE)
        assertEquals(Hidden, h.screen)
    }

    @Test
    fun entries_arriving_during_opening_update_the_home_it_will_return_to() {
        val h = Harness()
        val t = h.openEntry("b")
        h.send(HudEvent.LauncherEntriesChanged(listOf("c", "b")))
        h.send(HudEvent.OpenFailed(t))
        assertEquals(Home(HomeMode.LIST, "b"), h.screen)
    }

    @Test
    fun a_new_open_after_a_failed_one_uses_a_fresh_token() {
        val h = Harness()
        val t1 = h.openEntry("a")
        h.send(HudEvent.OpenFailed(t1))
        h.select()
        val t2 = (h.screen as Opening).openToken
        assertTrue(t2 != t1)
        assertTrue(h.send(HudEvent.DeadlineElapsed(t1)).isEmpty())
    }
}
