package com.anezium.rokidbus.glasses.hud

import android.content.Context
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.anezium.rokidbus.glasses.HudOverlayStack
import com.anezium.rokidbus.glasses.logError

/**
 * The one window of the launcher and of every overlay-path surface (docs/ui-rewrite/00-architecture
 * §2.3): a persistent `TYPE_ACCESSIBILITY_OVERLAY` holding a [HomeLayer] and an [AppLayer] that are
 * switched by visibility. It is attached when the machine leaves Hidden or External and detached
 * only when it enters them again, never between Home, Opening and App.
 *
 * The window's own layout params are set once. Nothing here calls `updateViewLayout` (HARDWARE W3):
 * later motion moves child views inside this fixed window.
 */
internal class HudHost(
    context: Context,
    private val windowManager: WindowManager,
    val geometry: HudGeometry = HudGeometry.DEFAULT,
) {
    val home = HomeLayer(context)
    val app = AppLayer(context)
    private val root = HudRootView(context)

    var isAttached = false
        private set

    init {
        val frame = FrameLayout(context)
        frame.addView(app, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(home, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(frame, geometry.viewportLayoutParams())
        showLayers(homeVisible = false, appVisible = false)
    }

    fun attach(): Boolean {
        if (isAttached) return true
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        )
        val added = runCatching { windowManager.addView(root, params) }
            .onFailure { logError("HUD host window could not be added", it) }
            .isSuccess
        if (!added) return false
        isAttached = true
        // Accessibility overlays stack in the order they were added, so the ambient layers that
        // were already up have to be re-added above this window.
        HudOverlayStack.reassert()
        return true
    }

    fun detach() {
        if (!isAttached) return
        isAttached = false
        runCatching { windowManager.removeView(root) }
        app.clear()
        home.clear()
        showLayers(homeVisible = false, appVisible = false)
    }

    /**
     * Applies what [screen] needs visible. The app layer stays visible under a launcher that was
     * opened over a surface, exactly as that surface's own window used to, so an Ink surface keeps
     * drawing and its first-frame gate is unaffected.
     */
    fun sync(screen: HudScreen) {
        val beneath = when (screen) {
            is HudScreen.Home -> screen.beneath
            is HudScreen.Opening -> screen.home.beneath
            else -> null
        }
        val homeVisible = screen is HudScreen.Home || screen is HudScreen.Opening
        val appVisible = screen is HudScreen.App || beneath is HudScreen.App
        val homeWasVisible = home.visibility == View.VISIBLE
        showLayers(homeVisible, appVisible)
        if (!isAttached) return
        if (homeVisible && !homeWasVisible) root.requestFocus()
        if (!homeVisible && appVisible) app.focusContent()
    }

    private fun showLayers(homeVisible: Boolean, appVisible: Boolean) {
        home.visibility = if (homeVisible) View.VISIBLE else View.GONE
        app.visibility = if (appVisible) View.VISIBLE else View.GONE
    }

    private class HudRootView(context: Context) : FrameLayout(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
            // The glasses never enter touch mode, so a focused full-screen view would be washed in
            // the platform's translucent focus highlight: a grey veil over a see-through card.
            defaultFocusHighlightEnabled = false
        }
    }
}
