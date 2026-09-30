package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens

/**
 * Renderer owned by the `media` surface kind; no phone/plugin implementation leaks here.
 *
 * The artwork is the one flexible block: it takes whatever height the text, the progress bar and
 * the clock leave, up to [ART_MAX_PX], and goes away below [ART_MIN_PX], so the title, the artist
 * and the progress are never pushed out of the panel.
 */
internal class MediaHudView(context: Context) : LinearLayout(context) {
    private val artworkView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        contentDescription = "Album artwork"
    }
    private val artworkFallback = TextView(context).apply {
        text = "\u266A"
        setTextColor(RokidHudTokens.TEXT_SECONDARY)
        typeface = RokidHudTokens.bodyTypeface()
        RokidHudTokens.applyTextSize(this, ART_GLYPH_PX)
        includeFontPadding = false
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
    }
    private val artworkFrame = FrameLayout(context).apply {
        background = SurfaceChrome.panel()
        addView(
            artworkView,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            artworkFallback,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }
    private val titleView = SurfaceType.marquee(SurfaceType.display(TextView(context))).apply {
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
    }
    private val artistView = SurfaceType.marquee(SurfaceType.body(TextView(context))).apply {
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
    }
    private val albumView = SurfaceType.bodySmall(TextView(context)).apply {
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
    }
    private val progressView = MediaProgressView(context)
    private val elapsedView = SurfaceType.data(TextView(context)).apply { gravity = Gravity.START }
    private val stateView = SurfaceType.label(TextView(context)).apply {
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
    }
    private val durationView = SurfaceType.data(TextView(context)).apply {
        gravity = Gravity.END
        textAlignment = TEXT_ALIGNMENT_VIEW_END
    }
    private val timeRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        addView(elapsedView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(stateView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(durationView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }
    private var renderedArtworkKey: String? = null
    private var renderedArtworkOwned = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        addView(artworkFrame, LayoutParams(ART_MAX_PX, ART_MAX_PX).apply {
            bottomMargin = RokidHudTokens.SPACE_3
        })
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(artistView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = RokidHudTokens.SPACE_1
        })
        addView(albumView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(progressView, LayoutParams(LayoutParams.MATCH_PARENT, MediaProgressView.HEIGHT_PX).apply {
            topMargin = RokidHudTokens.SPACE_3
        })
        addView(timeRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = RokidHudTokens.SPACE_1
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && width > 0) {
            val childWidth = MeasureSpec.makeMeasureSpec(width - paddingLeft - paddingRight, MeasureSpec.EXACTLY)
            val anyHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            var used = paddingTop + paddingBottom
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child === artworkFrame || child.visibility == GONE) continue
                val params = child.layoutParams as MarginLayoutParams
                child.measure(childWidth, anyHeight)
                used += child.measuredHeight + params.topMargin + params.bottomMargin
            }
            val artParams = artworkFrame.layoutParams as MarginLayoutParams
            val room = height - used - artParams.topMargin - artParams.bottomMargin
            val side = minOf(room, ART_MAX_PX, width - paddingLeft - paddingRight)
            if (side < ART_MIN_PX) {
                artworkFrame.visibility = GONE
            } else {
                artworkFrame.visibility = VISIBLE
                artParams.width = side
                artParams.height = side
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun render(surface: NexusSurface) {
        titleView.text = surface.mediaTitle
        artistView.text = surface.mediaArtist
        artistView.visibility = visibleIf(artistView.text.isNotBlank())
        albumView.text = surface.mediaAlbum
        albumView.visibility = visibleIf(surface.mediaAlbum.isNotBlank())

        val anchor = surface.anchor
        val position = anchor?.effectivePositionMs(SystemClock.elapsedRealtime())?.coerceAtLeast(0L) ?: 0L
        val duration = anchor?.durationMs
        progressView.setProgress(
            if (duration != null && duration > 0L) position.toFloat() / duration else 0f,
        )
        elapsedView.text = formatDuration(position)
        stateView.text = if (anchor?.playing == true) "PLAYING" else "PAUSED"
        durationView.text = duration?.let(::formatDuration).orEmpty()
        renderArtwork(surface)
    }

    fun clear() {
        titleView.text = ""
        artistView.text = ""
        albumView.text = ""
        elapsedView.text = ""
        stateView.text = ""
        durationView.text = ""
        progressView.setProgress(0f)
        clearRenderedArtwork()
    }

    override fun onDetachedFromWindow() {
        clearRenderedArtwork()
        super.onDetachedFromWindow()
    }

    private fun renderArtwork(surface: NexusSurface) {
        val image = surface.imageBitmap?.takeUnless { it.isRecycled }
            ?.takeIf { surface.mediaArtworkMetadata != null }
        val artwork = surface.artwork
        val nextKey = if (image != null) {
            "image:${surface.mediaArtworkMetadata?.sha256}:${image.generationId}"
        } else {
            artwork?.identityKey.orEmpty()
        }
        if (renderedArtworkKey == nextKey) return
        clearRenderedArtwork()
        renderedArtworkKey = nextKey
        if (image != null) {
            artworkView.setImageDrawable(
                BitmapDrawable(resources, image).apply {
                    paint.isAntiAlias = true
                    paint.isFilterBitmap = true
                    paint.isDither = false
                },
            )
            renderedArtworkOwned = false
            artworkFallback.visibility = GONE
            artworkFrame.background = null
            return
        }
        if (artwork == null) return
        val bitmap = artwork.toPhosphorBitmap(RokidHudTokens.TEXT_PRIMARY)
        artworkView.setImageDrawable(
            BitmapDrawable(resources, bitmap).apply {
                paint.isAntiAlias = false
                paint.isFilterBitmap = false
                paint.isDither = false
            },
        )
        renderedArtworkOwned = true
        artworkFallback.visibility = GONE
        artworkFrame.background = null
    }

    private fun clearRenderedArtwork() {
        if (renderedArtworkOwned) {
            (artworkView.drawable as? BitmapDrawable)?.bitmap?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        artworkView.setImageDrawable(null)
        artworkFallback.visibility = VISIBLE
        artworkFrame.background = SurfaceChrome.panel()
        renderedArtworkKey = null
        renderedArtworkOwned = false
    }

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private fun visibleIf(condition: Boolean): Int = if (condition) View.VISIBLE else View.GONE

    private companion object {
        const val ART_MAX_PX = 224
        const val ART_MIN_PX = 64
        const val ART_GLYPH_PX = 48f
    }
}
