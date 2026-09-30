package com.anezium.rokidbus.shared.motion

/**
 * A plain, Android-independent rectangle so the geometry tween's math is a pure JVM unit test —
 * `android.graphics.Rect` requires Robolectric to exercise reliably, and the tween math has
 * nothing to do with the Android framework.
 */
data class TileRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Interpolates between two [TileRect]s by a fraction — the geometry tween the glasses HUD's open/close
 * morph (`HudMorph`) drives from an item's rect to the app safe area and back. The caller applies the
 * easing; this is the linear interpolation of `left/top/width/height`.
 */
object TileRectTween {
    fun at(from: TileRect, to: TileRect, fraction: Float): TileRect {
        val f = fraction.coerceIn(0f, 1f)
        fun lerp(a: Int, b: Int): Int = a + ((b - a) * f).toInt()
        val left = lerp(from.left, to.left)
        val top = lerp(from.top, to.top)
        val width = lerp(from.width, to.width)
        val height = lerp(from.height, to.height)
        return TileRect(left, top, left + width, top + height)
    }
}
