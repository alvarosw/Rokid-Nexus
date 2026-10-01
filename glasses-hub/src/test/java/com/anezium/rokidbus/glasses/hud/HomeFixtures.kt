package com.anezium.rokidbus.glasses.hud

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.glasses.SystemWidgetSource
import com.anezium.rokidbus.hudtiles.SystemWidgetContent
import com.anezium.rokidbus.shared.WeatherContract
import com.anezium.rokidbus.shared.tile.SystemWidget
import com.anezium.rokidbus.shared.tile.SystemWidgets
import com.anezium.rokidbus.shared.tile.TileGridLayout
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TilePlacement
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import java.util.Locale
import java.util.TimeZone

internal val NAMES = listOf(
    "Lyrics", "Now Playing", "Navigation", "Transit", "Relay", "Agents", "Lens", "Tasker",
    "Weather", "Notes", "Timer", "Health", "Camera", "Translate",
)

internal fun entries(count: Int, iconKey: String? = null): List<GlassesHub.LauncherEntry> =
    (0 until count).map { GlassesHub.LauncherEntry("plugin$it", NAMES.getOrElse(it) { "Plugin $it" }, iconKey) }

/** Real icon resolution needs the app's resources; tests that are about layout use a flat drawable. */
internal val flatIcons: (Context, GlassesHub.LauncherEntry) -> Drawable = { _, _ -> ColorDrawable(Color.BLACK) }

internal fun snapshot(
    id: String,
    title: String = "12",
    unit: String = "min",
    tone: TileTone = TileTone.OK,
    subtitle: String = "",
    rows: List<String> = emptyList(),
    progress: Float? = null,
    badge: String = "",
) = TileSnapshot(
    pluginId = id,
    contentKey = "k",
    title = title,
    subtitle = subtitle,
    badge = badge,
    progress = progress,
    unit = unit,
    tone = tone,
    rows = rows,
)

/** Placement seam for a home layer: [pairs] are the declared sizes, positions come from the auto-pack. */
internal fun placementsOf(vararg pairs: Pair<String, TileSize>): (List<GlassesHub.LauncherEntry>) -> List<TilePlacement> {
    val map = pairs.toMap()
    return { list -> TileGridLayout.resolve(list.map { it.id to map[it.id] }, emptyList()) }
}

/**
 * Placement seam for a home layer driven by a stored layout, as the phone would sync it; the system
 * widgets it places come along, as from the hub.
 */
internal fun placementsOf(stored: List<TileLayoutEntry>): (List<GlassesHub.LauncherEntry>) -> List<TilePlacement> =
    { list -> TileGridLayout.resolveWithWidgets(list.map { it.id to null }, stored) }

/** Fixed widget data: Thursday 2026-10-01 14:32:10 UTC, glasses 82 % charging, phone 64 %, link up. */
internal class FakeWidgetSource(
    var epochMs: Long = 1_790_865_130_000L,
    var status: SystemWidgetContent.Status = SystemWidgetContent.Status(
        glasses = SystemWidgetContent.Battery(82, charging = true),
        phone = SystemWidgetContent.Battery(64, charging = false),
        phoneLinked = true,
    ),
    var weather: SystemWidgetContent.Weather = SystemWidgetContent.Weather(WEATHER_SAMPLE, ageMs = 4 * 60_000L),
) : SystemWidgetSource {
    var reads = 0
    var observers = 0
    private val listeners = ArrayList<() -> Unit>()

    override fun content(widget: SystemWidget): SystemWidgetContent? {
        reads++
        return when (widget.id) {
            SystemWidgets.CLOCK.id -> SystemWidgetContent.Clock(epochMs, TimeZone.getTimeZone("UTC"), Locale.US, use24Hour = true)
            SystemWidgets.STATUS.id -> status
            SystemWidgets.WEATHER.id -> weather
            else -> null
        }
    }

    override fun observe(widget: SystemWidget, onChange: () -> Unit): () -> Unit {
        observers++
        listeners += onChange
        return {
            observers--
            listeners -= onChange
        }
    }

    fun changed() = listeners.toList().forEach { it() }
}

/** Lays a home layer out on the 480x640 screen, as the host window does. */
internal fun HomeLayer.layoutOnCanvas(): HomeLayer {
    measure(
        View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
    )
    layout(0, 0, 480, 640)
    return this
}

/** Lisbon at 10:30 on Thursday 2026-10-01, as Open-Meteo reported it. */
internal val WEATHER_SAMPLE = WeatherContract.Reading(
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
        WeatherContract.Hour("13:00", 22, 0),
        WeatherContract.Hour("14:00", 23, 1),
        WeatherContract.Hour("15:00", 23, 1),
        WeatherContract.Hour("16:00", 22, 0),
    ),
    daily = listOf(
        WeatherContract.Day("Fri", 27, 17, 3),
        WeatherContract.Day("Sat", 26, 20, 3),
        WeatherContract.Day("Sun", 23, 20, 96),
        WeatherContract.Day("Mon", 25, 19, 45),
        WeatherContract.Day("Tue", 22, 19, 95),
        WeatherContract.Day("Wed", 22, 18, 45),
    ),
)
