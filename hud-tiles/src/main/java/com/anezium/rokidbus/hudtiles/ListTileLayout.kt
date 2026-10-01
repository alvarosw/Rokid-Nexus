package com.anezium.rokidbus.hudtiles

import android.graphics.Rect
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileContent.ListContent
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * [TileContent.ListContent] at every size, following the Relay, News and Feeds artboards. The
 * header's right end carries the summary. Items fill the body top-down while each fits wholly (its
 * title, and one paragraph line when it has a paragraph), with a divider between items and
 * sections; what does not fit is counted into "+N more" where the size has a foot line.
 *
 * An item with a paragraph is a message: its title on one line with the detail and age at the
 * right, the paragraph under it. An item without one is a headline: its title on up to a few
 * lines, then a line with its detail (or its section's title) and age.
 *
 * Every per-size decision is in [SPECS]. When the first item does not fit even so, the layout drops,
 * in order, the paragraphs, the details and the section headers; an item title never drops.
 */
internal object ListTileLayout {
    data class Spec(
        val top: Int,
        val maxItems: Int,
        /** An item's initials or glyph box at its left. */
        val leading: Boolean = false,
        /** A message's detail beside its age; a headline's meta line shows it from width 2. */
        val rowDetail: Boolean = false,
        val compactAge: Boolean = false,
        /** The first item's age on the foot line instead of beside its title. */
        val ageAtFoot: Boolean = false,
        /** A message's title: the first item, the rest. */
        val name: TileTextStyle,
        val restName: TileTextStyle = TileTextStyle.SMALL_MEDIUM,
        /** A headline: the first item, the rest, and their line caps. */
        val headline: TileTextStyle,
        val restHeadline: TileTextStyle = TileTextStyle.SMALL,
        val headlineLines: Int,
        val restHeadlineLines: Int = 1,
        /** Paragraph line caps, under the plugin's own `paragraphLines`. */
        val paragraphLines: Int,
        val restParagraphLines: Int = 1,
        val leadParagraphPrimary: Boolean = true,
        val paragraphGap: Int = 0,
        val separatorGap: Int = 6,
        /** A trailing media marker ("[photo]") on its own line under the paragraph. */
        val markersOwnLine: Boolean = false,
        val sectionHeaders: Boolean = false,
        val more: Boolean = false,
        /** "Updated … ago" from the receipt time when the plugin sends no footer. */
        val updated: Boolean = false,
    )

    val SPECS: Map<TileSize, Spec> = mapOf(
        TileSize.SMALL to Spec(
            top = 4, maxItems = 1, ageAtFoot = true, name = TileTextStyle.SMALL_MEDIUM,
            headline = TileTextStyle.SMALL, headlineLines = 2, paragraphLines = 2, leadParagraphPrimary = false,
            paragraphGap = 2,
        ),
        TileSize.WIDE to Spec(
            top = 4, maxItems = 1, name = TileTextStyle.TEXT_MEDIUM, headline = TileTextStyle.TEXT,
            headlineLines = 2, paragraphLines = 2, paragraphGap = 2,
        ),
        TileSize.BANNER to Spec(
            top = 4, maxItems = 1, rowDetail = true, name = TileTextStyle.TEXT_MEDIUM, headline = TileTextStyle.TEXT,
            headlineLines = 2, paragraphLines = 2, paragraphGap = 2,
        ),
        TileSize.TALL to Spec(
            top = 6, maxItems = WIDE_OPEN, compactAge = true, name = TileTextStyle.SMALL_MEDIUM,
            headline = TileTextStyle.SMALL, headlineLines = 3, restHeadlineLines = 3, paragraphLines = 6,
            restParagraphLines = 2, leadParagraphPrimary = false, markersOwnLine = true, more = true,
        ),
        TileSize.LARGE to Spec(
            top = 8, maxItems = WIDE_OPEN, leading = true, name = TileTextStyle.SMALL_MEDIUM,
            headline = TileTextStyle.TEXT, headlineLines = 2, paragraphLines = 3, markersOwnLine = true, more = true,
        ),
        TileSize.PANEL to Spec(
            top = 8, maxItems = WIDE_OPEN, leading = true, rowDetail = true, name = TileTextStyle.SMALL_MEDIUM,
            headline = TileTextStyle.TEXT, headlineLines = 2, paragraphLines = 2, markersOwnLine = true, more = true,
        ),
        TileSize.JUMBO to Spec(
            top = 10, maxItems = WIDE_OPEN, leading = true, rowDetail = true, name = TileTextStyle.TEXT_MEDIUM,
            headline = TileTextStyle.HEADING, headlineLines = 3, paragraphLines = 3, restParagraphLines = 2,
            markersOwnLine = true, sectionHeaders = true, more = true, updated = true,
        ),
    )

    /** What the layout gives up, cumulatively, when the first item does not fit. */
    enum class Drop { NOTHING, PARAGRAPHS, DETAILS, SECTION_HEADERS }

    private const val WIDE_OPEN = Int.MAX_VALUE
    private const val LEADING = RokidHudTokens.ICON_LG
    private const val SECTION_ROW = 20
    private val MARKERS = Regex("""\s*((?:\[[^\[\]]{1,24}]\s*)+)$""")

    fun layout(input: TileRenderInput, content: ListContent, size: TileSize): TileLayout {
        val spec = SPECS.getValue(size)
        val width = TileRenderer.widthOf(size)
        val height = TileRenderer.heightOf(size)
        val pad = TileRenderer.PADDING
        val header = TileHeader.layout(input, width, size.cols, content.summary, content.summaryShort)
        val bottom = height - pad
        val top = header.bottom + spec.top
        var placed = fitting(input, content, size, top, bottom)
        val footTop = bottom - TileTextStyle.META.lineHeightPx
        if (placed.footNeeded()) placed = Placement(input, content, size, spec, placed.drop).place(top, footTop - RokidHudTokens.SPACE_1)
        val footer = if (placed.footNeeded()) placed.foot(footTop, width) else emptyList()
        val bodyBottom = if (footer.isEmpty()) bottom else footTop - RokidHudTokens.SPACE_1
        return TileLayout(width, height, header.ops, placed.ops, Rect(pad, header.bottom, width - pad, bodyBottom), footer)
    }

    /** The first [Drop] at which the first item fits wholly between [top] and [bottom]. */
    private fun fitting(input: TileRenderInput, content: ListContent, size: TileSize, top: Int, bottom: Int): Placement {
        val spec = SPECS.getValue(size)
        return Drop.entries.asSequence()
            .map { drop -> Placement(input, content, size, spec, drop).place(top, bottom) }
            .firstOrNull { it.leadFits }
            ?: Placement(input, content, size, spec, Drop.SECTION_HEADERS).place(top, bottom)
    }

    /** What [content] gives up to fit a body [room] px high at [size]; for tests. */
    internal fun dropFor(input: TileRenderInput, content: ListContent, size: TileSize, room: Int): Pair<Drop, List<String>> {
        val placed = fitting(input, content, size, 0, room)
        return placed.drop to placed.ops.filterIsInstance<TileOp.Text>().filter { it.part == TilePart.ITEM_TITLE }.map { it.text }
    }

    /** The next minute of any item's age, and the next step of the "Updated" line where it shows. */
    fun nextChangeAtElapsed(input: TileRenderInput, content: ListContent, size: TileSize): Long? {
        val since = (input.nowElapsed - input.receivedAtElapsed).coerceAtLeast(0)
        val ages = content.items.mapNotNull { item -> item.ageMs?.let { TileTime.untilAgeChanges(it + since) } }
        val updated = if (SPECS.getValue(size).updated && content.footer.isBlank()) TileTime.untilUpdatedChanges(since) else null
        return (ages + listOfNotNull(updated)).minOrNull()?.let { input.nowElapsed + it }
    }

    /** One attempt at the list with [drop] applied. */
    private class Placement(
        val input: TileRenderInput,
        val content: ListContent,
        val size: TileSize,
        val spec: Spec,
        val drop: Drop,
    ) {
        val ink = TileInk(input)
        val ops = ArrayList<TileOp>()
        var shown = 0
        /** Nothing to fit counts as fitting: a list of section headers only keeps them. */
        var leadFits = content.items.isEmpty()
        private val pad = TileRenderer.PADDING
        private val width = TileRenderer.widthOf(size)
        private val since = (input.nowElapsed - input.receivedAtElapsed).coerceAtLeast(0)
        private val headersShown = spec.sectionHeaders && drop < Drop.SECTION_HEADERS
        private val paragraphs = drop < Drop.PARAGRAPHS
        private val details = drop < Drop.DETAILS
        private val indented = spec.leading && content.items.any { leadingOp(it, 0, 0) != null }

        val hidden: Int get() = content.overflow + content.items.size - shown

        private val leadAge: String
            get() = content.items.firstOrNull()?.ageMs?.let { age(it) }.orEmpty()

        private val footRight: String
            get() = when {
                size.rows >= 2 && content.footer.isNotBlank() -> content.footer
                spec.updated -> TileTime.updated(since)
                else -> ""
            }

        fun footNeeded(): Boolean =
            (spec.ageAtFoot && leadAge.isNotEmpty() && shown > 0) || (spec.more && hidden > 0) || footRight.isNotEmpty()

        fun place(top: Int, bottom: Int): Placement {
            var y = top
            var first = true
            loop@ for (section in content.sections) {
                var afterHeader = false
                if (headersShown && section.title.isNotBlank()) {
                    val separator = if (first) 0 else 2 * spec.separatorGap + 1
                    if (y + separator + SECTION_ROW > bottom) break@loop
                    if (!first) {
                        ops += TileOp.Separator(pad.toFloat(), (y + spec.separatorGap).toFloat(), (width - pad).toFloat(), ink.line)
                    }
                    sectionRow(section, y + separator)
                    y += separator + SECTION_ROW
                    first = false
                    afterHeader = true
                }
                for (item in section.items) {
                    if (shown >= spec.maxItems) break@loop
                    val separator = if (first || afterHeader) 0 else 2 * spec.separatorGap + 1
                    val itemOps = ArrayList<TileOp>()
                    val height = item(itemOps, section, item, lead = shown == 0, top = y + separator, bottom = bottom)
                        ?: break@loop
                    if (separator > 0) {
                        val left = pad + if (indented) LEADING + RokidHudTokens.SPACE_2 else 0
                        ops += TileOp.Separator(left.toFloat(), (y + spec.separatorGap).toFloat(), (width - pad).toFloat(), ink.line)
                    }
                    ops += itemOps
                    y += separator + height
                    if (shown == 0) leadFits = true
                    shown++
                    first = false
                    afterHeader = false
                }
            }
            return this
        }

        fun foot(top: Int, width: Int): List<TileOp> {
            val left = when {
                spec.more && hidden > 0 -> "+$hidden more"
                spec.ageAtFoot -> leadAge
                else -> ""
            }
            val inner = width - 2 * pad
            val out = ArrayList<TileOp>()
            val rightWidth = if (footRight.isEmpty()) 0 else minOf(TileText.desiredWidth(footRight, TileTextStyle.META), inner)
            if (rightWidth > 0) {
                out += TileText.line(TilePart.FOOTER, footRight, TileTextStyle.META, ink.secondary, width - pad - rightWidth, top, rightWidth).lines
            }
            val leftRoom = inner - if (rightWidth > 0) rightWidth + RokidHudTokens.SPACE_2 else 0
            if (left.isNotEmpty() && leftRoom > 0) {
                out += TileText.line(TilePart.MORE, left, TileTextStyle.META, ink.secondary, pad, top, leftRoom).lines
            }
            return out
        }

        private fun sectionRow(section: ListContent.Section, top: Int) {
            val inner = width - 2 * pad
            val detailWidth = if (section.detail.isEmpty()) 0 else TileText.desiredWidth(section.detail, TileTextStyle.FIGURE)
            val titleWidth = inner - if (detailWidth > 0) detailWidth + RokidHudTokens.SPACE_2 else 0
            val title = TileText.line(TilePart.SECTION_TITLE, section.title, TileTextStyle.SMALL, ink.primary, pad, top, titleWidth)
            val shift = (SECTION_ROW - TileTextStyle.SMALL.lineHeightPx) / 2
            ops += title.lines.map { it.shiftedBy(shift) }
            if (detailWidth > 0) {
                val detail = TileText.line(TilePart.SECTION_DETAIL, section.detail, TileTextStyle.FIGURE, ink.primary, width - pad - detailWidth, 0, detailWidth)
                ops += detail.lines.map { it.shiftedBy(top + shift + title.baseline - detail.baseline) }
            }
        }

        /** Lays one item out from [top]; null when it does not fit wholly above [bottom]. */
        private fun item(
            out: MutableList<TileOp>,
            section: ListContent.Section,
            item: ListContent.Item,
            lead: Boolean,
            top: Int,
            bottom: Int,
        ): Int? {
            val left = pad + if (indented) LEADING + RokidHudTokens.SPACE_2 else 0
            val columnWidth = width - pad - left
            val detail = if (!details) "" else item.detail.ifBlank { if (headersShown) "" else section.title }
            val age = if (spec.ageAtFoot && lead) "" else item.ageMs?.let { age(it) }.orEmpty()
            val titleInk = if (lead) ink.emphasis else ink.primary
            val column = Column(out, left, columnWidth, top, bottom)

            val height = if (paragraphs && item.paragraph.isNotBlank()) {
                val style = if (lead) spec.name else spec.restName
                val meta = joinDot(detail.takeIf { spec.rowDetail }.orEmpty(), age)
                if (top + style.lineHeightPx + spec.paragraphGap + TileTextStyle.SMALL.lineHeightPx > bottom) return null
                val metaWidth = if (meta.isEmpty()) 0 else minOf(TileText.desiredWidth(meta, TileTextStyle.META), columnWidth / 2)
                val title = column.text(
                    TilePart.ITEM_TITLE, item.title, style, titleInk, 1,
                    width = columnWidth - if (metaWidth > 0) metaWidth + RokidHudTokens.SPACE_2 else 0,
                ) ?: return null
                if (metaWidth > 0) {
                    val metaBlock = TileText.line(TilePart.ITEM_META, meta, TileTextStyle.META, ink.secondary, width - pad - metaWidth, 0, metaWidth)
                    val baseline = title.lines.first().baseline
                    out += metaBlock.lines.map { it.shiftedBy((baseline - metaBlock.lines.first().baseline).toInt()) }
                }
                val split = if (spec.markersOwnLine) MARKERS.find(item.paragraph) else null
                val text = split?.let { item.paragraph.substring(0, it.range.first) } ?: item.paragraph
                val cap = minOf(content.paragraphLines, if (lead) spec.paragraphLines else spec.restParagraphLines)
                val paragraphInk = if (lead && spec.leadParagraphPrimary) ink.primary else ink.secondary
                column.text(TilePart.PARAGRAPH, text, TileTextStyle.SMALL, paragraphInk, cap, gap = spec.paragraphGap)
                split?.let { column.text(TilePart.MARKER, it.groupValues[1].trim(), TileTextStyle.META, ink.secondary, 1, gap = 2) }
                column.y - top
            } else {
                val style = if (lead) spec.headline else spec.restHeadline
                val meta = joinDot(detail.takeIf { size.cols >= 2 }.orEmpty(), age)
                val metaHeight = if (meta.isEmpty()) 0 else 2 + TileTextStyle.META.lineHeightPx
                val fit = minOf(if (lead) spec.headlineLines else spec.restHeadlineLines, (bottom - top - metaHeight) / style.lineHeightPx)
                if (fit <= 0) return null
                column.text(TilePart.ITEM_TITLE, item.title, style, titleInk, fit) ?: return null
                column.text(TilePart.ITEM_META, meta, TileTextStyle.META, ink.secondary, 1, gap = 2)
                column.y - top
            }
            val leading = if (indented) leadingOp(item, pad, top) else null
            leading?.let { out += it }
            return if (leading != null) maxOf(height, LEADING) else height
        }

        private fun leadingOp(item: ListContent.Item, left: Int, top: Int): TileOp? = when (val leading = item.leading) {
            null -> null
            is ListContent.Leading.Initials -> TileOp.InitialsBox(
                left.toFloat(), top.toFloat(), LEADING.toFloat(), leading.text, ink.primary, ink.secondary,
            )
            is ListContent.Leading.Glyph -> input.glyph(leading.name)?.let { drawable ->
                TileOp.GlyphBox(drawable, left.toFloat(), top.toFloat(), LEADING.toFloat(), ink.primary, ink.secondary)
            }
        }

        private fun age(ms: Long) = TileTime.age(ms + since, spec.compactAge)
    }

    private fun joinDot(vararg parts: String) = parts.filter { it.isNotBlank() }.joinToString(" · ")
}
