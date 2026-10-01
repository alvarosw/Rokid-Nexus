package com.anezium.rokidbus.phone

import com.anezium.rokidbus.client.ui.HudGridMetrics
import com.anezium.rokidbus.shared.tile.GridRect
import com.anezium.rokidbus.shared.tile.SystemWidget
import com.anezium.rokidbus.shared.tile.TileGridLayout
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TilePlacement
import com.anezium.rokidbus.shared.tile.TileSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One tile the editor can place: a launchable plugin, the camera entry or a system widget. */
data class EditorTile(
    val id: String,
    val name: String,
    /** The sizes the picker offers as supported; every other size is shown disabled. */
    val sizes: Set<TileSize>,
    /** True when the plugin publishes live tiles (`widget_tile`), shown as the "LIVE" chip. */
    val live: Boolean,
    /** Set for a system widget, which is on the grid only while the layout has a cell for it. */
    val widget: SystemWidget? = null,
)

/** A tile being dragged: [x]/[y] is its top-left in glasses-canvas pixels, following the pointer. */
data class EditorDrag(
    val id: String,
    val startLayout: Map<String, GridRect>,
    val offsetX: Float,
    val offsetY: Float,
    val startX: Float,
    val startY: Float,
    val x: Float,
    val y: Float,
    val moved: Boolean,
    val targetCol: Int,
    val targetRow: Int,
)

/**
 * The tile-layout editor's state and rules, free of Android so the drag, resize and save behavior
 * is JVM-testable. Positions are cells of [TileGridLayout]; drag coordinates are pixels of the
 * glasses content canvas ([HudGridMetrics] geometry).
 *
 * [tiles] lists every system widget this build knows, placed or not; a widget is placed exactly
 * when [layout] has a cell for it, as on the glasses.
 */
