package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme

/**
 * What the home layer needs from either launcher rendering: today's list ([LauncherMenuView]) or
 * the grid ([GridLauncherView]). Selection, key handling and the open sequence live in the HUD
 * state machine only; a content view never reaches into the bus itself.
 */
internal interface LauncherContentView {
    fun render(entries: List<GlassesHub.LauncherEntry>, selectedIndex: Int)
    fun setHudTopInsetDp(value: Int)
}

internal class LauncherMenuView(context: Context) : LinearLayout(context), LauncherContentView {
    private val countView = monoText(10.5f, BusTheme.dim)
    private val listView = LinearLayout(context).apply {
        orientation = VERTICAL
    }
    private val emptyView = monoText(17f, BusTheme.dim).apply {
        text = "No phone plugins synced"
        gravity = Gravity.CENTER
    }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(
            listView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.TOP
        setBackgroundColor(BusTheme.glassesBg)
        setPadding(dp(18), dp(16), dp(18), dp(12))

        addView(monoText(12f, BusTheme.phosphor, bold = true).apply {
            text = "ROKID NEXUS"
            gravity = Gravity.CENTER_HORIZONTAL
        }, matchWrap())
        addView(gap(14))
        addView(monoText(24f, BusTheme.text, bold = true).apply {
            text = "Launcher"
            gravity = Gravity.CENTER_HORIZONTAL
        }, matchWrap())
        addView(gap(8))
        addView(countView.apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }, matchWrap())
        addView(gap(18))
        addView(monoText(10.5f, BusTheme.dim).apply {
            text = "PLUGINS"
            gravity = Gravity.CENTER_HORIZONTAL
        }, matchWrap())
        addView(gap(10))
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun render(entries: List<GlassesHub.LauncherEntry>, selectedIndex: Int) {
        listView.removeAllViews()
        if (entries.isEmpty()) {
            countView.text = "Waiting for phone"
            listView.addView(
                emptyView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, scroll.height.coerceAtLeast(dp(260))),
            )
            return
        }

        countView.text = "${selectedIndex + 1}/${entries.size}"
        var selectedRow: View? = null
        entries.forEachIndexed { index, entry ->
            val row = pluginRow(entry, selected = index == selectedIndex)
            if (index == selectedIndex) selectedRow = row
            listView.addView(row, matchWrap().apply {
                topMargin = if (index == 0) 0 else dp(8)
            })
        }
        selectedRow?.let { row ->
            row.post {
                row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true)
            }
        }
    }

    override fun setHudTopInsetDp(value: Int) {
        setPadding(dp(18), dp(16 + HudTopInset.sanitize(value)), dp(18), dp(12))
        requestLayout()
    }

    private fun pluginRow(
        entry: GlassesHub.LauncherEntry,
        selected: Boolean,
    ): View {
        val icon = ImageView(context).apply {
            setImageDrawable(GlassesHub.launcherDrawable(context, entry))
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) }
        }
        val label = monoText(18f, if (selected) BusTheme.phosphor else BusTheme.text, bold = selected).apply {
            text = entry.displayName
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(12), 0, dp(12), 0)
            background = outline(selected)
            addView(icon)
            addView(label)
        }
    }

    private fun monoText(sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            textSize = sizeSp
            setTextColor(color)
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
            isSingleLine = false
            setHorizontallyScrolling(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            }
        }

    private fun outline(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(dp(if (selected) 2 else 1), if (selected) BusTheme.phosphor else BusTheme.hairline)
            cornerRadius = dp(4).toFloat()
        }

    private fun gap(value: Int): View =
        View(context).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(value))
        }

    private fun matchWrap(): LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int =
        BusTheme.dp(context, value)
}
