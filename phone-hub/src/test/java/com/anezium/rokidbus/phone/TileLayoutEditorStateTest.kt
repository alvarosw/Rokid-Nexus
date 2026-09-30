package com.anezium.rokidbus.phone

import com.anezium.rokidbus.client.ui.HudGridMetrics
import com.anezium.rokidbus.shared.tile.GridRect
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
