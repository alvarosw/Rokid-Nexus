package com.anezium.rokidbus.glasses.hud

import com.anezium.rokidbus.glasses.hud.DeviceClass.KEYBOARD_DPAD
import com.anezium.rokidbus.glasses.hud.DeviceClass.OTHER
import com.anezium.rokidbus.glasses.hud.DeviceClass.R08
import com.anezium.rokidbus.glasses.hud.DeviceClass.TOUCHPAD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BACK = 4
private const val ENTER = 66
private const val CENTER = 23
private const val UP = 19
private const val DOWN = 20
private const val LEFT = 21
private const val RIGHT = 22
private const val CONTACT = 83
private const val TAP = 85
private const val FWD = 87
private const val BWD = 88

private class FakeContext : HudInputContext {
    var owner = InputOwner.NONE
    var activityIdle = false
    var activityHasActions = false
    var noticeOwnsRing = false
    val noticeRingKeys = HashSet<Int>()
    val noticeGeneric = HashSet<Int>()
    val noticeSeen = ArrayList<RawKeyEvent>()

    override fun owner() = owner
    override fun activityIdle() = activityIdle
    override fun activityHasActions() = activityHasActions
    override fun noticeOwnsRing() = noticeOwnsRing
    override fun noticeClaimsRing(keyCode: Int) = keyCode in noticeRingKeys
    override fun noticeHandlesKey(event: RawKeyEvent): Boolean {
        noticeSeen += event
        return event.keyCode in noticeGeneric && !event.isUp
    }
}

private class Rig {
    val ctx = FakeContext()
    val input = HudInput(ctx)
    var now = 1_000L

    fun key(code: Int, action: Int, device: DeviceClass, at: Long = now, repeat: Int = 0): HudInputResult {
        now = at
        return input.onKey(RawKeyEvent(code, action, repeat, at, device))
    }

    fun down(code: Int, device: DeviceClass, at: Long = now, repeat: Int = 0) =
        key(code, RawKeyEvent.ACTION_DOWN, device, at, repeat)

    fun up(code: Int, device: DeviceClass, at: Long = now) = key(code, RawKeyEvent.ACTION_UP, device, at)

    fun intents(r: HudInputResult) = r.intents.map { it.intent }
}

private fun raw(code: Int, down: Boolean = true, repeat: Int = 0) = HudIntent.Raw(RawKey(code, down, repeat))

class HudInputRingTest {
    private fun rig(owner: InputOwner = InputOwner.LAUNCHER) = Rig().also { it.ctx.owner = owner }

    // ---- 01 §7.7 ----

    @Test fun item120_only_an_R08_device_selects_the_ring_pipeline() {
        val r = rig()
        // The same keycode from a non-R08 device is a launcher no-op on the generic path.
        val generic = r.down(TAP, KEYBOARD_DPAD)
        assertTrue(generic.consumed); assertEquals(emptyList<HudIntent>(), r.intents(generic))
        r.up(TAP, KEYBOARD_DPAD)
        assertEquals(emptyList<RoutedIntent>(), r.input.onTick(r.now + 400))
        // From the ring it is a tap.
        r.down(TAP, R08, r.now + 1_000); r.up(TAP, R08)
        assertEquals(listOf(HudIntent.Select), r.input.onTick(r.now + 400).map { it.intent })
    }

    @Test fun item121_ring_keys_pass_when_nothing_owns_them() {
        val r = rig(InputOwner.NONE)
        for (k in listOf(TAP, FWD, BWD)) {
            val d = r.down(k, R08); assertFalse(d.consumed); assertTrue(d.intents.isEmpty())
            assertFalse(r.up(k, R08).consumed)
        }
    }

