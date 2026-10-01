package com.anezium.rokidbus.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.hudtiles.TileRenderer
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.TileSnapshot
import kotlin.math.ceil

/**
 * One tile at a chosen size, drawn as the glasses draw it, on the glasses' black ground: the
 * glasses content width (448 px) is scaled to this view's width, as in the editor's grid, so a
 * tile keeps its real proportion to the screen.
 */
internal class TileSizePreviewView(context: Context) : View(context) {
    private val painter = EditorTilePainter()
    private val ground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val density = resources.displayMetrics.density
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = NexusUi.LINE
    }
    private val framePad = 11f * density
    private val frameRadius = 12f * density
    private val tile = RectF()

    private var name = ""
    private var glyph: Drawable? = null
    private var snapshot: TileSnapshot? = null
    var size: TileSize = TileSize.SMALL
        private set

    /** A null [snapshot] draws the header only. */
    fun bind(name: String, glyph: Drawable?, snapshot: TileSnapshot?, size: TileSize) {
        this.name = name
        this.glyph = glyph
        this.snapshot = snapshot
        this.size = size
        contentDescription = "$name at ${size.cols} by ${size.rows}"
        requestLayout()
        invalidate()
    }

    private fun scaleFor(width: Int) = (width - 2 * framePad) / RokidHudTokens.CONTENT_WIDTH

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = ceil(2 * framePad + TileRenderer.heightOf(size) * scaleFor(width)).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), frameRadius, frameRadius, ground)
        val inset = density / 2f
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, frameRadius - inset, frameRadius - inset, border)
        val scale = scaleFor(width)
        canvas.save()
        canvas.translate(framePad, framePad)
        canvas.scale(scale, scale)
        tile.set(0f, 0f, TileRenderer.widthOf(size).toFloat(), TileRenderer.heightOf(size).toFloat())
        painter.draw(canvas, tile, name, glyph, snapshot, size, selected = false)
        canvas.restore()
    }

    internal val snapshotForTest: TileSnapshot? get() = snapshot
}
