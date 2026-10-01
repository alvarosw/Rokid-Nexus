package com.anezium.rokidbus.phone

import com.anezium.rokidbus.client.ui.HudGridMetrics
import com.anezium.rokidbus.shared.tile.GridRect
import com.anezium.rokidbus.shared.tile.SystemWidgets
import com.anezium.rokidbus.shared.tile.TileGridLayout
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileLayoutEditorStateTest {
    private val pitch = HudGridMetrics.PITCH.toFloat()

    private fun tile(id: String) = EditorTile(id, id.replaceFirstChar { it.uppercase() }, TileSize.entries.toSet(), live = false)

    private fun editor(
        layout: Map<String, GridRect>,
        default: Map<String, GridRect> = layout,
    ) = TileLayoutEditorState(layout.keys.map(::tile), layout, default)

    private val three = linkedMapOf(
        "a" to GridRect(0, 0, 2, 1),
        "b" to GridRect(2, 0, 1, 1),
        "c" to GridRect(0, 1, 1, 1),
    )

    @Test
    fun `the first tile in reading order starts selected`() {
        val state = editor(linkedMapOf("z" to GridRect(2, 1, 1, 1), "y" to GridRect(1, 0, 1, 1), "x" to GridRect(0, 1, 1, 1)))
        assertEquals("y", state.selectedId)
    }

    @Test
    fun `a drag below the threshold only selects`() {
        val state = editor(three)
        state.dragStart("b", 2 * pitch + 10, 10f)
        assertFalse(state.dragMove(2 * pitch + 12, 12f, travelDp = 3f))
        assertFalse(state.isDragging)
        assertEquals("b", state.selectedId)
        assertEquals(three, state.layout)
    }

    @Test
    fun `dragging displaces the tile under the target into the nearest free spot`() {
        val state = editor(three)
        state.dragStart("c", 10f, pitch + 10)
        // Lift c and carry it onto a, which covers row 0 columns 0..1.
        assertTrue(state.dragMove(10f, 10f, travelDp = 40f))
        assertTrue(state.isDragging)
        val layout = state.layout
        assertEquals(GridRect(0, 0, 1, 1), layout["c"])
        assertFalse(layout.getValue("a").overlaps(layout.getValue("c")))
        assertEquals(setOf("a", "b", "c"), layout.keys)
        // The dragged tile's pixel position follows the pointer, not the cell.
        assertEquals(0f, state.drag!!.x, 0.01f)
        assertEquals(0f, state.drag!!.y, 0.01f)
        state.dragEnd()
        assertNull(state.drag)
        assertEquals(layout, state.layout)
    }

    @Test
    fun `the drag target is the rounded cell and is clamped inside the grid`() {
        val state = editor(three)
        state.dragStart("b", 2 * pitch, 0f)
        state.dragMove(50_000f, -500f, travelDp = 500f)
        assertEquals(TileGridLayout.COLUMNS - 1, state.drag!!.targetCol)
        assertEquals(0, state.drag!!.targetRow)
        state.dragMove(2 * pitch, 0.6f * pitch, travelDp = 500f)
        assertEquals(1, state.drag!!.targetRow)
    }

    @Test
    fun `cancelling a drag restores the layout it started from`() {
        val state = editor(three)
        state.dragStart("c", 10f, pitch + 10)
        state.dragMove(10f, 10f, travelDp = 40f)
        assertTrue(state.layout != three)
        state.dragCancel()
        assertEquals(three, state.layout)
        assertNull(state.drag)
    }

    @Test
    fun `rows count the drag's starting layout so the grid does not collapse mid-drag`() {
        val tall = linkedMapOf("a" to GridRect(0, 0, 1, 1), "b" to GridRect(0, 4, 1, 1))
        val state = editor(tall)
        assertEquals(6, state.gridRows())
        state.dragStart("b", 10f, 4 * pitch + 10)
        state.dragMove(10f, pitch + 10, travelDp = 100f)
        assertEquals(1, state.layout.getValue("b").row)
        assertEquals(6, state.gridRows())
        state.dragEnd()
        assertEquals(4, state.gridRows())
    }

    @Test
    fun `grid rows are used rows plus one within 4 and 8`() {
        assertEquals(4, editor(linkedMapOf("a" to GridRect(0, 0, 1, 1))).gridRows())
        assertEquals(8, editor(linkedMapOf("a" to GridRect(0, 6, 1, 2))).gridRows())
        assertEquals(6, editor(linkedMapOf("a" to GridRect(0, 4, 1, 1))).gridRows())
    }

    @Test
    fun `a tile resolved past the last editor row stays shown and can be dragged back in`() {
        val state = editor(linkedMapOf("a" to GridRect(0, 0, 1, 1), "late" to GridRect(0, 8, 1, 1)))
        assertEquals(9, state.gridRows())
        state.dragStart("late", 10f, 8 * pitch + 10)
        state.dragMove(10f, 2 * pitch + 10, travelDp = 100f)
        state.dragEnd()
        assertEquals(GridRect(0, 2, 1, 1), state.layout["late"])
        assertEquals(4, state.gridRows())
    }

    @Test
    fun `move by action shifts a tile and selects it, and refuses to leave the grid`() {
        val state = editor(three)
        assertTrue(state.moveBy("b", 1, 0))
        assertEquals(GridRect(3, 0, 1, 1), state.layout["b"])
        assertEquals("b", state.selectedId)
        assertFalse(state.moveBy("b", 1, 0))
        assertFalse(state.moveBy("a", -1, 0))
    }

    @Test
    fun `resize applies when there is room`() {
        val state = editor(three)
        state.select("c")
        assertTrue(state.resize(TileSize.LARGE))
        assertEquals(GridRect(0, 1, 2, 2), state.layout["c"])
        assertEquals("", state.message)
    }

    @Test
    fun `resize reports no room and leaves the layout alone`() {
        // Eight rows of four 1x1 tiles leave nothing to absorb a 3x3.
        val full = LinkedHashMap<String, GridRect>()
        for (row in 0 until TileGridLayout.MAX_ROWS) for (col in 0 until 4) full["t$row$col"] = GridRect(col, row, 1, 1)
        val state = editor(full)
        state.select("t00")
        assertFalse(state.resize(TileSize.JUMBO))
        assertEquals("No room for 3×3 — shrink another tile first.", state.message)
        assertEquals(full, state.layout)
        state.select("t01")
        assertEquals("", state.message)
    }

    @Test
    fun `reset returns to the default layout and selection`() {
        val state = editor(three, default = linkedMapOf("a" to GridRect(0, 0, 1, 1), "b" to GridRect(1, 0, 1, 1), "c" to GridRect(2, 0, 1, 1)))
        state.moveBy("b", 0, 1)
        state.select("c")
        state.reset()
        assertEquals(GridRect(1, 0, 1, 1), state.layout["b"])
        assertEquals("a", state.selectedId)
    }

    @Test
    fun `auto-pack closes holes in reading order`() {
        val holes = linkedMapOf("a" to GridRect(3, 2, 1, 1), "b" to GridRect(1, 5, 2, 1), "c" to GridRect(0, 7, 1, 1))
        val state = editor(holes)
        state.autoPack()
        assertEquals(GridRect(0, 0, 1, 1), state.layout["a"])
        assertEquals(GridRect(1, 0, 2, 1), state.layout["b"])
        assertEquals(GridRect(3, 0, 1, 1), state.layout["c"])
    }

    @Test
    fun `save order is reading order and includes the camera`() {
        val layout = linkedMapOf(
            "weather" to GridRect(2, 0, 2, 1),
            "camera" to GridRect(0, 1, 1, 1),
            "clock" to GridRect(0, 0, 2, 2),
        )
        val entries = editor(layout).toEntries()
        assertEquals(
            listOf(
                TileLayoutEntry("clock", TileSize.LARGE, 0, 0),
                TileLayoutEntry("weather", TileSize.WIDE, 2, 0),
                TileLayoutEntry("camera", TileSize.SMALL, 0, 1),
            ),
            entries,
        )
    }

    private val widgetTiles = SystemWidgets.all.map {
        EditorTile(it.id, it.displayName, it.supportedSizes.toSet(), live = false, widget = it)
    }

    @Test
    fun `loading keeps the stored widgets and saving writes them back in reading order`() {
        val stored = listOf(
            TileLayoutEntry("sys:clock", TileSize.WIDE, 0, 0),
            TileLayoutEntry("a", TileSize.SMALL, 2, 0),
            TileLayoutEntry("sys:status", TileSize.BANNER, 0, 1),
            TileLayoutEntry("sys:radar", TileSize.SMALL, 3, 1),
        )
        val state = TileLayoutEditorState.load(listOf(tile("a"), tile("b")) + widgetTiles, stored)
        assertEquals(
            listOf(
                TileLayoutEntry("sys:clock", TileSize.WIDE, 0, 0),
                TileLayoutEntry("a", TileSize.SMALL, 2, 0),
                TileLayoutEntry("b", TileSize.SMALL, 3, 0),
                TileLayoutEntry("sys:status", TileSize.BANNER, 0, 1),
            ),
            state.toEntries(),
        )
    }

    @Test
    fun `an unplaced widget cannot be selected and the default layout has no widgets`() {
        val state = TileLayoutEditorState.load(
            listOf(tile("a")) + widgetTiles,
            listOf(TileLayoutEntry("sys:clock", TileSize.SMALL, 1, 0), TileLayoutEntry("a", TileSize.SMALL, 0, 0)),
        )
        state.select("sys:status")
        assertEquals("a", state.selectedId)
        state.reset()
        assertEquals(setOf("a"), state.layout.keys)
        assertEquals(listOf(TileLayoutEntry("a", TileSize.SMALL, 0, 0)), state.toEntries())
    }

    private fun widgetEditor(layout: Map<String, GridRect>) =
        TileLayoutEditorState(layout.keys.filterNot { it.startsWith("sys:") }.map(::tile) + widgetTiles, layout, layout)

    @Test
    fun `adding a widget places it at its default size in the first free cell and selects it`() {
        val state = widgetEditor(three)
        assertEquals(listOf("sys:clock", "sys:status", "sys:weather"), state.unplacedWidgets().map { it.id })
        assertTrue(state.addWidget("sys:status"))
        assertEquals(GridRect(3, 0, 1, 1), state.layout["sys:status"])
        assertEquals("sys:status", state.selectedId)
        assertEquals(listOf("sys:clock", "sys:weather"), state.unplacedWidgets().map { it.id })
        // Weather defaults to 2x1: the first free cell that fits it is under the first row.
        assertTrue(state.addWidget("sys:weather"))
        assertEquals(GridRect(1, 1, 2, 1), state.layout["sys:weather"])
        // Placed once only, and a plugin is no widget.
        assertFalse(state.addWidget("sys:status"))
        assertFalse(state.addWidget("a"))
    }

    @Test
    fun `adding a widget to a full grid reports no room and changes nothing`() {
        val full = LinkedHashMap<String, GridRect>()
        for (row in 0 until TileGridLayout.MAX_ROWS) for (col in 0 until 4) full["t$row$col"] = GridRect(col, row, 1, 1)
        val state = widgetEditor(full)
        state.select("t11")
        assertFalse(state.addWidget("sys:clock"))
        assertEquals("No room for Clock at 1×1 — shrink or remove a tile first.", state.message)
        assertEquals(full, state.layout)
        assertEquals("t11", state.selectedId)
    }

    @Test
    fun `removing a widget frees its cell and moves the selection, and plugins cannot be removed`() {
        val state = widgetEditor(three + ("sys:clock" to GridRect(3, 0, 1, 1)))
        state.select("sys:clock")
        assertFalse(state.removeWidget("a"))
        assertTrue(state.removeWidget("sys:clock"))
        assertEquals(three, state.layout)
        assertEquals("a", state.selectedId)
        assertEquals(listOf("sys:clock", "sys:status", "sys:weather"), state.unplacedWidgets().map { it.id })
        assertFalse(state.removeWidget("sys:clock"))
    }

    @Test
    fun `a widget resizes only to the sizes it supports`() {
        val state = widgetEditor(linkedMapOf("sys:status" to GridRect(0, 0, 1, 1)))
        state.select("sys:status")
        assertFalse(state.resize(TileSize.LARGE))
        assertEquals(GridRect(0, 0, 1, 1), state.layout["sys:status"])
        assertTrue(state.resize(TileSize.BANNER))
        assertEquals(GridRect(0, 0, 3, 1), state.layout["sys:status"])
    }

    @Test
    fun `widgets drag and auto-pack like tiles, and reset drops them`() {
        val state = TileLayoutEditorState(
            listOf(tile("a")) + widgetTiles,
            linkedMapOf("a" to GridRect(0, 0, 1, 1), "sys:clock" to GridRect(2, 3, 2, 1)),
            linkedMapOf("a" to GridRect(0, 0, 1, 1)),
        )
        state.dragStart("sys:clock", 2 * pitch + 10, 3 * pitch + 10)
        state.dragMove(2 * pitch + 10, pitch + 10, travelDp = 100f)
        state.dragEnd()
        assertEquals(GridRect(2, 1, 2, 1), state.layout["sys:clock"])
        state.autoPack()
        assertEquals(GridRect(1, 0, 2, 1), state.layout["sys:clock"])
        assertEquals(
            listOf(TileLayoutEntry("a", TileSize.SMALL, 0, 0), TileLayoutEntry("sys:clock", TileSize.WIDE, 1, 0)),
            state.toEntries(),
        )
        state.reset()
        assertEquals(setOf("a"), state.layout.keys)
        assertEquals(listOf("sys:clock", "sys:status", "sys:weather"), state.unplacedWidgets().map { it.id })
    }

    @Test
    fun `visible rows prefer the glasses report, else the inset formula`() {
        assertEquals(5, TileLayoutEditorState.visibleRows(glassesReported = 5, topInsetDp = 40, autoPosition = false))
        assertEquals(HudGridMetrics.visibleRows(0), TileLayoutEditorState.visibleRows(0, 40, autoPosition = true))
        assertEquals(
            HudGridMetrics.visibleRows(60),
            TileLayoutEditorState.visibleRows(0, 40, autoPosition = false),
        )
    }
}
