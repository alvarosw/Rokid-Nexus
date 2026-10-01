package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileSize
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileLeaseControllerTest {
    private class FakeScheduler : ExternalPluginScheduler {
        val actions = linkedMapOf<String, () -> Unit>()
        val delays = linkedMapOf<String, Long>()
        override fun schedule(key: String, delayMs: Long, action: () -> Unit) {
            actions[key] = action
            delays[key] = delayMs
        }
        override fun cancel(key: String) {
            actions.remove(key)
            delays.remove(key)
        }
        fun run(prefix: String) {
            val key = actions.keys.first { it.startsWith(prefix) }
            val action = actions.remove(key)!!
            delays.remove(key)
            action()
        }
    }

    private class FakeRuntime : TileLeaseRuntime {
        var bindResult = true
        val registered = mutableSetOf<String>()
        val deliveries = mutableListOf<Pair<String, JSONObject>>()
        val bound = mutableListOf<String>()
        val unbound = mutableListOf<String>()
        override fun bind(principal: PhonePluginPrincipal): Boolean {
            bound += principal.descriptor.id
            return bindResult
        }
        override fun isRegistered(principal: PhonePluginPrincipal): Boolean = principal.descriptor.id in registered
        override fun deliver(principal: PhonePluginPrincipal, path: String, id: String, payload: JSONObject): Boolean {
            deliveries += path to JSONObject(payload.toString())
            return principal.descriptor.id in registered
        }
        override fun unbind(principal: PhonePluginPrincipal) { unbound += principal.descriptor.id }

        fun events(): List<String> = deliveries.map { (path, payload) ->
            when (path) {
                BusPaths.PLUGIN_TILE_ACTIVE -> "${payload.getString("pluginId")}:active=${payload.getBoolean("active")}"
                BusPaths.PLUGIN_TILE_REFRESH -> "${payload.getString("pluginId")}:refresh"
                else -> path
            }
        }
    }

    private var now = 0L
    private val runtime = FakeRuntime()
    private val scheduler = FakeScheduler()
    private val journal = PluginBusJournal().apply { enabled.set(true) }
    private val controller = TileLeaseController(runtime, scheduler, nowMs = { now }, journal = journal)

    private fun principal(id: String = "media") = PhonePluginPrincipal(
        packageName = "dev.example.$id",
        serviceComponent = ComponentName("dev.example.$id", "dev.example.$id.Service"),
        uid = 10,
        signingDigestSha256 = "digest-$id",
        descriptor = PluginDescriptor(
            id, id, 3, setOf(PluginCapability.SURFACES, PluginCapability.WIDGET_TILE),
            listOf("/plugin/$id", "/system/plugin"), null, true,
        ),
    )

    @Test
    fun `a dormant plugin is bound and told its lease began once it registers`() {
        val media = principal()
        controller.update(listOf(media))
        assertEquals(listOf("media"), runtime.bound)
        assertTrue(runtime.deliveries.isEmpty())

        runtime.registered += "media"
        controller.onRegistered(media)

        assertEquals(listOf("media:active=true", "media:refresh"), runtime.events())
        assertEquals(setOf("media"), controller.activePluginIds())
    }

    @Test
    fun `an already registered plugin gets its lease at once`() {
        runtime.registered += "media"
        controller.update(listOf(principal()))
        assertEquals(listOf("media:active=true", "media:refresh"), runtime.events())
    }

    @Test
    fun `the lease ends when its plugin leaves the inputs`() {
        val media = principal()
        runtime.registered += "media"
        controller.update(listOf(media))
        controller.update(emptyList())
        assertEquals(listOf("media:active=true", "media:refresh", "media:active=false"), runtime.events())
        assertEquals(listOf("media"), runtime.unbound)
        assertTrue(controller.activePluginIds().isEmpty())
        assertTrue(scheduler.actions.isEmpty())
    }

    @Test
    fun `an unchanged input set grants nothing twice`() {
        runtime.registered += "media"
        controller.update(listOf(principal()))
        controller.update(listOf(principal()))
        assertEquals(listOf("media"), runtime.bound)
        assertEquals(2, runtime.deliveries.size)
    }

    @Test
    fun `refreshes are never closer together than the interval`() {
        runtime.registered += "media"
        controller.update(listOf(principal()))
        now = 60_000L
        controller.onHomeVisible()
        assertEquals(1, runtime.events().count { it == "media:refresh" })
        assertEquals(
            TileLeaseController.REFRESH_INTERVAL_MS - 60_000L,
            scheduler.delays.getValue("tile-refresh:dev.example.media:media"),
        )

        now = TileLeaseController.REFRESH_INTERVAL_MS
        scheduler.run("tile-refresh:")
        assertEquals(2, runtime.events().count { it == "media:refresh" })
        assertEquals(TileLeaseController.REFRESH_INTERVAL_MS, scheduler.delays.getValue("tile-refresh:dev.example.media:media"))
    }

    @Test
    fun `the home becoming visible refreshes a lease whose last refresh is old enough`() {
        runtime.registered += "media"
        controller.update(listOf(principal()))
        now = TileLeaseController.REFRESH_INTERVAL_MS + 1
        controller.onHomeVisible()
        assertEquals(2, runtime.events().count { it == "media:refresh" })
    }

    @Test
    fun `a lease regained soon after it ended does not refresh early`() {
        runtime.registered += "media"
        controller.update(listOf(principal()))
        controller.update(emptyList())
        now = 30_000L
        controller.update(listOf(principal()))
        assertEquals(
            listOf("media:active=true", "media:refresh", "media:active=false", "media:active=true"),
            runtime.events(),
        )
    }

    @Test
    fun `a plugin that never registers is unbound and not rebound until the lease ends`() {
        val media = principal()
        controller.update(listOf(media))
        scheduler.run("tile-registration:")
        assertEquals(listOf("media"), runtime.unbound)
        controller.update(listOf(media))
        assertEquals(listOf("media"), runtime.bound)
        controller.update(emptyList())
        controller.update(listOf(media))
        assertEquals(listOf("media", "media"), runtime.bound)
    }

    @Test
    fun `a bind failure is recorded and not retried while the lease stands`() {
        runtime.bindResult = false
        controller.update(listOf(principal()))
        controller.update(listOf(principal()))
        assertEquals(listOf("media"), runtime.bound)
        assertTrue(journal.snapshot().any { it.reason == "BIND_FAILED" })
    }

    @Test
    fun `a restarted plugin is told again after its binder died`() {
        val media = principal()
        runtime.registered += "media"
        controller.update(listOf(media))
        controller.onBinderDied(media.grantKey())
        assertTrue(controller.activePluginIds().isEmpty())
        controller.onRegistered(media)
        assertEquals(
            listOf("media:active=true", "media:refresh", "media:active=true"),
            runtime.events(),
        )
    }

    @Test
    fun `lease changes and refreshes are journaled`() {
        runtime.registered += "media"
        controller.update(listOf(principal()))
        controller.update(emptyList())
        val reasons = journal.snapshot().map { it.path to it.reason }
        assertEquals(
            listOf(
                BusPaths.PLUGIN_TILE_ACTIVE to "LEASE_GRANTED",
                BusPaths.PLUGIN_TILE_REFRESH to "REFRESH",
                BusPaths.PLUGIN_TILE_ACTIVE to "LEASE_ENDED",
            ),
            reasons,
        )
    }

    @Test
    fun `close ends every lease`() {
        runtime.registered += setOf("media", "relay")
        controller.update(listOf(principal("media"), principal("relay")))
        controller.close()
        assertEquals(listOf("media", "relay"), runtime.unbound)
        assertTrue(runtime.events().containsAll(listOf("media:active=false", "relay:active=false")))
        controller.update(listOf(principal("media")))
        assertEquals(listOf("media", "relay"), runtime.bound)
    }

    @Test
    fun `policy leases placed launchable plugins with widget_tile while grid and link are up`() {
        val media = principal("media")
        val feeds = principal("feeds")
        val launchable = listOf(media, feeds)
        val granted: (PhonePluginPrincipal) -> Boolean = { it.descriptor.id == "media" }

        assertEquals(
            listOf(media),
            TileLeasePolicy.leasedPlugins(true, true, launchable, emptyList(), granted),
        )
        assertTrue(TileLeasePolicy.leasedPlugins(false, true, launchable, emptyList(), granted).isEmpty())
        assertTrue(TileLeasePolicy.leasedPlugins(true, false, launchable, emptyList(), granted).isEmpty())
        assertTrue(TileLeasePolicy.leasedPlugins(true, true, listOf(feeds), emptyList(), granted).isEmpty())
    }

    @Test
    fun `policy follows the glasses' placement of a stored layout`() {
        val media = principal("media")
        val stored = listOf(TileLayoutEntry(pluginId = "other", size = TileSize.LARGE, col = 0, row = 0))
        assertEquals(
            listOf(media),
            TileLeasePolicy.leasedPlugins(true, true, listOf(media), stored) { true },
        )
    }
}
