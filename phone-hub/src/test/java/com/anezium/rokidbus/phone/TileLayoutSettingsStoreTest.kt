package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileSize
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TileLayoutSettingsStoreTest {
    @Test
    fun `no custom layout by default and entries round-trip in order`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val store = TileLayoutSettingsStore(context)

        assertEquals(emptyList<TileLayoutEntry>(), store.getEntries())

        val entries = listOf(
            TileLayoutEntry("weather", TileSize.WIDE, col = 0, row = 0),
            TileLayoutEntry("clock", TileSize.SMALL, col = 2, row = 0),
            TileLayoutEntry("banner", TileSize.BANNER, col = 0, row = 1),
            TileLayoutEntry("panel", TileSize.PANEL, col = 1, row = 2),
            TileLayoutEntry("jumbo", TileSize.JUMBO, col = 0, row = 4),
            TileLayoutEntry("tall", TileSize.TALL, col = 3, row = 1),
            TileLayoutEntry("large", TileSize.LARGE, col = 2, row = 5),
        )
        store.setEntries(entries)
        assertEquals(entries, TileLayoutSettingsStore(context).getEntries())

        store.setEntries(emptyList())
        assertEquals(emptyList<TileLayoutEntry>(), TileLayoutSettingsStore(context).getEntries())
    }
}
