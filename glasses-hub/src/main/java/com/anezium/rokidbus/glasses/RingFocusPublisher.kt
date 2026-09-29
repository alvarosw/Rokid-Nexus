package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.Intent

/**
 * Sends `NEXUS_RING_FOCUS` to the R08 access bridge. What the value is comes from the HUD state
 * machine (`HudState.ringFocus`), which emits an effect only on a change; this only delivers it.
 */
internal object RingFocusPublisher {
    private const val RING_FOCUS_ACTION = "com.anezium.r08accessbridge.action.NEXUS_RING_FOCUS"
    private const val RING_BRIDGE_PACKAGE = "com.anezium.r08accessbridge"

    fun publish(context: Context, focused: Boolean) {
        context.applicationContext.sendBroadcast(
            Intent(RING_FOCUS_ACTION)
                .setPackage(RING_BRIDGE_PACKAGE)
                .putExtra("focused", focused)
                .putExtra("ts", System.currentTimeMillis()),
        )
    }
}