class TileLayoutEditorState(
    val tiles: List<EditorTile>,
    initialLayout: Map<String, GridRect>,
    private val defaultLayout: Map<String, GridRect>,
) {
    private val tileById = tiles.associateBy { it.id }

    var layout: Map<String, GridRect> = initialLayout
        private set

    var selectedId: String? = firstInReadingOrder(initialLayout)
        private set

    /** The amber status line under the size chips; empty when there is nothing to say. */
    var message: String = ""
        private set

    var drag: EditorDrag? = null
        private set

    val isDragging: Boolean get() = drag?.moved == true

    fun select(id: String) {
        if (id !in tileById || id !in layout) return
        selectedId = id
        message = ""
    }

    fun clearSelection() {
        selectedId = null
        message = ""
    }

    fun dragStart(id: String, pointerX: Float, pointerY: Float) {
        val rect = layout[id] ?: return
        select(id)
        val x = rect.col * HudGridMetrics.PITCH.toFloat()
        val y = rect.row * HudGridMetrics.PITCH.toFloat()
        drag = EditorDrag(
            id = id,
            startLayout = layout,
            offsetX = pointerX - x,
            offsetY = pointerY - y,
            startX = pointerX,
            startY = pointerY,
            x = x,
            y = y,
            moved = false,
            targetCol = rect.col,
            targetRow = rect.row,
        )
    }

    /**
     * Follows the pointer; [travelDp] is how far it has moved from where it went down. Nothing
     * lifts until that exceeds [LIFT_THRESHOLD_DP]. Returns true when anything changed.
     */
    fun dragMove(pointerX: Float, pointerY: Float, travelDp: Float): Boolean {
        val current = drag ?: return false
        val moved = current.moved || travelDp > LIFT_THRESHOLD_DP
        if (!moved) return false
        val base = current.startLayout.getValue(current.id)
        val x = pointerX - current.offsetX
        val y = pointerY - current.offsetY
        val col = (x / HudGridMetrics.PITCH).roundToInt().coerceIn(0, TileGridLayout.COLUMNS - base.cols)
        val row = (y / HudGridMetrics.PITCH).roundToInt().coerceIn(0, TileGridLayout.MAX_ROWS - base.rows)
        var next = current.copy(x = x, y = y, moved = true)
        if (col != current.targetCol || row != current.targetRow || !current.moved) {
            val placed = TileGridLayout.placeAt(
                current.startLayout,
                current.id,
                GridRect(col, row, base.cols, base.rows),
            )
            if (placed != null) {
                layout = placed
                next = next.copy(targetCol = col, targetRow = row)
            }
        }
        drag = next
        return true
    }

    fun dragEnd() {
        drag = null
    }

    /** The system took the gesture: restore the layout the drag started from. */
    fun dragCancel() {
        val current = drag ?: return
        layout = current.startLayout
        drag = null
    }

    /** Moves [id] one cell; false when that would leave the grid or displace a tile with no room. */
    fun moveBy(id: String, dCol: Int, dRow: Int): Boolean {
        val rect = layout[id] ?: return false
        val placed = TileGridLayout.placeAt(layout, id, rect.copy(col = rect.col + dCol, row = rect.row + dRow))
            ?: return false
        layout = placed
        select(id)
        return true
    }

    /** Resizes the selected tile; on no room, leaves the layout alone and sets [message]. */
    fun resize(size: TileSize): Boolean {
        val id = selectedId ?: return false
        if (size !in tileById.getValue(id).sizes) return false
        val placed = TileGridLayout.resize(layout, id, size)
        if (placed == null) {
            message = "No room for ${sizeLabel(size)} — shrink another tile first."
            return false
        }
        layout = placed
        message = ""
        return true
    }

    /** The system widgets not on the grid, in catalog order: what "+ ADD WIDGET" offers. */
    fun unplacedWidgets(): List<EditorTile> = tiles.filter { it.widget != null && it.id !in layout }

    /**
     * Places the widget [id] at its default size in the first free cell, row-major, and selects it.
     * On no room, leaves the layout alone and sets [message].
     */
    fun addWidget(id: String): Boolean {
        val tile = tileById[id] ?: return false
        val widget = tile.widget ?: return false
        if (id in layout) return false
        val size = widget.defaultSize
        val spot = firstFree(size)
        if (spot == null) {
            message = "No room for ${tile.name} at ${sizeLabel(size)} — shrink or remove a tile first."
            return false
        }
        layout = LinkedHashMap(layout).apply { put(id, spot) }
        selectedId = id
        message = ""
        return true
    }

    /** Takes the widget [id] off the grid; plugins and the camera cannot be removed. */
    fun removeWidget(id: String): Boolean {
        if (tileById[id]?.widget == null || id !in layout) return false
        layout = layout - id
        if (selectedId == id) selectedId = firstInReadingOrder(layout)
        message = ""
        return true
    }

    private fun firstFree(size: TileSize): GridRect? {
        for (row in 0..TileGridLayout.MAX_ROWS - size.rows) {
            for (col in 0..TileGridLayout.COLUMNS - size.cols) {
                val rect = GridRect(col, row, size.cols, size.rows)
                if (TileGridLayout.fits(rect, layout.values)) return rect
            }
        }
        return null
    }

    fun reset() {
        layout = defaultLayout
        selectedId = firstInReadingOrder(defaultLayout)
        message = ""
        drag = null
    }

    fun autoPack() {
        layout = TileGridLayout.packAll(layout)
        message = ""
    }

    /** Every tile in reading order, ready to store and push. */
    fun toEntries(): List<TileLayoutEntry> =
        layout.entries
            .sortedWith(compareBy({ it.value.row }, { it.value.col }))
            .map { (id, rect) ->
                TileLayoutEntry(pluginId = id, size = sizeOf(rect), col = rect.col, row = rect.row)
            }

    /**
     * Rows the preview shows: one spare row below the lowest tile, 4..8, counting a drag's start.
     * A tile the glasses placed past the editor's last row (a plugin added to a full layout) still
     * gets its rows, so it is drawn and can be dragged back into the grid.
     */
    fun gridRows(): Int {
        var used = usedRows(layout)
        drag?.let { used = max(used, usedRows(it.startLayout)) }
        return min(max(TileGridLayout.MAX_ROWS, used), max(MIN_ROWS, used + 1))
    }

    fun usedRows(): Int = usedRows(layout)

    private fun usedRows(of: Map<String, GridRect>): Int = of.values.maxOfOrNull { it.row + it.rows } ?: 0

    companion object {
        const val LIFT_THRESHOLD_DP = 5f
        const val MIN_ROWS = 4

        /** The density of the glasses display, which converts the stored HUD inset from dp. */
        const val GLASSES_DENSITY = 1.5f

        fun sizeLabel(size: TileSize): String = "${size.cols}×${size.rows}"

        fun sizeLabel(rect: GridRect): String = "${rect.cols}×${rect.rows}"

        fun sizeOf(rect: GridRect): TileSize =
            TileSize.entries.first { it.cols == rect.cols && it.rows == rect.rows }

        fun layoutOf(placements: List<TilePlacement>): Map<String, GridRect> =
            placements.associate { it.pluginId to GridRect(it.col, it.row, it.size.cols, it.size.rows) }

        /**
         * The editor over [stored], placed as the glasses place it: the plugins and the camera are
         * resolved in [tiles] order with no declared sizes, and the system widgets [stored] has
         * entries for keep them, so saving never drops a widget. The default layout has no widgets.
         */
        fun load(tiles: List<EditorTile>, stored: List<TileLayoutEntry>): TileLayoutEditorState {
            val entries = tiles.filter { it.widget == null }.map { it.id to null }
            return TileLayoutEditorState(
                tiles,
                layoutOf(TileGridLayout.resolveWithWidgets(entries, stored)),
                layoutOf(TileGridLayout.resolve(entries, emptyList())),
            )
        }

        /**
         * Rows the glasses show before scrolling: what they last reported, else the same formula
         * from the phone's stored HUD inset (manual mode) or no inset (auto mode).
         */
        fun visibleRows(glassesReported: Int, topInsetDp: Int, autoPosition: Boolean): Int {
            if (glassesReported > 0) return glassesReported
            val insetPx = if (autoPosition) 0 else (topInsetDp * GLASSES_DENSITY).roundToInt()
            return HudGridMetrics.visibleRows(insetPx)
        }

        private fun firstInReadingOrder(layout: Map<String, GridRect>): String? =
            layout.entries.minWithOrNull(compareBy({ it.value.row }, { it.value.col }))?.key
    }
}
