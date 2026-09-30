package com.anezium.rokidbus.glasses.hud

import android.Manifest
import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anezium.rokidbus.glasses.GlassesHubTestSupport
import com.anezium.rokidbus.glasses.MainActivity
import com.anezium.rokidbus.glasses.SelfArmConstants
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * The "Nexus is not running" screen has no signal for the service coming back, so it polls: with the
 * app-icon launch request still pending, the launcher opens by itself once the service connects.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityServiceRecheckTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val looper get() = shadowOf(Looper.getMainLooper())
    private var service: TestAccessibilityService? = null

    @Before
    fun setUp() {
        GlassesHubTestSupport.install(context)
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            SelfArmConstants.ACCESSIBILITY_SERVICE,
        )
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        context.getSharedPreferences("selfarm_onboarding", Context.MODE_PRIVATE)
            .edit().putBoolean("legacy_adb_safe", true).commit()
        assertFalse(HudController.isServiceConnected())
    }

    @After
    fun tearDown() {
        service?.let { HudController.onServiceDestroyed(it) }
        GlassesHubTestSupport.uninstall()
    }

    @Test
    fun the_stopped_screen_hands_off_to_the_launcher_once_the_service_is_back() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The setup confirmation that opens the activity ends on its own timer; let it settle first.
            looper.idleFor(Duration.ofMillis(MainActivity.SETUP_CONFIRMATION_MS + 1))
            var finishing = true
            scenario.onActivity { finishing = it.isFinishing }
            assertFalse("stopped screen stays up while the service is down", finishing)

            looper.idleFor(Duration.ofMillis(MainActivity.SERVICE_RECHECK_MS))
            scenario.onActivity { finishing = it.isFinishing }
            assertFalse("still stopped while the service stays down", finishing)

            val connected = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
            service = connected
            HudController.onServiceConnected(connected)
            scenario.onActivity { finishing = it.isFinishing }
            assertFalse("connecting alone does not re-render the stopped screen", finishing)

            looper.idleFor(Duration.ofMillis(MainActivity.SERVICE_RECHECK_MS))
            scenario.onActivity { finishing = it.isFinishing }
            assertTrue("the recheck left the stopped state", finishing)
            assertTrue(HudController.isLauncherShown())
        }
    }
}
