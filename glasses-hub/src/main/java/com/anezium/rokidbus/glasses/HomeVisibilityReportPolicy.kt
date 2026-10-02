package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.HomeVisibility

/** Runs [task] once after [delayMs]; the returned handle cancels it if it has not run yet. */
internal fun interface HomeVisibilityScheduler {
    fun schedule(delayMs: Long, task: Runnable): () -> Unit
}

/**
 * Decides when the glasses tell the phone what they show. A change of either value is sent after
 * [DEBOUNCE_MS] of calm, so a flap collapses into its settled value, and nothing goes out when that
 * value is the one the phone already has. Every transport-up sends the current value regardless,
 * since a phone that just (re)connected knows nothing. While the link is down nothing is sent or
 * queued: the next transport-up carries the current value.
 *
 * Thread-safe; [send] runs with the policy's lock held and must not call back into it.
 */
internal class HomeVisibilityReportPolicy(
    private val scheduler: HomeVisibilityScheduler,
    private val send: (HomeVisibility) -> Boolean,
) {
    private var current = HomeVisibility(screenOn = false, homeVisible = false)
    private var lastSent: HomeVisibility? = null
    private var linkUp = false
    private var cancelPending: (() -> Unit)? = null

    @Synchronized
    fun update(screenOn: Boolean, homeShown: Boolean) {
        val next = HomeVisibility(screenOn, homeShown && screenOn)
        if (next == current) return
        current = next
        if (!linkUp) return
        cancelDebounce()
        if (next == lastSent) return
        cancelPending = scheduler.schedule(DEBOUNCE_MS) { onDebounced() }
    }

    /** A transport came up and the capabilities went out; the phone gets the current value now. */
    @Synchronized
    fun onTransportUp() {
        linkUp = true
        cancelDebounce()
        transmit()
    }

    @Synchronized
    fun onLinkDown() {
        linkUp = false
        lastSent = null
        cancelDebounce()
    }

    @Synchronized
    private fun onDebounced() {
        cancelPending = null
        if (linkUp && current != lastSent) transmit()
    }

    private fun transmit() {
        val value = current
        if (send(value)) lastSent = value
    }

    private fun cancelDebounce() {
        cancelPending?.invoke()
        cancelPending = null
    }

    companion object {
        const val DEBOUNCE_MS = 300L
    }
}