    @Test fun item122_ring_precedence_notice_claim_then_notice_noop_then_launcher_then_surface_then_activity() {
        val r = rig(InputOwner.LAUNCHER)
        r.ctx.activityIdle = true; r.ctx.activityHasActions = true
        // Claimed by the band, drawn over the launcher.
        r.ctx.noticeOwnsRing = true; r.ctx.noticeRingKeys += FWD
        var d = r.down(FWD, R08, 1_000)
        assertEquals(listOf(RoutedIntent(HudIntent.Next, InputTarget.NOTICE)), d.intents)
        // Owned but unclaimed: swallowed, no intent, the launcher below does not move.
        d = r.down(BWD, R08, 1_010)
        assertTrue(d.consumed); assertTrue(d.intents.isEmpty())
        // Band gone: launcher.
        r.ctx.noticeOwnsRing = false; r.ctx.noticeRingKeys.clear()
        assertEquals(listOf(RoutedIntent(HudIntent.Prev, InputTarget.HUD)), r.down(BWD, R08, 1_020, repeat = 0).intents)
        // Surface next, activity last.
        r.ctx.owner = InputOwner.SURFACE
        assertEquals(InputTarget.HUD, r.down(FWD, R08, 1_030).intents.single().target)
        r.ctx.owner = InputOwner.NONE
        assertEquals(RoutedIntent(HudIntent.Next, InputTarget.ACTIVITY), r.down(FWD, R08, 1_040).intents.single())
    }

    @Test fun item123_ring_up_repeat_and_non_down_are_consumed_without_action() {
        val r = rig()
        val d = r.down(FWD, R08, 1_000)
        assertEquals(listOf(HudIntent.Next), r.intents(d))
        for (n in 1..5) {
            val rep = r.down(FWD, R08, 1_000L + n * 50, repeat = n)
            assertTrue(rep.consumed); assertTrue(rep.intents.isEmpty())   // R4
        }
        val up = r.up(FWD, R08, 1_400)
        assertTrue(up.consumed); assertTrue(up.intents.isEmpty())
        // An UP with no consumed DOWN that reaches an owner is still consumed.
        assertTrue(r.up(BWD, R08, 1_500).consumed)
    }

    @Test fun item124_taps_resolve_after_the_window_to_select_dismiss_ignore() {
        val r = rig()
        r.down(TAP, R08, 1_000); r.up(TAP, R08, 1_050)
        assertEquals(1_351L, r.input.nextDeadlineMs())
        assertTrue(r.input.onTick(1_350).isEmpty())
        assertEquals(listOf(HudIntent.Select), r.input.onTick(1_351).map { it.intent })
        assertNull(r.input.nextDeadlineMs())

        r.down(TAP, R08, 2_000); r.down(TAP, R08, 2_200)
        assertEquals(listOf(HudIntent.Dismiss), r.input.onTick(2_551).map { it.intent })

        r.down(TAP, R08, 3_000); r.down(TAP, R08, 3_100); r.down(TAP, R08, 3_200)
        assertTrue(r.input.onTick(4_000).isEmpty())
    }

    @Test fun R5_taps_340ms_apart_are_a_double_and_360ms_apart_are_two_singles() {
        val r = rig()
        r.down(TAP, R08, 1_000)
        assertTrue(r.down(TAP, R08, 1_340).intents.isEmpty())
        assertEquals(listOf(HudIntent.Dismiss), r.input.onTick(1_691).map { it.intent })

        // No timer ran: the next tap settles the previous one first, so it never becomes a double.
        r.down(TAP, R08, 2_000)
        val second = r.down(TAP, R08, 2_360)
        assertEquals(listOf(HudIntent.Select), r.intents(second))
        assertEquals(listOf(HudIntent.Select), r.input.onTick(2_711).map { it.intent })
    }

    @Test fun R5_exactly_350ms_is_still_inside_the_window() {
        val r = rig()
        r.down(TAP, R08, 1_000)
        assertTrue(r.down(TAP, R08, 1_350).intents.isEmpty())
        assertEquals(listOf(HudIntent.Dismiss), r.input.onTick(1_701).map { it.intent })
    }

    @Test fun item126_ring_double_tap_is_dismiss_for_the_surface() {
        val r = rig(InputOwner.SURFACE)
        r.down(TAP, R08, 1_000); r.down(TAP, R08, 1_200)
        assertEquals(listOf(RoutedIntent(HudIntent.Dismiss, InputTarget.HUD)), r.input.onTick(1_551))
    }

    @Test fun item127_reader_ring_scroll_is_deduped_at_150ms() {
        val r = rig(InputOwner.READER)
        assertEquals(listOf(HudIntent.Next), r.intents(r.down(FWD, R08, 1_000)))
        assertTrue(r.down(FWD, R08, 1_100).intents.isEmpty())
        assertEquals(listOf(HudIntent.Next), r.intents(r.down(FWD, R08, 1_300)))
        assertEquals(listOf(HudIntent.Prev), r.intents(r.down(BWD, R08, 1_310)))
    }

