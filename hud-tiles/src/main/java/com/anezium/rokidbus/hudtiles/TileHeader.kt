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
 * top-left, `space-1` apart, and an optional summary at the right end. The name takes what is left
 * of the row and ellipsizes.
 */
internal object TileHeader {
    class Placed(val ops: List<TileOp>, val bottom: Int)

    /** [summary] shows at width >= 2, [summaryShort] at width 1. */
    fun layout(
        input: TileRenderInput,
        width: Int,
        cols: Int,
        summary: String = "",
        summaryShort: String = "",
    ): Placed {
        val pad = TileRenderer.PADDING
        val icon = RokidHudTokens.ICON_SM
        val right = width - pad - input.headerEndInset
        val summaryText = if (cols >= 2) summary else summaryShort
        val summaryStyle = if (cols >= 2) TileTextStyle.LABEL else TileTextStyle.DATA
        val summaryWidth = if (summaryText.isEmpty()) 0 else TileText.desiredWidth(summaryText, summaryStyle)
        val nameLeft = pad + icon + RokidHudTokens.SPACE_1
        val nameRight = right - if (summaryWidth > 0) summaryWidth + RokidHudTokens.SPACE_1 else 0
        val name = TileText.block(
            TilePart.NAME, input.name.uppercase(), TileTextStyle.LABEL,
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
        if (summaryWidth > 0) {
            val block = TileText.block(
                TilePart.SUMMARY, summaryText, summaryStyle, RokidHudTokens.TEXT_PRIMARY,
                right - summaryWidth, 0, summaryWidth, maxLines = 1,
            )
            ops += block.lines.map { it.shiftedBy(pad + (height - block.height) / 2) }
        }
        return Placed(ops, pad + height)
    }
}
