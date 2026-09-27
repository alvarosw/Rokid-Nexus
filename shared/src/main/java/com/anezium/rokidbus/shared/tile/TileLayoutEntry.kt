package com.anezium.rokidbus.shared.tile

/**
 * The wearer's chosen tile for one plugin: a size and a position. [col]/[row] are carried for
 * forward compatibility with a future drag-and-drop editor (see the roadmap's "richer" option);
 * today's ordered-list editor derives position purely from list order and re-runs
 * [TileGridPacker] over that order, so a stored [col]/[row] is informational only, not consumed
 * by the packer.
 */
data class TileLayoutEntry(
    val pluginId: String,
    val size: TileSize,
    val col: Int = 0,
    val row: Int = 0,
)
