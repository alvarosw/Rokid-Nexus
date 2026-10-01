package com.anezium.rokidbus.media

import com.anezium.rokidbus.media.session.MediaDeckMonitorStatus
import com.anezium.rokidbus.media.session.MediaDeckSnapshot
import com.anezium.rokidbus.media.session.MediaSessionWatcher
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDeckTileRuntimeTest {
    private var now = 1_000L
    private val published = mutableListOf<Pair<TileSnapshot, ByteArray?>>()
    private val watcher = FakeWatcher()
    private var cover: EncodedImageArtwork? = artwork("cover-a")
    private val runtime = MediaDeckTileRuntime(
        host = object : MediaDeckTileHost {
            override fun publish(snapshot: TileSnapshot, artworkBytes: ByteArray?) {
                published += snapshot to artworkBytes
            }

            override fun post(action: () -> Unit) = action()
        },
        encodeArtwork = { cover },
        clock = { now },
        watcherFactory = { onSnapshot, onStatus ->
            watcher.onSnapshot = onSnapshot
            watcher.onStatus = onStatus
            watcher
        },
    )

    @Test
    fun `nothing is watched or published outside the lease`() {
        watcher.onSnapshot(track())
        assertFalse(watcher.started)
        assertTrue(published.isEmpty())

        runtime.start()
        runtime.stop()
        watcher.onSnapshot(track())
        watcher.onStatus(MediaDeckMonitorStatus.NO_SESSION)

        assertFalse(watcher.started)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `a playing track publishes a music tile with its cover`() {
        runtime.start()
        assertTrue(watcher.started)

        watcher.onSnapshot(track(positionMs = 102_000))

        val (snapshot, bytes) = published.single()
        val music = snapshot.content as TileContent.Music
        assertEquals("Low Tide Static", music.title)
        assertEquals("Marta Velez", music.artist)
        assertEquals("Salt Rooms", music.album)
        assertEquals("Spotify", music.source)
        assertTrue(music.playing)
        assertEquals(102_000L, music.positionMs)
        assertEquals(236_000L, music.durationMs)
        assertTrue(music.artworkKey.startsWith(snapshot.contentKey))
        assertEquals(TileTone.OK, snapshot.tone)
        assertArrayEquals(cover!!.bytes, bytes)
    }

    @Test
    fun `steady playback publishes once, a seek or pause publishes again`() {
        runtime.start()
        watcher.onSnapshot(track(positionMs = 10_000))
        now += 5_000
        watcher.onSnapshot(track(positionMs = 15_000))
        assertEquals(1, published.size)

        watcher.onSnapshot(track(positionMs = 90_000))
        assertEquals(2, published.size)
        assertEquals(90_000L, (published.last().first.content as TileContent.Music).positionMs)

        watcher.onSnapshot(track(positionMs = 90_000, playing = false))
        assertEquals(3, published.size)
        val paused = published.last().first
        assertFalse((paused.content as TileContent.Music).playing)
        assertEquals(TileTone.OFF, paused.tone)
    }

    @Test
    fun `a new track changes the artwork key and a late cover is published`() {
        cover = null
        runtime.start()
        watcher.onSnapshot(track())
        val first = published.single()
        assertEquals("", (first.first.content as TileContent.Music).artworkKey)
        assertNull(first.second)

        cover = artwork("cover-a")
        watcher.onSnapshot(track(artworkUri = "content://art/1"))
        val withCover = published.last().first.content as TileContent.Music
        assertEquals(2, published.size)
        assertTrue(withCover.artworkKey.isNotEmpty())

        cover = artwork("cover-b")
        watcher.onSnapshot(track(title = "Harbor Lights"))
        val next = published.last().first
        assertEquals("Harbor Lights", (next.content as TileContent.Music).title)
        assertNotEquals(withCover.artworkKey, (next.content as TileContent.Music).artworkKey)
    }

    @Test
    fun `nothing playing publishes a generic tile once`() {
        runtime.start()
        watcher.onStatus(MediaDeckMonitorStatus.STARTING)
        assertTrue(published.isEmpty())

        watcher.onSnapshot(track())
        watcher.onStatus(MediaDeckMonitorStatus.NO_SESSION)
        watcher.onSnapshot(null)
        watcher.onStatus(MediaDeckMonitorStatus.NO_SESSION)
        watcher.onSnapshot(null)

        assertEquals(2, published.size)
        val idle = published.last().first.content as TileContent.Generic
        assertEquals("Nothing playing", idle.title)
    }

    @Test
    fun `missing notification access says so on the tile`() {
        runtime.start()
        watcher.onStatus(MediaDeckMonitorStatus.ACCESS_REQUIRED)
        watcher.onSnapshot(null)

        val snapshot = published.single().first
        assertEquals("Media access needed", (snapshot.content as TileContent.Generic).title)
        assertEquals(TileTone.WARN, snapshot.tone)
    }

    private fun track(
        title: String = "Low Tide Static",
        positionMs: Long = 0L,
        playing: Boolean = true,
        artworkUri: String = "",
    ) = MediaDeckSnapshot(
        packageName = "com.spotify.music",
        sourceLabel = "Spotify",
        mediaId = "",
        title = title,
        artist = "Marta Velez",
        album = "Salt Rooms",
        durationMs = 236_000L,
        positionMs = positionMs,
        isPlaying = playing,
        playbackSpeed = 1f,
        artwork = null,
        artworkUri = artworkUri,
    )

    private fun artwork(seed: String) = EncodedImageArtwork(
        mimeType = "image/jpeg",
        width = 8,
        height = 8,
        bytes = seed.toByteArray(),
        sha256 = seed.hashCode().toString(16).padStart(16, '0'),
        jpegQuality = 88,
    )

    private class FakeWatcher : MediaSessionWatcher {
        var started = false
        var onSnapshot: (MediaDeckSnapshot?) -> Unit = {}
        var onStatus: (MediaDeckMonitorStatus) -> Unit = {}

        override fun start() {
            started = true
        }

        override fun stop() {
            started = false
        }
    }
}
