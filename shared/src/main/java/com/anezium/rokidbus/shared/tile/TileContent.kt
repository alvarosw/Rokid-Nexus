package com.anezium.rokidbus.shared.tile

/**
 * What a plugin's tile shows: data only, in one of four templates. The hub owns every layout
 * decision per size. Bounds are enforced at construction so a plugin process cannot build an
 * oversized value, and re-checked defensively by [WidgetTileContract.fromPayload].
 */
sealed interface TileContent {
    val template: Template

    enum class Template(val wireValue: String) {
        GENERIC("generic"),
        MUSIC("music"),
        LINES("lines"),
        LIST("list"),
        ;

        companion object {
            fun fromWireValue(value: String): Template? = entries.firstOrNull { it.wireValue == value }
        }
    }

    /** Every plugin that does not fit a richer template. */
    data class Generic(
        val title: String,
        val subtitle: String = "",
        val badge: String = "",
        val progress: Float? = null,
        val unit: String = "",
        val rows: List<String> = emptyList(),
    ) : TileContent {
        override val template: Template get() = Template.GENERIC

        init {
            requireText("title", title, WidgetTileContract.MAX_TITLE_CHARS)
            requireText("subtitle", subtitle, WidgetTileContract.MAX_SUBTITLE_CHARS)
            requireText("badge", badge, WidgetTileContract.MAX_BADGE_CHARS)
            require(progress == null || progress in 0f..1f) { "progress must be 0f..1f" }
            requireText("unit", unit, WidgetTileContract.MAX_UNIT_CHARS)
            require(rows.size <= WidgetTileContract.MAX_ROWS) {
                "rows must have <= ${WidgetTileContract.MAX_ROWS} entries"
            }
            require(rows.all { it.length <= WidgetTileContract.MAX_ROW_CHARS }) {
                "each row must be <= ${WidgetTileContract.MAX_ROW_CHARS} chars"
            }
        }
    }

    /** A playing track. */
    data class Music(
        val title: String,
        val artist: String = "",
        val album: String = "",
        val source: String = "",
        val playing: Boolean,
        /** Position at publish time; the glasses advance it while [playing]. */
        val positionMs: Long? = null,
        val durationMs: Long? = null,
        /** Resolved through the tile artwork path (Delivery A); empty means no art. */
        val artworkKey: String = "",
    ) : TileContent {
        override val template: Template get() = Template.MUSIC

        init {
            requireText("title", title, WidgetTileContract.MAX_TITLE_CHARS)
            requireText("artist", artist, WidgetTileContract.MAX_TITLE_CHARS)
            requireText("album", album, WidgetTileContract.MAX_TITLE_CHARS)
            requireText("source", source, WidgetTileContract.MAX_TITLE_CHARS)
            requireTime("positionMs", positionMs)
            requireTime("durationMs", durationMs)
            requireText("artworkKey", artworkKey, WidgetTileContract.MAX_CONTENT_KEY_CHARS)
        }
    }

    /** A scrolling list of lines with the current one in the middle: lyrics, steps. */
    data class Lines(
        val lines: List<Line>,
        /** Index shown centered when the lines carry no start times. */
        val current: Int,
        val title: String = "",
        val subtitle: String = "",
        val source: String = "",
        val positionMs: Long? = null,
        val durationMs: Long? = null,
        val playing: Boolean = false,
    ) : TileContent {
        override val template: Template get() = Template.LINES

        init {
            require(lines.size <= WidgetTileContract.MAX_LINES) {
                "lines must have <= ${WidgetTileContract.MAX_LINES} entries"
            }
            require(lines.isEmpty() && current == 0 || current in lines.indices) {
                "current must index lines"
            }
            requireText("title", title, WidgetTileContract.MAX_TITLE_CHARS)
            requireText("subtitle", subtitle, WidgetTileContract.MAX_TITLE_CHARS)
            requireText("source", source, WidgetTileContract.MAX_TITLE_CHARS)
            requireTime("positionMs", positionMs)
            requireTime("durationMs", durationMs)
        }

        data class Line(val text: String, val startMs: Long? = null) {
            init {
                requireText("line text", text, WidgetTileContract.MAX_TITLE_CHARS)
                requireTime("startMs", startMs)
            }
        }
    }

