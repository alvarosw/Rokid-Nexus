package com.anezium.rokidbus.phone

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.anezium.rokidbus.client.ui.HudGridMetrics
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.tile.GridRect
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import kotlin.math.abs
import kotlin.math.max

/** What one tile shows in the preview: its plugin glyph and the last live snapshot, if any. */
internal class TileVisual(val glyph: Drawable?, val snapshot: TileSnapshot?)

/**
 * The glasses' home grid, drawn where the phone can drag it. The canvas is the glasses content
 * area at [HudGridMetrics] geometry (448 px wide) scaled to the view's width, so a tile occupies
 * exactly the cells, and the fold falls exactly on the row, the glasses use. Every measurement in
 * [onDraw] is in canvas pixels.
 */
internal class TileLayoutCanvasView(context: Context) : View(context) {
    private var state: TileLayoutEditorState? = null
    private var visuals: Map<String, TileVisual> = emptyMap()
    private var visibleRows = HudGridMetrics.MIN_VISIBLE_ROWS

    /** Called after every change the user made here, so the screen around it can follow. */
    var onChanged: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val framePad = 11f * density
    private val frameRadius = 16f * density

    private var scale = 1f
    private var shownRows = 0f
    private var rowsAnimator: ValueAnimator? = null

    private val drawn = HashMap<String, RectF>()
    private var moveFrom = HashMap<String, RectF>()
    private var moveTo = HashMap<String, RectF>()
    private var moveProgress = 1f
    private val moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = MOVE_MS
        interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        addUpdateListener {
            moveProgress = it.animatedValue as Float
            invalidate()
        }
    }

    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f

    // Subpixel text keeps measured widths equal to drawn widths under the canvas scale.
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tmp = RectF()
    private val clip = Path()

    private val access = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val id = hitTest(x, y) ?: return HOST_ID
            return indexOf(id)
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            val current = state ?: return
            current.tiles.indices
                .sortedWith(compareBy({ current.layout[current.tiles[it].id]?.row }, { current.layout[current.tiles[it].id]?.col }))
                .forEach { ids += it }
        }

        override fun onPopulateNodeForVirtualView(index: Int, info: AccessibilityNodeInfoCompat) {
            val current = state
            val tile = current?.tiles?.getOrNull(index)
            val rect = tile?.let { current.layout[it.id] }
            if (tile == null || rect == null) {
                info.contentDescription = ""
                info.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            info.contentDescription = describe(tile.name, rect)
            info.isSelected = current.selectedId == tile.id
            info.isClickable = true
            info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
            MOVE_ACTIONS.forEach { (id, label, _) -> info.addAction(AccessibilityActionCompat(id, label)) }
            val r = viewRect(rect)
            info.setBoundsInParent(Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()))
        }

        override fun onPopulateEventForVirtualView(index: Int, event: android.view.accessibility.AccessibilityEvent) {
            val current = state ?: return
            val tile = current.tiles.getOrNull(index) ?: return
            val rect = current.layout[tile.id] ?: return
            event.contentDescription = describe(tile.name, rect)
        }

        override fun onPerformActionForVirtualView(index: Int, action: Int, arguments: Bundle?): Boolean {
            val current = state ?: return false
            val tile = current.tiles.getOrNull(index) ?: return false
            if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
                current.select(tile.id)
                changed()
                return true
            }
            val move = MOVE_ACTIONS.firstOrNull { it.first == action } ?: return false
            val moved = current.moveBy(tile.id, move.third.first, move.third.second)
            if (moved) changed()
            return moved
        }
    }

    init {
        ViewCompat.setAccessibilityDelegate(this, access)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(
        state: TileLayoutEditorState,
        visuals: Map<String, TileVisual>,
        visibleRows: Int,
    ) {
        this.state = state
        this.visuals = visuals
        this.visibleRows = visibleRows
        shownRows = state.gridRows().toFloat()
        drawn.clear()
        retarget()
        moveProgress = 1f
        requestLayout()
        invalidate()
        access.invalidateRoot()
    }

    /** The state changed outside a gesture (reset, auto-pack, resize): animate to it. */
    fun stateChanged() {
        retarget()
        syncRows()
        invalidate()
        access.invalidateRoot()
    }

    fun setVisibleRows(rows: Int) {
        if (rows == visibleRows) return
        visibleRows = rows
        invalidate()
    }

    override fun onDetachedFromWindow() {
        moveAnimator.cancel()
        rowsAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        access.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val canvasHeight = canvasHeightFor(shownRows)
        val height = (2 * framePad + canvasHeight * scaleFor(width)).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = scaleFor(w)
    }

    private fun scaleFor(width: Int) = (width - 2 * framePad) / CANVAS_WIDTH

    private fun canvasHeightFor(rows: Float) =
        max(0f, rows * HudGridMetrics.PITCH - HudGridMetrics.GAP)

    private fun syncRows() {
        val target = state?.gridRows()?.toFloat() ?: return
        if (target == shownRows) return
        rowsAnimator?.cancel()
        rowsAnimator = ValueAnimator.ofFloat(shownRows, target).apply {
            duration = MOVE_MS
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener {
                shownRows = it.animatedValue as Float
                requestLayout()
            }
            start()
        }
    }

    private fun cellRect(rect: GridRect): RectF {
        val x = rect.col * HudGridMetrics.PITCH.toFloat()
        val y = rect.row * HudGridMetrics.PITCH.toFloat()
        return RectF(x, y, x + extent(rect.cols), y + extent(rect.rows))
    }

    private fun extent(cells: Int) = (cells * HudGridMetrics.UNIT + (cells - 1) * HudGridMetrics.GAP).toFloat()

    /** Starts every tile moving from where it is drawn now to where the layout puts it. */
    private fun retarget() {
        val current = state ?: return
        val next = HashMap<String, RectF>()
        current.layout.forEach { (id, rect) -> next[id] = cellRect(rect) }
        val from = HashMap<String, RectF>()
        next.forEach { (id, rect) -> from[id] = RectF(drawn[id] ?: rect) }
        moveFrom = from
        moveTo = next
        if (from.any { (id, rect) -> rect != next[id] }) {
            moveProgress = 0f
            moveAnimator.cancel()
            moveAnimator.start()
        } else {
            moveProgress = 1f
        }
    }

    private fun rectOf(id: String): RectF? {
        val current = state ?: return null
        current.drag?.takeIf { it.id == id && it.moved }?.let { drag ->
            val cell = moveTo[id] ?: return null
            return RectF(drag.x, drag.y, drag.x + cell.width(), drag.y + cell.height())
        }
        val to = moveTo[id] ?: return null
        val from = moveFrom[id] ?: to
        val p = moveProgress
        return RectF(
            from.left + (to.left - from.left) * p,
            from.top + (to.top - from.top) * p,
            from.right + (to.right - from.right) * p,
            from.bottom + (to.bottom - from.bottom) * p,
        )
    }

    private fun toCanvasX(x: Float) = (x - framePad) / scale
    private fun toCanvasY(y: Float) = (y - framePad) / scale

    private fun viewRect(rect: GridRect): RectF {
        val cell = cellRect(rect)
        return RectF(
            framePad + cell.left * scale,
            framePad + cell.top * scale,
            framePad + cell.right * scale,
            framePad + cell.bottom * scale,
        )
    }

    private fun hitTest(x: Float, y: Float): String? {
        val current = state ?: return null
        val cx = toCanvasX(x)
        val cy = toCanvasY(y)
        return current.tiles.asReversed().firstOrNull { tile ->
            current.layout[tile.id]?.let { cellRect(it).contains(cx, cy) } == true
        }?.id
    }

    private fun indexOf(id: String) = state?.tiles?.indexOfFirst { it.id == id } ?: -1

    private fun describe(name: String, rect: GridRect) =
        "$name, ${rect.cols} by ${rect.rows}, column ${rect.col + 1}, row ${rect.row + 1}. " +
            "Drag or use actions to move."

    private fun changed() {
        retarget()
        syncRows()
        invalidate()
        access.invalidateRoot()
        onChanged?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val current = state ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val id = hitTest(event.x, event.y) ?: return false
                parent?.requestDisallowInterceptTouchEvent(true)
                pointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                current.dragStart(id, toCanvasX(event.x), toCanvasY(event.y))
                changed()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (current.drag == null) return false
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                val travelDp = (abs(x - downX) + abs(y - downY)) / density
                if (current.dragMove(toCanvasX(x), toCanvasY(y), travelDp)) changed()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (current.drag == null) return false
                current.dragEnd()
                changed()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (current.drag == null) return false
                current.dragCancel()
                changed()
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        val current = state ?: return
        drawFrame(canvas)
        canvas.save()
        canvas.translate(framePad, framePad)
        canvas.scale(scale, scale)
        val dragging = current.isDragging
        val rows = shownRows.toInt().coerceAtLeast(current.gridRows())
        drawSlots(canvas, rows, dragging)
        if (current.gridRows() > visibleRows) drawFold(canvas)
        current.drag?.takeIf { it.moved }?.let { drawGhost(canvas, it.targetCol, it.targetRow, current.layout.getValue(it.id)) }
        current.tiles.forEach { tile ->
            val rect = rectOf(tile.id) ?: return@forEach
            drawn[tile.id] = RectF(rect)
            if (current.drag?.takeIf { it.moved }?.id == tile.id) return@forEach
            drawTile(canvas, tile, rect, selected = current.selectedId == tile.id, lifted = false)
        }
        current.drag?.takeIf { it.moved }?.let { drag ->
            val tile = current.tiles.first { it.id == drag.id }
            rectOf(tile.id)?.let { drawTile(canvas, tile, it, selected = current.selectedId == tile.id, lifted = true) }
        }
        canvas.restore()
    }

    private fun drawFrame(canvas: Canvas) {
        val border = density
        fill.color = 0xFF000000.toInt()
        tmp.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(tmp, frameRadius, frameRadius, fill)
        stroke.pathEffect = null
        stroke.strokeWidth = border
        stroke.color = NexusUi.LINE
        tmp.inset(border / 2f, border / 2f)
        canvas.drawRoundRect(tmp, frameRadius - border / 2f, frameRadius - border / 2f, stroke)
    }

    private fun drawSlots(canvas: Canvas, rows: Int, dragging: Boolean) {
        stroke.strokeWidth = 1f
        stroke.color = if (dragging) RokidHudTokens.GREEN_24 else SLOT_REST
        stroke.pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f)
        for (row in 0 until rows) {
            for (col in 0 until HudGridMetrics.COLUMNS) {
                val x = col * HudGridMetrics.PITCH.toFloat()
                val y = row * HudGridMetrics.PITCH.toFloat()
                tmp.set(x + 0.5f, y + 0.5f, x + HudGridMetrics.UNIT - 0.5f, y + HudGridMetrics.UNIT - 0.5f)
                canvas.drawRoundRect(tmp, RADIUS, RADIUS, stroke)
            }
        }
        stroke.pathEffect = null
    }

    private fun drawFold(canvas: Canvas) {
        val y = (visibleRows * HudGridMetrics.PITCH - HudGridMetrics.GAP + FOLD_OFFSET).toFloat()
        stroke.strokeWidth = 1f
        stroke.color = RokidHudTokens.GREEN_48
        stroke.pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
        canvas.drawLine(-6f, y, CANVAS_WIDTH + 6f, y, stroke)
        stroke.pathEffect = null
        monoPaint(11f, 0.08f, RokidHudTokens.GREEN_48)
        val label = "SCROLL ↓"
        val w = text.measureText(label)
        val right = CANVAS_WIDTH
        fill.color = 0xFF000000.toInt()
        canvas.drawRect(right - w - 12f, y - 8f, right, y - 8f + 16f, fill)
        canvas.drawText(label, right - w - 6f, y - 8f + 12f, text)
    }

    private fun drawGhost(canvas: Canvas, col: Int, row: Int, size: GridRect) {
        val cell = cellRect(GridRect(col, row, size.cols, size.rows))
        fill.color = RokidHudTokens.GREEN_06
        canvas.drawRoundRect(cell, RADIUS, RADIUS, fill)
        stroke.strokeWidth = 2f
        stroke.color = RokidHudTokens.GREEN_100
        stroke.pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
        tmp.set(cell)
        tmp.inset(1f, 1f)
        canvas.drawRoundRect(tmp, RADIUS - 1f, RADIUS - 1f, stroke)
        stroke.pathEffect = null
    }

    private fun drawTile(canvas: Canvas, tile: EditorTile, rect: RectF, selected: Boolean, lifted: Boolean) {
        val visual = visuals[tile.id]
        val snapshot = visual?.snapshot
        val cells = state?.layout?.get(tile.id) ?: return
        val layer = if (lifted) canvas.saveLayerAlpha(rect, LIFTED_ALPHA) else canvas.save()

        val borderWidth = if (selected) 2f else 1f
        fill.color = when {
            lifted -> 0xFF010401.toInt()
            selected -> RokidHudTokens.GREEN_12
            else -> RokidHudTokens.GREEN_06
        }
        canvas.drawRoundRect(rect, RADIUS, RADIUS, fill)
        stroke.strokeWidth = borderWidth
        stroke.color = if (selected) RokidHudTokens.GREEN_100 else RokidHudTokens.GREEN_48
        stroke.pathEffect =
            if (snapshot?.tone == TileTone.WARN) DashPathEffect(floatArrayOf(4f, 3f), 0f) else null
        tmp.set(rect)
        tmp.inset(borderWidth / 2f, borderWidth / 2f)
        canvas.drawRoundRect(tmp, RADIUS - borderWidth / 2f, RADIUS - borderWidth / 2f, stroke)
        stroke.pathEffect = null

        clip.reset()
        clip.addRoundRect(rect, RADIUS, RADIUS, Path.Direction.CW)
        canvas.clipPath(clip)

        val inner = CONTENT_INSET
        val left = rect.left + inner
        val right = rect.right - inner
        val bottom = rect.bottom - inner
        var y = rect.top + inner

        // Eyebrow: plugin glyph and uppercase name.
        visual?.glyph?.let { glyph ->
            glyph.setBounds(0, 0, GLYPH, GLYPH)
            glyph.colorFilter = PorterDuffColorFilter(RokidHudTokens.GREEN_48, PorterDuff.Mode.SRC_IN)
            canvas.save()
            canvas.translate(left, y)
            glyph.draw(canvas)
            canvas.restore()
        }
        val nameLeft = left + if (visual?.glyph != null) GLYPH + 5f else 0f
        sansPaint(11f, 0.09f, RokidHudTokens.GREEN_48, bold = false)
        drawLine(canvas, tile.name.uppercase(), nameLeft, y, GLYPH.toFloat(), right - nameLeft)
        y += GLYPH + 5f

        if (snapshot != null) {
            y = drawSnapshot(canvas, cells, snapshot, selected, left, right, y)
            val progress = snapshot.progress
            if (progress != null) {
                val sizeWidth = drawSizeLabel(canvas, cells, right, bottom, measureOnly = true)
                val trackRight = right - sizeWidth - 6f
                val trackTop = bottom - 4f - 3f
                fill.color = RokidHudTokens.GREEN_24
                tmp.set(left, trackTop, trackRight, trackTop + 3f)
                canvas.drawRoundRect(tmp, 2f, 2f, fill)
                fill.color = RokidHudTokens.GREEN_72
                tmp.set(left, trackTop, left + (trackRight - left) * progress, trackTop + 3f)
                canvas.drawRoundRect(tmp, 2f, 2f, fill)
            }
        }
        drawSizeLabel(canvas, cells, right, bottom, measureOnly = false)
        canvas.restoreToCount(layer)
    }

    /** Title, subtitle, badge and rows as the glasses' live tile lays them out; returns the next y. */
    private fun drawSnapshot(
        canvas: Canvas,
        cells: GridRect,
        snapshot: TileSnapshot,
        selected: Boolean,
        left: Float,
        right: Float,
        top: Float,
    ): Float {
        val titleColor = if (selected) RokidHudTokens.GREEN_100 else RokidHudTokens.GREEN_72
        var y = top
        if (snapshot.title.toDoubleOrNull() != null) {
            monoPaint(26f, 0f, titleColor, bold = true)
            drawLine(canvas, snapshot.title, left, y, 28f, right - left)
            val valueWidth = text.measureText(snapshot.title)
            if (snapshot.unit.isNotEmpty()) {
                monoPaint(11f, 0f, RokidHudTokens.GREEN_48)
                drawLine(canvas, snapshot.unit, left + valueWidth + 4f, y + 14f, 14f, right - left - valueWidth - 4f)
            }
            y += 28f
        } else {
            sansPaint(14f, 0f, titleColor, bold = true)
            drawLine(canvas, snapshot.title, left, y, 17.5f, right - left)
            y += 17.5f
        }
        if ((cells.cols >= 2 || cells.rows >= 2) && snapshot.subtitle.isNotEmpty()) {
            sansPaint(12f, 0f, RokidHudTokens.GREEN_48)
            y += 2f
            drawLine(canvas, snapshot.subtitle, left, y, 15f, right - left)
            y += 15f
        }
        if (snapshot.badge.isNotEmpty()) {
            monoPaint(11f, 0f, RokidHudTokens.GREEN_72)
            y += 3f
            drawLine(canvas, snapshot.badge, left, y, 14f, right - left)
            y += 14f
        }
        val rowCap = when {
            cells.rows == 1 -> 0
            cells.rows == 2 -> 3
            else -> WidgetTileContract.MAX_ROWS
        }
        if (rowCap > 0 && snapshot.rows.isNotEmpty()) {
            y += 6f
            monoPaint(11f, 0f, RokidHudTokens.GREEN_48)
            snapshot.rows.take(rowCap).forEach { row ->
                drawLine(canvas, row, left, y, 14f, right - left)
                y += 14f + 3f
            }
        }
        return y
    }

    /** The "W×H" label at the tile's bottom-right; returns its width. */
    private fun drawSizeLabel(canvas: Canvas, cells: GridRect, right: Float, bottom: Float, measureOnly: Boolean): Float {
        val label = TileLayoutEditorState.sizeLabel(cells)
        monoPaint(10f, 0.04f, RokidHudTokens.GREEN_48)
        val width = text.measureText(label)
        if (!measureOnly) canvas.drawText(label, right - width, bottom - text.descent(), text)
        return width
    }

    /** One ellipsized line, vertically centred in a [lineHeight] band starting at [top]. */
    private fun drawLine(canvas: Canvas, value: String, x: Float, top: Float, lineHeight: Float, maxWidth: Float) {
        if (maxWidth <= 0f) return
        val shown = if (text.measureText(value) <= maxWidth) {
            value
        } else {
            val room = maxWidth - text.measureText(ELLIPSIS)
            val fitting = if (room > 0f) text.breakText(value, true, room, null) else 0
            value.substring(0, fitting).trimEnd() + ELLIPSIS
        }
        val metrics = text.fontMetrics
        val baseline = top + (lineHeight - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        canvas.drawText(shown, x, baseline, text)
    }

    private fun sansPaint(size: Float, tracking: Float, color: Int, bold: Boolean = false) {
        text.typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.SANS_SERIF
        text.textSize = size
        text.letterSpacing = tracking
        text.color = color
    }

    private fun monoPaint(size: Float, tracking: Float, color: Int, bold: Boolean = false) {
        text.typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        text.textSize = size
        text.letterSpacing = tracking
        text.color = color
    }

    private companion object {
        const val CANVAS_WIDTH = RokidHudTokens.CONTENT_WIDTH.toFloat()
        const val RADIUS = RokidHudTokens.RADIUS_PANEL.toFloat()
        const val MOVE_MS = 200L
        const val ELLIPSIS = "…"

        /** Border plus padding of a tile: 1 + 9 at rest, 2 + 8 selected, so content never shifts. */
        const val CONTENT_INSET = 10f
        const val GLYPH = 13
        const val LIFTED_ALPHA = 235
        const val SLOT_REST = 0x1A40FF5E

        /** The fold sits just inside the gap under the last visible row. */
        const val FOLD_OFFSET = 3
        const val HOST_ID = ExploreByTouchHelper.INVALID_ID

        val MOVE_ACTIONS = listOf(
            Triple(0x7F0A0001, "Move left", -1 to 0),
            Triple(0x7F0A0002, "Move right", 1 to 0),
            Triple(0x7F0A0003, "Move up", 0 to -1),
            Triple(0x7F0A0004, "Move down", 0 to 1),
        )
    }
}
