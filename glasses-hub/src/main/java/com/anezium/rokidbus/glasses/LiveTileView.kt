package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * Renders a plugin's published [TileSnapshot], per-size, per `docs/grid-hud-roadmap/
 * 03-delivery-3-tile-data-pipeline.md`. Only ever shown once a snapshot exists (cached or fresh);
 * [FallbackTileView] stays the permanent no-data/no-adoption path.
 *
 * `tone` renders via the `Status` component's icon + border-shape combination — never a distinct
 * color, per the design system's single-hue rule. `CRITICAL`'s static parts (2px `critical`
 * border, alert glyph, full-intensity text) are spec-compliant on their own; [criticalEmphasis]
 * is a strict visual enhancement Delivery 2's blink-then-settle helper can hook into later without
 * this delivery depending on it being present.
 */
internal class LiveTileView(context: Context, private val size: TileSize) : FrameLayout(context) {
    /** Delivery 2's blink-then-settle enhancement hook; absent = static critical treatment. */
    var criticalEmphasis: ((LiveTileView) -> Unit)? = null

    private val titleView = TextView(context).apply {
        setTextColor(RokidHudTokens.TEXT_PRIMARY)
        typeface = RokidHudTokens.bodyTypeface()
        textSize = RokidHudTokens.BODY_TEXT_SIZE_SP
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val dataValueView = TextView(context).apply {
        setTextColor(RokidHudTokens.TEXT_PRIMARY)
        typeface = RokidHudTokens.dataTypeface()
        textSize = RokidHudTokens.DATA_TEXT_SIZE_SP
        maxLines = 1
    }
    private val unitView = TextView(context).apply {
        setTextColor(RokidHudTokens.TEXT_SECONDARY)
        typeface = RokidHudTokens.monoTypeface()
        textSize = RokidHudTokens.LABEL_TEXT_SIZE_SP
        maxLines = 1
    }
    private val subtitleView = TextView(context).apply {
        setTextColor(RokidHudTokens.TEXT_SECONDARY)
        typeface = RokidHudTokens.bodyTypeface()
        textSize = RokidHudTokens.LABEL_TEXT_SIZE_SP
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val badgeView = TextView(context).apply {
        setTextColor(RokidHudTokens.TEXT_PRIMARY)
        typeface = RokidHudTokens.dataTypeface()
        textSize = RokidHudTokens.LABEL_TEXT_SIZE_SP
        maxLines = 1
    }
    private val rowsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val loader = LoaderView(context)

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
        val pad = RokidHudTokens.dp(context, RokidHudTokens.SPACE_2)
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
                    TextView(context).apply {
                        setTextColor(RokidHudTokens.TEXT_PRIMARY)
                        typeface = RokidHudTokens.bodyTypeface()
                        textSize = RokidHudTokens.LABEL_TEXT_SIZE_SP
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        text = row
                    },
                )
            }
        }

        applyTone(snapshot.tone, stale)
        if (snapshot.tone == TileTone.CRITICAL) criticalEmphasis?.invoke(this)
    }

    private fun applyTone(tone: TileTone, stale: Boolean) {
        val alpha = if (stale) 0x60 else 0xFF
        val (strokeColor, dashed, strokeWidthDp) = when (tone) {
            TileTone.OK -> Triple(RokidHudTokens.TEXT_PRIMARY, false, RokidHudTokens.BORDER_DEFAULT)
            TileTone.INFO -> Triple(RokidHudTokens.TEXT_SECONDARY, false, RokidHudTokens.BORDER_DEFAULT)
            TileTone.WARN -> Triple(RokidHudTokens.TEXT_PRIMARY, true, RokidHudTokens.BORDER_DEFAULT)
            TileTone.CRITICAL -> Triple(RokidHudTokens.CRITICAL, false, RokidHudTokens.BORDER_STRONG)
            TileTone.OFF -> Triple(RokidHudTokens.TEXT_SECONDARY, false, RokidHudTokens.BORDER_DEFAULT)
        }
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(
                RokidHudTokens.dp(context, strokeWidthDp),
                strokeColor,
                if (dashed) RokidHudTokens.dp(context, 3).toFloat() else 0f,
                if (dashed) RokidHudTokens.dp(context, 3).toFloat() else 0f,
            )
            cornerRadius = RokidHudTokens.dp(context, RokidHudTokens.RADIUS_CONTROL).toFloat()
        }
        this.alpha = alpha / 255f
    }
}

/** Minimal `Loader` per the design system: `scan`/`point` while pending, `progress` once known. */
private class LoaderView(context: Context) : TextView(context) {
    init {
        setTextColor(RokidHudTokens.TEXT_SECONDARY)
        typeface = RokidHudTokens.monoTypeface()
        textSize = RokidHudTokens.LABEL_TEXT_SIZE_SP
        gravity = Gravity.CENTER
    }

    fun setProgress(progress: Float?) {
        text = if (progress != null) "${(progress * 100).toInt()}%" else "…"
    }
}
