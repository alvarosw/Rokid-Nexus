package com.anezium.rokidbus.plugin.relay

import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTileRuntimeTest {
    private var now = 1_000_000L
    private val published = mutableListOf<TileSnapshot>()
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private var reads = 0
    private var hidden = false
    private var inbox = listOf(
        entry("a", "Ana Ribeiro", "WhatsApp", "Leaving now, ten minutes away.", agoMs = 60_000L),
        entry("f", "Family", "WhatsApp", "Mum: who has the keys?\nDad: dinner moved to 8", agoMs = 360_000L),
        entry("t", "Tomás Prado", "Telegram", "Tomás Prado: Did the files come through?", agoMs = 1_080_000L),
    )
    private val runtime = RelayTileRuntime(
        publish = { published += it },
        entries = {
            reads += 1
            inbox
        },
        hideText = { hidden },
        now = { now },
        schedule = { delayMs, action -> scheduled += (now + delayMs) to action },
    )

    @Test
    fun `nothing is read or published outside the lease`() {
        runtime.inboxChanged()
        runtime.refresh()
        assertEquals(0, reads)

        runtime.start()
        runtime.stop()
        published.clear()
        val readsAtStop = reads
        runtime.inboxChanged()
        runtime.refresh()
        runPending()

        assertEquals(readsAtStop, reads)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `the lease start publishes the inbox as one untitled section`() {
        runtime.start()

        val snapshot = published.single()
        assertEquals("relay", snapshot.pluginId)
        assertEquals(TileTone.INFO, snapshot.tone)
        val list = snapshot.content as TileContent.ListContent
        assertEquals(1, list.sections.size)
        assertEquals("", list.sections.single().title)
        assertEquals("3 new", list.summary)
        assertEquals("3", list.summaryShort)
        assertEquals(0, list.overflow)

        val first = list.items.first()
        assertEquals("Ana Ribeiro", first.title)
        assertEquals("WhatsApp", first.detail)
        assertEquals("Leaving now, ten minutes away.", first.paragraph)
        assertEquals(60_000L, first.ageMs)
        assertEquals(TileContent.ListContent.Leading.Initials("AR"), first.leading)
        assertEquals(TileContent.ListContent.Leading.Initials("FA"), list.items[1].leading)
    }

    @Test
    fun `a group keeps its newest speaker and a one-to-one thread drops it`() {
        runtime.start()

        val items = (published.single().content as TileContent.ListContent).items
        assertEquals("Dad: dinner moved to 8", items[1].paragraph)
        assertEquals("Did the files come through?", items[2].paragraph)
    }

    @Test
    fun `a conversation Android redacted never reaches the tile`() {
        inbox = inbox + entry(
            "s",
            "Bank",
            "Bank",
            SensitiveNotificationDetector.HIDDEN_BODY,
            agoMs = 0L,
            redacted = true,
        )

        runtime.start()

        val list = published.single().content as TileContent.ListContent
        assertTrue(list.items.none { it.title == "Bank" })
        assertEquals("3 new", list.summary)
    }

    @Test
    fun `only redacted conversations publish an empty tile`() {
        inbox = listOf(entry("s", "Bank", "Bank", SensitiveNotificationDetector.HIDDEN_BODY, 0L, redacted = true))

        runtime.start()

        val content = published.single().content as TileContent.Generic
        assertEquals("No new messages", content.title)
        assertEquals(TileTone.OFF, published.single().tone)
    }

    @Test
    fun `a hide switch hides every message but keeps the senders`() {
        hidden = true

        runtime.start()

        val items = (published.single().content as TileContent.ListContent).items
        assertTrue(items.all { it.paragraph == RelayPrivacy.HIDDEN_BODY })
        assertEquals("Ana Ribeiro", items.first().title)
    }

    @Test
    fun `a new message republishes and an unchanged inbox does not`() {
        runtime.start()
        now += RelayTileRuntime.MIN_PUBLISH_INTERVAL_MS

        runtime.inboxChanged()
        runPending()
        assertEquals(1, published.size)

        inbox = listOf(entry("n", "Júlia Mendes", "Signal", "ok, see you there", agoMs = 0L)) + inbox
        runtime.inboxChanged()
        runPending()

        val list = published.last().content as TileContent.ListContent
        assertEquals(2, published.size)
        assertEquals("Júlia Mendes", list.items.first().title)
        assertEquals("4 new", list.summary)
    }

    @Test
    fun `a burst of messages is spaced out and the last state lands`() {
        runtime.start()
        repeat(5) { index ->
            inbox = listOf(entry("b$index", "Group", "WhatsApp", "message $index", agoMs = 0L)) + inbox
            runtime.inboxChanged()
        }

        assertEquals(1, scheduled.size)
        assertEquals(now + RelayTileRuntime.MIN_PUBLISH_INTERVAL_MS, scheduled.single().first)
        runPending()

        assertEquals(2, published.size)
        assertEquals("message 4", (published.last().content as TileContent.ListContent).items.first().paragraph)
    }

    @Test
    fun `a hub refresh republishes an unchanged inbox`() {
        runtime.start()
        now += 15 * 60_000L

        runtime.refresh()
        runPending()

        assertEquals(2, published.size)
    }

    @Test
    fun `a long inbox sends six conversations and counts the rest`() {
        inbox = (1..8).map { entry("$it", "Sender $it", "App", "Message $it", agoMs = it * 1_000L) }

        runtime.start()

        val list = published.single().content as TileContent.ListContent
        assertEquals(6, list.items.size)
        assertEquals(2, list.overflow)
        assertEquals("8 new", list.summary)
        assertEquals("8", list.summaryShort)
    }

    @Test
    fun `initials come from the first and last word, or the first two letters of one`() {
        assertEquals("AR", RelayTileRuntime.initials("Ana Ribeiro"))
        assertEquals("JM", RelayTileRuntime.initials("Júlia de Mendes"))
        assertEquals("FA", RelayTileRuntime.initials("Family"))
        assertEquals("M", RelayTileRuntime.initials("m"))
        assertNull(RelayTileRuntime.initials("🎉 !"))
    }

    private fun runPending() {
        while (scheduled.isNotEmpty()) {
            val (at, action) = scheduled.removeAt(0)
            if (at > now) now = at
            action()
        }
    }

    private fun entry(
        id: String,
        sender: String,
        app: String,
        text: String,
        agoMs: Long,
        redacted: Boolean = false,
    ) = RelayInboxEntry(
        snapshot = RelayInboxSnapshot(
            id = id,
            sender = sender,
            appLabel = app,
            renderedText = text,
            capturedAtMs = now - agoMs,
            redacted = redacted,
        ),
        availability = RelayReplyAvailability.REPLIABLE,
    )
}
