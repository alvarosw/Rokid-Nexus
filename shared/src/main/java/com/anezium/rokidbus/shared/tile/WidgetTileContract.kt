package com.anezium.rokidbus.shared.tile

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
    val title: String,
    val subtitle: String = "",
    val badge: String = "",
    val progress: Float? = null,
    val unit: String = "",
    val tone: TileTone = TileTone.OFF,
    val rows: List<String> = emptyList(),
) {
    init {
        require(pluginId.isNotBlank() && pluginId.length <= WidgetTileContract.MAX_PLUGIN_ID_CHARS) {
            "pluginId must be 1..${WidgetTileContract.MAX_PLUGIN_ID_CHARS} chars"
        }
        require(contentKey.length <= WidgetTileContract.MAX_CONTENT_KEY_CHARS) {
            "contentKey must be <= ${WidgetTileContract.MAX_CONTENT_KEY_CHARS} chars"
        }
        require(title.length <= WidgetTileContract.MAX_TITLE_CHARS) {
            "title must be <= ${WidgetTileContract.MAX_TITLE_CHARS} chars"
        }
        require(subtitle.length <= WidgetTileContract.MAX_SUBTITLE_CHARS) {
            "subtitle must be <= ${WidgetTileContract.MAX_SUBTITLE_CHARS} chars"
        }
        require(badge.length <= WidgetTileContract.MAX_BADGE_CHARS) {
            "badge must be <= ${WidgetTileContract.MAX_BADGE_CHARS} chars"
        }
        require(progress == null || progress in 0f..1f) { "progress must be 0f..1f" }
        require(unit.length <= WidgetTileContract.MAX_UNIT_CHARS) {
            "unit must be <= ${WidgetTileContract.MAX_UNIT_CHARS} chars"
        }
        require(rows.size <= WidgetTileContract.MAX_ROWS) {
            "rows must have <= ${WidgetTileContract.MAX_ROWS} entries"
        }
        require(rows.all { it.length <= WidgetTileContract.MAX_ROW_CHARS }) {
            "each row must be <= ${WidgetTileContract.MAX_ROW_CHARS} chars"
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
    const val MAX_PAYLOAD_BYTES = 8 * 1024

    fun toPayload(snapshot: TileSnapshot): JSONObject = JSONObject()
        .put("pluginId", snapshot.pluginId)
        .put("contentKey", snapshot.contentKey)
        .put("title", snapshot.title)
        .put("subtitle", snapshot.subtitle)
        .put("badge", snapshot.badge)
        .put("progress", snapshot.progress?.toDouble() ?: JSONObject.NULL)
        .put("unit", snapshot.unit)
        .put("tone", snapshot.tone.wireValue)
        .put("rows", JSONArray(snapshot.rows))

    /**
     * Defensive, hub-side decode: never trusts the plugin process. Returns null for anything
     * malformed or out of bounds rather than throwing, so one bad payload cannot crash the
     * receiving session.
     */
    fun fromPayload(payload: JSONObject?): TileSnapshot? {
        val json = payload ?: return null
        val pluginId = json.optString("pluginId")
        if (pluginId.isBlank() || pluginId.length > MAX_PLUGIN_ID_CHARS) return null
        val contentKey = json.optString("contentKey")
        if (contentKey.length > MAX_CONTENT_KEY_CHARS) return null
        val title = json.optString("title")
        if (title.length > MAX_TITLE_CHARS) return null
        val subtitle = json.optString("subtitle").take(MAX_SUBTITLE_CHARS)
        val badge = json.optString("badge").take(MAX_BADGE_CHARS)
        val progress = if (json.isNull("progress") || !json.has("progress")) {
            null
        } else {
            json.optDouble("progress").takeIf { !it.isNaN() }?.toFloat()?.coerceIn(0f, 1f)
        }
        val unit = json.optString("unit").take(MAX_UNIT_CHARS)
        val tone = TileTone.fromWireValue(json.optString("tone")) ?: TileTone.OFF
        val rawRows = json.optJSONArray("rows")
        val rows = buildList {
            if (rawRows != null) {
                for (i in 0 until rawRows.length()) {
                    if (size >= MAX_ROWS) break
                    add(rawRows.optString(i).take(MAX_ROW_CHARS))
                }
            }
        }
        return runCatching {
            TileSnapshot(
                pluginId = pluginId,
                contentKey = contentKey,
                title = title,
                subtitle = subtitle,
                badge = badge,
                progress = progress,
                unit = unit,
                tone = tone,
                rows = rows,
            )
        }.getOrNull()
    }
}
