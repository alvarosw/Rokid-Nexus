package com.anezium.rokidbus.glasses.hud

import android.content.Context
import android.widget.FrameLayout
import com.anezium.rokidbus.glasses.NexusSurface
import com.anezium.rokidbus.glasses.SurfaceHudView

/**
 * The surface host inside [HudHost]: the existing [SurfaceHudView] for every surface kind, Ink
 * included. It only draws what [SurfaceController][com.anezium.rokidbus.glasses.SurfaceController]
 * gives it; ordering, decoding and the Ink first-frame gate stay with the controller and the view.
 */
internal class AppLayer(context: Context) : FrameLayout(context) {
    private val hud = SurfaceHudView(context)
    private var presented: NexusSurface? = null

    init {
        addView(hud, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    val surfaceId: String? get() = presented?.surfaceId

    /** Renders [surface] unless this exact instance is already on the view. */
    fun present(surface: NexusSurface) {
        if (presented === surface) return
        presented = surface
        hud.render(surface)
    }

    fun clear() {
        if (presented == null) return
        presented = null
        hud.render(null)
    }

    fun focusContent() {
        if (!hud.hasFocus()) hud.requestFocus()
    }
}
