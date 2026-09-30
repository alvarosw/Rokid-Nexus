package com.anezium.rokidbus.glasses.hud

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.FallbackTileView
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.glasses.LiveTileView
import com.anezium.rokidbus.shared.tile.TileGridPacker
import com.anezium.rokidbus.shared.tile.TilePlacement
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.TileTone

/**
 * The grid rendering: four columns across the 448 px content width (unit 106 px, `space-2` gaps),
 * packed by [TileGridPacker] with the wearer's sizes. Tiles are kept by plugin id: a selection move
 * changes focus on two views, an entry-list change adds and removes tiles, and a tile-data change
 * swaps only that tile's content. The body shows as many whole grid rows as fit (5 on the 480x640 screen) and scrolls by whole rows so the
 * selected tile is always fully visible (a TALL or LARGE tile spans two).
 */
internal class GridHome(
    context: Context,
    private val iconLoader: (Context, GlassesHub.LauncherEntry) -> Drawable,
    private val sizes: (List<GlassesHub.LauncherEntry>) -> Map<String, TileSize?>,
    motion: HudMotionDriver,
) : HomeScreenView(context, motion) {
    private class Tile(
        var entry: GlassesHub.LauncherEntry,
        val size: TileSize,
        var live: HomeTile?,
        var view: View,
    ) {
        val item: HomeItemView get() = view as HomeItemView
    }

    private val tiles = LinkedHashMap<String, Tile>()
    private var placements: List<TilePlacement> = emptyList()
    private var totalRows = 0
    private var offsetRow = 0

    val offsetRowForTest: Int get() = offsetRow
    internal val visibleRowsForTest: Int get() = visibleRows

    override fun bindBody(prev: HomeViewModel?, model: HomeViewModel) {
        val entriesChanged = prev == null || prev.entries != model.entries
        if (entriesChanged) {
            relayout(model)
        } else if (prev.tileData != model.tileData) {
            (prev.tileData.keys + model.tileData.keys).forEach { id ->
                if (prev.tileData[id] != model.tileData[id]) refreshData(id, model)
            }
        }
        val focusedId = model.focusedId
        val openingId = (model.status as? HomeStatus.Opening)?.pluginId
        if (entriesChanged) {
            tiles.forEach { (id, tile) ->
                tile.item.setFocused(id == focusedId)
                tile.item.setOpening(id == openingId)
            }
        } else {
            if (prev.focusedId != focusedId) {
                prev.focusedId?.let { tiles[it]?.item?.setFocused(false, animateMoves) }
                focusedId?.let { tiles[it]?.item?.setFocused(true, animateMoves) }
            }
            val prevOpening = (prev.status as? HomeStatus.Opening)?.pluginId
            if (prevOpening != openingId) {
                prevOpening?.let { tiles[it]?.item?.setOpening(false) }
                openingId?.let { tiles[it]?.item?.setOpening(true) }
            }
        }
        assignCriticalRoles(model)
        if (entriesChanged || prev?.selectedId != model.selectedId) followSelection(model.selectedId)
    }

    /**
     * Only one tile per screen shows `critical`: the focused one if it is critical (focus wins
     * visually, its alert icon says critical), otherwise the first in packer order. Every other
     * critical tile reads as `WARN`.
     */
    private fun assignCriticalRoles(model: HomeViewModel) {
        val critical = placements.map { it.pluginId }.filter { tiles[it]?.live?.snapshot?.tone == TileTone.CRITICAL }
        val primary = critical.firstOrNull { it == model.focusedId } ?: critical.firstOrNull()
        tiles.forEach { (id, tile) ->
            (tile.view as? LiveTileView)?.setCriticalPrimary(id == primary || id !in critical)
        }
    }

    private fun relayout(model: HomeViewModel) {
        val entries = model.entries
        val sizeById = sizes(entries)
        placements = TileGridPacker.pack(
            entries.map { it.id to sizeById[it.id] },
            columns = COLUMNS,
        )
        val keep = entries.mapTo(HashSet()) { it.id }
        tiles.keys.filterNot { it in keep }.forEach { id -> strip.removeView(tiles.remove(id)?.view) }
        entries.forEachIndexed { index, entry ->
            val placement = placements[index]
            val data = model.tileData[entry.id]
            var tile = tiles[entry.id]
            if (tile == null || tile.size != placement.size || (data != null) != (tile.live != null)) {
                tile?.let { strip.removeView(it.view) }
                tile = createTile(entry, placement.size, data)
                tiles[entry.id] = tile
                strip.addView(tile.view)
            } else {
                if (tile.entry != entry) {
                    tile.entry = entry
                    (tile.view as? FallbackTileView)?.bind(entry, iconLoader)
                    (tile.view as? LiveTileView)?.bindEntry(entry, iconLoader)
                }
                if (tile.live != data && data != null) {
                    (tile.view as LiveTileView).bind(data.snapshot, data.stale)
                    tile.live = data
                }
            }
            tile.view.layoutParams = FrameLayout.LayoutParams(
                width(placement.size.cols),
                height(placement.size.rows),
            ).apply {
                leftMargin = placement.col * PITCH
                topMargin = placement.row * PITCH
            }
        }
        totalRows = placements.maxOfOrNull { it.row + it.size.rows } ?: 0
        val contentHeight = if (totalRows == 0) 0 else totalRows * PITCH - RokidHudTokens.SPACE_2
        strip.layoutParams = strip.layoutParams.apply { height = contentHeight }
        fitToContent(contentHeight)
    }

    private fun createTile(entry: GlassesHub.LauncherEntry, size: TileSize, data: HomeTile?): Tile {
        val view: View = if (data != null) {
            LiveTileView(context, size, motion).apply {
                bindEntry(entry, iconLoader)
                bind(data.snapshot, data.stale)
            }
        } else {
            FallbackTileView(context, size, motion).apply { bind(entry, iconLoader) }
        }
        return Tile(entry, size, data, view)
    }

    /** A tile's data changed: rebind a live tile in place, swap the view only when its kind flips. */
    private fun refreshData(id: String, model: HomeViewModel) {
        val tile = tiles[id] ?: return
        val data = model.tileData[id]
        if (data != null && tile.live != null) {
            (tile.view as LiveTileView).bind(data.snapshot, data.stale)
            tile.live = data
            return
        }
        val next = createTile(tile.entry, tile.size, data)
        next.view.layoutParams = tile.view.layoutParams
        val index = strip.indexOfChild(tile.view)
        strip.removeViewAt(index)
        strip.addView(next.view, index)
        next.item.setFocused(id == model.focusedId)
        next.item.setOpening((model.status as? HomeStatus.Opening)?.pluginId == id)
        tiles[id] = next
    }

    private val visibleRows: Int get() = ((bodyHeight + RokidHudTokens.SPACE_2) / PITCH).coerceAtLeast(1)

    override fun fitBody(available: Int): Int {
        // Two rows at least: a TALL or LARGE tile spans two and must be able to show whole.
        val rows = ((available + RokidHudTokens.SPACE_2) / PITCH).coerceAtLeast(MIN_ROWS)
        return rows * UNIT + (rows - 1) * RokidHudTokens.SPACE_2
    }

    override fun onBodyChanged(model: HomeViewModel) = followSelection(model.selectedId)

    private fun followSelection(selectedId: String?) {
        val placement = placements.firstOrNull { it.pluginId == selectedId }
        if (placement != null) {
            if (placement.row < offsetRow) offsetRow = placement.row
            val bottom = placement.row + placement.size.rows
            if (bottom > offsetRow + visibleRows) offsetRow = bottom - visibleRows
        }
        offsetRow = offsetRow.coerceIn(0, (totalRows - visibleRows).coerceAtLeast(0))
        scrollTo(offsetRow * PITCH)
        setPosition(offsetRow, visibleRows, totalRows)
    }

    override fun itemBounds(id: String): Rect? {
        val view = tiles[id]?.view ?: return null
        return boundsOf(view)
    }

    override fun itemView(id: String): View? = tiles[id]?.view

    override fun settleItems() = tiles.values.forEach { it.item.settleFocus() }

    internal fun placementsForTest(): List<TilePlacement> = placements

    internal fun tileViewForTest(id: String): View? = tiles[id]?.view

    internal fun isTileFocusedForTest(id: String): Boolean = tiles[id]?.item?.homeFocused == true

    internal fun tileIdsForTest(): List<String> = tiles.keys.toList()

    private fun width(cols: Int) = cols * UNIT + (cols - 1) * RokidHudTokens.SPACE_2

    private fun height(rows: Int) = rows * UNIT + (rows - 1) * RokidHudTokens.SPACE_2

    companion object {
        const val COLUMNS = TileGridPacker.DEFAULT_COLUMNS
        private const val MIN_ROWS = 2

        /** (448 - 3 x 8) / 4 = 106 px. */
        const val UNIT = (RokidHudTokens.CONTENT_WIDTH - (COLUMNS - 1) * RokidHudTokens.SPACE_2) / COLUMNS
        const val PITCH = UNIT + RokidHudTokens.SPACE_2
    }
}
