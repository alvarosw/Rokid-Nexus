package com.anezium.rokidbus.glasses

import android.util.Base64
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FakePhoneTest {
    private val delivered = mutableListOf<BusEnvelope>()
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private var seq = 100L
    private val phone = FakePhone(
        sink = { delivered += it },
        schedule = { delay, task -> scheduled += delay to task },
        nextSeq = { ++seq },
    )

    @Test
    fun `a single envelope keeps its path and payload and is delivered immediately`() {
        phone.play(
            phone.parse("""{"path":"/launcher/list","payload":{"plugins":[{"id":"a","displayName":"A"}]}}"""),
        )

        assertEquals(1, delivered.size)
        assertEquals(BusPaths.LAUNCHER_LIST, delivered[0].path)
        assertEquals("a", delivered[0].payload.getJSONArray("plugins").getJSONObject(0).getString("id"))
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `surface envelopes without a seq get monotonic ones and explicit seq is kept`() {
        phone.play(
            phone.parse(
                """[{"path":"/surface/show","payload":{"surfaceId":"a:main"}},
                    {"path":"/surface/update","payload":{"surfaceId":"a:main"}},
                    {"path":"/surface/hide","payload":{"surfaceId":"a:main","seq":7}},
                    {"path":"/launcher/list","payload":{}}]""",
            ),
        )

        assertEquals(listOf(101L, 102L, 7L), delivered.take(3).map { it.payload.getLong("seq") })
        assertFalse(delivered[3].payload.has("seq"))
    }

    @Test
    fun `notice pin and activity envelopes get monotonic seq too`() {
        phone.play(
            phone.parse(
                """[{"path":"/notice/show","payload":{"surfaceId":"a:n"}},
                    {"path":"/pin/show","payload":{"surfaceId":"a:p"}},
                    {"path":"/activity/start","payload":{"surfaceId":"a:local"}},
                    {"path":"/pin/hide","payload":{"seq":9}}]""",
            ),
        )

        assertEquals(listOf(101L, 102L, 103L, 9L), delivered.map { it.payload.getLong("seq") })
    }

    @Test
    fun `step delays accumulate and defer delivery`() {
        phone.play(
            phone.parse(
                """{"envelopes":[{"path":"/x/a","delayMs":100},{"path":"/x/b","delayMs":50},{"path":"/x/c"}]}""",
            ),
        )

        assertEquals(listOf(100L, 150L, 150L), scheduled.map { it.first })
        assertTrue(delivered.isEmpty())
        scheduled.forEach { it.second() }
        assertEquals(listOf("/x/a", "/x/b", "/x/c"), delivered.map { it.path })
    }

    @Test
    fun `binaryBase64 becomes the envelope binary`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        phone.play(phone.parse("""{"path":"/surface/show","payload":{},"binaryBase64":"$encoded"}"""))

        assertTrue(bytes.contentEquals(delivered.single().binary))
    }

    @Test
    fun `an open is answered by the plugin's rule after its delay`() {
        phone.play(
            phone.parse(
                """{"onOpen":{"cal":{"delayMs":400,"envelopes":[
                    {"path":"/surface/show","payload":{"surfaceId":"cal:main","kind":"card"}}]}}}""",
            ),
        )

        assertTrue(phone.onOutbound(open("cal")))
        assertTrue(delivered.isEmpty())
        assertEquals(400L, scheduled.single().first)
        scheduled.single().second()
        assertEquals("cal:main", delivered.single().payload.getString("surfaceId"))
        assertNotNull(delivered.single().payload.opt("seq"))
    }

    @Test
    fun `answering the same open twice stamps a newer seq each time`() {
        phone.play(
            phone.parse("""{"onOpen":{"a":{"envelopes":[{"path":"/surface/show","payload":{"surfaceId":"a:main"}}]}}}"""),
        )

        phone.onOutbound(open("a"))
        phone.onOutbound(open("a"))

        val seqs = delivered.map { it.payload.getLong("seq") }
        assertEquals(2, seqs.size)
        assertTrue(seqs[1] > seqs[0])
    }

    @Test
    fun `a never rule and a missing rule consume the open without replying`() {
        phone.play(phone.parse("""{"onOpen":{"slow":{"never":true,"envelopes":[]}}}"""))

        assertTrue(phone.onOutbound(open("slow")))
        assertTrue(phone.onOutbound(open("unknown")))
        assertTrue(delivered.isEmpty())
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `other outbound envelopes are not consumed`() {
        phone.play(phone.parse("""{"onOpen":{}}"""))

        assertFalse(phone.onOutbound(BusEnvelope(path = BusPaths.SURFACE_INPUT)))
    }

    @Test
    fun `a script without open rules keeps the existing ones and reset drops them`() {
        phone.play(phone.parse("""{"onOpen":{"a":{"envelopes":[{"path":"/x/a"}]}}}"""))
        phone.play(phone.parse("""{"path":"/launcher/list","payload":{}}"""))
        delivered.clear()

        phone.onOutbound(open("a"))
        assertEquals(listOf("/x/a"), delivered.map { it.path })

        delivered.clear()
        phone.reset()
        phone.onOutbound(open("a"))
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `an explicit id survives`() {
        phone.play(phone.parse("""{"path":"/x/a","id":"fixed-1"}"""))

        assertEquals("fixed-1", delivered.single().id)
        assertNull(delivered.single().binary)
    }

    private fun open(pluginId: String) =
        BusEnvelope(path = BusPaths.LAUNCHER_OPEN, payload = JSONObject().put("pluginId", pluginId))
}
