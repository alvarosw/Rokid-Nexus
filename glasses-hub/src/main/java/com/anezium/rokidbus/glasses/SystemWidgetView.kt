package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.hud.HomeChrome
import com.anezium.rokidbus.hudtiles.SystemWidgetInput
import com.anezium.rokidbus.hudtiles.SystemWidgetRenderer
import com.anezium.rokidbus.hudtiles.TileLayout
import com.anezium.rokidbus.shared.tile.SystemWidget
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * A system widget on the home grid, drawn by [SystemWidgetRenderer] inside a fallback tile's resting
 * chrome. It is not a launcher entry, so it has no focus, no opening loader and no alert mark.
 *
 * Data is read from [source] when the widget comes on screen, on every change [source] reports and
 * at [SystemWidgetRenderer.nextChangeAtElapsed]; observation and the scheduled redraw run only
 * while the widget is attached and visible, so a hidden home costs nothing.
 */
internal class SystemWidgetView(
    context: Context,
    val widget: SystemWidget,
    private val size: TileSize,
    private val icon: Drawable?,
    private val source: SystemWidgetSource,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : View(context) {
    private var tileLayout: TileLayout? = null
    private var input: SystemWidgetInput? = null
    private var onScreen = false
    private var attached = false
    private var stopObserving: (() -> Unit)? = null
    private val redraw = Runnable { relayout() }

    init {
        background = HomeChrome.blended(0f, RokidHudTokens.LINE, RokidHudTokens.BORDER_DEFAULT)
        relayout()
    }

    private fun relayout() {
        val next = source.content(widget)?.let { SystemWidgetInput(widget.displayName, icon, it, clock()) }
        input = next
        tileLayout = next?.let { SystemWidgetRenderer.layout(it, size) }
        invalidate()
        scheduleNextChange()
    }

    private fun scheduleNextChange() {
        removeCallbacks(redraw)
        if (stopObserving == null) return
        val at = input?.let(SystemWidgetRenderer::nextChangeAtElapsed) ?: return
        postDelayed(redraw, (at - clock()).coerceAtLeast(0L))
    }

    private fun syncObservation() {
        val live = attached && onScreen
        if (live && stopObserving == null) {
            stopObserving = source.observe(widget) { relayout() }
            relayout()
        } else if (!live && stopObserving != null) {
            stopObserving?.invoke()
            stopObserving = null
            removeCallbacks(redraw)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        onScreen = isVisible
        syncObservation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        syncObservation()
    }

    override fun onDetachedFromWindow() {
        attached = false
        syncObservation()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        tileLayout?.draw(canvas)
    }

    internal val layoutForTest: TileLayout? get() = tileLayout

    internal val observingForTest: Boolean get() = stopObserving != null
}
