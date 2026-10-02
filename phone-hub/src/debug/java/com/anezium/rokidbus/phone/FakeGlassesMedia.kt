package com.anezium.rokidbus.phone

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log

/**
 * The one fake media session of the harness. Held process-wide so it outlives the broadcast that
 * created it; the hub's foreground service keeps the process alive. Any media listener that has
 * no package filter (Media Deck) picks it up as the active session.
 */
internal object FakeGlassesMedia {
    private const val TAG = "FAKE_GLASSES"
    private var session: MediaSession? = null

    @Synchronized
    fun update(context: Context, state: String, title: String?, artist: String?, durationMs: Long?) {
        if (state == "stop") {
            release()
            return
        }
        val playing = when (state) {
            "play" -> true
            "pause" -> false
            else -> {
                Log.i(TAG, "media rejected state='$state'")
                return
            }
        }
        val current = session ?: MediaSession(context.applicationContext, "FakeGlassesMedia").also {
            it.setCallback(callback)
            session = it
        }
        val previous = current.controller.metadata
        val resolvedTitle = title ?: previous?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Fake track"
        val resolvedArtist = artist ?: previous?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Fake artist"
        val resolvedDuration = durationMs ?: previous?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        current.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, resolvedTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, resolvedArtist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, resolvedDuration)
                .build(),
        )
        val position = current.controller.playbackState?.position ?: 0L
        current.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    position,
                    if (playing) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
        // Activated last, like a real player: a listener that attaches on the activation edge
        // (Media Deck) ignores a session whose metadata is still empty and never revisits it.
        if (!current.isActive) current.isActive = true
        Log.i(TAG, "media $state title=$resolvedTitle artist=$resolvedArtist durationMs=$resolvedDuration")
    }

    @Synchronized
    fun release() {
        session?.let {
            it.isActive = false
            it.release()
            Log.i(TAG, "media released")
        }
        session = null
    }

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() {
            Log.i(TAG, "media-callback play")
        }
        override fun onPause() {
            Log.i(TAG, "media-callback pause")
        }
        override fun onStop() {
            Log.i(TAG, "media-callback stop")
        }
        override fun onSkipToNext() {
            Log.i(TAG, "media-callback next")
        }
        override fun onSkipToPrevious() {
            Log.i(TAG, "media-callback previous")
        }
        override fun onSeekTo(pos: Long) {
            Log.i(TAG, "media-callback seek $pos")
        }
    }
}
