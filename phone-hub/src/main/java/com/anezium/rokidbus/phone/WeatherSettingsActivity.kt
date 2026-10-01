package com.anezium.rokidbus.phone

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.tile.SystemWidgets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Where the weather widget's forecast is for, and in which unit: the phone's approximate location
 * (asked for here, never at app start), else a typed city, and °C/°F by region or fixed.
 *
 * Location is coarse only and read when a forecast is fetched; while this screen is in front it
 * may make one current-location request so the first fetch has a fix. Nothing runs in the
 * background from here: the hub fetches only while the widget is placed and the glasses are linked.
 */
open class WeatherSettingsActivity : Activity() {
    private lateinit var settings: WeatherSettingsStore
    private lateinit var locationSwitch: Switch
    private lateinit var cityField: EditText
    private lateinit var cityStatus: TextView
    private lateinit var status: TextView
    private val unitChips = LinkedHashMap<WeatherUnitSetting, TextView>()
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var locationRequest: CancellationSignal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = WeatherSettingsStore(this)
        buildUi()
        render()
    }

    override fun onResume() {
        super.onResume()
        // The permission can change in the system settings while this screen is away.
        render()
    }

    override fun onDestroy() {
        locationRequest?.cancel()
        worker.shutdownNow()
        super.onDestroy()
    }

    internal open fun locationPermissionGranted(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    internal open fun requestLocationPermission() {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
    }

    /** Blocking; run on [worker]. */
    internal open fun geocode(city: String): Result<WeatherPlace?> =
        PhoneWeatherSource(
            settings = settings,
            location = AndroidWeatherDeviceLocation(this),
            http = HttpsWeatherHttp(),
            locale = Locale::getDefault,
            use24Hour = { true },
            log = {},
        ).geocode(city)

    internal open fun weatherPlaced(): Boolean =
        TileLayoutSettingsStore(this).getEntries().any { it.pluginId == SystemWidgets.WEATHER.id }

    internal open fun now(): Long = System.currentTimeMillis()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestCurrentLocationOnce()
            settingsChanged()
        } else {
            // Denied: say so with the switch rather than keep a choice that cannot work.
            settings.setUseLocation(false)
            locationSwitch.isChecked = false
            render()
        }
    }

    private fun onUseLocation(enabled: Boolean) {
        if (enabled == settings.useLocation() && (!enabled || locationPermissionGranted())) return
        settings.setUseLocation(enabled)
        if (enabled && !locationPermissionGranted()) {
            requestLocationPermission()
            return
        }
        if (enabled) requestCurrentLocationOnce()
        settingsChanged()
    }

    /**
     * One coarse fix while this screen is in front, so a phone with no last known location still
     * gets one; its arrival triggers a fetch. No updates are kept running.
     */
    private fun requestCurrentLocationOnce() {
        if (!locationPermissionGranted()) return
        val manager = getSystemService(LocationManager::class.java) ?: return
        val provider = runCatching { manager.getProviders(true) }.getOrNull()?.let { enabled ->
            PROVIDER_ORDER.firstOrNull { it in enabled } ?: enabled.firstOrNull()
        } ?: return
        locationRequest?.cancel()
        val signal = CancellationSignal()
        locationRequest = signal
        runCatching {
            manager.getCurrentLocation(provider, signal, mainExecutor) { location ->
                if (location != null && !isDestroyed) BusHubService.onWeatherSettingsChanged()
            }
        }
    }

    private fun onSetCity() {
        val city = cityField.text.toString().trim()
        if (city == settings.city() && (city.isEmpty() || settings.cityPlace() != null)) return
        settings.setCity(city)
        settingsChanged()
        if (city.isEmpty()) return
        cityStatus.text = "Looking up the city…"
        worker.execute {
            val result = geocode(city)
            main.post {
                if (isDestroyed || settings.city() != city) return@post
                result.fold(
                    onSuccess = { place ->
                        if (place != null) {
                            settings.setCityPlace(city, place)
                            BusHubService.onWeatherSettingsChanged()
                        }
                    },
                    onFailure = { cityLookupFailed = true },
                )
                cityNotFound = result.getOrNull() == null && result.isSuccess
                render()
            }
        }
    }

    private var cityNotFound = false
    private var cityLookupFailed = false

    private fun onUnit(unit: WeatherUnitSetting) {
        if (unit == settings.unit()) return
        settings.setUnit(unit)
        settingsChanged()
    }

    /** What is fetched changed: the cached reading is for something else now. */
    private fun settingsChanged() {
        settings.clearLastReading()
        cityNotFound = false
        cityLookupFailed = false
        BusHubService.onWeatherSettingsChanged()
        render()
    }

    private fun render() {
        val granted = locationPermissionGranted()
        val useLocation = settings.useLocation() && granted
        locationSwitch.setOnCheckedChangeListener(null)
        locationSwitch.isChecked = useLocation
        locationSwitch.setOnCheckedChangeListener { _, enabled -> onUseLocation(enabled) }

        val city = settings.city()
        val place = settings.cityPlace()
        cityStatus.text = when {
            city.isEmpty() -> "No city set."
            place != null -> "Found: ${place.name}"
            cityNotFound -> "No place by that name."
            cityLookupFailed -> "Could not look it up now; the next update will try again."
            else -> "Not looked up yet."
        }
        cityStatus.setTextColor(if (cityNotFound) NexusUi.AMBER else NexusUi.INK3)

        val auto = WeatherUnitSetting.AUTO.resolve(Locale.getDefault())
        unitChips.forEach { (unit, chip) ->
            chip.text = when (unit) {
                WeatherUnitSetting.AUTO -> "AUTO · ${auto.symbol}"
                WeatherUnitSetting.CELSIUS -> "°C"
                WeatherUnitSetting.FAHRENHEIT -> "°F"
            }
            styleChip(chip, on = unit == settings.unit())
        }

        val source = when {
            useLocation -> "approximate location"
            city.isNotEmpty() -> place?.name ?: city
            else -> null
        }
        val last = settings.lastReading()
        status.text = buildString {
            append(if (weatherPlaced()) "The widget is on the grid." else "The widget is not on the grid: add it in Tile layout.")
            append('\n')
            append(source?.let { "Forecast for: $it" } ?: "No place yet: allow location or type a city.")
            if (last != null) {
                val minutes = ((now() - last.fetchedAtMs).coerceAtLeast(0L) / 60_000L)
                append('\n')
                append(if (minutes < 1) "Updated just now" else "Updated $minutes min ago")
            }
        }
    }

    internal fun renderForTest() = render()

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        locationSwitch = NexusUi.switch(this).apply { contentDescription = "Use approximate location" }
        cityField = NexusUi.field(this, "City, e.g. Lisbon").apply {
            setText(settings.city())
            contentDescription = "City"
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ ->
                onSetCity()
                false
            }
        }
        cityStatus = NexusUi.rowSub(this, "")
        status = NexusUi.rowSub(this, "").apply {
            maxLines = 4
            setLineSpacing(0f, 1.25f)
        }

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@WeatherSettingsActivity,
                    "The weather widget's place and units. The phone asks Open-Meteo about every 30 minutes, " +
                        "only while the widget is on the grid and the glasses are connected.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@WeatherSettingsActivity, 22))
            addView(
                card().apply {
                    addView(
                        NexusUi.switchRow(
                            this@WeatherSettingsActivity,
                            "Use approximate location",
                            "Coarse, read only when fetching",
                            locationSwitch,
                        ),
                        NexusUi.block(),
                    )
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@WeatherSettingsActivity, 10))
            addView(
                card().apply {
                    addView(NexusUi.rowTitle(this@WeatherSettingsActivity, "City"))
                    addView(BusTheme.gap(this@WeatherSettingsActivity, 3))
                    addView(NexusUi.rowSub(this@WeatherSettingsActivity, "Used when location is off or unavailable"))
                    addView(BusTheme.gap(this@WeatherSettingsActivity, 10))
                    addView(
                        LinearLayout(this@WeatherSettingsActivity).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            addView(cityField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                            addView(
                                NexusUi.outlinePillButton(this@WeatherSettingsActivity, "Set").apply {
                                    contentDescription = "Set city"
                                    setPadding(dp(18), 0, dp(18), 0)
                                    setOnClickListener { onSetCity() }
                                },
                                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply {
                                    marginStart = dp(10)
                                },
                            )
                        },
                        NexusUi.block(),
                    )
                    addView(BusTheme.gap(this@WeatherSettingsActivity, 8))
                    addView(cityStatus, NexusUi.block())
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@WeatherSettingsActivity, 10))
            addView(
                card().apply {
                    addView(NexusUi.rowTitle(this@WeatherSettingsActivity, "Units"))
                    addView(BusTheme.gap(this@WeatherSettingsActivity, 3))
                    addView(NexusUi.rowSub(this@WeatherSettingsActivity, "Auto follows the phone's region"))
                    addView(BusTheme.gap(this@WeatherSettingsActivity, 10))
                    addView(
                        LinearLayout(this@WeatherSettingsActivity).apply {
                            WeatherUnitSetting.entries.forEachIndexed { index, unit ->
                                val chip = NexusUi.metaLabel(this@WeatherSettingsActivity, "", NexusUi.GREEN).apply {
                                    gravity = Gravity.CENTER
                                    isClickable = true
                                    isFocusable = true
                                    contentDescription = when (unit) {
                                        WeatherUnitSetting.AUTO -> "Automatic units"
                                        WeatherUnitSetting.CELSIUS -> "Celsius"
                                        WeatherUnitSetting.FAHRENHEIT -> "Fahrenheit"
                                    }
                                    setOnClickListener { onUnit(unit) }
                                }
                                unitChips[unit] = chip
                                addView(
                                    chip,
                                    LinearLayout.LayoutParams(0, dp(44), if (unit == WeatherUnitSetting.AUTO) 1.4f else 1f).apply {
                                        if (index > 0) marginStart = dp(8)
                                    },
                                )
                            }
                        },
                        NexusUi.block(),
                    )
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@WeatherSettingsActivity, 10))
            addView(
                card().apply {
                    addView(NexusUi.metaLabel(this@WeatherSettingsActivity, "STATUS"))
                    addView(BusTheme.gap(this@WeatherSettingsActivity, 8))
                    addView(status, NexusUi.block())
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@WeatherSettingsActivity, 14))
            addView(
                NexusUi.rowSub(
                    this@WeatherSettingsActivity,
                    "The phone's location is rounded to about a kilometre before it is sent, and never stored or logged.",
                ).apply { maxLines = 3 },
                NexusUi.block(),
            )
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(
            NexusUi.fixedRoot(this).apply {
                addView(titleHeader(), NexusUi.block())
                addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
    }

    private fun card(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(this@WeatherSettingsActivity, NexusUi.PANEL, NexusUi.LINE, 15)
            setPadding(dp(15), dp(12), dp(15), dp(12))
        }

    private fun styleChip(chip: TextView, on: Boolean) {
        chip.isSelected = on
        chip.setTextColor(if (on) NexusUi.ON_ACCENT else NexusUi.GREEN)
        chip.background = if (on) {
            NexusUi.bordered(this, NexusUi.GREEN, NexusUi.GREEN, 12)
        } else {
            NexusUi.bordered(this, NexusUi.alpha(NexusUi.GREEN, 0x0A), NexusUi.alpha(NexusUi.GREEN, 0x38), 12)
        }
    }

    private fun titleHeader(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(this@WeatherSettingsActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(12), dp(22), dp(12))
                    addView(
                        TextView(this@WeatherSettingsActivity).apply {
                            text = "‹"
                            textSize = 26f
                            includeFontPadding = false
                            gravity = Gravity.CENTER
                            setTextColor(NexusUi.INK)
                            background = NexusUi.pressed(this@WeatherSettingsActivity, Color.TRANSPARENT, 22)
                            contentDescription = "Back"
                            isClickable = true
                            isFocusable = true
                            setOnClickListener { finish() }
                        },
                        LinearLayout.LayoutParams(dp(44), dp(44)),
                    )
                    addView(
                        NexusUi.metaLabel(this@WeatherSettingsActivity, "WEATHER", NexusUi.INK).apply {
                            textSize = 12f
                            letterSpacing = 0.2f
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                },
                NexusUi.block(),
            )
            addView(
                View(this@WeatherSettingsActivity).apply { setBackgroundColor(NexusUi.LINE) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
            )
        }

    private fun dp(value: Int) = NexusUi.dp(this, value)

    private companion object {
        const val REQUEST_LOCATION = 41
        /** Network first: it answers indoors and fast, and coarse is all that is asked for. */
        val PROVIDER_ORDER = listOf(LocationManager.NETWORK_PROVIDER, "fused", LocationManager.PASSIVE_PROVIDER)
    }
}
