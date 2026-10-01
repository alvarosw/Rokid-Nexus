package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.WeatherContract
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** A point on the map; only [rounded] ever leaves the phone. */
data class WeatherCoordinates(val latitude: Double, val longitude: Double) {
    /** Two decimals, about a kilometre: all a forecast needs. */
    fun rounded(): WeatherCoordinates = WeatherCoordinates(round2(latitude), round2(longitude))

    private fun round2(value: Double) = Math.round(value * 100.0) / 100.0
}

/**
 * Open-Meteo's forecast and geocoding APIs: no key, no account. Building the URLs and reading the
 * answers is pure, so it is tested on recorded responses; the HTTP itself is [WeatherHttp].
 */
internal object OpenMeteo {
    const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"

    /**
     * The current hour's reading, the next [WeatherContract.MAX_POINTS] hours and today plus six
     * days, in the wearer's unit and the place's own time zone (so hour labels are local there).
     */
    fun forecastUrl(at: WeatherCoordinates, unit: WeatherContract.TemperatureUnit): String {
        val rounded = at.rounded()
        val temperatureUnit = when (unit) {
            WeatherContract.TemperatureUnit.CELSIUS -> "celsius"
            WeatherContract.TemperatureUnit.FAHRENHEIT -> "fahrenheit"
        }
        return FORECAST_URL +
            "?latitude=" + String.format(Locale.US, "%.2f", rounded.latitude) +
            "&longitude=" + String.format(Locale.US, "%.2f", rounded.longitude) +
            "&current=temperature_2m,weather_code" +
            "&hourly=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&temperature_unit=" + temperatureUnit +
            "&timezone=auto" +
            "&forecast_days=" + (WeatherContract.MAX_POINTS + 1) +
            "&forecast_hours=" + (WeatherContract.MAX_POINTS + 1)
    }

    /** The best match for a typed [name], with its name in [language] (an ISO 639-1 code). */
    fun geocodingUrl(name: String, language: String): String =
        GEOCODING_URL +
            "?name=" + URLEncoder.encode(name.trim(), StandardCharsets.UTF_8.name()) +
            "&count=1" +
            "&language=" + URLEncoder.encode(language, StandardCharsets.UTF_8.name()) +
            "&format=json"

    /**
     * [body] as the widget's reading for [location]. Hour labels follow [use24Hour] and weekday
     * labels [locale]. Throws on an answer that is not a forecast or breaks the contract's bounds.
     */
    fun parseForecast(
        body: String,
        location: String,
        unit: WeatherContract.TemperatureUnit,
        locale: Locale,
        use24Hour: Boolean,
    ): WeatherContract.Reading {
        val json = JSONObject(body)
        val current = json.getJSONObject("current")
        val now = current.getString("time")
        val code = current.getInt("weather_code")

        val hourly = json.optJSONObject("hourly")
        val hourFormat = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h a", locale)
        val hours = ArrayList<WeatherContract.Hour>()
        if (hourly != null) {
            val times = hourly.getJSONArray("time")
            val temperatures = hourly.getJSONArray("temperature_2m")
            val codes = hourly.getJSONArray("weather_code")
            for (index in 0 until times.length()) {
                if (hours.size == WeatherContract.MAX_POINTS) break
                val time = times.getString(index)
                // ISO local times of one format compare as strings; the current hour is already "now".
                if (time <= now) continue
                val temperature = number(temperatures, index) ?: continue
                val hourCode = codes.optInt(index, -1).takeIf { it >= 0 } ?: continue
                hours += WeatherContract.Hour(
                    label(LocalDateTime.parse(time).format(hourFormat)),
                    temperature,
                    hourCode,
                )
            }
        }

        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val highs = daily.getJSONArray("temperature_2m_max")
        val lows = daily.getJSONArray("temperature_2m_min")
        val dayCodes = daily.getJSONArray("weather_code")
        val days = ArrayList<WeatherContract.Day>()
        for (index in 1 until dates.length()) {
            if (days.size == WeatherContract.MAX_POINTS) break
            val high = number(highs, index) ?: continue
            val low = number(lows, index) ?: continue
            val dayCode = dayCodes.optInt(index, -1).takeIf { it >= 0 } ?: continue
            val weekday = LocalDate.parse(dates.getString(index)).dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            days += WeatherContract.Day(label(weekday), high, low, dayCode)
        }

        return WeatherContract.Reading(
            location = location.trim().take(WeatherContract.MAX_LOCATION_CHARS),
            temperature = current.getDouble("temperature_2m").roundToInt(),
            unit = unit,
            code = code,
            condition = WeatherContract.conditionText(code),
            high = number(highs, 0) ?: error("no high today"),
            low = number(lows, 0) ?: error("no low today"),
            hourly = hours,
            daily = days,
        )
    }

    /** The first match of a geocoding answer, or null when nothing matched. */
    fun parsePlace(body: String): WeatherPlace? {
        val results = JSONObject(body).optJSONArray("results") ?: return null
        val first = results.optJSONObject(0) ?: return null
        val name = first.optString("name").trim()
        val latitude = first.optDouble("latitude")
        val longitude = first.optDouble("longitude")
        if (name.isEmpty() || latitude.isNaN() || longitude.isNaN()) return null
        return WeatherPlace(name.take(WeatherContract.MAX_LOCATION_CHARS), latitude, longitude)
    }

    /** A rounded temperature, or null where Open-Meteo has no value. */
    private fun number(array: JSONArray, index: Int): Int? =
        array.optDouble(index).takeIf { !it.isNaN() }?.roundToInt()

    private fun label(text: String): String = text.trim().take(WeatherContract.MAX_LABEL_CHARS)
}
