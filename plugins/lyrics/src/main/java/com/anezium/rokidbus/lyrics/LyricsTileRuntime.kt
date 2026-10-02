package com.anezium.rokidbus.lyrics

import com.anezium.rokidbus.lyrics.contracts.LyricsSessionState
import com.anezium.rokidbus.lyrics.contracts.LyricsSnapshot
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import kotlin.math.abs

/**
 * Keeps the Lyrics grid tile current for as long as the hub's tile lease lasts. It follows the
 * runtime graph's state only between [start] and [stop], and publishes per track, seek and
 * play/pause; the glasses move the current line along the published start times on their own.
 * A tile holds at most [WidgetTileContract.MAX_LINES] lines, so timed lyrics travel as a window
 * around the current line that is published again only when playback nears its end.
 */
internal class LyricsTileRuntime(
    private val publish: (TileSnapshot) -> Unit,
    private val subscribe: (listener: (LyricsPhoneViewState) -> Unit) -> () -> Unit,
    private val clock: () -> Long,
) {
    private var active = false
    private var unsubscribe: (() -> Unit)? = null
    private var lastSent: SentTile? = null
    private var latestState: LyricsPhoneViewState? = null

    fun start() {
        if (active) return
        active = true
        lastSent = null
        unsubscribe = subscribe(::handleState)
    }

    fun stop() {
        if (!active) return
        active = false
        unsubscribe?.invoke()
        unsubscribe = null
        lastSent = null
        latestState = null
    }

    /** The hub asked for the current state: publish the latest one even though it is unchanged. */
    fun refresh() {
        if (!active) return
        lastSent = null
        latestState?.let(::handleState)
    }

    private fun handleState(state: LyricsPhoneViewState) {
        if (!active) return
        latestState = state
        val lyrics = state.lyrics
        val now = clock()
        val timed = lyrics.synced && lyrics.lines.isNotEmpty()
        val playing = lyrics.sessionState == LyricsSessionState.PLAYING
        val windowStart = if (timed) windowStartFor(lyrics) else 0
        val snapshot = tileFor(lyrics, windowStart, playing)
        val shape = shapeOf(snapshot)
        val previous = lastSent
        if (previous != null && previous.shape == shape &&
            (!timed || abs(lyrics.progressMs - previous.predictedPosition(now)) < SEEK_RESYNC_MS)
        ) {
            return
        }
        publish(snapshot)
        lastSent = SentTile(
            shape = shape,
            windowStart = windowStart,
            positionMs = lyrics.progressMs,
            playing = playing,
            sentAt = now,
        )
    }

    /** Keeps the published window while the current line stays in its first part. */
    private fun windowStartFor(lyrics: LyricsSnapshot): Int {
        val current = lyrics.currentLineIndex.coerceAtLeast(0)
        val size = lyrics.lines.size
        val previous = lastSent?.windowStart
        if (previous != null && current >= previous &&
            (current <= previous + MAX_CURRENT_OFFSET || previous + WidgetTileContract.MAX_LINES >= size)
        ) {
            return previous
        }
        return (current - LINES_BEFORE).coerceIn(0, (size - WidgetTileContract.MAX_LINES).coerceAtLeast(0))
    }

    private fun tileFor(lyrics: LyricsSnapshot, windowStart: Int, playing: Boolean): TileSnapshot {
        val content = when {
            lyrics.synced && lyrics.lines.isNotEmpty() -> {
                val window = lyrics.lines.drop(windowStart).take(WidgetTileContract.MAX_LINES)
                linesContent(
                    lyrics = lyrics,
                    lines = window.map { TileContent.Lines.Line(clipped(it.text), it.startTimeMs.coerceAtLeast(0L)) },
                    current = (lyrics.currentLineIndex - windowStart).coerceIn(0, window.size - 1),
                    playing = playing,
                )
            }
            lyrics.plainLyrics.isNotBlank() -> linesContent(
                lyrics = lyrics,
                lines = lyrics.plainLyrics.lineSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .take(WidgetTileContract.MAX_LINES)
                    .map { TileContent.Lines.Line(clipped(it)) }
                    .toList(),
                current = 0,
                playing = playing,
            )
            lyrics.trackTitle.isNotBlank() -> TileContent.Generic(
                title = clipped(lyrics.trackTitle),
                subtitle = clipped(
                    listOf(
                        if (lyrics.sessionState == LyricsSessionState.LOADING) "Loading lyrics" else "No lyrics",
                        lyrics.artistName,
                    ).filter(String::isNotBlank).joinToString(SEPARATOR),
                ),
            )
            else -> TileContent.Generic(title = "Nothing playing", subtitle = "Lyrics")
        }
        return TileSnapshot(
            pluginId = PLUGIN_ID,
            contentKey = "lyrics",
            content = content,
            tone = if (playing && content is TileContent.Lines) TileTone.OK else TileTone.OFF,
            staleAfterMs = STALE_AFTER_MS,
        )
    }

    private fun linesContent(
        lyrics: LyricsSnapshot,
        lines: List<TileContent.Lines.Line>,
        current: Int,
        playing: Boolean,
    ) = TileContent.Lines(
        lines = lines,
        current = current,
        title = clipped(lyrics.trackTitle),
        subtitle = clipped(listOf(lyrics.artistName, lyrics.albumName).filter(String::isNotBlank).joinToString(SEPARATOR)),
        source = sourceLabel(lyrics.provider),
        positionMs = lyrics.progressMs.coerceAtLeast(0L),
        durationMs = lyrics.durationSeconds?.takeIf { it > 0 }?.let { it * 1_000L },
        playing = playing,
    )

    /** What decides a publish: everything but the position and, when timed, the current line. */
    private fun shapeOf(snapshot: TileSnapshot): TileSnapshot {
        val content = snapshot.content as? TileContent.Lines ?: return snapshot
        val timed = content.lines.any { it.startMs != null }
        return snapshot.copy(content = content.copy(positionMs = null, current = if (timed) 0 else content.current))
    }

    private fun sourceLabel(provider: String): String = when (provider.uppercase()) {
        "" -> ""
        "LRCLIB" -> "LrcLib"
        else -> clipped(provider.lowercase().replaceFirstChar(Char::uppercaseChar))
    }

    private fun clipped(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim().take(WidgetTileContract.MAX_TITLE_CHARS)

    private data class SentTile(
        val shape: TileSnapshot,
        val windowStart: Int,
        val positionMs: Long,
        val playing: Boolean,
        val sentAt: Long,
    ) {
        fun predictedPosition(now: Long): Long =
            if (playing) positionMs + (now - sentAt).coerceAtLeast(0L) else positionMs
    }

    companion object {
        const val PLUGIN_ID = "lyrics"
        private const val SEEK_RESYNC_MS = 1_500L
        private const val LINES_BEFORE = 2

        /** The current line's last slot that still leaves three lines after it in the window. */
        private const val MAX_CURRENT_OFFSET = WidgetTileContract.MAX_LINES - 4
        private const val STALE_AFTER_MS = 60 * 60_000L
        private const val SEPARATOR = " · "
    }
}
