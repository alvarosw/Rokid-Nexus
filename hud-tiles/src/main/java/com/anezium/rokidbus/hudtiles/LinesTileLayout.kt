package com.anezium.rokidbus.hudtiles

import android.graphics.Rect
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * [TileContent.Lines] at every size, following the Lyrics artboards: the current line emphasized
 * and, from two rows high, centered in the body with the lines before and after it around it; the
 * track's meta and progress at the foot. With start times on the lines and a position, the current
 * line follows the position on its own. Every per-size decision is in [SPECS].
 */
internal object LinesTileLayout {
    /** What the header's right end shows. */
    enum class HeaderMeta { NONE, TRACK, SYNC, SYNC_SOURCE }

    enum class Foot {
        NONE,
        TRACK,
        /** "title · subtitle" over the track. */
        META_TRACK,
        /** The title, the subtitle and the times on one row over the track. */
        META_TIME_TRACK,
        /** A divider, the title and the subtitle on their own lines, the track, the times under it. */
        FULL,
    }

    data class Spec(
        val top: Int,
        val current: TileTextStyle,
        val currentLines: Int,
        val before: Int,
        val after: Int,
        val beforeLines: Int = 1,
        /** The line right after the current one; the ones further away take [farLines]. */
        val afterNearLines: Int = 1,
        val farLines: Int = 1,
        /** Between the current line and its neighbours, then between context lines. */
        val nearGap: Int,
        val farGap: Int = RokidHudTokens.SPACE_1,
        val header: HeaderMeta = HeaderMeta.NONE,
        val foot: Foot = Foot.NONE,
    ) {
        /** One row high, the current line leads from the top; taller, it holds the middle. */
        val centered: Boolean get() = before > 0
    }

    val SPECS: Map<TileSize, Spec> = mapOf(
        TileSize.SMALL to Spec(top = 4, current = TileTextStyle.SMALL, currentLines = 4, before = 0, after = 0, nearGap = 0),
        TileSize.WIDE to Spec(top = 4, current = TileTextStyle.TEXT, currentLines = 2, before = 0, after = 1, nearGap = 2),
        TileSize.BANNER to Spec(
            top = 4, current = TileTextStyle.TEXT, currentLines = 2, before = 0, after = 1, nearGap = 4,
            header = HeaderMeta.TRACK,
        ),
        TileSize.TALL to Spec(
            top = 6, current = TileTextStyle.TEXT, currentLines = 4, before = 1, after = 1, beforeLines = 2,
            afterNearLines = 2, nearGap = 8, foot = Foot.TRACK,
        ),
        TileSize.LARGE to Spec(
            top = 8, current = TileTextStyle.HEADING, currentLines = 3, before = 1, after = 2, afterNearLines = 2,
            nearGap = 8, header = HeaderMeta.SYNC, foot = Foot.META_TRACK,
        ),
        TileSize.PANEL to Spec(
            top = 8, current = TileTextStyle.HEADING, currentLines = 2, before = 2, after = 2, nearGap = 8,
            header = HeaderMeta.SYNC_SOURCE, foot = Foot.META_TIME_TRACK,
        ),
        TileSize.JUMBO to Spec(
            top = 12, current = TileTextStyle.HEADING, currentLines = 3, before = 3, after = 3, nearGap = 10,
            header = HeaderMeta.SYNC_SOURCE, foot = Foot.FULL,
        ),
    )

