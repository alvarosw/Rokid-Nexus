package com.anezium.rokidbus.glasses.hud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
import com.anezium.rokidbus.shared.tile.TileGridLayout
import com.anezium.rokidbus.shared.tile.TilePlacement

/**
 * The placements the hub resolved when it ordered [entries], so each tile is placed exactly once. If
 * the hub's last resolution covers a different set of ids (a race, a test with its own entries), the
 * entries are resolved from the stored layout instead.
 */
internal fun resolvedPlacements(context: Context, entries: List<GlassesHub.LauncherEntry>): List<TilePlacement> {
    val byId = GlassesHub.launcherPlacements().associateBy { it.pluginId }
    if (byId.size == entries.size && entries.all { it.id in byId }) return entries.map { byId.getValue(it.id) }
    return TileGridLayout.resolve(entries.map { it.id to null }, TileLayoutStore.getEntries(context))
}

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
    private val placementSource: (List<GlassesHub.LauncherEntry>) -> List<TilePlacement> = { entries ->
        resolvedPlacements(context, entries)
    },
    private val tileSource: (String) -> HomeTile? = { id ->
        // Read once per open or per publish, never per selection move.
        if (!TileController.isActive) {
            null
        } else {
            TileCache.get(context, id)?.let {
                HomeTile(it.snapshot, TileCache.isStale(it, SystemClock.elapsedRealtime()), it.receivedAtElapsedRealtime)
            }
        }
    },
    internal val motion: HudMotionDriver = HudMotionDriver.forContext(context),
) : FrameLayout(context) {
    private var screen: HomeScreenView? = null
    private var model = HomeViewModel()
    var topInsetPx = 0
        private set
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
        val tileData = if (mode == HomeMode.GRID && isOnScreen) loadTileData(entries, emptyMap()) else emptyMap()
        apply(
            HomeViewModel(
                entries, selectedId, mode, HomeStatus.None, tileData,
                placements = placementsFor(mode, entries),
                noticeOwnsRing = model.noticeOwnsRing,
            ),
        )
    }

    fun update(entries: List<GlassesHub.LauncherEntry>, selectedId: String?) {
        val tileData =
            if (model.mode == HomeMode.GRID && isOnScreen) loadTileData(entries, model.tileData) else emptyMap()
        apply(
            model.copy(
                entries = entries,
                selectedId = selectedId,
                tileData = tileData,
                placements = placementsFor(model.mode, entries),
            ),
        )
    }

    /**
     * A selection move is an input, so it also dismisses a failure. It is the one change that
     * animates: the scroll offset and the focus ring move at `duration-default`.
     */
    fun select(selectedId: String?) {
        handler.removeCallbacks(expireFailure)
        val status = if (model.status is HomeStatus.Failed) HomeStatus.None else model.status
        apply(model.copy(selectedId = selectedId, status = status), animate = selectedId != model.selectedId)
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

    /** While a notice owns the ring the selection rests: the notice draws the screen's one focus frame. */
    fun setNoticeOwnsRing(owns: Boolean) {
        if (model.noticeOwnsRing == owns) return
        apply(model.copy(noticeOwnsRing = owns))
    }

    /** Drops the rendered content so nothing stale is drawn when the layer is next shown. */
    fun clear() {
        handler.removeCallbacks(expireFailure)
        apply(HomeViewModel(mode = model.mode, noticeOwnsRing = model.noticeOwnsRing))
    }

    fun setHudTopInsetDp(value: Int) {
        topInsetPx = HudTopInset.toPx(context, value)
        screen?.setTopInsetPx(topInsetPx)
    }

    /** A tile snapshot was written: refresh that one tile if the grid is up. Main thread. */
    fun onTileChanged(pluginId: String) {
        if (!isOnScreen || model.mode != HomeMode.GRID || model.entries.none { it.id == pluginId }) return
        val data = tileSource(pluginId)
        val next = if (data == null) model.tileData - pluginId else model.tileData + (pluginId to data)
        if (next != model.tileData) apply(model.copy(tileData = next))
    }

    /** Where an item is, in this layer's coordinates and with the scroll offset applied. */
    fun itemBounds(pluginId: String): Rect? = screen?.itemBounds(pluginId)

    /** Ends the scroll and focus animations on their end states; bounds read after it are final. */
    fun settleMotion() {
        screen?.settleMotion()
    }

    /** A bitmap of one item's content, for the morph panel to keep it while it grows. */
    fun snapshotItem(pluginId: String): Bitmap? = screen?.snapshotItem(pluginId)

    /**
     * The rect the morph panel covers: the home is not drawn inside it, so what a panel grows over
     * is hidden rather than seen through it (black is transparent on the optic).
     */
    fun setCover(rect: Rect?) {
        if (cover == rect) return
        cover = rect?.let(::Rect)
        invalidate()
    }

    private var cover: Rect? = null

    internal val coverForTest: Rect? get() = cover

    override fun dispatchDraw(canvas: Canvas) {
        val hole = cover
        if (hole == null) {
            super.dispatchDraw(canvas)
            return
        }
        val saved = canvas.save()
        canvas.clipOutRect(hole)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saved)
    }

    // Dimming the home is a plain alpha on children that never overlap: no offscreen layer.
    override fun hasOverlappingRendering(): Boolean = false

    /**
     * Live tile data is bound only while the layer is visible. A tile binds its critical blink
     * once, so a snapshot bound under an app or a hidden host would spend the blink where nobody
     * sees it; the tiles catch up from the cache when the layer is next shown.
     */
    private val isOnScreen: Boolean get() = visibility == VISIBLE

    override fun setVisibility(visibility: Int) {
        val was = this.visibility
        super.setVisibility(visibility)
        if (this.visibility == was) return
        syncTileObservation()
        if (isOnScreen) refreshTileData()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncTileObservation()
    }

    override fun onDetachedFromWindow() {
        stopTileObservation()
        handler.removeCallbacks(expireFailure)
        super.onDetachedFromWindow()
    }

    private fun syncTileObservation() {
        if (!isOnScreen) {
            stopTileObservation()
        } else if (stopObservingTiles == null && isAttachedToWindow) {
            stopObservingTiles = TileCache.observe { id -> handler.post { onTileChanged(id) } }
        }
    }

    private fun stopTileObservation() {
        stopObservingTiles?.invoke()
        stopObservingTiles = null
    }

    /** The layer came on screen: every tile shows what the cache holds now. */
    private fun refreshTileData() {
        if (model.mode != HomeMode.GRID) return
        val next = loadTileData(model.entries, emptyMap())
        if (next != model.tileData) apply(model.copy(tileData = next))
    }

    private fun apply(next: HomeViewModel, animate: Boolean = false) {
        if (screen == null || next.mode != model.mode) swapScreen(next.mode)
        model = next
        screen?.bind(next, animate)
    }

    private fun swapScreen(mode: HomeMode) {
        screen?.let(::removeView)
        val next: HomeScreenView = when (mode) {
            HomeMode.LIST -> ListHome(context, iconLoader, motion)
            HomeMode.GRID -> GridHome(context, iconLoader, motion)
        }
        next.setTopInsetPx(topInsetPx)
        addView(next, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        screen = next
    }

    private fun placementsFor(mode: HomeMode, entries: List<GlassesHub.LauncherEntry>): List<TilePlacement> =
        if (mode == HomeMode.GRID) placementSource(entries) else emptyList()

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
