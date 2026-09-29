package com.anezium.rokidbus.glasses.hud

import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.shared.tile.TileSnapshot

/** A plugin's last published tile snapshot and whether it is old enough to be drawn dimmed. */
internal data class HomeTile(val snapshot: TileSnapshot, val stale: Boolean)

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
) {
    val selectedIndex: Int get() = entries.indexOfFirst { it.id == selectedId }
}

/** What a list row or grid tile has to do to be a home item. */
internal interface HomeItemView {
    val homeFocused: Boolean
    val homeOpening: Boolean

    /** Focus is the one selection the home layer has: fill, 2 px focus border, focus text. */
    fun setFocused(focused: Boolean)

    /** Shows or hides the `Loader` while the plugin's surface is being opened. */
    fun setOpening(opening: Boolean)
}
