package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * The generic closed-state tile: centered icon + name, laid out per [TileSize]. This is the only
 * renderer Delivery 1 ships — every plugin gets it regardless of what it declares in
 * `TILE_SIZES` — and it stays the permanent fallback in Delivery 3 for any plugin that never
 * adopts the tile-data contract, so building it now means that delivery inherits this renderer
 * instead of building its own.
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
        setTextColor(RokidHudTokens.TEXT_PRIMARY)
        typeface = RokidHudTokens.bodyTypeface()
        textSize = RokidHudTokens.BODY_TEXT_SIZE_SP
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    init {
        val content = LinearLayout(context).apply {
            orientation = if (size == TileSize.WIDE) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val iconSizeDp = if (size == TileSize.SMALL) RokidHudTokens.ICON_MD else RokidHudTokens.ICON_LG
        content.addView(
            icon,
            LinearLayout.LayoutParams(
                RokidHudTokens.dp(context, iconSizeDp),
                RokidHudTokens.dp(context, iconSizeDp),
            ),
        )
        // SMALL shows icon only, per the roadmap's "icon only at SMALL, icon + two-line name at
        // LARGE" split; every other declared size gets the name too.
        if (size != TileSize.SMALL) {
            content.addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (size == TileSize.WIDE) {
                        marginStart = RokidHudTokens.dp(context, RokidHudTokens.SPACE_1)
                    } else {
                        topMargin = RokidHudTokens.dp(context, RokidHudTokens.SPACE_1)
                    }
                },
            )
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        label.text = entry.displayName
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
