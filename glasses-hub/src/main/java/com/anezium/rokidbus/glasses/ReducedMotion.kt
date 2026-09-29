package com.anezium.rokidbus.glasses

import android.content.Context
import android.provider.Settings

/**
 * Android has no CSS-style reduced-motion media query. The closest system signal is the
 * animator-duration-scale setting: `0` means the wearer has turned animations off (via developer
 * options or, on builds that expose it, an accessibility "remove animations" toggle backed by the
 * same setting). Read fresh every time rather than cached, so flipping it mid-session takes effect
 * on the very next tile open, not the one after.
 */
object ReducedMotion {
    fun isEnabled(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    /**
     * The same setting as a multiplier for durations, `1` when unset or off. A value above `1` slows
     * every host animation down, which is how the emulator runs read a 220 ms morph frame by frame.
     */
    fun durationScale(context: Context): Float =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            .takeIf { it > 0f } ?: 1f
}
