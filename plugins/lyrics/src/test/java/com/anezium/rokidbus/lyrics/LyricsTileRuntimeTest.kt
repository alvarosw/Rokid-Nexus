package com.anezium.rokidbus.lyrics

import com.anezium.rokidbus.lyrics.contracts.LyricsLine
import com.anezium.rokidbus.lyrics.contracts.LyricsSessionState
import com.anezium.rokidbus.lyrics.contracts.LyricsSnapshot
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTileRuntimeTest {
    private var now = 1_000L
    private val published = mutableListOf<TileSnapshot>()
    private var listener: ((LyricsPhoneViewState) -> Unit)? = null
    private var current = LyricsPhoneViewState()
    private val runtime = LyricsTileRuntime(
        publish = { published += it },
        subscribe = { subscriber ->
            listener = subscriber
            subscriber(current)
            ({ listener = null })
        },
        clock = { now },
    )

    @Test
    fun nothingIsObservedOrPublishedOutsideTheLease() {
        assertNull(listener)
        assertTrue(published.isEmpty())

        runtime.start()
        assertTrue(listener != null)
        val idle = published.single().content as TileContent.Generic
        assertEquals("Nothing playing", idle.title)

        runtime.stop()
        assertNull(listener)
    }

    @Test
    fun syncedLyricsPublishTimedLinesOncePerTrack() {
        runtime.start()
        emit(synced(progressMs = 9_000, index = 1))

        val lines = published.last().content as TileContent.Lines
        assertEquals("Low Tide Static", lines.title)
        assertEquals("Marta Velez · Salt Rooms", lines.subtitle)
        assertEquals("LrcLib", lines.source)
        assertEquals(9_000L, lines.positionMs)
        assertEquals(236_000L, lines.durationMs)
        assertTrue(lines.playing)
        assertEquals(9, lines.lines.size)
        assertEquals(0L, lines.lines.first().startMs)
        assertEquals(1, lines.current)

        val count = published.size
        now += 5_000
        emit(synced(progressMs = 14_000, index = 2))
        now += 5_000
        emit(synced(progressMs = 19_000, index = 3))
        assertEquals(count, published.size)
    }

    @Test
    fun seekAndPauseRepublishTheAnchor() {
        runtime.start()
        emit(synced(progressMs = 9_000, index = 1))
        val count = published.size

        emit(synced(progressMs = 30_000, index = 6))
        assertEquals(count + 1, published.size)

        emit(synced(progressMs = 30_000, index = 6, playing = false))
        assertEquals(count + 2, published.size)
        assertFalse((published.last().content as TileContent.Lines).playing)
    }

    @Test
    fun theWindowMovesOnlyWhenPlaybackNearsItsEnd() {
        runtime.start()
        emit(synced(progressMs = 0, index = 0))
        val first = published.last().content as TileContent.Lines
        assertEquals("line 0", first.lines.first().text)

        var position = 0L
        for (index in 1..5) {
            position += 5_000
            now += 5_000
            emit(synced(progressMs = position, index = index))
        }
        assertEquals(first, published.last().content)

        position += 5_000
        now += 5_000
        emit(synced(progressMs = position, index = 6))
        val moved = published.last().content as TileContent.Lines
        assertEquals("line 4", moved.lines.first().text)
        assertEquals(2, moved.current)
    }

    @Test
    fun plainLyricsPublishUntimedLinesFromTheTop() {
        runtime.start()
        emit(
            LyricsSnapshot(
                sessionState = LyricsSessionState.PLAYING,
                trackTitle = "Low Tide Static",
                artistName = "Marta Velez",
                provider = "NETEASE",
                plainLyrics = "first line\n\nsecond line\nthird line",
            ),
        )

        val lines = published.last().content as TileContent.Lines
        assertEquals(listOf("first line", "second line", "third line"), lines.lines.map { it.text })
        assertTrue(lines.lines.all { it.startMs == null })
        assertEquals(0, lines.current)
        assertEquals("Netease", lines.source)
    }

    @Test
    fun aTrackWithoutLyricsPublishesItsTitle() {
        runtime.start()
        emit(
            LyricsSnapshot(
                sessionState = LyricsSessionState.READY,
                trackTitle = "Low Tide Static",
                artistName = "Marta Velez",
            ),
        )

        val generic = published.last().content as TileContent.Generic
        assertEquals("Low Tide Static", generic.title)
        assertEquals("No lyrics · Marta Velez", generic.subtitle)
    }

    @Test
    fun updatesAfterTheLeaseEndsAreIgnored() {
        runtime.start()
        val stale = listener!!
        runtime.stop()
        published.clear()

        stale(LyricsPhoneViewState(lyrics = synced(progressMs = 0, index = 0)))

        assertTrue(published.isEmpty())
    }

    private fun emit(lyrics: LyricsSnapshot) {
        current = LyricsPhoneViewState(lyrics = lyrics)
        listener!!(current)
    }

    private fun synced(progressMs: Long, index: Int, playing: Boolean = true) = LyricsSnapshot(
        sessionState = if (playing) LyricsSessionState.PLAYING else LyricsSessionState.READY,
        trackTitle = "Low Tide Static",
        artistName = "Marta Velez",
        albumName = "Salt Rooms",
        durationSeconds = 236,
        provider = "LRCLIB",
        synced = true,
        progressMs = progressMs,
        currentLineIndex = index,
        lines = (0 until 40).map { LyricsLine(startTimeMs = it * 5_000L, text = "line $it") },
    )
}
