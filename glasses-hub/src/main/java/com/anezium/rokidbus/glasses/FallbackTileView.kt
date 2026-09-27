package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * The generic closed-state tile: an icon + `label`-styled, uppercase plugin name pinned to the
 * top-left corner, matching the reference design's header row on every declared [TileSize] — a
 * 1x1 tile is not exempted from it. This is the only renderer Delivery 1 ships — every plugin
 * gets it regardless of what it declares in `TILE_SIZES` — and it stays the permanent fallback
 * in Delivery 3 for any plugin that never adopts the tile-data contract, which is also what fills
 * the remaining box below the header with real content; Delivery 1 leaves that area empty.
 *
 * Every color here comes from [RokidHudTokens] — never [com.anezium.rokidbus.client.ui.BusTheme]
 * and never a literal, per the design system's single-hue rule.
 */
internal class FallbackTileView(context: Context, size: TileSize) : FrameLayout(context) {
    /** Test-only: the drawable set by [setSelected] doesn't expose selection state readably. */
    internal var isFocusedForTest: Boolean = false
        private set

    private val icon = ImageView(context)
    private val label = TextView(context).apply {
        // typography.label — panel-title style: uppercase, text-secondary, 0.06em tracking.
        setTextColor(RokidHudTokens.TEXT_SECONDARY)
        typeface = RokidHudTokens.labelTypeface()
        textSize = RokidHudTokens.LABEL_TEXT_SIZE_SP
        letterSpacing = RokidHudTokens.LABEL_LETTER_SPACING_EM
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    init {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconSizeDp = RokidHudTokens.dp(context, RokidHudTokens.ICON_SM)
        header.addView(icon, LinearLayout.LayoutParams(iconSizeDp, iconSizeDp))
        header.addView(
            label,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = RokidHudTokens.dp(context, RokidHudTokens.SPACE_1)
            },
        )
        addView(
            header,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START),
        )
        val pad = RokidHudTokens.dp(context, RokidHudTokens.SPACE_2)
        setPadding(pad, pad, pad, pad)
    }

    /**
     * [iconLoader] defaults to the real [GlassesHub.launcherDrawable] and is only ever overridden
     * in tests — Robolectric doesn't have this module's Android resources loaded, and a real
     * icon lookup would throw there for reasons that have nothing to do with what this view's
     * tests actually check (tile count and focus bookkeeping).
     */
    fun bind(
        entry: GlassesHub.LauncherEntry,
        iconLoader: (Context, GlassesHub.LauncherEntry) -> Drawable = GlassesHub::launcherDrawable,
    ) {
        icon.setImageDrawable(iconLoader(context, entry))
        label.text = entry.displayName.uppercase()
    }

    /** Selected = surface-selected fill + text-primary border; focused adds the 2px focus border. */
    fun setSelected(selected: Boolean, focused: Boolean) {
        isFocusedForTest = focused
        background = GradientDrawable().apply {
            setColor(if (selected) RokidHudTokens.SURFACE_SELECTED else Color.TRANSPARENT)
            val strokeColor = when {
                focused -> RokidHudTokens.FOCUS
                selected -> RokidHudTokens.TEXT_PRIMARY
                else -> RokidHudTokens.LINE
            }
            val strokeWidthDp = if (focused) RokidHudTokens.BORDER_STRONG else RokidHudTokens.BORDER_DEFAULT
            setStroke(RokidHudTokens.dp(context, strokeWidthDp), strokeColor)
            cornerRadius = RokidHudTokens.dp(context, RokidHudTokens.RADIUS_CONTROL).toFloat()
        }
    }
}
