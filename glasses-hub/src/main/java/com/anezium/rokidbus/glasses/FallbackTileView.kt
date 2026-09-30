package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.FrameLayout
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.hud.FocusTransition
import com.anezium.rokidbus.glasses.hud.HomeChrome
import com.anezium.rokidbus.glasses.hud.HomeItemView
import com.anezium.rokidbus.glasses.hud.HudLoaderView
import com.anezium.rokidbus.glasses.hud.HudMotionDriver
import com.anezium.rokidbus.glasses.hud.TileHeaderView
import com.anezium.rokidbus.shared.tile.TileContentRules
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * The generic closed-state tile: an icon and a `label`-styled, uppercase plugin name on one line in the
 * top-left corner on every declared [TileSize] — a 1x1 tile is not exempted from it. It stays the
 * permanent fallback for any plugin that never adopts the tile-data contract, which is also what
 * would fill the box below the header.
 *
 * Every color here comes from [RokidHudTokens]
 * and never a literal, per the design system's single-hue rule. Sizes are token pixels.
 */
internal class FallbackTileView(
    context: Context,
    size: TileSize,
    motion: HudMotionDriver? = null,
) : FrameLayout(context), HomeItemView {
    override var homeFocused: Boolean = false
        private set
    override var homeOpening: Boolean = false
        private set

    private val header = TileHeaderView(context, nameLines = TileContentRules.NAME_LINES)
    private val loader = HudLoaderView(context).apply { visibility = GONE }
    private val focusTransition = FocusTransition(motion) { amount ->
        header.setFocusAmount(amount)
        background = HomeChrome.blended(amount, RokidHudTokens.LINE, RokidHudTokens.BORDER_DEFAULT)
    }

    init {
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
        focusTransition.set(false, animate = false)
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
    ) = header.bind(entry, iconLoader)

    /** Focused = `surface-selected` fill, 2 px `focus` border, `focus`-intensity text and icon. */
    override fun setFocused(focused: Boolean, animate: Boolean) {
        if (homeFocused == focused) return
        homeFocused = focused
        focusTransition.set(focused, animate)
    }

    override fun settleFocus() = focusTransition.settle()

    override fun drawContent(canvas: Canvas) {
        // Alpha, not visibility: a visibility change would stop and restart the loader's animator.
        loader.alpha = 0f
        dispatchDraw(canvas)
        loader.alpha = 1f
    }

    override fun setOpening(opening: Boolean) {
        if (homeOpening == opening) return
        homeOpening = opening
        loader.visibility = if (opening) VISIBLE else GONE
        loader.setActive(opening)
    }

    internal val nameForTest: String get() = header.nameText
}