    @Test fun item128_notice_tap_is_targeted_at_the_notice_and_unclaimed_keys_are_a_noop() {
        val r = rig(InputOwner.NONE)
        r.ctx.noticeOwnsRing = true; r.ctx.noticeRingKeys += TAP
        r.down(TAP, R08, 1_000)
        assertEquals(listOf(RoutedIntent(HudIntent.Select, InputTarget.NOTICE)), r.input.onTick(1_351))
        r.down(TAP, R08, 2_000); r.down(TAP, R08, 2_100)
        assertEquals(listOf(RoutedIntent(HudIntent.Dismiss, InputTarget.NOTICE)), r.input.onTick(2_451))
        val swallowed = r.down(FWD, R08, 3_000)
        assertTrue(swallowed.consumed); assertTrue(swallowed.intents.isEmpty())
    }

    @Test fun item129_cancel_drops_a_pending_tap() {
        val r = rig()
        r.down(TAP, R08, 1_000)
        r.input.cancelPendingRingTaps()
        assertTrue(r.input.onTick(2_000).isEmpty())
        assertNull(r.input.nextDeadlineMs())
    }

    @Test fun item130_activity_tap_target_is_fixed_by_the_first_tap() {
        val r = rig(InputOwner.NONE)
        r.ctx.activityIdle = true
        r.down(TAP, R08, 1_000)
        // A notice appears mid-window and claims the second tap: a new sequence for the notice, the
        // activity tap is not upgraded into a double answer for it.
        r.ctx.noticeOwnsRing = true; r.ctx.noticeRingKeys += TAP
        r.down(TAP, R08, 1_100)
        assertEquals(listOf(RoutedIntent(HudIntent.Select, InputTarget.NOTICE)), r.input.onTick(1_451))
    }

    @Test fun activity_ring_directions_need_actions_but_the_tap_is_always_claimed() {
        val r = rig(InputOwner.NONE)
        r.ctx.activityIdle = true
        assertFalse(r.down(FWD, R08, 1_000).consumed)
        assertTrue(r.down(TAP, R08, 1_100).consumed)
        assertEquals(listOf(RoutedIntent(HudIntent.Select, InputTarget.ACTIVITY)), r.input.onTick(1_451))
        r.ctx.activityHasActions = true
        assertEquals(RoutedIntent(HudIntent.Next, InputTarget.ACTIVITY), r.down(FWD, R08, 2_000).intents.single())
    }

    // ---- HARDWARE R3 ----

    @Test fun R3_orphan_up_is_consumed_after_the_owner_disappears() {
        val r = rig()
        assertTrue(r.down(TAP, R08, 1_000).consumed)
        r.ctx.owner = InputOwner.NONE                     // the tap's own action hid the launcher
        val orphan = r.up(TAP, R08, 1_060)
        assertTrue("an ENTER-like UP must never reach the ROM", orphan.consumed)
        // The debt is paid once: a later UP with no DOWN passes when nobody owns keys.
        assertFalse(r.up(TAP, R08, 2_000).consumed)
    }

    @Test fun R3_orphan_up_generic_pipeline_after_owner_change() {
        val r = rig()
        assertTrue(r.down(ENTER, TOUCHPAD, 1_000).consumed)
        r.ctx.owner = InputOwner.NONE
        assertTrue(r.up(ENTER, TOUCHPAD, 1_050).consumed)
        assertFalse(r.up(ENTER, TOUCHPAD, 1_100).consumed)
    }

    @Test fun R3_ring_and_generic_debts_do_not_cross() {
        val r = rig()
        assertTrue(r.down(TAP, R08, 1_000).consumed)
        r.ctx.owner = InputOwner.NONE
        // A keyboard's MEDIA_PLAY_PAUSE UP is not the ring's.
        assertFalse(r.up(TAP, KEYBOARD_DPAD, 1_010).consumed)
        assertTrue(r.up(TAP, R08, 1_020).consumed)
    }

    @Test fun R3_a_passed_down_leaves_no_debt() {
        val r = rig(InputOwner.NONE)
        assertFalse(r.down(ENTER, OTHER, 1_000).consumed)
        assertFalse(r.up(ENTER, OTHER, 1_050).consumed)
    }

