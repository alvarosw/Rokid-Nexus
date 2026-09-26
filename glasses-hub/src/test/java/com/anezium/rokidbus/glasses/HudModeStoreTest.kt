package com.anezium.rokidbus.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HudModeStoreTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `the mode defaults to list and persists what the phone pushes`() {
        assertFalse(HudModeStore.isGridModeEnabled(context))
        HudModeStore.setGridModeEnabled(context, true)
        assertTrue(HudModeStore.isGridModeEnabled(context))
        HudModeStore.setGridModeEnabled(context, false)
        assertFalse(HudModeStore.isGridModeEnabled(context))
    }
}
