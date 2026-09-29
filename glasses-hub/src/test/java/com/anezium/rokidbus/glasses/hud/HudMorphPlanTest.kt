package com.anezium.rokidbus.glasses.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class HudMorphPlanTest {
    private val home = HudScreen.Home(HomeMode.LIST, "a")
    private val opening = HudScreen.Opening("a", 1, 10_000, home)
    private val appFromHome = HudScreen.App(SurfaceInfo("a:s", "a"), Origin.HOME)
    private val appFromHidden = HudScreen.App(SurfaceInfo("a:s", "a"), Origin.HIDDEN)

    @Test
    fun tap_on_a_plain_home_opens_and_a_repeated_opening_stays_an_open() {
        assertEquals(MorphPlan.Open("a"), HudMorphPlan.of(home, opening))
        assertEquals(MorphPlan.Open("a"), HudMorphPlan.of(opening, opening))
    }

    @Test
    fun the_surface_arriving_reveals_only_for_the_answer_to_our_own_open() {
        assertEquals(MorphPlan.Reveal, HudMorphPlan.of(opening, appFromHome))
        assertEquals(MorphPlan.Instant, HudMorphPlan.of(opening, appFromHidden))
    }

    @Test
    fun a_failed_or_dismissed_open_and_a_closed_app_collapse_onto_the_item() {
        assertEquals(MorphPlan.Collapse("a"), HudMorphPlan.of(opening, home))
        assertEquals(MorphPlan.Collapse("a"), HudMorphPlan.of(appFromHome, home))
    }

    @Test
    fun hidden_and_external_transitions_never_morph() {
        assertEquals(MorphPlan.Instant, HudMorphPlan.of(appFromHidden, HudScreen.Hidden))
        assertEquals(MorphPlan.Instant, HudMorphPlan.of(appFromHome, HudScreen.Hidden))
        assertEquals(MorphPlan.Instant, HudMorphPlan.of(home, HudScreen.Hidden))
        assertEquals(MorphPlan.Instant, HudMorphPlan.of(opening, HudScreen.Hidden))
        val external = HudScreen.External(ExternalKind.CAMERA, Origin.HIDDEN)
        assertEquals(MorphPlan.Instant, HudMorphPlan.of(opening, external))
        assertEquals(MorphPlan.Instant, HudMorphPlan.of(appFromHome, external))
        assertEquals(MorphPlan.Instant, HudMorphPlan.of(external, home))
        assertEquals(MorphPlan.Instant, HudMorphPlan.of(HudScreen.Hidden, home))
    }

    @Test
    fun a_launcher_over_a_surface_has_no_panel_either_way() {
        val over = HudScreen.Home(HomeMode.LIST, "a", beneath = appFromHome)
        val opening = HudScreen.Opening("a", 1, 10_000, over)
        assertEquals(MorphPlan.Instant, HudMorphPlan.of(over, opening))
        assertEquals(MorphPlan.Instant, HudMorphPlan.of(opening, over))
        assertEquals(MorphPlan.Instant, HudMorphPlan.of(appFromHome, over))
    }

    @Test
    fun a_selection_move_ends_a_running_close_and_an_unchanged_screen_leaves_motion_alone() {
        assertEquals(MorphPlan.Keep, HudMorphPlan.of(home, home))
        assertEquals(MorphPlan.Instant, HudMorphPlan.of(home, home.copy(selectedId = "b")))
        assertEquals(MorphPlan.Keep, HudMorphPlan.of(appFromHome, appFromHome.copy(backToken = 3)))
    }
}
