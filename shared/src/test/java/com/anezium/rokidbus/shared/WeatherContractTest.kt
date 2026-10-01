package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.plugin.PathRules
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherContractTest {
    private val reading = WeatherContract.Reading(
        location = "Lisbon",
        temperature = 19,
        unit = WeatherContract.TemperatureUnit.CELSIUS,
        code = 3,
        condition = "Overcast",
        high = 23,
        low = 17,
        hourly = listOf(
            WeatherContract.Hour("11:00", 20, 3),
            WeatherContract.Hour("12:00", 21, 0),
        ),
        daily = listOf(
            WeatherContract.Day("Fri", 27, 17, 3),
            WeatherContract.Day("Sat", 26, 20, 96),
        ),
    )

    private fun valid(payload: JSONObject) =
        WeatherContract.validate(payload) as WeatherContract.ValidationResult.Valid

    private fun rejected(payload: JSONObject?) =
        WeatherContract.validate(payload) == WeatherContract.ValidationResult.Invalid(WeatherContract.ERROR_INVALID)

    private fun payload() = WeatherContract.toJson(reading, ageMs = 90_000L, seq = 7L)

    @Test
    fun `round-trips a reading with its age and seq`() {
        val result = valid(payload())
        assertEquals(reading, result.reading)
        assertEquals(90_000L, result.ageMs)
        assertEquals(7L, result.seq)
        assertEquals(reading, WeatherContract.readingFrom(payload()))
    }

    @Test
    fun `absent forecasts are empty`() {
        val json = payload().apply {
            remove(WeatherContract.KEY_HOURLY)
            remove(WeatherContract.KEY_DAILY)
        }
        val result = valid(json)
        assertTrue(result.reading.hourly.isEmpty())
        assertTrue(result.reading.daily.isEmpty())
    }

    @Test
    fun `a message without seq or age is rejected`() {
        assertTrue(rejected(null))
        assertTrue(rejected(payload().apply { remove(WeatherContract.KEY_SEQ) }))
        assertTrue(rejected(payload().apply { remove(WeatherContract.KEY_AGE_MS) }))
        assertTrue(rejected(payload().put(WeatherContract.KEY_AGE_MS, -1)))
        assertTrue(rejected(payload().put(WeatherContract.KEY_AGE_MS, WeatherContract.MAX_AGE_MS + 1)))
    }

    @Test
    fun `every bound rejects the whole reading rather than clamping it`() {
        assertTrue(rejected(payload().put(WeatherContract.KEY_TEMPERATURE, 141)))
        assertTrue(rejected(payload().put(WeatherContract.KEY_LOW, -131)))
        assertTrue(rejected(payload().put(WeatherContract.KEY_TEMPERATURE, 19.5)))
        assertTrue(rejected(payload().put(WeatherContract.KEY_TEMPERATURE, "19")))
        assertTrue(rejected(payload().put(WeatherContract.KEY_TEMPERATURE, 1L shl 40)))
        assertTrue(rejected(payload().put(WeatherContract.KEY_CODE, 100)))
        assertTrue(rejected(payload().put(WeatherContract.KEY_UNIT, "k")))
        assertTrue(rejected(payload().put(WeatherContract.KEY_LOCATION, "x".repeat(41))))
        assertTrue(rejected(payload().put(WeatherContract.KEY_CONDITION, "x".repeat(25))))
        assertTrue(rejected(payload().apply { remove(WeatherContract.KEY_HIGH) }))
        val sevenHours = JSONArray().apply {
            repeat(7) { put(JSONObject().put("label", "1$it:00").put("temperature", 20).put("code", 0)) }
        }
        assertTrue(rejected(payload().put(WeatherContract.KEY_HOURLY, sevenHours)))
        val longLabel = JSONArray().put(JSONObject().put("label", "123456789").put("high", 1).put("low", 0).put("code", 0))
        assertTrue(rejected(payload().put(WeatherContract.KEY_DAILY, longLabel)))
        val emptyLabel = JSONArray().put(JSONObject().put("label", "").put("temperature", 1).put("code", 0))
        assertTrue(rejected(payload().put(WeatherContract.KEY_HOURLY, emptyLabel)))
    }

    @Test
    fun `construction enforces the same bounds`() {
        assertFalse(runCatching { reading.copy(location = "x".repeat(41)) }.isSuccess)
        assertFalse(runCatching { reading.copy(hourly = List(7) { WeatherContract.Hour("1", 0, 0) }) }.isSuccess)
        assertFalse(runCatching { WeatherContract.Day("Fri", 200, 0, 0) }.isSuccess)
        assertFalse(runCatching { WeatherContract.Hour("12:00", 0, -1) }.isSuccess)
    }

    @Test
    fun `condition text covers the WMO codes Open-Meteo reports`() {
        assertEquals("Clear", WeatherContract.conditionText(0))
        assertEquals("Overcast", WeatherContract.conditionText(3))
        assertEquals("Fog", WeatherContract.conditionText(48))
        assertEquals("Rain", WeatherContract.conditionText(63))
        assertEquals("Storm with hail", WeatherContract.conditionText(99))
        assertEquals("Unknown", WeatherContract.conditionText(42))
        (0..99).forEach { assertTrue(WeatherContract.conditionText(it).length <= WeatherContract.MAX_CONDITION_CHARS) }
    }

    @Test
    fun `the weather path is hub-only and no plugin can subscribe to it`() {
        assertEquals("/phone/weather", BusPaths.PHONE_WEATHER)
        assertTrue(PathRules.isHubOnly(BusPaths.PHONE_WEATHER))
        assertTrue(PathRules.isReserved(BusPaths.PHONE_WEATHER))
        assertNull(PathRules.requiredCapability(BusPaths.PHONE_WEATHER))
        val every = PluginCapability.entries.toSet()
        listOf("/phone", "/phone/weather", "/phone/weather/x").forEach { prefix ->
            assertFalse(prefix, PathRules.isAllowedReceivePrefix(prefix, "weatherly", every))
            assertNull(prefix, PathRules.requiredCapabilityForReceivePrefix(prefix))
        }
        assertFalse(PathRules.isDirectReply(BusPaths.PHONE_WEATHER))
    }
}
