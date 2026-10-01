package com.anezium.rokidbus.hudtiles

import android.animation.ArgbEvaluator
import com.anezium.rokidbus.client.ui.RokidHudTokens

/** A [rest] intensity moved [amount] of the way to `focus`, as the glasses' home chrome blends it. */
internal fun focusInk(amount: Float, rest: Int): Int = when {
    amount <= 0f -> rest
    amount >= 1f -> RokidHudTokens.FOCUS
    else -> ArgbEvaluator().evaluate(amount, rest, RokidHudTokens.FOCUS) as Int
}

/**
 * The eyebrow every tile carries: the 16 px icon and the `label`-style uppercase name on one line,
 * top-left, `space-1` apart, and optional runs at the right end (a summary, a play state). The end
 * keeps its width while the name keeps at least half the row; past that the last end text
 * ellipsizes, and the name takes what is left and ellipsizes.
 */
internal object TileHeader {
    class Placed(val ops: List<TileOp>, val bottom: Int)

    /** One run at the header's right end, laid out in order, `space-1` apart. */
    sealed interface End {
        data class Text(
            val text: String,
            val style: TileTextStyle = TileTextStyle.LABEL,
            val color: Int = RokidHudTokens.TEXT_PRIMARY,
        ) : End {
            /** `label` is an uppercase style. */
            val shown: String get() = if (style == TileTextStyle.LABEL) text.uppercase() else text
        }

        data class PlayState(val playing: Boolean) : End
    }

    /** [summary] shows at width >= 2, [summaryShort] at width 1. */
    fun layout(
        input: TileRenderInput,
        width: Int,
        cols: Int,
        summary: String = "",
        summaryShort: String = "",
    ): Placed {
        val text = if (cols >= 2) summary else summaryShort
        val style = if (cols >= 2) TileTextStyle.LABEL else TileTextStyle.DATA
        return layout(input, width, if (text.isEmpty()) emptyList() else listOf(End.Text(text, style)))
    }

    fun layout(input: TileRenderInput, width: Int, end: List<End>): Placed {
        val pad = TileRenderer.PADDING
        val icon = RokidHudTokens.ICON_SM
        val gap = RokidHudTokens.SPACE_1
        val right = width - pad - input.headerEndInset
        val nameLeft = pad + icon + gap
        val nameText = input.name.uppercase()
        val row = right - nameLeft
        val widths = end.map { run ->
            when (run) {
                is End.Text -> TileText.desiredWidth(run.shown, run.style)
                is End.PlayState -> icon
            }
        }.toMutableList()
        val endRoom = row - minOf(TileText.desiredWidth(nameText, TileTextStyle.LABEL), row / 2) - gap
        val endWidth = { if (widths.isEmpty()) 0 else widths.sum() + gap * (widths.size - 1) }
        val lastText = end.indexOfLast { it is End.Text }
        if (endWidth() > endRoom && lastText >= 0) {
            widths[lastText] = (widths[lastText] - (endWidth() - endRoom)).coerceAtLeast(0)
        }
        val nameRight = right - if (end.isNotEmpty()) endWidth() + gap else 0
        val name = TileText.block(
            TilePart.NAME, nameText, TileTextStyle.LABEL,
            focusInk(input.focusAmount, RokidHudTokens.TEXT_SECONDARY), nameLeft, 0, nameRight - nameLeft, maxLines = 1,
        )
        val height = maxOf(icon, name.height)
        // Children of a CENTER_VERTICAL row: each centered on the row, rounded down.
        val ops = ArrayList<TileOp>()
        ops += TileOp.Icon(
            input.icon,
            pad.toFloat(),
            (pad + (height - icon) / 2).toFloat(),
            icon.toFloat(),
            focusInk(input.focusAmount, RokidHudTokens.TEXT_PRIMARY),
        )
        ops += name.lines.map { it.shiftedBy(pad + (height - name.height) / 2) }
        var x = right - endWidth()
        end.forEachIndexed { index, run ->
            val runWidth = widths[index]
            when (run) {
                is End.Text -> if (runWidth > 0) {
                    val block = TileText.block(TilePart.SUMMARY, run.shown, run.style, run.color, x, 0, runWidth, maxLines = 1)
                    ops += block.lines.map { it.shiftedBy(pad + (height - block.height) / 2) }
                }
                is End.PlayState -> ops += TileOp.PlayState(
                    run.playing, x.toFloat(), (pad + (height - icon) / 2).toFloat(), icon.toFloat(), RokidHudTokens.TEXT_PRIMARY,
                )
            }
            x += runWidth + gap
        }
        return Placed(ops, pad + height)
    }
}
