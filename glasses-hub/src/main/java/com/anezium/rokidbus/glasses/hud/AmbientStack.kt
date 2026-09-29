package com.anezium.rokidbus.glasses.hud

import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.glasses.ActivityController
import com.anezium.rokidbus.glasses.NoticeController
import com.anezium.rokidbus.glasses.PinController
import com.anezium.rokidbus.glasses.log
import com.anezium.rokidbus.glasses.logError
import java.util.EnumMap

/**
 * Every Nexus overlay window, bottom to top. The declaration order is the stacking order.
 *
 * Accessibility overlays stack in the order they were added and Android has no z-index for them, so
 * the only way to put a window above another one is to add it later. The order below is what the
 * wearer must see, and why:
 *
 *  - [HOST] the launcher and the surfaces: full screen and opaque, so everything else floats over it.
 *  - [BADGE] a phone-battery chip in the ROM launcher's status row. It only exists while no Nexus
 *    window is up; it sits just above the host so a host that appears later can never leave it on top.
 *  - [PIN] a corner label the wearer glances at. It is background information, so it is below the
 *    notice: a notice is a transient interruption and must never be covered by it (F-17).
 *  - [ACTIVITY] progress chips and flares, in the same corners a pin reserves.
 *  - [NOTICE] the band: the most interruptive thing a plugin can show.
 *  - [POINTER] the remote cursor, which has to stay visible over whatever it points at.
 *
 * Only [HOST] is focusable. Every other layer is NOT_FOCUSABLE and NOT_TOUCHABLE, so keys and touches
 * fall through to the host that owns them (HARDWARE W4).
 */
enum class AmbientLayer(val focusable: Boolean = false) {
    HOST(focusable = true),
    BADGE,
    PIN,
    ACTIVITY,
    NOTICE,
    POINTER,
}

/** One window the stack orders. Implemented by the owner of the window, which knows how to re-add it. */
interface AmbientWindow {
    val layer: AmbientLayer

    /**
     * True while the window's own animation runs. Re-adding detaches the view and would cut that
     * animation, so the stack waits for [AmbientStack.animationEnded].
     */
    val isAnimating: Boolean get() = false

    /** Removes the window and adds it again, on top of every window added so far. */
    fun readd(): Boolean
}

fun interface AmbientTimer {
    /** Runs [action] once after [delayMs]; returns the function that cancels it. */
    fun postDelayed(delayMs: Long, action: () -> Unit): () -> Unit
}

/**
 * The one owner of overlay window order. Every window is announced through [added] right after its
 * `addView` and through [removed] when it goes; nothing else re-adds a window.
 *
 * The stack mirrors the window manager's order in [realOrder]. A newly added window is on top, so the
 * windows that must be above it are exactly the ones that end up wrongly below it; only those are
 * re-added, lowest first. A window that is already above everything that must be below it is never
 * touched (the notice is not re-added when a pin appears under it, nor the pin when a notice does).
 *
 * Android has no call that reorders an accessibility overlay without removing it, so re-adding
 * stays, but a window that is animating (the notice band sliding in or out, an activity island's
 * spring) is not: it is put back the moment its animation ends ([animationEnded]) and, should that
 * signal ever be missed, after [MAX_DEFER_MS]. In between it is briefly under the window that was
 * added over it, which is the lesser evil next to cutting a 280 ms slide. Everything that must be
 * above a deferred window waits behind it so each window is re-added once, not twice.
 *
 * Main thread only.
 */
class AmbientStack(
    private val timer: AmbientTimer,
    private val onFailure: (String) -> Unit = {},
    private val trace: (String) -> Unit = {},
) {
    private val windows = EnumMap<AmbientLayer, AmbientWindow>(AmbientLayer::class.java)
    private val order = ArrayList<AmbientLayer>()
    private var cancelDeadline: (() -> Unit)? = null

    /** Layers bottom to top as the window manager stacks them. */
    fun realOrder(): List<AmbientLayer> = order.toList()

    /** True when the real order is the declared one, i.e. nothing waits for an animation. */
    fun isSettled(): Boolean = order.zipWithNext().all { (below, above) -> below.ordinal < above.ordinal }

    /** Call right after [window] was added successfully. */
    fun added(window: AmbientWindow) {
        windows[window.layer] = window
        order.remove(window.layer)
        order += window.layer
        settle(force = false)
    }

    /** Call right after the window of [layer] was removed. */
    fun removed(layer: AmbientLayer) {
        windows.remove(layer)
        order.remove(layer)
        // Windows that waited behind the one that just left are no longer held back.
        settle(force = false)
    }

    /** An animating window went idle; puts back whatever was waiting for it. */
    fun animationEnded() {
        if (cancelDeadline != null) settle(force = false)
    }

    private fun settle(force: Boolean) {
        val threshold = untouchedBelow()
        val toAdd = order.filter { it.ordinal >= threshold }.sortedBy { it.ordinal }
        for (layer in toAdd) {
            val window = windows[layer] ?: continue
            if (!force && window.isAnimating) {
                trace("ambient defer layer=$layer animating")
                break
            }
            trace("ambient re-add layer=$layer force=$force")
            if (window.readd()) {
                order.remove(layer)
                order += layer
            } else {
                // The window is gone (removeView succeeded, addView did not): forget it.
                onFailure("Overlay z-order refresh failed layer=$layer")
                order.remove(layer)
                windows.remove(layer)
            }
        }
        if (isSettled()) clearDeadline() else armDeadline()
    }

    /**
     * The lowest layer that has to be re-added: everything below it can stay because it is already
     * in ascending order. Returns the layer count when nothing needs to move.
     */
    private fun untouchedBelow(): Int {
        for (threshold in AmbientLayer.entries.size downTo 0) {
            val kept = order.filter { it.ordinal < threshold }
            if (kept.zipWithNext().all { (below, above) -> below.ordinal < above.ordinal }) return threshold
        }
        return 0
    }

    private fun armDeadline() {
        if (cancelDeadline != null) return
        cancelDeadline = timer.postDelayed(MAX_DEFER_MS) {
            cancelDeadline = null
            settle(force = true)
        }
    }

    private fun clearDeadline() {
        cancelDeadline?.invoke()
        cancelDeadline = null
    }

    companion object {
        /** Longer than every structural animation, including the emulator's slow-motion runs. */
        const val MAX_DEFER_MS = 2_000L

        val main: AmbientStack by lazy {
            val handler = Handler(Looper.getMainLooper())
            AmbientStack(
                timer = AmbientTimer { delayMs, action ->
                    val run = Runnable(action)
                    handler.postDelayed(run, delayMs)
                    val cancel: () -> Unit = { handler.removeCallbacks(run) }
                    cancel
                },
                onFailure = { logError(it) },
                trace = { log(it) },
            )
        }

        /**
         * The camera overlay lives in the `:camera` process, so its visibility arrives here as a
         * broadcast edge. It hides every ambient layer that draws over the camera view (BUSSPEC:
         * pin, activity) or would claim input over it (notice); the pointer stays because remote
         * input has to remain visible, and the badge only exists over the ROM launcher.
         */
        fun setCameraOverlayActive(active: Boolean) {
            PinController.setCameraOverlayActive(active)
            NoticeController.setCameraOverlayActive(active)
            ActivityController.setCameraOverlayActive(active)
        }
    }
}
