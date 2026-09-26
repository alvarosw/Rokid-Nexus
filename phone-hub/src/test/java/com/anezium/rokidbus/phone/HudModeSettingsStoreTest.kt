package com.anezium.rokidbus.phone

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HudModeSettingsStoreTest {
    @Test
    fun `grid mode is off by default and persists owner changes`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val store = HudModeSettingsStore(context)

        assertFalse(store.isGridModeEnabled())
        store.setGridModeEnabled(true)
        assertTrue(HudModeSettingsStore(context).isGridModeEnabled())
        store.setGridModeEnabled(false)
        assertFalse(HudModeSettingsStore(context).isGridModeEnabled())
    }
}
