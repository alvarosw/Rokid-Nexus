package com.anezium.rokidbus.glasses

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownscaleBlurTest {
    @Test
    fun `output dimensions match the source for a normal tile size`() {
        val source = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val blurred = DownscaleBlur.blur(source)
        assertEquals(source.width, blurred.width)
        assertEquals(source.height, blurred.height)
    }

    @Test
    fun `a 1x1 bitmap does not throw and returns 1x1`() {
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val blurred = DownscaleBlur.blur(source)
        assertEquals(1, blurred.width)
        assertEquals(1, blurred.height)
    }

    @Test
    fun `a large full-screen tile does not throw`() {
        val source = Bitmap.createBitmap(480, 352, Bitmap.Config.ARGB_8888)
        val blurred = DownscaleBlur.blur(source)
        assertEquals(source.width, blurred.width)
        assertEquals(source.height, blurred.height)
    }

    @Test
    fun `scale factor controls the intermediate downscale without throwing`() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val blurred = DownscaleBlur.blur(source, downscaleFactor = 0.5f)
        assertEquals(64, blurred.width)
        assertEquals(64, blurred.height)
    }
}
