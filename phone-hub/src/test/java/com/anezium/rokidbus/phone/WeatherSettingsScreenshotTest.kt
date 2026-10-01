package com.anezium.rokidbus.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import com.anezium.rokidbus.shared.WeatherContract
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileSize
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

class ScreenshotWeatherSettingsActivity : WeatherSettingsActivity() {
    var granted = false
    var permissionRequests = 0
    val lookedUp = ArrayList<String>()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
    }

    override fun locationPermissionGranted(): Boolean = granted

    override fun requestLocationPermission() {
        permissionRequests++
    }

    override fun geocode(city: String): Result<WeatherPlace?> {
        lookedUp += city
        return Result.success(if (city == "Lisbon") WeatherPlace("Lisbon", 38.71667, -9.13333) else null)
    }

    override fun now(): Long = FETCHED_AT + 12 * 60_000L

    companion object {
        const val FETCHED_AT = 1_790_865_000_000L
    }
}

/** The weather settings at 390x844 dp (xhdpi), written to `build/screenshots/` for review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [28], qualifiers = "w390dp-h844dp-xhdpi")
class WeatherSettingsScreenshotTest {
    private val app = RuntimeEnvironment.getApplication()

    @After
    fun clear() {
        app.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun capture(name: String, activity: ScreenshotWeatherSettingsActivity) {
        val root = activity.window.decorView
        val width = 780
        val height = 1688
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun <T : View> find(root: View, type: Class<T>, match: (T) -> Boolean = { true }): T? {
        if (type.isInstance(root) && match(type.cast(root)!!)) return type.cast(root)
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) find(root.getChildAt(i), type, match)?.let { return it }
        }
        return null
    }

    private fun text(activity: ScreenshotWeatherSettingsActivity, prefix: String): String? =
        find(activity.window.decorView, TextView::class.java) { it.text.startsWith(prefix) }?.text?.toString()

    @Test
    fun `a city typed while location is denied, placed widget, last update`() {
        val store = WeatherSettingsStore(app)
        TileLayoutSettingsStore(app).setEntries(listOf(TileLayoutEntry("sys:weather", TileSize.WIDE, 0, 0)))
        store.setLastReading(
            WeatherSettingsStore.LastReading(
                WeatherContract.Reading("Lisbon", 19, WeatherContract.TemperatureUnit.CELSIUS, 3, "Overcast", 23, 17),
                ScreenshotWeatherSettingsActivity.FETCHED_AT,
            ),
        )
        val activity = Robolectric.buildActivity(ScreenshotWeatherSettingsActivity::class.java).setup().get()
        val switch = find(activity.window.decorView, Switch::class.java)!!
        assertEquals("denied permission shows the switch off", false, switch.isChecked)

        find(activity.window.decorView, EditText::class.java)!!.setText("Lisbon")
        find(activity.window.decorView, View::class.java) { it.contentDescription == "Set city" }!!.performClick()
        // The lookup runs on the screen's worker and posts its answer back.
        val deadline = System.currentTimeMillis() + 5_000L
        while (text(activity, "Found") == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            ShadowLooper.idleMainLooper()
        }
        assertEquals(listOf("Lisbon"), activity.lookedUp)
        assertEquals("Found: Lisbon", text(activity, "Found"))
        assertEquals(WeatherPlace("Lisbon", 38.71667, -9.13333), store.cityPlace())
        assertTrue("a new city drops the cached reading", store.lastReading() == null)

        // A reading fetched for the new city, twelve minutes ago.
        store.setLastReading(
            WeatherSettingsStore.LastReading(
                WeatherContract.Reading("Lisbon", 19, WeatherContract.TemperatureUnit.CELSIUS, 3, "Overcast", 23, 17),
                ScreenshotWeatherSettingsActivity.FETCHED_AT,
            ),
        )
        activity.renderForTest()
        assertTrue(text(activity, "The widget is on the grid.")!!.contains("Forecast for: Lisbon\nUpdated 12 min ago"))
        capture("weather-settings", activity)
    }

    @Test
    fun `turning location on asks for the permission and units are one tap`() {
        val activity = Robolectric.buildActivity(ScreenshotWeatherSettingsActivity::class.java).setup().get()
        val root = activity.window.decorView
        find(root, Switch::class.java)!!.isChecked = true
        assertEquals(1, activity.permissionRequests)

        find(root, View::class.java) { it.contentDescription == "Fahrenheit" }!!.performClick()
        assertEquals(WeatherUnitSetting.FAHRENHEIT, WeatherSettingsStore(app).unit())
        assertTrue(find(root, View::class.java) { it.contentDescription == "Fahrenheit" }!!.isSelected)
        assertTrue(text(activity, "The widget is not on the grid")!!.contains("No place yet"))

        activity.granted = true
        activity.renderForTest()
        assertTrue(text(activity, "The widget is not on the grid")!!.contains("Forecast for: approximate location"))
        capture("weather-settings-location", activity)
    }
}
