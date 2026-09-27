package com.anezium.rokidbus.glasses

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.motion.TileRect
import com.anezium.rokidbus.shared.motion.TileRectTween

/**
 * Drives [target]'s `left/top/width/height` from one [Rect] to another at `duration-structural`
 * — the tapped tile's rect tweening into the destination panel's full-safe-area rect on open, and
 * the reverse on close.
 *
 * An additive layer over Delivery 1's instant show/hide: [enabled] `false`, or the platform's
 * reduced-motion signal on, both skip straight to [to] with no tween at all — the tile becomes
 * the open panel (or the closed tile) instantly, exactly Delivery 1's behavior, per the design
 * system's rule that a static end-state must convey the same thing motion does when motion is
 * turned off.
 *
 * [expand] and [collapse] are the same operation; the two names exist only so a call site reads
 * as what it's doing.
 */
class TileExpansionAnimator(private val context: Context) {
    var enabled: Boolean = true

    private var animator: ValueAnimator? = null

    fun expand(target: View, from: Rect, to: Rect, onSettled: (() -> Unit)? = null) = run(target, from, to, onSettled)

    fun collapse(target: View, from: Rect, to: Rect, onSettled: (() -> Unit)? = null) = run(target, from, to, onSettled)

    /** Cancels any tween in flight, leaving [target] wherever it currently sits — a caller that
     * wants a consistent end state (rather than a stuck mid-transition) must apply one itself. */
    fun cancel() {
        animator?.cancel()
        animator = null
    }

    private fun run(target: View, from: Rect, to: Rect, onSettled: (() -> Unit)?) {
        animator?.cancel()
        animator = null
        if (!enabled || ReducedMotion.isEnabled(context)) {
            applyRect(target, to)
            onSettled?.invoke()
            return
        }
        val fromRect = from.toTileRect()
        val toRect = to.toTileRect()
        val next = ValueAnimator.ofFloat(0f, 1f)
        next.duration = RokidHudTokens.DURATION_STRUCTURAL_MS
        next.addUpdateListener {
            val fraction = it.animatedValue as Float
            applyRect(target, TileRectTween.at(fromRect, toRect, fraction).toAndroidRect())
        }
        next.addListener(
            object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    // A rapid tap-then-back cancels the tween mid-flight; snapping to the target
                    // rect here is what keeps the view hierarchy in a consistent end state
                    // instead of stuck at whatever fraction it was interrupted at.
                    cancelled = true
                    applyRect(target, to)
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onSettled?.invoke()
                }
            },
        )
        animator = next
        next.start()
    }

    private fun applyRect(target: View, rect: Rect) {
        val params = target.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        params.width = rect.width()
        params.height = rect.height()
        params.leftMargin = rect.left
        params.topMargin = rect.top
        target.layoutParams = params
    }
}

private fun Rect.toTileRect(): TileRect = TileRect(left, top, right, bottom)

private fun TileRect.toAndroidRect(): Rect = Rect(left, top, right, bottom)
