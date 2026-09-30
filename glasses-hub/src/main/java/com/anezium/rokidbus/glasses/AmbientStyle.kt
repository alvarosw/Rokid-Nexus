package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.hud.HudMotionDriver

/**
 * Shared drawing rules of the ambient layers (notice band, pin, activity island, badge, pointer):
 * pixels and [RokidHudTokens] intensities only. Text styles come from [SurfaceType], outlines from
 * [SurfaceChrome].
 */
internal object AmbientStyle {
    /**
     * `Panel` for a window that floats over other windows. The fill is `ground` (black): on the
     * optic that is an unlit pixel, and in the compositor it is what keeps the windows below from
     * showing through the text, which a transparent panel would let happen.
     */
    fun panel(): GradientDrawable = GradientDrawable().apply {
        setColor(RokidHudTokens.GROUND)
        setStroke(RokidHudTokens.BORDER_DEFAULT, RokidHudTokens.LINE)
        cornerRadius = RokidHudTokens.RADIUS_PANEL.toFloat()
    }

    /** [drawable] redrawn in one token intensity; the drawables themselves carry a legacy green. */
    fun tinted(drawable: Drawable, color: Int): Drawable = drawable.mutate().apply {
        colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    /** An icon slot of [sizePx] showing [drawable] at [color]'s intensity. */
    fun icon(context: Context, drawable: Drawable, sizePx: Int, color: Int): ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageDrawable(tinted(drawable, color))
        minimumWidth = sizePx
        minimumHeight = sizePx
    }

    /**
     * Shows [drawable] in [view] at [color]'s intensity, [sizePx] tall (and wide, except a route
     * mark, which is as wide as its text), or hides the view when there is none.
     */
    fun glyph(view: ImageView, drawable: Drawable?, sizePx: Int, color: Int) {
        if (drawable == null) {
            view.setImageDrawable(null)
            view.visibility = android.view.View.GONE
            return
        }
        // A route mark draws its own token intensities.
        view.setImageDrawable(if (drawable is ActivityBadgeDrawable) drawable else tinted(drawable, color))
        view.layoutParams = view.layoutParams.apply {
            width = if (drawable is ActivityBadgeDrawable) drawable.intrinsicWidth.coerceAtLeast(sizePx) else sizePx
            height = sizePx
        }
        view.visibility = android.view.View.VISIBLE
    }

    /**
     * The one motion driver of the ambient windows: Choreographer frames, reduced motion and the
     * duration scale read from the system at the start of every animation.
     */
    fun motionDriver(context: () -> Context?): HudMotionDriver = HudMotionDriver(
        com.anezium.rokidbus.glasses.hud.ChoreographerFrameClock(),
        { context()?.let(ReducedMotion::isEnabled) ?: false },
        { context()?.let(ReducedMotion::durationScale) ?: 1f },
    )
}

/**
 * A number on a [HudMotionDriver] that says when it has come to rest, which is what
 * `AmbientStack` waits for before it re-adds a window (docs/ui-rewrite/00-architecture §6, U6):
 * [onIdle] runs after an animation ends, and after a snap or a cancel that stopped one.
 */
internal class AmbientMotionValue(
    driver: HudMotionDriver,
    initial: Float,
    onChange: (Float) -> Unit,
    private val onIdle: () -> Unit,
) {
    private val value = driver.value(initial, onChange)

    val current: Float get() = value.current

    val isRunning: Boolean get() = value.isRunning

    /** A retarget continues from [current]; only the animation that reaches [target] calls [onEnd]. */
    fun animateTo(target: Float, durationMs: Long, onEnd: (() -> Unit)? = null) {
        value.animateTo(target, durationMs, onEnd = {
            onEnd?.invoke()
            onIdle()
        })
    }

    fun snapTo(target: Float) {
        val wasRunning = value.isRunning
        value.snapTo(target)
        if (wasRunning) onIdle()
    }

    fun cancel() {
        val wasRunning = value.isRunning
        value.cancel()
        if (wasRunning) onIdle()
    }
}
