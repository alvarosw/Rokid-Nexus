package com.anezium.rokidbus.hudtiles

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import com.anezium.rokidbus.client.ui.RokidHudTokens

/**
 * The text styles a tile draws with, in glasses pixels. [includeFontPadding] follows what the
 * glasses' views always used for that style, so line heights do not move between the two.
 */
enum class TileTextStyle(
    val sizePx: Float,
    val letterSpacingEm: Float,
    val includeFontPadding: Boolean,
) {
    /** `label`: the plugin name and the header summary. */
    LABEL(RokidHudTokens.LABEL_TEXT_SIZE, RokidHudTokens.LABEL_LETTER_SPACING_EM, false),

    /** `body`: a text title. */
    BODY(RokidHudTokens.BODY_TEXT_SIZE, 0f, false),

    /** `data`: a numeric title, and the short header summary of a one-wide tile. */
    DATA(RokidHudTokens.DATA_TEXT_SIZE, 0f, true),

    /** `mono`: a unit beside a data value. */
    MONO(RokidHudTokens.MONO_TEXT_SIZE, 0f, false),

    /** The body face at label size: subtitles and rows. */
    DETAIL(RokidHudTokens.LABEL_TEXT_SIZE, 0f, false),

    /** The data face at label size: the badge. */
    DATA_DETAIL(RokidHudTokens.LABEL_TEXT_SIZE, 0f, true),
    ;

    fun typeface(): Typeface = when (this) {
        LABEL -> RokidHudTokens.labelTypeface()
        BODY, DETAIL -> RokidHudTokens.bodyTypeface()
        DATA, DATA_DETAIL -> RokidHudTokens.dataTypeface()
        MONO -> RokidHudTokens.monoTypeface()
    }
}

/**
 * One paint per style, shared by layout (measuring) and drawing. Tiles are laid out and drawn on
 * the main thread only.
 */
internal object TilePaints {
    private val text = HashMap<TileTextStyle, TextPaint>()
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun text(style: TileTextStyle): TextPaint = text.getOrPut(style) {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = style.typeface()
            textSize = style.sizePx
            letterSpacing = style.letterSpacingEm
        }
    }
}
