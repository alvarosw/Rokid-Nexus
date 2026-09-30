package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.ActivityTrack

/** How the panel fits its primary value: the size in px, and whether the ETA had to move down. */
internal data class ActivityPrimaryFit(
    val sizePx: Float,
    val etaBelow: Boolean,
)

/**
 * Picks the largest primary size that fits instead of ellipsizing.
 *
 * Inline with the ETA the size may shrink to [inlineMinPx]; below that the
 * primary is worth more than the ETA's position, so the ETA moves to the
 * secondary row and the primary takes the largest size that fits alone.
 * [widthAtPx] measures the primary at a given size.
 */
internal fun fitActivityPrimary(
    availablePx: Float,
    inlineEtaPx: Float?,
    widthAtPx: (Float) -> Float,
    maxPx: Float = ACTIVITY_PRIMARY_MAX_PX,
    inlineMinPx: Float = ACTIVITY_PRIMARY_INLINE_MIN_PX,
    minPx: Float = ACTIVITY_PRIMARY_MIN_PX,
): ActivityPrimaryFit {
    if (inlineEtaPx != null) {
        var size = maxPx
        while (size >= inlineMinPx) {
            if (widthAtPx(size) + inlineEtaPx <= availablePx) return ActivityPrimaryFit(size, false)
            size -= 1f
        }
    }
    var size = maxPx
    while (size >= minPx) {
        if (widthAtPx(size) <= availablePx) return ActivityPrimaryFit(size, inlineEtaPx != null)
        size -= 1f
    }
    return ActivityPrimaryFit(minPx, inlineEtaPx != null)
}

/** The primary is the panel's one `display` value, and shrinks no further than `heading`. */
internal const val ACTIVITY_PRIMARY_MAX_PX = RokidHudTokens.DISPLAY_TEXT_SIZE
internal const val ACTIVITY_PRIMARY_INLINE_MIN_PX = 18f
internal const val ACTIVITY_PRIMARY_MIN_PX = RokidHudTokens.HEADING_TEXT_SIZE

/**
 * A line or route mark ("38", "RER B"): a `data` chip, `line-control` outline and `text-primary`
 * mono text, the way the surface rows draw a route. A line and text, never a lit block, which on
 * additive optics would outweigh the value next to it.
 *
 * It stands in for the activity glyph, so it is as tall as an `icon-lg` and as wide as its text
 * needs ([getIntrinsicWidth]); it draws into whatever bounds the glyph slot gives it.
 */
internal class ActivityBadgeDrawable(private val text: String) : Drawable() {
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RokidHudTokens.BORDER_DEFAULT.toFloat()
        color = RokidHudTokens.LINE_CONTROL
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RokidHudTokens.TEXT_PRIMARY
        typeface = RokidHudTokens.dataTypeface()
        textSize = RokidHudTokens.DATA_TEXT_SIZE
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val width = (label.measureText(text) + 2 * RokidHudTokens.SPACE_2).toInt()

    override fun getIntrinsicWidth(): Int = maxOf(RokidHudTokens.ICON_LG, width)

    override fun getIntrinsicHeight(): Int = RokidHudTokens.ICON_LG

    override fun draw(canvas: Canvas) {
        val box = bounds
        if (box.isEmpty) return
        rect.set(box)
        rect.inset(outline.strokeWidth / 2f, outline.strokeWidth / 2f)
        val radius = RokidHudTokens.RADIUS_CONTROL.toFloat()
        canvas.drawRoundRect(rect, radius, radius, outline)

        // "RER B" still fits a slot sized for "38": the text gives way, not the mark.
        val room = box.width() - 2 * RokidHudTokens.SPACE_1
        val measured = label.measureText(text)
        val size = label.textSize
        if (measured > room && measured > 0f) label.textSize = size * room / measured
        val baseline = box.exactCenterY() - (label.descent() + label.ascent()) / 2f
        canvas.drawText(text, box.exactCenterX(), baseline, label)
        label.textSize = size
    }

