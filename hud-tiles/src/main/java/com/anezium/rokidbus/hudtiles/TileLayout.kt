package com.anezium.rokidbus.hudtiles

import android.graphics.Canvas
import android.graphics.Rect

/**
 * A tile's interior as positioned draw ops, in glasses pixels from the tile's top-left corner.
 * [body] is clipped to [bodyClip] as the glasses' content box always was; [header] and [footer]
 * are not.
 */
class TileLayout(
    val width: Int,
    val height: Int,
    val header: List<TileOp>,
    val body: List<TileOp>,
    val bodyClip: Rect,
    val footer: List<TileOp>,
) {
    val ops: List<TileOp> get() = header + body + footer

    fun texts(part: TilePart): List<TileOp.Text> = ops.filterIsInstance<TileOp.Text>().filter { it.part == part }

    fun draw(canvas: Canvas) {
        header.forEach { it.draw(canvas) }
        if (body.isNotEmpty()) {
            val save = canvas.save()
            canvas.clipRect(bodyClip)
            body.forEach { it.draw(canvas) }
            canvas.restoreToCount(save)
        }
        footer.forEach { it.draw(canvas) }
    }
}
