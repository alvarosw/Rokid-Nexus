package com.anezium.rokidbus.phone

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TileLayoutSettingsActivitySmokeTest {
    @Test
    fun `activity creates without crashing with no plugins installed`() {
        Robolectric.buildActivity(TileLayoutSettingsActivity::class.java).setup()
    }
}
