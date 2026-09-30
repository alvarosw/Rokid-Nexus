package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import com.anezium.rokidbus.shared.tile.TileContentRules
import com.anezium.rokidbus.shared.tile.TileContentRules.TitleStyle
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * Renders a plugin's published [TileSnapshot], per-size, per `docs/grid-hud-roadmap/
 * 03-delivery-3-tile-data-pipeline.md`. Only ever shown once a snapshot exists (cached or fresh);
 * [FallbackTileView] stays the permanent no-data/no-adoption path.
 *
 * Every live tile carries its plugin's identity in the top-left corner, exactly like a fallback
 * tile: the 16 px icon beside the `label`-styled uppercase name. The live value is the content
 * below it, left-aligned and top-down as the reference tile draws it, and a 3 px progress track
 * sits at the foot while the snapshot carries a progress. What is shown at which size is decided by
 * [TileContentRules], which the phone's layout preview follows too.
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

    private val header = TileHeaderView(context, nameLines = TileContentRules.NAME_LINES)
    private val titleView = HudType.body(TextView(context), RokidHudTokens.TEXT_PRIMARY)
    private val dataValueView = TextView(context).apply {
        setTextColor(RokidHudTokens.TEXT_PRIMARY)
        typeface = RokidHudTokens.dataTypeface()
        RokidHudTokens.applyTextSize(this, RokidHudTokens.DATA_TEXT_SIZE)
        maxLines = 1
    }
    private val unitView = HudType.mono(TextView(context), RokidHudTokens.TEXT_SECONDARY)
    private val subtitleView = HudType.body(TextView(context), RokidHudTokens.TEXT_SECONDARY).apply {
        RokidHudTokens.applyTextSize(this, RokidHudTokens.LABEL_TEXT_SIZE)
    }
    private val badgeView = TextView(context).apply {
        setTextColor(RokidHudTokens.TEXT_PRIMARY)
        typeface = RokidHudTokens.dataTypeface()
        RokidHudTokens.applyTextSize(this, RokidHudTokens.LABEL_TEXT_SIZE)
        maxLines = 1
    }
    private val rowsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val progressTrack = ProgressTrackView(context).apply { visibility = GONE }
    private val loader = LoaderView(context)
    private val openingLoader = HudLoaderView(context).apply { visibility = GONE }
    internal val alertMark = HudIconView(context, HudIconView.Kind.ALERT).apply { visibility = GONE }

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.TOP or Gravity.START
    }

    private val focusTransition = FocusTransition(motion) { amount ->
        focusAmount = amount
        header.setFocusAmount(amount)
        if (bound) applyChrome()
    }

    init {
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        content.addView(titleView, LinearLayout.LayoutParams(match, wrap).apply { topMargin = RokidHudTokens.SPACE_1 })
        content.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                addView(dataValueView)
                addView(unitView, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = RokidHudTokens.SPACE_1 })
            },
            LinearLayout.LayoutParams(match, wrap).apply { topMargin = RokidHudTokens.SPACE_1 },
        )
        content.addView(subtitleView, LinearLayout.LayoutParams(match, wrap).apply { topMargin = 2 })
        content.addView(badgeView, LinearLayout.LayoutParams(match, wrap).apply { topMargin = RokidHudTokens.SPACE_1 })
        content.addView(rowsContainer, LinearLayout.LayoutParams(match, wrap).apply { topMargin = 6 })
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(
            progressTrack,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ProgressTrackView.HEIGHT).apply {
                topMargin = RokidHudTokens.SPACE_1
            },
        )
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
        progressTrack.visibility = GONE
        loader.visibility = VISIBLE
        loader.setProgress(progress)
        alertMark.visibility = GONE
        background = null
    }

    fun bind(snapshot: TileSnapshot, stale: Boolean) {
        bound = true
        loader.visibility = INVISIBLE
        content.visibility = VISIBLE

        val shown = TileContentRules.contentFor(size, snapshot)
        val dataRow = dataValueView.parent as View
        titleView.visibility = if (shown.titleStyle == TitleStyle.TEXT) VISIBLE else GONE
        titleView.maxLines = shown.titleMaxLines
        titleView.text = if (shown.titleStyle == TitleStyle.TEXT) snapshot.title else ""
        dataRow.visibility = if (shown.titleStyle == TitleStyle.DATA_VALUE) VISIBLE else GONE
        dataValueView.text = if (shown.titleStyle == TitleStyle.DATA_VALUE) snapshot.title else ""
        unitView.visibility = if (shown.showUnit) VISIBLE else GONE
        unitView.text = if (shown.showUnit) snapshot.unit else ""

        subtitleView.visibility = if (shown.subtitleVisible) VISIBLE else GONE
        subtitleView.maxLines = shown.subtitleMaxLines
        subtitleView.text = if (shown.subtitleVisible) snapshot.subtitle else ""
        badgeView.visibility = if (shown.badgeVisible) VISIBLE else GONE
        badgeView.text = if (shown.badgeVisible) snapshot.badge else ""

        rowsContainer.removeAllViews()
        rowsContainer.visibility = if (shown.rowCount > 0) VISIBLE else GONE
        snapshot.rows.take(shown.rowCount).forEachIndexed { index, row ->
            rowsContainer.addView(
                HudType.body(TextView(context), RokidHudTokens.TEXT_PRIMARY).apply {
                    RokidHudTokens.applyTextSize(this, RokidHudTokens.LABEL_TEXT_SIZE)
                    text = row
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { if (index > 0) topMargin = 3 },
            )
        }

        progressTrack.visibility = if (shown.progressVisible) VISIBLE else GONE
        progressTrack.setProgress(shown.progress ?: 0f)
        progressTrack.alpha = if (stale) STALE_ALPHA else 1f

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
        // The alert icon sits at the top-right of the eyebrow row: the name gives way to it.
        (header.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            val reserved = if (alert) RokidHudTokens.ICON_SM + RokidHudTokens.SPACE_1 else 0
            if (params.marginEnd != reserved) {
                params.marginEnd = reserved
                header.layoutParams = params
            }
        }
        alertMark.setIntensity(if (shown == TileTone.CRITICAL) RokidHudTokens.CRITICAL else RokidHudTokens.TEXT_PRIMARY)
    }

    /** Resting border intensity per tone; the width is always 1 px, 2 px belongs to focus. */
    private fun restStroke(tone: TileTone): Int = when (tone) {
        TileTone.OK, TileTone.WARN, TileTone.CRITICAL -> RokidHudTokens.TEXT_PRIMARY
        TileTone.INFO, TileTone.OFF -> RokidHudTokens.TEXT_SECONDARY
    }

    internal val nameForTest: String get() = header.nameText

    internal val alertMarkVisibleForTest: Boolean get() = alertMark.visibility == VISIBLE

    internal val progressTrackVisibleForTest: Boolean get() = progressTrack.visibility == VISIBLE

    internal val progressForTest: Float get() = progressTrack.progress

    /** The row texts currently shown, top to bottom. */
    internal val rowTextsForTest: List<String>
        get() = (0 until rowsContainer.childCount).map { (rowsContainer.getChildAt(it) as TextView).text.toString() }

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

/** `line` track with a `text-primary` fill: the tile's 3 px progress bar, radius-data corners. */
private class ProgressTrackView(context: Context) : View(context) {
    var progress = 0f
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = RokidHudTokens.RADIUS_DATA.toFloat()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = RokidHudTokens.LINE
        canvas.drawRoundRect(rect, radius, radius, paint)
        rect.right = width * progress
        paint.color = RokidHudTokens.TEXT_PRIMARY
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    companion object {
        const val HEIGHT = 3
    }
}
