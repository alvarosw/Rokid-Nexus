package com.anezium.rokidbus.glasses.hud

import android.Manifest
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anezium.rokidbus.glasses.GlassesHubTestSupport
import com.anezium.rokidbus.glasses.MainActivity
import com.anezium.rokidbus.glasses.SelfArmConstants
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real pixels of `MainActivity`'s "Nexus is not running" state (docs/ui-rewrite/00-architecture.md,
 * review fixes): setup is complete, the app icon asked for the launcher, and the accessibility
 * service is not connected, so the setup view explains instead of finishing to a blank task. Same
 * 480x640 px canvas and single-hue rule as [HomeScreenshotTest].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h427dp-hdpi")
class MainActivityScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    init {
        System.setProperty("roborazzi.test.record", "true")
    }

    @Before
    fun setUp() {
        // The hub's one-time bring-up loads the vendor CXR library; MainActivity calls start().
        GlassesHubTestSupport.install(context)
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            SelfArmConstants.ACCESSIBILITY_SERVICE,
        )
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        context.getSharedPreferences("selfarm_onboarding", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("legacy_adb_safe", true).commit()
        assertFalse("the accessibility service is not connected", HudController.isServiceConnected())
    }

    @After
    fun tearDown() {
        GlassesHubTestSupport.uninstall()
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString()).filter { view.visibility == View.VISIBLE && it.isNotBlank() }
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun nexus_is_not_running_when_the_launcher_was_asked_for_and_the_service_is_down() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var shown = emptyList<String>()
            scenario.onActivity { activity ->
                shown = texts(activity.window.decorView)
            }
            assertTrue("title in $shown", "Nexus is not running" in shown)
            assertTrue("action in $shown", "OPEN SETTINGS" in shown)

            val path = "build/outputs/roborazzi/main-activity-not-running.png"
            onView(isRoot()).captureRoboImage(path)
            assertSingleHue(File(path))
        }
    }
}
