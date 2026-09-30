package com.anezium.rokidbus.glasses.hud

import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.motion.TileRect
import com.anezium.rokidbus.shared.motion.TileRectTween

/**
 * What the host has to animate when the machine's screen changes from [HudMorphPlan.of]'s `prev`
 * to `next`. It is derived from the two screens alone, so it is pure and JVM-testable; the
 * animation is never the reason for a screen change.
 */
internal sealed interface MorphPlan {
    /** Nothing animates: whatever runs is snapped and the layers are laid out statically. */
    data object Instant : MorphPlan

    /** Same visual state as before (an info change, a repeated sync): leave any motion alone. */
    data object Keep : MorphPlan

    /** Home to Opening: the item's panel grows to the app safe area and shows a `Loader`. */
    data class Open(val pluginId: String) : MorphPlan

    /** Opening to the plugin's surface: the surface appears inside the panel. */
    data object Reveal : MorphPlan

    /** Opening failed or was dismissed, or the app closed to Home: the panel collapses onto the item. */
    data class Collapse(val pluginId: String) : MorphPlan
}

internal object HudMorphPlan {
    fun of(prev: HudScreen, next: HudScreen): MorphPlan = when (next) {
        is HudScreen.Opening -> when {
            // A launcher opened over a surface keeps that surface drawn beneath it: no panel.
            next.home.beneath != null -> MorphPlan.Instant
            prev is HudScreen.Home && prev.beneath == null -> MorphPlan.Open(next.pluginId)
            prev is HudScreen.Opening && prev.pluginId == next.pluginId -> MorphPlan.Open(next.pluginId)
            else -> MorphPlan.Instant
        }
        is HudScreen.App -> when {
            prev is HudScreen.Opening && prev.home.beneath == null && next.origin == Origin.HOME -> MorphPlan.Reveal
            prev is HudScreen.App -> MorphPlan.Keep
            else -> MorphPlan.Instant
        }
        is HudScreen.Home -> when {
            next.beneath != null -> MorphPlan.Instant
            prev is HudScreen.Opening && prev.home.beneath == null -> MorphPlan.Collapse(prev.pluginId)
            prev is HudScreen.App && prev.origin == Origin.HOME ->
                next.selectedId?.let { MorphPlan.Collapse(it) } ?: MorphPlan.Instant
            // A selection move while a close runs is a new state: the close ends where it stands.
            prev is HudScreen.Home && prev.beneath == null && prev.selectedId == next.selectedId -> MorphPlan.Keep
            else -> MorphPlan.Instant
        }
        else -> MorphPlan.Instant
    }
}

/**
 * The morphing panel: `Panel` style (1 px `line` border, `radius-panel`, transparent fill), drawn
 * inside the host window between the home and the app layers. At the start of an open, and the end of
 * a close, it is the item it replaces: the item's `surface-selected` fill and 2 px `focus` border and
 * the item's own content, kept as a bitmap. It cross-fades to the panel style as it grows, so at no
 * point are an item frame and a panel frame both on screen. Everything here is drawing: the rect and
 * the alphas are set per frame and nothing is laid out.
 */