    @Test fun R3_a_missing_up_cannot_swallow_the_up_of_a_later_press() {
        val r = rig()
        assertTrue(r.down(ENTER, TOUCHPAD, 1_000).consumed)      // its UP never arrives
        r.ctx.owner = InputOwner.NONE
        // Much later a press whose DOWN passed through: its UP is not owed to the lost one.
        assertFalse(r.down(ENTER, TOUCHPAD, 30_000).consumed)
        assertFalse(r.up(ENTER, TOUCHPAD, 30_050).consumed)
    }

    @Test fun R3_a_held_key_keeps_its_debt_alive_through_repeats() {
        val r = rig()
        assertTrue(r.down(ENTER, TOUCHPAD, 1_000).consumed)
        for (n in 1..12) r.down(ENTER, TOUCHPAD, 1_000L + n * 1_000L, repeat = n)
        r.ctx.owner = InputOwner.NONE
        assertTrue(r.up(ENTER, TOUCHPAD, 13_100).consumed)
    }

    @Test fun R3_notice_consumed_down_owes_its_up_even_when_the_notice_is_gone() {
        val r = rig(InputOwner.NONE)
        r.ctx.noticeGeneric += ENTER
        assertTrue(r.down(ENTER, TOUCHPAD, 1_000).consumed)
        r.ctx.noticeGeneric.clear()
        assertTrue(r.up(ENTER, TOUCHPAD, 1_100).consumed)
        assertFalse(r.down(ENTER, TOUCHPAD, 2_000).consumed)   // a different press is not consumed
    }

    @Test fun R6_prog_blue_is_never_consumed_or_tracked_on_any_source() {
        val r = rig(InputOwner.LAUNCHER)
        for (d in DeviceClass.values()) {
            assertFalse(r.down(HudKeys.PROG_BLUE, d, 1_000 + d.ordinal * 10L).consumed)
            assertFalse(r.up(HudKeys.PROG_BLUE, d, 1_005 + d.ordinal * 10L).consumed)
        }
        assertNull(r.input.nextDeadlineMs())
    }

    @Test fun hook_position_notice_is_asked_before_the_owner_chain_and_never_for_ring_generic() {
        val r = rig(InputOwner.LAUNCHER)
        r.ctx.noticeGeneric += ENTER
        val d = r.down(ENTER, TOUCHPAD, 1_000)
        assertTrue(d.consumed); assertTrue("the notice took it, the launcher must not select", d.intents.isEmpty())
        r.down(FWD, R08, 2_000)
        assertTrue(r.ctx.noticeSeen.none { it.deviceClass == R08 })
    }
}

class HudInputGenericTest {
    private fun rig(owner: InputOwner = InputOwner.LAUNCHER) = Rig().also { it.ctx.owner = owner }

    // ---- 01 §7.6 ----

    @Test fun item105_generic_order_notice_launcher_surface_activity_pass() {
        val r = rig(InputOwner.LAUNCHER)
        r.ctx.activityIdle = true
        r.ctx.noticeGeneric += ENTER
        assertTrue(r.intents(r.down(ENTER, KEYBOARD_DPAD, 1_000)).isEmpty())    // notice
        r.up(ENTER, KEYBOARD_DPAD, 1_010)
        assertEquals(listOf(HudIntent.Select), r.intents(r.down(CENTER, KEYBOARD_DPAD, 2_000)))   // launcher
        r.up(CENTER, KEYBOARD_DPAD, 2_010)
        r.ctx.noticeGeneric.clear()
        r.ctx.owner = InputOwner.SURFACE
        assertEquals(listOf(raw(ENTER)), r.intents(r.down(ENTER, KEYBOARD_DPAD, 3_000)))          // surface
        r.up(ENTER, KEYBOARD_DPAD, 3_010)
        r.ctx.owner = InputOwner.NONE
        assertEquals(listOf(HudIntent.Select), r.intents(r.down(ENTER, KEYBOARD_DPAD, 4_000)))    // activity
        assertEquals(InputTarget.ACTIVITY, r.down(ENTER, KEYBOARD_DPAD, 4_010).intents.single().target)
        r.up(ENTER, KEYBOARD_DPAD, 4_020)
        r.ctx.activityIdle = false
        assertFalse(r.down(ENTER, KEYBOARD_DPAD, 5_000).consumed)                                  // pass
    }

