package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject

/**
 * The weather the glasses' weather widget draws, fetched by the phone hub and sent on
 * [BusPaths.PHONE_WEATHER].
 *
 * **Hub-owned, deliberately not a plugin API**, for the same reason as [PhoneBatteryContract]: the
 * widget is part of the home, not a third party's tile. The path is hub-only in `PathRules`, so no
 * plugin can forge a reading or listen to where the wearer is.
 *
 * Every field is bounded and a reading that breaks a bound is rejected whole, never clamped: the
 * glasses keep the last good reading, which is stale rather than wrong. The temperatures are already
 * in [Reading.unit], chosen on the phone (the wearer's setting, else the phone's locale), so the
 * glasses only print them. The forecast labels are formatted on the phone for the same reason.
 *
 * Age is relative, as for tiles: [toJson] carries how old the reading was when it was sent, and the
 * glasses add the time since receipt, so neither device's wall clock matters.
 */
object WeatherContract {
    const val VERSION = 1

    const val ERROR_INVALID = "INVALID_PHONE_WEATHER"

    const val MAX_LOCATION_CHARS = 40
    const val MAX_CONDITION_CHARS = 24
    const val MAX_LABEL_CHARS = 8
    const val MAX_POINTS = 6

    /** Wide enough for any surface temperature in either unit. */
    const val MIN_TEMPERATURE = -130
    const val MAX_TEMPERATURE = 140

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    const val MIN_CODE = 0
    const val MAX_CODE = 99

    /** A reading older than this when sent is not worth sending. */
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** The widget dims a reading this old and shows its age: the phone fetches about every 30 min. */
    const val STALE_AFTER_MS = 2L * 60 * 60 * 1000

    const val KEY_VERSION = "version"
    const val KEY_SEQ = "seq"
    const val KEY_AGE_MS = "ageMs"
    const val KEY_LOCATION = "location"
    const val KEY_TEMPERATURE = "temperature"
    const val KEY_UNIT = "unit"
    const val KEY_CODE = "code"
    const val KEY_CONDITION = "condition"
    const val KEY_HIGH = "high"
    const val KEY_LOW = "low"
    const val KEY_HOURLY = "hourly"
    const val KEY_DAILY = "daily"
    const val KEY_LABEL = "label"

    enum class TemperatureUnit(val wireValue: String, val symbol: String) {
        CELSIUS("c", "°C"),
        FAHRENHEIT("f", "°F"),
        ;

        companion object {
            fun fromWireValue(value: String): TemperatureUnit? = entries.firstOrNull { it.wireValue == value }
        }
    }

    /** One coming hour; [label] is the hour as the phone formats it, e.g. `15:00` or `3 PM`. */
    data class Hour(val label: String, val temperature: Int, val code: Int) {
        init {
            requireLabel(label)
            requireTemperature(temperature)
            requireCode(code)
        }
    }

    /** One coming day; [label] is the weekday as the phone formats it, e.g. `Fri`. */
    data class Day(val label: String, val high: Int, val low: Int, val code: Int) {
        init {
            requireLabel(label)
            requireTemperature(high)
            requireTemperature(low)
            requireCode(code)
        }
    }

    data class Reading(
        /** A place name for the wearer, never coordinates. */
        val location: String,
        val temperature: Int,
        val unit: TemperatureUnit,
        val code: Int,
        val condition: String,
        /** Today's high and low. */
        val high: Int,
        val low: Int,
        /** The hours after the current one, in order. */
        val hourly: List<Hour> = emptyList(),
        /** The days after today, in order. */
        val daily: List<Day> = emptyList(),
    ) {
        init {
            require(location.length <= MAX_LOCATION_CHARS) { "location must be <= $MAX_LOCATION_CHARS chars" }
            require(condition.length <= MAX_CONDITION_CHARS) { "condition must be <= $MAX_CONDITION_CHARS chars" }
            requireTemperature(temperature)
            requireTemperature(high)
            requireTemperature(low)
            requireCode(code)
            require(hourly.size <= MAX_POINTS) { "hourly must have <= $MAX_POINTS points" }
            require(daily.size <= MAX_POINTS) { "daily must have <= $MAX_POINTS points" }
        }
    }

    sealed interface ValidationResult {
        data class Valid(val reading: Reading, val ageMs: Long, val seq: Long) : ValidationResult
        data class Invalid(val reason: String) : ValidationResult
    }

