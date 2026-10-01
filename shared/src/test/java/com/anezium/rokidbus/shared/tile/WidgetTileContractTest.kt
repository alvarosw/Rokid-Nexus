package com.anezium.rokidbus.shared.tile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetTileContractTest {
    private fun snapshot() = TileSnapshot(
        pluginId = "transit",
        contentKey = "eta-42",
        title = "12",
        subtitle = "min to Downtown",
        badge = "!",
        progress = 0.5f,
        unit = "min",
        tone = TileTone.WARN,
        rows = listOf("Downtown 12m", "Uptown 4m"),
    )

    @Test
    fun `round trips through toPayload and fromPayload`() {
        val decoded = WidgetTileContract.fromPayload(WidgetTileContract.toPayload(snapshot()))
        assertEquals(snapshot(), decoded)
    }

    @Test
    fun `fromPayload rejects null and malformed payloads without throwing`() {
        assertNull(WidgetTileContract.fromPayload(null))
        assertNull(WidgetTileContract.fromPayload(JSONObject()))
        assertNull(WidgetTileContract.fromPayload(JSONObject().put("pluginId", "").put("title", "x")))
        assertNull(
            WidgetTileContract.fromPayload(
                JSONObject().put("pluginId", "x".repeat(129)).put("title", "x"),
            ),
        )
        assertNull(
            WidgetTileContract.fromPayload(
                JSONObject().put("pluginId", "p").put("title", "x".repeat(121)),
            ),
        )
    }

    @Test
    fun `fromPayload clamps out-of-range progress and unknown tone falls back to OFF`() {
        val decoded = WidgetTileContract.fromPayload(
            JSONObject()
                .put("pluginId", "p")
                .put("title", "x")
                .put("progress", 42.0)
                .put("tone", "not-a-tone"),
        )
        assertEquals(1f, (decoded?.content as TileContent.Generic).progress)
        assertEquals(TileTone.OFF, decoded?.tone)
    }

    @Test
    fun `fromPayload truncates rows past the cap rather than rejecting`() {
        val payload = JSONObject()
            .put("pluginId", "p")
            .put("title", "x")
            .put("rows", org.json.JSONArray(listOf("a", "b", "c", "d", "e")))
        val decoded = WidgetTileContract.fromPayload(payload)
        assertEquals(WidgetTileContract.MAX_ROWS, (decoded?.content as TileContent.Generic).rows.size)
    }

    @Test
    fun `construction rejects fields past their bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot(pluginId = "", contentKey = "x", title = "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot(pluginId = "p", contentKey = "x".repeat(129), title = "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot(pluginId = "p", contentKey = "x", title = "x".repeat(121))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot(pluginId = "p", contentKey = "x", title = "x", badge = "x".repeat(25))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot(pluginId = "p", contentKey = "x", title = "x", rows = List(5) { "r" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot(pluginId = "p", contentKey = "x", title = "x", rows = listOf("x".repeat(121)))
        }
    }

    @Test
    fun `tone wire values match the five sanctioned Status states`() {
        assertTrue(TileTone.entries.map { it.wireValue }.containsAll(listOf("ok", "info", "warn", "critical", "off")))
        assertEquals(TileTone.CRITICAL, TileTone.fromWireValue("critical"))
        assertNull(TileTone.fromWireValue("bogus"))
    }

    private fun music() = TileContent.Music(
        title = "Harbour Lights",
        artist = "Nova Reyes",
        album = "Low Tide",
        source = "Spotify",
        playing = true,
        positionMs = 83_000,
        durationMs = 214_000,
        artworkKey = "art-1",
    )

    private fun lines() = TileContent.Lines(
        lines = listOf(
            TileContent.Lines.Line("First line", 1_000),
            TileContent.Lines.Line("Second line", 5_000),
            TileContent.Lines.Line("Third line", 9_000),
        ),
        current = 1,
        title = "Harbour Lights",
        subtitle = "Nova Reyes",
        source = "LrcLib",
        positionMs = 6_000,
        durationMs = 214_000,
        playing = true,
    )

    private fun list() = TileContent.ListContent(
        sections = listOf(
            TileContent.ListContent.Section(
                title = "Relay",
                detail = "3 new",
                items = listOf(
                    TileContent.ListContent.Item(
                        title = "Ana Ruiz",
                        detail = "Signal",
                        paragraph = "Running ten minutes late [photo]",
                        ageMs = 60_000,
                        leading = TileContent.ListContent.Leading.Initials("AR"),
                    ),
                    TileContent.ListContent.Item(
                        title = "Build bot",
                        leading = TileContent.ListContent.Leading.Glyph("bell"),
                    ),
                ),
            ),
            TileContent.ListContent.Section(
                items = listOf(TileContent.ListContent.Item(title = "Third")),
            ),
        ),
        summary = "3 new",
        summaryShort = "3",
        footer = "Updated",
        paragraphLines = 3,
        overflow = 4,
    )

    private fun roundTrip(content: TileContent, tone: TileTone = TileTone.INFO): TileSnapshot? {
        val snapshot = TileSnapshot("p", "k", content, tone, staleAfterMs = 120_000)
        return WidgetTileContract.fromPayload(WidgetTileContract.toPayload(snapshot))
    }

    @Test
    fun `every template round trips through the wire`() {
        listOf(music(), lines(), list(), TileContent.Generic(title = "12", unit = "min")).forEach { content ->
            assertEquals(
                TileSnapshot("p", "k", content, TileTone.INFO, staleAfterMs = 120_000),
                roundTrip(content),
            )
        }
    }

    @Test
    fun `optional music fields survive as null and playing as false`() {
        val bare = TileContent.Music(title = "T", playing = false)
        assertEquals(bare, (roundTrip(bare))?.content)
    }

    @Test
    fun `payload carries the template, the content and always the legacy fields`() {
        val payload = WidgetTileContract.toPayload(TileSnapshot("p", "k", music()))
        assertEquals("music", payload.getString("template"))
        assertEquals("Harbour Lights", payload.getJSONObject("content").getString("title"))
        assertEquals(WidgetTileContract.DEFAULT_STALE_AFTER_MS, payload.getLong("staleAfterMs"))
        assertEquals("Harbour Lights", payload.getString("title"))
        assertEquals("Nova Reyes \u00B7 Low Tide", payload.getString("subtitle"))
        assertEquals(83_000f / 214_000f, payload.getDouble("progress").toFloat(), 1e-6f)
        assertEquals("list", WidgetTileContract.toPayload(TileSnapshot("p", "k", list())).getString("template"))
        assertEquals("lines", WidgetTileContract.toPayload(TileSnapshot("p", "k", lines())).getString("template"))
        assertEquals(
            "generic",
            WidgetTileContract.toPayload(TileSnapshot("p", "k", title = "x")).getString("template"),
        )
    }

    @Test
    fun `down-level mapping of music`() {
        val generic = WidgetTileContract.downLevel(music())
        assertEquals("Harbour Lights", generic.title)
        assertEquals("Nova Reyes \u00B7 Low Tide", generic.subtitle)
        assertEquals(83_000f / 214_000f, generic.progress!!, 1e-6f)
        assertNull(WidgetTileContract.downLevel(TileContent.Music(title = "T", playing = true)).progress)
        assertNull(
            WidgetTileContract.downLevel(
                TileContent.Music(title = "T", playing = true, positionMs = 5, durationMs = 0),
            ).progress,
        )
    }

    @Test
    fun `down-level mapping of lines shows the current line and the next ones`() {
        val generic = WidgetTileContract.downLevel(lines())
        assertEquals("Second line", generic.title)
        assertEquals("Harbour Lights \u00B7 Nova Reyes", generic.subtitle)
        assertEquals(listOf("Third line"), generic.rows)
        val empty = WidgetTileContract.downLevel(TileContent.Lines(lines = emptyList(), current = 0, title = "Steps"))
        assertEquals("Steps", empty.title)
        assertTrue(empty.rows.isEmpty())
    }

    @Test
    fun `down-level mapping of a list`() {
        val generic = WidgetTileContract.downLevel(list())
        assertEquals("3 new", generic.title)
        assertEquals("3", generic.badge)
        assertEquals(
            listOf("Ana Ruiz - Running ten minutes late [photo]", "Build bot", "Third"),
            generic.rows,
        )
        val unsummarised = WidgetTileContract.downLevel(
            TileContent.ListContent(
                sections = listOf(
                    TileContent.ListContent.Section(
                        items = listOf(
                            TileContent.ListContent.Item("One"),
                            TileContent.ListContent.Item("Two"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals("One", unsummarised.title)
        assertEquals(listOf("Two"), unsummarised.rows)
    }

    @Test
    fun `down-level output always satisfies the generic bounds`() {
        val long = TileContent.ListContent(
            sections = listOf(
                TileContent.ListContent.Section(
                    items = List(6) {
                        TileContent.ListContent.Item("t".repeat(120), paragraph = "p".repeat(280))
                    },
                ),
            ),
            summaryShort = "123456",
        )
        val generic = WidgetTileContract.downLevel(long)
        assertTrue(generic.rows.all { it.length <= WidgetTileContract.MAX_ROW_CHARS })
        assertTrue(generic.rows.size <= WidgetTileContract.MAX_ROWS)
    }

    @Test
    fun `a payload without template decodes as generic from the legacy fields`() {
        val decoded = WidgetTileContract.fromPayload(
            JSONObject()
                .put("pluginId", "p")
                .put("contentKey", "k")
                .put("title", "12")
                .put("subtitle", "min")
                .put("unit", "min")
                .put("rows", org.json.JSONArray(listOf("a")))
                .put("tone", "ok"),
        )!!
        assertEquals(TileContent.Generic(title = "12", subtitle = "min", unit = "min", rows = listOf("a")), decoded.content)
        assertEquals(TileTone.OK, decoded.tone)
        assertEquals(WidgetTileContract.DEFAULT_STALE_AFTER_MS, decoded.staleAfterMs)
    }

    @Test
    fun `an unknown template decodes as generic from the legacy fields`() {
        val decoded = WidgetTileContract.fromPayload(
            JSONObject()
                .put("pluginId", "p")
                .put("title", "legacy title")
                .put("template", "hologram")
                .put("content", JSONObject().put("title", "ignored")),
        )!!
        assertEquals(TileContent.Generic(title = "legacy title"), decoded.content)
    }

    @Test
    fun `a known template without a content object falls back to the legacy fields`() {
        val decoded = WidgetTileContract.fromPayload(
            JSONObject().put("pluginId", "p").put("title", "legacy").put("template", "music"),
        )!!
        assertEquals(TileContent.Generic(title = "legacy"), decoded.content)
    }

    @Test
    fun `staleAfterMs is clamped on decode and rejected out of range at construction`() {
        fun decodeStale(value: Long) = WidgetTileContract.fromPayload(
            JSONObject().put("pluginId", "p").put("title", "x").put("staleAfterMs", value),
        )!!.staleAfterMs
        assertEquals(WidgetTileContract.MIN_STALE_AFTER_MS, decodeStale(5))
        assertEquals(WidgetTileContract.MAX_STALE_AFTER_MS, decodeStale(Long.MAX_VALUE))
        assertEquals(90_000, decodeStale(90_000))
        assertThrows(IllegalArgumentException::class.java) { TileSnapshot("p", "k", music(), staleAfterMs = 1_000) }
        assertThrows(IllegalArgumentException::class.java) {
            TileSnapshot("p", "k", music(), staleAfterMs = WidgetTileContract.MAX_STALE_AFTER_MS + 1)
        }
    }

    @Test
    fun `content bounds are enforced at construction`() {
        val item = TileContent.ListContent.Item("i")
        fun section(count: Int) = TileContent.ListContent.Section(items = List(count) { item })
        assertThrows(IllegalArgumentException::class.java) { TileContent.Music(title = "x".repeat(121), playing = true) }
        assertThrows(IllegalArgumentException::class.java) { TileContent.Music(title = "x", playing = true, positionMs = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            TileContent.Lines(lines = List(10) { TileContent.Lines.Line("l") }, current = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileContent.Lines(lines = listOf(TileContent.Lines.Line("l")), current = 1)
        }
        assertThrows(IllegalArgumentException::class.java) { TileContent.Lines.Line("x".repeat(121)) }
        assertThrows(IllegalArgumentException::class.java) {
            TileContent.ListContent(sections = List(4) { section(1) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileContent.ListContent(sections = listOf(section(4), section(3)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileContent.ListContent(sections = emptyList(), summaryShort = "1234567")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileContent.ListContent(sections = emptyList(), summary = "x".repeat(61))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileContent.ListContent(sections = emptyList(), paragraphLines = 7)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileContent.ListContent(sections = emptyList(), paragraphLines = 0)
        }
        assertThrows(IllegalArgumentException::class.java) { TileContent.ListContent.Item("i", detail = "x".repeat(61)) }
        assertThrows(IllegalArgumentException::class.java) {
            TileContent.ListContent.Item("i", paragraph = "x".repeat(281))
        }
        assertThrows(IllegalArgumentException::class.java) { TileContent.ListContent.Leading.Initials("") }
        assertThrows(IllegalArgumentException::class.java) { TileContent.ListContent.Leading.Initials("ABC") }
        assertThrows(IllegalArgumentException::class.java) { TileContent.ListContent.Leading.Glyph("") }
        // At the limits everything is accepted, and a 280-char paragraph is fine.
        TileContent.ListContent.Item("i", detail = "x".repeat(60), paragraph = "x".repeat(280))
        TileContent.ListContent(sections = listOf(section(3), section(3)), paragraphLines = 6)
    }

    @Test
    fun `fromPayload clamps template content past its bounds instead of throwing`() {
        val items = org.json.JSONArray()
        repeat(9) { items.put(JSONObject().put("title", "t$it").put("paragraph", "p".repeat(400))) }
        val sections = org.json.JSONArray()
        repeat(5) { sections.put(JSONObject().put("title", "s").put("items", items)) }
        val decoded = WidgetTileContract.fromPayload(
            JSONObject()
                .put("pluginId", "p")
                .put("template", "list")
                .put(
                    "content",
                    JSONObject()
                        .put("sections", sections)
                        .put("summary", "s".repeat(100))
                        .put("summaryShort", "123456789")
                        .put("paragraphLines", 99)
                        .put("overflow", -5),
                ),
        )!!
        val content = decoded.content as TileContent.ListContent
        assertEquals(WidgetTileContract.MAX_SECTIONS, content.sections.size)
        assertEquals(WidgetTileContract.MAX_LIST_ITEMS, content.items.size)
        assertEquals(WidgetTileContract.MAX_PARAGRAPH_CHARS, content.items.first().paragraph.length)
        assertEquals(WidgetTileContract.MAX_DETAIL_CHARS, content.summary.length)
        assertEquals(WidgetTileContract.MAX_SUMMARY_SHORT_CHARS, content.summaryShort.length)
        assertEquals(WidgetTileContract.MAX_PARAGRAPH_LINES, content.paragraphLines)
        assertEquals(0, content.overflow)

        val lines = org.json.JSONArray()
        repeat(20) { lines.put(JSONObject().put("text", "l$it").put("startMs", -3)) }
        val decodedLines = WidgetTileContract.fromPayload(
            JSONObject()
                .put("pluginId", "p")
                .put("template", "lines")
                .put("content", JSONObject().put("lines", lines).put("current", 50).put("positionMs", -1)),
        )!!.content as TileContent.Lines
        assertEquals(WidgetTileContract.MAX_LINES, decodedLines.lines.size)
        assertEquals(WidgetTileContract.MAX_LINES - 1, decodedLines.current)
        assertNull(decodedLines.positionMs)
        assertNull(decodedLines.lines.first().startMs)
    }

    @Test
    fun `fromPayload drops an unusable leading marker and keeps the item`() {
        val decoded = WidgetTileContract.fromPayload(
            JSONObject()
                .put("pluginId", "p")
                .put("template", "list")
                .put(
                    "content",
                    JSONObject().put(
                        "sections",
                        org.json.JSONArray().put(
                            JSONObject().put(
                                "items",
                                org.json.JSONArray()
                                    .put(JSONObject().put("title", "a").put("leading", JSONObject().put("type", "mystery")))
                                    .put(JSONObject().put("title", "b").put("leading", JSONObject().put("type", "initials").put("text", "XYZ"))),
                            ),
                        ),
                    ),
                ),
        )!!.content as TileContent.ListContent
        assertNull(decoded.items[0].leading)
        assertEquals(TileContent.ListContent.Leading.Initials("XY"), decoded.items[1].leading)
    }

    @Test
    fun `the largest legal list stays within the payload cap`() {
        val item = TileContent.ListContent.Item(
            title = "t".repeat(120),
            detail = "d".repeat(60),
            paragraph = "\u00e9".repeat(280),
            ageMs = Long.MAX_VALUE,
            leading = TileContent.ListContent.Leading.Initials("AR"),
        )
        val content = TileContent.ListContent(
            sections = List(3) {
                TileContent.ListContent.Section("s".repeat(120), "d".repeat(60), List(2) { item })
            },
            summary = "s".repeat(60),
            footer = "f".repeat(120),
        )
        val bytes = WidgetTileContract.toPayload(TileSnapshot("p", "k", content)).toString().toByteArray().size
        assertTrue("payload is $bytes bytes", bytes <= WidgetTileContract.MAX_PAYLOAD_BYTES)
    }
}
