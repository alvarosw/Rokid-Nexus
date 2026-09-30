package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileSize
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileLayoutContractTest {
    @Test
    fun `entries round-trip through json in order`() {
        val entries = listOf(
            TileLayoutEntry("weather", TileSize.WIDE, col = 0, row = 0),
            TileLayoutEntry("clock", TileSize.SMALL, col = 2, row = 0),
        )
        assertEquals(entries, TileLayoutContract.entriesFromConfig(TileLayoutContract.configToJson(entries)))
    }

    @Test
    fun `empty list round-trips to an empty list, not an error`() {
        assertEquals(emptyList<TileLayoutEntry>(), TileLayoutContract.entriesFromConfig(TileLayoutContract.configToJson(emptyList())))
    }

    @Test
    fun `unrecognized or missing payload reads as no answer`() {
        assertNull(TileLayoutContract.entriesFromConfig(null))
        assertNull(
            TileLayoutContract.entriesFromConfig(
                JSONObject().put("version", 0).put("entries", JSONArray()),
            ),
        )
        assertNull(TileLayoutContract.entriesFromConfig(JSONObject().put("version", 1)))
    }

    @Test
    fun `a malformed entry is dropped, not the whole config`() {
        val payload = JSONObject()
            .put("version", 1)
            .put(
                "entries",
                JSONArray()
                    .put(JSONObject().put("pluginId", "weather").put("size", "1x1"))
                    .put(JSONObject().put("pluginId", "").put("size", "1x1"))
                    .put(JSONObject().put("pluginId", "clock").put("size", "not-a-size")),
            )
        val entries = TileLayoutContract.entriesFromConfig(payload)
        assertTrue(entries != null && entries.size == 1 && entries.single().pluginId == "weather")
    }

    @Test
    fun `version 1 payloads are still accepted`() {
        val payload = JSONObject()
            .put("version", 1)
            .put(
                "entries",
                JSONArray().put(
                    JSONObject().put("pluginId", "weather").put("size", "2x1").put("col", 2).put("row", 1),
                ),
            )
        assertEquals(
            listOf(TileLayoutEntry("weather", TileSize.WIDE, col = 2, row = 1)),
            TileLayoutContract.entriesFromConfig(payload),
        )
    }

    @Test
    fun `config is written as version 2 and keeps positions`() {
        val json = TileLayoutContract.configToJson(listOf(TileLayoutEntry("a", TileSize.SMALL, col = 3, row = 5)))
        assertEquals(2, json.getInt("version"))
        val item = json.getJSONArray("entries").getJSONObject(0)
        assertEquals(3, item.getInt("col"))
        assertEquals(5, item.getInt("row"))
    }

    @Test
    fun `the 3-wide sizes round-trip`() {
        val entries = listOf(
            TileLayoutEntry("a", TileSize.BANNER, col = 0, row = 0),
            TileLayoutEntry("b", TileSize.PANEL, col = 1, row = 1),
            TileLayoutEntry("c", TileSize.JUMBO, col = 0, row = 3),
        )
        assertEquals(entries, TileLayoutContract.entriesFromConfig(TileLayoutContract.configToJson(entries)))
    }
}