    fun toJson(reading: Reading, ageMs: Long, seq: Long): JSONObject =
        JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_SEQ, seq)
            .put(KEY_AGE_MS, ageMs.coerceAtLeast(0L))
            .put(KEY_LOCATION, reading.location)
            .put(KEY_TEMPERATURE, reading.temperature)
            .put(KEY_UNIT, reading.unit.wireValue)
            .put(KEY_CODE, reading.code)
            .put(KEY_CONDITION, reading.condition)
            .put(KEY_HIGH, reading.high)
            .put(KEY_LOW, reading.low)
            .put(
                KEY_HOURLY,
                JSONArray().apply {
                    reading.hourly.forEach { hour ->
                        put(
                            JSONObject()
                                .put(KEY_LABEL, hour.label)
                                .put(KEY_TEMPERATURE, hour.temperature)
                                .put(KEY_CODE, hour.code),
                        )
                    }
                },
            )
            .put(
                KEY_DAILY,
                JSONArray().apply {
                    reading.daily.forEach { day ->
                        put(
                            JSONObject()
                                .put(KEY_LABEL, day.label)
                                .put(KEY_HIGH, day.high)
                                .put(KEY_LOW, day.low)
                                .put(KEY_CODE, day.code),
                        )
                    }
                },
            )

    /** A missing `seq`, a missing field, or any value out of bounds rejects the whole message. */
    fun validate(payload: JSONObject?): ValidationResult {
        val json = payload ?: return invalid()
        if (!json.has(KEY_SEQ) || !json.has(KEY_AGE_MS)) return invalid()
        val seq = json.optLong(KEY_SEQ, Long.MIN_VALUE)
        val ageMs = json.optLong(KEY_AGE_MS, -1L)
        if (ageMs !in 0L..MAX_AGE_MS) return invalid()
        val reading = runCatching { readingFrom(json) }.getOrNull() ?: return invalid()
        return ValidationResult.Valid(reading, ageMs, seq)
    }

    /** The reading in [json] as [toJson] wrote it, or null; for stores that keep the payload. */
    fun readingFrom(json: JSONObject): Reading? {
        val unit = TemperatureUnit.fromWireValue(json.optString(KEY_UNIT)) ?: return null
        val hourly = points(json.optJSONArray(KEY_HOURLY)) { item ->
            Hour(item.getString(KEY_LABEL), int(item, KEY_TEMPERATURE), int(item, KEY_CODE))
        } ?: return null
        val daily = points(json.optJSONArray(KEY_DAILY)) { item ->
            Day(item.getString(KEY_LABEL), int(item, KEY_HIGH), int(item, KEY_LOW), int(item, KEY_CODE))
        } ?: return null
        return runCatching {
            Reading(
                location = json.getString(KEY_LOCATION),
                temperature = int(json, KEY_TEMPERATURE),
                unit = unit,
                code = int(json, KEY_CODE),
                condition = json.getString(KEY_CONDITION),
                high = int(json, KEY_HIGH),
                low = int(json, KEY_LOW),
                hourly = hourly,
                daily = daily,
            )
        }.getOrNull()
    }

    /**
     * The condition the widget writes for a WMO [code], in the HUD's plain words. Codes Open-Meteo
     * does not use read as unknown rather than being guessed at.
     */
    fun conditionText(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Storm with hail"
        else -> "Unknown"
    }

    private fun invalid() = ValidationResult.Invalid(ERROR_INVALID)

    /** An absent list is empty; a list over [MAX_POINTS] or with one bad point is null. */
    private fun <T> points(array: JSONArray?, read: (JSONObject) -> T): List<T>? {
        if (array == null) return emptyList()
        if (array.length() > MAX_POINTS) return null
        return runCatching { List(array.length()) { index -> read(array.getJSONObject(index)) } }.getOrNull()
    }

    /** An integer field; a fractional or non-numeric value is an error, not a rounding. */
    private fun int(json: JSONObject, key: String): Int {
        val value = json.get(key)
        require(value is Int || value is Long) { "$key must be an integer" }
        val number = (value as Number).toLong()
        require(number in Int.MIN_VALUE..Int.MAX_VALUE) { "$key is out of range" }
        return number.toInt()
    }

    private fun requireLabel(label: String) =
        require(label.length in 1..MAX_LABEL_CHARS) { "label must be 1..$MAX_LABEL_CHARS chars" }

    private fun requireTemperature(value: Int) =
        require(value in MIN_TEMPERATURE..MAX_TEMPERATURE) { "temperature must be $MIN_TEMPERATURE..$MAX_TEMPERATURE" }

    private fun requireCode(code: Int) =
        require(code in MIN_CODE..MAX_CODE) { "code must be $MIN_CODE..$MAX_CODE" }
}