    /** Sectioned list: messages, headlines, posts. */
    data class ListContent(
        val sections: List<Section>,
        /** Header right at width >= 2, e.g. "3 new". */
        val summary: String = "",
        /** Header right at width 1, e.g. "3". */
        val summaryShort: String = "",
        val footer: String = "",
        /** 1..6: how many lines a paragraph may take. */
        val paragraphLines: Int = DEFAULT_PARAGRAPH_LINES,
        /** Items not sent, rendered as "+N more" where room allows. */
        val overflow: Int = 0,
    ) : TileContent {
        override val template: Template get() = Template.LIST

        init {
            require(sections.size <= WidgetTileContract.MAX_SECTIONS) {
                "sections must have <= ${WidgetTileContract.MAX_SECTIONS} entries"
            }
            require(sections.sumOf { it.items.size } <= WidgetTileContract.MAX_LIST_ITEMS) {
                "a list must carry <= ${WidgetTileContract.MAX_LIST_ITEMS} items in total"
            }
            requireText("summary", summary, WidgetTileContract.MAX_DETAIL_CHARS)
            requireText("summaryShort", summaryShort, WidgetTileContract.MAX_SUMMARY_SHORT_CHARS)
            requireText("footer", footer, WidgetTileContract.MAX_TITLE_CHARS)
            require(paragraphLines in 1..WidgetTileContract.MAX_PARAGRAPH_LINES) {
                "paragraphLines must be 1..${WidgetTileContract.MAX_PARAGRAPH_LINES}"
            }
            require(overflow >= 0) { "overflow must be >= 0" }
        }

        /** Every item across sections, in order. */
        val items: List<Item> get() = sections.flatMap { it.items }

        data class Section(
            val title: String = "",
            val detail: String = "",
            val items: List<Item>,
        ) {
            init {
                requireText("section title", title, WidgetTileContract.MAX_TITLE_CHARS)
                requireText("section detail", detail, WidgetTileContract.MAX_DETAIL_CHARS)
            }
        }

        data class Item(
            val title: String,
            val detail: String = "",
            /** Media markers such as "[photo]" go at its end. */
            val paragraph: String = "",
            /** Age at publish; rendered relative and kept current. */
            val ageMs: Long? = null,
            val leading: Leading? = null,
        ) {
            init {
                requireText("item title", title, WidgetTileContract.MAX_TITLE_CHARS)
                requireText("item detail", detail, WidgetTileContract.MAX_DETAIL_CHARS)
                requireText("paragraph", paragraph, WidgetTileContract.MAX_PARAGRAPH_CHARS)
                requireTime("ageMs", ageMs)
            }
        }

        sealed interface Leading {
            /** 1..2 chars, e.g. "AR". */
            data class Initials(val text: String) : Leading {
                init {
                    require(text.length in 1..WidgetTileContract.MAX_INITIALS_CHARS) {
                        "initials must be 1..${WidgetTileContract.MAX_INITIALS_CHARS} chars"
                    }
                }
            }

            /** A glyph from the plugin's own GLYPHS set. */
            data class Glyph(val name: String) : Leading {
                init {
                    require(name.length in 1..WidgetTileContract.MAX_GLYPH_NAME_CHARS) {
                        "glyph name must be 1..${WidgetTileContract.MAX_GLYPH_NAME_CHARS} chars"
                    }
                }
            }
        }

        companion object {
            const val DEFAULT_PARAGRAPH_LINES = 2
        }
    }
}

private fun requireText(name: String, value: String, max: Int) {
    require(value.length <= max) { "$name must be <= $max chars" }
}

private fun requireTime(name: String, value: Long?) {
    require(value == null || value >= 0) { "$name must be >= 0" }
}
