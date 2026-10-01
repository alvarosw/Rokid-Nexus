package com.anezium.rokidbus.hudtiles

import android.graphics.drawable.ColorDrawable
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSize
import org.junit.Assert.assertTrue

internal const val MIN = 60_000L

internal fun templateInput(content: TileContent?, name: String = "Plugin", sinceReceiptMs: Long = 0) =
    TileRenderInput(name = name, icon = ColorDrawable(0), content = content, receivedAtElapsed = 1_000_000L, nowElapsed = 1_000_000L + sinceReceiptMs)

internal fun TileLayout.text(part: TilePart): List<String> = texts(part).map { it.text }

/**
 * Every body and foot op stays inside the tile's padding box, and every body text inside the body
 * clip. The header row is the generic template's, checked by its own tests.
 */
internal fun assertInside(layout: TileLayout, label: String) {
    val pad = TileRenderer.PADDING.toFloat()
    (layout.body + layout.footer).filterIsInstance<TileOp.Text>().forEach { text ->
        assertTrue("$label: $text above the tile", text.top >= pad)
        assertTrue("$label: $text below the tile", text.bottom <= layout.height - pad)
        assertTrue("$label: $text left of the tile", text.left >= pad - 0.5f)
    }
    layout.body.filterIsInstance<TileOp.Text>().forEach { text ->
        assertTrue("$label: $text outside the body ${layout.bodyClip}", text.bottom <= layout.bodyClip.bottom && text.top >= layout.bodyClip.top)
    }
    layout.footer.filterIsInstance<TileOp.Track>().forEach { track ->
        assertTrue("$label: track inside", track.bottom <= layout.height - pad && track.right <= layout.width - pad)
    }
}

internal val ALL_SIZES = TileSize.entries