    override fun setAlpha(alpha: Int) {
        outline.alpha = alpha
        label.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        outline.colorFilter = colorFilter
        label.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * A row of ordered positions: passed ones filled `text-secondary`, the current one filled
 * `text-primary`, the target as a ring, and later ones hollow on a `line` outline. The target's
 * label follows the row. Every value comes from the plugin's last update; the view never advances
 * on its own.
 */
internal class ActivityTrackView(context: Context) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = RokidHudTokens.bodyTypeface()
        textSize = RokidHudTokens.BODY_SMALL_TEXT_SIZE
    }
    private var track: ActivityTrack? = null

    fun render(track: ActivityTrack?) {
        this.track = track
        visibility = if (track == null) GONE else VISIBLE
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            HEIGHT_PX,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val current = track ?: return
        val bright = RokidHudTokens.TEXT_PRIMARY
        val passed = RokidHudTokens.TEXT_SECONDARY
        val hollow = RokidHudTokens.LINE
        val targetRadius = TARGET_RADIUS_PX
        val dotRadius = DOT_RADIUS_PX
        val centerY = height / 2f

        // The dots keep a legible minimum spacing; a long label gives way to
        // them rather than the other way round.
        val minimumDots = (current.count - 1) * MIN_STEP_PX + 2 * targetRadius
        val gap = LABEL_GAP_PX
        val labelText = current.label
            ?.let { TextUtils.ellipsize(it, label, width - minimumDots - gap, TextUtils.TruncateAt.END) }
            ?.toString()
            ?.takeIf { it.isNotEmpty() }
        val labelWidth = labelText?.let { label.measureText(it) + gap } ?: 0f
        val start = targetRadius
        val room = (width - labelWidth - 2 * targetRadius).coerceAtLeast(0f)
        val step = if (current.count > 1) {
            (room / (current.count - 1)).coerceAtMost(MAX_STEP_PX)
        } else {
            0f
        }
        fun x(index: Int) = start + index * step

        stroke.strokeWidth = SEGMENT_PX
        for (index in 0 until current.count - 1) {
            stroke.color = if (index < current.at) passed else hollow
            canvas.drawLine(x(index), centerY, x(index + 1), centerY, stroke)
        }
        for (index in 0 until current.count) {
            val cx = x(index)
            when {
                index == current.target -> {
                    dot.color = RokidHudTokens.GROUND
                    canvas.drawCircle(cx, centerY, targetRadius, dot)
                    stroke.color = bright
                    stroke.strokeWidth = RokidHudTokens.BORDER_DEFAULT.toFloat()
                    canvas.drawCircle(cx, centerY, targetRadius - stroke.strokeWidth / 2f, stroke)
                    if (index == current.at) {
                        dot.color = bright
                        canvas.drawCircle(cx, centerY, dotRadius * 0.8f, dot)
                    }
                }
                index < current.at -> {
                    dot.color = passed
                    canvas.drawCircle(cx, centerY, dotRadius, dot)
                }
                index == current.at -> {
                    dot.color = bright
                    canvas.drawCircle(cx, centerY, dotRadius, dot)
                }
                else -> {
                    dot.color = RokidHudTokens.GROUND
                    canvas.drawCircle(cx, centerY, dotRadius, dot)
                    stroke.color = hollow
                    stroke.strokeWidth = RokidHudTokens.BORDER_DEFAULT.toFloat()
                    canvas.drawCircle(cx, centerY, dotRadius - stroke.strokeWidth / 2f, stroke)
                }
            }
        }
        if (labelText != null) {
            label.color = bright
            val baseline = centerY - (label.descent() + label.ascent()) / 2f
            canvas.drawText(
                labelText,
                x(current.count - 1) + targetRadius + gap,
                baseline,
                label,
            )
        }
    }

    private companion object {
        const val HEIGHT_PX = RokidHudTokens.SPACE_6
        const val DOT_RADIUS_PX = 4f
        const val TARGET_RADIUS_PX = 7f
        const val SEGMENT_PX = 2f
        const val MAX_STEP_PX = 32f
        const val MIN_STEP_PX = 14f
        const val LABEL_GAP_PX = RokidHudTokens.SPACE_2.toFloat()
    }
}
