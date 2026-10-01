package com.anezium.rokidbus.hudtiles

import android.graphics.Rect
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * [TileContent.Music] at every size, following the Media Deck artboards: the artwork box with the
 * track's text beside it (or above it on the narrow tall tile), the 3 px track at the foot and the
 * elapsed / duration times. The header's right end shows the play state. Without artwork the box is
 * absent and the text takes the width. Every per-size decision is in [SPECS].
 */
internal object MusicTileLayout {
    /** Where the elapsed / duration times go. */
    enum class Time { NONE, IN_COLUMN, BESIDE_TITLE, UNDER_TRACK }

    enum class Album { NONE, AFTER_ARTIST, OWN_LINE }

    /** `CAPTION`: the source as a label at the column's foot; `LABELLED`: a "Source" caption over it. */
    enum class Source { NONE, CAPTION, LABELLED }

    data class Spec(
        /** The artwork box's edge; 0 draws none at this size. */
        val art: Int,
        val artGap: Int,
        /** The box on its own row above the text instead of beside it. */
        val artAbove: Boolean = false,
        val top: Int,
        val title: TileTextStyle,
        val titleLines: Int,
        val artist: TileTextStyle,
        val artistLines: Int,
        val artistPrimary: Boolean = false,
        val artistGap: Int = 0,
        val album: Album = Album.NONE,
        val albumLines: Int = 1,
        val albumGap: Int = 0,
        val source: Source = Source.NONE,
        val time: Time,
        val timeStyle: TileTextStyle = TileTextStyle.META,
        val footGap: Int = RokidHudTokens.SPACE_1,
        /** "Playing" / "Paused" beside the header's play state icon. */
        val stateLabel: Boolean = true,
        /** "· Spotify" after the state label. */
        val headerSource: Boolean = false,
    )

    val SPECS: Map<TileSize, Spec> = mapOf(
        TileSize.SMALL to Spec(
            art = 0, artGap = 0, top = 4, title = TileTextStyle.SMALL, titleLines = 2,
            artist = TileTextStyle.SMALL, artistLines = 1, time = Time.NONE, stateLabel = false,
        ),
        TileSize.WIDE to Spec(
            art = 48, artGap = 8, top = 4, title = TileTextStyle.TEXT, titleLines = 1,
            artist = TileTextStyle.SMALL, artistLines = 1, time = Time.IN_COLUMN,
        ),
        TileSize.BANNER to Spec(
            art = 48, artGap = 12, top = 4, title = TileTextStyle.TEXT, titleLines = 1,
            artist = TileTextStyle.SMALL, artistLines = 1, album = Album.AFTER_ARTIST,
            time = Time.BESIDE_TITLE, headerSource = true,
        ),
        TileSize.TALL to Spec(
            art = 80, artGap = 8, artAbove = true, top = 8, title = TileTextStyle.TEXT, titleLines = 2,
            artist = TileTextStyle.SMALL, artistLines = 1, time = Time.UNDER_TRACK, stateLabel = false,
        ),
        TileSize.LARGE to Spec(
            art = 96, artGap = 12, top = 8, title = TileTextStyle.TEXT, titleLines = 3,
            artist = TileTextStyle.SMALL, artistLines = 2, artistGap = 2, source = Source.CAPTION,
            time = Time.UNDER_TRACK,
        ),
        TileSize.PANEL to Spec(
            art = 120, artGap = 16, top = 8, title = TileTextStyle.HEADING, titleLines = 2,
            artist = TileTextStyle.TEXT, artistLines = 1, artistPrimary = true, artistGap = 2,
            album = Album.OWN_LINE, source = Source.CAPTION, time = Time.UNDER_TRACK,
        ),
        TileSize.JUMBO to Spec(
            art = 200, artGap = 12, top = 12, title = TileTextStyle.HEADING, titleLines = 3,
            artist = TileTextStyle.TEXT, artistLines = 2, artistPrimary = true, artistGap = 4,
            album = Album.OWN_LINE, albumLines = 2, albumGap = 2, source = Source.LABELLED,
            time = Time.UNDER_TRACK, timeStyle = TileTextStyle.FIGURE, footGap = 6,
        ),
    )

