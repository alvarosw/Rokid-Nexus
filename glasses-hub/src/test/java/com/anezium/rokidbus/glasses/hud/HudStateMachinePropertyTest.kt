package com.anezium.rokidbus.glasses.hud

import com.anezium.rokidbus.glasses.hud.HudEffect.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Random event sequences; every invariant is checked after every single step. */
class HudStateMachinePropertyTest {
    private val ids = listOf("a", "b", "c", CAMERA_ENTRY_ID)
    private val surfaces = listOf("a", "b", "b:1", "x", "y")

    private fun randomEvent(r: Random, state: HudState): HudEvent {
        fun <T> pick(l: List<T>) = l[r.nextInt(l.size)]
        val tokens = listOfNotNull(state.deadlineToken, 0L, 99L)
        return when (r.nextInt(22)) {
            0, 1 -> HudEvent.Intent(HudIntent.Next)
            2 -> HudEvent.Intent(HudIntent.Prev)
            3, 4 -> HudEvent.Intent(HudIntent.Select)
            5, 6 -> HudEvent.Intent(HudIntent.Dismiss)
            7 -> HudEvent.Intent(HudIntent.OpenLauncher(pick(LauncherTrigger.values().toList())))
            8 -> HudEvent.Intent(HudIntent.Raw(RawKey(pick(listOf(4, 19, 23, 83, 85, HudKeys.PROG_BLUE)), r.nextBoolean(), r.nextInt(3))))
            9 -> HudEvent.LauncherEntriesChanged(List(r.nextInt(5)) { pick(ids) })
            10 -> HudEvent.ModeChanged(pick(HomeMode.values().toList()))
            11, 12 -> HudEvent.SurfaceShown(
                pick(surfaces), pick(listOf("a", "b", "x", null)), pick(DisplayPath.values().toList()),
                r.nextBoolean(), r.nextBoolean(),
            )
            13 -> HudEvent.SurfaceInfoChanged(pick(surfaces), r.nextBoolean(), r.nextBoolean())
            14, 15 -> HudEvent.SurfaceHidden(pick(surfaces))
            16 -> HudEvent.ExternalStarted(pick(ExternalKind.values().toList()))
            17 -> HudEvent.ExternalEnded(pick(ExternalKind.values().toList()))
            18 -> HudEvent.OpenFailed(pick(tokens), pick(OpenFailure.values().toList()))
            19 -> HudEvent.DeadlineElapsed(pick(tokens))
            20 -> HudEvent.NoticeOwnsRingChanged(r.nextBoolean())
            else -> if (r.nextInt(4) == 0) HudEvent.ServiceDestroyed else HudEvent.ServiceConnected
        }
    }

    private fun hostAttached(s: HudScreen) =
        s is HudScreen.Home || s is HudScreen.Opening || s is HudScreen.App

