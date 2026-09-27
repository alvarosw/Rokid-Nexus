package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.ImageView
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
    private val expansionAnimator = TileExpansionAnimator(context)
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
        // MATCH_PARENT height, not WRAP_CONTENT: the TextView's own gravity=CENTER only centers
        // within its own bounds, so it needs the full frame height to actually center in it.
        addView(emptyView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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

    /**
     * Tweens the tile at [index] from its packed grid rect to this view's full safe-area rect,
     * dimming the other tiles meanwhile, then calls [onSettled] — the point at which
     * [LauncherOverlayRenderer] hands off to the real open sequence. Delivery 1's instant open is
     * unchanged if [index] is out of range or the view hasn't laid out yet: [onSettled] fires
     * immediately with no tween.
     */
    internal fun beginOpenTransition(index: Int, onSettled: () -> Unit) {
        grid.beginOpenTransition(hudRoot = this, index = index, animator = expansionAnimator, onSettled = onSettled)
    }

    /** The reverse of [beginOpenTransition]: tweens the full-safe-area rect back down to tile [index]. */
    internal fun beginCloseTransition(index: Int, onSettled: () -> Unit) {
        grid.beginCloseTransition(hudRoot = this, index = index, animator = expansionAnimator, onSettled = onSettled)
    }

    /** Kill switch: `false` falls back to Delivery 1's instant show/hide, with no tween at all. */
    internal var motionEnabledForTest: Boolean
        get() = expansionAnimator.enabled
        set(value) { expansionAnimator.enabled = value }

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
    private var ghost: View? = null
    private val blurOverlays = mutableMapOf<View, View>()

    fun render(entries: List<GlassesHub.LauncherEntry>, selectedIndex: Int) {
        removeAllViews()
        blurOverlays.clear()
        placements = TileGridPacker.pack(entries.map { it.id to null }, columns = GridLauncherView.COLUMNS)
        val unit = RokidHudTokens.dp(context, GridLauncherView.TILE_UNIT_DP)
        val gap = RokidHudTokens.dp(context, RokidHudTokens.SPACE_2)
        entries.forEachIndexed { index, entry ->
            val placement = placements.getOrNull(index) ?: return@forEachIndexed
            val cached = if (TileController.isActive) TileCache.get(context, entry.id) else null
            val tile: View = if (cached != null) {
                LiveTileView(context, placement.size).apply {
                    bind(cached.snapshot, TileCache.isStale(cached, SystemClock.elapsedRealtime()))
                }
            } else {
                FallbackTileView(context, placement.size).apply { bind(entry, iconLoader) }
            }
            val isSelected = index == selectedIndex
            if (tile is FallbackTileView) tile.setSelected(selected = isSelected, focused = isSelected)
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

    fun tileAt(index: Int): View? = getChildAt(index)

    fun beginOpenTransition(hudRoot: FrameLayout, index: Int, animator: TileExpansionAnimator, onSettled: () -> Unit) {
        val tile = tileAt(index)
        if (tile == null || tile.width == 0 || tile.height == 0) {
            onSettled()
            return
        }
        val tileRect = Rect(0, 0, tile.width, tile.height)
        hudRoot.offsetDescendantRectToMyCoords(tile, tileRect)
        val destRect = Rect(
            hudRoot.paddingLeft,
            hudRoot.paddingTop,
            hudRoot.width - hudRoot.paddingRight,
            hudRoot.height - hudRoot.paddingBottom,
        )

        dimSiblings(except = index)
        tile.alpha = 0f
        val ghostView = View(context).apply { background = tile.background }
        ghost = ghostView
        hudRoot.addView(
            ghostView,
            FrameLayout.LayoutParams(tileRect.width(), tileRect.height()).apply {
                leftMargin = tileRect.left
                topMargin = tileRect.top
            },
        )
        animator.expand(ghostView, tileRect, destRect, onSettled)
    }

    fun beginCloseTransition(hudRoot: FrameLayout, index: Int, animator: TileExpansionAnimator, onSettled: () -> Unit) {
        val tile = tileAt(index)
        if (tile == null || tile.width == 0 || tile.height == 0) {
            onSettled()
            return
        }
        val tileRect = Rect(0, 0, tile.width, tile.height)
        hudRoot.offsetDescendantRectToMyCoords(tile, tileRect)
        val fromRect = Rect(
            hudRoot.paddingLeft,
            hudRoot.paddingTop,
            hudRoot.width - hudRoot.paddingRight,
            hudRoot.height - hudRoot.paddingBottom,
        )

        val ghostView = ghost ?: View(context).also { hudRoot.addView(it, FrameLayout.LayoutParams(fromRect.width(), fromRect.height())) }
        ghost = ghostView
        animator.collapse(ghostView, fromRect, tileRect) {
            hudRoot.removeView(ghostView)
            ghost = null
            tile.alpha = 1f
            undimSiblings()
            onSettled()
        }
    }

    /** Snapshots each non-selected tile, blurs the snapshot (see [DownscaleBlur]), and cross-fades
     * to it in place of the live tile — the cheap blur-approximation the design calls for, rather
     * than a plain alpha fade of the crisp tile. */
    private fun dimSiblings(except: Int) {
        for (i in 0 until childCount) {
            if (i == except) continue
            val tile = getChildAt(i) ?: continue
            if (tile.width == 0 || tile.height == 0) continue
            val bitmap = Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888)
            tile.draw(Canvas(bitmap))
            val overlay = ImageView(context).apply {
                setImageBitmap(DownscaleBlur.blur(bitmap))
                alpha = 0f
            }
            blurOverlays[tile] = overlay
            addView(overlay, indexOfChild(tile) + 1, LayoutParams(tile.layoutParams as LayoutParams))
            tile.alpha = 0f
            overlay.animate().alpha(DIMMED_ALPHA).setDuration(RokidHudTokens.DURATION_STRUCTURAL_MS).start()
        }
    }

    private fun undimSiblings() {
        blurOverlays.forEach { (tile, overlay) ->
            tile.alpha = 1f
            removeView(overlay)
        }
        blurOverlays.clear()
    }

    companion object {
        // The design docs give a token for the border/fill treatment but not a numeric dim
        // opacity; this is a judgment call for "softened, not hidden."
        private const val DIMMED_ALPHA = 0.35f
    }
}