    fun layout(input: TileRenderInput, content: TileContent.Music, size: TileSize): TileLayout {
        val spec = SPECS.getValue(size)
        val ink = TileInk(input)
        val width = TileRenderer.widthOf(size)
        val height = TileRenderer.heightOf(size)
        val pad = TileRenderer.PADDING
        val bottom = height - pad

        val state = TileHeader.End.PlayState(content.playing)
        val end = buildList {
            add(state)
            if (size.cols >= 2 && spec.stateLabel) add(TileHeader.End.Text(if (content.playing) "Playing" else "Paused"))
            if (spec.headerSource && content.source.isNotBlank()) {
                add(TileHeader.End.Text("· ${content.source}", color = RokidHudTokens.TEXT_SECONDARY))
            }
        }
        val header = TileHeader.layout(input, width, end)

        val position = playbackPositionMs(content.positionMs, content.durationMs, content.playing, input)
        val duration = content.durationMs?.takeIf { it > 0 }
        val timeText = when {
            position == null -> ""
            duration == null -> TileTime.clock(position)
            else -> "${TileTime.clock(position)} / ${TileTime.clock(duration)}"
        }

        // The foot: the track at the bottom, or the track over the times row.
        val footer = ArrayList<TileOp>()
        var footTop = bottom
        if (spec.time == Time.UNDER_TRACK && position != null) {
            val timesTop = bottom - spec.timeStyle.lineHeightPx
            val elapsed = TileText.line(TilePart.ELAPSED, TileTime.clock(position), spec.timeStyle, ink.primary, pad, timesTop, width - 2 * pad)
            footer += elapsed.lines
            if (duration != null) {
                val text = TileTime.clock(duration)
                val durationWidth = TileText.desiredWidth(text, spec.timeStyle)
                footer += TileText.line(TilePart.DURATION, text, spec.timeStyle, ink.secondary, width - pad - durationWidth, timesTop, durationWidth).lines
            }
            footTop = timesTop - if (duration != null) spec.footGap else RokidHudTokens.SPACE_1
        }
        if (position != null && duration != null) {
            val trackTop = footTop - GenericTileLayout.TRACK_HEIGHT
            footer += TileOp.Track(
                pad.toFloat(), trackTop.toFloat(), (width - pad).toFloat(), footTop.toFloat(),
                (position.toFloat() / duration).coerceIn(0f, 1f), ink.line, ink.primary,
            )
            footTop = trackTop - RokidHudTokens.SPACE_1
        }
        val bodyBottom = footTop

        val body = ArrayList<TileOp>()
        val artwork = input.artwork?.takeIf { spec.art > 0 }
        val top = header.bottom + spec.top
        var left = pad
        var y = top
        if (artwork != null) {
            body += TileOp.Artwork(artwork, pad.toFloat(), top.toFloat(), spec.art.toFloat())
            if (spec.artAbove) y = top + spec.art + spec.artGap else left = pad + spec.art + spec.artGap
        }
        val column = Column(body, left, width - pad - left, y, bodyBottom)

        if (spec.time == Time.BESIDE_TITLE && timeText.isNotEmpty()) {
            val timeWidth = TileText.desiredWidth(timeText, TileTextStyle.META)
            val titleWidth = column.width - timeWidth - RokidHudTokens.SPACE_2
            val title = column.text(TilePart.TITLE, content.title, spec.title, ink.emphasis, spec.titleLines, width = titleWidth)
            title?.let {
                val baseline = it.lines.first().baseline
                val time = TileText.line(TilePart.TIME, timeText, TileTextStyle.META, ink.primary, left + column.width - timeWidth, 0, timeWidth)
                body += time.lines.map { line -> line.shiftedBy((baseline - time.baseline).toInt()) }
            }
        } else {
            column.text(TilePart.TITLE, content.title, spec.title, ink.emphasis, spec.titleLines)
        }
        val artist = if (spec.album == Album.AFTER_ARTIST) joinDot(content.artist, content.album) else content.artist
        column.text(
            TilePart.ARTIST, artist, spec.artist, if (spec.artistPrimary) ink.primary else ink.secondary,
            spec.artistLines, gap = spec.artistGap,
        )
        if (spec.album == Album.OWN_LINE) {
            column.text(TilePart.ALBUM, content.album, TileTextStyle.SMALL, ink.secondary, spec.albumLines, gap = spec.albumGap)
        }
        if (spec.time == Time.IN_COLUMN) column.text(TilePart.TIME, timeText, TileTextStyle.META, ink.secondary, 1)

        if (spec.source != Source.NONE && content.source.isNotBlank()) {
            // Beside artwork the source sits on the box's bottom edge; text-only, on the body's.
            val sourceBottom = if (artwork != null && !spec.artAbove) minOf(top + spec.art, bodyBottom) else bodyBottom
            val captionHeight = if (spec.source == Source.LABELLED) TileTextStyle.CAPTION.lineHeightPx + 2 else 0
            val blockTop = maxOf(column.y + RokidHudTokens.SPACE_1, sourceBottom - captionHeight - sourceStyle(spec).lineHeightPx)
            if (blockTop + captionHeight + sourceStyle(spec).lineHeightPx <= bodyBottom) {
                if (spec.source == Source.LABELLED) {
                    body += TileText.line(TilePart.SOURCE_CAPTION, "SOURCE", TileTextStyle.CAPTION, ink.secondary, left, blockTop, column.width).lines
                    body += TileText.line(TilePart.SOURCE, content.source, TileTextStyle.SMALL, ink.primary, left, blockTop + captionHeight, column.width).lines
                } else {
                    body += TileText.line(TilePart.SOURCE, content.source.uppercase(), TileTextStyle.CAPTION, ink.secondary, left, blockTop, column.width).lines
                }
            }
        }
        return TileLayout(width, height, header.ops, body, Rect(pad, header.bottom, width - pad, bodyBottom), footer)
    }

    private fun sourceStyle(spec: Spec) = if (spec.source == Source.LABELLED) TileTextStyle.SMALL else TileTextStyle.CAPTION

    /** The next whole second of a playing position: the times and the track move on it. */
    fun nextChangeAtElapsed(input: TileRenderInput, content: TileContent.Music): Long? {
        if (!content.playing) return null
        val position = playbackPositionMs(content.positionMs, content.durationMs, true, input) ?: return null
        val duration = content.durationMs
        if (duration != null && duration > 0 && position >= duration) return null
        return input.nowElapsed + TileTime.untilClockChanges(position)
    }

    private fun joinDot(vararg parts: String) = parts.filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * Text stacked top-down in a column, each block clamped to its line cap and to the lines that still
 * fit above [bottom]; a block with no line left is dropped whole.
 */
internal class Column(
    private val ops: MutableList<TileOp>,
    val left: Int,
    val width: Int,
    var y: Int,
    private val bottom: Int,
) {
    fun text(
        part: TilePart,
        value: String,
        style: TileTextStyle,
        color: Int,
        maxLines: Int,
        gap: Int = 0,
        width: Int = this.width,
    ): TextBlock? {
        if (value.isBlank()) return null
        val top = y + gap
        val lines = minOf(maxLines, (bottom - top) / style.lineHeightPx)
        if (lines <= 0) return null
        val block = TileText.block(part, value, style, color, left, top, width, lines)
        ops += block.lines
        y = top + block.height
        return block
    }
}