    @Test
    fun invariants_hold_for_random_sequences() {
        val machine = HudStateMachine(HudConfig())
        for (seed in 1L..400L) {
            val r = Random(seed)
            var state = HudState()
            var now = 0L
            var host = false
            var scheduled: Long? = null
            repeat(80) { step ->
                now += r.nextInt(3_000)
                val event = randomEvent(r, state)
                val before = state
                val t = machine.reduce(state, event, now)
                val fx = t.effects
                val after = t.state
                val ctx = "seed=$seed step=$step event=$event before=${before.screen} after=${after.screen} fx=$fx"

                // Pure and deterministic.
                assertEquals(ctx, t, machine.reduce(before, event, now))

                // Ring focus: derived, and emitted exactly on change (or on reconnect republish).
                assertEquals(ctx, after.ringFocus(), after.ringFocusPublished)
                val pubs = fx.filterIsInstance<PublishRingFocus>()
                assertTrue(ctx, pubs.size <= 1)
                val changed = before.ringFocusPublished != after.ringFocusPublished
                // A (re)connect republishes a true focus even when it did not change (F-25).
                if (event == HudEvent.ServiceConnected) assertEquals(ctx, after.ringFocusPublished, pubs.isNotEmpty())
                else assertEquals(ctx, changed, pubs.isNotEmpty())
                pubs.firstOrNull()?.let { assertEquals(ctx, after.ringFocusPublished, it.focused) }

                // A launcher open is only ever sent by Select in Home with a selection.
                val sends = fx.filter { it is SendLauncherOpen || it is StartCamera }
                if (sends.isNotEmpty()) {
                    assertTrue(ctx, before.screen is HudScreen.Home)
                    assertEquals(ctx, HudEvent.Intent(HudIntent.Select), event)
                    assertTrue(ctx, after.screen is HudScreen.Opening)
                    assertEquals(ctx, 1, sends.size)
                }

                // PassToSystem: Hidden only (or PROG_BLUE), and only for input.
                if (PassToSystem in fx) {
                    val prog = event is HudEvent.Intent && event.intent is HudIntent.Raw &&
                        (event.intent as HudIntent.Raw).key.keyCode == HudKeys.PROG_BLUE
                    assertTrue(ctx, prog || before.screen == HudScreen.Hidden)
                    assertTrue(ctx, event is HudEvent.Intent)
                }
                if (fx.any { it is PassToExternal }) {
                    assertTrue(ctx, (before.screen as HudScreen.External).kind != ExternalKind.ACTIVITY_SURFACE)
                }

                // Exclusive input: nothing is forwarded while the launcher is up or opening.
                if (before.screen is HudScreen.Home || before.screen is HudScreen.Opening) {
                    assertTrue(ctx, fx.none { it is ForwardToApp })
                }
                if (fx.any { it is ForwardToApp }) assertTrue(ctx, event is HudEvent.Intent)

                // Hidden stays quiet: only pass-through, ring and host bookkeeping may follow.
                if (before.screen == HudScreen.Hidden && after.screen == HudScreen.Hidden) {
                    assertTrue(ctx, fx.all { it is PassToSystem || it is PublishRingFocus || it is CancelDeadline })
                }
                // Dismiss outside Hidden is never handed to the ROM.
                if (event == HudEvent.Intent(HudIntent.Dismiss) && before.screen != HudScreen.Hidden) {
                    assertTrue(ctx, PassToSystem !in fx)
                }

                // Host attach state follows the screen and is never redundant.
                for (e in fx) {
                    if (e == AttachHost) { assertTrue(ctx, !host); host = true }
                    if (e == DetachHost) { assertTrue(ctx, host); host = false }
                }
                assertEquals(ctx, hostAttached(after.screen), host)

                // One deadline at most, always the current screen's own.
                fx.filterIsInstance<ScheduleDeadline>().lastOrNull()?.let { scheduled = it.token }
                if (CancelDeadline in fx) scheduled = null
                val expected = when (val sc = after.screen) {
                    is HudScreen.Opening -> sc.openToken
                    is HudScreen.App -> sc.backToken
                    is HudScreen.External -> sc.backToken
                    else -> null
                }
                assertEquals(ctx, expected, after.deadlineToken)
                if (expected != null) assertEquals(ctx, expected, scheduled)

                // Selection stays a valid id whenever a launcher is up.
                val home = when (val sc = after.screen) {
                    is HudScreen.Home -> sc
                    is HudScreen.Opening -> sc.home
                    else -> null
                }
                if (home != null) {
                    if (after.entries.isEmpty()) assertEquals(ctx, null, home.selectedId)
                    else assertTrue(ctx, home.selectedId in after.entries)
                }
                assertEquals(ctx, after.entries.distinct(), after.entries)

                // Nothing is drawn while the service is down.
                if (!after.serviceConnected) assertEquals(ctx, HudScreen.Hidden, after.screen)

                state = after
            }
        }
    }
}
