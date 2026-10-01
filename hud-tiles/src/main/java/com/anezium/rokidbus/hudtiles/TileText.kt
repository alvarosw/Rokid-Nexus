package com.anezium.rokidbus.hudtiles

import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import kotlin.math.ceil

/** A run of text laid out from a top edge: its line ops, the height it takes and its first baseline. */
internal class TextBlock(val lines: List<TileOp.Text>, val height: Int, val baseline: Int)

internal fun TileOp.Text.shiftedBy(dy: Int) = copy(top = top + dy, bottom = bottom + dy, baseline = baseline + dy)

/**
 * Text laid out the way the glasses' `TextView`s laid it out (same break strategy, padding and END
 * ellipsis), so a tile drawn here wraps and clamps exactly as it did before.
 */
internal object TileText {
    fun block(
        part: TilePart,
        value: String,
        style: TileTextStyle,
        color: Int,
        left: Int,
        top: Int,
        width: Int,
        maxLines: Int,
        ellipsize: Boolean = true,
    ): TextBlock {
        val paint = TilePaints.text(style)
        val room = width.coerceAtLeast(0)
        val builder = StaticLayout.Builder.obtain(value, 0, value.length, paint, room)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(style.includeFontPadding)
            .setLineSpacing(0f, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setMaxLines(maxLines)
        if (ellipsize) builder.setEllipsize(TextUtils.TruncateAt.END).setEllipsizedWidth(room)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setUseLineSpacingFromFallbacks(true)
        val layout = builder.build()
        val lines = (0 until minOf(layout.lineCount, maxLines)).mapNotNull { line ->
            val start = layout.getLineStart(line)
            val ellipsisCount = layout.getEllipsisCount(line)
            val shown = if (ellipsisCount > 0) {
                value.substring(start, start + layout.getEllipsisStart(line)).trimEnd() + ELLIPSIS
            } else {
                value.substring(start, layout.getLineEnd(line)).trimEnd('\n')
            }
            if (shown.isBlank()) return@mapNotNull null
            TileOp.Text(
                part = part,
                text = shown,
                left = left + layout.getLineLeft(line),
                top = (top + layout.getLineTop(line)).toFloat(),
                bottom = (top + layout.getLineBottom(line)).toFloat(),
                baseline = (top + layout.getLineBaseline(line)).toFloat(),
                style = style,
                color = color,
            )
        }
        return TextBlock(lines, layout.height, layout.getLineBaseline(0))
    }

    /** What a `WRAP_CONTENT` view of [value] measures across. */
    fun desiredWidth(value: String, style: TileTextStyle): Int =
        ceil(Layout.getDesiredWidth(value, TilePaints.text(style))).toInt()

    private const val ELLIPSIS = "…"
}
