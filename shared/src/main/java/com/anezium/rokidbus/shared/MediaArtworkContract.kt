package com.anezium.rokidbus.shared

import org.json.JSONObject

/**
 * Binary album-art validation for a media v1 surface, and for a music tile's artwork
 * (`/tile/publish`), which carries the same `artwork` object.
 *
 * The actual JPEG/PNG rules intentionally stay owned by [ImageSurfaceContract].
 * This adapter only maps the nested media artwork metadata onto that contract and
 * applies Media Deck's tighter decoded-edge limit.
 */
object MediaArtworkContract {
    const val KIND = "media"
    const val MEDIA_VERSION = 1
    const val ENCODING_BINARY = "binary"
    const val MAX_EDGE_PIXELS = 256

    fun hasBinaryArtwork(payload: JSONObject): Boolean =
        payload.optJSONObject("artwork")?.optString("encoding") == ENCODING_BINARY

    fun validate(payload: JSONObject, binary: ByteArray?): ImageSurfaceValidationResult {
        if (payload.opt("kind") != KIND) return invalid("kind must be media")
        if (integer(payload.opt("mediaVersion")) != MEDIA_VERSION) {
            return invalid("mediaVersion must be 1")
        }
        return validateArtwork(payload.opt("contentKey"), payload.optJSONObject("artwork"), binary)
    }

    /**
     * The nested `artwork` object and its binary body alone, keyed by [contentKey]: a media
     * surface's artwork, or a music tile's under its `artworkKey`.
     */
    fun validateArtwork(contentKey: Any?, artwork: JSONObject?, binary: ByteArray?): ImageSurfaceValidationResult {
        if (artwork == null) return invalid("artwork is required")
        if (artwork.opt("encoding") != ENCODING_BINARY) {
            return invalid("artwork encoding must be binary")
        }

        val imagePayload = JSONObject()
            .put("kind", ImageSurfaceContract.KIND)
            .put("imageVersion", ImageSurfaceContract.VERSION)
            .put("contentKey", contentKey)
            .put("mimeType", artwork.opt("mimeType"))
            .put("pixelWidth", artwork.opt("pixelWidth"))
            .put("pixelHeight", artwork.opt("pixelHeight"))
            .put("sha256", artwork.opt("sha256"))
        val validation = ImageSurfaceContract.validate(imagePayload, binary)
        if (validation !is ImageSurfaceValidationResult.Valid) return validation
        if (validation.metadata.pixelWidth > MAX_EDGE_PIXELS ||
            validation.metadata.pixelHeight > MAX_EDGE_PIXELS
        ) {
            return invalid("media artwork edge exceeds $MAX_EDGE_PIXELS")
        }
        return validation
    }

    /**
     * The `artwork` object describing [bytes] (a JPEG or PNG, told by its signature), or null when
     * the bytes are not an image these limits accept.
     */
    fun describe(bytes: ByteArray): JSONObject? {
        val mimeType = when {
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> ImageSurfaceContract.MIME_JPEG
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> ImageSurfaceContract.MIME_PNG
            else -> return null
        }
        val dimensions = ImageSurfaceContract.dimensions(mimeType, bytes) ?: return null
        val artwork = JSONObject()
            .put("encoding", ENCODING_BINARY)
            .put("mimeType", mimeType)
            .put("pixelWidth", dimensions.width)
            .put("pixelHeight", dimensions.height)
            .put("sha256", ImageSurfaceContract.sha256(bytes))
        return artwork.takeIf { validateArtwork("describe", it, bytes) is ImageSurfaceValidationResult.Valid }
    }

    private fun integer(value: Any?): Int? {
        val number = value as? Number ?: return null
        val long = number.toLong()
        if (number.toDouble() != long.toDouble() || long !in Int.MIN_VALUE..Int.MAX_VALUE) return null
        return long.toInt()
    }

    private fun invalid(reason: String) =
        ImageSurfaceValidationResult.Invalid(ImageSurfaceContract.ERROR_INVALID_IMAGE, reason)
}
