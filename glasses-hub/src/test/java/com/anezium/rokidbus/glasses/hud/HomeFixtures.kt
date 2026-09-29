package com.anezium.rokidbus.glasses.hud

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone

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
) = TileSnapshot(pluginId = id, contentKey = "k", title = title, unit = unit, tone = tone, subtitle = subtitle)

internal fun sizesOf(vararg pairs: Pair<String, TileSize>): (List<GlassesHub.LauncherEntry>) -> Map<String, TileSize?> {
    val map = pairs.toMap()
    return { list -> list.associate { it.id to map[it.id] } }
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
