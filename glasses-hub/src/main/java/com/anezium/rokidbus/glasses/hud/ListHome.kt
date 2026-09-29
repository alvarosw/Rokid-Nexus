package com.anezium.rokidbus.glasses.hud

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.GlassesHub

/**
 * One `ListItem` (docs/grid-hud-roadmap/00-overview.md): 32 px row, 20 px icon, `body` label.
 * Focused = `surface-selected` fill, 2 px `focus` border and `focus` text; at rest a hairline
 * `line` border. While opening, the right end carries a `Loader`.
 */
internal class ListRowView(
    context: Context,
    private val iconLoader: (Context, GlassesHub.LauncherEntry) -> Drawable,
) : LinearLayout(context), HomeItemView {
    var entry: GlassesHub.LauncherEntry? = null
        private set
    override var homeFocused = false
        private set
    override var homeOpening = false
        private set

    private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val label = HudType.body(TextView(context), RokidHudTokens.TEXT_PRIMARY).apply {
        gravity = Gravity.CENTER_VERTICAL
    }
    private val openingLabel = HudType.label(TextView(context)).apply {
        text = "OPENING"
        visibility = GONE
    }
    private val loader = HudLoaderView(context).apply { visibility = GONE }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(RokidHudTokens.SPACE_3, 0, RokidHudTokens.SPACE_3, 0)
        val iconSize = RokidHudTokens.ICON_MD
        addView(icon, LayoutParams(iconSize, iconSize))
        addView(
            label,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { marginStart = RokidHudTokens.SPACE_1 * 2 },
        )
        addView(openingLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(
            loader,
            LayoutParams(LOADER_WIDTH, HudLoaderView.TRACK_HEIGHT * 2).apply { marginStart = RokidHudTokens.SPACE_2 },
        )
        applyChrome()
    }

    fun bind(entry: GlassesHub.LauncherEntry) {
        this.entry = entry
        icon.setImageDrawable(iconLoader(context, entry))
        label.text = entry.displayName
    }

    override fun setFocused(focused: Boolean) {
        if (homeFocused == focused) return
        homeFocused = focused
        applyChrome()
    }

    override fun setOpening(opening: Boolean) {
        if (homeOpening == opening) return
        homeOpening = opening
        val visibility = if (opening) VISIBLE else GONE
        openingLabel.visibility = visibility
        loader.visibility = visibility
        loader.setActive(opening)
    }

    private fun applyChrome() {
        val intensity = if (homeFocused) RokidHudTokens.FOCUS else RokidHudTokens.TEXT_PRIMARY
        background = if (homeFocused) HomeChrome.focused() else HomeChrome.rest()
        label.setTextColor(intensity)
        icon.imageTintList = ColorStateList.valueOf(intensity)
    }

    internal fun loaderForTest(): HudLoaderView = loader

    private companion object {
        const val LOADER_WIDTH = 48
    }
}

/**
 * The list rendering. Rows are kept by plugin id and updated in place; the list is a strip that is
 * translated by its own offset so the selected row (plus a row of context on each side when
 * there is one) stays fully inside the whole rows the viewport shows (14 on the 480x640 screen).
 */
internal class ListHome(
    context: Context,
    private val iconLoader: (Context, GlassesHub.LauncherEntry) -> Drawable,
) : HomeScreenView(context) {
    private val rows = LinkedHashMap<String, ListRowView>()
    private var offset = 0

    val offsetForTest: Int get() = offset

    override fun bindBody(prev: HomeViewModel?, model: HomeViewModel) {
        val entriesChanged = prev == null || prev.entries != model.entries
        if (entriesChanged) {
            syncRows(model.entries)
            fitToContent(contentHeight(model.entries.size))
        }
        applyFocus(prev, model, entriesChanged)
        applyOpening(prev, model, entriesChanged)
        val selected = model.selectedIndex
        if (entriesChanged || prev?.selectedId != model.selectedId) followSelection(selected, model.entries.size)
    }

    private fun syncRows(entries: List<GlassesHub.LauncherEntry>) {
        val keep = entries.mapTo(HashSet()) { it.id }
        rows.keys.filterNot { it in keep }.forEach { id -> strip.removeView(rows.remove(id)) }
        entries.forEachIndexed { index, entry ->
            val row = rows.getOrPut(entry.id) {
                ListRowView(context, iconLoader).also { strip.addView(it) }
            }
            if (row.entry != entry) row.bind(entry)
            val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ROW_HEIGHT).apply {
                topMargin = index * PITCH
            }
            row.layoutParams = params
        }
        strip.layoutParams = strip.layoutParams.apply { height = contentHeight(entries.size) }
    }

    private fun applyFocus(prev: HomeViewModel?, model: HomeViewModel, all: Boolean) {
        if (all) {
            rows.forEach { (id, row) -> row.setFocused(id == model.selectedId) }
        } else {
            prev?.selectedId?.let { rows[it]?.setFocused(false) }
            model.selectedId?.let { rows[it]?.setFocused(true) }
        }
    }

    private fun applyOpening(prev: HomeViewModel?, model: HomeViewModel, all: Boolean) {
        val now = (model.status as? HomeStatus.Opening)?.pluginId
        if (all) {
            rows.forEach { (id, row) -> row.setOpening(id == now) }
        } else {
            (prev?.status as? HomeStatus.Opening)?.pluginId?.let { rows[it]?.setOpening(false) }
            now?.let { rows[it]?.setOpening(true) }
        }
    }

    override fun fitBody(available: Int): Int {
        val rows = ((available + RokidHudTokens.SPACE_2) / PITCH).coerceAtLeast(1)
        return rows * ROW_HEIGHT + (rows - 1) * RokidHudTokens.SPACE_2
    }

    override fun onBodyChanged(model: HomeViewModel) = followSelection(model.selectedIndex, model.entries.size)

    private fun contentHeight(count: Int) = if (count == 0) 0 else count * PITCH - RokidHudTokens.SPACE_2

    private fun followSelection(selectedIndex: Int, count: Int) {
        val contentHeight = contentHeight(count)
        val maxOffset = (contentHeight - bodyHeight).coerceAtLeast(0)
        if (selectedIndex >= 0) {
            // Keep the selected row and one row of context around it inside the viewport.
            val top = selectedIndex * PITCH - PITCH
            val bottom = selectedIndex * PITCH + ROW_HEIGHT + PITCH
            if (top < offset) offset = top
            if (bottom > offset + bodyHeight) offset = bottom - bodyHeight
        }
        offset = offset.coerceIn(0, maxOffset)
        strip.translationY = -offset.toFloat()
        setPosition(offset, bodyHeight, contentHeight)
    }

    override fun itemBounds(id: String): Rect? {
        val row = rows[id] ?: return null
        return boundsOf(row)
    }

    internal fun rowsForTest(): Map<String, ListRowView> = rows

    internal fun stripForTest(): View = strip

    internal val visibleRowsForTest: Int get() = (bodyHeight + RokidHudTokens.SPACE_2) / PITCH

    companion object {
        const val ROW_HEIGHT = RokidHudTokens.LIST_ITEM_HEIGHT
        const val PITCH = ROW_HEIGHT + RokidHudTokens.SPACE_2
    }
}