    fun layout(input: TileRenderInput, content: TileContent.Lines, size: TileSize): TileLayout {
        val spec = SPECS.getValue(size)
        val ink = TileInk(input)
        val width = TileRenderer.widthOf(size)
        val height = TileRenderer.heightOf(size)
        val pad = TileRenderer.PADDING
        val inner = width - 2 * pad

        val header = TileHeader.layout(input, width, headerEnd(spec.header, content))
        val position = playbackPositionMs(content.positionMs, content.durationMs, content.playing, input)
        val duration = content.durationMs?.takeIf { it > 0 }
        val progress = if (position != null && duration != null) (position.toFloat() / duration).coerceIn(0f, 1f) else null

        val footer = ArrayList<TileOp>()
        val footTop = foot(footer, spec.foot, content, ink, width, height - pad, position, duration, progress)
        val top = header.bottom + spec.top
        val bottom = if (footer.isEmpty()) height - pad else footTop - RokidHudTokens.SPACE_1

        val body = ArrayList<TileOp>()
        val current = currentIndex(content, input)
        val currentText = content.lines.getOrNull(current)?.text ?: content.title
        val roomLines = (bottom - top) / spec.current.lineHeightPx
        if (currentText.isNotBlank() && roomLines > 0) {
            val measured = TileText.block(TilePart.CURRENT_LINE, currentText, spec.current, ink.emphasis, pad, 0, inner, minOf(spec.currentLines, roomLines))
            // A line missing before the first or past the last still holds one line of room, so the
            // current line keeps its place as the lines scroll through it.
            fun slots(count: Int, step: Int, nearLines: Int) = (1..count).map { distance ->
                val block = content.lines.getOrNull(current + step * distance)?.text
                    ?.let { context(it, ink, pad, inner, if (distance == 1) nearLines else spec.farLines) }
                Slot(block, block?.height ?: TileTextStyle.SMALL.lineHeightPx, if (distance == 1) spec.nearGap else spec.farGap)
            }.toMutableList()
            val before = slots(spec.before, -1, spec.beforeLines)
            val after = slots(spec.after, 1, spec.afterNearLines)
            val total = { measured.height + (before + after).sumOf { it.height + it.gap } }
            while (total() > bottom - top && (before.isNotEmpty() || after.isNotEmpty())) {
                if (after.size >= before.size && after.isNotEmpty()) after.removeAt(after.lastIndex) else before.removeAt(before.lastIndex)
            }
            var y = if (spec.centered) top + (bottom - top - total()) / 2 else top
            before.asReversed().forEach { slot ->
                slot.block?.let { block -> body += block.lines.map { it.shiftedBy(y) } }
                y += slot.height + slot.gap
            }
            body += measured.lines.map { it.shiftedBy(y) }
            y += measured.height
            after.forEach { slot ->
                y += slot.gap
                slot.block?.let { block -> body += block.lines.map { it.shiftedBy(y) } }
                y += slot.height
            }
        }
        return TileLayout(width, height, header.ops, body, Rect(pad, header.bottom, width - pad, bottom), footer)
    }

    /** A context line, or the room of a missing one, and its gap toward the current line. */
    private class Slot(val block: TextBlock?, val height: Int, val gap: Int)

    private fun context(text: String, ink: TileInk, left: Int, width: Int, lines: Int) =
        TileText.block(TilePart.CONTEXT_LINE, text, TileTextStyle.SMALL, ink.secondary, left, 0, width, lines)

    private fun headerEnd(meta: HeaderMeta, content: TileContent.Lines): List<TileHeader.End> {
        val sync = if (isTimed(content)) "Synced" else ""
        val text = when (meta) {
            HeaderMeta.NONE -> ""
            HeaderMeta.TRACK -> return trackMeta(content).takeIf { it.isNotEmpty() }
                ?.let { listOf(TileHeader.End.Text(it, TileTextStyle.META, RokidHudTokens.TEXT_SECONDARY)) }
                .orEmpty()
            HeaderMeta.SYNC -> sync.ifEmpty { content.source }
            HeaderMeta.SYNC_SOURCE -> joinDot(sync, content.source)
        }
        return if (text.isEmpty()) emptyList() else listOf(TileHeader.End.Text(text, color = RokidHudTokens.TEXT_SECONDARY))
    }

