package com.anezium.rokidbus.hudtiles

import android.graphics.Rect
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.WeatherContract
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * The weather widget: the current temperature in the display-size data face with its unit, the
 * condition, today's high and low and, from two wide or two tall, the place. A three-wide one-row
 * tile adds the next hours at its right; a two-row tile adds a row of hours and a row of days under
 * a separator, two per column of width, as long as they fit. A one-wide tile drops the place first.
 *
 * A reading older than [WeatherContract.STALE_AFTER_MS] dims like a stale tile and puts its age in
 * the header. Every size has a layout; the editor offers 1x1, 2x1, 3x1, 2x2 and 3x2.
 */
internal object WeatherWidgetLayout {
    private class Inks(stale: Boolean) {
        private val dim = { color: Int -> if (stale) RokidHudTokens.scaleAlpha(color, GenericTileLayout.STALE_ALPHA) else color }
        val primary = dim(RokidHudTokens.TEXT_PRIMARY)
        val secondary = dim(RokidHudTokens.TEXT_SECONDARY)
        val line = dim(RokidHudTokens.LINE)
    }

    /** One line of a forecast column. */
    private class Cell(val text: String, val style: TileTextStyle, val color: Int)

    fun layout(input: SystemWidgetInput, content: SystemWidgetContent.Weather, size: TileSize): TileLayout {
        val width = TileRenderer.widthOf(size)
        val height = TileRenderer.heightOf(size)
        val pad = TileRenderer.PADDING
        val inner = width - 2 * pad
        val bottom = height - pad
        val reading = content.reading
        val age = if (reading != null && isStale(content.ageMs)) ageLabel(content.ageMs) else null
        val header = SystemWidgetRenderer.header(
            input, width, size.cols,
            summary = age?.let { "$it $AGO" }.orEmpty(),
            summaryShort = age.orEmpty(),
        )
        val clip = Rect(pad, header.bottom, width - pad, bottom)
        if (reading == null) {
            val waiting = TileText.block(
                TilePart.SUBTITLE, NO_DATA, TileTextStyle.DETAIL, RokidHudTokens.TEXT_SECONDARY,
                pad, header.bottom + RokidHudTokens.SPACE_1, inner, maxLines = 2,
            )
            return TileLayout(width, height, header.ops, waiting.lines, clip, emptyList())
        }
        val ink = Inks(stale = age != null)
        val body = ArrayList<TileOp>()
        val top = header.bottom + RokidHudTokens.SPACE_1
        val hoursBeside = size.cols >= 3 && size.rows == 1
        var y = if (size.cols == 1) {
            stacked(body, reading, ink, pad, top, inner, withPlace = size.rows >= 2)
        } else {
            val besideWidth = if (hoursBeside) inner / 2 else 0
            val currentWidth = inner - if (besideWidth > 0) besideWidth + RokidHudTokens.SPACE_2 else 0
            val currentBottom = sideBySide(body, reading, ink, pad, top, currentWidth)
            if (hoursBeside) {
                columns(body, hourCells(reading, ink, BESIDE_HOURS), pad + inner - besideWidth, top, besideWidth, bottom)
            }
            currentBottom
        }
        if (size.rows >= 2) {
            val count = minOf(WeatherContract.MAX_POINTS, size.cols * 2)
            listOf(hourCells(reading, ink, count), dayCells(reading, ink, count)).forEach { cells ->
                if (cells.isEmpty()) return@forEach
                val separatorTop = y + SECTION_GAP
                val columnsTop = separatorTop + 1 + SECTION_GAP
                val section = ArrayList<TileOp>()
                section += TileOp.Separator(pad.toFloat(), separatorTop.toFloat(), (width - pad).toFloat(), ink.line)
                val used = columns(section, cells, pad, columnsTop, inner, bottom)
                if (used < 0) return@forEach
                body += section
                y = columnsTop + used
            }
        }
        return TileLayout(width, height, header.ops, body, clip, emptyList())
    }

