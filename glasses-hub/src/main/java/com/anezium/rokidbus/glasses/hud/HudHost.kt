package com.anezium.rokidbus.glasses.hud

import android.content.Context
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.logError

/**
 * The one window of the launcher and of every overlay-path surface (docs/ui-rewrite/00-architecture
 * §2.3): a persistent `TYPE_ACCESSIBILITY_OVERLAY` holding a [HomeLayer] and an [AppLayer] that are
 * switched by visibility. It is attached when the machine leaves Hidden or External and detached
 * only when it enters them again, never between Home, Opening and App.
 *
 * The window's own layout params are set once. Nothing here calls `updateViewLayout` (HARDWARE W3):
 * motion moves child views inside this fixed window. [sync] is told what the machine's screen is and
 * lays the layers out for it at once; [HudMorph] then animates the open and close between the home
 * and the app toward that state, and can be interrupted by the next [sync] at any moment.
 */
internal class HudHost(
    context: Context,
    private val windowManager: WindowManager,
    val geometry: HudGeometry = HudGeometry.DEFAULT,
    motion: HudMotionDriver = HudMotionDriver.forContext(context),
    val home: HomeLayer = HomeLayer(context, motion = motion),
    private val stack: AmbientStack = AmbientStack.main,
) {
    val app = AppLayer(context)
    private val panel = MorphPanelView(context)
    private val backdrop = View(context).apply {
        setBackgroundColor(RokidHudTokens.GROUND)
        visibility = View.GONE
    }
    private val root = HudRootView(context)
    private val morph = HudMorph(home, app, panel, backdrop, geometry, motion) { layOutStatically() }

    /** The screen the layers are laid out for, whatever motion is still playing toward it. */
    private var shown: HudScreen = HudScreen.Hidden

    var isAttached = false
        private set

    private var windowParams: WindowManager.LayoutParams? = null

    private val ambientWindow = object : AmbientWindow {
        override val layer = AmbientLayer.HOST

        override fun readd(): Boolean {
            val params = windowParams ?: return false
            return runCatching {
                windowManager.removeView(root)
                windowManager.addView(root, params)
            }.isSuccess
        }
    }

    init {
        val frame = FrameLayout(context)
        frame.addView(backdrop, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(app, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(home, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(frame, geometry.viewportLayoutParams())
        panel.visibility = View.GONE
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
        windowParams = params
        // Overlays stack in the order they were added: the stack puts the ambient layers that were
        // already up back above this window.
        stack.added(ambientWindow)
        return true
    }

    fun detach() {
        if (!isAttached) return
        isAttached = false
        runCatching { windowManager.removeView(root) }
        windowParams = null
        stack.removed(AmbientLayer.HOST)
        morph.snap()
        shown = HudScreen.Hidden
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
        val previous = shown
        shown = screen
        val homeVisible = isHomeVisible(screen)
        val appVisible = isAppVisible(screen)
        val morphing = morph.handle(HudMorphPlan.of(previous, screen))
        if (!morphing) showLayers(homeVisible, appVisible)
        if (!isAttached) return
        if (homeVisible && !isHomeVisible(previous)) root.requestFocus()
        if (!homeVisible && appVisible) app.focusContent()
    }

    private fun layOutStatically() {
        showLayers(isHomeVisible(shown), isAppVisible(shown))
    }

    private fun isHomeVisible(screen: HudScreen) = screen is HudScreen.Home || screen is HudScreen.Opening

    private fun isAppVisible(screen: HudScreen): Boolean {
        val beneath = when (screen) {
            is HudScreen.Home -> screen.beneath
            is HudScreen.Opening -> screen.home.beneath
            else -> null
        }
        return screen is HudScreen.App || beneath is HudScreen.App
    }

    private fun showLayers(homeVisible: Boolean, appVisible: Boolean) {
        home.visibility = if (homeVisible) View.VISIBLE else View.GONE
        app.visibility = if (appVisible) View.VISIBLE else View.GONE
    }

    /** The window's content, for a test that draws the host without a window manager. */
    internal val contentViewForTest: View get() = root

    internal val panelForTest: MorphPanelView get() = panel

    internal val backdropForTest: View get() = backdrop

    internal val morphForTest: HudMorph get() = morph

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
