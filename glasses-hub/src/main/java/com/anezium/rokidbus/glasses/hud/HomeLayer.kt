package com.anezium.rokidbus.glasses.hud

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.glasses.GridLauncherView
import com.anezium.rokidbus.glasses.HudTopInset
import com.anezium.rokidbus.glasses.LauncherContentView
import com.anezium.rokidbus.glasses.LauncherMenuView

/**
 * The launcher inside [HudHost]: the existing list or grid content view, plus one line for the
 * "opening" and "could not open" status. It renders what it is told and holds no selection of its
 * own; the machine's selection is an id and this layer turns it into the index the content views
 * take.
 */
internal class HomeLayer(context: Context) : FrameLayout(context) {
    private var content: View? = null
    private var mode: HomeMode? = null
    private var entries: List<GlassesHub.LauncherEntry> = emptyList()
    private var selectedId: String? = null
    private var hudTopInsetDp = 0

    private val statusView = TextView(context).apply {
        setTextColor(RokidHudTokens.TEXT_PRIMARY)
        typeface = RokidHudTokens.bodyTypeface()
        textSize = RokidHudTokens.BODY_TEXT_SIZE_SP
        gravity = Gravity.CENTER
        isSingleLine = true
        val pad = RokidHudTokens.dp(context, RokidHudTokens.SPACE_2)
        setPadding(pad, pad, pad, pad)
        setBackgroundColor(RokidHudTokens.GROUND)
        visibility = GONE
    }

    init {
        addView(
            statusView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
    }

    /** A new mode swaps the content view; the same mode keeps it, so a re-show never rebuilds. */
    fun show(mode: HomeMode, entries: List<GlassesHub.LauncherEntry>, selectedId: String?) {
        if (this.mode != mode) {
            content?.let(::removeView)
            val next: View = if (mode == HomeMode.GRID) GridLauncherView(context) else LauncherMenuView(context)
            (next as LauncherContentView).setHudTopInsetDp(hudTopInsetDp)
            addView(next, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            content = next
            this.mode = mode
        }
        clearStatus()
        update(entries, selectedId)
    }

    fun update(entries: List<GlassesHub.LauncherEntry>, selectedId: String?) {
        this.entries = entries
        this.selectedId = selectedId
        val index = entries.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        (content as? LauncherContentView)?.render(entries, index)
    }

    fun select(selectedId: String?) {
        clearStatus()
        update(entries, selectedId)
    }

    fun showOpening(label: String) = setStatus("Opening $label...")

    fun showStatus(text: String) = setStatus(text)

    fun clearStatus() {
        statusView.visibility = GONE
    }

    /** Drops the rendered content so nothing stale is drawn when the layer is next shown. */
    fun clear() {
        clearStatus()
        entries = emptyList()
        selectedId = null
        (content as? LauncherContentView)?.render(emptyList(), 0)
    }

    fun setHudTopInsetDp(value: Int) {
        hudTopInsetDp = HudTopInset.sanitize(value)
        (content as? LauncherContentView)?.setHudTopInsetDp(hudTopInsetDp)
    }

    private fun setStatus(text: String) {
        statusView.text = text
        statusView.visibility = VISIBLE
    }
}
