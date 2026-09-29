package com.anezium.rokidbus.glasses.hud

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.glasses.HudTopInset
import com.anezium.rokidbus.glasses.TileCache
import com.anezium.rokidbus.glasses.TileController
import com.anezium.rokidbus.glasses.TileLayoutStore
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * The launcher inside [HudHost]: one [HomeViewModel] drawn by [ListHome] or [GridHome]. It renders
 * what it is told and holds no selection of its own; the machine's selection is an id, and both
 * renderings diff the model by plugin id, so a selection move never rebuilds the tree (F-8) and a
 * tile-data write refreshes an open grid in place (F-10).
 *
 * A failure is shown until the next input, or for [FAILURE_MS], whichever comes first; the timer is
 * only cosmetic and never feeds back into the state machine.
 */
internal class HomeLayer(
    context: Context,
    private val iconLoader: (Context, GlassesHub.LauncherEntry) -> Drawable = GlassesHub::launcherDrawable,
    private val sizeSource: (List<GlassesHub.LauncherEntry>) -> Map<String, TileSize?> = { entries ->
        val stored = TileLayoutStore.getEntries(context).associate { it.pluginId to it.size }
        entries.associate { it.id to stored[it.id] }
    },
    private val tileSource: (String) -> HomeTile? = { id ->
        // Read once per open or per publish, never per selection move.
        if (!TileController.isActive) {
            null
        } else {
            TileCache.get(context, id)?.let { HomeTile(it.snapshot, TileCache.isStale(it, SystemClock.elapsedRealtime())) }
        }
    },
) : FrameLayout(context) {
    private var screen: HomeScreenView? = null
    private var model = HomeViewModel()
    private var topInsetPx = 0
    private var stopObservingTiles: (() -> Unit)? = null

    // Not View.postDelayed: that queues until the view is attached, and a hidden layer must still expire.
    private val handler = Handler(Looper.getMainLooper())

    private val expireFailure = Runnable {
        if (model.status is HomeStatus.Failed) apply(model.copy(status = HomeStatus.None))
    }

    val currentModel: HomeViewModel get() = model

    /** A new mode swaps the rendering; the same mode keeps it, so a re-show never rebuilds it. */
    fun show(mode: HomeMode, entries: List<GlassesHub.LauncherEntry>, selectedId: String?) {
        handler.removeCallbacks(expireFailure)
        val tileData = if (mode == HomeMode.GRID) loadTileData(entries, emptyMap()) else emptyMap()
        apply(HomeViewModel(entries, selectedId, mode, HomeStatus.None, tileData))
    }

    fun update(entries: List<GlassesHub.LauncherEntry>, selectedId: String?) {
        val tileData = if (model.mode == HomeMode.GRID) loadTileData(entries, model.tileData) else emptyMap()
        apply(model.copy(entries = entries, selectedId = selectedId, tileData = tileData))
    }

    /** A selection move is an input, so it also dismisses a failure. */
    fun select(selectedId: String?) {
        handler.removeCallbacks(expireFailure)
        val status = if (model.status is HomeStatus.Failed) HomeStatus.None else model.status
        apply(model.copy(selectedId = selectedId, status = status))
    }

    fun showOpening(pluginId: String) {
        handler.removeCallbacks(expireFailure)
        apply(model.copy(status = HomeStatus.Opening(pluginId)))
    }

    fun showStatus(text: String) {
        handler.removeCallbacks(expireFailure)
        apply(model.copy(status = HomeStatus.Failed(text)))
        handler.postDelayed(expireFailure, FAILURE_MS)
    }

    fun clearStatus() {
        handler.removeCallbacks(expireFailure)
        if (model.status != HomeStatus.None) apply(model.copy(status = HomeStatus.None))
    }

    /** Drops the rendered content so nothing stale is drawn when the layer is next shown. */
    fun clear() {
        handler.removeCallbacks(expireFailure)
        apply(HomeViewModel(mode = model.mode))
    }

    fun setHudTopInsetDp(value: Int) {
        topInsetPx = HudTopInset.toPx(context, value)
        screen?.setTopInsetPx(topInsetPx)
    }

    /** A tile snapshot was written: refresh that one tile if the grid is up. Main thread. */
    fun onTileChanged(pluginId: String) {
        if (model.mode != HomeMode.GRID || model.entries.none { it.id == pluginId }) return
        val data = tileSource(pluginId)
        val next = if (data == null) model.tileData - pluginId else model.tileData + (pluginId to data)
        if (next != model.tileData) apply(model.copy(tileData = next))
    }

    /** Where an item is, in this layer's coordinates and with the scroll offset applied. */
    fun itemBounds(pluginId: String): Rect? = screen?.itemBounds(pluginId)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopObservingTiles?.invoke()
        stopObservingTiles = TileCache.observe { id -> handler.post { onTileChanged(id) } }
    }

    override fun onDetachedFromWindow() {
        stopObservingTiles?.invoke()
        stopObservingTiles = null
        handler.removeCallbacks(expireFailure)
        super.onDetachedFromWindow()
    }

    private fun apply(next: HomeViewModel) {
        if (screen == null || next.mode != model.mode) swapScreen(next.mode)
        model = next
        screen?.bind(next)
    }

    private fun swapScreen(mode: HomeMode) {
        screen?.let(::removeView)
        val next: HomeScreenView = when (mode) {
            HomeMode.LIST -> ListHome(context, iconLoader)
            HomeMode.GRID -> GridHome(context, iconLoader, sizeSource)
        }
        next.setTopInsetPx(topInsetPx)
        addView(next, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        screen = next
    }

    private fun loadTileData(
        entries: List<GlassesHub.LauncherEntry>,
        known: Map<String, HomeTile>,
    ): Map<String, HomeTile> = buildMap {
        entries.forEach { entry ->
            val data = known[entry.id] ?: tileSource(entry.id)
            if (data != null) put(entry.id, data)
        }
    }

    internal fun screenForTest(): HomeScreenView? = screen

    companion object {
        const val FAILURE_MS = 4_000L
    }
}
