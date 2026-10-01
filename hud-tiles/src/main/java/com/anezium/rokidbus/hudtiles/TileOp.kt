package com.anezium.rokidbus.hudtiles

import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.anezium.rokidbus.client.ui.RokidHudTokens

/** What a run of tile text is, so hosts and tests can find it without matching strings. */
enum class TilePart { NAME, SUMMARY, TITLE, VALUE, UNIT, SUBTITLE, BADGE, ROW }

/** One positioned drawing step of a tile, in glasses pixels from the tile's top-left corner. */
sealed interface TileOp {
    fun draw(canvas: Canvas)

    /** One line of text, already clamped: an ellipsized line carries its ellipsis. */
    data class Text(
        val part: TilePart,
        val text: String,
        val left: Float,
        val top: Float,
        val bottom: Float,
        val baseline: Float,
        val style: TileTextStyle,
        val color: Int,
    ) : TileOp {
        override fun draw(canvas: Canvas) {
            val paint = TilePaints.text(style)
            paint.color = color
            canvas.drawText(text, left, baseline, paint)
        }
    }

    /** A `line` track with a [fillColor] share of [progress] (0..1), radius-data corners. */
    data class Track(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val progress: Float,
        val trackColor: Int,
        val fillColor: Int,
    ) : TileOp {
        override fun draw(canvas: Canvas) {
            val radius = RokidHudTokens.RADIUS_DATA.toFloat()
            val paint = TilePaints.fill
            paint.color = trackColor
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
            paint.color = fillColor
            canvas.drawRoundRect(left, top, left + (right - left) * progress, bottom, radius, radius, paint)
        }
    }

    /** A 1 px divider between list sections or items. */
    data class Separator(val left: Float, val top: Float, val right: Float, val color: Int) : TileOp {
        override fun draw(canvas: Canvas) {
            val paint = TilePaints.fill
            paint.color = color
            canvas.drawRect(left, top, right, top + 1f, paint)
        }
    }

    /** A list item's leading initials in a 1 px rounded box. */
    data class InitialsBox(
        val left: Float,
        val top: Float,
        val size: Float,
        val text: String,
        val color: Int,
        val borderColor: Int,
    ) : TileOp {
        override fun draw(canvas: Canvas) {
            val stroke = TilePaints.stroke
            stroke.strokeWidth = RokidHudTokens.BORDER_DEFAULT.toFloat()
            stroke.color = borderColor
            val radius = RokidHudTokens.RADIUS_CONTROL.toFloat()
            canvas.drawRoundRect(left + 0.5f, top + 0.5f, left + size - 0.5f, top + size - 0.5f, radius, radius, stroke)
            val paint = TilePaints.text(TileTextStyle.MONO)
            paint.color = color
            val metrics = paint.fontMetrics
            val baseline = top + (size - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
            canvas.drawText(text, left + (size - paint.measureText(text)) / 2f, baseline, paint)
        }
    }

    /** Where a track's artwork goes; drawn as its empty frame until artwork is resolved. */
    data class ArtworkSlot(val left: Float, val top: Float, val size: Float, val borderColor: Int) : TileOp {
        override fun draw(canvas: Canvas) {
            val stroke = TilePaints.stroke
            stroke.strokeWidth = RokidHudTokens.BORDER_DEFAULT.toFloat()
            stroke.color = borderColor
            val radius = RokidHudTokens.RADIUS_DATA.toFloat()
            canvas.drawRoundRect(left + 0.5f, top + 0.5f, left + size - 0.5f, top + size - 0.5f, radius, radius, stroke)
        }
    }

    /** A monochrome icon tinted [color], fitted and centered in a [size] square. */
    data class Icon(val drawable: Drawable?, val left: Float, val top: Float, val size: Float, val color: Int) : TileOp {
        override fun draw(canvas: Canvas) {
            val icon = drawable ?: return
            val bounds = fitCenter(icon.intrinsicWidth, icon.intrinsicHeight)
            icon.setBounds(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
            icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            icon.draw(canvas)
        }

        /** An `ImageView`'s default `FIT_CENTER`, which the glasses' header used. */
        private fun fitCenter(width: Int, height: Int): RectF {
            if (width <= 0 || height <= 0) return RectF(left, top, left + size, top + size)
            val scale = minOf(size / width, size / height)
            val w = width * scale
            val h = height * scale
            val x = left + (size - w) / 2f
            val y = top + (size - h) / 2f
            return RectF(x, y, x + w, y + h)
        }
    }
}
