package com.anezium.rokidbus.shared.tile

import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.ImageSurfaceValidationResult
import com.anezium.rokidbus.shared.MediaArtworkContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * The five-value tone table from the design system's `Status` component
 * (`ok`/`info`/`warn`/`critical`/`off`), and nothing else — a plugin picks a state, never a color.
 * This deliberately does not reuse `SurfaceRow.TONE_*` (`alert`/`normal`/`dim`/`body`); that
 * vocabulary predates this contract's grounding in the actual design system. A caller bridging an
 * existing `SurfaceRow` tone into a tile must map it explicitly at the call site rather than
 * letting the two vocabularies blur together.
 */
enum class TileTone(val wireValue: String) {
    OK("ok"),
    INFO("info"),
    WARN("warn"),
    CRITICAL("critical"),
    OFF("off"),
    ;

    companion object {
        fun fromWireValue(value: String): TileTone? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * The closed-state grid tile payload a plugin publishes over `/tile/publish`. Bounded the same way
 * `SurfaceModels`' typed models are: small hard caps, enforced at construction so a plugin process
 * cannot even build an oversized snapshot, checked again defensively on the hub side by
 * [WidgetTileContract.fromPayload] since the plugin process is never trusted.
 */
data class TileSnapshot(
    val pluginId: String,
    val contentKey: String,
    val content: TileContent,
    val tone: TileTone = TileTone.OFF,
    /** How long after receipt the tile is shown as stale, within [WidgetTileContract.MIN_STALE_AFTER_MS]..[WidgetTileContract.MAX_STALE_AFTER_MS]. */
    val staleAfterMs: Long = WidgetTileContract.DEFAULT_STALE_AFTER_MS,
) {
    /** Builds a [TileContent.Generic] tile from the fields every plugin used before templates. */
    constructor(
        pluginId: String,
        contentKey: String,
        title: String,
        subtitle: String = "",
        badge: String = "",
        progress: Float? = null,
        unit: String = "",
        tone: TileTone = TileTone.OFF,
        rows: List<String> = emptyList(),
    ) : this(
        pluginId = pluginId,
        contentKey = contentKey,
        content = TileContent.Generic(
            title = title,
            subtitle = subtitle,
            badge = badge,
            progress = progress,
            unit = unit,
            rows = rows,
        ),
        tone = tone,
    )

    init {
        require(pluginId.isNotBlank() && pluginId.length <= WidgetTileContract.MAX_PLUGIN_ID_CHARS) {
            "pluginId must be 1..${WidgetTileContract.MAX_PLUGIN_ID_CHARS} chars"
        }
        require(contentKey.length <= WidgetTileContract.MAX_CONTENT_KEY_CHARS) {
            "contentKey must be <= ${WidgetTileContract.MAX_CONTENT_KEY_CHARS} chars"
        }
        require(
            staleAfterMs in WidgetTileContract.MIN_STALE_AFTER_MS..WidgetTileContract.MAX_STALE_AFTER_MS,
        ) {
            "staleAfterMs must be ${WidgetTileContract.MIN_STALE_AFTER_MS}.." +
                "${WidgetTileContract.MAX_STALE_AFTER_MS}"
        }
    }
}

/**
 * Wire shape and bounds for [TileSnapshot], same discipline as `SurfaceModels`'
 * `contentKey <= 128 chars` / `MonoArtwork` caps: a small, bounded payload, checked at
 * construction on the plugin side and re-checked defensively (never trusted) on the hub side.
 */
object WidgetTileContract {
    const val MAX_PLUGIN_ID_CHARS = 128
    const val MAX_CONTENT_KEY_CHARS = 128
    const val MAX_TITLE_CHARS = 120
    const val MAX_SUBTITLE_CHARS = 120
    const val MAX_BADGE_CHARS = 24
    const val MAX_UNIT_CHARS = 16
    const val MAX_ROWS = 4
    const val MAX_ROW_CHARS = 120
    const val MAX_DETAIL_CHARS = 60
    const val MAX_SUMMARY_SHORT_CHARS = 6
    const val MAX_PARAGRAPH_CHARS = 280
    const val MAX_INITIALS_CHARS = 2
    const val MAX_GLYPH_NAME_CHARS = 24
    const val MAX_LINES = 9
    const val MAX_SECTIONS = 3
    const val MAX_LIST_ITEMS = 6
    const val MAX_PARAGRAPH_LINES = 6
    const val MAX_PAYLOAD_BYTES = 12 * 1024

    /** The payload field describing artwork bytes that ride with a music publish. */
    const val ARTWORK_FIELD = "artwork"

    const val DEFAULT_STALE_AFTER_MS = 10 * 60 * 1000L
    const val MIN_STALE_AFTER_MS = 60 * 1000L
    const val MAX_STALE_AFTER_MS = 24 * 60 * 60 * 1000L

    /**
     * The legacy top-level fields are always written next to `template`/`content`, by [downLevel],
     * so a glasses hub that predates templates still shows something.
     */
    fun toPayload(snapshot: TileSnapshot): JSONObject {
        val legacy = downLevel(snapshot.content)
        return JSONObject()
            .put("pluginId", snapshot.pluginId)
            .put("contentKey", snapshot.contentKey)
            .put("title", legacy.title)
            .put("subtitle", legacy.subtitle)
            .put("badge", legacy.badge)
            .put("progress", legacy.progress?.toDouble() ?: JSONObject.NULL)
            .put("unit", legacy.unit)
            .put("tone", snapshot.tone.wireValue)
            .put("rows", JSONArray(legacy.rows))
            .put("template", snapshot.content.template.wireValue)
            .put("content", contentToJson(snapshot.content))
            .put("staleAfterMs", snapshot.staleAfterMs)
    }

    /**
     * Defensive, hub-side decode: never trusts the plugin process. Returns null for anything
     * malformed or out of bounds rather than throwing, so one bad payload cannot crash the
     * receiving session. A missing or unknown `template`, or a `content` that does not decode,
     * falls back to [TileContent.Generic] from the legacy fields.
     */
    fun fromPayload(payload: JSONObject?): TileSnapshot? {
        val json = payload ?: return null
        val pluginId = json.optString("pluginId")
        if (pluginId.isBlank() || pluginId.length > MAX_PLUGIN_ID_CHARS) return null
        val contentKey = json.optString("contentKey")
        if (contentKey.length > MAX_CONTENT_KEY_CHARS) return null
        val tone = TileTone.fromWireValue(json.optString("tone")) ?: TileTone.OFF
        val staleAfterMs = if (json.has("staleAfterMs")) {
            json.optLong("staleAfterMs", DEFAULT_STALE_AFTER_MS)
                .coerceIn(MIN_STALE_AFTER_MS, MAX_STALE_AFTER_MS)
        } else {
            DEFAULT_STALE_AFTER_MS
        }
        val content = runCatching { templatedContent(json) }.getOrNull()
            ?: legacyContent(json)
            ?: return null
        return runCatching {
            TileSnapshot(
                pluginId = pluginId,
                contentKey = contentKey,
                content = content,
                tone = tone,
                staleAfterMs = staleAfterMs,
            )
        }.getOrNull()
    }

    /**
     * The music template's `artworkKey` in a `/tile/publish` payload, or "" when the payload is
     * not music or names no artwork. A tile's artwork is cached and looked up under it.
     */
    fun artworkKeyOf(payload: JSONObject): String {
        if (payload.optString("template") != TileContent.Template.MUSIC.wireValue) return ""
        val key = payload.optJSONObject("content")?.optString("artworkKey").orEmpty()
        return if (key.length <= MAX_CONTENT_KEY_CHARS) key else ""
    }

    /**
     * A publish that carries artwork bytes: the payload's `artwork` object (as a media surface's,
     * see [MediaArtworkContract]) must describe [binary], and the payload must be music with an
     * `artworkKey`.
     */
    fun validateArtwork(payload: JSONObject, binary: ByteArray?): ImageSurfaceValidationResult {
        val key = artworkKeyOf(payload)
        if (key.isBlank()) {
            return ImageSurfaceValidationResult.Invalid(ImageSurfaceContract.ERROR_INVALID_IMAGE, "artwork needs a music artworkKey")
        }
        return MediaArtworkContract.validateArtwork(key, payload.optJSONObject(ARTWORK_FIELD), binary)
    }

    /** [payload] with its artwork description removed: what travels when the bytes do not. */
    fun withoutArtwork(payload: JSONObject): JSONObject =
        JSONObject(payload.toString()).apply { remove(ARTWORK_FIELD) }

    /** [payload] describing [artwork], an object from [MediaArtworkContract.describe]. */
    fun withArtwork(payload: JSONObject, artwork: JSONObject): JSONObject =
        JSONObject(payload.toString()).put(ARTWORK_FIELD, JSONObject(artwork.toString()))

    /**
     * What a template looks like in the legacy fields, and what a renderer draws for a template it
     * has no layout for yet.
     */
    fun downLevel(content: TileContent): TileContent.Generic = when (content) {
        is TileContent.Generic -> content
        is TileContent.Music -> TileContent.Generic(
            title = content.title,
            subtitle = joinNonBlank(content.artist, content.album),
            progress = progressOf(content.positionMs, content.durationMs),
        )
        is TileContent.Lines -> {
            val current = content.lines.getOrNull(content.current)?.text.orEmpty()
            TileContent.Generic(
                title = current.ifBlank { content.title },
                subtitle = joinNonBlank(content.title.takeIf { current.isNotBlank() }.orEmpty(), content.subtitle),
                progress = progressOf(content.positionMs, content.durationMs),
                rows = content.lines.drop(content.current + 1).take(MAX_ROWS).map { it.text },
            )
        }
        is TileContent.ListContent -> {
            val items = content.items
            val titleFromItem = content.summary.isBlank()
            TileContent.Generic(
                title = content.summary.ifBlank { items.firstOrNull()?.title.orEmpty() },
                badge = content.summaryShort,
                rows = items.drop(if (titleFromItem) 1 else 0).take(MAX_ROWS).map { item ->
                    joinNonBlank(item.title, item.paragraph, separator = " - ").take(MAX_ROW_CHARS)
                },
            )
        }
    }

    private fun progressOf(positionMs: Long?, durationMs: Long?): Float? {
        if (positionMs == null || durationMs == null || durationMs <= 0) return null
        return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    private fun joinNonBlank(vararg parts: String, separator: String = " \u00B7 "): String =
        parts.filter { it.isNotBlank() }.joinToString(separator).take(MAX_SUBTITLE_CHARS)

    private fun contentToJson(content: TileContent): JSONObject = when (content) {
        is TileContent.Generic -> JSONObject()
            .put("title", content.title)
            .put("subtitle", content.subtitle)
            .put("badge", content.badge)
            .put("progress", content.progress?.toDouble() ?: JSONObject.NULL)
            .put("unit", content.unit)
            .put("rows", JSONArray(content.rows))
        is TileContent.Music -> JSONObject()
            .put("title", content.title)
            .put("artist", content.artist)
            .put("album", content.album)
            .put("source", content.source)
            .put("playing", content.playing)
            .put("positionMs", content.positionMs ?: JSONObject.NULL)
            .put("durationMs", content.durationMs ?: JSONObject.NULL)
            .put("artworkKey", content.artworkKey)
        is TileContent.Lines -> JSONObject()
            .put(
                "lines",
                JSONArray().also { array ->
                    content.lines.forEach { line ->
                        array.put(
                            JSONObject()
                                .put("text", line.text)
                                .put("startMs", line.startMs ?: JSONObject.NULL),
                        )
                    }
                },
            )
            .put("current", content.current)
            .put("title", content.title)
            .put("subtitle", content.subtitle)
            .put("source", content.source)
            .put("positionMs", content.positionMs ?: JSONObject.NULL)
            .put("durationMs", content.durationMs ?: JSONObject.NULL)
            .put("playing", content.playing)
        is TileContent.ListContent -> JSONObject()
            .put(
                "sections",
                JSONArray().also { array ->
                    content.sections.forEach { section ->
                        array.put(
                            JSONObject()
                                .put("title", section.title)
                                .put("detail", section.detail)
                                .put("items", JSONArray().also { items -> section.items.forEach { items.put(itemToJson(it)) } }),
                        )
                    }
                },
            )
            .put("summary", content.summary)
            .put("summaryShort", content.summaryShort)
            .put("footer", content.footer)
            .put("paragraphLines", content.paragraphLines)
            .put("overflow", content.overflow)
    }

    private fun itemToJson(item: TileContent.ListContent.Item): JSONObject = JSONObject()
        .put("title", item.title)
        .put("detail", item.detail)
        .put("paragraph", item.paragraph)
        .put("ageMs", item.ageMs ?: JSONObject.NULL)
        .put(
            "leading",
            when (val leading = item.leading) {
                null -> JSONObject.NULL
                is TileContent.ListContent.Leading.Initials ->
                    JSONObject().put("type", "initials").put("text", leading.text)
                is TileContent.ListContent.Leading.Glyph ->
                    JSONObject().put("type", "glyph").put("name", leading.name)
            },
        )

    /** Null when there is no known `template` or its `content` is not an object. */
    private fun templatedContent(json: JSONObject): TileContent? {
        val template = TileContent.Template.fromWireValue(json.optString("template")) ?: return null
        val content = json.optJSONObject("content") ?: return null
        return when (template) {
            TileContent.Template.GENERIC -> genericContent(content)
            TileContent.Template.MUSIC -> TileContent.Music(
                title = content.optString("title").take(MAX_TITLE_CHARS),
                artist = content.optString("artist").take(MAX_TITLE_CHARS),
                album = content.optString("album").take(MAX_TITLE_CHARS),
                source = content.optString("source").take(MAX_TITLE_CHARS),
                playing = content.optBoolean("playing", false),
                positionMs = optTime(content, "positionMs"),
                durationMs = optTime(content, "durationMs"),
                artworkKey = content.optString("artworkKey").take(MAX_CONTENT_KEY_CHARS),
            )
            TileContent.Template.LINES -> {
                val rawLines = content.optJSONArray("lines")
                val lines = buildList {
                    if (rawLines != null) {
                        for (i in 0 until minOf(rawLines.length(), MAX_LINES)) {
                            val line = rawLines.optJSONObject(i) ?: continue
                            add(
                                TileContent.Lines.Line(
                                    text = line.optString("text").take(MAX_TITLE_CHARS),
                                    startMs = optTime(line, "startMs"),
                                ),
                            )
                        }
                    }
                }
                TileContent.Lines(
                    lines = lines,
                    current = content.optInt("current", 0).coerceIn(0, maxOf(lines.size - 1, 0)),
                    title = content.optString("title").take(MAX_TITLE_CHARS),
                    subtitle = content.optString("subtitle").take(MAX_TITLE_CHARS),
                    source = content.optString("source").take(MAX_TITLE_CHARS),
                    positionMs = optTime(content, "positionMs"),
                    durationMs = optTime(content, "durationMs"),
                    playing = content.optBoolean("playing", false),
                )
            }
            TileContent.Template.LIST -> listContent(content)
        }
    }

    private fun genericContent(json: JSONObject): TileContent.Generic? {
        val title = json.optString("title")
        if (title.length > MAX_TITLE_CHARS) return null
        val progress = if (json.isNull("progress") || !json.has("progress")) {
            null
        } else {
            json.optDouble("progress").takeIf { !it.isNaN() }?.toFloat()?.coerceIn(0f, 1f)
        }
        val rawRows = json.optJSONArray("rows")
        val rows = buildList {
            if (rawRows != null) {
                for (i in 0 until rawRows.length()) {
                    if (size >= MAX_ROWS) break
                    add(rawRows.optString(i).take(MAX_ROW_CHARS))
                }
            }
        }
        return TileContent.Generic(
            title = title,
            subtitle = json.optString("subtitle").take(MAX_SUBTITLE_CHARS),
            badge = json.optString("badge").take(MAX_BADGE_CHARS),
            progress = progress,
            unit = json.optString("unit").take(MAX_UNIT_CHARS),
            rows = rows,
        )
    }

    private fun legacyContent(json: JSONObject): TileContent.Generic? =
        runCatching { genericContent(json) }.getOrNull()

    private fun listContent(json: JSONObject): TileContent.ListContent {
        val rawSections = json.optJSONArray("sections")
        var remainingItems = MAX_LIST_ITEMS
        val sections = buildList {
            if (rawSections != null) {
                for (i in 0 until minOf(rawSections.length(), MAX_SECTIONS)) {
                    val section = rawSections.optJSONObject(i) ?: continue
                    val rawItems = section.optJSONArray("items")
                    val items = buildList {
                        if (rawItems != null) {
                            for (j in 0 until rawItems.length()) {
                                if (remainingItems == 0) break
                                val item = rawItems.optJSONObject(j) ?: continue
                                add(listItem(item))
                                remainingItems--
                            }
                        }
                    }
                    add(
                        TileContent.ListContent.Section(
                            title = section.optString("title").take(MAX_TITLE_CHARS),
                            detail = section.optString("detail").take(MAX_DETAIL_CHARS),
                            items = items,
                        ),
                    )
                }
            }
        }
        return TileContent.ListContent(
            sections = sections,
            summary = json.optString("summary").take(MAX_DETAIL_CHARS),
            summaryShort = json.optString("summaryShort").take(MAX_SUMMARY_SHORT_CHARS),
            footer = json.optString("footer").take(MAX_TITLE_CHARS),
            paragraphLines = json.optInt("paragraphLines", TileContent.ListContent.DEFAULT_PARAGRAPH_LINES)
                .coerceIn(1, MAX_PARAGRAPH_LINES),
            overflow = json.optInt("overflow", 0).coerceAtLeast(0),
        )
    }

    private fun listItem(json: JSONObject): TileContent.ListContent.Item {
        val leading = json.optJSONObject("leading")?.let { raw ->
            when (raw.optString("type")) {
                "initials" -> raw.optString("text").take(MAX_INITIALS_CHARS).takeIf { it.isNotEmpty() }
                    ?.let(TileContent.ListContent.Leading::Initials)
                "glyph" -> raw.optString("name").takeIf { it.isNotEmpty() && it.length <= MAX_GLYPH_NAME_CHARS }
                    ?.let(TileContent.ListContent.Leading::Glyph)
                else -> null
            }
        }
        return TileContent.ListContent.Item(
            title = json.optString("title").take(MAX_TITLE_CHARS),
            detail = json.optString("detail").take(MAX_DETAIL_CHARS),
            paragraph = json.optString("paragraph").take(MAX_PARAGRAPH_CHARS),
            ageMs = optTime(json, "ageMs"),
            leading = leading,
        )
    }

    private fun optTime(json: JSONObject, key: String): Long? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optLong(key, -1L).takeIf { it >= 0 }
    }
}
