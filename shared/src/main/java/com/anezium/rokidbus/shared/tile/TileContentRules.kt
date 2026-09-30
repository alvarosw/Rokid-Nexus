package com.anezium.rokidbus.shared.tile

/**
 * What a tile of a given [TileSize] shows of a [TileSnapshot]: the one set of rules both the
 * glasses' live tile and the phone's layout preview follow, so the preview reads like the glasses.
 *
 * | part     | rule                                                                              |
 * |----------|-----------------------------------------------------------------------------------|
 * | header   | icon and uppercase name on one line at every size ([NAME_LINES])                  |
 * | title    | numeric title = data value (+ unit when set), else text; none when empty          |
 * |          | one line at height 1, two lines from height 2                                     |
 * | subtitle | when present and (width >= 2 or height >= 2); one line at height 1, else two      |
 * | badge    | when present, at every size                                                       |
 * | rows     | height 1: none; height 2: up to [ROWS_AT_TWO_HIGH]; 3+: up to [WidgetTileContract.MAX_ROWS] |
 * | progress | when the snapshot carries one                                                     |
 */
object TileContentRules {
    const val NAME_LINES = 1
    const val ROWS_AT_TWO_HIGH = 3

    enum class TitleStyle { NONE, TEXT, DATA_VALUE }

    data class TileContent(
        val nameLines: Int,
        val titleStyle: TitleStyle,
        val titleMaxLines: Int,
        val showUnit: Boolean,
        val subtitleVisible: Boolean,
        val subtitleMaxLines: Int,
        val badgeVisible: Boolean,
        val rowCount: Int,
        /** Progress clamped to 0..1, or null when the track is not shown. */
        val progress: Float?,
    ) {
        val progressVisible: Boolean get() = progress != null
    }

    /** How many rows a tile of [size] can show at most. */
    fun rowCap(size: TileSize): Int = when {
        size.rows <= 1 -> 0
        size.rows == 2 -> ROWS_AT_TWO_HIGH
        else -> WidgetTileContract.MAX_ROWS
    }

    /** Whether [title] renders as a numeric data value rather than text. */
    fun isDataValue(title: String): Boolean = title.toDoubleOrNull() != null

    /** With a null [snapshot] only the header remains. */
    fun contentFor(size: TileSize, snapshot: TileSnapshot?): TileContent {
        val oneRow = size.rows == 1
        if (snapshot == null) {
            return TileContent(
                nameLines = NAME_LINES,
                titleStyle = TitleStyle.NONE,
                titleMaxLines = if (oneRow) 1 else 2,
                showUnit = false,
                subtitleVisible = false,
                subtitleMaxLines = if (oneRow) 1 else 2,
                badgeVisible = false,
                rowCount = 0,
                progress = null,
            )
        }
        val numeric = isDataValue(snapshot.title)
        return TileContent(
            nameLines = NAME_LINES,
            titleStyle = when {
                numeric -> TitleStyle.DATA_VALUE
                snapshot.title.isNotEmpty() -> TitleStyle.TEXT
                else -> TitleStyle.NONE
            },
            titleMaxLines = if (oneRow) 1 else 2,
            showUnit = numeric && snapshot.unit.isNotEmpty(),
            subtitleVisible = (size.cols >= 2 || size.rows >= 2) && snapshot.subtitle.isNotEmpty(),
            subtitleMaxLines = if (oneRow) 1 else 2,
            badgeVisible = snapshot.badge.isNotEmpty(),
            rowCount = minOf(snapshot.rows.size, rowCap(size)),
            progress = snapshot.progress?.coerceIn(0f, 1f),
        )
    }
}
