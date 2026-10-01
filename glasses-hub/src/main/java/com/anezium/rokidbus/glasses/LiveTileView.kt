package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.hud.FocusTransition
import com.anezium.rokidbus.glasses.hud.HomeChrome
import com.anezium.rokidbus.glasses.hud.HomeItemView
import com.anezium.rokidbus.glasses.hud.HudIconView
import com.anezium.rokidbus.glasses.hud.HudLoaderView
import com.anezium.rokidbus.glasses.hud.HudMotionDriver
import com.anezium.rokidbus.hudtiles.TileLayout
import com.anezium.rokidbus.hudtiles.TileOp
import com.anezium.rokidbus.hudtiles.TilePart
import com.anezium.rokidbus.hudtiles.TileRenderInput
import com.anezium.rokidbus.hudtiles.TileRenderer
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * Renders a plugin's published [TileSnapshot], per-size, per `docs/grid-hud-roadmap/
 * 03-delivery-3-tile-data-pipeline.md`. Only ever shown once a snapshot exists (cached or fresh);
 * [FallbackTileView] stays the permanent no-data/no-adoption path.
 *
 * The tile's interior (the plugin's icon and uppercase name top-left, the content below it, the
 * progress track at the foot) is drawn by [TileRenderer], the same renderer the phone's layout
 * editor uses. This view keeps the chrome around it: the border and focus fill, the loaders and
 * the alert mark.
 *
 * `tone` renders via the `Status` component: never a distinct color, and never a second 2 px frame,
 * because the 2 px `focus` frame is the one full-intensity frame the screen may have. `WARN` is the
 * alert icon at `text-primary` on a dashed 1 px border. `CRITICAL` is the alert icon at 100 % on a
 * solid 1 px `text-primary` border, blinking 3 times at `duration-default` and then steady. The
 * icon, not the border, carries "critical" because border thickness is the focus vocabulary; a
 * 2 px critical frame beside the focused one would put two rings on screen. Only one tile per
 * screen is [criticalPrimary]; the others show as `WARN` (the grid decides which). A focused
 * tile takes the ordinary focus chrome, and if it is critical the icon still says so.
 *
 * Focus (the home layer's one selection) is `surface-selected` plus a 2 px `focus` border, dashed
 * where the tile shows a warning, so a live tile is as visibly selected as a fallback one (F-10).
 * A stale snapshot dims its content only, never the border.
 *
 * A template whose drawing moves on its own (a playing position, an age) is redrawn once at
 * [TileRenderer.nextChangeAtElapsed], and only while the tile is attached and visible.
 */
internal class LiveTileView(
    context: Context,
    private val size: TileSize,
    motion: HudMotionDriver? = null,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : FrameLayout(context), HomeItemView {
    /** The blink-then-settle emphasis of a newly critical tile; null = static icon only. */
    var criticalEmphasis: ((LiveTileView) -> Unit)? = { view -> view.criticalBlink = CriticalBlink.animate(view.alertMark) }
    private var criticalBlink: CriticalBlink.Handle? = null
    private var blinkedThisEpisode = false
    private var tone = TileTone.OFF
    private var focused = false
    private var opening = false
    private var bound = false
    private var focusAmount = 0f

    private var name = ""
    private var icon: Drawable? = null
    private var snapshot: TileSnapshot? = null
    private var stale = false
    private var receivedAtElapsed = 0L
    private var artwork: Bitmap? = null

    /** The plugin's own glyphs by name, for a list item's glyph leading. */
    var glyphs: (String) -> Drawable? = { null }
    private var alertShown = false
    private var onScreen = false
    private var input: TileRenderInput? = null
    private var tileLayout: TileLayout = TileRenderer.layout(TileRenderInput(name = ""), size)
    private val redraw = Runnable { relayout() }

    /** Whether this is the one tile allowed the full `critical` treatment on the screen. */
    var criticalPrimary = true
        private set

    override val homeFocused: Boolean get() = focused
    override val homeOpening: Boolean get() = opening

    private val loader = LoaderView(context)
    private val openingLoader = HudLoaderView(context).apply { visibility = GONE }
    internal val alertMark = HudIconView(context, HudIconView.Kind.ALERT).apply { visibility = GONE }

    private val focusTransition = FocusTransition(motion) { amount ->
        focusAmount = amount
        if (bound) applyChrome()
        relayout()
    }

    init {
        addView(loader, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(
            openingLoader,
            LayoutParams(LayoutParams.MATCH_PARENT, HudLoaderView.TRACK_HEIGHT * 2, Gravity.BOTTOM),
        )
        addView(
            alertMark,
            LayoutParams(RokidHudTokens.ICON_SM, RokidHudTokens.ICON_SM, Gravity.TOP or Gravity.END),
        )
        val pad = RokidHudTokens.SPACE_2
        setPadding(pad, pad, pad, pad)
        showLoading(null)
    }

    /** The plugin this tile stands for; drawn top-left whether or not a snapshot exists yet. */
    fun bindEntry(
        entry: GlassesHub.LauncherEntry,
        iconLoader: (Context, GlassesHub.LauncherEntry) -> Drawable = GlassesHub::launcherDrawable,
    ) {
        icon = iconLoader(context, entry)
        name = entry.displayName
        relayout()
    }

    /** No snapshot yet at all — the mandated in-flight state, never a blank view. */
    fun showLoading(progress: Float?) {
        bound = false
        loader.visibility = VISIBLE
        loader.setProgress(progress)
        alertMark.visibility = GONE
        alertShown = false
        background = null
        relayout()
    }

    /** [artwork] is the decoded art of a music snapshot's `artworkKey`, when the hub holds it. */
    fun bind(snapshot: TileSnapshot, stale: Boolean, receivedAtElapsed: Long = clock(), artwork: Bitmap? = null) {
        bound = true
        loader.visibility = INVISIBLE
        this.snapshot = snapshot
        this.artwork = artwork
        this.stale = stale
        this.receivedAtElapsed = receivedAtElapsed
        tone = snapshot.tone
        if (tone != TileTone.CRITICAL) {
            criticalBlink?.cancel()
            criticalBlink = null
            blinkedThisEpisode = false
        }
        applyChrome()
        relayout()
        emphasizeIfCritical()
    }
    /**
     * Whether this tile is the screen's one `critical`. Losing it turns the tile into a plain
     * `WARN`, and a blink in flight is dropped.
     */
    fun setCriticalPrimary(primary: Boolean) {
        if (criticalPrimary == primary) return
        criticalPrimary = primary
        if (!primary) {
            criticalBlink?.cancel()
            criticalBlink = null
            blinkedThisEpisode = false
        }
        if (bound) {
            applyChrome()
            relayout()
        }
        emphasizeIfCritical()
    }

    private fun emphasizeIfCritical() {
        // A re-publish of the same critical tone must not restart the blink (F-7).
        if (!bound || tone != TileTone.CRITICAL || !criticalPrimary || blinkedThisEpisode) return
        blinkedThisEpisode = true
        criticalEmphasis?.invoke(this)
    }

    override fun setFocused(focused: Boolean, animate: Boolean) {
        if (this.focused == focused) return
        this.focused = focused
        focusTransition.set(focused, animate)
    }

    override fun settleFocus() = focusTransition.settle()

    override fun setOpening(opening: Boolean) {
        if (this.opening == opening) return
        this.opening = opening
        openingLoader.visibility = if (opening) VISIBLE else GONE
        openingLoader.setActive(opening)
    }

    override fun drawContent(canvas: Canvas) {
        // Alpha, not visibility: a visibility change would stop and restart the loader's animator.
        val hidden = listOf(loader, openingLoader)
        hidden.forEach { it.alpha = 0f }
        dispatchDraw(canvas)
        hidden.forEach { it.alpha = 1f }
    }

    /** What the tile shows as its status: `CRITICAL` only while it is the one; the rest read as `WARN`. */
    private val effectiveTone: TileTone
        get() = if (tone == TileTone.CRITICAL && !criticalPrimary) TileTone.WARN else tone

    private fun applyChrome() {
        val shown = effectiveTone
        val dashed = shown == TileTone.WARN
        background = HomeChrome.blended(
            focusAmount,
            restStroke = restStroke(shown),
            restWidth = RokidHudTokens.BORDER_DEFAULT,
            restDashed = dashed,
            focusDashed = dashed,
        )
        alertShown = shown == TileTone.WARN || shown == TileTone.CRITICAL
        alertMark.visibility = if (alertShown) VISIBLE else GONE
        alertMark.setIntensity(if (shown == TileTone.CRITICAL) RokidHudTokens.CRITICAL else RokidHudTokens.TEXT_PRIMARY)
    }

    /** Resting border intensity per tone; the width is always 1 px, 2 px belongs to focus. */
    private fun restStroke(tone: TileTone): Int = when (tone) {
        TileTone.OK, TileTone.WARN, TileTone.CRITICAL -> RokidHudTokens.TEXT_PRIMARY
        TileTone.INFO, TileTone.OFF -> RokidHudTokens.TEXT_SECONDARY
    }

    private fun relayout() {
        val next = TileRenderInput(
            name = name,
            icon = icon,
            content = if (bound) snapshot?.content else null,
            tone = tone,
            stale = stale,
            receivedAtElapsed = receivedAtElapsed,
            nowElapsed = clock(),
            focusAmount = focusAmount,
            // The alert icon sits at the top-right of the eyebrow row: the name gives way to it.
            headerEndInset = if (alertShown) RokidHudTokens.ICON_SM + RokidHudTokens.SPACE_1 else 0,
            artwork = artwork,
            glyph = glyphs,
        )
        input = next
        tileLayout = TileRenderer.layout(next, size)
        invalidate()
        scheduleNextChange()
    }

    private fun scheduleNextChange() {
        removeCallbacks(redraw)
        if (!isAttachedToWindow || !onScreen) return
        val at = input?.let { TileRenderer.nextChangeAtElapsed(it, size) } ?: return
        postDelayed(redraw, (at - clock()).coerceAtLeast(0L))
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        onScreen = isVisible
        if (isVisible) scheduleNextChange() else removeCallbacks(redraw)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleNextChange()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(redraw)
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        tileLayout.draw(canvas)
        super.dispatchDraw(canvas)
    }

    internal val nameForTest: String get() = name.uppercase()

    internal val alertMarkVisibleForTest: Boolean get() = alertMark.visibility == VISIBLE

    internal val loaderVisibleForTest: Boolean get() = loader.visibility == VISIBLE

    internal val layoutForTest: TileLayout get() = tileLayout

    private val track: TileOp.Track? get() = tileLayout.footer.filterIsInstance<TileOp.Track>().firstOrNull()

    internal val progressTrackVisibleForTest: Boolean get() = track != null

    internal val progressForTest: Float get() = track?.progress ?: 0f

    /** The row texts currently shown, top to bottom. */
    internal val rowTextsForTest: List<String> get() = tileLayout.texts(TilePart.ROW).map { it.text }
}

/** Minimal `Loader` per the design system: `scan`/`point` while pending, `progress` once known. */
private class LoaderView(context: Context) : TextView(context) {
    init {
        setTextColor(RokidHudTokens.TEXT_SECONDARY)
        typeface = RokidHudTokens.monoTypeface()
        RokidHudTokens.applyTextSize(this, RokidHudTokens.LABEL_TEXT_SIZE)
        gravity = Gravity.CENTER
    }

    fun setProgress(progress: Float?) {
        text = if (progress != null) "${(progress * 100).toInt()}%" else "…"
    }
}
