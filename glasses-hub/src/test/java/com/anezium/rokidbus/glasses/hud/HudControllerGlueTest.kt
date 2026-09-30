package com.anezium.rokidbus.glasses.hud

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import com.anezium.rokidbus.glasses.ActivityController
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.glasses.GlassesHubTestSupport
import com.anezium.rokidbus.glasses.HudTopInset
import com.anezium.rokidbus.glasses.NoticeController
import com.anezium.rokidbus.glasses.NoticeKeyDispatcher
import com.anezium.rokidbus.glasses.SurfaceController
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The glue of [HudController] against its real collaborators (docs/ui-rewrite/00-architecture.md
 * §2): a raw key becomes a [HudIntent] in [HudInput], the intent is routed to the machine, the
 * notice or the activity layer, and the machine's effects reach the [HudHost], the bus and the
 * controllers. Only the phone link and the window manager are replaced.
 */
@RunWith(RobolectricTestRunner::class)
class HudControllerGlueTest {
    private companion object {
        var clockBase = 60_000L
    }

    private val context: Context = RuntimeEnvironment.getApplication()
    private var host: HudHost? = null
    private val connected = ArrayList<AccessibilityService>()
    private val surfaces = ArrayList<String>()

    private val looper get() = shadowOf(Looper.getMainLooper())

    @Before
    fun setUp() {
        // Reduced motion: a morph lands inside the call and the host is in its final layout.
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        NoticeKeyDispatcher.reset()
        GlassesHubTestSupport.install(context)
        GlassesHubTestSupport.launcherList("alpha", "beta", "gamma")
        HudController.hostFactory = { service, manager -> HudHost(service, manager).also { host = it } }
        val service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
        HudController.onServiceConnected(service)
        connected += service
        idle()
        // HudInput's gesture detectors outlive a connection and Robolectric restarts the uptime clock
        // in every test: start every test later than the last one, past any window it left open.
        advance(clockBase)
        clockBase += 60_000
    }

    @After
    fun tearDown() {
        surfaces.forEach { hideSurface(it) }
        connected.forEach { HudController.onServiceDestroyed(it) }
        HudController.hostFactory = { service, manager -> HudHost(service, manager) }
        NoticeController.onPhoneLinkLost()
        GlassesHubTestSupport.endActivity(context)
        ActivityController.onServiceDestroyed()
        idle()
        GlassesHubTestSupport.uninstall()
    }

    private fun idle() = looper.idle()

    private fun advance(ms: Long) = looper.idleFor(Duration.ofMillis(ms))

    private fun now() = SystemClock.uptimeMillis()

    private fun key(code: Int, device: DeviceClass, action: Int, repeat: Int = 0): Boolean =
        HudController.onRawKey(RawKeyEvent(code, action, repeat, now(), device), null)

    private fun press(code: Int, device: DeviceClass = DeviceClass.KEYBOARD_DPAD): Boolean {
        val down = key(code, device, RawKeyEvent.ACTION_DOWN)
        key(code, device, RawKeyEvent.ACTION_UP)
        idle()
        return down
    }

    private fun tripleTap() {
        repeat(3) {
            key(83, DeviceClass.TOUCHPAD, RawKeyEvent.ACTION_DOWN)
            key(83, DeviceClass.TOUCHPAD, RawKeyEvent.ACTION_UP)
            advance(50)
        }
        // The firmware's tap that follows a triple tap (BACK/ENTER within 800 ms) is consumed (item 20).
        advance(1_000)
        idle()
    }

    private fun ringTap() {
        key(85, DeviceClass.R08, RawKeyEvent.ACTION_DOWN)
        key(85, DeviceClass.R08, RawKeyEvent.ACTION_UP)
    }

    private fun showSurface(id: String, handlesBack: Boolean = false) {
        surfaces += id
        SurfaceController.handleSurfaceEnvelope(
            context,
            BusEnvelope(BusPaths.SURFACE_SHOW, payload = GlassesHubTestSupport.cardPayload(id, handlesBack)),
        )
        idle()
    }

    private fun hideSurface(id: String) {
        SurfaceController.handleSurfaceEnvelope(
            context,
            BusEnvelope(
                BusPaths.SURFACE_HIDE,
                payload = JSONObject().put("surfaceId", id).put("seq", GlassesHubTestSupport.nextSeq()),
            ),
        )
        idle()
    }

