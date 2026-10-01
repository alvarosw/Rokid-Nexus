package com.anezium.rokidbus.phone

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.hudtiles.SystemWidgetContent
import com.anezium.rokidbus.hudtiles.SystemWidgetInput
import com.anezium.rokidbus.hudtiles.SystemWidgetRenderer
import com.anezium.rokidbus.hudtiles.TileOp
import com.anezium.rokidbus.hudtiles.TileRenderInput
import com.anezium.rokidbus.hudtiles.TileRenderer
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone

/**
 * One glasses tile as the editor shows it, in glasses pixels: the editor's chrome (fill, border by
 * tone, the alert mark, the size label) around the interior [TileRenderer] draws, which is exactly
 * what the glasses draw inside the tile. A system widget's interior is [SystemWidgetRenderer]'s,
 * in the resting chrome of a tile with no snapshot, as the glasses draw it.
 */
internal class EditorTilePainter {
    // Subpixel text keeps measured widths equal to drawn widths under a canvas scale.
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tmp = RectF()
    private val path = Path()

    /**
     * [sizeLabel] is the editor-only size caption at the bottom-right; null draws none. A non-null
     * [widget] draws that system widget content and ignores [snapshot].
     */
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
        widget: SystemWidgetContent? = null,
    ) {
        val layer = if (lifted) canvas.saveLayerAlpha(rect, LIFTED_ALPHA) else canvas.save()
        val snapshot = snapshot.takeIf { widget == null }
        val tone = snapshot?.tone
        val alert = tone == TileTone.WARN || tone == TileTone.CRITICAL

        val borderWidth = if (selected) 2f else 1f
        fill.color = when {
            lifted -> LIFTED_FILL
            selected -> RokidHudTokens.GREEN_12
            widget != null -> Color.TRANSPARENT
            else -> RokidHudTokens.GREEN_06
        }
        canvas.drawRoundRect(rect, RADIUS, RADIUS, fill)
        stroke.strokeWidth = borderWidth
        // Resting border intensity as the glasses draw it: the fallback tile at `line`, a live one by tone.
        stroke.color = when {
            selected -> RokidHudTokens.GREEN_100
            widget != null -> RokidHudTokens.LINE
            snapshot == null -> RokidHudTokens.LINE
            tone == TileTone.OK || tone == TileTone.WARN || tone == TileTone.CRITICAL -> RokidHudTokens.GREEN_72
            else -> RokidHudTokens.GREEN_48
        }
        // A system widget rests dashed, as the reference draws it; selected it takes the solid frame.
        val dashed = tone == TileTone.WARN || (widget != null && !selected)
        stroke.pathEffect = if (dashed) DashPathEffect(floatArrayOf(4f, 3f), 0f) else null
        tmp.set(rect)
        tmp.inset(borderWidth / 2f, borderWidth / 2f)
        canvas.drawRoundRect(tmp, RADIUS - borderWidth / 2f, RADIUS - borderWidth / 2f, stroke)
        stroke.pathEffect = null

        path.reset()
        path.addRoundRect(rect, RADIUS, RADIUS, Path.Direction.CW)
        canvas.clipPath(path)

        val icon = RokidHudTokens.ICON_SM.toFloat()
        val layout = if (widget != null) {
            SystemWidgetRenderer.layout(SystemWidgetInput(name = name, icon = glyph, content = widget), size)
        } else {
            TileRenderer.layout(
                TileRenderInput(
                    name = name,
                    icon = glyph,
                    content = snapshot?.content,
                    tone = tone ?: TileTone.OFF,
                    focusAmount = if (selected) 1f else 0f,
                    headerEndInset = if (alert) RokidHudTokens.ICON_SM + RokidHudTokens.SPACE_1 else 0,
                ),
                size,
            )
        }
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
            val track = layout.footer.filterIsInstance<TileOp.Track>().firstOrNull()
            val labelBottom = if (track != null) rect.top + track.top - RokidHudTokens.SPACE_1 else rect.bottom - pad
            text.typeface = Typeface.MONOSPACE
            text.textSize = 10f
            text.letterSpacing = 0.04f
            text.color = RokidHudTokens.GREEN_48
            val labelTop = labelBottom - text.descent() + text.ascent()
            // A widget's body can reach the tile's foot (status' three readings); the card names the size.
            val covered = widget != null &&
                layout.body.filterIsInstance<TileOp.Text>().any { rect.top + it.bottom > labelTop }
            if (!covered) {
                canvas.drawText(sizeLabel, right - text.measureText(sizeLabel), labelBottom - text.descent(), text)
            }
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
