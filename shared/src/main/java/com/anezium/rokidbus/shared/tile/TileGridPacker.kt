package com.anezium.rokidbus.shared.tile

data class TilePlacement(val pluginId: String, val size: TileSize, val col: Int, val row: Int)

/**
 * Packs `(pluginId, TileSize)` pairs row-major into a fixed-column grid — the same math as the
 * mockup's `rect(col, row, w, h)`, against native `View` bounds instead of CSS. Pure, deterministic
 * for a given input order, and has no Android framework dependency so it stays unit-testable
 * without instrumentation. Delivery 4 later overrides this auto-pack default with a wearer-chosen
 * arrangement; nothing here anticipates that beyond taking placements as data.
 */
object TileGridPacker {
    const val DEFAULT_COLUMNS = 4

    /**
     * A null or unrecognized size falls back to [TileSize.SMALL] — the same "absent declaration
     * degrades gracefully" rule the icon/glyph metadata already follows.
     */
    fun pack(entries: List<Pair<String, TileSize?>>, columns: Int = DEFAULT_COLUMNS): List<TilePlacement> {
        require(entries.all { (_, size) -> columns >= (size ?: TileSize.SMALL).cols }) {
            "columns must fit the widest tile size in use"
        }
        val occupied = HashSet<Long>()
        fun cellKey(row: Int, col: Int): Long = row.toLong() * columns + col

        fun fits(row: Int, col: Int, size: TileSize): Boolean {
            if (col + size.cols > columns) return false
            for (r in row until row + size.rows) {
                for (c in col until col + size.cols) {
                    if (cellKey(r, c) in occupied) return false
                }
            }
            return true
        }

        fun occupy(row: Int, col: Int, size: TileSize) {
            for (r in row until row + size.rows) {
                for (c in col until col + size.cols) {
                    occupied += cellKey(r, c)
                }
            }
        }

        val placements = mutableListOf<TilePlacement>()
        entries.forEach { (pluginId, declaredSize) ->
            val size = declaredSize ?: TileSize.SMALL
            var row = 0
            var placedCol = -1
            while (placedCol < 0) {
                for (col in 0..(columns - size.cols)) {
                    if (fits(row, col, size)) {
                        placedCol = col
                        break
                    }
                }
                if (placedCol < 0) row++
            }
            occupy(row, placedCol, size)
            placements += TilePlacement(pluginId, size, placedCol, row)
        }
        return placements
    }
}
