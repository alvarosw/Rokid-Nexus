package com.anezium.rokidbus.phone

import android.content.Context
import android.content.SharedPreferences
import com.anezium.rokidbus.shared.HudModeContract

/**
 * The owner's switch between today's list launcher and the grid launcher.
 *
 * The mode lives on the glasses — a restart or a disconnected stretch must still show the last
 * chosen mode — so this store is the phone's copy of record: the settings screen writes it, and
 * the hub re-pushes it on every glasses capabilities announce, so a toggle flipped while the link
 * was down still lands. Defaults to the contract's answer so both sides agree before the first
 * push.
 */
class HudModeSettingsStore private constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE),
    )

    fun isGridModeEnabled(): Boolean =
        preferences.getBoolean(KEY_GRID_MODE_ENABLED, HudModeContract.DEFAULT_MODE == HudModeContract.MODE_GRID)

    fun setGridModeEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_GRID_MODE_ENABLED, enabled).apply()
    }

    companion object {
        private const val KEY_GRID_MODE_ENABLED = "hud_grid_mode_enabled"
    }
}
