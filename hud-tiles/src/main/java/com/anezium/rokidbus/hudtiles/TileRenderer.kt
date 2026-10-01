package com.anezium.rokidbus.hudtiles

import com.anezium.rokidbus.client.ui.HudGridMetrics
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * The one tile renderer both hubs draw with: the glasses' home grid and the phone's layout editor.
 * It owns every layout decision per template and size; a host draws the chrome around it (border,
 * focus fill, loaders, the alert mark) and nothing inside.
 */
object TileRenderer {
    /** The tile's inner padding; a host's 1 or 2 px border is drawn inside it. */
    const val PADDING = RokidHudTokens.SPACE_2

    fun widthOf(size: TileSize): Int = extent(size.cols)

    fun heightOf(size: TileSize): Int = extent(size.rows)

    /** [input] positioned at [size]'s real glasses size; pure, nothing is drawn. */
    fun layout(input: TileRenderInput, size: TileSize): TileLayout = when (val content = input.content) {
        null -> GenericTileLayout.layout(input, null, size)
        is TileContent.Generic -> GenericTileLayout.layout(input, content, size)
        is TileContent.Music -> MusicTileLayout.layout(input, content, size)
        is TileContent.Lines -> LinesTileLayout.layout(input, content, size)
        is TileContent.ListContent -> ListTileLayout.layout(input, content, size)
    }

    /**
     * The `elapsedRealtime` at which [input]'s drawing next changes on its own (a music position
     * second, the next timed line, the next age minute), or null when it never does. A host
     * schedules one redraw for it, and only while the tile is on screen. [size] matters because
     * only some sizes show a moving time.
     */
    fun nextChangeAtElapsed(input: TileRenderInput, size: TileSize): Long? = when (val content = input.content) {
        null, is TileContent.Generic -> null
        is TileContent.Music -> MusicTileLayout.nextChangeAtElapsed(input, content)
        is TileContent.Lines -> LinesTileLayout.nextChangeAtElapsed(input, content, size)
        is TileContent.ListContent -> ListTileLayout.nextChangeAtElapsed(input, content, size)
    }

    private fun extent(cells: Int) = cells * HudGridMetrics.UNIT + (cells - 1) * HudGridMetrics.GAP
}
