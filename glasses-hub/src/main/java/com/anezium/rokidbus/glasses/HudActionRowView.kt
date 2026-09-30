package com.anezium.rokidbus.glasses

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.NexusGlyphs
import com.anezium.rokidbus.client.ui.RokidHudTokens

/** One drawable command in a [HudActionRowView], stripped of whose tier it came from. */
internal data class HudActionChip(val glyph: String, val label: String)

/**
 * The platform's row of choices, drawn identically wherever it appears.
 *
 * An activity panel and a notice band are different tiers with different
 * lifetimes, but the affordance is one thing: a short row of glyphs, exactly
 * one of them selected, stepped through with forward and backward and fired
 * with confirm. Two drawings of that would be two things for the wearer to
 * learn, so the drawing lives here and each tier only supplies its own list.
 *
 * Each chip is a `Button`: an outline in `line-control`, the icon and `body` label at
 * `text-primary`. The selected chip is the row's one primary button, drawn in the focus chrome
 * (`surface-selected`, 2 px `focus` border, `focus` text and icon).
 *
 * The row hides itself when there is nothing to offer, so a caller can render
 * unconditionally.
 */
internal class HudActionRowView(context: Context) : LinearLayout(context) {
    private val labels = mutableListOf<TextView>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.START
    }

    fun render(actions: List<HudActionChip>, selectedIndex: Int) {
        removeAllViews()
        labels.clear()
        actions.forEachIndexed { index, action ->
            addView(
                chip(action, selected = index == selectedIndex),
                LayoutParams(LayoutParams.WRAP_CONTENT, CHIP_HEIGHT).apply {
                    if (index > 0) marginStart = RokidHudTokens.SPACE_2
                },
            )
        }
        visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Every chip is given an equal share of the row before it is measured.
     *
     * A chip wraps its label, so without a ceiling one long label simply makes
     * the row wider than the band and pushes the chips after it off the glass —
     * the ellipsize on the label never fires, because nothing ever told the
     * label it was short on room. Handing each chip the same share means three
     * long labels all truncate instead of the last one disappearing, and a short
     * label still costs only what it needs.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        if (available > 0 && labels.isNotEmpty()) {
            val gaps = RokidHudTokens.SPACE_2 * (labels.size - 1)
            val share = (available - gaps) / labels.size - CHIP_CHROME
            val ceiling = share.coerceAtLeast(MIN_LABEL)
            labels.forEach { it.maxWidth = ceiling }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun chip(action: HudActionChip, selected: Boolean) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(RokidHudTokens.SPACE_2, 0, RokidHudTokens.SPACE_2, 0)
        background = if (selected) SurfaceChrome.focused() else SurfaceChrome.control()
        val intensity = if (selected) RokidHudTokens.FOCUS else RokidHudTokens.TEXT_PRIMARY
        addView(
            AmbientStyle.icon(
                context,
                requireNotNull(context.getDrawable(NexusGlyphs.drawableFor(action.glyph))),
                RokidHudTokens.ICON_SM,
                intensity,
            ),
            LayoutParams(RokidHudTokens.ICON_SM, RokidHudTokens.ICON_SM),
        )
        addView(
            SurfaceType.body(TextView(context), intensity).apply {
                text = action.label
                labels.add(this)
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = RokidHudTokens.SPACE_1
            },
        )
    }

    private companion object {
        /** `ListItem`'s 32 px row: the height of every control in the design system. */
        const val CHIP_HEIGHT = RokidHudTokens.LIST_ITEM_HEIGHT

        /** A chip's own width around the label: padding, icon, and the gap after it. */
        const val CHIP_CHROME = 2 * RokidHudTokens.SPACE_2 + RokidHudTokens.ICON_SM + RokidHudTokens.SPACE_1

        /** Below this the label reads as noise, so the row overflows rather than lie. */
        const val MIN_LABEL = 36
    }
}
