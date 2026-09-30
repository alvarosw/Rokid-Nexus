package com.anezium.rokidbus.client.ui

/**
 * The glasses home grid's geometry in physical pixels, shared by the glasses renderer and the
 * phone's layout preview so both agree on where the screen ends. All values derive from
 * [RokidHudTokens]; nothing here depends on the display density.
 */
object HudGridMetrics {
    const val COLUMNS = 4

    /** (448 - 3 x 8) / 4 = 106 px. */
    const val UNIT = (RokidHudTokens.CONTENT_WIDTH - (COLUMNS - 1) * RokidHudTokens.SPACE_2) / COLUMNS
    const val GAP = RokidHudTokens.SPACE_2
    const val PITCH = UNIT + GAP

    /** A grid body is never shorter than this: a two-row tile must be able to show whole. */
    const val MIN_VISIBLE_ROWS = 2

    /** `label`-style title row of the home screen. */
    const val HEADER_HEIGHT = 16

    /** `body-small` 16 px line plus `space-1` above and below. */
    const val STATUS_HEIGHT = 24

    /** `space-1` between the header, the body and the status slot. */
    const val SECTION_GAP = RokidHudTokens.SPACE_1

    /** Pixels left for the body under the safe area, the header and the status slot. */
    fun availableBodyHeight(topInsetPx: Int, screenHeight: Int = RokidHudTokens.CANVAS_HEIGHT): Int =
        screenHeight - (RokidHudTokens.SAFE_Y + topInsetPx) - RokidHudTokens.SAFE_Y -
            HEADER_HEIGHT - 2 * SECTION_GAP - STATUS_HEIGHT

    /** Whole grid rows that fit in [available] body pixels, never fewer than [MIN_VISIBLE_ROWS]. */
    fun rowsFitting(available: Int): Int =
        ((available + GAP) / PITCH).coerceAtLeast(MIN_VISIBLE_ROWS)

    /** Height of [rows] grid rows. */
    fun rowsHeight(rows: Int): Int = rows * UNIT + (rows - 1) * GAP

    /** Whole grid rows the home screen shows without scrolling for a given top inset. */
    fun visibleRows(topInsetPx: Int, screenHeight: Int = RokidHudTokens.CANVAS_HEIGHT): Int =
        rowsFitting(availableBodyHeight(topInsetPx, screenHeight))
}
