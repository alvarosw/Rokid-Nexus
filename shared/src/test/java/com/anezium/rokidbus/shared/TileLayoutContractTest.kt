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
}
