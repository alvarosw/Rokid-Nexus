package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.tile.TileSize

/**
 * Which sizes the layout editor offers for one plugin: its declared subset of [TileSize.entries]
 * (`TILE_SIZES` from the plugin manifest, parsed into `PluginDescriptor.supportedTileSizes`), or
 * the full fixed enum when it declared none — the generic fallback tile renders any size with no
 * plugin cooperation required, so an absent declaration is not a restriction. A picked-outside-
 * declaration size is never offered by the editor, so this alone is what keeps acceptance
 * criterion 1 satisfied.
 */
object TileSizeOptions {
    fun forPlugin(declaredSizes: Set<TileSize>): List<TileSize> =
        if (declaredSizes.isEmpty()) TileSize.entries else TileSize.entries.filter { it in declaredSizes }
}
