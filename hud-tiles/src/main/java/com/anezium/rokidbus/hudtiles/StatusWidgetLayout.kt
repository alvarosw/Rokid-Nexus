package com.anezium.rokidbus.hudtiles

import android.graphics.Rect
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * The status widget: glasses charge, phone charge and the glasses-phone link. Up to two columns
 * wide it is one row per reading, the `label` name on the left and the data value on the right; a
 * three-wide tile puts the readings side by side, each name over its value. Every size has a
 * layout; the editor offers 1x1, 2x1 and 3x1.
 */
internal object StatusWidgetLayout {
    private class Reading(val label: String, val value: String)

    fun layout(input: SystemWidgetInput, content: SystemWidgetContent.Status, size: TileSize): TileLayout {
        val width = TileRenderer.widthOf(size)
        val height = TileRenderer.heightOf(size)
        val pad = TileRenderer.PADDING
        val inner = width - 2 * pad
        val header = SystemWidgetRenderer.header(input, width, size.cols)
        val readings = listOf(
            Reading(GLASSES, battery(content.glasses)),
            Reading(PHONE, battery(content.phone)),
            Reading(LINK, if (content.phoneLinked) LINK_UP else LINK_DOWN),
        )
        val top = header.bottom + RokidHudTokens.SPACE_1
        val body = if (size.cols >= 3) columns(readings, pad, top, inner) else rows(readings, pad, top, inner)
        return TileLayout(width, height, header.ops, body, Rect(pad, header.bottom, width - pad, height - pad), emptyList())
    }

    private fun rows(readings: List<Reading>, left: Int, top: Int, width: Int): List<TileOp> {
        val ops = ArrayList<TileOp>()
        var y = top
        readings.forEachIndexed { index, reading ->
            if (index > 0) y += ROW_GAP
            val valueWidth = minOf(TileText.desiredWidth(reading.value, TileTextStyle.DATA), width)
            val value = TileText.block(
                TilePart.VALUE, reading.value, TileTextStyle.DATA, RokidHudTokens.TEXT_PRIMARY,
                left + width - valueWidth, 0, valueWidth, maxLines = 1, ellipsize = false,
            )
            val label = TileText.block(
                TilePart.SUBTITLE, reading.label, TileTextStyle.LABEL, RokidHudTokens.TEXT_SECONDARY,
                left, 0, width - valueWidth - RokidHudTokens.SPACE_1, maxLines = 1,
            )
            val ascent = maxOf(value.baseline, label.baseline)
            val descent = maxOf(value.height - value.baseline, label.height - label.baseline)
            ops += label.lines.map { it.shiftedBy(y + ascent - label.baseline) }
            ops += value.lines.map { it.shiftedBy(y + ascent - value.baseline) }
            y += ascent + descent
        }
        return ops
    }

    private fun columns(readings: List<Reading>, left: Int, top: Int, width: Int): List<TileOp> {
        val ops = ArrayList<TileOp>()
        val gap = RokidHudTokens.SPACE_2
        val columnWidth = (width - (readings.size - 1) * gap) / readings.size
        readings.forEachIndexed { index, reading ->
            val x = left + index * (columnWidth + gap)
            val label = TileText.block(
                TilePart.SUBTITLE, reading.label, TileTextStyle.LABEL, RokidHudTokens.TEXT_SECONDARY,
                x, top, columnWidth, maxLines = 1,
            )
            val value = TileText.block(
                TilePart.VALUE, reading.value, TileTextStyle.DATA, RokidHudTokens.TEXT_PRIMARY,
                x, top + label.height + COLUMN_GAP, columnWidth, maxLines = 1,
            )
            ops += label.lines
            ops += value.lines
        }
        return ops
    }

    /** As the surface status row writes a charge: `82%`, `82%+` while charging. */
    private fun battery(battery: SystemWidgetContent.Battery?): String =
        battery?.let { "${it.level}%" + if (it.charging) "+" else "" } ?: UNKNOWN

    private const val GLASSES = "GLASSES"
    private const val PHONE = "PHONE"
    private const val LINK = "LINK"
    private const val LINK_UP = "UP"
    private const val LINK_DOWN = "DOWN"
    private const val UNKNOWN = "--"
    private const val ROW_GAP = RokidHudTokens.SPACE_1
    private const val COLUMN_GAP = 2
}
