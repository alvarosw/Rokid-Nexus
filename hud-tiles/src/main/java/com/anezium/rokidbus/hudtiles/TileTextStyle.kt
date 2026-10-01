package com.anezium.rokidbus.hudtiles

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import com.anezium.rokidbus.client.ui.RokidHudTokens

/**
 * The text styles a tile draws with, in glasses pixels. [includeFontPadding] follows what the
 * glasses' views always used for that style, so line heights do not move between the two.
 * A [lineHeightPx] above zero sets the line box as the design's CSS does (text centered in it);
 * zero keeps the font's own line height, which the generic template's styles use.
 */
enum class TileTextStyle(
    val sizePx: Float,
    val letterSpacingEm: Float,
    val includeFontPadding: Boolean,
    val lineHeightPx: Int = 0,
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

    /** `heading`, 16 / 22: a template's emphasized title or current line. */
    HEADING(RokidHudTokens.HEADING_TEXT_SIZE, 0f, false, 22),

    /** `body`, 14 / 20. */
    TEXT(RokidHudTokens.BODY_TEXT_SIZE, 0f, false, 20),

    /** `body` at 500 weight, 14 / 20: a sender's name. */
    TEXT_MEDIUM(RokidHudTokens.BODY_TEXT_SIZE, 0f, false, 20),

    /** `body-small`, 12 / 16: paragraphs, context lines, artists. */
    SMALL(RokidHudTokens.BODY_SMALL_TEXT_SIZE, 0f, false, 16),

    /** `body-small` at 500 weight, 12 / 16. */
    SMALL_MEDIUM(RokidHudTokens.BODY_SMALL_TEXT_SIZE, 0f, false, 16),

    /** `mono`, 11 / 14: ages, times, item meta, "+N more". */
    META(RokidHudTokens.MONO_TEXT_SIZE, 0f, false, 14),

    /** `data`, 13 / 18: counts and the large tile's times. */
    FIGURE(RokidHudTokens.DATA_TEXT_SIZE, 0f, false, 18),

    /** `label`, 11 / 14, inside the content (a source caption). */
    CAPTION(RokidHudTokens.LABEL_TEXT_SIZE, RokidHudTokens.LABEL_LETTER_SPACING_EM, false, 14),
    ;

    fun typeface(): Typeface = when (this) {
        LABEL, CAPTION, TEXT_MEDIUM, SMALL_MEDIUM -> RokidHudTokens.labelTypeface()
        BODY, DETAIL, TEXT, SMALL -> RokidHudTokens.bodyTypeface()
        DATA, DATA_DETAIL, FIGURE -> RokidHudTokens.dataTypeface()
        MONO, META -> RokidHudTokens.monoTypeface()
        HEADING -> RokidHudTokens.headingTypeface()
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
    val bitmap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun text(style: TileTextStyle): TextPaint = text.getOrPut(style) {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = style.typeface()
            textSize = style.sizePx
            letterSpacing = style.letterSpacingEm
        }
    }
}
