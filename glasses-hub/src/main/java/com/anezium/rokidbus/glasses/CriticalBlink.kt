package com.anezium.rokidbus.glasses

import android.os.Handler
import android.os.Looper
import android.view.View
import com.anezium.rokidbus.client.ui.RokidHudTokens

/**
 * The design system's `critical` motion pattern: exactly 3 blinks at `duration-default`, then a
 * stable settled state — never a continuous loop, per the design system's rule that at most one
 * `critical` element is ever on screen. No tile needs this in Delivery 2 (no live data exists
 * yet), but it is built once here, to spec, as a generic primitive so Delivery 5's plugins don't
 * each reimplement it slightly differently.
 *
 * Driven by a plain [Handler] step chain rather than a frame-interpolated [android.animation.ValueAnimator]
 * — a blink is a discrete on/off sequence, not a continuous tween, and a step chain is exactly
 * as easy to cancel and far easier to assert against in a Robolectric test than sampling
 * animator frames.
 */
object CriticalBlink {
    private const val BLINK_COUNT = 3
    private const val DIM_ALPHA = 0.2f
    private const val SETTLED_ALPHA = 1f

    /** Returned so a caller can cancel a blink in progress — e.g. the tile is replaced or scrolls away. */
    class Handle internal constructor(private val onCancel: () -> Unit) {
        fun cancel() = onCancel()
    }

    fun animate(target: View, onSettled: (() -> Unit)? = null): Handle {
        val handler = Handler(Looper.getMainLooper())
        val totalSteps = BLINK_COUNT * 2
        var step = 0
        var cancelled = false
        var tick: (() -> Unit)? = null
        tick = {
            if (!cancelled) {
                if (step < totalSteps) {
                    target.alpha = if (step % 2 == 0) DIM_ALPHA else SETTLED_ALPHA
                    step++
                    handler.postDelayed({ tick?.invoke() }, RokidHudTokens.DURATION_DEFAULT_MS)
                } else {
                    target.alpha = SETTLED_ALPHA
                    onSettled?.invoke()
                }
            }
        }
        tick()
        return Handle {
            cancelled = true
            handler.removeCallbacksAndMessages(null)
        }
    }
}
