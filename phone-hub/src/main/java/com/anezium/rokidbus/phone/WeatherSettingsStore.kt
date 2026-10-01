package com.anezium.rokidbus.phone

import android.content.Context
import android.content.SharedPreferences
import com.anezium.rokidbus.shared.WeatherContract
import java.util.Locale
import org.json.JSONObject

/** How the weather widget's temperatures are written: by the phone's region, or fixed. */
enum class WeatherUnitSetting(val wireValue: String) {
    AUTO("auto"),
    CELSIUS("c"),
    FAHRENHEIT("f"),
    ;

    /** [AUTO] follows the region's convention: Fahrenheit in the few countries that use it. */
    fun resolve(locale: Locale): WeatherContract.TemperatureUnit = when (this) {
        CELSIUS -> WeatherContract.TemperatureUnit.CELSIUS
        FAHRENHEIT -> WeatherContract.TemperatureUnit.FAHRENHEIT
        AUTO -> if (locale.country.uppercase(Locale.ROOT) in FAHRENHEIT_REGIONS) {
            WeatherContract.TemperatureUnit.FAHRENHEIT
        } else {
            WeatherContract.TemperatureUnit.CELSIUS
        }
    }

    companion object {
        private val FAHRENHEIT_REGIONS = setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH", "PR", "GU", "VI", "AS")

        fun fromWireValue(value: String?): WeatherUnitSetting = entries.firstOrNull { it.wireValue == value } ?: AUTO
    }
}

/** A city the wearer typed, as Open-Meteo's geocoding resolved it. */
data class WeatherPlace(val name: String, val latitude: Double, val longitude: Double)

/**
 * The weather widget's settings and the phone's last fetched reading.
 *
 * Location is opt-in twice over: [useLocation] here, and the coarse location permission the
 * settings screen asks for. Without both, or without a recent location, the typed [city] is used.
 * The city and its resolved place stay on the phone; nothing here is ever logged.
 */
class WeatherSettingsStore private constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE),
    )

    /** The last reading and the wall time it was fetched at. */
    data class LastReading(val reading: WeatherContract.Reading, val fetchedAtMs: Long)

    fun useLocation(): Boolean = preferences.getBoolean(KEY_USE_LOCATION, true)

    fun setUseLocation(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_USE_LOCATION, enabled).apply()
    }

    fun city(): String = preferences.getString(KEY_CITY, "").orEmpty()

    /** A new city forgets the place resolved for the old one. */
    fun setCity(city: String) {
        val trimmed = city.trim()
        if (trimmed == city()) return
        preferences.edit().putString(KEY_CITY, trimmed).remove(KEY_CITY_PLACE).apply()
    }

    /** The place [city] resolved to, when it was resolved for the city that is set now. */
    fun cityPlace(): WeatherPlace? {
        val raw = preferences.getString(KEY_CITY_PLACE, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (json.optString("for") != city()) return null
        return WeatherPlace(json.optString("name"), json.optDouble("latitude"), json.optDouble("longitude"))
            .takeIf { it.name.isNotEmpty() && !it.latitude.isNaN() && !it.longitude.isNaN() }
    }

    fun setCityPlace(forCity: String, place: WeatherPlace) {
        val json = JSONObject()
            .put("for", forCity)
            .put("name", place.name)
            .put("latitude", place.latitude)
            .put("longitude", place.longitude)
        preferences.edit().putString(KEY_CITY_PLACE, json.toString()).apply()
    }

    fun unit(): WeatherUnitSetting = WeatherUnitSetting.fromWireValue(preferences.getString(KEY_UNIT, null))

    fun setUnit(unit: WeatherUnitSetting) {
        preferences.edit().putString(KEY_UNIT, unit.wireValue).apply()
    }

    fun lastReading(): LastReading? {
        val raw = preferences.getString(KEY_LAST, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val reading = json.optJSONObject("reading")?.let(WeatherContract::readingFrom) ?: return null
        if (!json.has("fetchedAtMs")) return null
        return LastReading(reading, json.optLong("fetchedAtMs"))
    }

    fun setLastReading(last: LastReading) {
        val json = JSONObject()
            .put("reading", WeatherContract.toJson(last.reading, ageMs = 0L, seq = 0L))
            .put("fetchedAtMs", last.fetchedAtMs)
        preferences.edit().putString(KEY_LAST, json.toString()).apply()
    }

    /** A setting that changes what is fetched makes the cached reading obsolete. */
    fun clearLastReading() {
        preferences.edit().remove(KEY_LAST).apply()
    }

    companion object {
        private const val KEY_USE_LOCATION = "weather_use_location"
        private const val KEY_CITY = "weather_city"
        private const val KEY_CITY_PLACE = "weather_city_place"
        private const val KEY_UNIT = "weather_unit"
        private const val KEY_LAST = "weather_last_reading"
    }
}
