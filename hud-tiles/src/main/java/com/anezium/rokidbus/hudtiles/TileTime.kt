package com.anezium.rokidbus.hudtiles

import com.anezium.rokidbus.client.ui.RokidHudTokens

/**
 * The time texts the templates draw, all counted on the drawing device's `elapsedRealtime`: a
 * relative age ("now", "1 min", "2 h", "3 d"; compact "1m"), a track clock ("1:42") and the list's
 * "Updated … ago" line. Each has the delay until its text next changes, for
 * [TileRenderer.nextChangeAtElapsed].
 */
internal object TileTime {
    private const val SECOND = 1_000L
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val UPDATED_STEP = 10 * SECOND

    fun age(ms: Long, compact: Boolean): String {
        val age = ms.coerceAtLeast(0)
        val (value, unit) = when {
            age < MINUTE -> return "now"
            age < HOUR -> age / MINUTE to if (compact) "m" else " min"
            age < DAY -> age / HOUR to if (compact) "h" else " h"
            else -> age / DAY to if (compact) "d" else " d"
        }
        return "$value$unit"
    }

    /** Milliseconds until [age]'s text changes. */
    fun untilAgeChanges(ms: Long): Long {
        val age = ms.coerceAtLeast(0)
        return when {
            age < MINUTE -> MINUTE - age
            age < HOUR -> MINUTE - age % MINUTE
            age < DAY -> HOUR - age % HOUR
            else -> DAY - age % DAY
        }
    }

    /** "Updated now", then in 10 s steps for the first minute, then as an age. */
    fun updated(ms: Long): String {
        val age = ms.coerceAtLeast(0)
        return when {
            age < UPDATED_STEP -> "Updated now"
            age < MINUTE -> "Updated ${age / UPDATED_STEP * 10} s ago"
            else -> "Updated ${age(age, compact = false)} ago"
        }
    }

    fun untilUpdatedChanges(ms: Long): Long {
        val age = ms.coerceAtLeast(0)
        return if (age < MINUTE) UPDATED_STEP - age % UPDATED_STEP else untilAgeChanges(age)
    }

    /** "1:42", or "1:02:07" past an hour. */
    fun clock(ms: Long): String {
        val total = ms.coerceAtLeast(0) / SECOND
        val hours = total / 3_600
        val minutes = total % 3_600 / 60
        val seconds = total % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    fun untilClockChanges(ms: Long): Long = SECOND - ms.coerceAtLeast(0) % SECOND
}

/** A track position published at receipt, advanced while playing and clamped to its duration. */
internal fun playbackPositionMs(
    positionMs: Long?,
    durationMs: Long?,
    playing: Boolean,
    input: TileRenderInput,
): Long? {
    val published = positionMs ?: return null
    val moved = if (playing) (input.nowElapsed - input.receivedAtElapsed).coerceAtLeast(0) else 0L
    val position = published + moved
    return if (durationMs != null && durationMs > 0) position.coerceAtMost(durationMs) else position
}

/** The inks a template draws with: dimmed when stale, the emphasized text brightening with focus. */
internal class TileInk(private val input: TileRenderInput) {
    private fun dim(color: Int): Int =
        if (input.stale) RokidHudTokens.scaleAlpha(color, GenericTileLayout.STALE_ALPHA) else color

    val primary: Int get() = dim(RokidHudTokens.TEXT_PRIMARY)
    val secondary: Int get() = dim(RokidHudTokens.TEXT_SECONDARY)
    val line: Int get() = dim(RokidHudTokens.LINE)
    val emphasis: Int get() = dim(focusInk(input.focusAmount, RokidHudTokens.TEXT_PRIMARY))
}
