package com.anezium.rokidbus.glasses

import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.anezium.rokidbus.glasses.hud.HudController
import com.anezium.rokidbus.glasses.hud.LauncherTrigger
import com.anezium.rokidbus.glasses.hud.TestAccessibilityService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * 01 §7.12 item 186: the standby watchdog reads its "launcher overlay shown" gate from the new
 * host (`HudController::isLauncherShown`), so the display is never put to sleep under an open
 * launcher (docs/ui-rewrite/00-architecture.md §2.3).
 */
@RunWith(RobolectricTestRunner::class)
class DisplayStandbyLauncherGateTest {
    private val context = RuntimeEnvironment.getApplication()
    private val service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()

    @Before
    fun setUp() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        HudController.onServiceConnected(service)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        HudController.onServiceDestroyed(service)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun conditions(): DisplayStandbyConditions {
        val watchdog = DisplayStandbyWatchdog(service, Handler(Looper.getMainLooper()))
        val read = DisplayStandbyWatchdog::class.java.getDeclaredMethod("readConditions").apply { isAccessible = true }
        return read.invoke(watchdog) as DisplayStandbyConditions
    }

    @Test
    fun item186_the_launcher_gate_follows_the_host_and_blocks_standby() {
        assertFalse(conditions().launcherOverlayShown)

        HudController.openLauncher(LauncherTrigger.APP_ICON)
        shadowOf(Looper.getMainLooper()).idle()
        val open = conditions()
        assertTrue(open.launcherOverlayShown)
        assertTrue(
            "an open launcher is a reason not to sleep",
            DisplayStandbyPolicy.decide(open.copy(interactiveState = StandbyInteractiveState.INTERACTIVE), DisplayStandbyPolicyState(), 1_000_000L, 0L)
                .decision is DisplayStandbyDecision.Blocked,
        )

        HudController.openLauncher(LauncherTrigger.BROADCAST_TOGGLE)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("hidden again, gate released", conditions().launcherOverlayShown)
    }
}
