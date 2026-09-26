package com.anezium.rokidbus.glasses

import android.content.Context
import com.anezium.rokidbus.shared.HudModeContract

/**
 * The wearer's launcher mode, as last pushed by the phone.
 *
 * Persisted here, not just held in memory, because the flag has to survive a hub restart or a
 * stretch with no phone connected — the same reasoning as [SelfArmBootRepairStore]. Defaults to
 * the contract's answer so glasses that never heard the phone show today's list launcher.
 */
internal object HudModeStore {
    private const val PREFS_NAME = "hud_mode"
    private const val KEY_GRID_MODE_ENABLED = "grid_mode_enabled"

    fun isGridModeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(
            KEY_GRID_MODE_ENABLED,
            HudModeContract.DEFAULT_MODE == HudModeContract.MODE_GRID,
        )

    fun setGridModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GRID_MODE_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
