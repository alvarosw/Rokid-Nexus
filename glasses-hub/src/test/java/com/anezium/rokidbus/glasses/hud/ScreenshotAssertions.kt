package com.anezium.rokidbus.glasses.hud

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Green channel of `surface-selected` (green-12, alpha 0x1F of #40FF5E) composited over black:
 * 255 x 31/255 = 31. A focused tile of any size fills with it.
 */
internal const val SURFACE_SELECTED_GREEN = 31

/** Pixels must be brighter than a focus fill by this margin to count as a bright area. */
private const val BRIGHT_MARGIN = 8

/**
 * The design system's hard rules on real pixels: the PNG is exactly 480x640, every lit pixel is
 * the one green hue (black composited with #40FF5E at some intensity) and no large area is bright.
 * The bloom rule is about bright areas, so the share only counts pixels above the surface-selected
 * level ([SURFACE_SELECTED_GREEN] + [BRIGHT_MARGIN] = 39 on the green channel): the dim focus fill
 * of a large tile (a focused 3x3 is about 36 % of the canvas) is not bloom. The hue check still
 * covers every lit pixel. [exempt] is the rectangle of a decoded image, which keeps its own pixels.
 */
internal fun assertSingleHue(file: File, exempt: android.graphics.Rect? = null) {
    val image = ImageIO.read(file)
    assertEquals("$file width", 480, image.width)
    assertEquals("$file height", 640, image.height)
    var lit = 0
    var bright = 0
    for (y in 0 until image.height) for (x in 0 until image.width) {
        // Image content itself (a decoded picture) is not ours to recolor.
        if (exempt != null && exempt.contains(x, y)) continue
        val argb = image.getRGB(x, y)
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        if (g < 4 && r < 4 && b < 4) continue
        lit++
        if (g > SURFACE_SELECTED_GREEN + BRIGHT_MARGIN) bright++
        // #40FF5E at any intensity over black: r = 0.251 g, b = 0.369 g (anti-aliasing keeps it).
        assertTrue(
            "$file pixel ($x,$y) is not the single hue: r=$r g=$g b=$b",
            abs(r - g * 0.251) <= 4 && abs(b - g * 0.369) <= 4,
        )
    }
    assertTrue("$file lit nothing", lit > 0)
    // No large bright area: pixels above the focus fill stay a small share of the canvas (bloom rule).
    assertTrue("$file bright share ${bright / (480f * 640)}", bright < 480 * 640 * 0.35)
}

/** The green channel of the pixel at ([x], [y]) in [file]. */
internal fun greenAt(file: File, x: Int, y: Int): Int = ImageIO.read(file).getRGB(x, y) shr 8 and 0xFF
