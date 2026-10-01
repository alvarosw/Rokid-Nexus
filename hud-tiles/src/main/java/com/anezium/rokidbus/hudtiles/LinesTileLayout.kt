package com.anezium.rokidbus.hudtiles

import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.WidgetTileContract

/** [TileContent.Lines]: drawn as its down-level generic tile until its own layout lands. */
internal object LinesTileLayout {
    fun layout(input: TileRenderInput, content: TileContent.Lines, size: TileSize): TileLayout =
        GenericTileLayout.layout(input, WidgetTileContract.downLevel(content), size)

    /** The down-level tile is static. */
    @Suppress("UNUSED_PARAMETER")
    fun nextChangeAtElapsed(input: TileRenderInput, content: TileContent.Lines): Long? = null
}
