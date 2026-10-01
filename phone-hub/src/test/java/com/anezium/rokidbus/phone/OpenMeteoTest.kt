package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.WeatherContract
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Open-Meteo's answers, read from fixtures: the forecast was recorded from the live API
 * (Lisbon, 2026-10-01 10:30 local), the geocoding answers follow its documented format.
 */
class OpenMeteoTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource("weather/$name")) { name }.readText()

    private fun lisbon(locale: Locale = Locale.US, use24Hour: Boolean = true) = OpenMeteo.parseForecast(
        fixture("open-meteo-forecast-lisbon.json"),
        location = "Lisbon",
        unit = WeatherContract.TemperatureUnit.CELSIUS,
        locale = locale,
        use24Hour = use24Hour,
    )

    @Test
    fun `a recorded forecast becomes the widget's reading`() {
        val reading = lisbon()
        assertEquals("Lisbon", reading.location)
        assertEquals(19, reading.temperature)
        assertEquals(3, reading.code)
        assertEquals("Overcast", reading.condition)
        assertEquals(23, reading.high)
        assertEquals(17, reading.low)
        // The current hour (10:00, it is 10:30) is not a coming hour.
        assertEquals(listOf("11:00", "12:00", "13:00", "14:00", "15:00", "16:00"), reading.hourly.map { it.label })
        assertEquals(listOf(20, 21, 22, 23, 23, 22), reading.hourly.map { it.temperature })
        assertEquals(listOf(3, 0, 0, 1, 1, 0), reading.hourly.map { it.code })
        // Today gives the high and low; the days are the six after it.
        assertEquals(listOf("Fri", "Sat", "Sun", "Mon", "Tue", "Wed"), reading.daily.map { it.label })
        assertEquals(listOf(27, 26, 23, 25, 22, 22), reading.daily.map { it.high })
        assertEquals(listOf(17, 20, 20, 19, 19, 18), reading.daily.map { it.low })
        // The reading survives the wire.
        val sent = WeatherContract.validate(WeatherContract.toJson(reading, 0L, 1L)) as WeatherContract.ValidationResult.Valid
        assertEquals(reading, sent.reading)
    }

    @Test
    fun `labels follow the phone's clock format and language`() {
        val twelve = lisbon(use24Hour = false).hourly.first().label
        assertTrue(twelve, twelve.startsWith("11") && twelve.endsWith("AM"))
        assertEquals("ven.", lisbon(Locale.FRANCE).daily.first().label)
        lisbon(Locale("de")).daily.forEach { assertTrue(it.label.length <= WeatherContract.MAX_LABEL_CHARS) }
    }

    @Test
    fun `a missing value drops that point and a broken answer throws`() {
        val body = fixture("open-meteo-forecast-lisbon.json").replace("[18.5,19.8,", "[18.5,null,")
        assertEquals("12:00", OpenMeteo.parseForecast(body, "Lisbon", WeatherContract.TemperatureUnit.CELSIUS, Locale.US, true).hourly.first().label)
        assertTrue(runCatching { OpenMeteo.parseForecast("{}", "x", WeatherContract.TemperatureUnit.CELSIUS, Locale.US, true) }.isFailure)
        assertTrue(runCatching { OpenMeteo.parseForecast("<html>", "x", WeatherContract.TemperatureUnit.CELSIUS, Locale.US, true) }.isFailure)
    }

    @Test
    fun `the forecast URL carries rounded coordinates and the unit`() {
        val url = OpenMeteo.forecastUrl(WeatherCoordinates(38.716_789, -9.139_123), WeatherContract.TemperatureUnit.FAHRENHEIT)
        assertTrue(url, url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=38.72&longitude=-9.14&"))
        assertTrue(url.contains("&temperature_unit=fahrenheit"))
        assertTrue(url.contains("&forecast_hours=7") && url.contains("&forecast_days=7"))
        assertFalse(url.contains("38.7167"))
        assertEquals(WeatherCoordinates(-0.01, 0.0), WeatherCoordinates(-0.0149, 0.004).rounded())
        assertTrue(OpenMeteo.forecastUrl(WeatherCoordinates(1.0, 2.0), WeatherContract.TemperatureUnit.CELSIUS).contains("temperature_unit=celsius"))
    }

    @Test
    fun `geocoding finds the first match or nothing`() {
        assertEquals(WeatherPlace("Lisbon", 38.71667, -9.13333), OpenMeteo.parsePlace(fixture("open-meteo-geocoding-lisbon.json")))
        assertNull(OpenMeteo.parsePlace(fixture("open-meteo-geocoding-none.json")))
        assertEquals(
            "https://geocoding-api.open-meteo.com/v1/search?name=S%C3%A3o+Paulo&count=1&language=pt&format=json",
            OpenMeteo.geocodingUrl(" São Paulo ", "pt"),
        )
    }

    @Test
    fun `auto units follow the region`() {
        assertEquals(WeatherContract.TemperatureUnit.FAHRENHEIT, WeatherUnitSetting.AUTO.resolve(Locale.US))
        assertEquals(WeatherContract.TemperatureUnit.CELSIUS, WeatherUnitSetting.AUTO.resolve(Locale.UK))
        assertEquals(WeatherContract.TemperatureUnit.CELSIUS, WeatherUnitSetting.AUTO.resolve(Locale("pt", "PT")))
        assertEquals(WeatherContract.TemperatureUnit.CELSIUS, WeatherUnitSetting.AUTO.resolve(Locale.ENGLISH))
        assertEquals(WeatherContract.TemperatureUnit.CELSIUS, WeatherUnitSetting.CELSIUS.resolve(Locale.US))
        assertEquals(WeatherContract.TemperatureUnit.FAHRENHEIT, WeatherUnitSetting.FAHRENHEIT.resolve(Locale.FRANCE))
        assertEquals(WeatherUnitSetting.AUTO, WeatherUnitSetting.fromWireValue("kelvin"))
    }
}
