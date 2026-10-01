package com.anezium.rokidbus.plugin.relay

import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import com.anezium.rokidbus.shared.tile.WidgetTileContract

/**
 * The Relay grid tile: the inbox's conversations, newest first, for as long as the hub's tile
 * lease lasts. The inbox is read only between [start] and [stop]; a capture, a removal or a sent
 * reply outside the lease is ignored, and nothing is published.
 *
 * Conversations whose text Android redacted never reach the tile, not even as a sender: the tile
 * sits on the grid unasked, which is the opposite of the wearer opening the inbox to read one.
 * While either of Relay's hide switches is on, every message reads as [RelayPrivacy.HIDDEN_BODY].
 *
 * Publishes are spaced at least [MIN_PUBLISH_INTERVAL_MS] apart, the glasses' steady tile rate, so
 * a busy group chat cannot exhaust it and lose the newest state; the last change always lands.
 */
internal class RelayTileRuntime(
    private val publish: (TileSnapshot) -> Unit,
    private val entries: () -> List<RelayInboxEntry>,
    private val hideText: () -> Boolean,
    private val now: () -> Long,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
) {
    private var active = false
    private var generation = 0
    private var scheduled = false
    private var refreshRequested = false
    private var lastPublishedAtMs: Long? = null
    private var lastSignature: List<Any>? = null

    fun start() {
        if (active) return
        active = true
        generation += 1
        publishNow(force = true)
    }

    fun stop() {
        if (!active) return
        active = false
        generation += 1
        scheduled = false
        refreshRequested = false
        lastPublishedAtMs = null
        lastSignature = null
    }

    /** The hub's refresh: republish what the inbox holds, so the glasses' copy never goes stale. */
    fun refresh() {
        if (!active) return
        refreshRequested = true
        request()
    }

    fun inboxChanged() {
        if (!active) return
        request()
    }

    private fun request() {
        if (scheduled) return
        val last = lastPublishedAtMs
        val delayMs = if (last == null) 0L else (last + MIN_PUBLISH_INTERVAL_MS - now()).coerceAtLeast(0L)
        val scheduledGeneration = generation
        scheduled = true
        schedule(delayMs) {
            if (scheduledGeneration == generation) {
                scheduled = false
                publishNow(force = refreshRequested)
            }
        }
    }

    private fun publishNow(force: Boolean) {
        refreshRequested = false
        val at = now()
        val shown = entries().filterNot { it.snapshot.redacted }
        val hidden = hideText()
        val signature = listOf<Any>(hidden) + shown.map { it.id to it.snapshot.renderedText }
        if (!force && signature == lastSignature) return
        lastSignature = signature
        lastPublishedAtMs = at
        publish(snapshotOf(shown, hidden, at))
    }

    companion object {
        const val PLUGIN_ID = "relay"
        const val MIN_PUBLISH_INTERVAL_MS = 12_000L
        private const val STALE_AFTER_MS = 40 * 60_000L

        fun snapshotOf(entries: List<RelayInboxEntry>, hideText: Boolean, nowMs: Long): TileSnapshot {
            if (entries.isEmpty()) {
                return TileSnapshot(
                    pluginId = PLUGIN_ID,
                    contentKey = "empty",
                    content = TileContent.Generic(title = "No new messages", subtitle = "Relay"),
                    tone = TileTone.OFF,
                    staleAfterMs = STALE_AFTER_MS,
                )
            }
            val items = entries.take(WidgetTileContract.MAX_LIST_ITEMS)
            val count = entries.size.toString()
            return TileSnapshot(
                pluginId = PLUGIN_ID,
                contentKey = "${entries.first().id}:${entries.size}",
                content = TileContent.ListContent(
                    sections = listOf(
                        TileContent.ListContent.Section(items = items.map { item(it.snapshot, hideText, nowMs) }),
                    ),
                    summary = "$count new",
                    summaryShort = count.take(WidgetTileContract.MAX_SUMMARY_SHORT_CHARS),
                    overflow = entries.size - items.size,
                ),
                tone = TileTone.INFO,
                staleAfterMs = STALE_AFTER_MS,
            )
        }

        private fun item(snapshot: RelayInboxSnapshot, hideText: Boolean, nowMs: Long): TileContent.ListContent.Item {
            val sender = oneLine(snapshot.sender).ifBlank { oneLine(snapshot.appLabel) }.ifBlank { "Unknown" }
            return TileContent.ListContent.Item(
                title = fit(sender, WidgetTileContract.MAX_TITLE_CHARS),
                detail = fit(oneLine(snapshot.appLabel), WidgetTileContract.MAX_DETAIL_CHARS),
                paragraph = if (hideText) RelayPrivacy.HIDDEN_BODY else paragraph(snapshot.renderedText, sender),
                ageMs = (nowMs - snapshot.capturedAtMs).coerceAtLeast(0L),
                leading = initials(sender)?.let(TileContent.ListContent.Leading::Initials),
            )
        }

        /**
         * The newest message. In a group the title is the group, so the speaker stays in front of
         * what they said; one-to-one, the title already names them.
         */
        private fun paragraph(rendered: String, sender: String): String {
            val newest = RelayInboxCatalog.threadMessages(rendered).lastOrNull() ?: return ""
            val text = if (newest.speaker.isBlank() || newest.speaker.equals(sender, ignoreCase = true)) {
                newest.text
            } else {
                "${newest.speaker}: ${newest.text}"
            }
            return fit(text, WidgetTileContract.MAX_PARAGRAPH_CHARS)
        }

        /** "Ana Ribeiro" reads "AR", "Family" reads "FA"; null when the name has no letter or digit. */
        fun initials(name: String): String? {
            val words = name.split(WHITESPACE)
                .map { word -> word.filter(Char::isLetterOrDigit) }
                .filter(String::isNotEmpty)
            val letters = when {
                words.isEmpty() -> return null
                words.size == 1 -> words.single().take(WidgetTileContract.MAX_INITIALS_CHARS)
                else -> "${words.first().first()}${words.last().first()}"
            }
            return letters.map(Char::uppercaseChar).joinToString("")
        }

        private fun oneLine(value: String): String = value.replace(WHITESPACE, " ").trim()

        private fun fit(value: String, maxChars: Int): String {
            if (value.length <= maxChars) return value
            var end = maxChars - 1
            if (Character.isHighSurrogate(value[end - 1])) end -= 1
            return value.substring(0, end).trimEnd() + "…"
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