    @Test fun item106_prog_blue_passes_even_over_the_launcher() {
        val r = rig(InputOwner.LAUNCHER)
        val d = r.down(HudKeys.PROG_BLUE, TOUCHPAD)
        assertFalse(d.consumed); assertTrue(d.intents.isEmpty())
    }

    @Test fun item107_consumed_down_consumes_its_up_even_if_the_consumer_is_gone() {
        val r = rig(InputOwner.SURFACE)
        assertTrue(r.down(SPACE, KEYBOARD_DPAD).consumed)
        r.ctx.owner = InputOwner.NONE
        assertTrue(r.up(SPACE, KEYBOARD_DPAD, r.now + 30).consumed)
    }

    @Test fun item113_surface_dpad_duplicates_are_suppressed_with_their_up() {
        val r = rig(InputOwner.SURFACE)
        assertEquals(listOf(raw(RIGHT)), r.intents(r.down(RIGHT, TOUCHPAD, 1_000)))
        val dup = r.down(RIGHT, TOUCHPAD, 1_050)                                   // T3: pairs 20-80 ms apart
        assertTrue(dup.consumed); assertTrue(dup.intents.isEmpty())
        assertTrue(r.up(RIGHT, TOUCHPAD, 1_060).consumed)
        val secondUp = r.up(RIGHT, TOUCHPAD, 1_070)      // interleaved D D U U: both UPs are owed
        assertTrue(secondUp.consumed); assertTrue(secondUp.intents.isEmpty())
        assertEquals(listOf(raw(RIGHT)), r.intents(r.down(RIGHT, TOUCHPAD, 1_250)))   // a real second swipe
    }

    @Test fun T3_swipe_pairs_at_20_50_80ms_count_once_and_opposite_directions_do_not_dedupe() {
        for (gap in listOf(20L, 50L, 80L)) {
            val r = rig()
            assertEquals(listOf(HudIntent.Next), r.intents(r.down(RIGHT, TOUCHPAD, 1_000)))
            assertTrue(r.down(RIGHT, TOUCHPAD, 1_000 + gap).intents.isEmpty())
            assertEquals(listOf(HudIntent.Next), r.intents(r.down(DOWN, TOUCHPAD, 1_400)))     // DOWN == forward too
            assertTrue(r.down(DOWN, TOUCHPAD, 1_400 + gap).intents.isEmpty())
        }
        val r = rig()
        r.down(RIGHT, TOUCHPAD, 1_000)
        assertEquals(listOf(HudIntent.Prev), r.intents(r.down(LEFT, TOUCHPAD, 1_030)))
    }

    @Test fun item114_reader_scrolls_on_directions_and_media_but_forwards_enter_center_back() {
        val r = rig(InputOwner.READER)
        assertEquals(listOf(HudIntent.Next), r.intents(r.down(DOWN, TOUCHPAD, 1_000)))
        assertEquals(listOf(HudIntent.Prev), r.intents(r.down(UP, TOUCHPAD, 1_300)))
        assertEquals(listOf(HudIntent.Next), r.intents(r.down(FWD, KEYBOARD_DPAD, 1_400)))
        assertEquals(listOf(HudIntent.Prev), r.intents(r.down(BWD, KEYBOARD_DPAD, 1_410)))
        assertEquals(listOf(raw(ENTER)), r.intents(r.down(ENTER, TOUCHPAD, 1_500)))
        assertEquals(listOf(raw(CENTER)), r.intents(r.down(CENTER, TOUCHPAD, 1_510)))
        assertEquals(listOf(HudIntent.Dismiss), r.intents(r.down(BACK, TOUCHPAD, 1_520)))
    }

    @Test fun reader_leaves_space_and_media_play_pause_to_the_system() {
        val r = rig(InputOwner.READER)
        for ((i, k) in listOf(SPACE, TAP).withIndex()) {
            val t = 1_000L + i * 300
            assertFalse(k.toString(), r.down(k, KEYBOARD_DPAD, t).consumed)
            assertFalse(k.toString(), r.up(k, KEYBOARD_DPAD, t + 20).consumed)
        }
        assertTrue(r.down(ENTER, KEYBOARD_DPAD, 2_000).consumed)
        assertTrue(r.up(ENTER, KEYBOARD_DPAD, 2_020).consumed)
    }

