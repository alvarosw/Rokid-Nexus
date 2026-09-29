package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.hud.FocusTransition
import com.anezium.rokidbus.glasses.hud.HomeChrome
import com.anezium.rokidbus.glasses.hud.HomeItemView
import com.anezium.rokidbus.glasses.hud.HudIconView
import com.anezium.rokidbus.glasses.hud.HudLoaderView
import com.anezium.rokidbus.glasses.hud.HudMotionDriver
import com.anezium.rokidbus.glasses.hud.HudType
import com.anezium.rokidbus.glasses.hud.TileHeaderView
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * Renders a plugin's published [TileSnapshot], per-size, per `docs/grid-hud-roadmap/
 * 03-delivery-3-tile-data-pipeline.md`. Only ever shown once a snapshot exists (cached or fresh);
 * [FallbackTileView] stays the permanent no-data/no-adoption path.
 *
 * Every live tile carries its plugin's identity in the top-left corner, exactly like a fallback
 * tile: the 16 px icon over the `label`-styled uppercase name. The live value is the content below.
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
 */
internal class LiveTileView(
    context: Context,
    private val size: TileSize,
    motion: HudMotionDriver? = null,
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

    /** Whether this is the one tile allowed the full `critical` treatment on the screen. */
    var criticalPrimary = true
        private set

    override val homeFocused: Boolean get() = focused
    override val homeOpening: Boolean get() = opening

    private val header = TileHeaderView(context, nameLines = if (size.rows == 1) 1 else 2)
    private val titleView = HudType.body(TextView(context), RokidHudTokens.TEXT_PRIMARY).apply { maxLines = 2 }
    private val dataValueView = TextView(context).apply {
        setTextColor(RokidHudTokens.TEXT_PRIMARY)
        typeface = RokidHudTokens.dataTypeface()
        RokidHudTokens.applyTextSize(this, RokidHudTokens.DATA_TEXT_SIZE)
        maxLines = 1
    }
    private val unitView = HudType.mono(TextView(context), RokidHudTokens.TEXT_SECONDARY)
    private val subtitleView = HudType.body(TextView(context), RokidHudTokens.TEXT_SECONDARY).apply {
        RokidHudTokens.applyTextSize(this, RokidHudTokens.LABEL_TEXT_SIZE)
        maxLines = 2
    }
    private val badgeView = TextView(context).apply {
        setTextColor(RokidHudTokens.TEXT_PRIMARY)
        typeface = RokidHudTokens.dataTypeface()
        RokidHudTokens.applyTextSize(this, RokidHudTokens.LABEL_TEXT_SIZE)
        maxLines = 1
    }
    private val rowsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val loader = LoaderView(context)
    private val openingLoader = HudLoaderView(context).apply { visibility = GONE }
    internal val alertMark = HudIconView(context, HudIconView.Kind.ALERT).apply { visibility = GONE }

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }

    private val focusTransition = FocusTransition(motion) { amount ->
        focusAmount = amount
        header.setFocusAmount(amount)
        if (bound) applyChrome()
    }

    init {
        content.addView(titleView)
        content.addView(subtitleView)
        content.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(dataValueView)
                addView(unitView)
            },
        )
        content.addView(badgeView)
        content.addView(rowsContainer)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        header.setFocusAmount(0f)
        showLoading(null)
    }

    /** The plugin this tile stands for; drawn top-left whether or not a snapshot exists yet. */
    fun bindEntry(
        entry: GlassesHub.LauncherEntry,
        iconLoader: (Context, GlassesHub.LauncherEntry) -> Drawable = GlassesHub::launcherDrawable,
    ) = header.bind(entry, iconLoader)

    /** No snapshot yet at all — the mandated in-flight state, never a blank view. */
    fun showLoading(progress: Float?) {
        bound = false
        content.visibility = INVISIBLE
        loader.visibility = VISIBLE
        loader.setProgress(progress)
        alertMark.visibility = GONE
        background = null
    }

    fun bind(snapshot: TileSnapshot, stale: Boolean) {
        bound = true
        loader.visibility = INVISIBLE
        content.visibility = VISIBLE

        val numericValue = snapshot.title.toDoubleOrNull()
        if (numericValue != null) {
            titleView.visibility = GONE
            dataValueView.visibility = VISIBLE
            dataValueView.text = snapshot.title
            unitView.visibility = if (snapshot.unit.isNotEmpty()) VISIBLE else GONE
            unitView.text = snapshot.unit
        } else {
            titleView.visibility = VISIBLE
            titleView.text = snapshot.title
            dataValueView.visibility = GONE
            unitView.visibility = GONE
        }

        subtitleView.visibility = if (size != TileSize.SMALL && snapshot.subtitle.isNotEmpty()) VISIBLE else GONE
        subtitleView.text = snapshot.subtitle
        badgeView.visibility = if (snapshot.badge.isNotEmpty()) VISIBLE else GONE
        badgeView.text = snapshot.badge

        rowsContainer.removeAllViews()
        if (size == TileSize.LARGE && snapshot.rows.isNotEmpty()) {
            // ListItem's hard cap: at most 3-4 rows shown simultaneously.
            snapshot.rows.take(4).forEach { row ->
                rowsContainer.addView(
                    HudType.body(TextView(context), RokidHudTokens.TEXT_PRIMARY).apply {
                        RokidHudTokens.applyTextSize(this, RokidHudTokens.LABEL_TEXT_SIZE)
                        text = row
                    },
                )
            }
        }

        tone = snapshot.tone
        content.alpha = if (stale) STALE_ALPHA else 1f
        if (tone != TileTone.CRITICAL) {
            criticalBlink?.cancel()
            criticalBlink = null
            blinkedThisEpisode = false
        }
        applyChrome()
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
        if (bound) applyChrome()
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
        val hidden = listOf<View>(loader, openingLoader)
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
        val text = HomeChrome.intensity(focusAmount, RokidHudTokens.TEXT_PRIMARY)
        titleView.setTextColor(text)
        dataValueView.setTextColor(text)
        background = HomeChrome.blended(
            focusAmount,
            restStroke = restStroke(shown),
            restWidth = RokidHudTokens.BORDER_DEFAULT,
            restDashed = dashed,
            focusDashed = dashed,
        )
        val alert = shown == TileTone.WARN || shown == TileTone.CRITICAL
        alertMark.visibility = if (alert) VISIBLE else GONE
        alertMark.setIntensity(if (shown == TileTone.CRITICAL) RokidHudTokens.CRITICAL else RokidHudTokens.TEXT_PRIMARY)
    }

    /** Resting border intensity per tone; the width is always 1 px, 2 px belongs to focus. */
    private fun restStroke(tone: TileTone): Int = when (tone) {
        TileTone.OK, TileTone.WARN, TileTone.CRITICAL -> RokidHudTokens.TEXT_PRIMARY
        TileTone.INFO, TileTone.OFF -> RokidHudTokens.TEXT_SECONDARY
    }

    internal val nameForTest: String get() = header.nameText

    internal val alertMarkVisibleForTest: Boolean get() = alertMark.visibility == VISIBLE

    private companion object {
        /** 0x60 / 0xFF, as the whole-view alpha of a stale tile used to be. */
        const val STALE_ALPHA = 0x60 / 255f
    }
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
