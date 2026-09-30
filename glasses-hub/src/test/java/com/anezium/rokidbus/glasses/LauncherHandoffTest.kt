package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.glasses.LauncherHandoff.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherHandoffTest {
    @Test
    fun an_app_icon_launch_with_the_service_up_opens_the_launcher() {
        assertEquals(Outcome.OPEN, LauncherHandoff.decide(requested = true, serviceConnected = true))
    }

    @Test
    fun an_app_icon_launch_with_the_service_down_explains_instead_of_finishing() {
        assertEquals(Outcome.SHOW_SERVICE_STOPPED, LauncherHandoff.decide(requested = true, serviceConnected = false))
    }

    @Test
    fun a_resume_without_a_launch_never_opens_the_launcher_over_an_app_screen() {
        assertEquals(Outcome.FINISH, LauncherHandoff.decide(requested = false, serviceConnected = true))
        assertEquals(Outcome.FINISH, LauncherHandoff.decide(requested = false, serviceConnected = false))
    }
}
