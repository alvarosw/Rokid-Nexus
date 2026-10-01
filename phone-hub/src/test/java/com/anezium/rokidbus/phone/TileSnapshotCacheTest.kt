package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TileSnapshotCacheTest {
    @After
    fun clear() = TileSnapshotCache.clear()

    private fun payload(pluginId: String, title: String = "Hello") =
        WidgetTileContract.toPayload(TileSnapshot(pluginId = pluginId, contentKey = "k", title = title))

    @Test
    fun `a recorded snapshot is returned for the stamped plugin`() {
        TileSnapshotCache.record("clock", payload("clock", "12:00"))
        assertEquals("12:00", (TileSnapshotCache.get("clock")?.content as? TileContent.Generic)?.title)
    }

    @Test
    fun `the payload's own pluginId is never trusted`() {
        TileSnapshotCache.record("mallory", payload("victim", "spoofed"))
        assertNull(TileSnapshotCache.get("victim"))
        assertEquals("mallory", TileSnapshotCache.get("mallory")?.pluginId)
    }

    @Test
    fun `a malformed payload records nothing`() {
        TileSnapshotCache.record("clock", JSONObject().put("title", "x".repeat(WidgetTileContract.MAX_TITLE_CHARS + 1)))
        assertNull(TileSnapshotCache.get("clock"))
    }

    @Test
    fun `the cache is bounded and drops the least recently used plugin`() {
        repeat(TileSnapshotCache.MAX_ENTRIES + 1) { TileSnapshotCache.record("p$it", payload("p$it")) }
        assertNull(TileSnapshotCache.get("p0"))
        assertNotNull(TileSnapshotCache.get("p1"))
        assertNotNull(TileSnapshotCache.get("p${TileSnapshotCache.MAX_ENTRIES}"))
    }

    @Test
    fun `remove forgets one plugin only`() {
        TileSnapshotCache.record("a", payload("a"))
        TileSnapshotCache.record("b", payload("b"))
        TileSnapshotCache.remove("a")
        assertNull(TileSnapshotCache.get("a"))
        assertNotNull(TileSnapshotCache.get("b"))
    }

    @Test
    fun `retainOnly drops every plugin outside the given set`() {
        TileSnapshotCache.record("a", payload("a"))
        TileSnapshotCache.record("b", payload("b"))
        TileSnapshotCache.retainOnly(setOf("b"))
        assertNull(TileSnapshotCache.get("a"))
        assertNotNull(TileSnapshotCache.get("b"))
    }
}
