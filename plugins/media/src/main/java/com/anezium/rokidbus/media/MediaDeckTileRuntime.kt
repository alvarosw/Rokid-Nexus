package com.anezium.rokidbus.media

import com.anezium.rokidbus.media.session.MediaDeckMonitorStatus
import com.anezium.rokidbus.media.session.MediaDeckSnapshot
import com.anezium.rokidbus.media.session.MediaSessionWatcher
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import kotlin.math.abs

internal interface MediaDeckTileHost {
    /** [artworkBytes] is non-null exactly when the snapshot is music with an `artworkKey`. */
    fun publish(snapshot: TileSnapshot, artworkBytes: ByteArray?)
    fun post(action: () -> Unit)
}

/**
 * Keeps the Media Deck grid tile current for as long as the hub's tile lease lasts: the media
 * session is watched only between [start] and [stop], and a tile is published on a track change,
 * play/pause, a seek, or the arrival of the cover, never per second.
 */
internal class MediaDeckTileRuntime(
    private val host: MediaDeckTileHost,
    private val encodeArtwork: (MediaDeckSnapshot) -> EncodedImageArtwork?,
    private val clock: () -> Long,
    watcherFactory: (
        onSnapshot: (MediaDeckSnapshot?) -> Unit,
        onStatus: (MediaDeckMonitorStatus) -> Unit,
    ) -> MediaSessionWatcher,
) {
    private val watcher = watcherFactory(
        { snapshot -> host.post { handleSnapshot(snapshot) } },
        { status -> host.post { handleStatus(status) } },
    )
    private var active = false
    private var latestSnapshot: MediaDeckSnapshot? = null
    private var monitorStatus = MediaDeckMonitorStatus.STARTING
    private var lastPublishedIdle: MediaDeckMonitorStatus? = null
    private var lastSent: SentTrack? = null
    private var artworkTrackKey: String? = null
    private var artwork: EncodedImageArtwork? = null
    private var artworkUriAttempt: String? = null

    fun start() {
        if (active) return
        active = true
        watcher.start()
    }

    fun stop() {
        if (!active) return
        active = false
        watcher.stop()
        latestSnapshot = null
        monitorStatus = MediaDeckMonitorStatus.STARTING
        lastPublishedIdle = null
        lastSent = null
        artworkTrackKey = null
        artwork = null
        artworkUriAttempt = null
    }

    /**
     * The hub asked for the current state: forget what was sent, so the watcher's answer is
     * published even when nothing changed. The encoded cover for the current track is kept.
     */
    fun refresh() {
        if (!active) return
        lastPublishedIdle = null
        lastSent = null
        watcher.refresh()
    }

    private fun handleStatus(status: MediaDeckMonitorStatus) {
        if (!active) return
        monitorStatus = status
        if (latestSnapshot == null) publishIdle(status)
    }

    private fun handleSnapshot(snapshot: MediaDeckSnapshot?) {
        if (!active) return
        latestSnapshot = snapshot
        if (snapshot == null) publishIdle(monitorStatus) else publishTrack(snapshot)
    }

    private fun publishIdle(status: MediaDeckMonitorStatus) {
        // STARTING is followed by a real status or snapshot within the same refresh.
        if (status == MediaDeckMonitorStatus.STARTING || status == lastPublishedIdle) return
        val content = if (status == MediaDeckMonitorStatus.ACCESS_REQUIRED) {
            TileContent.Generic(title = "Media access needed", subtitle = "Enable it in Media Deck settings")
        } else {
            TileContent.Generic(title = "Nothing playing", subtitle = "Media Deck")
        }
        host.publish(
            TileSnapshot(
                pluginId = PLUGIN_ID,
                contentKey = "idle:${status.name.lowercase()}",
                content = content,
                tone = if (status == MediaDeckMonitorStatus.ACCESS_REQUIRED) TileTone.WARN else TileTone.OFF,
                staleAfterMs = IDLE_STALE_AFTER_MS,
            ),
            null,
        )
        lastPublishedIdle = status
        lastSent = null
    }

    private fun publishTrack(snapshot: MediaDeckSnapshot) {
        val now = clock()
        val trackKey = MediaTrackKey.of(snapshot)
        val hadArtwork = artworkTrackKey == trackKey && artwork != null
        val cover = coverFor(trackKey, snapshot)
        val previous = lastSent
        val changed = previous == null ||
            previous.trackKey != trackKey ||
            previous.playing != snapshot.isPlaying ||
            previous.durationMs != snapshot.durationMs ||
            (!hadArtwork && cover != null) ||
            abs(snapshot.positionMs - previous.predictedPosition(now)) >= SEEK_RESYNC_MS
        if (!changed) return

        val artworkKey = cover?.let { "$trackKey-${it.sha256.take(ARTWORK_HASH_CHARS)}" }.orEmpty()
        val position = snapshot.positionMs.coerceAtLeast(0L)
        host.publish(
            TileSnapshot(
                pluginId = PLUGIN_ID,
                contentKey = trackKey,
                content = TileContent.Music(
                    title = clipped(snapshot.title),
                    artist = clipped(snapshot.artist),
                    album = clipped(snapshot.album),
                    source = clipped(snapshot.sourceLabel),
                    playing = snapshot.isPlaying,
                    positionMs = position,
                    durationMs = snapshot.durationMs,
                    artworkKey = artworkKey,
                ),
                tone = if (snapshot.isPlaying) TileTone.OK else TileTone.OFF,
                staleAfterMs = staleAfterMs(snapshot, position),
            ),
            cover?.bytes,
        )
        lastPublishedIdle = null
        lastSent = SentTrack(
            trackKey = trackKey,
            positionMs = position,
            durationMs = snapshot.durationMs,
            playing = snapshot.isPlaying,
            playbackSpeed = snapshot.playbackSpeed,
            sentAt = now,
        )
    }

    private fun coverFor(trackKey: String, snapshot: MediaDeckSnapshot): EncodedImageArtwork? {
        if (artworkTrackKey != trackKey) {
            artworkTrackKey = trackKey
            artworkUriAttempt = snapshot.artworkUri
            artwork = encodeArtwork(snapshot)
        } else if (artwork == null &&
            (snapshot.artwork != null || snapshot.artworkUri.isNotBlank() && snapshot.artworkUri != artworkUriAttempt)
        ) {
            artworkUriAttempt = snapshot.artworkUri
            artwork = encodeArtwork(snapshot)
        }
        return artwork
    }

    /** A playing track goes stale shortly after it should have ended; anything else holds longer. */
    private fun staleAfterMs(snapshot: MediaDeckSnapshot, position: Long): Long {
        val duration = snapshot.durationMs
        if (!snapshot.isPlaying || duration == null) return IDLE_STALE_AFTER_MS
        val speed = snapshot.playbackSpeed.coerceAtLeast(MIN_SPEED)
        val remaining = ((duration - position).coerceAtLeast(0L) / speed).toLong()
        return (remaining + TRACK_END_GRACE_MS)
            .coerceIn(WidgetTileContract.MIN_STALE_AFTER_MS, WidgetTileContract.MAX_STALE_AFTER_MS)
    }

    private fun clipped(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim().take(WidgetTileContract.MAX_TITLE_CHARS)

    private data class SentTrack(
        val trackKey: String,
        val positionMs: Long,
        val durationMs: Long?,
        val playing: Boolean,
        val playbackSpeed: Float,
        val sentAt: Long,
    ) {
        fun predictedPosition(now: Long): Long {
            if (!playing) return positionMs
            val predicted = positionMs + ((now - sentAt).coerceAtLeast(0L) * playbackSpeed).toLong()
            return durationMs?.let { predicted.coerceAtMost(it) } ?: predicted
        }
    }

    companion object {
        const val PLUGIN_ID = "media"
        private const val SEEK_RESYNC_MS = 1_250L
        private const val ARTWORK_HASH_CHARS = 16
        private const val TRACK_END_GRACE_MS = 60_000L
        private const val IDLE_STALE_AFTER_MS = 60 * 60_000L
        private const val MIN_SPEED = 0.25f
    }
}
