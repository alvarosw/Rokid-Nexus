package com.anezium.rokidbus.shared.tile

/**
 * A hub-owned grid tile that is not a plugin: drawn by the glasses from data they already hold,
 * never selected, never opened. Its [id] is in the reserved `sys:` namespace, which no plugin can
 * take because `:` is not a valid plugin-id character (`PluginDescriptor.isValidId`).
 *
 * A widget is enabled exactly when the stored tile layout has an entry for it; there is no other
 * switch. [supportedSizes] are the sizes the editor offers; a stored entry at any other size is
 * still drawn, by the nearest layout.
 */
data class SystemWidget(
    val id: String,
    val displayName: String,
    /** One short line for the editor's widget list. */
    val description: String,
    /** A built-in icon key (`NexusPluginIcons`), drawn in the tile header like a plugin's. */
    val iconKey: String,
    val supportedSizes: List<TileSize>,
    val defaultSize: TileSize,
)

/**
 * The system widgets this build knows. A `sys:` id that is not here (a widget from a newer phone)
 * is dropped by the glasses exactly like an entry for an uninstalled plugin.
 */
object SystemWidgets {
    const val ID_PREFIX = "sys:"

    val CLOCK = SystemWidget(
        id = "sys:clock",
        displayName = "Clock",
        description = "Local time and date",
        iconKey = "clock",
        supportedSizes = listOf(TileSize.SMALL, TileSize.WIDE, TileSize.BANNER, TileSize.LARGE),
        defaultSize = TileSize.SMALL,
    )

    val STATUS = SystemWidget(
        id = "sys:status",
        displayName = "Status",
        description = "Glasses and phone battery, phone link",
        iconKey = "battery",
        supportedSizes = listOf(TileSize.SMALL, TileSize.WIDE, TileSize.BANNER),
        defaultSize = TileSize.SMALL,
    )

    /** Fed by the phone hub on `/phone/weather`; while it is placed, the phone fetches about every 30 min. */
    val WEATHER = SystemWidget(
        id = "sys:weather",
        displayName = "Weather",
        description = "Temperature, conditions and forecast",
        iconKey = "forecast",
        supportedSizes = listOf(TileSize.SMALL, TileSize.WIDE, TileSize.BANNER, TileSize.LARGE, TileSize.PANEL),
        defaultSize = TileSize.WIDE,
    )

    val all: List<SystemWidget> = listOf(CLOCK, STATUS, WEATHER)

    fun isSystemId(id: String): Boolean = id.startsWith(ID_PREFIX)

    fun byId(id: String): SystemWidget? = all.firstOrNull { it.id == id }

    /** The widgets this build knows that [stored] has an entry for, in stored order, once each. */
    fun placedIn(stored: List<TileLayoutEntry>): List<SystemWidget> =
        stored.mapNotNull { byId(it.pluginId) }.distinct()
}
