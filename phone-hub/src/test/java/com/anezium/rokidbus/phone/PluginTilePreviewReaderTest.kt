package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginTilePreviewReaderTest {
    private fun bytes(json: String) = json.toByteArray(Charsets.UTF_8)

    @Test
    fun `a sample decodes as a publish and is stamped with the plugin's own id`() {
        val sample = TileSnapshot(
            pluginId = "someone-else",
            contentKey = "sample",
            content = TileContent.Music(title = "Harbour Lights", artist = "Nova Reyes", playing = true),
        )
        val decoded = PluginTilePreviewReader.parse("media", bytes(WidgetTileContract.toPayload(sample).toString()))!!
        assertEquals("media", decoded.pluginId)
        assertEquals(sample.content, decoded.content)
    }

    @Test
    fun `a legacy payload without a template is a generic sample`() {
        val decoded = PluginTilePreviewReader.parse("transit", bytes("""{"contentKey":"k","title":"12","unit":"min"}"""))!!
        assertEquals(TileContent.Generic(title = "12", unit = "min"), decoded.content)
    }

    @Test
    fun `an invalid sample is ignored, never thrown`() {
        assertNull(PluginTilePreviewReader.parse("p.x", bytes("not json")))
        assertNull(PluginTilePreviewReader.parse("p.x", bytes("[1, 2]")))
        assertNull(PluginTilePreviewReader.parse("p.x", bytes("""{"title":"${"x".repeat(WidgetTileContract.MAX_TITLE_CHARS + 1)}"}""")))
        assertNull(PluginTilePreviewReader.parse("p.x", ByteArray(WidgetTileContract.MAX_PAYLOAD_BYTES + 1) { ' '.code.toByte() }))
    }
}
