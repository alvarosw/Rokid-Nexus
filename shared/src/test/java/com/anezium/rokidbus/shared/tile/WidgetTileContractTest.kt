package com.anezium.rokidbus.shared.tile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetTileContractTest {
    private fun snapshot() = TileSnapshot(
        pluginId = "transit",
        contentKey = "eta-42",
        title = "12",
        subtitle = "min to Downtown",
        badge = "!",
        progress = 0.5f,
        unit = "min",
        tone = TileTone.WARN,
        rows = listOf("Downtown 12m", "Uptown 4m"),
    )

    @Test
    fun `round trips through toPayload and fromPayload`() {
        val decoded = WidgetTileContract.fromPayload(WidgetTileContract.toPayload(snapshot()))
        assertEquals(snapshot(), decoded)
    }

    @Test
    fun `fromPayload rejects null and malformed payloads without throwing`() {
        assertNull(WidgetTileContract.fromPayload(null))
        assertNull(WidgetTileContract.fromPayload(JSONObject()))
        assertNull(WidgetTileContract.fromPayload(JSONObject().put("pluginId", "").put("title", "x")))
        assertNull(
            WidgetTileContract.fromPayload(
                JSONObject().put("pluginId", "x".repeat(129)).put("title", "x"),
            ),
        )
        assertNull(
            WidgetTileContract.fromPayload(
                JSONObject().put("pluginId", "p").put("title", "x".repeat(121)),
            ),
        )
    }

    @Test
    fun `fromPayload clamps out-of-range progress and unknown tone falls back to OFF`() {
        val decoded = WidgetTileContract.fromPayload(
            JSONObject()
                .put("pluginId", "p")
                .put("title", "x")
                .put("progress", 42.0)
                .put("tone", "not-a-tone"),
        )
        assertEquals(1f, decoded?.progress)
        assertEquals(TileTone.OFF, decoded?.tone)
    }

    @Test
    fun `fromPayload truncates rows past the cap rather than rejecting`() {
        val payload = JSONObject()
            .put("pluginId", "p")
            .put("title", "x")
            .put("rows", org.json.JSONArray(listOf("a", "b", "c", "d", "e")))
        val decoded = WidgetTileContract.fromPayload(payload)
        assertEquals(WidgetTileContract.MAX_ROWS, decoded?.rows?.size)
    }

    @Test
    fun `construction rejects fields past their bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot(pluginId = "", contentKey = "x", title = "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot(pluginId = "p", contentKey = "x".repeat(129), title = "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot(pluginId = "p", contentKey = "x", title = "x".repeat(121))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot(pluginId = "p", contentKey = "x", title = "x", badge = "x".repeat(25))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot(pluginId = "p", contentKey = "x", title = "x", rows = List(5) { "r" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot(pluginId = "p", contentKey = "x", title = "x", rows = listOf("x".repeat(121)))
        }
    }

    @Test
    fun `tone wire values match the five sanctioned Status states`() {
        assertTrue(TileTone.entries.map { it.wireValue }.containsAll(listOf("ok", "info", "warn", "critical", "off")))
        assertEquals(TileTone.CRITICAL, TileTone.fromWireValue("critical"))
        assertNull(TileTone.fromWireValue("bogus"))
    }
}
