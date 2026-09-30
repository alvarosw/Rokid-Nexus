package com.anezium.rokidbus.glasses

import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.hud.HudType

/**
 * The design system's text styles and outlines for plugin-surface content. Sizes are pixels and
 * every color is a [RokidHudTokens] intensity; sans for text, mono for numbers and times.
 */
internal object SurfaceType {
    /** `heading`: 16 / 22 / 600, one line. */
    fun heading(view: TextView, color: Int = RokidHudTokens.TEXT_PRIMARY): TextView = view.apply {
        setTextColor(color)
        typeface = RokidHudTokens.headingTypeface()
        RokidHudTokens.applyTextSize(this, RokidHudTokens.HEADING_TEXT_SIZE)
        lineHeight = HEADING_LINE
        includeFontPadding = false
        maxLines = 1
    }

    /** `display`: 22 / 28 / 600. At most one per screen. */
    fun display(view: TextView, color: Int = RokidHudTokens.TEXT_PRIMARY): TextView = view.apply {
        setTextColor(color)
        typeface = RokidHudTokens.displayTypeface()
        RokidHudTokens.applyTextSize(this, RokidHudTokens.DISPLAY_TEXT_SIZE)
        lineHeight = DISPLAY_LINE
        includeFontPadding = false
        maxLines = 1
    }

    /** `body`: 14 / 20 / 400. */
    fun body(view: TextView, color: Int = RokidHudTokens.TEXT_PRIMARY): TextView = HudType.body(view, color).apply {
        lineHeight = BODY_LINE
    }

    /** `body-small`: 12 / 16 / 400. */
    fun bodySmall(view: TextView, color: Int = RokidHudTokens.TEXT_SECONDARY): TextView = view.apply {
        setTextColor(color)
        typeface = RokidHudTokens.bodyTypeface()
        RokidHudTokens.applyTextSize(this, RokidHudTokens.BODY_SMALL_TEXT_SIZE)
        lineHeight = BODY_SMALL_LINE
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** `label`: 11 / 14 / 500, `text-secondary`. The caller uppercases the text. */
    fun label(view: TextView): TextView = HudType.label(view).apply { lineHeight = LABEL_LINE }

    /** `data`: 13 / 18 / 500 mono, the value of a `DataReadout`. */
    fun data(view: TextView, color: Int = RokidHudTokens.TEXT_PRIMARY): TextView = view.apply {
        setTextColor(color)
        typeface = RokidHudTokens.dataTypeface()
        RokidHudTokens.applyTextSize(this, RokidHudTokens.DATA_TEXT_SIZE)
        lineHeight = DATA_LINE
        includeFontPadding = false
        maxLines = 1
    }

    /** `mono`: 11 / 14 / 400, codes, counters and clocks. */
    fun mono(view: TextView, color: Int = RokidHudTokens.TEXT_SECONDARY): TextView = HudType.mono(view, color).apply {
        lineHeight = MONO_LINE
    }

    /** Lets a text wrap over [lines] lines instead of the one the styles above default to. */
    fun wrap(view: TextView, lines: Int): TextView = view.apply {
        isSingleLine = false
        setHorizontallyScrolling(false)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }

    /** Slow horizontal scroll for a name too long for its slot; `isSelected` keeps it running without focus. */
    fun marquee(view: TextView): TextView = view.apply {
        isSingleLine = true
        setHorizontallyScrolling(true)
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isSelected = true
    }

    const val HEADING_LINE = 22
    const val DISPLAY_LINE = 28
    const val BODY_LINE = 20
    const val BODY_SMALL_LINE = 16
    const val LABEL_LINE = 14
    const val DATA_LINE = 18
    const val MONO_LINE = 14
}

internal object SurfaceChrome {
    /** `Panel`: outline only, `line` border, `radius-panel`, transparent fill. */
    fun panel(): GradientDrawable = GradientDrawable().apply {
        setColor(android.graphics.Color.TRANSPARENT)
        setStroke(RokidHudTokens.BORDER_DEFAULT, RokidHudTokens.LINE)
        cornerRadius = RokidHudTokens.RADIUS_PANEL.toFloat()
    }

    /** `ListItem` focused: `surface-selected` fill, 2 px `focus` border. */
    fun focused(): GradientDrawable = GradientDrawable().apply {
        setColor(RokidHudTokens.SURFACE_SELECTED)
        setStroke(RokidHudTokens.BORDER_STRONG, RokidHudTokens.FOCUS)
        cornerRadius = RokidHudTokens.RADIUS_CONTROL.toFloat()
    }

    /** A control outline (`line-control`, `radius-control`): the route chip and the text field at rest. */
    fun control(): GradientDrawable = GradientDrawable().apply {
        setColor(android.graphics.Color.TRANSPARENT)
        setStroke(RokidHudTokens.BORDER_DEFAULT, RokidHudTokens.LINE_CONTROL)
        cornerRadius = RokidHudTokens.RADIUS_CONTROL.toFloat()
    }
}
