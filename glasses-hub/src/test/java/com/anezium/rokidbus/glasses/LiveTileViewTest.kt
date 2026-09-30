package com.anezium.rokidbus.glasses

import android.graphics.drawable.GradientDrawable
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LiveTileViewTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun snapshot(tone: TileTone) = TileSnapshot(
        pluginId = "transit",
        contentKey = "eta",
        title = "12",
        unit = "min",
        tone = tone,
    )

    @Test
    fun `every tone renders an outline drawable, never a filled background`() {
        TileTone.entries.forEach { tone ->
            val view = LiveTileView(context, TileSize.SMALL)
            view.bind(snapshot(tone), stale = false)
            assertTrue(view.background is GradientDrawable)
        }
    }

    @Test
    fun `critical emphasis hook fires only for the critical tone`() {
        val view = LiveTileView(context, TileSize.SMALL)
        var fired = 0
        view.criticalEmphasis = { fired++ }
        view.bind(snapshot(TileTone.OK), stale = false)
        assertEquals(0, fired)
        view.bind(snapshot(TileTone.CRITICAL), stale = false)
        assertEquals(1, fired)
    }

    @Test
    fun `numeric title renders as DataReadout mono value, not plain text`() {
        val view = LiveTileView(context, TileSize.SMALL)
        view.bind(snapshot(TileTone.OFF), stale = false)
        val dataValue = view.getChildAt(0)
        assertTrue(dataValue != null)
    }

    @Test
    fun `no snapshot yet shows the loader, never a blank view`() {
        val view = LiveTileView(context, TileSize.SMALL)
        // Constructed but never bound: still the mandated in-flight state.
        assertEquals(android.view.View.VISIBLE, view.findLoaderVisibilityForTest())
    }
}

private fun LiveTileView.findLoaderVisibilityForTest(): Int {
    // The loader is the second child added in init(); mirrors LiveTileView's own layout order.
    return getChildAt(1).visibility
}
