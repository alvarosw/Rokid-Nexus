package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.anezium.rokidbus.client.ui.RokidHudTokens
import kotlin.math.max

/**
 * A live voice waveform, newest sample on the right: `text-primary` bars over a `line` rest level.
 *
 * This is the one continuous animation the HUD is allowed to run, and only
 * while the wearer is actually speaking: it is feedback for an action in
 * progress, not decoration, and it dies with the interaction that started it.
 *
 * Drawing is [invalidate]-driven from [push] rather than from a frame loop, so
 * the redraw rate is the amplitude rate and a stalled audio source costs
 * nothing instead of spinning.
 */
internal class HudWaveformView(context: Context) : View(context) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RokidHudTokens.TEXT_PRIMARY
        strokeCap = Paint.Cap.ROUND
        strokeWidth = BAR_PX
    }
    private val restPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RokidHudTokens.LINE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = BAR_PX
    }

    private var amplitudes = FloatArray(0)
    private var head = 0
    private var filled = 0
    private var smoothed = 0f

    /** Push one normalised amplitude, 0..1. Cheap enough to call at 30 Hz. */
    fun push(amplitude: Float) {
        if (amplitudes.isEmpty()) return
        // A little smoothing: raw mic RMS reads as noise on a 2 px bar, and the
        // eye wants the envelope, not the samples.
        smoothed = smoothed * SMOOTHING + amplitude.coerceIn(0f, 1f) * (1f - SMOOTHING)
        amplitudes[head] = smoothed
        head = (head + 1) % amplitudes.size
        if (filled < amplitudes.size) filled++
        invalidate()
    }

    fun reset() {
        amplitudes.fill(0f)
        head = 0
        filled = 0
        smoothed = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(RokidHudTokens.LIST_ITEM_HEIGHT, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val slots = max(1, (w / BAR_PITCH_PX).toInt())
        if (slots != amplitudes.size) {
            amplitudes = FloatArray(slots)
            head = 0
            filled = 0
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (amplitudes.isEmpty()) return
        val centerY = height / 2f
        val maxHalf = (height / 2f) - 1f
        val restHalf = 0.5f
        for (slot in amplitudes.indices) {
            // Oldest sample on the left, newest on the right.
            val index = (head + slot) % amplitudes.size
            val amplitude = if (filled == amplitudes.size || index < filled) amplitudes[index] else 0f
            val x = (slot + 0.5f) * BAR_PITCH_PX
            if (x > width) break
            val half = max(restHalf, amplitude * maxHalf)
            val paint = if (amplitude > SILENCE) barPaint else restPaint
            canvas.drawLine(x, centerY - half, x, centerY + half, paint)
        }
    }

    private companion object {
        const val BAR_PX = 2f
        const val BAR_PITCH_PX = 6f
        const val SMOOTHING = 0.55f
        const val SILENCE = 0.06f
    }
}
