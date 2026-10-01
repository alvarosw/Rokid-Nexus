package com.anezium.rokidbus.glasses.hud

import android.graphics.Canvas
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.shared.tile.TilePlacement
import com.anezium.rokidbus.shared.tile.TileSnapshot

/**
 * A plugin's last published tile snapshot, whether it is old enough to be drawn dimmed, and when it
 * arrived (`elapsedRealtime`), which a template's time-relative fields count from.
 */
internal data class HomeTile(val snapshot: TileSnapshot, val stale: Boolean, val receivedAtElapsed: Long = 0L)

internal sealed interface HomeStatus {
    data object None : HomeStatus

    /** An open is in flight for [pluginId]: its row or tile shows a `Loader`. */
    data class Opening(val pluginId: String) : HomeStatus

    /** The open failed; shown as a `Status warn` strip until the next input or a short timeout. */
    data class Failed(val text: String) : HomeStatus
}

/**
 * Everything the two home renderings draw, as one value. Both [ListHome] and [GridHome] render it
 * and diff it against the previous one by plugin id, so a selection move changes focus on two
 * views and a data update touches the tiles whose data changed.
 */
internal data class HomeViewModel(
    val entries: List<GlassesHub.LauncherEntry> = emptyList(),
    val selectedId: String? = null,
    val mode: HomeMode = HomeMode.LIST,
    val status: HomeStatus = HomeStatus.None,
    /** Only plugins that have a snapshot; every other entry is a fallback tile. Grid mode only. */
    val tileData: Map<String, HomeTile> = emptyMap(),
    /** Where the grid puts each entry; empty in list mode. */
    val placements: List<TilePlacement> = emptyList(),
    /**
     * The system widgets the grid draws beside the entries; empty in list mode. They are not
     * entries: never selected, opened or counted.
     */
    val widgets: List<TilePlacement> = emptyList(),
    /** A notice band owns the ring and draws the one focus frame; the selection rests. */
    val noticeOwnsRing: Boolean = false,
) {
    val selectedIndex: Int get() = entries.indexOfFirst { it.id == selectedId }

    /** The item that draws the focus chrome: the selection, unless the notice has the one frame. */
    val focusedId: String? get() = selectedId.takeUnless { noticeOwnsRing }
}

/** What a list row or grid tile has to do to be a home item. */
internal interface HomeItemView {
    val homeFocused: Boolean
    val homeOpening: Boolean

    /**
     * Focus is the one selection the home layer has: fill, 2 px focus border, focus text. The
     * state changes at once; [animate] only cross-fades the chrome at `duration-default`.
     */
    fun setFocused(focused: Boolean, animate: Boolean = false)

    /** Ends a focus cross-fade on its end state, so the item is drawn as it will rest. */
    fun settleFocus()

    /** Draws the item's content only, at the item's own size: no chrome, no loader. */
    fun drawContent(canvas: Canvas)

    /** Shows or hides the `Loader` while the plugin's surface is being opened. */
    fun setOpening(opening: Boolean)
}
