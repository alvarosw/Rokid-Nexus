package com.anezium.rokidbus.client.ui

import android.content.Context
import android.widget.FrameLayout

/**
 * The required root of every grid-HUD screen (`HudFrame` in the design system). Applies
 * `safe-x`/`safe-y` automatically and never paints an opaque fill for anything but `ground` —
 * `ground` is transparent on-device, so an opaque non-ground background here would paint over
 * the real world the wearer is looking through.
 */
open class HudFrameLayout(context: Context) : FrameLayout(context) {
    init {
        setBackgroundColor(RokidHudTokens.GROUND)
        applySafeAreaPadding(topInsetPx = 0)
    }

    /**
     * Re-applies `safe-y` plus an extra top inset (pixels) on top of it — the hook the glasses side
     * uses to fold in [com.anezium.rokidbus.shared.HudModeContract]'s synced HUD position without
     * duplicating the safe-area math at every call site. Tokens are pixels, so the caller converts
     * a dp inset itself.
     */
    protected fun applySafeAreaPadding(topInsetPx: Int) {
        setPadding(
            RokidHudTokens.SAFE_X,
            RokidHudTokens.SAFE_Y + topInsetPx,
            RokidHudTokens.SAFE_X,
            RokidHudTokens.SAFE_Y,
        )
    }
}
