package com.anezium.rokidbus.media

import com.anezium.rokidbus.media.session.MediaDeckSnapshot
import java.security.MessageDigest

/** A short, stable key for one track: the surface's and the tile's content key. */
internal object MediaTrackKey {
    fun of(snapshot: MediaDeckSnapshot): String = shortHash(
        listOf(
            snapshot.packageName,
            snapshot.mediaId,
            snapshot.title,
            snapshot.artist,
            snapshot.album,
            snapshot.durationMs?.toString().orEmpty(),
        ).joinToString("\u0000"),
    )

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(16) {
            for (index in 0 until 8) {
                val byte = digest[index].toInt() and 0xff
                append(hex[byte ushr 4])
                append(hex[byte and 0x0f])
            }
        }
    }
}