    @Test fun item111_surface_forwards_its_key_set_and_passes_the_rest() {
        val r = rig(InputOwner.SURFACE)
        var t = 1_000L
        for (k in listOf(ENTER, CENTER, SPACE, TAP, FWD, BWD)) {
            val d = r.down(k, KEYBOARD_DPAD, t); t += 300
            assertTrue(k.toString(), d.consumed)
            assertEquals(listOf(raw(k)), r.intents(d))
        }
        for (k in listOf(CONTACT, 24, 25, 82)) assertFalse(r.down(k, KEYBOARD_DPAD, t++).consumed)
    }

    @Test fun item112_surface_back_down_is_dismiss_once_and_repeats_are_silent() {
        val r = rig(InputOwner.SURFACE)
        assertEquals(listOf(HudIntent.Dismiss), r.intents(r.down(BACK, TOUCHPAD, 1_000)))
        val rep = r.down(BACK, TOUCHPAD, 1_100, repeat = 1)
        assertTrue(rep.consumed); assertTrue(rep.intents.isEmpty())
    }

    @Test fun launcher_maps_and_consumes_every_key_including_unmapped_ones() {
        val r = rig()
        assertEquals(listOf(HudIntent.Dismiss), r.intents(r.down(BACK, TOUCHPAD, 1_000)))
        assertEquals(listOf(HudIntent.Select), r.intents(r.down(ENTER, TOUCHPAD, 1_010)))
        val noop = r.down(24, KEYBOARD_DPAD, 1_020)
        assertTrue(noop.consumed); assertTrue(noop.intents.isEmpty())
        val rep = r.down(ENTER, TOUCHPAD, 1_030, repeat = 2)
        assertTrue(rep.consumed); assertTrue(rep.intents.isEmpty())
        assertTrue(r.up(24, KEYBOARD_DPAD, 1_040).consumed)
    }

    @Test fun item132_ring_keycodes_from_a_non_R08_device_are_launcher_noops() {
        val r = rig()
        for (k in listOf(TAP, FWD, BWD)) {
            val d = r.down(k, OTHER, 1_000L + k)
            assertTrue(d.consumed); assertTrue(d.intents.isEmpty())
        }
        assertNull(r.input.nextDeadlineMs())
    }

    @Test fun editable_card_passes_everything_but_back_and_never_triggers() {
        val r = rig(InputOwner.EDITABLE_SURFACE)
        for (k in listOf(ENTER, CENTER, RIGHT, LEFT, 29)) {
            assertFalse(r.down(k, KEYBOARD_DPAD, 1_000L + k).consumed)
            assertFalse(r.up(k, KEYBOARD_DPAD, 1_000L + k).consumed)
        }
        assertEquals(listOf(HudIntent.Dismiss), r.intents(r.down(BACK, KEYBOARD_DPAD, 2_000)))
        r.up(BACK, KEYBOARD_DPAD, 2_010)
        // T5: three contacts in 500 ms while a field is focused.
        for (t in listOf(3_000L, 3_250L, 3_500L)) {
            val d = r.down(CONTACT, TOUCHPAD, t)
            assertFalse(d.consumed); assertTrue(d.intents.isEmpty())
        }
        assertNull(r.input.nextDeadlineMs())
    }

    @Test fun unowned_back_reaches_the_machine_for_the_b1_guard_and_is_not_consumed_by_input() {
        val r = rig(InputOwner.NONE)
        val d = r.down(BACK, TOUCHPAD, 1_000)
        assertFalse(d.consumed); assertEquals(listOf(HudIntent.Dismiss), r.intents(d))
        // The machine answered SwallowBack: the service tells input so the UP is swallowed too.
        r.input.noteConsumedDown(RawKeyEvent(BACK, RawKeyEvent.ACTION_DOWN, 0, 1_000, TOUCHPAD))
        assertTrue(r.up(BACK, TOUCHPAD, 1_050).consumed)
    }

