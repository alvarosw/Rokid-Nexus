package com.anezium.rokidbus.phone

import android.content.Context
import android.widget.Button
import android.view.View
import android.view.ViewGroup
import com.anezium.rokidbus.shared.TileLayoutContract
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileSize
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The phone re-pushes its stored layout on every glasses announce, so a system widget entry the
 * editor drops would be erased from the glasses too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TileLayoutWidgetRoundTripTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clear() {
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun <T : View> find(root: View, type: Class<T>, match: (T) -> Boolean): T? {
        if (type.isInstance(root) && match(type.cast(root)!!)) return type.cast(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) find(root.getChildAt(i), type, match)?.let { return it }
        }
        return null
    }

    @Test
    fun `saving the editor keeps the stored widgets and the pushed config carries them`() {
        val stored = listOf(
            TileLayoutEntry("sys:clock", TileSize.WIDE, 0, 0),
            TileLayoutEntry("media", TileSize.SMALL, 2, 0),
            TileLayoutEntry("sys:status", TileSize.SMALL, 3, 0),
        )
        TileLayoutSettingsStore(context).setEntries(stored)
        val activity = Robolectric.buildActivity(ScreenshotTileLayoutActivity::class.java).setup().get()

        find(activity.window.decorView, Button::class.java) { it.text == "SAVE LAYOUT" }!!.performClick()

        val saved = TileLayoutSettingsStore(context).getEntries()
        assertEquals(stored, saved.take(3))
        // What BusHubService.pushTileLayoutConfig sends is the store as is.
        assertEquals(saved, TileLayoutContract.entriesFromConfig(TileLayoutContract.configToJson(saved)))
    }
}
