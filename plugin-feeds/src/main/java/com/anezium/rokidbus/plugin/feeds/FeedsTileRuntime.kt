package com.anezium.rokidbus.plugin.feeds

import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The Feeds grid tile. Poll-based: each [refresh] inside the hub's tile lease fetches the first
 * page of one timeline once, publishes it, and leaves nothing running. The lease start counts as
 * a refresh; the hub's own refreshes then set the cadence, and the plugin never schedules one.
 */
internal class FeedsTileRuntime(
    private val publish: (TileSnapshot) -> Unit,
    private val settings: () -> FeedsSettings,
    private val sourceFactory: (FeedsSettings, FeedSourceKind) -> FeedSource,
    private val post: (() -> Unit) -> Unit,
    private val log: (String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Instant = Instant::now,
) {
    private var scope: CoroutineScope? = null
    private var fetchJob: Job? = null
    private var lastFetchAt: Instant? = null
    private var seenPostIds: Set<String>? = null

    fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        refresh()
    }

    fun stop() {
        scope?.cancel()
        scope = null
        fetchJob = null
        lastFetchAt = null
        seenPostIds = null
    }

    fun refresh() {
        val activeScope = scope ?: return
        if (fetchJob?.isActive == true) return
        val fetchedAt = now()
        // The lease start and the hub's first refresh arrive together; one fetch answers both.
        lastFetchAt?.let { if (Duration.between(it, fetchedAt) < MIN_REFRESH_INTERVAL) return }
        lastFetchAt = fetchedAt
        val loadedSettings = settings()
        val kind = tileSource(loadedSettings)
        fetchJob = activeScope.launch {
            val source = sourceFactory(loadedSettings, kind)
            val page = try {
                source.fetchPage(null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log("tile fetch failed source=${kind.tag} cause=${failure.javaClass.simpleName}")
                null
            } finally {
                (source as? AutoCloseable)?.close()
            }
            if (page != null) post { if (scope === activeScope) publishPage(kind, page) }
        }
    }

    private fun publishPage(kind: FeedSourceKind, page: FeedPage) {
        val posts = page.posts
        val label = kind.displayName.substringBefore(" (")
        val newCount = seenPostIds?.let { seen -> posts.count { it.id !in seen } } ?: posts.size
        seenPostIds = posts.mapTo(mutableSetOf(), FeedPost::id)
        val content = if (posts.isEmpty()) {
            TileContent.Generic(title = "No posts", subtitle = label)
        } else {
            val shown = posts.take(WidgetTileContract.MAX_LIST_ITEMS)
            val at = now()
            TileContent.ListContent(
                sections = listOf(TileContent.ListContent.Section(items = shown.map { item(it, at) })),
                summary = if (newCount > 0) "$label · $newCount new" else label,
                summaryShort = if (newCount > 0) "+$newCount".take(WidgetTileContract.MAX_SUMMARY_SHORT_CHARS) else "",
                paragraphLines = PARAGRAPH_LINES,
                overflow = posts.size - shown.size,
            )
        }
        publish(
            TileSnapshot(
                pluginId = PLUGIN_ID,
                contentKey = "${kind.tag}:${posts.firstOrNull()?.id.orEmpty()}".take(WidgetTileContract.MAX_CONTENT_KEY_CHARS),
                content = content,
                tone = if (newCount > 0) TileTone.INFO else TileTone.OFF,
                staleAfterMs = STALE_AFTER_MS,
            ),
        )
    }

    private fun item(post: FeedPost, at: Instant): TileContent.ListContent.Item {
        val name = post.authorName.ifBlank { post.authorHandle.ifBlank { "Unknown" } }
        val handle = post.authorHandle.trim().takeIf(String::isNotBlank)?.let { "@$it" }.orEmpty()
        return TileContent.ListContent.Item(
            title = name.trim().take(WidgetTileContract.MAX_TITLE_CHARS),
            detail = handle.take(WidgetTileContract.MAX_DETAIL_CHARS),
            paragraph = paragraph(post),
            ageMs = Duration.between(post.createdAt, at).toMillis().coerceAtLeast(0L),
        )
    }

    /** The post text with its media marker kept at the end, where the tile gives it its own line. */
    private fun paragraph(post: FeedPost): String {
        val text = post.text.replace(WHITESPACE, " ").trim()
        val marker = mediaMarker(post.media) ?: return text.take(WidgetTileContract.MAX_PARAGRAPH_CHARS)
        val room = WidgetTileContract.MAX_PARAGRAPH_CHARS - marker.length - 1
        return if (text.isEmpty()) marker else "${text.take(room).trimEnd()} $marker"
    }

    private fun mediaMarker(media: List<FeedMedia>): String? {
        val first = media.firstOrNull() ?: return null
        return when (first.type) {
            FeedMediaType.PHOTO -> {
                val photos = media.count { it.type == FeedMediaType.PHOTO }
                if (photos > 1) "[$photos photos]" else "[photo]"
            }
            FeedMediaType.GIF -> "[GIF]"
            FeedMediaType.VIDEO -> "[video]"
        }
    }

    companion object {
        const val PLUGIN_ID = "feeds"
        private const val PARAGRAPH_LINES = 4
        private const val STALE_AFTER_MS = 40 * 60_000L
        private val MIN_REFRESH_INTERVAL: Duration = Duration.ofMinutes(1)
        private val WHITESPACE = Regex("\\s+")

        /**
         * The wearer's default timeline when it works without the WebView overlay window, which
         * only an open surface may run; Bluesky otherwise.
         */
        fun tileSource(settings: FeedsSettings): FeedSourceKind = when (settings.source) {
            FeedSourceKind.X_ACCOUNT,
            FeedSourceKind.X_OFFICIAL,
            -> settings.source.takeIf { it.isConfigured(settings) } ?: FeedSourceKind.BLUESKY
            FeedSourceKind.BLUESKY,
            FeedSourceKind.X_WEBVIEW,
            FeedSourceKind.DEMO,
            -> FeedSourceKind.BLUESKY
        }
    }
}
