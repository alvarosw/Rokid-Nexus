package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.GlassesHubCapabilitiesContract
import com.anezium.rokidbus.shared.HomeVisibilityContract
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeGlassesTest {
    @Test
    fun parsesSingleEnvelope() {
        val steps = FakeGlasses.parse("""{"path":"/a/b","payload":{"x":1},"delayMs":5}""")
        assertEquals(1, steps.size)
        assertEquals("/a/b", steps[0].envelope.path)
        assertEquals(1, steps[0].envelope.payload.getInt("x"))
        assertEquals(5L, steps[0].delayMs)
    }

    @Test
    fun parsesArrayAndEnvelopesObjectWithIdAndBinary() {
        val array = FakeGlasses.parse("""[{"path":"/a"},{"path":"/b","id":"i-1","binaryBase64":"AQID"}]""")
        assertEquals(listOf("/a", "/b"), array.map { it.envelope.path })
        assertEquals("i-1", array[1].envelope.id)
        assertArrayEquals(byteArrayOf(1, 2, 3), array[1].envelope.binary)
        assertNull(array[0].envelope.binary)

        val wrapped = FakeGlasses.parse("""{"envelopes":[{"path":"/c","payload":{}}]}""")
        assertEquals(listOf("/c"), wrapped.map { it.envelope.path })
        assertTrue(FakeGlasses.parse("""{"envelopes":[]}""").isEmpty())
    }

    @Test(expected = org.json.JSONException::class)
    fun rejectsEnvelopeWithoutPath() {
        FakeGlasses.parse("""[{"payload":{}}]""")
    }

    @Test
    fun playDeliversInOrderAndAccumulatesDelays() {
        val delivered = mutableListOf<String>()
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        FakeGlasses(
            sink = { delivered += it.path },
            schedule = { delay, task -> scheduled += delay to task },
        ).play(
            FakeGlasses.parse("""[{"path":"/now"},{"path":"/later","delayMs":100},{"path":"/last","delayMs":50}]"""),
        )
        assertEquals(listOf("/now"), delivered)
        assertEquals(listOf(100L, 150L), scheduled.map { it.first })
        scheduled.forEach { it.second() }
        assertEquals(listOf("/now", "/later", "/last"), delivered)
    }

    @Test
    fun capabilitiesEnvelopeParsesAsCurrentGlasses() {
        val envelope: BusEnvelope = FakeGlasses.capabilitiesEnvelope("1.6.0")
        assertEquals(BusPaths.HUB_CAPABILITIES, envelope.path)
        val parsed = GlassesHubCapabilitiesContract.parse(envelope.payload)
        assertEquals(GlassesHubCapabilitiesContract.VERSION, parsed.protocolVersion)
        assertEquals("1.6.0", parsed.versionName)
        assertTrue(parsed.setupComplete)
        assertTrue(parsed.homeGridVisibleRows > 0)
    }

    @Test
    fun visibilityEnvelopesUseTheContract() {
        val on = HomeVisibilityContract.parse(FakeGlasses.visibilityEnvelope("on")!!.payload)!!
        assertTrue(on.screenOn && on.homeVisible)
        val hidden = HomeVisibilityContract.parse(FakeGlasses.visibilityEnvelope("hidden")!!.payload)!!
        assertTrue(hidden.screenOn)
        assertFalse(hidden.homeVisible)
        val off = HomeVisibilityContract.parse(FakeGlasses.visibilityEnvelope("off")!!.payload)!!
        assertFalse(off.screenOn)
        assertNull(FakeGlasses.visibilityEnvelope("bogus"))
        assertNotNull(FakeGlasses.visibilityEnvelope("on"))
    }
}
