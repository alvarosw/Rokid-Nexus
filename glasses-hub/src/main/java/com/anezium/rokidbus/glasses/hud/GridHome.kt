package com.anezium.rokidbus.glasses.hud

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import com.anezium.rokidbus.client.ui.HudGridMetrics
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.FallbackTileView
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.glasses.LiveTileView
import com.anezium.rokidbus.glasses.SystemWidgetSource
import com.anezium.rokidbus.glasses.SystemWidgetView
import com.anezium.rokidbus.shared.tile.SystemWidgets
import com.anezium.rokidbus.shared.tile.TileGridLayout
import com.anezium.rokidbus.shared.tile.TilePlacement
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.TileTone

/**
 * The grid rendering: four columns across the 448 px content width (unit 106 px, `space-2` gaps),
 * each tile at the position the wearer's layout gives it; cells no tile covers stay empty. Tiles are kept by plugin id: a selection move
 * changes focus on two views, an entry-list change adds and removes tiles, and a tile-data change
 * swaps only that tile's content. The body shows as many whole grid rows as fit (5 on the 480x640 screen) and scrolls by whole rows so the
 * selected tile is always fully visible (a tall tile spans up to three rows).
 *
 * System widgets are placed and drawn like tiles but are not entries: the ring never stops on them.
 * Instead the ring's ends scroll toward the content's ends, so a widget below the last entry or above
 * the first one still comes into view.
 */
internal class GridHome(
    context: Context,
    private val iconLoader: (Context, GlassesHub.LauncherEntry) -> Drawable,
    motion: HudMotionDriver,
    private val widgetSource: SystemWidgetSource,
) : HomeScreenView(context, motion) {
    private class Tile(
        var entry: GlassesHub.LauncherEntry,
        val size: TileSize,
        var live: HomeTile?,
        var view: View,
    ) {
        val item: HomeItemView get() = view as HomeItemView
    }

    private class Widget(val size: TileSize, val view: SystemWidgetView)

    private val tiles = LinkedHashMap<String, Tile>()
    private val widgets = LinkedHashMap<String, Widget>()
    private var placements: List<TilePlacement> = emptyList()
    private var firstEntryId: String? = null
    private var lastEntryId: String? = null
    private var totalRows = 0
    private var offsetRow = 0

    val offsetRowForTest: Int get() = offsetRow
    internal val visibleRowsForTest: Int get() = visibleRows

    override fun bindBody(prev: HomeViewModel?, model: HomeViewModel) {
        val entriesChanged = prev == null || prev.entries != model.entries || prev.placements != model.placements ||
            prev.widgets != model.widgets
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
     * visually, its alert icon says critical), otherwise the first in placement order. Every other
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
        placements = model.placements
        val placementById = placements.associateBy { it.pluginId }
        val keep = entries.mapTo(HashSet()) { it.id }
        tiles.keys.filterNot { it in keep }.forEach { id -> strip.removeView(tiles.remove(id)?.view) }
        entries.forEach { entry ->
            val placement = placementById.getValue(entry.id)
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
                    (tile.view as LiveTileView).bind(data.snapshot, data.stale, data.receivedAtElapsed, data.artwork)
                    tile.live = data
                }
            }
            tile.view.layoutParams = cellParams(placement)
        }
        relayoutWidgets(model.widgets)
        firstEntryId = entries.firstOrNull()?.id
        lastEntryId = entries.lastOrNull()?.id
        totalRows = (placements + model.widgets).maxOfOrNull { it.row + it.size.rows } ?: 0
        val contentHeight = if (totalRows == 0) 0 else totalRows * PITCH - RokidHudTokens.SPACE_2
        strip.layoutParams = strip.layoutParams.apply { height = contentHeight }
        fitToContent(contentHeight)
    }

    private fun relayoutWidgets(placed: List<TilePlacement>) {
        val keep = placed.mapTo(HashSet()) { it.pluginId }
        widgets.keys.filterNot { it in keep }.forEach { id -> strip.removeView(widgets.remove(id)?.view) }
        placed.forEach { placement ->
            val widget = SystemWidgets.byId(placement.pluginId) ?: return@forEach
            var drawn = widgets[widget.id]
            if (drawn == null || drawn.size != placement.size) {
                drawn?.let { strip.removeView(it.view) }
                val icon = iconLoader(context, GlassesHub.LauncherEntry(widget.id, widget.displayName, widget.iconKey))
                drawn = Widget(placement.size, SystemWidgetView(context, widget, placement.size, icon, widgetSource))
                widgets[widget.id] = drawn
                strip.addView(drawn.view)
            }
            drawn.view.layoutParams = cellParams(placement)
        }
    }

    private fun cellParams(placement: TilePlacement) = FrameLayout.LayoutParams(
        width(placement.size.cols),
        height(placement.size.rows),
    ).apply {
        leftMargin = placement.col * PITCH
        topMargin = placement.row * PITCH
    }

    private fun createTile(entry: GlassesHub.LauncherEntry, size: TileSize, data: HomeTile?): Tile {
        val view: View = if (data != null) {
            LiveTileView(context, size, motion).apply {
                bindEntry(entry, iconLoader)
                bind(data.snapshot, data.stale, data.receivedAtElapsed, data.artwork)
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
            (tile.view as LiveTileView).bind(data.snapshot, data.stale, data.receivedAtElapsed, data.artwork)
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
        return HudGridMetrics.rowsHeight(HudGridMetrics.rowsFitting(available))
    }

    override fun onBodyChanged(model: HomeViewModel) = followSelection(model.selectedId)

    private fun followSelection(selectedId: String?) {
        val placement = placements.firstOrNull { it.pluginId == selectedId }
        if (placement != null) {
            if (placement.row < offsetRow) offsetRow = placement.row
            val bottom = placement.row + placement.size.rows
            if (bottom > offsetRow + visibleRows) offsetRow = bottom - visibleRows
            // As far toward the content's end (or start) as keeps the selection whole in view.
            if (selectedId == lastEntryId) {
                offsetRow = maxOf(offsetRow, placement.row)
            } else if (selectedId == firstEntryId) {
                offsetRow = minOf(offsetRow, bottom - visibleRows)
            }
        }
        offsetRow = offsetRow.coerceIn(0, (totalRows - visibleRows).coerceAtLeast(0))
        scrollTo(offsetRow * PITCH)
        setPosition(offsetRow, visibleRows, totalRows)
    }

    /**
     * A grid of widgets only has no selection to follow, so each ring step moves it one row toward
     * the content's end or start, stopping there: every widget can be read and none is focused.
     */
    override fun scrollRows(rows: Int) {
        if (tiles.isNotEmpty()) return
        val next = (offsetRow + rows).coerceIn(0, (totalRows - visibleRows).coerceAtLeast(0))
        if (next == offsetRow) return
        offsetRow = next
        scrollTo(offsetRow * PITCH, animate = true)
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

    internal fun widgetViewForTest(id: String): SystemWidgetView? = widgets[id]?.view

    private fun width(cols: Int) = cols * UNIT + (cols - 1) * RokidHudTokens.SPACE_2

    private fun height(rows: Int) = rows * UNIT + (rows - 1) * RokidHudTokens.SPACE_2

    companion object {
        const val COLUMNS = TileGridLayout.COLUMNS
        const val UNIT = HudGridMetrics.UNIT
        const val PITCH = HudGridMetrics.PITCH
    }
}
