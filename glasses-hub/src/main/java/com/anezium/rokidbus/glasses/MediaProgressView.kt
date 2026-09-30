package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.anezium.rokidbus.client.ui.RokidHudTokens
import kotlin.math.abs

/** `Loader`, `progress` variant with a known value: a `text-primary` fill on a `line` track. */
internal class MediaProgressView(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RokidHudTokens.LINE }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RokidHudTokens.TEXT_PRIMARY }
    private val rect = RectF()
    private var progress = 0f

    fun setProgress(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (abs(progress - next) < 0.001f) return
        progress = next
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(HEIGHT_PX, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = RokidHudTokens.RADIUS_DATA.toFloat()
        val top = (height - BAR_PX) / 2f
        rect.set(0f, top, width.toFloat(), top + BAR_PX)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        if (progress > 0f) {
            rect.set(0f, top, width * progress, top + BAR_PX)
            canvas.drawRoundRect(rect, radius, radius, progressPaint)
        }
    }

    companion object {
        const val HEIGHT_PX = 8
        private const val BAR_PX = 4
    }
}
