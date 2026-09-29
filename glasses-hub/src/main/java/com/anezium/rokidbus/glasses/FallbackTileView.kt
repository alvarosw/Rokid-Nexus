package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.hud.HomeChrome
import com.anezium.rokidbus.glasses.hud.HomeItemView
import com.anezium.rokidbus.glasses.hud.HudLoaderView
import com.anezium.rokidbus.glasses.hud.HudType
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * The generic closed-state tile: an icon and a `label`-styled, uppercase plugin name pinned to the
 * top-left corner on every declared [TileSize] — a 1x1 tile is not exempted from it. It stays the permanent fallback for any plugin that never
 * adopts the tile-data contract, which is also what would fill the box below the header.
 *
 * Every color here comes from [RokidHudTokens] — never [com.anezium.rokidbus.client.ui.BusTheme]
 * and never a literal, per the design system's single-hue rule. Sizes are token pixels.
 */
internal class FallbackTileView(context: Context, size: TileSize) : FrameLayout(context), HomeItemView {
    override var homeFocused: Boolean = false
        private set
    override var homeOpening: Boolean = false
        private set

    private val icon = ImageView(context)
    private val label = HudType.label(TextView(context)).apply { maxLines = 2 }
    private val loader = HudLoaderView(context).apply { visibility = GONE }

    init {
        // Icon over name rather than side by side: at 106 px a beside-the-icon name leaves ~70 px,
        // which cuts "NAVIGATION" mid-word; stacked, the name gets the tile's full inner width.
        val header = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val iconSize = RokidHudTokens.ICON_SM
        header.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
        header.addView(
            label,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = RokidHudTokens.SPACE_1
            },
        )
        addView(
            header,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START),
        )
        addView(
            loader,
            LayoutParams(LayoutParams.MATCH_PARENT, HudLoaderView.TRACK_HEIGHT * 2, Gravity.BOTTOM),
        )
        val pad = RokidHudTokens.SPACE_2
        setPadding(pad, pad, pad, pad)
        applyChrome()
    }

    /**
     * [iconLoader] defaults to the real [GlassesHub.launcherDrawable] and is only ever overridden
     * in tests — Robolectric doesn't have this module's Android resources loaded, and a real
     * icon lookup would throw there for reasons that have nothing to do with what this view's
     * tests actually check.
     */
    fun bind(
        entry: GlassesHub.LauncherEntry,
        iconLoader: (Context, GlassesHub.LauncherEntry) -> Drawable = GlassesHub::launcherDrawable,
    ) {
        icon.setImageDrawable(iconLoader(context, entry))
        label.text = entry.displayName.uppercase()
    }

    /** Focused = `surface-selected` fill, 2 px `focus` border, `focus`-intensity text and icon. */
    override fun setFocused(focused: Boolean) {
        if (homeFocused == focused) return
        homeFocused = focused
        applyChrome()
    }

    override fun setOpening(opening: Boolean) {
        if (homeOpening == opening) return
        homeOpening = opening
        loader.visibility = if (opening) VISIBLE else GONE
        loader.setActive(opening)
    }

    private fun applyChrome() {
        background = if (homeFocused) HomeChrome.focused() else HomeChrome.rest()
        val intensity = if (homeFocused) RokidHudTokens.FOCUS else RokidHudTokens.TEXT_SECONDARY
        label.setTextColor(intensity)
        icon.imageTintList = ColorStateList.valueOf(if (homeFocused) RokidHudTokens.FOCUS else RokidHudTokens.TEXT_PRIMARY)
    }
}