    /** Lays the foot out upward from [bottom] into [ops]; returns its top. */
    private fun foot(
        ops: MutableList<TileOp>,
        foot: Foot,
        content: TileContent.Lines,
        ink: TileInk,
        width: Int,
        bottom: Int,
        position: Long?,
        duration: Long?,
        progress: Float?,
    ): Int {
        val pad = TileRenderer.PADDING
        val inner = width - 2 * pad
        var y = bottom
        fun track() {
            if (progress == null) return
            ops += TileOp.Track(
                pad.toFloat(), (y - GenericTileLayout.TRACK_HEIGHT).toFloat(), (width - pad).toFloat(), y.toFloat(),
                progress, ink.line, ink.primary,
            )
            y -= GenericTileLayout.TRACK_HEIGHT
        }
        when (foot) {
            Foot.NONE -> Unit
            Foot.TRACK -> track()
            Foot.META_TRACK -> {
                track()
                if (progress != null) y -= 6
                val meta = trackMeta(content)
                if (meta.isNotEmpty()) {
                    y -= TileTextStyle.META.lineHeightPx
                    ops += TileText.line(TilePart.TRACK_META, meta, TileTextStyle.META, ink.secondary, pad, y, inner).lines
                }
            }
            Foot.META_TIME_TRACK -> {
                track()
                if (progress != null) y -= 6
                y -= TileTextStyle.SMALL.lineHeightPx
                val time = when {
                    position == null -> ""
                    duration == null -> TileTime.clock(position)
                    else -> "${TileTime.clock(position)} / ${TileTime.clock(duration)}"
                }
                var right = width - pad
                if (time.isNotEmpty()) {
                    val timeWidth = TileText.desiredWidth(time, TileTextStyle.META)
                    val timeBlock = TileText.line(TilePart.TIME, time, TileTextStyle.META, ink.primary, right - timeWidth, 0, timeWidth)
                    // Baseline-aligned with the title run beside it.
                    val titleBaseline = y + TileText.line(TilePart.TRACK_TITLE, "x", TileTextStyle.SMALL, 0, 0, 0, inner).baseline
                    ops += timeBlock.lines.map { it.shiftedBy(titleBaseline - timeBlock.baseline) }
                    right -= timeWidth + RokidHudTokens.SPACE_2
                }
                val titleWidth = minOf(TileText.desiredWidth(content.title, TileTextStyle.SMALL), right - pad)
                if (content.title.isNotBlank()) {
                    ops += TileText.line(TilePart.TRACK_TITLE, content.title, TileTextStyle.SMALL, ink.primary, pad, y, titleWidth).lines
                }
                val restLeft = pad + if (content.title.isNotBlank()) titleWidth else 0
                val rest = if (content.title.isNotBlank() && content.subtitle.isNotBlank()) " · ${content.subtitle}" else content.subtitle
                if (rest.isNotBlank() && right - restLeft > RokidHudTokens.SPACE_6) {
                    ops += TileText.line(TilePart.TRACK_META, rest, TileTextStyle.SMALL, ink.secondary, restLeft, y, right - restLeft).lines
                }
            }
            Foot.FULL -> {
                if (position != null) {
                    y -= TileTextStyle.META.lineHeightPx
                    ops += TileText.line(TilePart.ELAPSED, TileTime.clock(position), TileTextStyle.META, ink.primary, pad, y, inner).lines
                    if (duration != null) {
                        val text = TileTime.clock(duration)
                        val durationWidth = TileText.desiredWidth(text, TileTextStyle.META)
                        ops += TileText.line(TilePart.DURATION, text, TileTextStyle.META, ink.secondary, width - pad - durationWidth, y, durationWidth).lines
                    }
                    y -= RokidHudTokens.SPACE_2
                }
                if (progress != null) {
                    track()
                    y -= RokidHudTokens.SPACE_2
                }
                if (content.subtitle.isNotBlank()) {
                    y -= TileTextStyle.SMALL.lineHeightPx
                    ops += TileText.line(TilePart.TRACK_META, content.subtitle, TileTextStyle.SMALL, ink.secondary, pad, y, inner).lines
                }
                if (content.title.isNotBlank()) {
                    y -= TileTextStyle.TEXT.lineHeightPx
                    ops += TileText.line(TilePart.TRACK_TITLE, content.title, TileTextStyle.TEXT, ink.primary, pad, y, inner).lines
                }
                if (ops.isNotEmpty()) {
                    y -= RokidHudTokens.SPACE_2 + 1
                    ops += TileOp.Separator(pad.toFloat(), y.toFloat(), (width - pad).toFloat(), ink.line)
                }
            }
        }
        return y
    }

    /** With start times and a position, the last line already started; otherwise the published one. */
    fun currentIndex(content: TileContent.Lines, input: TileRenderInput): Int {
        if (!isTimed(content)) return content.current
        val position = playbackPositionMs(content.positionMs, content.durationMs, content.playing, input)
            ?: return content.current
        return content.lines.indexOfLast { line -> line.startMs?.let { it <= position } == true }.coerceAtLeast(0)
    }

    private fun isTimed(content: TileContent.Lines) = content.lines.any { it.startMs != null }

    /**
     * While playing: the next line's start, and the next whole second where the foot shows the
     * position.
     */
    fun nextChangeAtElapsed(input: TileRenderInput, content: TileContent.Lines, size: TileSize): Long? {
        if (!content.playing) return null
        val position = playbackPositionMs(content.positionMs, content.durationMs, true, input) ?: return null
        val duration = content.durationMs
        if (duration != null && duration > 0 && position >= duration) return null
        val nextLine = if (isTimed(content)) {
            content.lines.mapNotNull { it.startMs }.filter { it > position }.minOrNull()?.let { input.nowElapsed + it - position }
        } else {
            null
        }
        val moving = SPECS.getValue(size).foot != Foot.NONE && duration != null && duration > 0
        val nextSecond = if (moving) input.nowElapsed + TileTime.untilClockChanges(position) else null
        return listOfNotNull(nextLine, nextSecond).minOrNull()
    }

    private fun trackMeta(content: TileContent.Lines) = joinDot(content.title, content.subtitle)

    private fun joinDot(vararg parts: String) = parts.filter { it.isNotBlank() }.joinToString(" · ")
}
