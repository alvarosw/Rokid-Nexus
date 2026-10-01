package com.anezium.rokidbus.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import com.anezium.rokidbus.shared.WeatherContract
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

/** One HTTPS GET; throws on any failure, including a status other than 200. */
fun interface WeatherHttp {
    @Throws(IOException::class)
    fun get(url: String): String
}

/** The phone hub's plain `HttpURLConnection` stack, as the update checker and the registry use it. */
internal class HttpsWeatherHttp : WeatherHttp {
    override fun get(url: String): String {
        val target = URL(url)
        require(target.protocol == "https") { "weather URLs must use HTTPS" }
        val connection = (target.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Rokid-Nexus-Weather/1")
        }
        return try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val body = CharArray(MAX_BODY_CHARS + 1)
                var read = 0
                while (read <= MAX_BODY_CHARS) {
                    val count = reader.read(body, read, body.size - read)
                    if (count < 0) break
                    read += count
                }
                if (read > MAX_BODY_CHARS) throw IOException("body too large")
                String(body, 0, read)
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /** A seven-day forecast is about 2 KB; anything near this is not one. */
        const val MAX_BODY_CHARS = 256 * 1024
    }
}

/** What the phone itself knows about where it is. Coarse only, and only with the permission. */
internal interface WeatherDeviceLocation {
    fun permissionGranted(): Boolean

    /** The freshest last known location across the enabled providers; no request is made. */
    fun lastKnown(): WeatherCoordinates?

    /** A place name for [at] to show the wearer, or null. */
    fun placeName(at: WeatherCoordinates): String?
}

internal class AndroidWeatherDeviceLocation(context: Context) : WeatherDeviceLocation {
    private val context = context.applicationContext

    override fun permissionGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun lastKnown(): WeatherCoordinates? {
        if (!permissionGranted()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return runCatching {
            manager.getProviders(true)
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull(Location::getElapsedRealtimeNanos)
                ?.let { WeatherCoordinates(it.latitude, it.longitude) }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    override fun placeName(at: WeatherCoordinates): String? {
        if (!Geocoder.isPresent()) return null
        val rounded = at.rounded()
        // The synchronous call: this runs on the hub's worker thread, never the main one.
        return runCatching {
            Geocoder(context, Locale.getDefault())
                .getFromLocation(rounded.latitude, rounded.longitude, 1)
                ?.firstOrNull()
                ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

/** Where the weather is fetched for. */
internal sealed interface WeatherLocationChoice {
    /** Logged as the source; never the place itself. */
    val kind: String

    data class Device(val at: WeatherCoordinates) : WeatherLocationChoice {
        override val kind: String get() = "location"
    }

    data class City(val name: String) : WeatherLocationChoice {
        override val kind: String get() = "city"
    }

    data object None : WeatherLocationChoice {
        override val kind: String get() = "none"
    }
}

internal object WeatherLocationPolicy {
    /**
     * The phone's own last known location when the wearer allows it and the permission is held,
     * else the typed city, else nothing. [lastKnown] is only asked when location may be used.
     */
    fun choose(
        useLocation: Boolean,
        permissionGranted: Boolean,
        lastKnown: () -> WeatherCoordinates?,
        city: String,
    ): WeatherLocationChoice {
        if (useLocation && permissionGranted) lastKnown()?.let { return WeatherLocationChoice.Device(it) }
        if (city.isNotBlank()) return WeatherLocationChoice.City(city.trim())
        return WeatherLocationChoice.None
    }
}

/**
 * One weather fetch: choose the place, resolve a typed city once (the result is kept with the
 * city), ask Open-Meteo, parse. Blocking: the hub runs it on its worker executor.
 *
 * Privacy: coordinates are rounded before any request, and neither they nor the city name are
 * ever logged; the log says which kind of place was used and how the fetch ended.
 */
internal class PhoneWeatherSource(
    private val settings: WeatherSettingsStore,
    private val location: WeatherDeviceLocation,
    private val http: WeatherHttp,
    private val locale: () -> Locale,
    private val use24Hour: () -> Boolean,
    private val log: (String) -> Unit,
) {
    /** Null when there is no place to ask for or the fetch failed. */
    fun fetch(): WeatherContract.Reading? {
        val choice = WeatherLocationPolicy.choose(
            useLocation = settings.useLocation(),
            permissionGranted = location.permissionGranted(),
            lastKnown = location::lastKnown,
            city = settings.city(),
        )
        val (name, at) = when (choice) {
            is WeatherLocationChoice.Device -> (location.placeName(choice.at) ?: CURRENT_LOCATION) to choice.at
            is WeatherLocationChoice.City -> resolveCity(choice.name) ?: return null
            WeatherLocationChoice.None -> {
                log("weather fetch skipped reason=no_location")
                return null
            }
        }
        val unit = settings.unit().resolve(locale())
        return try {
            val body = http.get(OpenMeteo.forecastUrl(at, unit))
            OpenMeteo.parseForecast(body, name, unit, locale(), use24Hour()).also {
                log("weather fetched source=${choice.kind} unit=${unit.wireValue}")
            }
        } catch (e: Exception) {
            // The class only: an exception message can carry the request URL, coordinates included.
            log("weather fetch failed source=${choice.kind} error=${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * The typed [city] as Open-Meteo resolves it: a place, null for no match, or a failure when the
     * question could not be asked. The settings screen uses it to confirm a city as it is typed.
     */
    fun geocode(city: String): Result<WeatherPlace?> = runCatching {
        OpenMeteo.parsePlace(http.get(OpenMeteo.geocodingUrl(city, locale().language.ifEmpty { "en" })))
    }

    private fun resolveCity(city: String): Pair<String, WeatherCoordinates>? {
        val place = settings.cityPlace() ?: geocode(city).fold(
            onSuccess = { found ->
                if (found == null) log("weather fetch skipped reason=city_not_found")
                found?.also { settings.setCityPlace(city, it) }
            },
            onFailure = { error ->
                log("weather geocoding failed error=${error.javaClass.simpleName}")
                null
            },
        ) ?: return null
        return place.name to WeatherCoordinates(place.latitude, place.longitude)
    }

    private companion object {
        const val CURRENT_LOCATION = "Current location"
    }
}