    /** When the reading turns stale, then when its age label next changes; null without a known age. */
    fun nextChangeAtElapsed(input: SystemWidgetInput, content: SystemWidgetContent.Weather): Long? {
        if (content.reading == null) return null
        val age = content.ageMs ?: return null
        if (age < WeatherContract.STALE_AFTER_MS) return input.nowElapsed + (WeatherContract.STALE_AFTER_MS - age)
        val unit = ageUnit(age)
        return input.nowElapsed + (unit - age % unit)
    }

    private fun isStale(ageMs: Long?): Boolean = ageMs == null || ageMs >= WeatherContract.STALE_AFTER_MS

    /** `3H`, `2D`; `OLD` when the age cannot be told. */
    private fun ageLabel(ageMs: Long?): String {
        val age = ageMs ?: return UNKNOWN_AGE
        return when (val unit = ageUnit(age)) {
            MINUTE_MS -> "${age / unit}M"
            HOUR_MS -> "${age / unit}H"
            else -> "${age / unit}D"
        }
    }

    private fun ageUnit(ageMs: Long): Long = when {
        ageMs < HOUR_MS -> MINUTE_MS
        ageMs < 2 * DAY_MS -> HOUR_MS
        else -> DAY_MS
    }

    /** A one-wide tile: temperature, condition, high and low, then the place; returns the bottom. */
    private fun stacked(
        body: MutableList<TileOp>,
        reading: WeatherContract.Reading,
        ink: Inks,
        left: Int,
        top: Int,
        width: Int,
        withPlace: Boolean,
    ): Int {
        var y = top + temperature(body, reading, ink, left, top, width).height
        y += LINE_GAP
        val condition = TileText.block(TilePart.TITLE, reading.condition, TileTextStyle.DETAIL, ink.secondary, left, y, width, maxLines = 1)
        body += condition.lines
        y += condition.height + LINE_GAP
        val range = TileText.block(TilePart.BADGE, range(reading), TileTextStyle.DATA_DETAIL, ink.primary, left, y, width, maxLines = 1)
        body += range.lines
        y += range.height
        if (withPlace && reading.location.isNotEmpty()) {
            y += LINE_GAP
            val place = TileText.block(TilePart.SUBTITLE, reading.location, TileTextStyle.DETAIL, ink.secondary, left, y, width, maxLines = 1)
            body += place.lines
            y += place.height
        }
        return y
    }

    /**
     * Two wide and more: the temperature, with the condition over the high and low beside it, then
     * the place under both; returns the bottom.
     */
    private fun sideBySide(
        body: MutableList<TileOp>,
        reading: WeatherContract.Reading,
        ink: Inks,
        left: Int,
        top: Int,
        width: Int,
    ): Int {
        val temperature = temperature(body, reading, ink, left, top, width)
        val besideLeft = left + temperature.width + RokidHudTokens.SPACE_3
        val besideWidth = left + width - besideLeft
        var besideBottom = top
        if (besideWidth > 0) {
            val condition = TileText.block(
                TilePart.TITLE, reading.condition, TileTextStyle.BODY, ink.primary, besideLeft, top, besideWidth, maxLines = 1,
            )
            val range = TileText.block(
                TilePart.BADGE, range(reading), TileTextStyle.DATA_DETAIL, ink.secondary,
                besideLeft, top + condition.height + LINE_GAP, besideWidth, maxLines = 1,
            )
            body += condition.lines
            body += range.lines
            besideBottom = top + condition.height + LINE_GAP + range.height
        }
        var y = maxOf(top + temperature.height, besideBottom)
        if (reading.location.isNotEmpty()) {
            y += LINE_GAP
            val place = TileText.block(TilePart.SUBTITLE, reading.location, TileTextStyle.DETAIL, ink.secondary, left, y, width, maxLines = 1)
            body += place.lines
            y += place.height
        }
        return y
    }

    private class Placed(val width: Int, val height: Int)

