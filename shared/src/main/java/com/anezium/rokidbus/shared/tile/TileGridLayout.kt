package com.anezium.rokidbus.shared.tile

import kotlin.math.abs

/** A tile's cell rectangle on the grid: top-left [col]/[row], extent [cols] x [rows]. */
data class GridRect(val col: Int, val row: Int, val cols: Int, val rows: Int) {
    fun overlaps(other: GridRect): Boolean =
        col < other.col + other.cols && other.col < col + cols &&
            row < other.row + other.rows && other.row < row + rows
}

/**
 * The free-placement layout math the phone editor and the glasses grid share: drag with
 * displacement, resize, auto-pack, and the glasses' resolution of a stored layout. Pure Kotlin,
 * deterministic, no Android dependency. Layouts are `id -> GridRect` maps; the iteration order of
 * a map never matters to a result except where a function says so.
 */
object TileGridLayout {
    const val COLUMNS = TileGridPacker.DEFAULT_COLUMNS

    /** The editor's row limit. [resolve] has no such bound: the glasses render whatever they get. */
    const val MAX_ROWS = 8

    /** True when [rect] is inside the editor's bounds and overlaps none of [placed]. */
    fun fits(rect: GridRect, placed: Collection<GridRect>): Boolean =
        inBounds(rect) && placed.none { rect.overlaps(it) }

    /**
     * Puts [id] at [rect]; every tile it covers moves to the nearest free spot that fits it,
     * displaced tiles being handled in the reading order of their old position. Null if [rect] is
     * out of bounds or a displaced tile has no room. [id] must be a key of [layout].
     */
    fun placeAt(layout: Map<String, GridRect>, id: String, rect: GridRect): Map<String, GridRect>? {
        if (!inBounds(rect)) return null
        val out = LinkedHashMap<String, GridRect>()
        out[id] = rect
        val displaced = mutableListOf<String>()
        layout.forEach { (otherId, other) ->
            if (otherId == id) return@forEach
            if (other.overlaps(rect)) displaced += otherId else out[otherId] = other
        }
        displaced.sortWith(compareBy({ layout.getValue(it).row }, { layout.getValue(it).col }))
        for (displacedId in displaced) {
            val old = layout.getValue(displacedId)
            var best: GridRect? = null
            var bestDistance = Double.MAX_VALUE
            for (row in 0..MAX_ROWS - old.rows) {
                for (col in 0..COLUMNS - old.cols) {
                    val candidate = GridRect(col, row, old.cols, old.rows)
                    if (!fits(candidate, out.values)) continue
                    val distance = abs(col - old.col) + abs(row - old.row) * 1.5 + row * 0.01
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = candidate
                    }
                }
            }
            out[displacedId] = best ?: return null
        }
        return out
    }

    /**
     * Re-places every tile first-fit in the reading order of its current position. Returns
     * [layout] unchanged if any tile does not fit.
     */
    fun packAll(layout: Map<String, GridRect>): Map<String, GridRect> {
        val ids = layout.keys.sortedWith(compareBy({ layout.getValue(it).row }, { layout.getValue(it).col }))
        val out = LinkedHashMap<String, GridRect>()
        for (id in ids) {
            val old = layout.getValue(id)
            val spot = firstFit(old.cols, old.rows, out.values, MAX_ROWS) ?: return layout
            out[id] = spot
        }
        return out
    }

    /**
     * Changes [id] to [size] in place, clamped back inside the grid, displacing what it now
     * covers. Null means "no room": the editor's "No room for W×H — shrink another tile first."
     */
    fun resize(layout: Map<String, GridRect>, id: String, size: TileSize): Map<String, GridRect>? {
        val current = layout[id] ?: return null
        val rect = GridRect(
            col = minOf(current.col, COLUMNS - size.cols),
            row = minOf(current.row, MAX_ROWS - size.rows),
            cols = size.cols,
            rows = size.rows,
        )
        return placeAt(layout, id, rect)
    }

    /**
     * The glasses' placement. Stored entries for ids present in [entries] are pinned in stored
     * order when their rect is inside the columns (no upper row bound) and overlaps no already
     * pinned tile; every other id (a new plugin, the camera, a rejected entry) is placed first-fit,
     * row-major, into the free cells in [entries] order. Size is the stored one, else the declared
     * one from [entries], else [TileSize.SMALL]. With no stored layout this equals
     * [TileGridPacker.pack]. The result is in [entries] order; see [readingOrder].
     */
    fun resolve(
        entries: List<Pair<String, TileSize?>>,
        stored: List<TileLayoutEntry>,
    ): List<TilePlacement> {
        val known = entries.map { it.first }.toSet()
        val pinned = LinkedHashMap<String, TilePlacement>()
        stored.forEach { entry ->
            if (entry.pluginId !in known || entry.pluginId in pinned) return@forEach
            val rect = GridRect(entry.col, entry.row, entry.size.cols, entry.size.rows)
            val inColumns = rect.col >= 0 && rect.row >= 0 && rect.col + rect.cols <= COLUMNS
            if (inColumns && pinned.values.none { rect.overlaps(it.rect()) }) {
                pinned[entry.pluginId] = TilePlacement(entry.pluginId, entry.size, entry.col, entry.row)
            }
        }
        val occupied = pinned.values.mapTo(ArrayList()) { it.rect() }
        return entries.map { (id, declared) ->
            pinned[id] ?: run {
                val size = stored.firstOrNull { it.pluginId == id }?.size ?: declared ?: TileSize.SMALL
                val spot = firstFit(size.cols, size.rows, occupied, Int.MAX_VALUE)
                    ?: error("unbounded first-fit always finds a spot")
                occupied += spot
                TilePlacement(id, size, spot.col, spot.row)
            }
        }
    }

    /**
     * [resolve] with the system widgets [stored] places counted as present: the glasses' placement.
     * Widgets follow [entries] in stored order at their default size when their stored cell is
     * rejected. A `sys:` id this build does not know has no widget and is dropped like an
     * uninstalled plugin, which is also what a hub that predates widgets does with every one.
     */
    fun resolveWithWidgets(
        entries: List<Pair<String, TileSize?>>,
        stored: List<TileLayoutEntry>,
    ): List<TilePlacement> {
        val ids = entries.mapTo(HashSet()) { it.first }
        val widgets = SystemWidgets.placedIn(stored).filter { it.id !in ids }
        return resolve(entries + widgets.map { it.id to it.defaultSize }, stored)
    }

    /** Selection and list order: by top-left corner, row then column. */
    fun readingOrder(placements: List<TilePlacement>): List<TilePlacement> =
        placements.sortedWith(compareBy({ it.row }, { it.col }))

    private fun inBounds(rect: GridRect): Boolean =
        rect.col >= 0 && rect.row >= 0 && rect.col + rect.cols <= COLUMNS && rect.row + rect.rows <= MAX_ROWS

    /** First free spot scanning row-major, rows below [rowLimit] excluded. */
    private fun firstFit(cols: Int, rows: Int, occupied: Collection<GridRect>, rowLimit: Int): GridRect? {
        var row = 0
        while (rowLimit == Int.MAX_VALUE || row + rows <= rowLimit) {
            for (col in 0..COLUMNS - cols) {
                val candidate = GridRect(col, row, cols, rows)
                if (occupied.none { candidate.overlaps(it) }) return candidate
            }
            row++
        }
        return null
    }

    private fun TilePlacement.rect() = GridRect(col, row, size.cols, size.rows)
}
