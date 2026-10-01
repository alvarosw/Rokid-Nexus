package com.anezium.rokidbus.plugin.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import java.io.ByteArrayOutputStream

/** A generated stand-in album cover for the demo music tile: a disc on a dark ground, as a PNG. */
internal object DemoCover {
    private const val EDGE = 128

    fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(EDGE, EDGE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(12, 20, 40))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(EDGE * 0.4f, EDGE * 0.45f, EDGE * 0.45f, Color.rgb(255, 170, 60), Color.rgb(120, 30, 90), Shader.TileMode.CLAMP)
        canvas.drawCircle(EDGE * 0.4f, EDGE * 0.45f, EDGE * 0.36f, paint)
        paint.shader = null
        paint.color = Color.rgb(12, 20, 40)
        canvas.drawCircle(EDGE * 0.4f, EDGE * 0.45f, EDGE * 0.06f, paint)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bitmap.recycle() }
    }
}
