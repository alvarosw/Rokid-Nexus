package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.tile.TileSize

/**
 * Which sizes the layout editor offers for one plugin: its declared subset of the seven shapes
 * (`TILE_SIZES` from the plugin manifest, parsed into `PluginDescriptor.supportedTileSizes`), or
 * all seven when it declared none — the generic fallback tile renders any size with no plugin
 * cooperation required, so an absent declaration is not a restriction. Results follow
 * [TileSize.PICKER_ORDER]. A picked-outside-declaration size is never offered as supported by the
 * editor, so this alone is what keeps acceptance criterion 1 satisfied.
 */
object TileSizeOptions {
    fun forPlugin(declaredSizes: Set<TileSize>): List<TileSize> =
        if (declaredSizes.isEmpty()) TileSize.PICKER_ORDER else TileSize.PICKER_ORDER.filter { it in declaredSizes }
}
