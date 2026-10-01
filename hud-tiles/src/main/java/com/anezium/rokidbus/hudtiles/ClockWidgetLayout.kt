package com.anezium.rokidbus.hudtiles

import android.graphics.Rect
import android.text.format.DateFormat
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.tile.TileSize
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The clock widget: the header, the time in the display-size data face (with a 12-hour marker
 * when the system uses one), then the date, longer as the tile widens. A two-row tile gives the
 * weekday its own line. Every size has a layout; the editor offers 1x1, 2x1, 3x1 and 2x2.
 */
internal object ClockWidgetLayout {
    fun layout(input: SystemWidgetInput, content: SystemWidgetContent.Clock, size: TileSize): TileLayout {
        val width = TileRenderer.widthOf(size)
        val height = TileRenderer.heightOf(size)
        val pad = TileRenderer.PADDING
        val inner = width - 2 * pad
        val header = SystemWidgetRenderer.header(input, width, size.cols)
        val body = ArrayList<TileOp>()
        var y = header.bottom + RokidHudTokens.SPACE_1
        y += timeRow(body, content, pad, y, inner)
        if (size.rows >= 2) {
            y += DATE_GAP
            val weekday = TileText.block(
                TilePart.TITLE, format(content, WEEKDAY), TileTextStyle.BODY, RokidHudTokens.TEXT_PRIMARY,
                pad, y, inner, maxLines = 1,
            )
            body += weekday.lines
            y += weekday.height
        }
        y += if (size.rows >= 2) LINE_GAP else DATE_GAP
        val date = TileText.block(
            TilePart.SUBTITLE, format(content, dateSkeleton(size)), TileTextStyle.DETAIL, RokidHudTokens.TEXT_SECONDARY,
            pad, y, inner, maxLines = 1,
        )
        body += date.lines
        return TileLayout(width, height, header.ops, body, Rect(pad, header.bottom, width - pad, height - pad), emptyList())
    }

    /** The start of the next wall-clock minute, on the elapsed clock [input] was sampled on. */
    fun nextChangeAtElapsed(input: SystemWidgetInput, content: SystemWidgetContent.Clock): Long {
        val local = content.epochMs + content.timeZone.getOffset(content.epochMs)
        return input.nowElapsed + (MINUTE_MS - Math.floorMod(local, MINUTE_MS))
    }

    /** The date's skeleton per size: a one-row tile has one date line, the weekday folded in. */
    private fun dateSkeleton(size: TileSize): String = when {
        size.rows >= 2 -> if (size.cols >= 2) "MMMMdy" else "MMMd"
        size.cols >= 3 -> "EEEEMMMMd"
        size.cols == 2 -> "EEEMMMd"
        else -> "EEEd"
    }

    /** The time and its 12-hour marker on one baseline; returns the row's height. */
    private fun timeRow(body: MutableList<TileOp>, content: SystemWidgetContent.Clock, left: Int, top: Int, width: Int): Int {
        val text = pattern(content, if (content.use24Hour) "HH:mm" else "h:mm")
        val valueWidth = minOf(TileText.desiredWidth(text, TileTextStyle.DATA_DISPLAY), width)
        val value = TileText.block(
            TilePart.VALUE, text, TileTextStyle.DATA_DISPLAY, RokidHudTokens.TEXT_PRIMARY, left, 0, valueWidth,
            maxLines = 1, ellipsize = false,
        )
        val markerLeft = left + valueWidth + RokidHudTokens.SPACE_1
        val marker = if (content.use24Hour) {
            null
        } else {
            TileText.block(
                TilePart.UNIT, pattern(content, "a"), TileTextStyle.MONO, RokidHudTokens.TEXT_SECONDARY,
                markerLeft, 0, left + width - markerLeft, maxLines = 1, ellipsize = false,
            )
        }
        val ascent = maxOf(value.baseline, marker?.baseline ?: 0)
        val descent = maxOf(value.height - value.baseline, marker?.let { it.height - it.baseline } ?: 0)
        body += value.lines.map { it.shiftedBy(top + ascent - value.baseline) }
        marker?.let { block -> body += block.lines.map { it.shiftedBy(top + ascent - block.baseline) } }
        return ascent + descent
    }

    private fun format(content: SystemWidgetContent.Clock, skeleton: String): String =
        pattern(content, DateFormat.getBestDateTimePattern(content.locale, skeleton))

    private fun pattern(content: SystemWidgetContent.Clock, pattern: String): String =
        // java.time, not SimpleDateFormat: the best patterns use stand-alone letters (`c`, `L`).
        DateTimeFormatter.ofPattern(pattern, content.locale)
            .format(Instant.ofEpochMilli(content.epochMs).atZone(content.timeZone.toZoneId()))

    private const val WEEKDAY = "EEEE"
    private const val DATE_GAP = RokidHudTokens.SPACE_1
    private const val LINE_GAP = 2
    private const val MINUTE_MS = 60_000L
}