    @Test fun activity_generic_83_is_consumed_directions_need_actions_enter_fires() {
        val r = rig(InputOwner.NONE)
        r.ctx.activityIdle = true
        assertTrue(r.down(CONTACT, TOUCHPAD, 1_000).consumed)
        assertTrue(r.up(CONTACT, TOUCHPAD, 1_010).consumed)
        assertFalse(r.down(RIGHT, TOUCHPAD, 1_100).consumed)
        r.ctx.activityHasActions = true
        assertEquals(RoutedIntent(HudIntent.Next, InputTarget.ACTIVITY), r.down(RIGHT, TOUCHPAD, 1_300).intents.single())
        assertEquals(RoutedIntent(HudIntent.Select, InputTarget.ACTIVITY), r.down(ENTER, TOUCHPAD, 1_500).intents.single())
        assertFalse(r.down(BACK, TOUCHPAD, 1_600).consumed)
    }

    // ---- triple tap (T2) ----

    private fun contacts(r: Rig, vararg at: Long): List<HudInputResult> =
        at.map { r.down(CONTACT, TOUCHPAD, it).also { _ -> r.up(CONTACT, TOUCHPAD, it + 20) } }

    @Test fun T2_three_contacts_at_0_250_500_trigger_and_open_the_launcher() {
        val r = rig(InputOwner.NONE)
        val res = contacts(r, 1_000, 1_250, 1_500)
        assertTrue(res[0].intents.isEmpty()); assertTrue(res[1].intents.isEmpty())
        assertEquals(listOf(HudIntent.OpenLauncher(LauncherTrigger.TRIPLE_TAP)), r.intents(res[2]))
        assertTrue(res[2].consumed)
        assertNull("the trigger cancels the flush", r.input.nextDeadlineMs())
    }

    @Test fun T2_window_edges_600_triggers_601_does_not() {
        var r = rig(InputOwner.NONE)
        assertEquals(1, contacts(r, 1_000, 1_300, 1_600).count { it.intents.isNotEmpty() })
        r = rig(InputOwner.NONE)
        assertEquals(0, contacts(r, 1_000, 1_300, 1_601).count { it.intents.isNotEmpty() })
        r = rig(InputOwner.NONE)
        assertEquals(0, contacts(r, 1_000, 1_250, 1_700).count { it.intents.isNotEmpty() })
    }

    @Test fun T2_classifications_are_swallowed_for_800ms_and_pass_at_801() {
        val r = rig(InputOwner.NONE)
        contacts(r, 1_000, 1_250, 1_500)
        val trigger = 1_500L
        assertTrue(r.down(ENTER, TOUCHPAD, trigger + 800).consumed)
        assertTrue(r.up(ENTER, TOUCHPAD, trigger + 800).consumed)
        assertTrue(r.down(BACK, TOUCHPAD, trigger + 700).consumed)
        assertFalse("801 ms passes", r.down(ENTER, TOUCHPAD, trigger + 801).consumed)
    }

    @Test fun T2_three_fast_swipes_never_trigger() {
        val r = rig(InputOwner.NONE)
        var t = 1_000L
        repeat(3) {
            assertTrue(r.down(CONTACT, TOUCHPAD, t).intents.isEmpty())
            r.down(RIGHT, TOUCHPAD, t + 30); r.up(RIGHT, TOUCHPAD, t + 35)
            t += 150
        }
        assertNull(r.input.nextDeadlineMs())
    }

    @Test fun T2_trigger_works_while_the_launcher_is_already_shown() {
        val r = rig(InputOwner.LAUNCHER)
        val res = contacts(r, 1_000, 1_100, 1_200)
        assertEquals(listOf(HudIntent.OpenLauncher(LauncherTrigger.TRIPLE_TAP)), r.intents(res[2]))
    }

    // ---- T1 / item 118 ----

    @Test fun T1_a_contact_followed_by_a_swipe_pair_never_confirms() {
        val r = rig(InputOwner.LAUNCHER)
        assertTrue(r.down(CONTACT, TOUCHPAD, 1_000).intents.isEmpty())
        val swipe = r.down(RIGHT, TOUCHPAD, 1_400)
        assertEquals(listOf(HudIntent.Next), r.intents(swipe))
        assertTrue(r.input.onTick(3_000).isEmpty())
    }

    @Test fun T1_first_contact_is_consumed_by_launcher_and_activity_but_passes_over_a_surface() {
        var r = rig(InputOwner.LAUNCHER)
        assertTrue(r.down(CONTACT, TOUCHPAD, 1_000).consumed)
        r = rig(InputOwner.SURFACE)
        assertFalse(r.down(CONTACT, TOUCHPAD, 1_000).consumed)
    }

