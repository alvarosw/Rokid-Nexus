package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.HudFrameLayout
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.tile.TileGridPacker
import com.anezium.rokidbus.shared.tile.TilePlacement

/**
 * Grid-mode rendering of today's launcher entries: every plugin auto-packed at
 * [com.anezium.rokidbus.shared.tile.TileSize.SMALL] — Delivery 4 is what lets the wearer pick a
 * size and a manual layout instead, and Delivery 1 has no size-picker UI to feed anything else
 * into the packer. Selection, open, and close are entirely owned by [LauncherOverlayRenderer];
 * this view only renders whatever `(entries, selectedIndex)` it is given and never touches the
 * bus itself — exactly the same split [LauncherOverlayRenderer]'s existing list view already
 * uses, per §6 of the roadmap ("no new gesture, no new key handling code path").
 */
internal class GridLauncherView(context: Context) : HudFrameLayout(context), LauncherContentView {
    private val grid = TileGridContainer(context)
    private val emptyView = TextView(context).apply {
        setTextColor(RokidHudTokens.TEXT_SECONDARY)
        typeface = RokidHudTokens.bodyTypeface()
        textSize = RokidHudTokens.BODY_TEXT_SIZE_SP
        gravity = Gravity.CENTER
        text = "No phone plugins synced"
    }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(
            grid,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    init {
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(emptyView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun render(entries: List<GlassesHub.LauncherEntry>, selectedIndex: Int) {
        emptyView.visibility = if (entries.isEmpty()) VISIBLE else INVISIBLE
        scroll.visibility = if (entries.isEmpty()) INVISIBLE else VISIBLE
        grid.render(entries, selectedIndex)
    }

    override fun setHudTopInsetDp(value: Int) {
        applySafeAreaPadding(topInsetDp = HudTopInset.sanitize(value))
        requestLayout()
    }

    /** Test-only: the View hierarchy is exactly what a Robolectric test can assert against. */
    internal fun tileCountForTest(): Int = grid.childCount

    internal fun isTileFocusedForTest(index: Int): Boolean = grid.getChildAt(index)?.let { child ->
        (child as? FallbackTileView)?.isFocusedForTest
    } ?: false

    /** Test-only: see [FallbackTileView.bind] — avoids needing real Android resources loaded. */
    internal fun setIconLoaderForTest(loader: (Context, GlassesHub.LauncherEntry) -> Drawable) {
        grid.iconLoader = loader
    }

    companion object {
        const val COLUMNS = TileGridPacker.DEFAULT_COLUMNS
        const val TILE_UNIT_DP = 96
    }
}

/**
 * Absolute-positions [FallbackTileView] children per [TileGridPacker]'s `(col, row)` output —
 * plain child bounds rather than nested weighted rows, so a `WIDE`/`TALL`/`LARGE` span (2x1/1x2/
 * 2x2 tiles the wearer can pick starting in Delivery 4) lays out correctly without special-casing
 * each shape.
 */
private class TileGridContainer(context: Context) : FrameLayout(context) {
    private var placements: List<TilePlacement> = emptyList()
    var iconLoader: (Context, GlassesHub.LauncherEntry) -> Drawable = GlassesHub::launcherDrawable

    fun render(entries: List<GlassesHub.LauncherEntry>, selectedIndex: Int) {
        removeAllViews()
        placements = TileGridPacker.pack(entries.map { it.id to null }, columns = GridLauncherView.COLUMNS)
        val unit = RokidHudTokens.dp(context, GridLauncherView.TILE_UNIT_DP)
        val gap = RokidHudTokens.dp(context, RokidHudTokens.SPACE_2)
        entries.forEachIndexed { index, entry ->
            val placement = placements.getOrNull(index) ?: return@forEachIndexed
            val tile = FallbackTileView(context, placement.size)
            tile.bind(entry, iconLoader)
            val isSelected = index == selectedIndex
            tile.setSelected(selected = isSelected, focused = isSelected)
            val width = placement.size.cols * unit + (placement.size.cols - 1) * gap
            val height = placement.size.rows * unit + (placement.size.rows - 1) * gap
            addView(
                tile,
                LayoutParams(width, height).apply {
                    leftMargin = placement.col * (unit + gap)
                    topMargin = placement.row * (unit + gap)
                },
            )
            if (isSelected) {
                tile.post { tile.requestRectangleOnScreen(Rect(0, 0, tile.width, tile.height), true) }
            }
        }
    }
}
