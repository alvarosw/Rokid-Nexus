package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.WeatherContract
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executor
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

private class FakeLocation(
    var granted: Boolean = true,
    var last: WeatherCoordinates? = WeatherCoordinates(38.716_789, -9.139_123),
    var name: String? = "Lisboa",
) : WeatherDeviceLocation {
    var lastKnownReads = 0
    override fun permissionGranted() = granted
    override fun lastKnown(): WeatherCoordinates? {
        lastKnownReads++
        return last
    }
    override fun placeName(at: WeatherCoordinates) = name
}

/** Answers forecast and geocoding URLs from the fixtures, and records what was asked. */
private class FakeHttp : WeatherHttp {
    val asked = ArrayList<String>()
    var failing = false
    var geocoding = "open-meteo-geocoding-lisbon.json"

    override fun get(url: String): String {
        asked += url
        if (failing) throw IOException("offline $url")
        val name = if (url.startsWith(OpenMeteo.GEOCODING_URL)) geocoding else "open-meteo-forecast-lisbon.json"
        return requireNotNull(javaClass.classLoader!!.getResource("weather/$name")).readText()
    }
}

@RunWith(RobolectricTestRunner::class)
class PhoneWeatherTest {
    private val context = RuntimeEnvironment.getApplication()
    private val settings = WeatherSettingsStore(context)
    private val location = FakeLocation()
    private val http = FakeHttp()
    private val logs = ArrayList<String>()
    private val source = PhoneWeatherSource(settings, location, http, { Locale.UK }, { true }, logs::add)

