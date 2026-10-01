package com.anezium.rokidbus.glasses

import android.content.Intent
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.HudModeContract
import com.anezium.rokidbus.shared.TileLayoutContract
import com.anezium.rokidbus.glasses.hud.GridHome
import com.anezium.rokidbus.glasses.hud.HomeLayer
import com.anezium.rokidbus.glasses.hud.HomeMode
import com.anezium.rokidbus.glasses.hud.HudMotionDriver
import com.anezium.rokidbus.shared.tile.TileGridLayout
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileSize
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The hub's launcher-facing surface from docs/ui-rewrite/01-current-behavior.md §7.2, driven through
 * the real ingress ([GlassesHub.onRemoteEnvelope]) with the link replaced by
 * [GlassesHubTestSupport].
 */
@RunWith(RobolectricTestRunner::class)
class GlassesHubLauncherTest {
    private val context = RuntimeEnvironment.getApplication()
    private val seen = ArrayList<List<GlassesHub.LauncherEntry>>()
    private var stop: (() -> Unit)? = null

    @Before
    fun setUp() {
        GlassesHubTestSupport.install(context)
        HudModeStore.setGridModeEnabled(context, false)
        TileLayoutStore.setEntries(context, emptyList())
        seen.clear()
        stop = GlassesHub.observeLauncher { seen += it }
        seen.clear()
    }

    @After
    fun tearDown() {
        stop?.invoke()
        GlassesHubTestSupport.uninstall()
    }

    // ---- item 36 -------------------------------------------------------------------------

    @Test
    fun item36_a_blank_id_is_refused_before_anything_is_sent() {
        assertEquals("launcherOpen=false reason=blank", GlassesHub.openLauncherEntry(""))
        assertEquals("launcherOpen=false reason=blank", GlassesHub.openLauncherEntry("   "))
        assertTrue(GlassesHubTestSupport.sent.isEmpty())
    }

    @Test
    fun item36_the_camera_entry_without_a_hub_context_is_hub_not_started() {
        GlassesHubTestSupport.uninstall()
        assertEquals("launcherOpen=false reason=hub_not_started", GlassesHub.openLauncherEntry("camera"))
    }

    @Test
    fun item36_a_send_error_is_reported_as_the_error_code_and_success_names_the_plugin() {
        assertEquals("launcherOpen=true pluginId=alpha", GlassesHub.openLauncherEntry("alpha"))
        val open = GlassesHubTestSupport.sentOn(BusPaths.LAUNCHER_OPEN).single()
        assertEquals("alpha", open.payload.getString("pluginId"))

        GlassesHubTestSupport.linkUp = false
        val failed = GlassesHub.openLauncherEntry("alpha")
        assertEquals("launcherOpen=false pluginId=alpha code=NO_LINK", failed)
    }

