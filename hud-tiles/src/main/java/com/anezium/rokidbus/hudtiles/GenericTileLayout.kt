package com.anezium.rokidbus.hudtiles

import android.graphics.Rect
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileContentRules
import com.anezium.rokidbus.shared.tile.TileContentRules.TitleStyle
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * [TileContent.Generic] at every size, by [TileContentRules]: header, then the content top-down
 * (title or data value with its unit, subtitle, badge, rows), and the 3 px progress track at the
 * foot. Spacing is the glasses' original view tree's, pixel for pixel.
 */
internal object GenericTileLayout {
    fun layout(input: TileRenderInput, content: TileContent.Generic?, size: TileSize): TileLayout {
        val width = TileRenderer.widthOf(size)
        val height = TileRenderer.heightOf(size)
        val pad = TileRenderer.PADDING
        val inner = width - 2 * pad
        val innerBottom = height - pad
        val header = TileHeader.layout(input, width, size.cols)
        val shown = TileContentRules.contentFor(size, content)
        val dim = { color: Int -> if (input.stale) RokidHudTokens.scaleAlpha(color, STALE_ALPHA) else color }
        val titleInk = dim(focusInk(input.focusAmount, RokidHudTokens.TEXT_PRIMARY))

        val body = ArrayList<TileOp>()
        var y = header.bottom
        if (content != null) {
            when (shown.titleStyle) {
                TitleStyle.TEXT -> {
                    y += RokidHudTokens.SPACE_1
                    val title = TileText.block(
                        TilePart.TITLE, content.title, TileTextStyle.BODY, titleInk, pad, y, inner, shown.titleMaxLines,
                    )
                    body += title.lines
                    y += title.height
                }
                TitleStyle.DATA_VALUE -> {
                    y += RokidHudTokens.SPACE_1
                    y += dataRow(body, content, shown.showUnit, titleInk, dim(RokidHudTokens.TEXT_SECONDARY), pad, y, inner)
                }
                TitleStyle.NONE -> Unit
            }
            if (shown.subtitleVisible) {
                y += SUBTITLE_GAP
                val subtitle = TileText.block(
                    TilePart.SUBTITLE, content.subtitle, TileTextStyle.DETAIL, dim(RokidHudTokens.TEXT_SECONDARY),
                    pad, y, inner, shown.subtitleMaxLines,
                )
                body += subtitle.lines
                y += subtitle.height
            }
            if (shown.badgeVisible) {
                y += RokidHudTokens.SPACE_1
                val badge = TileText.block(
                    TilePart.BADGE, content.badge, TileTextStyle.DATA_DETAIL, dim(RokidHudTokens.TEXT_PRIMARY),
                    pad, y, inner, maxLines = 1, ellipsize = false,
                )
                body += badge.lines
                y += badge.height
            }
            if (shown.rowCount > 0) {
                y += ROWS_GAP
                content.rows.take(shown.rowCount).forEachIndexed { index, value ->
                    if (index > 0) y += ROW_GAP
                    val row = TileText.block(
                        TilePart.ROW, value, TileTextStyle.DETAIL, dim(RokidHudTokens.TEXT_PRIMARY), pad, y, inner, maxLines = 1,
                    )
                    body += row.lines
                    y += row.height
                }
            }
        }

        val footer = ArrayList<TileOp>()
        val progress = shown.progress
        val bodyBottom = if (progress != null) {
            val trackTop = innerBottom - TRACK_HEIGHT
            footer += TileOp.Track(
                pad.toFloat(), trackTop.toFloat(), (width - pad).toFloat(), innerBottom.toFloat(),
                progress, dim(RokidHudTokens.LINE), dim(RokidHudTokens.TEXT_PRIMARY),
            )
            trackTop - RokidHudTokens.SPACE_1
        } else {
            innerBottom
        }
        return TileLayout(width, height, header.ops, body, Rect(pad, header.bottom, width - pad, bodyBottom), footer)
    }

    /**
     * The data value and its unit on one baseline, as a baseline-aligned row lays them out; returns
     * the row's height.
     */
    private fun dataRow(
        body: MutableList<TileOp>,
        content: TileContent.Generic,
        showUnit: Boolean,
        valueInk: Int,
        unitInk: Int,
        left: Int,
        top: Int,
        width: Int,
    ): Int {
        val valueWidth = minOf(TileText.desiredWidth(content.title, TileTextStyle.DATA), width)
        val value = TileText.block(
            TilePart.VALUE, content.title, TileTextStyle.DATA, valueInk, left, 0, valueWidth, maxLines = 1, ellipsize = false,
        )
        val unitLeft = left + valueWidth + RokidHudTokens.SPACE_1
        val unit = if (showUnit) {
            TileText.block(
                TilePart.UNIT, content.unit, TileTextStyle.MONO, unitInk, unitLeft, 0, left + width - unitLeft,
                maxLines = 1, ellipsize = false,
            )
        } else {
            null
        }
        val ascent = maxOf(value.baseline, unit?.baseline ?: 0)
        val descent = maxOf(value.height - value.baseline, unit?.let { it.height - it.baseline } ?: 0)
        body += value.lines.map { it.shiftedBy(top + ascent - value.baseline) }
        unit?.let { block -> body += block.lines.map { it.shiftedBy(top + ascent - block.baseline) } }
        return ascent + descent
    }

    /** 0x60 / 0xFF, as the whole-view alpha of a stale tile used to be. */
    const val STALE_ALPHA = 0x60 / 255f
    const val TRACK_HEIGHT = 3
    private const val SUBTITLE_GAP = 2
    private const val ROWS_GAP = 6
    private const val ROW_GAP = 3
}
