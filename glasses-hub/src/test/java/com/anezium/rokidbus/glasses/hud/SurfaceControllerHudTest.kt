package com.anezium.rokidbus.glasses.hud

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.anezium.rokidbus.glasses.GlassesHubTestSupport
import com.anezium.rokidbus.glasses.SurfaceActivity
import com.anezium.rokidbus.glasses.SurfaceController
import com.anezium.rokidbus.ink.InkActionBinding
import com.anezium.rokidbus.ink.RenderDocument
import com.anezium.rokidbus.ink.RenderNode
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * [SurfaceController] against the real [HudController] and [HudHost]: what it sends to the plugin
 * for the intents the machine routes to it (01 §7.4-§7.6), and how it reacts when the overlay is
 * not there. Only the phone link and, in some tests, the window manager are replaced.
 */
@RunWith(RobolectricTestRunner::class)
class SurfaceControllerHudTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var host: HudHost? = null
    private val connected = ArrayList<AccessibilityService>()
    private val shown = ArrayList<String>()

    private class RefusingWindowManager(private val delegate: WindowManager) : WindowManager by delegate {
        override fun addView(view: View, params: ViewGroup.LayoutParams) {
            throw WindowManager.BadTokenException("refused")
        }
    }

    private val looper get() = shadowOf(Looper.getMainLooper())
    private fun idle() = looper.idle()
    private fun advance(ms: Long) = looper.idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        GlassesHubTestSupport.install(context)
        HudController.hostFactory = { service, manager -> HudHost(service, manager).also { host = it } }
    }

    @After
    fun tearDown() {
        shown.forEach { hide(it) }
        connected.forEach { HudController.onServiceDestroyed(it) }
        connected.clear()
        HudController.hostFactory = { service, manager -> HudHost(service, manager) }
        SurfaceController.setInkResyncListener(null)
        idle()
        GlassesHubTestSupport.uninstall()
    }

    private fun connect(refuseWindows: Boolean = false): AccessibilityService {
        val service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
        if (refuseWindows) {
            val refusing = RefusingWindowManager(service.getSystemService(WindowManager::class.java))
            HudController.hostFactory = { s, _ -> HudHost(s, refusing).also { host = it } }
        }
        HudController.onServiceConnected(service)
        connected += service
        idle()
        return service
    }

    private fun show(payload: JSONObject) {
        shown += payload.getString("surfaceId")
        SurfaceController.handleSurfaceEnvelope(context, BusEnvelope(BusPaths.SURFACE_SHOW, payload = payload))
        idle()
    }

    private fun hide(id: String) {
        SurfaceController.handleSurfaceEnvelope(
            context,
            BusEnvelope(
                BusPaths.SURFACE_HIDE,
                payload = JSONObject().put("surfaceId", id).put("seq", GlassesHubTestSupport.nextSeq()),
            ),
        )
        idle()
    }

    private fun card(id: String, handlesBack: Boolean = false, editable: JSONObject? = null) =
        GlassesHubTestSupport.cardPayload(id, handlesBack, editable)

    private fun reader(id: String) = JSONObject()
        .put("surfaceId", id).put("seq", GlassesHubTestSupport.nextSeq()).put("kind", "reader")
        .put("contentKey", "book").put("ownerPluginId", id.substringBefore(':'))
        .put(
            "segments",
            JSONArray().put(JSONObject().put("kind", "prose").put("text", "Once upon a time.")),
        )

    private fun inputs() = GlassesHubTestSupport.sentOn(BusPaths.SURFACE_INPUT)
        .map { it.payload.getInt("keyCode") to it.payload.getInt("action") }

    private fun inkEvents() = GlassesHubTestSupport.sentOn(BusPaths.INK_EVENT).map { it.payload }


    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP

    // ---- 01 §7.6 item 124: the intents the machine routes to the surface --------------------

    @Test
    fun item124_next_prev_and_select_become_a_dpad_pair_each() {
        connect()
        show(card("relay:card"))
        val id = "relay:card"

        SurfaceController.onHudIntent(id, HudIntent.Next)
        SurfaceController.onHudIntent(id, HudIntent.Prev)
        SurfaceController.onHudIntent(id, HudIntent.Select)

        assertEquals(
            listOf(
                KeyEvent.KEYCODE_DPAD_RIGHT to down, KeyEvent.KEYCODE_DPAD_RIGHT to up,
                KeyEvent.KEYCODE_DPAD_LEFT to down, KeyEvent.KEYCODE_DPAD_LEFT to up,
                KeyEvent.KEYCODE_ENTER to down, KeyEvent.KEYCODE_ENTER to up,
            ),
            inputs(),
        )
        assertTrue(
            "every event names the surface",
            GlassesHubTestSupport.sentOn(BusPaths.SURFACE_INPUT).all { it.payload.getString("surfaceId") == id },
        )
    }

    @Test
    fun item124_raw_keys_keep_their_direction_and_only_the_forwarded_set_reaches_the_plugin() {
        connect()
        show(card("relay:card"))
        val id = "relay:card"

        SurfaceController.onHudIntent(id, HudIntent.Raw(RawKey(KeyEvent.KEYCODE_MEDIA_NEXT, isDown = true)))
        SurfaceController.onHudIntent(id, HudIntent.Raw(RawKey(KeyEvent.KEYCODE_MEDIA_NEXT, isDown = false)))
        SurfaceController.onHudIntent(id, HudIntent.Raw(RawKey(KeyEvent.KEYCODE_A, isDown = true)))
        // The touchpad contact is forwarded as itself, whatever the forwarded set says.
        SurfaceController.onHudIntent(id, HudIntent.Raw(RawKey(83, isDown = true)))

        assertEquals(
            listOf(KeyEvent.KEYCODE_MEDIA_NEXT to down, KeyEvent.KEYCODE_MEDIA_NEXT to up, 83 to down),
            inputs(),
        )
    }

    @Test
    fun item124_dismiss_forwards_back_down_once_and_an_intent_for_another_surface_is_ignored() {
        connect()
        show(card("relay:card", handlesBack = true))

        SurfaceController.onHudIntent("someone-else:card", HudIntent.Select)
        assertTrue(inputs().isEmpty())

        SurfaceController.onHudIntent("relay:card", HudIntent.Dismiss)
        assertEquals(listOf(KeyEvent.KEYCODE_BACK to down), inputs())
    }

    @Test
    fun item114_124_a_reader_scrolls_on_next_and_prev_and_is_sent_only_back_enter_and_center() {
        connect()
        show(reader("relay:book"))
        val id = "relay:book"
        val scrolls = ArrayList<Int>()
        val stop = SurfaceController.observeReaderScroll { scrolls += it }

        SurfaceController.onHudIntent(id, HudIntent.Next)
        SurfaceController.onHudIntent(id, HudIntent.Prev)
        idle()
        stop()
        assertEquals(listOf(1, -1), scrolls)
        assertTrue("directions are never forwarded from a reader", inputs().isEmpty())

        SurfaceController.onHudIntent(id, HudIntent.Select)
        SurfaceController.onHudIntent(id, HudIntent.Raw(RawKey(KeyEvent.KEYCODE_SPACE)))
        SurfaceController.onHudIntent(id, HudIntent.Raw(RawKey(KeyEvent.KEYCODE_DPAD_LEFT)))
        assertEquals(listOf(KeyEvent.KEYCODE_ENTER to down, KeyEvent.KEYCODE_ENTER to up), inputs())
    }

    // ---- 01 §7.1 item 15: the controller's side of a reconnect ------------------------------

    @Test
    fun item15_the_active_surface_survives_a_service_destroy_and_connect_cycle() {
        val first = connect()
        show(card("relay:card"))
        val surface = SurfaceController.activeSurface()
        assertNotNull(surface)
        assertTrue(HudController.isShowingSurface("relay:card"))

        HudController.onServiceDestroyed(first)
        idle()
        assertSame("the state is not tied to the window", surface, SurfaceController.activeSurface())
        assertFalse(HudController.isShowingSurface("relay:card") && host!!.isAttached)

        connect()
        assertSame(surface, SurfaceController.activeSurface())
        assertTrue("the machine re-shows the surface", HudController.isShowingSurface("relay:card"))
        assertTrue(host!!.isAttached)
        assertEquals("and the app layer draws the controller's surface", "relay:card", host!!.app.surfaceId)
    }

    @Test
    fun item15_a_surface_hidden_while_the_service_was_down_is_not_brought_back() {
        val first = connect()
        show(card("relay:card"))
        HudController.onServiceDestroyed(first)
        hide("relay:card")
        assertNull(SurfaceController.activeSurface())

        connect()
        assertFalse(HudController.isShowingSurface("relay:card"))
        assertEquals(HudScreen.Hidden, HudController.state.screen)
    }

    // ---- 01 §7.4 items 85 and 81: the overlay is not there -----------------------------------

    @Test
    fun item85_a_card_whose_overlay_cannot_attach_falls_back_to_the_activity() {
        connect(refuseWindows = true)

        show(card("relay:card"))

        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull("the surface moved to SurfaceActivity", started)
        assertEquals(SurfaceActivity::class.java.name, started.component?.className)
        assertEquals("relay:card", started.getStringExtra("surfaceId"))
        val screen = HudController.state.screen
        assertTrue(screen is HudScreen.External && screen.kind == ExternalKind.ACTIVITY_SURFACE)
        assertNotNull("the surface itself is kept", SurfaceController.activeSurface())
    }

    @Test
    fun item85_on_overlay_unavailable_moves_an_on_screen_card_to_the_activity_path() {
        connect()
        show(card("relay:card"))
        assertTrue(HudController.state.screen is HudScreen.App)

        SurfaceController.onOverlayUnavailable("relay:card")
        idle()

        val screen = HudController.state.screen
        assertTrue(screen is HudScreen.External && screen.kind == ExternalKind.ACTIVITY_SURFACE)
        assertEquals(SurfaceActivity::class.java.name, shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity.component?.className)

        SurfaceController.onOverlayUnavailable("another:surface")
        idle()
        assertNotNull("an unknown surface id changes nothing", SurfaceController.activeSurface())
    }

    @Test
    fun item81_an_ink_surface_whose_overlay_is_unavailable_closes_with_renderer_error() {
        connect()
        showInk("relay:ink")
        assertNotNull(SurfaceController.activeSurface())

        SurfaceController.onOverlayUnavailable("relay:ink")
        idle()

        val closed = inkEvents().single { it.getString("type") == "closed" }
        assertEquals("relay:ink", closed.getString("surfaceId"))
        assertEquals("renderer_error", closed.getString("reason"))
        assertNull(SurfaceController.activeSurface())
        assertNull("an Ink surface never uses the activity path", shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test
    fun item81_an_ink_surface_shown_with_no_service_closes_with_renderer_error() {
        showInk("relay:ink", waitForActive = false)
        waitFor { inkEvents().any { it.getString("type") == "closed" } }

        val closed = inkEvents().single { it.getString("type") == "closed" }
        assertEquals("renderer_error", closed.getString("reason"))
        assertNull(SurfaceController.activeSurface())
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    // ---- 01 §7.5 item 102: the Ink events a plugin receives ---------------------------------

    @Test
    fun item102_ready_is_sent_once_with_the_surface_id_and_type() {
        connect()
        showInk("relay:ink")
        advance(700)

        val ready = inkEvents().filter { it.getString("type") == "ready" }
        assertEquals(1, ready.size)
        assertEquals(setOf("surfaceId", "type"), ready.single().keys().asSequence().toSet())
        assertEquals("relay:ink", ready.single().getString("surfaceId"))
        advance(2_000)
        assertEquals("never again for the same frame", 1, inkEvents().count { it.getString("type") == "ready" })
    }

    @Test
    fun item102_closed_carries_the_reason_for_each_way_a_session_ends() {
        connect()
        // the plugin hides it
        showInk("relay:ink1")
        hide("relay:ink1")
        // the wearer dismisses it
        showInk("relay:ink2")
        SurfaceController.closeFromHud("relay:ink2", CloseReason.WEARER_DISMISSED)
        idle()
        // another surface replaces it
        showInk("relay:ink3")
        show(card("relay:card"))
        // the phone link drops
        showInk("relay:ink4")
        SurfaceController.onPhoneLinkLost()
        idle()

        val closed = inkEvents().filter { it.getString("type") == "closed" }
            .associate { it.getString("surfaceId") to it.getString("reason") }
        assertEquals("plugin", closed["relay:ink1"])
        assertEquals("user", closed["relay:ink2"])
        assertEquals("replaced", closed["relay:ink3"])
        assertEquals("link_lost", closed["relay:ink4"])
        assertTrue(
            "closed carries exactly the surface id, the type and the reason",
            inkEvents().filter { it.getString("type") == "closed" }
                .all { it.keys().asSequence().toSet() == setOf("surfaceId", "type", "reason") },
        )
    }

    @Test
    fun item102_a_patch_for_another_document_asks_for_a_resync_with_both_identities() {
        connect()
        showInk("relay:ink")
        val resyncs = ArrayList<InkResyncRequestProbe>()
        SurfaceController.setInkResyncListener { resyncs += InkResyncRequestProbe(it.patchDocumentId) }

        val patch = JSONObject()
            .put("v", 1).put("doc", "another-doc").put("baseRev", 5).put("targetRev", 6)
            .put("changes", JSONArray())
        show(
            JSONObject()
                .put("surfaceId", "relay:ink").put("seq", GlassesHubTestSupport.nextSeq()).put("kind", "ink")
                .put("contentKey", "ink").put("ownerPluginId", "relay")
                .put("ink", JSONObject().put("patch", patch.toString())),
        )
        waitFor { inkEvents().any { it.getString("type") == "resync" } }

        val resync = inkEvents().single { it.getString("type") == "resync" }
        assertEquals("relay:ink", resync.getString("surfaceId"))
        assertEquals("doc-relay:ink", resync.getString("documentId"))
        assertEquals(0, resync.getInt("revision"))
        assertEquals("another-doc", resync.getString("patchDocumentId"))
        assertEquals(5, resync.getInt("patchBaseRevision"))
        assertEquals("another-doc", resyncs.single().patchDocumentId)
    }

    private data class InkResyncRequestProbe(val patchDocumentId: String)

    // ---- 01 §7.5 item 115: Ink keys arrive as synthesised events through deliverKey -----------

    @Test
    fun item115_next_moves_the_ink_selection_locally_and_select_emits_the_selected_action() {
        connect()
        showInk("relay:ink", actions = listOf("first", "second"))
        layOutHost()

        SurfaceController.onHudIntent("relay:ink", HudIntent.Next)
        assertTrue("the ink view consumed the direction: nothing goes to the plugin", inputs().isEmpty())

        SurfaceController.onHudIntent("relay:ink", HudIntent.Select)

        val action = inkEvents().single { it.getString("type") == "action" }
        assertEquals("second", action.getString("actionId"))
        assertEquals("relay:ink", action.getString("surfaceId"))
        assertNotNull(action.getJSONObject("dataset"))
        assertTrue("the confirm pair is consumed by the Ink view, DOWN and UP", inputs().isEmpty())
    }

    @Test
    fun item115_without_a_selectable_action_ink_directions_fall_through_as_a_forwarded_pair() {
        connect()
        showInk("relay:ink", actions = emptyList())
        layOutHost()

        SurfaceController.onHudIntent("relay:ink", HudIntent.Next)

        assertEquals(
            "not consumed locally, so the plugin gets the D-pad the wearer swiped",
            listOf(KeyEvent.KEYCODE_DPAD_RIGHT to down, KeyEvent.KEYCODE_DPAD_RIGHT to up),
            inputs(),
        )
        assertTrue(inkEvents().none { it.getString("type") == "action" })
    }

    // ---- 01 §7.5 item 97: an editable card commits or cancels ------------------------------------

    private fun editableCard(id: String = "assistant:board", handlesBack: Boolean = false) =
        card(id, handlesBack = handlesBack, editable = JSONObject().put("placeholder", "Ask"))

    private fun back() = HudController.onRawKey(
        RawKeyEvent(KeyEvent.KEYCODE_BACK, RawKeyEvent.ACTION_DOWN, 0, android.os.SystemClock.uptimeMillis(), DeviceClass.KEYBOARD_DPAD),
        null,
    )

    private fun editField(): EditText {
        fun walk(view: View): EditText? {
            if (view is EditText) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))?.let { return it }
            return null
        }
        return walk(host!!.contentViewForTest) ?: fail("no editable field in the host") as Nothing
    }

    private fun committed() = GlassesHubTestSupport.sentOn(BusPaths.SURFACE_TEXT_COMMITTED).map { it.payload }

    @Test
    fun item97_a_hardware_enter_commits_the_typed_text_with_the_owner_and_keeps_the_card() {
        connect()
        show(editableCard())
        val field = editField()
        field.setText("hello")

        field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))

        val payload = committed().single()
        assertEquals("assistant:board", payload.getString("surfaceId"))
        assertEquals("hello", payload.getString("text"))
        assertFalse(payload.getBoolean("cancelled"))
        assertEquals("assistant", payload.getString("ownerPluginId"))
        assertNotNull("committing does not close the card: the plugin decides", SurfaceController.activeSurface())
    }

    @Test
    fun item97_the_ime_send_action_commits_the_same_way() {
        connect()
        show(editableCard())
        val field = editField()
        field.setText("via ime")

        field.onEditorAction(EditorInfo.IME_ACTION_SEND)

        val payload = committed().single()
        assertEquals("via ime", payload.getString("text"))
        assertFalse(payload.getBoolean("cancelled"))
    }

    @Test
    fun item97_back_cancels_with_no_text_and_only_that_path_sends_text_committed() {
        connect()
        show(editableCard())
        editField().setText("draft the wearer abandons")

        val handled = back()
        idle()

        assertTrue(handled)
        val payload = committed().single()
        assertTrue(payload.getBoolean("cancelled"))
        assertFalse("a cancelled field never carries text", payload.has("text"))
        assertEquals("assistant", payload.getString("ownerPluginId"))
        assertNull(SurfaceController.activeSurface())
    }

    @Test
    fun item97_back_on_a_card_that_handles_back_reaches_the_plugin_and_the_failsafe_then_cancels() {
        connect()
        show(editableCard(handlesBack = true))
        editField().setText("draft")

        assertTrue(back())
        idle()
        assertEquals("the plugin hears BACK first", listOf(KeyEvent.KEYCODE_BACK to down), inputs())
        assertTrue("and has not been cancelled yet", committed().isEmpty())

        advance(2_000)

        val payload = committed().single()
        assertTrue(payload.getBoolean("cancelled"))
        assertFalse(payload.has("text"))
        assertNull(SurfaceController.activeSurface())
    }

    @Test
    fun item97_dismissing_a_card_without_a_field_sends_no_text_committed() {
        connect()
        show(card("relay:card"))
        SurfaceController.closeFromHud("relay:card", CloseReason.WEARER_DISMISSED)
        idle()
        assertTrue(committed().isEmpty())
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun layOutHost() {
        val root = host!!.contentViewForTest
        repeat(2) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 480, 640)
            idle()
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        repeat(400) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("condition not reached")
    }

    private fun inkDocument(id: String, actions: List<String>): String {
        val children = actions.map { action ->
            RenderNode(
                action, "view",
                attributes = mapOf("id" to action),
                style = mapOf("height" to "64px", "width" to "100%", "flex-shrink" to "0", "border-width" to "1px"),
                events = mapOf("tap" to InkActionBinding(action, false)),
                dataset = mapOf("row" to action),
                children = listOf(RenderNode("$action-label", "text", text = action)),
            )
        } + RenderNode("static-label", "text", text = "Ink $id")
        val root = RenderNode(
            "root", "view",
            style = mapOf("display" to "flex", "flex-direction" to "column", "width" to "100%"),
            children = children,
        )
        return RenderDocument(listOf(root), documentId = "doc-$id").toWireJson()
    }

    /** Shows an Ink surface through the real envelope path and waits for its background decode. */
    private fun showInk(id: String, actions: List<String> = listOf("go"), waitForActive: Boolean = true) {
        shown += id
        SurfaceController.handleSurfaceEnvelope(
            context,
            BusEnvelope(
                BusPaths.SURFACE_SHOW,
                payload = JSONObject()
                    .put("surfaceId", id).put("seq", GlassesHubTestSupport.nextSeq()).put("kind", "ink")
                    .put("contentKey", "ink").put("ownerPluginId", id.substringBefore(':'))
                    .put("ink", JSONObject().put("document", inkDocument(id, actions))),
            ),
        )
        if (waitForActive) waitFor { SurfaceController.activeSurface()?.surfaceId == id }
    }
}
