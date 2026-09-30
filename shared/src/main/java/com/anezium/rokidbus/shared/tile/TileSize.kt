package com.anezium.rokidbus.shared.tile

import com.anezium.rokidbus.shared.plugin.splitMetadataList

/**
 * A fixed, small set of tile shapes rather than free `w×h` — this is what keeps the auto-pack
 * algorithm and the phone-app size picker simple: a handful of known shapes, not arbitrary
 * rectangles. Declaration order is load-bearing (defaults iterate [entries]): new shapes are
 * only ever appended.
 */
enum class TileSize(val cols: Int, val rows: Int, val wireValue: String) {
    SMALL(1, 1, "1x1"),
    WIDE(2, 1, "2x1"),
    TALL(1, 2, "1x2"),
    LARGE(2, 2, "2x2"),
    BANNER(3, 1, "3x1"),
    PANEL(3, 2, "3x2"),
    JUMBO(3, 3, "3x3"),
    ;

    companion object {
        /** The order the phone's size picker lists the shapes in; [entries] order is fixed by append-only history. */
        val PICKER_ORDER: List<TileSize> = listOf(SMALL, WIDE, BANNER, TALL, LARGE, PANEL, JUMBO)

        fun fromWireValue(value: String): TileSize? = entries.firstOrNull { it.wireValue == value }

        /** Absent/empty input is valid and means "no declared sizes" — an empty set, not an error. */
        fun parseList(value: String): TileSizeParseResult {
            val rawValues = splitMetadataList(value)
            val parsed = linkedSetOf<TileSize>()
            rawValues.forEach { raw ->
                val size = fromWireValue(raw) ?: return TileSizeParseResult.Invalid("INVALID_TILE_SIZE")
                parsed += size
            }
            return TileSizeParseResult.Valid(parsed)
        }
    }
}

sealed interface TileSizeParseResult {
    data class Valid(val sizes: Set<TileSize>) : TileSizeParseResult
    data class Invalid(val reason: String) : TileSizeParseResult
}
