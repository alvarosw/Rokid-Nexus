package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.hud.HomeChrome
import com.anezium.rokidbus.glasses.hud.HomeItemView
import com.anezium.rokidbus.glasses.hud.HudLoaderView
import com.anezium.rokidbus.glasses.hud.HudType
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * Renders a plugin's published [TileSnapshot], per-size, per `docs/grid-hud-roadmap/
 * 03-delivery-3-tile-data-pipeline.md`. Only ever shown once a snapshot exists (cached or fresh);
 * [FallbackTileView] stays the permanent no-data/no-adoption path.
 *
 * `tone` renders via the `Status` component's border-shape combination — never a distinct
 * color, per the design system's single-hue rule. Focus (the home layer's one selection) is the
 * same on every tile: `surface-selected` fill and a 2 px `focus` border, dashed where the tone is
 * `WARN`, so a live tile is as visibly selected as a fallback one (F-10). A stale snapshot dims its
 * content only, never the border. `CRITICAL`'s static parts (2px `critical`
 * border, alert glyph, full-intensity text) are spec-compliant on their own; [criticalEmphasis]
 * defaults to [CriticalBlink] (Delivery 2) as a strict visual enhancement on top of that static
 * treatment. Nulling it out falls back to the static-only rendering, so this delivery stays
 * correct even if Delivery 2's helper is ever absent or rolled back.
 */
internal class LiveTileView(context: Context, private val size: TileSize) : FrameLayout(context), HomeItemView {
    /** Delivery 2's blink-then-settle enhancement hook; null = static critical treatment only. */
    var criticalEmphasis: ((LiveTileView) -> Unit)? = { view -> view.criticalBlink = CriticalBlink.animate(view) }
    private var criticalBlink: CriticalBlink.Handle? = null
    private var tone = TileTone.OFF
    private var focused = false
    private var opening = false

    override val homeFocused: Boolean get() = focused
    override val homeOpening: Boolean get() = opening

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

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
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
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(loader, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(
            openingLoader,
            LayoutParams(LayoutParams.MATCH_PARENT, HudLoaderView.TRACK_HEIGHT * 2, Gravity.BOTTOM),
        )
        val pad = RokidHudTokens.SPACE_2
        setPadding(pad, pad, pad, pad)
        showLoading(null)
    }

    /** No snapshot yet at all — the mandated in-flight state, never a blank view. */
    fun showLoading(progress: Float?) {
        content.visibility = INVISIBLE
        loader.visibility = VISIBLE
        loader.setProgress(progress)
        background = null
    }

    fun bind(snapshot: TileSnapshot, stale: Boolean) {
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

        val wasCritical = tone == TileTone.CRITICAL
        tone = snapshot.tone
        content.alpha = if (stale) STALE_ALPHA else 1f
        applyChrome()
        if (tone == TileTone.CRITICAL) {
            // A re-publish of the same critical tone must not restart the blink (F-7).
            if (!wasCritical) {
                criticalBlink?.cancel()
                criticalEmphasis?.invoke(this)
            }
        } else {
            criticalBlink?.cancel()
            criticalBlink = null
            alpha = 1f
        }
    }

    override fun setFocused(focused: Boolean) {
        if (this.focused == focused) return
        this.focused = focused
        if (content.visibility == VISIBLE) applyChrome()
    }

    override fun setOpening(opening: Boolean) {
        if (this.opening == opening) return
        this.opening = opening
        openingLoader.visibility = if (opening) VISIBLE else GONE
        openingLoader.setActive(opening)
    }

    private fun applyChrome() {
        val dashed = tone == TileTone.WARN
        val text = if (focused) RokidHudTokens.FOCUS else RokidHudTokens.TEXT_PRIMARY
        titleView.setTextColor(text)
        dataValueView.setTextColor(text)
        background = if (focused) {
            HomeChrome.outline(
                RokidHudTokens.SURFACE_SELECTED,
                RokidHudTokens.FOCUS,
                RokidHudTokens.BORDER_STRONG,
                dashed,
            )
        } else {
            val (stroke, width) = when (tone) {
                TileTone.OK -> RokidHudTokens.TEXT_PRIMARY to RokidHudTokens.BORDER_DEFAULT
                TileTone.INFO -> RokidHudTokens.TEXT_SECONDARY to RokidHudTokens.BORDER_DEFAULT
                TileTone.WARN -> RokidHudTokens.TEXT_PRIMARY to RokidHudTokens.BORDER_DEFAULT
                TileTone.CRITICAL -> RokidHudTokens.CRITICAL to RokidHudTokens.BORDER_STRONG
                TileTone.OFF -> RokidHudTokens.TEXT_SECONDARY to RokidHudTokens.BORDER_DEFAULT
            }
            HomeChrome.outline(Color.TRANSPARENT, stroke, width, dashed)
        }
    }

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
