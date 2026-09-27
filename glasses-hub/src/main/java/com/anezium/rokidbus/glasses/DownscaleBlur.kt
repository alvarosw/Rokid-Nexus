package com.anezium.rokidbus.glasses

import android.graphics.Bitmap

/**
 * Approximates a soft blur without `RenderEffect` — the reliable blur APIs need API 33, and this
 * project's `minSdk` (26 for `bus-client`, 30 for the hubs) isn't guaranteed to have them.
 * Downscales to roughly 1/8 resolution (filtered, so the downscale itself smooths detail) then
 * blits back up unfiltered — the blocky upscale is what reads as "softened" at typical viewing
 * distance, at near-zero GPU cost, and works identically on every supported device rather than
 * being a feature-detected bonus on newer hardware only.
 */
object DownscaleBlur {
    const val DOWNSCALE_FACTOR = 0.125f

    fun blur(source: Bitmap, downscaleFactor: Float = DOWNSCALE_FACTOR): Bitmap {
        require(downscaleFactor > 0f && downscaleFactor <= 1f) { "downscaleFactor must be in (0, 1]" }
        val scaledWidth = (source.width * downscaleFactor).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * downscaleFactor).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, /* filter = */ true)
        return Bitmap.createScaledBitmap(small, source.width, source.height, /* filter = */ false)
    }
}
