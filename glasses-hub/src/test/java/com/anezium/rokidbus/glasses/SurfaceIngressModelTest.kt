package com.anezium.rokidbus.glasses

import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.ink.InkProblem
import com.anezium.rokidbus.ink.RenderDocument
import com.anezium.rokidbus.ink.RenderNode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** 01 §7.5 items 90, 91 and 100: what a `/surface/show` payload becomes before it is drawn. */
@RunWith(RobolectricTestRunner::class)
class SurfaceIngressModelTest {
    private fun payload(surfaceId: String = "s", kind: String? = "card", contentKey: String = "k") =
        JSONObject().put("surfaceId", surfaceId).put("seq", 1).put("contentKey", contentKey)
            .apply { kind?.let { put("kind", it) } }

    @Test
    fun item90_an_update_merges_with_the_previous_surface_only_when_id_kind_and_content_key_match() {
        val previous = NexusSurface.fromPayload(
            payload().put("title", "Old title").put("footer", "Old footer").put("lines", JSONArray().put("row")),
        )

        val merged = NexusSurface.fromPayload(payload().put("seq", 2), previous)
        assertEquals("Old title", merged.title)
        assertEquals("Old footer", merged.footer)
        assertEquals(listOf("row"), merged.rows.map { it.text })

        listOf(
            payload(surfaceId = "other"),
            payload(kind = "reader"),
            payload(contentKey = "different"),
        ).forEach { next ->
            val fresh = NexusSurface.fromPayload(next, previous)
            assertEquals("", fresh.title)
            assertEquals("", fresh.footer)
            assertTrue(fresh.rows.isEmpty())
        }
    }

    @Test
    fun item90_a_blank_content_key_still_merges_because_it_names_nothing_new() {
        val previous = NexusSurface.fromPayload(payload().put("title", "Kept"))
        assertEquals("Kept", NexusSurface.fromPayload(payload(contentKey = ""), previous).title)
    }

    @Test
    fun item91_an_unknown_kind_is_rejected_and_an_empty_kind_is_a_card() {
        try {
            NexusSurface.fromPayload(payload(kind = "hologram"))
            fail("an unknown kind must not become a surface")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("hologram"))
        }
        assertEquals(NexusSurface.KIND_CARD, NexusSurface.fromPayload(payload(kind = "")).kind)
        assertEquals(NexusSurface.KIND_CARD, NexusSurface.fromPayload(payload(kind = null)).kind)
    }

    // ---- item 100 --------------------------------------------------------------------------

    private class Rig {
        val resyncs = ArrayList<InkResyncRequest>()
        val errors = ArrayList<List<InkProblem>>()
        val layer = InkRendererLayer(
            Handler(Looper.getMainLooper()),
            { resyncs += it },
            { _, _ -> },
            { _, problems -> errors += problems },
        )
        var committed = 0

        fun submit(document: String? = null, patch: String? = null, seq: Long = 1) {
            val surface = NexusSurface(
                surfaceId = "s", seq = seq, kind = NexusSurface.KIND_INK, contentKey = "k", title = "", subtitle = "",
                footer = "", rows = emptyList(), timedLines = emptyList(), anchor = null, handlesBack = false,
                ink = InkSurfacePayload(documentJson = document, patchJson = patch),
            )
            layer.submit(surface) { committed++ }
        }

        fun await(condition: () -> Boolean) {
            repeat(400) {
                shadowOf(Looper.getMainLooper()).idle()
                if (condition()) return
                Thread.sleep(10)
            }
            fail("condition not reached")
        }
    }

    private fun document(revision: Int, id: String = "doc") =
        RenderDocument(listOf(RenderNode("root", "view")), documentId = id, revision = revision).toWireJson()

    private fun emptyPatch(doc: String, base: Int) =
        JSONObject().put("v", 1).put("doc", doc).put("baseRev", base).put("targetRev", base + 1)
            .put("changes", JSONArray()).toString()

    @Test
    fun item100_a_payload_needs_exactly_one_of_document_or_patch() {
        val rig = Rig()
        rig.submit()
        rig.submit(document = document(0), patch = emptyPatch("doc", 0))
        assertEquals(2, rig.errors.size)
        assertEquals(0, rig.committed)
        assertFalse(rig.errors.any { it.isEmpty() })
    }

    @Test
    fun item100_a_patch_with_no_store_asks_for_a_resync() {
        val rig = Rig()
        rig.submit(patch = emptyPatch("doc", 3))
        rig.await { rig.resyncs.isNotEmpty() }
        assertEquals(InkResyncRequest("", -1, "doc", 3), rig.resyncs.single())
        assertEquals(0, rig.committed)
        assertNull(rig.layer.identity())
    }

    @Test
    fun item100_a_stale_document_revision_is_dropped_and_a_newer_one_replaces_it() {
        val rig = Rig()
        rig.submit(document = document(revision = 4))
        rig.await { rig.committed == 1 }
        assertEquals("doc" to 4, rig.layer.identity())

        rig.submit(document = document(revision = 3))
        rig.await { true }
        Thread.sleep(150)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the older revision commits nothing", 1, rig.committed)
        assertEquals("doc" to 4, rig.layer.identity())

        rig.submit(document = document(revision = 5))
        rig.await { rig.committed == 2 }
        assertEquals("doc" to 5, rig.layer.identity())
        rig.layer.destroy()
    }
}