    private fun showNotice(
        actions: List<String> = emptyList(),
        instanceId: String = "instance-1",
        questionId: String = "question-1",
    ) {
        NoticeController.handleNoticeEnvelope(
            context,
            BusEnvelope(
                BusPaths.NOTICE_SHOW,
                payload = GlassesHubTestSupport.noticePayload(
                    actions = actions,
                    instanceId = instanceId,
                    questionId = questionId,
                ),
            ),
        )
        idle()
    }

    private fun sentActions() = GlassesHubTestSupport.sentOn(BusPaths.NOTICE_ACTION).map { it.payload.getString("id") }

    private fun screen() = HudController.state.screen

    /** The selection survives a service reconnect (item 33), so a test never assumes where it starts. */
    private fun selected() = (screen() as HudScreen.Home).selectedId!!

    private val ids = listOf("alpha", "beta", "gamma")

    private fun after(id: String) = ids[(ids.indexOf(id) + 1) % ids.size]

    // ---- the main flows ---------------------------------------------------------------------

    @Test
    fun a_hidden_hud_passes_every_key_to_the_system() {
        assertEquals(HudScreen.Hidden, screen())
        listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_RIGHT).forEach {
            assertFalse("key $it", press(it))
        }
        assertFalse(host!!.isAttached)
    }

    @Test
    fun a_triple_tap_opens_the_launcher_in_one_persistent_window() {
        tripleTap()

        assertTrue(screen() is HudScreen.Home)
        assertTrue(HudController.isLauncherShown())
        val host = host!!
        assertTrue(host.isAttached)
        assertEquals(View.VISIBLE, host.home.visibility)
        assertEquals(View.GONE, host.app.visibility)
        assertEquals(
            "the home draws the phone's plugins",
            listOf("alpha", "beta", "gamma"),
            host.home.currentModel.entries.map { it.id },
        )
        assertEquals((screen() as HudScreen.Home).selectedId, host.home.currentModel.selectedId)
        assertTrue("the R08 bridge is told the ring is ours", HudController.state.ringFocus())
    }

    @Test
    fun the_third_contact_of_a_triple_tap_is_consumed_and_the_first_two_are_not() {
        val results = (1..3).map {
            val down = key(83, DeviceClass.TOUCHPAD, RawKeyEvent.ACTION_DOWN)
            key(83, DeviceClass.TOUCHPAD, RawKeyEvent.ACTION_UP)
            advance(50)
            down
        }
        assertEquals(listOf(false, false, true), results)
    }

    @Test
    fun a_swipe_moves_the_selection_and_select_sends_the_launcher_open_through_the_hub() {
        tripleTap()
        val expected = after(selected())
        assertTrue(press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(expected, selected())
        assertEquals(expected, host!!.home.currentModel.selectedId)

        assertTrue(press(KeyEvent.KEYCODE_ENTER))

        val open = GlassesHubTestSupport.sentOn(BusPaths.LAUNCHER_OPEN).single()
        assertEquals(expected, open.payload.getString("pluginId"))
        assertTrue(screen() is HudScreen.Opening)
        assertEquals(HomeStatus.Opening(expected), host!!.home.currentModel.status)
    }

    @Test
    fun a_send_that_fails_returns_to_the_home_with_a_status() {
        tripleTap()
        val plugin = selected()
        GlassesHubTestSupport.linkUp = false

        press(KeyEvent.KEYCODE_ENTER)

        assertTrue("home again, not stuck in Opening", screen() is HudScreen.Home)
        val status = host!!.home.currentModel.status
        assertTrue(status is HomeStatus.Failed)
        assertEquals(
            "Could not open ${plugin.replaceFirstChar(Char::uppercase)}: not sent",
            (status as HomeStatus.Failed).text,
        )
    }

    @Test
    fun the_surface_that_answers_the_open_shows_in_the_app_layer_and_dismiss_returns_home() {
        tripleTap()
        val plugin = selected()
        val surfaceId = "$plugin:board"
        press(KeyEvent.KEYCODE_ENTER)
        showSurface(surfaceId)

        val screen = screen()
        assertTrue(screen is HudScreen.App)
        assertEquals(surfaceId, (screen as HudScreen.App).surface.surfaceId)
        val host = host!!
        assertEquals(View.VISIBLE, host.app.visibility)
        assertEquals(View.GONE, host.home.visibility)
        assertEquals(surfaceId, host.app.surfaceId)
        assertTrue(HudController.isShowingSurface(surfaceId))
        assertFalse("the launcher is not shown over an app it opened", HudController.isLauncherShown())

        assertTrue("BACK belongs to the HUD while it owns a surface", press(KeyEvent.KEYCODE_BACK))

        assertTrue("the wearer returns to the launcher they came from", screen() is HudScreen.Home)
        assertEquals(plugin, selected())
        assertEquals(View.GONE, host.app.visibility)
        assertNull("the surface is gone from the controller", SurfaceController.activeSurface())
        assertEquals(
            "a card without handlesBack is dismissed locally and the plugin hears BACK",
            KeyEvent.KEYCODE_BACK,
            GlassesHubTestSupport.sentOn(BusPaths.SURFACE_INPUT).single().payload.getInt("keyCode"),
        )
    }

    @Test
    fun dismissing_the_home_hides_the_window_and_frees_the_ring() {
        tripleTap()
        assertTrue(press(KeyEvent.KEYCODE_BACK))
        assertEquals(HudScreen.Hidden, screen())
        assertFalse(host!!.isAttached)
        assertFalse(HudController.state.ringFocus())

        val focus = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents
            .filter { it.action == "com.anezium.r08accessbridge.action.NEXUS_RING_FOCUS" }
            .map { it.getBooleanExtra("focused", false) }
        assertEquals("one edge up, one edge down", listOf(true, false), focus)
        assertFalse("with nothing on screen the next BACK is the system's", press(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun the_broadcast_toggle_and_the_app_icon_go_through_the_same_machine() {
        assertEquals("shown", HudController.toggleLauncherFromBroadcast())
        assertTrue(HudController.isLauncherShown())
        assertEquals("hidden", HudController.toggleLauncherFromBroadcast())
        assertFalse(HudController.isLauncherShown())

        assertTrue(HudController.openLauncher(LauncherTrigger.APP_ICON))
        idle()
        assertTrue(HudController.isLauncherShown())
        assertTrue("the app icon only opens", HudController.openLauncher(LauncherTrigger.APP_ICON))
        idle()
        assertTrue(HudController.isLauncherShown())
    }

    // ---- 01 §7.2 items 36 and 38: the camera entry ---------------------------------------------

    private fun selectCamera() {
        GlassesHubTestSupport.advertiseCamera("Lens")
        idle()
        tripleTap()
        repeat(ids.size + 1) {
            if (selected() != "camera") press(KeyEvent.KEYCODE_DPAD_RIGHT)
            advance(200) // swipes closer than 150 ms are one swipe
        }
        assertEquals("camera", selected())
    }

    @Test
    fun item36_38_the_camera_entry_starts_the_camera_activity_and_hands_the_display_over() {
        selectCamera()
        assertEquals("the camera entry is first, named by the phone", "Lens", host!!.home.currentModel.entries.first().displayName)

        press(KeyEvent.KEYCODE_ENTER)

        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(com.anezium.rokidbus.glasses.CameraActivity::class.java.name, started.component?.className)
        assertTrue(GlassesHubTestSupport.sentOn(BusPaths.LAUNCHER_OPEN).isEmpty())
        val screen = screen()
        assertTrue(screen is HudScreen.External && screen.kind == ExternalKind.CAMERA)
        assertFalse(host!!.isAttached)
    }

    @Test
    fun item36_a_camera_that_cannot_start_keeps_the_launcher_and_says_so() {
        selectCamera()
        val failing = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: android.content.Intent) =
                throw android.content.ActivityNotFoundException("no camera")
        }
        GlassesHubTestSupport.setAppContext(failing)

        press(KeyEvent.KEYCODE_ENTER)

        assertTrue("still the launcher the wearer opened", screen() is HudScreen.Home)
        val status = host!!.home.currentModel.status
        assertTrue(status is HomeStatus.Failed)
        assertEquals("Could not open Lens: not sent", (status as HomeStatus.Failed).text)
    }

    // ---- a notice claims first --------------------------------------------------------------

    @Test
    fun a_visible_notice_takes_enter_before_the_launcher_does() {
        tripleTap()
        showNotice(actions = listOf("yes", "no"))

        assertTrue(press(KeyEvent.KEYCODE_ENTER))

        assertEquals("the notice's selected action answered", listOf("yes"), sentActions())
        assertTrue("and the launcher did not select anything", GlassesHubTestSupport.sentOn(BusPaths.LAUNCHER_OPEN).isEmpty())
        assertTrue(screen() is HudScreen.Home)
    }

    @Test
    fun back_dismisses_the_notice_first_and_the_launcher_on_the_next_press() {
        tripleTap()
        showNotice(actions = listOf("yes", "no"))

        assertTrue(press(KeyEvent.KEYCODE_BACK))
        assertNull(NoticeController.visibleNotice())
        assertTrue("the launcher survives the notice's dismissal", HudController.isLauncherShown())

        assertTrue(press(KeyEvent.KEYCODE_BACK))
        assertEquals(HudScreen.Hidden, screen())
    }

    @Test
    fun a_notice_owning_the_ring_rests_the_home_selection() {
        tripleTap()
        assertFalse(host!!.home.currentModel.noticeOwnsRing)
        showNotice(actions = listOf("yes", "no"))
        assertTrue(HudController.state.noticeOwnsRing)
        assertTrue(host!!.home.currentModel.noticeOwnsRing)
        assertNull("no focus frame under a notice", host!!.home.currentModel.focusedId)
    }

    // ---- 01 §7.7 items 128 and 129: the ring, the notice and the tap wiring --------------------

    @Test
    fun item128_a_ring_single_tap_answers_the_notice_with_its_selected_action() {
        showNotice(actions = listOf("yes", "no"))
        ringTap()
        assertTrue("the answer waits for the double-tap window", sentActions().isEmpty())

        advance(400)

        assertEquals(listOf("yes"), sentActions())
    }

    @Test
    fun item128_ring_directions_step_the_notice_selection_and_the_tap_answers_the_new_one() {
        showNotice(actions = listOf("yes", "no"))
        assertTrue("claimed, so the R08 bridge never sees it", key(87, DeviceClass.R08, RawKeyEvent.ACTION_DOWN))
        key(87, DeviceClass.R08, RawKeyEvent.ACTION_UP)
        idle()
        advance(200)

        ringTap()
        advance(400)

        assertEquals(listOf("no"), sentActions())
    }

    @Test
    fun item128_a_ring_double_tap_dismisses_the_notice_as_the_wearer() {
        showNotice(actions = listOf("yes", "no"))

        ringTap()
        advance(100)
        ringTap()
        advance(400)

        assertNull(NoticeController.visibleNotice())
        assertTrue("a double tap is a dismissal, never an answer", sentActions().isEmpty())
        val closed = GlassesHubTestSupport.sentOn(BusPaths.NOTICE_CLOSED).single()
        assertEquals("user", closed.payload.getString("reason"))
    }

    @Test
    fun item128_a_ring_key_the_notice_does_not_claim_is_swallowed_while_it_owns_the_ring() {
        // One action: directions belong to nobody, but the interactive band still owns the ring.
        showNotice(actions = listOf("yes"))
        assertTrue(key(88, DeviceClass.R08, RawKeyEvent.ACTION_DOWN))
        key(88, DeviceClass.R08, RawKeyEvent.ACTION_UP)
        assertTrue(sentActions().isEmpty())
    }

    @Test
    fun item129_a_notice_replaced_during_a_pending_ring_tap_does_not_receive_that_tap() {
        showNotice(actions = listOf("yes", "no"), instanceId = "instance-1", questionId = "question-1")
        ringTap()
        advance(100)

        // A different question arrives inside the double-tap window.
        showNotice(actions = listOf("ok", "later"), instanceId = "instance-2", questionId = "question-2")
        advance(400)

        assertTrue("the tap was aimed at the first question", sentActions().isEmpty())

        // The new question answers normally afterwards.
        ringTap()
        advance(400)
        assertEquals(listOf("ok"), sentActions())
    }

    @Test
    fun item129_a_notice_closed_during_a_pending_ring_tap_drops_the_tap() {
        showNotice(actions = listOf("yes", "no"))
        ringTap()
        advance(100)

        NoticeController.onPhoneLinkLost()
        idle()
        showNotice(actions = listOf("ok", "later"), instanceId = "instance-3", questionId = "question-3")
        advance(400)

        assertTrue(sentActions().isEmpty())
    }

    @Test
    fun item129_an_update_that_keeps_the_notice_identity_keeps_the_pending_tap() {
        showNotice(actions = listOf("yes", "no"))
        ringTap()
        advance(100)
        NoticeController.handleNoticeEnvelope(
            context,
            BusEnvelope(
                BusPaths.NOTICE_UPDATE,
                payload = GlassesHubTestSupport.noticePayload(actions = listOf("yes", "no"))
                    .put("body", "Body, updated"),
            ),
        )
        idle()
        advance(400)
        assertEquals("the same question, so the tap still answers it", listOf("yes"), sentActions())
    }

    @Test
    fun item130_the_activity_a_ring_tap_is_for_is_fixed_by_its_first_tap() {
        ActivityController.onServiceConnected(context) {}
        startActivity()
        ringTap()
        advance(400)
        assertEquals(
            "a tap on an idle activity opens its owner",
            "relay",
            GlassesHubTestSupport.sentOn(BusPaths.LAUNCHER_OPEN).single().payload.getString("pluginId"),
        )
    }

    @Test
    fun item130_a_tap_whose_activity_ended_before_it_resolved_fires_nothing() {
        ActivityController.onServiceConnected(context) {}
        startActivity()
        ringTap()
        advance(100)
        ActivityController.handleActivityEnvelope(
            context,
            BusEnvelope(
                BusPaths.ACTIVITY_END,
                payload = JSONObject().put("surfaceId", "relay:activity").put("ownerPluginId", "relay")
                    .put("seq", GlassesHubTestSupport.nextSeq()),
            ),
        )
        idle()
        advance(400)
        assertTrue(GlassesHubTestSupport.sentOn(BusPaths.LAUNCHER_OPEN).isEmpty())
    }

    private fun startActivity() {
        ActivityController.handleActivityEnvelope(
            context,
            BusEnvelope(
                BusPaths.ACTIVITY_START,
                payload = JSONObject()
                    .put("kind", "activity").put("surfaceId", "relay:activity").put("ownerPluginId", "relay")
                    .put("seq", GlassesHubTestSupport.nextSeq()).put("glyph", "dot").put("primary", "Building"),
            ),
        )
        idle()
    }

    // ---- 01 §7.11 item 170: a top-inset change reaches the live window ------------------------

    @Test
    fun item170_a_top_inset_change_repositions_the_open_home_without_recreating_the_window() {
        tripleTap()
        val host = host!!
        val before = host.home.topInsetPx

        HudTopInset.set(context, manualDp = 40, auto = false)
        idle()

        assertEquals(HudTopInset.toPx(context, 40), host.home.topInsetPx)
        assertTrue(host.home.topInsetPx != before || before == HudTopInset.toPx(context, 40))
        assertTrue("still the same attached window", host.isAttached)
        val screen = host.home.screenForTest()
        assertNotNull(screen)
        assertEquals(
            "the rendered home is padded by the inset, not just the layer's field",
            HudInsetProbe.paddingTop(screen!!) - HudInsetProbe.safeY(),
            host.home.topInsetPx,
        )

        HudTopInset.set(context, manualDp = 0, auto = false)
        idle()
        assertEquals(0, host.home.topInsetPx)
    }

    @Test
    fun the_hub_is_the_only_owner_of_the_launcher_list_the_home_shows() {
        tripleTap()
        GlassesHubTestSupport.launcherList("alpha", "delta")
        idle()
        assertEquals(listOf("alpha", "delta"), host!!.home.currentModel.entries.map { it.id })
        assertNotNull(GlassesHub.LauncherEntry("alpha", "Alpha"))
    }
}

/** Reads the padding of a home screen view, which is the visible effect of the top inset. */
private object HudInsetProbe {
    fun paddingTop(view: View) = view.paddingTop
    fun safeY() = com.anezium.rokidbus.client.ui.RokidHudTokens.SAFE_Y
}
