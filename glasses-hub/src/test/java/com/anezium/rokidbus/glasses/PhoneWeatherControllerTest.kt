package com.anezium.rokidbus.glasses

import android.os.Looper
import android.provider.Settings
import com.anezium.rokidbus.hudtiles.SystemWidgetContent
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.WeatherContract
import com.anezium.rokidbus.shared.tile.SystemWidgets
import java.time.Duration
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class PhoneWeatherControllerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val reading = WeatherContract.Reading(
        location = "Lisbon",
        temperature = 19,
        unit = WeatherContract.TemperatureUnit.CELSIUS,
        code = 3,
        condition = "Overcast",
        high = 23,
        low = 17,
        hourly = listOf(WeatherContract.Hour("11:00", 20, 3)),
        daily = listOf(WeatherContract.Day("Fri", 27, 17, 3)),
    )

    @Before
    fun reset() = PhoneWeatherController.resetForTest(context)

    @After
    fun clear() = PhoneWeatherController.resetForTest(context)

    private fun send(reading: WeatherContract.Reading, seq: Long, ageMs: Long = 0L, payload: JSONObject? = null): Boolean {
        val handled = PhoneWeatherController.handleEnvelope(
            context,
            BusEnvelope(BusPaths.PHONE_WEATHER, payload = payload ?: WeatherContract.toJson(reading, ageMs, seq)),
        )
        shadowOf(Looper.getMainLooper()).idle()
        return handled
    }

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun a_reading_is_kept_with_its_age_and_older_sequences_are_dropped() {
        var notified = 0
        val stop = PhoneWeatherController.observe { notified++ }
        assertEquals("observing reads the current state at once", 1, notified)

        assertTrue(send(reading, seq = 10, ageMs = 60_000L))
        assertEquals(2, notified)
        val cached = PhoneWeatherController.cached(context)!!
        assertEquals(reading, cached.reading)
        assertEquals(60_000L, PhoneWeatherController.ageMs(context, cached))

        idleFor(5 * 60_000L)
        assertEquals("the age grows on the elapsed clock", 6 * 60_000L, PhoneWeatherController.ageMs(context, cached))

        send(reading.copy(temperature = 5), seq = 9)
        assertEquals("an older reading never replaces a fresher one", 19, PhoneWeatherController.cached(context)!!.reading.temperature)
        assertEquals(2, notified)

        send(reading.copy(temperature = 21), seq = 11)
        assertEquals(21, PhoneWeatherController.cached(context)!!.reading.temperature)
        assertEquals(3, notified)
        stop()
    }

    @Test
    fun an_invalid_reading_keeps_the_last_good_one() {
        send(reading, seq = 1)
        send(reading, seq = 2, payload = WeatherContract.toJson(reading, 0L, 2L).put(WeatherContract.KEY_TEMPERATURE, 999))
        assertEquals(19, PhoneWeatherController.cached(context)!!.reading.temperature)
    }

    @Test
    fun the_reading_survives_a_hub_restart_and_reads_stale_after_two_hours() {
        send(reading, seq = 1, ageMs = 30 * 60_000L)
        // A new hub process: memory is gone, the store is not.
        ReflectionHelpers.setField(PhoneWeatherController, "memory", null)
        ReflectionHelpers.setField(PhoneWeatherController, "loaded", false)

        val restored = PhoneWeatherController.cached(context)!!
        assertEquals(reading, restored.reading)
        assertEquals(30 * 60_000L, PhoneWeatherController.ageMs(context, restored))

        idleFor(WeatherContract.STALE_AFTER_MS)
        val age = PhoneWeatherController.ageMs(context, restored)!!
        assertTrue(age >= WeatherContract.STALE_AFTER_MS)
    }

    @Test
    fun a_reading_from_another_boot_has_no_age() {
        send(reading, seq = 1)
        val cached = PhoneWeatherController.cached(context)!!
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, cached.bootCount + 1)
        assertNull(PhoneWeatherController.ageMs(context, cached))
        // And an elapsed clock behind the receipt is a reboot too, whatever the boot count says.
        assertNull(cached.ageMs(nowElapsed = cached.receivedAtElapsed - 1, currentBoot = cached.bootCount))
        assertEquals(0L, cached.ageMs(cached.receivedAtElapsed, cached.bootCount))
    }

    @Test
    fun the_widget_source_draws_the_cached_reading_with_its_age() {
        val source = DeviceWidgetSource(context)
        val empty = source.content(SystemWidgets.WEATHER) as SystemWidgetContent.Weather
        assertNull(empty.reading)

        send(reading, seq = 1, ageMs = 1_000L)
        val drawn = source.content(SystemWidgets.WEATHER) as SystemWidgetContent.Weather
        assertEquals(reading, drawn.reading)
        assertEquals(1_000L, drawn.ageMs)

        var changes = 0
        val stop = source.observe(SystemWidgets.WEATHER) { changes++ }
        send(reading.copy(temperature = 20), seq = 2)
        assertEquals(2, changes)
        stop()
        send(reading.copy(temperature = 21), seq = 3)
        assertEquals(2, changes)
    }

    @Test
    fun other_paths_are_not_handled() {
        assertFalse(PhoneWeatherController.handleEnvelope(context, BusEnvelope(BusPaths.PHONE_BATTERY)))
    }
}
