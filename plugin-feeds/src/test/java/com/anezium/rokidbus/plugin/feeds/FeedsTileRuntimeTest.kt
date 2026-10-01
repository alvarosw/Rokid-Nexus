package com.anezium.rokidbus.plugin.feeds

import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedsTileRuntimeTest {
    private var now = Instant.parse("2026-07-12T12:00:00Z")
    private val published = mutableListOf<TileSnapshot>()
    private val requestedKinds = mutableListOf<FeedSourceKind>()
    private var feedSettings = settings()
    private var page = FeedPage(
        listOf(
            post("1", "Inês Castro", "inescastro", "Fixed the fog light on the old Vespa.", 120, photos = 1),
            post("2", "Port Weather", "portweather", "Gusts up to 45 km/h after 18:00.", 360),
            post("3", "Davi Lopes", "davilopes", "Sketchbook pages\nfrom the market.", 660, photos = 3),
        ),
        nextCursor = "next",
    )
    private var failure: Exception? = null
    private val runtime = FeedsTileRuntime(
        publish = { published += it },
        settings = { feedSettings },
        sourceFactory = { _, kind ->
            requestedKinds += kind
            object : FeedSource {
                override fun fetchPage(cursor: String?): FeedPage = failure?.let { throw it } ?: page
            }
        },
        post = { it() },
        log = {},
        ioDispatcher = Dispatchers.Unconfined,
        now = { now },
    )

    @Test
    fun nothingIsFetchedOrPublishedOutsideTheLease() {
        runtime.refresh()
        assertTrue(requestedKinds.isEmpty())

        runtime.start()
        runtime.stop()
        published.clear()
        now = now.plusSeconds(15 * 60)
        runtime.refresh()

        assertEquals(1, requestedKinds.size)
        assertTrue(published.isEmpty())
    }

    @Test
    fun leaseStartFetchesOnceAndPublishesTheTimeline() {
        runtime.start()
        runtime.refresh()

        assertEquals(listOf(FeedSourceKind.BLUESKY), requestedKinds)
        val list = published.single().content as TileContent.ListContent
        assertEquals("Bluesky · 3 new", list.summary)
        assertEquals("+3", list.summaryShort)
        assertEquals(4, list.paragraphLines)
        assertEquals(0, list.overflow)
        val first = list.items.first()
        assertEquals("Inês Castro", first.title)
        assertEquals("@inescastro", first.detail)
        assertEquals("Fixed the fog light on the old Vespa. [photo]", first.paragraph)
        assertEquals(120_000L, first.ageMs)
        assertEquals("Sketchbook pages from the market. [3 photos]", list.items.last().paragraph)
    }

    @Test
    fun aHubRefreshFetchesAgainAndCountsOnlyUnseenPosts() {
        runtime.start()
        now = now.plusSeconds(15 * 60)
        page = page.copy(posts = listOf(post("4", "Ana", "ana", "Morning.", 30)) + page.posts)

        runtime.refresh()

        assertEquals(2, requestedKinds.size)
        val list = published.last().content as TileContent.ListContent
        assertEquals("Bluesky · 1 new", list.summary)
        assertEquals("+1", list.summaryShort)
    }

    @Test
    fun aLongTimelineSendsSixItemsAndCountsTheRest() {
        page = FeedPage((1..8).map { post("$it", "Author $it", "a$it", "Post $it", it * 60L) }, null)

        runtime.start()

        val list = published.single().content as TileContent.ListContent
        assertEquals(6, list.items.size)
        assertEquals(2, list.overflow)
    }

    @Test
    fun aFailedFetchPublishesNothing() {
        failure = IOException("offline")

        runtime.start()

        assertTrue(published.isEmpty())
    }

    @Test
    fun theTileReadsTheDefaultTimelineUnlessItNeedsTheOverlayWindow() {
        val cookies = XAccountCookies(authToken = "auth", ct0 = "csrf")

        assertEquals(FeedSourceKind.BLUESKY, FeedsTileRuntime.tileSource(settings()))
        assertEquals(
            FeedSourceKind.X_ACCOUNT,
            FeedsTileRuntime.tileSource(settings(source = FeedSourceKind.X_ACCOUNT, cookies = cookies)),
        )
        assertEquals(
            FeedSourceKind.BLUESKY,
            FeedsTileRuntime.tileSource(settings(source = FeedSourceKind.X_ACCOUNT)),
        )
        assertEquals(
            FeedSourceKind.BLUESKY,
            FeedsTileRuntime.tileSource(settings(source = FeedSourceKind.X_WEBVIEW, cookies = cookies)),
        )
    }

    private fun settings(
        source: FeedSourceKind = FeedSourceKind.BLUESKY,
        cookies: XAccountCookies = XAccountCookies(),
    ) = FeedsSettings(
        source = source,
        xAccountCookies = cookies,
        xBearerToken = "",
        xUserId = "",
        blueskyFeedGeneratorUri = BlueskyFeedSource.DEFAULT_FEED_GENERATOR_URI,
    )

    private fun post(
        id: String,
        name: String,
        handle: String,
        text: String,
        ageSeconds: Long,
        photos: Int = 0,
    ) = FeedPost(
        id = id,
        authorName = name,
        authorHandle = handle,
        text = text,
        createdAt = now.minusSeconds(ageSeconds),
        source = FeedSourceKind.BLUESKY.tag,
        media = List(photos) { FeedMedia(FeedMediaType.PHOTO, "https://cdn/$it", "", "", null) },
    )
}
