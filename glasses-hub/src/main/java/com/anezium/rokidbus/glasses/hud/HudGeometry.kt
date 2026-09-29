package com.anezium.rokidbus.glasses.hud

import android.widget.FrameLayout
import com.anezium.rokidbus.client.ui.RokidHudTokens

/**
 * The one place that says where the lit part of the display is inside the HUD window. The home and
 * app layers are laid out inside [viewport]; nothing else reads the display size for layout.
 *
 * The official screen is 480x640 px (owner, from the official spec, 2026-09-29; HARDWARE D1), so
 * [DEFAULT] is that whole screen at the window origin. The earlier 480x352 / 480x400 figures are
 * superseded; this stays the single value to change if that ever moves.
 */
data class HudGeometry(val viewport: Viewport = Viewport()) {
    /** Pixels, in window coordinates. */
    data class Viewport(
        val left: Int = 0,
        val top: Int = 0,
        val width: Int = RokidHudTokens.CANVAS_WIDTH,
        val height: Int = RokidHudTokens.CANVAS_HEIGHT,
    )

    fun viewportLayoutParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(viewport.width, viewport.height).apply {
            leftMargin = viewport.left
            topMargin = viewport.top
        }

    companion object {
        val DEFAULT = HudGeometry()
    }
}
