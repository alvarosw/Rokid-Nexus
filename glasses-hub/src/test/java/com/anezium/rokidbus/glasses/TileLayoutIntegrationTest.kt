package com.anezium.rokidbus.glasses

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.anezium.rokidbus.shared.TileLayoutContract
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TilePlacement
import com.anezium.rokidbus.shared.tile.TileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * End-to-end manual verification of Delivery 4's wire-to-render path, using the real production
 * code at every step rather than re-deriving expectations by hand: [TileLayoutContract] (the
 * exact payload the phone sends), [TileLayoutStore] (exactly what
 * `GlassesHub`'s `TILE_LAYOUT_CONFIG`/`LAUNCHER_LIST` handlers call), and [GridLauncherView] (the
 * real view class the accessibility overlay renders). This does not go through `GlassesHub` itself
 * — its `start()` loads the vendor CXR native library unconditionally, which is not available to a
 * JVM test — so this test drives the same downstream calls `GlassesHub` makes once a config
 * envelope is parsed.
 */
@RunWith(RobolectricTestRunner::class)
class TileLayoutIntegrationTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun launcherEntry(id: String) = GlassesHub.LauncherEntry(id = id, displayName = id)

    @Test
    fun `a synced layout reorders and sizes tiles, and a newly installed plugin lands after them`() {
        // The phone's chosen layout: "c" wide, "a" small. "b" doesn't exist yet from the phone's
        // point of view.
        val phoneSideEntries = listOf(
            TileLayoutEntry("c", TileSize.WIDE),
            TileLayoutEntry("a", TileSize.SMALL),
        )

        // Exactly what crosses the wire, and exactly what GlassesHub.onRemoteEnvelope does with the
        // TILE_LAYOUT_CONFIG payload it receives.
        val wirePayload = TileLayoutContract.configToJson(phoneSideEntries)
        val parsedEntries = requireNotNull(TileLayoutContract.entriesFromConfig(wirePayload))
        TileLayoutStore.setEntries(context, parsedEntries)

        // The launcher list as it exists on the glasses today: install order a, b, c — "b" was
        // installed after the layout above was saved and was never placed.
        val installOrderEntries = listOf(launcherEntry("a"), launcherEntry("b"), launcherEntry("c"))

        // Exactly what GlassesHub.allLauncherEntries() does to the non-camera portion of the list.
        val ordered = TileLayoutStore.applyOrder(context, installOrderEntries)
        assertEquals(listOf("c", "a", "b"), ordered.map { it.id })

        val view = GridLauncherView(context).apply {
            setIconLoaderForTest { _, _ -> ColorDrawable(Color.BLACK) }
        }
        view.render(ordered, selectedIndex = 0)

        assertEquals(3, view.tileCountForTest())
        assertTrue("the first tile in the custom order should be focused", view.isTileFocusedForTest(0))
        assertTrue(!view.isTileFocusedForTest(1) && !view.isTileFocusedForTest(2))

        // The packer guarantees no overlap by construction; this asserts the actual placements it
        // produced for this order match what a 4-column row-major pack of [WIDE, SMALL, SMALL]
        // must be: "c" (wide) takes columns 0-1, "a" and "b" fall into the two open columns behind
        // it, all on the same row since everything still fits in one.
        assertEquals(
            listOf(
                TilePlacement("c", TileSize.WIDE, col = 0, row = 0),
                TilePlacement("a", TileSize.SMALL, col = 2, row = 0),
                TilePlacement("b", TileSize.SMALL, col = 3, row = 0),
            ),
            view.placementsForTest(),
        )
    }

    @Test
    fun `clearing the layout falls back to install order and every tile at SMALL`() {
        TileLayoutStore.setEntries(context, emptyList())
        val installOrderEntries = listOf(launcherEntry("a"), launcherEntry("b"))

        val ordered = TileLayoutStore.applyOrder(context, installOrderEntries)
        assertEquals(listOf("a", "b"), ordered.map { it.id })

        val view = GridLauncherView(context).apply {
            setIconLoaderForTest { _, _ -> ColorDrawable(Color.BLACK) }
        }
        view.render(ordered, selectedIndex = 0)

        assertEquals(
            listOf(
                TilePlacement("a", TileSize.SMALL, col = 0, row = 0),
                TilePlacement("b", TileSize.SMALL, col = 1, row = 0),
            ),
            view.placementsForTest(),
        )
    }
}
