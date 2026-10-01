package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.tile.TileContent
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
        val binaries = mutableListOf<Pair<JSONObject, ByteArray>>()
        override fun connect(listener: NexusPluginTransport.Listener) { this.listener = listener }
        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sends += path to JSONObject(payload.toString())
            return true
        }
        override fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray): Boolean {
            binaries += JSONObject(payload.toString()) to data
            return path == BusPaths.TILE_PUBLISH
        }
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
    fun `publish sends a templated snapshot with its template and the legacy fields`() {
        val (client, transport) = client("widget_tile")
        val music = TileSnapshot(
            pluginId = "hello",
            contentKey = "track",
            content = TileContent.Music(title = "Harbour Lights", artist = "Nova Reyes", playing = true),
        )
        assertEquals(NexusSdkResult.SENT, client.widgetTileSession("main").publish(music))
        val payload = transport.sends.single().second
        assertEquals("music", payload.getString("template"))
        assertEquals("Harbour Lights", payload.getString("title"))
        assertEquals("Nova Reyes", payload.getString("subtitle"))
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

    private fun cover(width: Int = 64, height: Int = 64): ByteArray = ByteArray(128).also { bytes ->
        byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(),
            0x00, 0x11, 0x08,
            (height ushr 8).toByte(), height.toByte(),
            (width ushr 8).toByte(), width.toByte(),
            0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00,
            0xff.toByte(), 0xd9.toByte(),
        ).copyInto(bytes)
    }

    private fun track(key: String) = TileSnapshot(
        pluginId = "hello",
        contentKey = "track",
        content = TileContent.Music(title = "Harbour Lights", playing = true, artworkKey = key),
    )

    @Test
    fun `artwork bytes cross once per artworkKey`() {
        val (client, transport) = client("widget_tile")
        val session = client.widgetTileSession("main")
        assertEquals(NexusSdkResult.SENT, session.publish(track("a"), cover()))
        val (payload, bytes) = transport.binaries.single()
        assertEquals("music", payload.getString("template"))
        assertEquals("image/jpeg", payload.getJSONObject("artwork").getString("mimeType"))
        assertEquals(128, bytes.size)
        assertEquals(NexusSdkResult.SENT, session.publish(track("a"), cover()))
        assertEquals(1, transport.binaries.size)
        assertEquals(1, transport.sends.size)
        assertEquals(false, transport.sends.single().second.has("artwork"))
        assertEquals(NexusSdkResult.SENT, session.publish(track("b"), cover()))
        assertEquals(2, transport.binaries.size)
    }

    @Test
    fun `artwork is refused without a music artworkKey or with bytes over the limits`() {
        val (client, transport) = client("widget_tile")
        val session = client.widgetTileSession("main")
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, session.publish(snapshot(), cover()))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, session.publish(track(""), cover()))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, session.publish(track("a"), cover(width = 512)))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, session.publish(track("a"), ByteArray(16)))
        assertEquals(0, transport.binaries.size + transport.sends.size)
        val (denied, _) = client("")
        assertEquals(NexusSdkResult.CAPABILITY_NOT_GRANTED, denied.widgetTileSession("main").publish(track("a"), cover()))
    }
}
