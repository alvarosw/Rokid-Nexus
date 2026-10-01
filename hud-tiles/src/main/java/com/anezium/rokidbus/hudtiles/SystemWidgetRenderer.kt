package com.anezium.rokidbus.hudtiles

import android.graphics.drawable.Drawable
import com.anezium.rokidbus.shared.tile.TileSize
import java.util.Locale
import java.util.TimeZone

/**
 * What a system widget shows, sampled by the hub that draws it. Hub-internal: no plugin sends it,
 * it never crosses the link, and it is not one of the public `TileContent` templates.
 */
sealed interface SystemWidgetContent {
    /** The wall time [epochMs] in [timeZone], formatted for [locale]. */
    data class Clock(
        val epochMs: Long,
        val timeZone: TimeZone,
        val locale: Locale,
        val use24Hour: Boolean,
    ) : SystemWidgetContent

    /** The glasses' and the phone's charge, and whether the glasses-phone link is up. Null = unknown. */
    data class Status(
        val glasses: Battery?,
        val phone: Battery?,
        val phoneLinked: Boolean,
    ) : SystemWidgetContent

    data class Battery(val level: Int, val charging: Boolean)
}

/** Everything a system widget's drawing depends on; [nowElapsed] is when [content] was sampled. */
data class SystemWidgetInput(
    /** The widget's display name; the header shows it uppercase, as a plugin tile's. */
    val name: String,
    val icon: Drawable? = null,
    val content: SystemWidgetContent,
    val nowElapsed: Long = 0L,
)

/**
 * The renderer's entry point for system widgets: the same header, text styles and ops as a plugin
 * tile, one layout class per widget. A widget is never focused, so nothing here brightens.
 */
object SystemWidgetRenderer {
    fun layout(input: SystemWidgetInput, size: TileSize): TileLayout = when (val content = input.content) {
        is SystemWidgetContent.Clock -> ClockWidgetLayout.layout(input, content, size)
        is SystemWidgetContent.Status -> StatusWidgetLayout.layout(input, content, size)
    }

    /**
     * When [input]'s drawing next changes on its own (the next minute), or null when only new data
     * changes it. A host schedules one redraw for it, and only while the widget is on screen.
     */
    fun nextChangeAtElapsed(input: SystemWidgetInput): Long? = when (val content = input.content) {
        is SystemWidgetContent.Clock -> ClockWidgetLayout.nextChangeAtElapsed(input, content)
        is SystemWidgetContent.Status -> null
    }

    internal fun header(input: SystemWidgetInput, width: Int, cols: Int): TileHeader.Placed =
        TileHeader.layout(TileRenderInput(name = input.name, icon = input.icon), width, cols)
}