    @Before
    @After
    fun clear() {
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ---- where the forecast is for ------------------------------------------------------------

    @Test
    fun `location is used only when allowed, granted and known, else the city, else nothing`() {
        val here = WeatherCoordinates(1.0, 2.0)
        assertEquals(WeatherLocationChoice.Device(here), WeatherLocationPolicy.choose(true, true, { here }, "Porto"))
        assertEquals(WeatherLocationChoice.City("Porto"), WeatherLocationPolicy.choose(true, true, { null }, " Porto "))
        assertEquals(WeatherLocationChoice.City("Porto"), WeatherLocationPolicy.choose(true, false, { error("not asked") }, "Porto"))
        assertEquals(WeatherLocationChoice.City("Porto"), WeatherLocationPolicy.choose(false, true, { error("not asked") }, "Porto"))
        assertEquals(WeatherLocationChoice.None, WeatherLocationPolicy.choose(true, true, { null }, "  "))
        assertEquals(WeatherLocationChoice.None, WeatherLocationPolicy.choose(false, false, { null }, ""))
    }

    @Test
    fun `a fetch by location sends rounded coordinates and names the place`() {
        val reading = source.fetch()!!
        assertEquals("Lisboa", reading.location)
        assertEquals(19, reading.temperature)
        assertTrue(http.asked.single(), http.asked.single().contains("latitude=38.72&longitude=-9.14&"))
        assertEquals(listOf("weather fetched source=location unit=c"), logs)
    }

    @Test
    fun `without a location the typed city is looked up once and kept`() {
        location.last = null
        settings.setCity("Lisbon")
        assertEquals("Lisbon", source.fetch()!!.location)
        assertTrue(http.asked[0].startsWith(OpenMeteo.GEOCODING_URL))
        assertTrue(http.asked[1].contains("latitude=38.72&longitude=-9.13&"))
        assertEquals(WeatherPlace("Lisbon", 38.71667, -9.13333), settings.cityPlace())

        http.asked.clear()
        source.fetch()
        assertEquals("the resolved place is reused", 1, http.asked.size)

        settings.setCity("Porto")
        assertNull("a new city forgets the old place", settings.cityPlace())
    }

    @Test
    fun `denied location falls back to the city without reading the location`() {
        location.granted = false
        settings.setCity("Lisbon")
        source.fetch()
        assertEquals(0, location.lastKnownReads)
        settings.setUseLocation(false)
        location.granted = true
        source.fetch()
        assertEquals(0, location.lastKnownReads)
    }

    @Test
    fun `no place, an unknown city or a failed request yield nothing, and the log never names the place`() {
        location.last = null
        assertNull(source.fetch())
        assertTrue(http.asked.isEmpty())

        settings.setCity("Atlantis")
        http.geocoding = "open-meteo-geocoding-none.json"
        assertNull(source.fetch())
        assertNull(settings.cityPlace())

        location.last = WeatherCoordinates(38.716_789, -9.139_123)
        http.failing = true
        assertNull(source.fetch())
        assertEquals(
            listOf(
                "weather fetch skipped reason=no_location",
                "weather fetch skipped reason=city_not_found",
                "weather fetch failed source=location error=IOException",
            ),
            logs,
        )
        logs.forEach { line ->
            listOf("Atlantis", "Lisb", "38.7", "-9.1").forEach { assertFalse(line, line.contains(it)) }
        }
    }

    @Test
    fun `the unit setting decides the request and the reading`() {
        settings.setUnit(WeatherUnitSetting.FAHRENHEIT)
        assertEquals(WeatherContract.TemperatureUnit.FAHRENHEIT, source.fetch()!!.unit)
        assertTrue(http.asked.last().contains("temperature_unit=fahrenheit"))
    }

    // ---- cadence ------------------------------------------------------------------------------

    private class FakeTimer : WeatherTimer {
        var delay: Long? = null
        var task: Runnable? = null
        override fun schedule(delayMs: Long, task: Runnable) {
            delay = delayMs
            this.task = task
        }
        override fun cancel() {
            delay = null
            task = null
        }
    }

    private inner class Cadence {
        var now = 1_790_000_000_000L
        var placed = true
        var linked = true
        var fetches = 0
        var result: WeatherContract.Reading? = WeatherContract.Reading("Lisbon", 19, WeatherContract.TemperatureUnit.CELSIUS, 3, "Overcast", 23, 17)
        val timer = FakeTimer()
        val sent = ArrayList<BusEnvelope>()
        val reporter = PhoneWeatherReporter(
            clock = { now },
            timer = timer,
            worker = Executor(Runnable::run),
            isPlaced = { placed },
            isLinked = { linked },
            fetch = {
                fetches++
                result
            },
            store = settings,
            send = {
                sent += it
                null
            },
            log = {},
        )

        /** Moves the clock and fires the timer if it is due. */
        fun advance(ms: Long) {
            now += ms
            val delay = timer.delay ?: return
            if (delay <= ms) timer.task!!.run()
        }
    }

    @Test
    fun `fetches only while placed and linked, then about every 30 minutes`() {
        val c = Cadence()
        c.placed = false
        c.reporter.onConditionsChanged("link_up")
        assertEquals("not placed: nothing runs", 0, c.fetches)
        assertNull(c.timer.delay)

        c.placed = true
        c.linked = false
        c.reporter.onConditionsChanged("layout")
        assertEquals("not linked: nothing runs", 0, c.fetches)

        c.linked = true
        c.reporter.onConditionsChanged("link_up")
        assertEquals(1, c.fetches)
        assertEquals(BusPaths.PHONE_WEATHER, c.sent.single().path)
        assertEquals(0L, c.sent.single().payload.getLong(WeatherContract.KEY_AGE_MS))
        assertEquals(PhoneWeatherReporter.INTERVAL_MS, c.timer.delay)

        c.advance(29 * 60_000L)
        assertEquals(1, c.fetches)
        c.advance(PhoneWeatherReporter.INTERVAL_MS)
        assertEquals(2, c.fetches)
        assertEquals(2, c.sent.size)
        assertTrue(c.sent[1].payload.getLong(WeatherContract.KEY_SEQ) > c.sent[0].payload.getLong(WeatherContract.KEY_SEQ))
    }

    @Test
    fun `removing the widget or losing the link stops the timer`() {
        val c = Cadence()
        c.reporter.onConditionsChanged("link_up")
        assertEquals(PhoneWeatherReporter.INTERVAL_MS, c.timer.delay)

        c.placed = false
        c.reporter.onConditionsChanged("layout")
        assertNull(c.timer.delay)
        c.advance(3 * PhoneWeatherReporter.INTERVAL_MS)
        assertEquals(1, c.fetches)

        c.placed = true
        c.reporter.onConditionsChanged("layout")
        assertEquals("placed again and due: fetch", 2, c.fetches)
        c.linked = false
        c.reporter.onConditionsChanged("link_down")
        assertNull(c.timer.delay)
    }

    @Test
    fun `a reconnect within the interval resends the cached reading with its age instead of fetching`() {
        val c = Cadence()
        c.reporter.onConditionsChanged("link_up")
        c.linked = false
        c.reporter.onConditionsChanged("link_down")
        c.advance(10 * 60_000L)
        c.linked = true
        c.reporter.resend("glasses_announced")
        assertEquals(1, c.fetches)
        assertEquals(2, c.sent.size)
        assertEquals(10 * 60_000L, c.sent[1].payload.getLong(WeatherContract.KEY_AGE_MS))
        assertEquals(20 * 60_000L, c.timer.delay)
    }

    @Test
    fun `the cache outlives the reporter, so a hub restart does not refetch early`() {
        val first = Cadence()
        first.reporter.onConditionsChanged("link_up")
        first.reporter.stop()

        val second = Cadence()
        second.now = first.now + 5 * 60_000L
        second.reporter.onConditionsChanged("link_up")
        assertEquals(0, second.fetches)
        assertEquals(5 * 60_000L, second.sent.single().payload.getLong(WeatherContract.KEY_AGE_MS))
        assertEquals(25 * 60_000L, second.timer.delay)
    }

    @Test
    fun `a failed fetch is retried after five minutes and settings force a fetch`() {
        val c = Cadence()
        c.result = null
        c.reporter.onConditionsChanged("link_up")
        assertEquals(1, c.fetches)
        assertTrue(c.sent.isEmpty())
        assertEquals(PhoneWeatherReporter.RETRY_MS, c.timer.delay)
        c.reporter.onConditionsChanged("link_up")
        assertEquals("no retry before its time", 1, c.fetches)

        c.result = WeatherContract.Reading("Porto", 17, WeatherContract.TemperatureUnit.CELSIUS, 0, "Clear", 20, 12)
        c.advance(PhoneWeatherReporter.RETRY_MS)
        assertEquals(2, c.fetches)
        assertEquals("Porto", c.sent.single().payload.getString(WeatherContract.KEY_LOCATION))

        c.reporter.refreshNow("settings")
        assertEquals(3, c.fetches)

        c.placed = false
        c.reporter.refreshNow("settings")
        assertEquals("not even settings fetch for a widget that is not placed", 3, c.fetches)
    }
}