    @Test fun item118_expired_contacts_replay_at_most_two_to_the_surface_never_a_notice() {
        val r = rig(InputOwner.SURFACE)
        r.ctx.noticeOwnsRing = true
        r.down(CONTACT, TOUCHPAD, 1_000); r.down(CONTACT, TOUCHPAD, 1_200)
        assertEquals(1_801L, r.input.nextDeadlineMs())
        assertTrue(r.input.onTick(1_800).isEmpty())
        val flushed = r.input.onTick(1_801)
        assertEquals(List(2) { RoutedIntent(raw(CONTACT), InputTarget.HUD) }, flushed)
        assertNull(r.input.nextDeadlineMs())
        assertTrue(r.input.onTick(9_000).isEmpty())
    }

    @Test fun item118_expired_contact_goes_to_the_activity_when_no_surface() {
        val r = rig(InputOwner.NONE)
        r.ctx.activityIdle = true
        r.down(CONTACT, TOUCHPAD, 1_000)
        assertEquals(listOf(RoutedIntent(raw(CONTACT), InputTarget.ACTIVITY)), r.input.onTick(1_601))
    }

    @Test fun item119_launcher_over_a_surface_takes_expired_contacts_nothing_leaks_below() {
        val r = rig(InputOwner.LAUNCHER)
        r.down(CONTACT, TOUCHPAD, 1_000)
        assertTrue(r.input.onTick(1_601).isEmpty())
    }

    @Test fun a_non_contact_down_cancels_the_pending_flush() {
        val r = rig(InputOwner.SURFACE)
        r.down(CONTACT, TOUCHPAD, 1_000)
        r.down(ENTER, TOUCHPAD, 1_300)
        assertTrue(r.input.onTick(2_000).isEmpty())
    }

    @Test fun T4_two_taps_188ms_apart_reach_the_notice_hook_as_two_presses_the_notice_dedupes() {
        val r = rig(InputOwner.NONE)
        r.ctx.noticeGeneric += ENTER
        assertTrue(r.down(ENTER, TOUCHPAD, 1_000).consumed)
        assertTrue(r.up(ENTER, TOUCHPAD, 1_030).consumed)
        assertTrue(r.down(ENTER, TOUCHPAD, 1_188).consumed)
        // Both are consumed here; the single answer is NoticeController's `answered` flag (T4).
        assertEquals(2, r.ctx.noticeSeen.count { it.isDown })
    }

    @Test fun device_classifier_by_name_R1() {
        assertEquals(R08, HudKeyEventAdapter.classify("R08 Ring"))
        assertEquals(R08, HudKeyEventAdapter.classify("rokid r08 hid"))
        assertEquals(OTHER, HudKeyEventAdapter.classify(null))
        assertEquals(OTHER, HudKeyEventAdapter.classify("Virtual"))
        assertEquals(TOUCHPAD, HudKeyEventAdapter.classify("mtk-tpd touch"))
        assertEquals(KEYBOARD_DPAD, HudKeyEventAdapter.classify("Some Keyboard", 0x00000101))
        assertEquals(R08, HudKeyEventAdapter.classify("R08", 0x00000101))
    }

    /** What the debug receiver injects: an R08 sequence through the seam produces the ring intents. */
    @Test fun debug_seam_injected_R08_sequence_produces_intents() {
        val r = rig()
        val seen = ArrayList<RoutedIntent>()
        HudInputSeam.sink = { e -> seen += r.input.onKey(e).intents }
        try {
            val sink = HudInputSeam.sink!!
            sink(RawKeyEvent(FWD, RawKeyEvent.ACTION_DOWN, 0, 1_000, R08))
            sink(RawKeyEvent(FWD, RawKeyEvent.ACTION_UP, 0, 1_010, R08))
            sink(RawKeyEvent(TAP, RawKeyEvent.ACTION_DOWN, 0, 2_000, R08))
            sink(RawKeyEvent(TAP, RawKeyEvent.ACTION_UP, 0, 2_020, R08))
            seen += r.input.onTick(2_351)
        } finally {
            HudInputSeam.sink = null
        }
        assertEquals(listOf(HudIntent.Next, HudIntent.Select), seen.map { it.intent })
    }
}

private const val SPACE = 62
