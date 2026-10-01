package com.anezium.rokidbus.hudtiles

import android.graphics.drawable.Drawable
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileTone

/**
 * Everything a tile's drawing depends on. Times are `elapsedRealtime` on the device that draws:
 * a template's time-relative fields count from [receivedAtElapsed], so neither device's wall clock
 * matters.
 */
data class TileRenderInput(
    /** The plugin's display name; the header shows it uppercase. */
    val name: String,
    val icon: Drawable? = null,
    /** Null draws the header only. */
    val content: TileContent? = null,
    val tone: TileTone = TileTone.OFF,
    /** A stale tile dims its content, never its header. */
    val stale: Boolean = false,
    val receivedAtElapsed: Long = 0L,
    val nowElapsed: Long = receivedAtElapsed,
    /** 0 at rest, 1 focused: the header and the title brighten toward `focus` with it. */
    val focusAmount: Float = 0f,
    /** Pixels the header leaves free at its right end, for a host's alert mark. */
    val headerEndInset: Int = 0,
)