internal class MorphPanelView(context: Context) : FrameLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = RectF()
    private val rect = Rect()
    private var chromeMix = 0f
    private var ghost: Bitmap? = null
    private var loading = false

    private val statusColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val loader = HudLoaderView(context)

    init {
        setWillNotDraw(false)
        val label = HudType.label(TextView(context)).apply { text = "OPENING" }
        statusColumn.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        statusColumn.addView(
            loader,
            LinearLayout.LayoutParams(LOADER_WIDTH, HudLoaderView.TRACK_HEIGHT * 2).apply { topMargin = RokidHudTokens.SPACE_2 },
        )
        addView(statusColumn, LayoutParams(LOADER_WIDTH, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        statusColumn.alpha = 0f
    }

    /** The content of the item the panel stands for; drawn at the panel's top-left while it fades. */
    fun setGhost(bitmap: Bitmap?) {
        ghost = bitmap
        invalidate()
    }

    /** Whether an open is in flight: only then does the panel say so, in the loader and its label. */
    fun setLoading(loading: Boolean) {
        this.loading = loading
        loader.setActive(loading)
        if (!loading) statusColumn.alpha = 0f
    }

    /** [progress] is the morph, 0 at the item and 1 at the app safe area. */
    fun setFrame(bounds: Rect, progress: Float) {
        rect.set(bounds)
        chromeMix = (progress / CHROME_SPAN).coerceIn(0f, 1f)
        val contentAlpha = ((progress - CONTENT_FROM) / (1f - CONTENT_FROM)).coerceIn(0f, 1f)
        statusColumn.alpha = if (loading) contentAlpha else 0f
        statusColumn.translationX = rect.exactCenterX() - LOADER_WIDTH / 2f
        statusColumn.translationY = rect.exactCenterY() - STATUS_HEIGHT / 2f
        invalidate()
    }

    /** What the panel is drawing, for tests: the rect and how far the item chrome has become the panel's. */
    val frameForTest: Rect get() = Rect(rect)
    val chromeMixForTest: Float get() = chromeMix
    val loaderRunningForTest: Boolean get() = loader.isAnimating

    override fun dispatchDraw(canvas: Canvas) {
        if (rect.width() > 0 && rect.height() > 0) {
            val stroke = lerp(RokidHudTokens.BORDER_STRONG.toFloat(), RokidHudTokens.BORDER_DEFAULT.toFloat(), chromeMix)
            val radius = lerp(RokidHudTokens.RADIUS_CONTROL.toFloat(), RokidHudTokens.RADIUS_PANEL.toFloat(), chromeMix)
            // A stroke is drawn inside the bounds, as GradientDrawable does for the item it replaces.
            frame.set(rect)
            frame.inset(stroke / 2f, stroke / 2f)
            paint.style = Paint.Style.FILL
            paint.color = RokidHudTokens.scaleAlpha(RokidHudTokens.SURFACE_SELECTED, 1f - chromeMix)
            canvas.drawRoundRect(frame, radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = ArgbEvaluator().evaluate(chromeMix, RokidHudTokens.FOCUS, RokidHudTokens.LINE) as Int
            canvas.drawRoundRect(frame, radius, radius, paint)
            ghost?.let { bitmap ->
                paint.style = Paint.Style.FILL
                paint.alpha = ((1f - chromeMix) * 255f).toInt()
                if (paint.alpha > 0) canvas.drawBitmap(bitmap, rect.left.toFloat(), rect.top.toFloat(), paint)
                paint.alpha = 255
            }
        }
        super.dispatchDraw(canvas)
    }

    override fun hasOverlappingRendering(): Boolean = false

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        /** The item chrome has become the panel's by this share of the morph. */
        const val CHROME_SPAN = 0.33f

        /** The status content fades in over the last part of the morph, once the panel is large. */
        const val CONTENT_FROM = 0.6f
        const val LOADER_WIDTH = 120
        const val STATUS_HEIGHT = 26
    }
}

/**
 * The open/close morph of [HudHost] (architecture §2.6): everything the wearer sees between the
 * launcher and an app, as pure views animating toward the state the machine already reached.
 *
 * Two [HudMotionDriver] values carry it: `progress` (0 = the panel is the item, 1 = the app safe
 * area) and `reveal` (0 = the surface is not drawn, 1 = fully in). Every visual is a function of
 * those two, so any retarget or snap lands on a consistent layout:
 *
 *  - the home is dimmed with a plain alpha ([DIM_ALPHA]) and not drawn inside the panel's rect;
 *  - a `ground` backdrop sits under both layers for as long as the morph runs, because the home and
 *    the app paint their own `ground` and a dimmed or fading layer would otherwise let whatever is
 *    behind the window shine through where the other layer is not opaque yet. `ground` is unlit
 *    on the optic, so it changes nothing there;
 *  - the surface, while it appears, is clipped to the panel's rect and cross-fades in.
 *
 * Nothing in here can change the machine: no callback emits an event, sends on the bus or reads
 * anything but the views. A new [MorphPlan] retargets from the current values; [Instant][MorphPlan.Instant]
 * snaps everything to the static layout [onSettled] draws.
 */
internal class HudMorph(
    private val home: HomeLayer,
    private val app: AppLayer,
    private val panel: MorphPanelView,
    private val backdrop: android.view.View,
    private val geometry: HudGeometry,
    motion: HudMotionDriver,
    private val onSettled: () -> Unit,
) {
    private enum class Mode { IDLE, OPEN, REVEAL, COLLAPSE }

    private var mode = Mode.IDLE
    private var pluginId: String? = null
    private val from = Rect()
    private val to = Rect()
    private val bounds = Rect()
    private val progress = motion.value(0f) { render() }
    private val reveal = motion.value(0f) { render() }

    val isActive: Boolean get() = mode != Mode.IDLE

    /** Returns true when the morph now draws the layers, false when the caller lays them out statically. */
    fun handle(plan: MorphPlan): Boolean {
        when (plan) {
            MorphPlan.Instant -> snap()
            MorphPlan.Keep -> Unit
            is MorphPlan.Open -> open(plan.pluginId)
            MorphPlan.Reveal -> reveal()
            is MorphPlan.Collapse -> collapse(plan.pluginId)
        }
        return isActive
    }

    /** Ends everything on the static layout; the caller lays the layers out for the screen. */
    fun snap() {
        progress.cancel()
        reveal.cancel()
        mode = Mode.IDLE
        pluginId = null
        panel.setGhost(null)
        panel.setLoading(false)
        panel.visibility = android.view.View.GONE
        backdrop.visibility = android.view.View.GONE
        home.setCover(null)
        home.alpha = 1f
        app.alpha = 1f
        app.clipBounds = null
    }

    private fun open(id: String) {
        if (mode == Mode.OPEN && pluginId == id) return
        if (mode == Mode.COLLAPSE && pluginId == id) {
            // Tap right after back: the panel that was going home turns around from where it is.
            mode = Mode.OPEN
            reveal.snapTo(0f)
            panel.setLoading(true)
            progress.animateTo(1f, RokidHudTokens.DURATION_STRUCTURAL_MS, proportional = true)
            return
        }
        if (!begin(id)) return
        mode = Mode.OPEN
        panel.setLoading(true)
        progress.snapTo(0f)
        progress.animateTo(1f, RokidHudTokens.DURATION_STRUCTURAL_MS, proportional = true)
    }

    private fun reveal() {
        if (mode != Mode.OPEN) {
            // The open never morphed (no bounds, or a launcher over a surface): nothing to reveal in.
            snap()
            return
        }
        mode = Mode.REVEAL
        progress.animateTo(1f, RokidHudTokens.DURATION_STRUCTURAL_MS, proportional = true) { settleIfDone() }
        reveal.animateTo(1f, RokidHudTokens.DURATION_FEEDBACK_MS, proportional = true) { settleIfDone() }
        settleIfDone()
    }

    private fun collapse(id: String) {
        if (mode == Mode.COLLAPSE && pluginId == id) return
        val fresh = mode == Mode.IDLE || pluginId != id
        if (fresh && !begin(id)) return
        mode = Mode.COLLAPSE
        // A close from an open still in flight keeps saying so while it shrinks; a close from an app never does.
        if (fresh) {
            panel.setLoading(false)
            progress.snapTo(1f)
        }
        reveal.snapTo(0f)
        progress.animateTo(0f, RokidHudTokens.DURATION_STRUCTURAL_MS, proportional = true) { settleIfDone() }
        settleIfDone()
    }

    /** Captures where the panel starts and ends, and the item's content. False when the item is not on screen. */
    private fun begin(id: String): Boolean {
        snap()
        // Bounds are read on the end state: a scroll or a focus move still in flight would move the item.
        home.settleMotion()
        val item = home.itemBounds(id) ?: return false
        from.set(item)
        to.set(geometry.appBounds(home.topInsetPx))
        pluginId = id
        panel.setGhost(home.snapshotItem(id))
        return true
    }

    private fun settleIfDone() {
        val done = when (mode) {
            Mode.REVEAL -> !progress.isRunning && !reveal.isRunning && progress.current == 1f && reveal.current == 1f
            Mode.COLLAPSE -> !progress.isRunning && progress.current == 0f
            else -> false
        }
        if (!done) return
        snap()
        onSettled()
    }

    private fun render() {
        if (mode == Mode.IDLE) return
        val p = progress.current
        val a = reveal.current
        val at = TileRectTween.at(TileRect(from.left, from.top, from.right, from.bottom), TileRect(to.left, to.top, to.right, to.bottom), p)
        bounds.set(at.left, at.top, at.right, at.bottom)
        backdrop.visibility = android.view.View.VISIBLE
        panel.visibility = android.view.View.VISIBLE
        panel.alpha = 1f - a
        panel.setFrame(bounds, p)
        home.visibility = android.view.View.VISIBLE
        home.alpha = (1f + (DIM_ALPHA - 1f) * p) * (1f - a)
        home.setCover(bounds)
        if (mode == Mode.REVEAL) {
            app.visibility = android.view.View.VISIBLE
            app.alpha = a
            app.clipBounds = Rect(bounds)
        } else {
            app.visibility = android.view.View.GONE
        }
    }

    internal val progressForTest: Float get() = progress.current
    internal val revealForTest: Float get() = reveal.current

    companion object {
        /**
         * What the home dims to behind a growing panel. `text-secondary` is 48 % and the design's
         * dimmed opacity is 0.35: 72 % text at 0.48 lands at 34.6 %, so siblings recede by exactly
         * one intensity step and nothing new is invented.
         */
        const val DIM_ALPHA = 0.48f
    }
}
