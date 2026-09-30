package com.anezium.rokidbus.glasses.hud

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The design system's hard rules on real pixels: the PNG is exactly 480x640, every lit pixel is
 * the one green hue (black composited with #40FF5E at some intensity) and no large area is lit.
 * [exempt] is the rectangle of a decoded image, which keeps its own pixels.
 */
internal fun assertSingleHue(file: File, exempt: android.graphics.Rect? = null) {
    val image = ImageIO.read(file)
    assertEquals("$file width", 480, image.width)
    assertEquals("$file height", 640, image.height)
    var lit = 0
    for (y in 0 until image.height) for (x in 0 until image.width) {
        // Image content itself (a decoded picture) is not ours to recolor.
        if (exempt != null && exempt.contains(x, y)) continue
        val argb = image.getRGB(x, y)
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        if (g < 4 && r < 4 && b < 4) continue
        lit++
        // #40FF5E at any intensity over black: r = 0.251 g, b = 0.369 g (anti-aliasing keeps it).
        assertTrue(
            "$file pixel ($x,$y) is not the single hue: r=$r g=$g b=$b",
            abs(r - g * 0.251) <= 4 && abs(b - g * 0.369) <= 4,
        )
    }
    assertTrue("$file lit nothing", lit > 0)
    // No large fill: lit area stays a small share of the canvas (bloom rule).
    assertTrue("$file lit share ${lit / (480f * 640)}", lit < 480 * 640 * 0.35)
}
