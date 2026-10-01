package com.anezium.rokidbus.hudtiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.anezium.rokidbus.client.ui.RokidHudTokens

/** What a run of tile text is, so hosts and tests can find it without matching strings. */
enum class TilePart {
    NAME, SUMMARY, TITLE, VALUE, UNIT, SUBTITLE, BADGE, ROW,

    /** Music. */
    ARTIST, ALBUM, SOURCE_CAPTION, SOURCE, TIME, ELAPSED, DURATION,

    /** Lines: the current line, the lines around it, the track under them. */
    CURRENT_LINE, CONTEXT_LINE, TRACK_TITLE, TRACK_META,

    /** List. */
    SECTION_TITLE, SECTION_DETAIL, ITEM_TITLE, ITEM_META, PARAGRAPH, MARKER, MORE, FOOTER,
}

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

    /** A track's artwork, scaled to cover the [size] square and cropped to it, as decoded. */
    data class Artwork(val bitmap: Bitmap, val left: Float, val top: Float, val size: Float) : TileOp {
        override fun draw(canvas: Canvas) {
            if (bitmap.isRecycled) return
            val edge = minOf(bitmap.width, bitmap.height)
            val x = (bitmap.width - edge) / 2
            val y = (bitmap.height - edge) / 2
            canvas.drawBitmap(
                bitmap,
                Rect(x, y, x + edge, y + edge),
                RectF(left, top, left + size, top + size),
                TilePaints.bitmap,
            )
        }
    }

    /** The play triangle or the pause bars, 1 px stroke, in a [size] square. */
    data class PlayState(val playing: Boolean, val left: Float, val top: Float, val size: Float, val color: Int) : TileOp {
        override fun draw(canvas: Canvas) {
            val stroke = TilePaints.stroke
            stroke.strokeWidth = RokidHudTokens.BORDER_DEFAULT.toFloat()
            stroke.color = color
            val unit = size / 16f
            val path = Path()
            if (playing) {
                path.moveTo(left + 5.5f * unit, top + 3.5f * unit)
                path.lineTo(left + 12f * unit, top + 8f * unit)
                path.lineTo(left + 5.5f * unit, top + 12.5f * unit)
                path.close()
            } else {
                path.moveTo(left + 5.5f * unit, top + 3.5f * unit)
                path.lineTo(left + 5.5f * unit, top + 12.5f * unit)
                path.moveTo(left + 10.5f * unit, top + 3.5f * unit)
                path.lineTo(left + 10.5f * unit, top + 12.5f * unit)
            }
            canvas.drawPath(path, stroke)
        }
    }

    /** A list item's leading glyph: the plugin's own glyph tinted [color] in a 1 px rounded box. */
    data class GlyphBox(
        val drawable: Drawable,
        val left: Float,
        val top: Float,
        val size: Float,
        val color: Int,
        val borderColor: Int,
    ) : TileOp {
        override fun draw(canvas: Canvas) {
            val stroke = TilePaints.stroke
            stroke.strokeWidth = RokidHudTokens.BORDER_DEFAULT.toFloat()
            stroke.color = borderColor
            val radius = RokidHudTokens.RADIUS_CONTROL.toFloat()
            canvas.drawRoundRect(left + 0.5f, top + 0.5f, left + size - 0.5f, top + size - 0.5f, radius, radius, stroke)
            val icon = RokidHudTokens.ICON_SM.toFloat()
            Icon(drawable, left + (size - icon) / 2f, top + (size - icon) / 2f, icon, color).draw(canvas)
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