    @Test
    fun item36_38_the_camera_entry_starts_the_camera_activity_and_sends_no_launcher_open() {
        assertEquals("launcherOpen=true pluginId=camera", GlassesHub.openLauncherEntry("camera"))
        assertTrue(GlassesHubTestSupport.sentOn(BusPaths.LAUNCHER_OPEN).isEmpty())
        val started: Intent = shadowOf(context).nextStartedActivity
        assertEquals(CameraActivity::class.java.name, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    // ---- item 44 -------------------------------------------------------------------------

    @Test
    fun item44_a_valid_hud_mode_config_is_stored_and_switches_the_tile_subsystem() {
        GlassesHubTestSupport.receive(BusPaths.HUD_MODE_CONFIG, HudModeContract.configToJson(true))
        assertTrue(HudModeStore.isGridModeEnabled(context))
        assertTrue("grid mode starts the tile subsystem (item 46)", TileController.isActive)

        GlassesHubTestSupport.receive(BusPaths.HUD_MODE_CONFIG, HudModeContract.configToJson(false))
        assertFalse(HudModeStore.isGridModeEnabled(context))
        assertFalse("list mode stops it", TileController.isActive)
    }

    @Test
    fun item44_an_invalid_hud_mode_payload_leaves_the_stored_mode_unchanged() {
        GlassesHubTestSupport.receive(BusPaths.HUD_MODE_CONFIG, HudModeContract.configToJson(true))
        val invalid = listOf(
            JSONObject(),
            JSONObject().put("version", 0).put("mode", "list"),
            JSONObject().put("version", 1).put("mode", "carousel"),
            JSONObject().put("version", 1),
        )
        invalid.forEach { payload ->
            GlassesHubTestSupport.receive(BusPaths.HUD_MODE_CONFIG, payload)
            assertTrue("payload $payload changed the mode", HudModeStore.isGridModeEnabled(context))
            assertTrue(TileController.isActive)
        }
    }

    // ---- item 48 -------------------------------------------------------------------------

    @Test
    fun item48_launcher_list_skips_blank_ids_defaults_the_name_and_drops_a_blank_icon_key() {
        val plugins = JSONArray()
            .put(JSONObject().put("id", "alpha").put("displayName", "Alpha").put("iconKey", "lens"))
            .put(JSONObject().put("id", "").put("displayName", "Nameless"))
            .put(JSONObject().put("id", "   ").put("displayName", "Blank"))
            .put(JSONObject().put("id", "beta"))
            .put(JSONObject().put("id", "gamma").put("displayName", "Gamma").put("iconKey", ""))
            .put("not an object")
        GlassesHubTestSupport.receive(BusPaths.LAUNCHER_LIST, JSONObject().put("plugins", plugins))

        val entries = seen.last()
        assertEquals(
            listOf(
                GlassesHub.LauncherEntry("alpha", "Alpha", "lens"),
                GlassesHub.LauncherEntry("beta", "beta", null),
                GlassesHub.LauncherEntry("gamma", "Gamma", null),
            ),
            entries,
        )
    }

    @Test
    fun item48_a_list_without_plugins_is_an_empty_launcher() {
        GlassesHubTestSupport.launcherList("alpha")
        GlassesHubTestSupport.receive(BusPaths.LAUNCHER_LIST, JSONObject())
        assertTrue(seen.last().isEmpty())
    }

    // ---- item 49 -------------------------------------------------------------------------

    @Test
    fun item49_a_tile_layout_push_re_notifies_launcher_observers_in_reading_order() {
        GlassesHubTestSupport.launcherList("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), seen.last().map { it.id })
        val before = seen.size

        val layout = listOf(
            TileLayoutEntry("c", TileSize.WIDE, col = 0, row = 0),
            TileLayoutEntry("a", TileSize.SMALL, col = 2, row = 0),
        )
        GlassesHubTestSupport.receive(BusPaths.TILE_LAYOUT_CONFIG, TileLayoutContract.configToJson(layout))

        assertEquals("one re-notification", before + 1, seen.size)
        assertEquals(listOf("c", "a", "b"), seen.last().map { it.id })
    }

    @Test
    fun a_stored_v2_layout_orders_the_launcher_by_position_camera_included() {
        GlassesHubTestSupport.launcherList("a", "b", "c")
        GlassesHubTestSupport.advertiseCamera("Lens")
        assertEquals("no layout: camera first, then the catalog", listOf("camera", "a", "b", "c"), seen.last().map { it.id })

        // Holes are fine; "b" is not in the layout and takes the first free cell behind the pinned ones.
        val layout = listOf(
            TileLayoutEntry("c", TileSize.SMALL, col = 0, row = 0),
            TileLayoutEntry("camera", TileSize.BANNER, col = 0, row = 2),
            TileLayoutEntry("a", TileSize.SMALL, col = 3, row = 0),
        )
        GlassesHubTestSupport.receive(BusPaths.TILE_LAYOUT_CONFIG, TileLayoutContract.configToJson(layout))

        assertEquals(listOf("c", "b", "a", "camera"), seen.last().map { it.id })
    }

    @Test
    fun the_grid_draws_the_placements_the_hub_resolved_for_mixed_size_unpinned_tiles() {
        GlassesHubTestSupport.launcherList("a", "b", "c", "d")
        GlassesHubTestSupport.advertiseCamera("Lens")
        // "b" and "a" are pinned; "c" is a TALL whose stored cell is taken, so it is unpinned, as are
        // "d" and the camera (SMALL): three unpinned tiles of two sizes around the pinned ones.
        val stored = listOf(
            TileLayoutEntry("b", TileSize.SMALL, col = 0, row = 0),
            TileLayoutEntry("a", TileSize.SMALL, col = 1, row = 1),
            TileLayoutEntry("c", TileSize.TALL, col = 0, row = 0),
        )
        GlassesHubTestSupport.receive(BusPaths.TILE_LAYOUT_CONFIG, TileLayoutContract.configToJson(stored))

        val expected = TileGridLayout.resolve(
            listOf("camera", "a", "b", "c", "d").map { it to null },
            stored,
        )
        val layer = HomeLayer(
            context,
            iconLoader = { _, _ -> android.graphics.drawable.ColorDrawable(0) },
            tileSource = { null },
            motion = HudMotionDriver.instant(),
        )
        layer.show(HomeMode.GRID, seen.last(), seen.last().first().id)
        val drawn = (layer.screenForTest() as GridHome).placementsForTest()

        assertEquals(expected.sortedBy { it.pluginId }, drawn.sortedBy { it.pluginId })
        assertEquals(TileGridLayout.readingOrder(expected).map { it.pluginId }, seen.last().map { it.id })
    }

    @Test
    fun the_list_mode_order_is_the_same_reading_order() {
        HudModeStore.setGridModeEnabled(context, false)
        GlassesHubTestSupport.launcherList("a", "b")
        val layout = listOf(
            TileLayoutEntry("b", TileSize.SMALL, col = 0, row = 0),
            TileLayoutEntry("a", TileSize.SMALL, col = 1, row = 0),
        )
        GlassesHubTestSupport.receive(BusPaths.TILE_LAYOUT_CONFIG, TileLayoutContract.configToJson(layout))
        assertEquals(listOf("b", "a"), seen.last().map { it.id })
    }

    @Test
    fun system_widgets_are_placed_but_never_launcher_entries_so_the_ring_skips_them() {
        GlassesHubTestSupport.launcherList("a", "b", "c")
        val layout = listOf(
            TileLayoutEntry("a", TileSize.SMALL, col = 0, row = 0),
            TileLayoutEntry("sys:clock", TileSize.WIDE, col = 1, row = 0),
            TileLayoutEntry("b", TileSize.SMALL, col = 3, row = 0),
            TileLayoutEntry("sys:status", TileSize.SMALL, col = 0, row = 1),
            TileLayoutEntry("sys:radar", TileSize.SMALL, col = 1, row = 1),
            TileLayoutEntry("c", TileSize.SMALL, col = 2, row = 1),
        )
        GlassesHubTestSupport.receive(BusPaths.TILE_LAYOUT_CONFIG, TileLayoutContract.configToJson(layout))

        val ids = seen.last().map { it.id }
        assertEquals(listOf("a", "b", "c"), ids)
        assertEquals(
            setOf("a", "b", "c", "sys:clock", "sys:status"),
            GlassesHub.launcherPlacements().map { it.pluginId }.toSet(),
        )

        // The machine only ever sees entries: Next walks a, b, c and wraps, never onto a widget.
        val h = com.anezium.rokidbus.glasses.hud.Harness(entries = ids)
        h.open()
        val visited = (0 until 4).map { h.home().selectedId.also { h.next() } }
        assertEquals(listOf("a", "b", "c", "a"), visited)

        // The grid draws the widgets the hub resolved, apart from the entries.
        val layer = HomeLayer(
            context,
            iconLoader = { _, _ -> android.graphics.drawable.ColorDrawable(0) },
            tileSource = { null },
            motion = HudMotionDriver.instant(),
        )
        layer.show(HomeMode.GRID, seen.last(), "a")
        assertEquals(listOf("a", "b", "c"), layer.currentModel.placements.map { it.pluginId })
        assertEquals(listOf("sys:clock", "sys:status"), layer.currentModel.widgets.map { it.pluginId })
    }

    @Test
    fun a_layout_of_widgets_only_leaves_the_launcher_empty() {
        GlassesHubTestSupport.launcherList()
        val layout = listOf(TileLayoutEntry("sys:clock", TileSize.LARGE, col = 0, row = 0))
        GlassesHubTestSupport.receive(BusPaths.TILE_LAYOUT_CONFIG, TileLayoutContract.configToJson(layout))
        assertTrue(seen.last().isEmpty())
        assertEquals(listOf("sys:clock"), GlassesHub.launcherPlacements().map { it.pluginId })
    }

    @Test
    fun item49_an_invalid_tile_layout_notifies_nobody() {
        GlassesHubTestSupport.launcherList("a", "b")
        val before = seen.size
        GlassesHubTestSupport.receive(BusPaths.TILE_LAYOUT_CONFIG, JSONObject().put("garbage", true))
        assertEquals(before, seen.size)
    }
}
