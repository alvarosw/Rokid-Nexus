package com.anezium.rokidbus.shared

import org.json.JSONObject

/**
 * The wearer's choice between today's list launcher and the grid launcher, steered from the
 * phone.
 *
 * The flag is persisted on the glasses because that is exactly what has to survive a hub restart
 * or a stretch with the phone disconnected. The phone holds the settings switch and is the copy
 * of record; the hub re-pushes it on every glasses capabilities announce, so a toggle flipped
 * while the link was down still lands. See `phone-hub/.../GlassesRepairSettingsStore.kt` for the
 * exact mechanism this mirrors.
 */
object HudModeContract {
    const val VERSION = 1

    const val MODE_LIST = "list"
    const val MODE_GRID = "grid"
    val MODES: List<String> = listOf(MODE_LIST, MODE_GRID)

    /** An owner who never touched the toggle keeps today's list launcher. */
    const val DEFAULT_MODE = MODE_LIST

    fun configToJson(gridModeEnabled: Boolean): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("mode", if (gridModeEnabled) MODE_GRID else MODE_LIST)

    /** Null for anything this build does not recognise — the glasses' stored mode then stands. */
    fun gridModeFromConfig(payload: JSONObject?): Boolean? {
        val json = payload ?: return null
        if (json.optInt("version", 0) < 1) return null
        return when (json.optString("mode")) {
            MODE_GRID -> true
            MODE_LIST -> false
            else -> null
        }
    }
}
