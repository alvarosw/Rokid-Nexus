package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.glasses.hud.HudMotionDriver
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.anezium.rokidbus.glasses.hud.GridHome
import com.anezium.rokidbus.glasses.hud.HomeLayer
import com.anezium.rokidbus.glasses.hud.HomeMode
import com.anezium.rokidbus.shared.TileLayoutContract
import com.anezium.rokidbus.shared.tile.TileGridLayout
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
 * `GlassesHub`'s `TILE_LAYOUT_CONFIG`/`LAUNCHER_LIST` handlers call), and [HomeLayer] in grid mode
 * (the real view the accessibility overlay renders). This does not go through `GlassesHub` itself
 * — its `start()` loads the vendor CXR native library unconditionally, which is not available to a
 * JVM test — so this test drives the same downstream calls `GlassesHub` makes once a config
 * envelope is parsed.
 */
@RunWith(RobolectricTestRunner::class)
class TileLayoutIntegrationTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun launcherEntry(id: String) = GlassesHub.LauncherEntry(id = id, displayName = id)

    // Real icon resolution needs the app's resources; a flat drawable keeps this about layout.
    private fun gridWith(entries: List<GlassesHub.LauncherEntry>, selectedId: String): GridHome {
        val layer = HomeLayer(context, iconLoader = { _, _ -> ColorDrawable(Color.BLACK) }, motion = HudMotionDriver.instant())
        layer.show(HomeMode.GRID, entries, selectedId)
        return layer.screenForTest() as GridHome
    }

    /** Exactly what GlassesHub.allLauncherEntries() does with the entries it is given. */
    private fun readingOrder(entries: List<GlassesHub.LauncherEntry>): List<GlassesHub.LauncherEntry> {
        val byId = entries.associateBy { it.id }
        return TileGridLayout.readingOrder(TileLayoutStore.placements(context, entries)).map { byId.getValue(it.pluginId) }
    }

    @Test
    fun `a synced layout keeps its positions and holes, and a newly installed plugin takes the first free cell`() {
        // The phone's layout: "c" wide at the top right, "a" small below it, a hole in between.
        // "b" doesn't exist yet from the phone's point of view.
        val phoneSideEntries = listOf(
            TileLayoutEntry("c", TileSize.WIDE, col = 2, row = 0),
            TileLayoutEntry("a", TileSize.SMALL, col = 1, row = 2),
        )

        // Exactly what crosses the wire, and exactly what GlassesHub.onRemoteEnvelope does with the
        // TILE_LAYOUT_CONFIG payload it receives.
        val wirePayload = TileLayoutContract.configToJson(phoneSideEntries)
        val parsedEntries = requireNotNull(TileLayoutContract.entriesFromConfig(wirePayload))
        TileLayoutStore.setEntries(context, parsedEntries)

        val installOrderEntries = listOf(launcherEntry("a"), launcherEntry("b"), launcherEntry("c"))
        val ordered = readingOrder(installOrderEntries)
        assertEquals(listOf("b", "c", "a"), ordered.map { it.id })

        val view = gridWith(ordered, selectedId = ordered.first().id)

        assertEquals(listOf("b", "c", "a"), view.tileIdsForTest())
        assertTrue("the first tile in reading order should be focused", view.isTileFocusedForTest("b"))
        assertTrue(!view.isTileFocusedForTest("a") && !view.isTileFocusedForTest("c"))
        assertEquals(
            listOf(
                TilePlacement("b", TileSize.SMALL, col = 0, row = 0),
                TilePlacement("c", TileSize.WIDE, col = 2, row = 0),
                TilePlacement("a", TileSize.SMALL, col = 1, row = 2),
            ),
            view.placementsForTest(),
        )
    }

    @Test
    fun `clearing the layout falls back to install order and every tile at SMALL`() {
        TileLayoutStore.setEntries(context, emptyList())
        val installOrderEntries = listOf(launcherEntry("a"), launcherEntry("b"))

        val ordered = readingOrder(installOrderEntries)
        assertEquals(listOf("a", "b"), ordered.map { it.id })

        val view = gridWith(ordered, selectedId = ordered.first().id)

        assertEquals(
            listOf(
                TilePlacement("a", TileSize.SMALL, col = 0, row = 0),
                TilePlacement("b", TileSize.SMALL, col = 1, row = 0),
            ),
            view.placementsForTest(),
        )
    }
}