    /** The temperature and its unit on one baseline, as the clock sets its 12-hour marker. */
    private fun temperature(body: MutableList<TileOp>, reading: WeatherContract.Reading, ink: Inks, left: Int, top: Int, width: Int): Placed {
        val text = reading.temperature.toString()
        val valueWidth = minOf(TileText.desiredWidth(text, TileTextStyle.DATA_DISPLAY), width)
        val value = TileText.block(
            TilePart.VALUE, text, TileTextStyle.DATA_DISPLAY, ink.primary, left, 0, valueWidth, maxLines = 1, ellipsize = false,
        )
        val unitLeft = left + valueWidth + RokidHudTokens.SPACE_1
        val unitWidth = minOf(TileText.desiredWidth(reading.unit.symbol, TileTextStyle.MONO), left + width - unitLeft)
        val unit = TileText.block(
            TilePart.UNIT, reading.unit.symbol, TileTextStyle.MONO, ink.secondary, unitLeft, 0, unitWidth,
            maxLines = 1, ellipsize = false,
        )
        val ascent = maxOf(value.baseline, unit.baseline)
        val descent = maxOf(value.height - value.baseline, unit.height - unit.baseline)
        body += value.lines.map { it.shiftedBy(top + ascent - value.baseline) }
        body += unit.lines.map { it.shiftedBy(top + ascent - unit.baseline) }
        return Placed(unitLeft + unitWidth - left, ascent + descent)
    }

    private fun range(reading: WeatherContract.Reading) = "H ${degrees(reading.high)}  L ${degrees(reading.low)}"

    private fun degrees(value: Int) = "$value°"

    private fun hourCells(reading: WeatherContract.Reading, ink: Inks, count: Int): List<List<Cell>> =
        reading.hourly.take(count).map { hour ->
            listOf(
                Cell(hour.label.uppercase(), TileTextStyle.LABEL, ink.secondary),
                Cell(degrees(hour.temperature), TileTextStyle.DATA_DETAIL, ink.primary),
            )
        }

    private fun dayCells(reading: WeatherContract.Reading, ink: Inks, count: Int): List<List<Cell>> =
        reading.daily.take(count).map { day ->
            listOf(
                Cell(day.label.uppercase(), TileTextStyle.LABEL, ink.secondary),
                Cell(degrees(day.high), TileTextStyle.DATA_DETAIL, ink.primary),
                Cell(degrees(day.low), TileTextStyle.DATA_DETAIL, ink.secondary),
            )
        }

    /**
     * [cells] as equal columns across [width], each line centered in its column; returns the height
     * used, or -1 (and adds nothing) when they would pass [bottom].
     */
    private fun columns(body: MutableList<TileOp>, cells: List<List<Cell>>, left: Int, top: Int, width: Int, bottom: Int): Int {
        if (cells.isEmpty()) return 0
        val columnWidth = width / cells.size
        val ops = ArrayList<TileOp>()
        var height = 0
        cells.forEachIndexed { index, column ->
            val columnLeft = left + index * columnWidth
            var y = top
            column.forEachIndexed { line, cell ->
                if (line > 0) y += CELL_GAP
                val textWidth = minOf(TileText.desiredWidth(cell.text, cell.style), columnWidth)
                val block = TileText.block(
                    TilePart.ROW, cell.text, cell.style, cell.color,
                    columnLeft + (columnWidth - textWidth) / 2, y, textWidth, maxLines = 1,
                )
                ops += block.lines
                y += block.height
            }
            height = maxOf(height, y - top)
        }
        if (top + height > bottom) return -1
        body += ops
        return height
    }

    private const val NO_DATA = "No weather from the phone yet"
    private const val AGO = "AGO"
    private const val UNKNOWN_AGE = "OLD"
    private const val BESIDE_HOURS = 3
    private const val LINE_GAP = 2
    private const val CELL_GAP = 1
    private const val SECTION_GAP = RokidHudTokens.SPACE_1
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS
}
