package com.anezium.rokidbus.phone

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.hudtiles.TileOp
import com.anezium.rokidbus.hudtiles.TileRenderInput
import com.anezium.rokidbus.hudtiles.TileRenderer
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone

/**
 * One glasses tile as the editor shows it, in glasses pixels: the editor's chrome (fill, border by
 * tone, the alert mark, the size label) around the interior [TileRenderer] draws, which is exactly
 * what the glasses draw inside the tile.
 */
internal class EditorTilePainter {
    // Subpixel text keeps measured widths equal to drawn widths under a canvas scale.
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tmp = RectF()
    private val path = Path()

    /** [sizeLabel] is the editor-only size caption at the bottom-right; null draws none. */
    fun draw(
        canvas: Canvas,
        rect: RectF,
        name: String,
        glyph: Drawable?,
        snapshot: TileSnapshot?,
        size: TileSize,
        selected: Boolean,
        lifted: Boolean = false,
        sizeLabel: String? = null,
    ) {
        val layer = if (lifted) canvas.saveLayerAlpha(rect, LIFTED_ALPHA) else canvas.save()
        val tone = snapshot?.tone
        val alert = tone == TileTone.WARN || tone == TileTone.CRITICAL

        val borderWidth = if (selected) 2f else 1f
        fill.color = when {
            lifted -> LIFTED_FILL
            selected -> RokidHudTokens.GREEN_12
            else -> RokidHudTokens.GREEN_06
        }
        canvas.drawRoundRect(rect, RADIUS, RADIUS, fill)
        stroke.strokeWidth = borderWidth
        // Resting border intensity as the glasses draw it: the fallback tile at `line`, a live one by tone.
        stroke.color = when {
            selected -> RokidHudTokens.GREEN_100
            snapshot == null -> RokidHudTokens.LINE
            tone == TileTone.OK || tone == TileTone.WARN || tone == TileTone.CRITICAL -> RokidHudTokens.GREEN_72
            else -> RokidHudTokens.GREEN_48
        }
        stroke.pathEffect = if (tone == TileTone.WARN) DashPathEffect(floatArrayOf(4f, 3f), 0f) else null
        tmp.set(rect)
        tmp.inset(borderWidth / 2f, borderWidth / 2f)
        canvas.drawRoundRect(tmp, RADIUS - borderWidth / 2f, RADIUS - borderWidth / 2f, stroke)
        stroke.pathEffect = null

        path.reset()
        path.addRoundRect(rect, RADIUS, RADIUS, Path.Direction.CW)
        canvas.clipPath(path)

        val icon = RokidHudTokens.ICON_SM.toFloat()
        val layout = TileRenderer.layout(
            TileRenderInput(
                name = name,
                icon = glyph,
                content = snapshot?.content,
                tone = tone ?: TileTone.OFF,
                focusAmount = if (selected) 1f else 0f,
                headerEndInset = if (alert) RokidHudTokens.ICON_SM + RokidHudTokens.SPACE_1 else 0,
                // Only a cover this hub received; a declared sample or an uncached key is text-only.
                artwork = (snapshot?.content as? TileContent.Music)?.let { TileArtworkCache.bitmap(snapshot.pluginId, it.artworkKey) },
            ),
            size,
        )
        canvas.save()
        canvas.translate(rect.left, rect.top)
        layout.draw(canvas)
        canvas.restore()

        val pad = TileRenderer.PADDING
        val right = rect.right - pad
        if (alert) {
            drawAlert(
                canvas, right - icon, rect.top + pad,
                if (tone == TileTone.CRITICAL) RokidHudTokens.CRITICAL else RokidHudTokens.TEXT_PRIMARY,
            )
        }
        if (sizeLabel != null) {
            // Above the tile's foot (a track, a list's "+N more" line), never over it.
            val footTop = layout.footer.mapNotNull { op ->
                when (op) {
                    is TileOp.Track -> op.top
                    is TileOp.Text -> op.top
                    else -> null
                }
            }.minOrNull()
            val labelBottom = if (footTop != null) rect.top + footTop - RokidHudTokens.SPACE_1 else rect.bottom - pad
            text.typeface = Typeface.MONOSPACE
            text.textSize = 10f
            text.letterSpacing = 0.04f
            text.color = RokidHudTokens.GREEN_48
            canvas.drawText(sizeLabel, right - text.measureText(sizeLabel), labelBottom - text.descent(), text)
        }
        canvas.restoreToCount(layer)
    }

    /** The glasses' `ALERT` icon: a triangle with an exclamation mark, 1 px round stroke. */
    private fun drawAlert(canvas: Canvas, x: Float, y: Float, color: Int) {
        stroke.strokeWidth = RokidHudTokens.BORDER_DEFAULT.toFloat()
        stroke.color = color
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        path.reset()
        path.moveTo(x + 8f, y + 1.5f)
        path.lineTo(x + 15f, y + 14f)
        path.lineTo(x + 1f, y + 14f)
        path.close()
        path.moveTo(x + 8f, y + 6f)
        path.lineTo(x + 8f, y + 9.5f)
        path.moveTo(x + 8f, y + 11.5f)
        path.lineTo(x + 8f, y + 12f)
        canvas.drawPath(path, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeJoin = Paint.Join.MITER
    }

    private companion object {
        const val RADIUS = RokidHudTokens.RADIUS_PANEL.toFloat()
        const val LIFTED_ALPHA = 235
        const val LIFTED_FILL = 0xFF010401.toInt()
    }
}
