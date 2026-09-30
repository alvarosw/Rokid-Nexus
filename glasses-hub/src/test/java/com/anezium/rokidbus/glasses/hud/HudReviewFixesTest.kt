package com.anezium.rokidbus.glasses.hud

import com.anezium.rokidbus.glasses.hud.HudEffect.*
import com.anezium.rokidbus.glasses.hud.HudScreen.Hidden
import com.anezium.rokidbus.glasses.hud.HudScreen.Home
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudReviewFixesTest {

    // ---- a launcher whose window cannot be added must not keep the keys ----------------------

    @Test
    fun a_failed_attach_of_the_home_recovers_to_hidden_and_releases_the_ring() {
        val h = Harness()
        h.open()
        assertTrue(h.state.ringFocus())
        val fx = h.send(HudEvent.HostAttachFailed)
        assertEquals(Hidden, h.screen)
        assertTrue(PublishRingFocus(false) in fx)
        assertTrue(DetachHost in fx)
        // Keys reach the system again instead of vanishing into an invisible launcher.
        assertEquals(listOf<HudEffect>(PassToSystem), h.next())
    }

    @Test
    fun a_failed_attach_while_opening_drops_the_pending_open() {
        val h = Harness()
        h.openEntry("b")
        val fx = h.send(HudEvent.HostAttachFailed)
        assertEquals(Hidden, h.screen)
        assertTrue(CancelDeadline in fx)
    }

    @Test
    fun a_failed_attach_over_foreign_content_returns_to_it() {
        val h = Harness()
        h.send(HudEvent.ExternalStarted(ExternalKind.NATIVE_APP))
        h.open()
        assertTrue(h.screen is Home)
        h.send(HudEvent.HostAttachFailed)
        assertEquals(HudScreen.External(ExternalKind.NATIVE_APP, Origin.HIDDEN), h.screen)
    }

    @Test
    fun a_stale_attach_failure_changes_nothing() {
        val h = Harness()
        h.shown("s1")
        assertTrue(h.send(HudEvent.HostAttachFailed).isEmpty())
        assertTrue(h.screen is HudScreen.App)
    }

    // ---- camera from a launcher opened over a surface ----------------------------------------

    @Test
    fun the_camera_started_from_a_launcher_over_a_surface_closes_that_surface() {
        val h = Harness(entries = listOf("camera", "a"))
        h.shown("s1")
        h.open()
        h.select()
        val fx = h.send(HudEvent.ExternalStarted(ExternalKind.CAMERA))
        assertTrue(CloseApp("s1", CloseReason.SUPERSEDED) in fx)
        assertEquals(HudScreen.External(ExternalKind.CAMERA, Origin.HOME), h.screen)
        h.send(HudEvent.ExternalEnded(ExternalKind.CAMERA))
        assertEquals(Home(HomeMode.LIST, "camera"), h.screen)
    }

    // ---- an entry that keeps its id but changes its name or icon ------------------------------

    @Test
    fun a_renamed_entry_refreshes_the_open_home() {
        val h = Harness(entries = listOf("a", "b"))
        h.send(HudEvent.LauncherEntriesChanged(listOf("a", "b"), mapOf("a" to "Lyrics|music", "b" to "Now|disc")))
        h.open()
        h.log.clear()
        val fx = h.send(HudEvent.LauncherEntriesChanged(listOf("a", "b"), mapOf("a" to "Lyrics|music", "b" to "Playing|disc")))
        assertEquals(listOf<HudEffect>(RefreshHomeEntries(listOf("a", "b"), "a")), fx)
        // The same appearance again is not a change.
        assertTrue(
            h.send(HudEvent.LauncherEntriesChanged(listOf("a", "b"), mapOf("a" to "Lyrics|music", "b" to "Playing|disc")))
                .isEmpty(),
        )
    }

    @Test
    fun a_changed_icon_while_hidden_emits_nothing_and_is_remembered() {
        val h = Harness(entries = listOf("a"))
        h.send(HudEvent.LauncherEntriesChanged(listOf("a"), mapOf("a" to "A|x")))
        assertTrue(h.send(HudEvent.LauncherEntriesChanged(listOf("a"), mapOf("a" to "A|y"))).isEmpty())
        assertEquals(mapOf("a" to "A|y"), h.state.entryAppearance)
    }

    // ---- what a cancelled open tells the plugin -----------------------------------------------

    @Test
    fun only_a_wearer_dismissal_sends_back_to_the_plugin() {
        assertTrue(CloseReason.WEARER_DISMISSED.forwardsBackToPlugin())
        assertFalse(CloseReason.OPEN_CANCELLED.forwardsBackToPlugin())
        assertFalse(CloseReason.BACK_FAILSAFE.forwardsBackToPlugin())
        assertFalse(CloseReason.SUPERSEDED.forwardsBackToPlugin())
    }

    @Test
    fun a_reshow_inside_the_cancelled_window_is_closed_the_same_way_every_time() {
        val h = Harness()
        h.openEntry("b")
        h.dismiss()
        assertTrue(h.screen is Home)
        repeat(3) {
            val fx = h.shown("b")
            assertEquals(listOf<HudEffect>(CloseApp("b", CloseReason.OPEN_CANCELLED)), fx)
            assertNull((h.screen as Home).beneath)
        }
    }
}
