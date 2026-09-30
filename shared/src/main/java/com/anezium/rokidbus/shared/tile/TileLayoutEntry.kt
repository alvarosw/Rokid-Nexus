package com.anezium.rokidbus.shared.tile

/**
 * The wearer's chosen tile for one plugin: a size and a position. Since layout config v2 the
 * [col]/[row] are authoritative: the glasses place the tile exactly there (see
 * [TileGridLayout.resolve]), holes included, instead of re-packing the stored order. Entries from
 * a v1 phone carry the positions the packer produced, so they read the same way.
 */
data class TileLayoutEntry(
    val pluginId: String,
    val size: TileSize,
    val col: Int = 0,
    val row: Int = 0,
)
