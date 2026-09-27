package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WidgetTileModelsTest {
    private class FakeTransport : NexusPluginTransport {
        lateinit var listener: NexusPluginTransport.Listener
        val sends = mutableListOf<Pair<String, JSONObject>>()
        override fun connect(listener: NexusPluginTransport.Listener) { this.listener = listener }
        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sends += path to JSONObject(payload.toString())
            return true
        }
        override fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray): Boolean = false
        override fun capabilities(): Int = 0
        override fun approvedCapabilities(): String? = null
        override fun close() = Unit
    }

    private val callbacks = object : NexusPluginCallbacks {
        override fun onOpen() = Unit
        override fun onClose() = Unit
        override fun onInput(event: NexusInputEvent) = Unit
        override fun onLinkState(state: Int) = Unit
        override fun onRegistrationState(result: Int) = Unit
    }

    private fun client(capabilities: String): Pair<NexusPluginClient, FakeTransport> {
        val transport = FakeTransport()
        val client = NexusPluginClient("hello", callbacks, transport)
        client.connect()
        transport.listener.onMessage(
            BusPaths.PLUGIN_REGISTRATION,
            "registration-1",
            JSONObject()
                .put("pluginId", "hello")
                .put("result", PluginRegistrationResult.APPROVED)
                .put("capabilities", capabilities),
        )
        return client to transport
    }

    private fun snapshot() = TileSnapshot(
        pluginId = "hello",
        contentKey = "eta",
        title = "12 min",
        tone = TileTone.INFO,
    )

    @Test
    fun `publish with widget_tile capability sends the payload`() {
        val (client, transport) = client("widget_tile")
        val result = client.widgetTileSession("main").publish(snapshot())
        assertEquals(NexusSdkResult.SENT, result)
        val payload = transport.sends.single()
        assertEquals(BusPaths.TILE_PUBLISH, payload.first)
        assertEquals("12 min", payload.second.getString("title"))
        assertEquals("info", payload.second.getString("tone"))
    }

    @Test
    fun `publish without widget_tile capability is refused locally`() {
        val (client, transport) = client("surfaces")
        val result = client.widgetTileSession("main").publish(snapshot())
        assertEquals(NexusSdkResult.CAPABILITY_NOT_GRANTED, result)
        assertEquals(0, transport.sends.size)
    }

    @Test
    fun `unregistered client refuses to publish`() {
        val transport = FakeTransport()
        val client = NexusPluginClient("hello", callbacks, transport)
        client.connect()
        val result = client.widgetTileSession("main").publish(snapshot())
        assertEquals(NexusSdkResult.NOT_REGISTERED, result)
    }

    @Test
    fun `oversized snapshot fields fail construction locally`() {
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot(pluginId = "hello", contentKey = "x", title = "x".repeat(121))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot(pluginId = "hello", contentKey = "x".repeat(129), title = "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot(pluginId = "hello", contentKey = "x", title = "x", progress = 1.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot(pluginId = "hello", contentKey = "x", title = "x", rows = List(5) { "row" })
        }
    }
}
