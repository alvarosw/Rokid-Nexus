package com.anezium.rokidbus.glasses.hud

import android.content.Context
import android.os.Binder
import android.os.Looper
import com.anezium.rokidbus.glasses.ActivityController
import com.anezium.rokidbus.glasses.ActivityPresentation
import com.anezium.rokidbus.glasses.CameraOverlayVisibilityBridge
import com.anezium.rokidbus.glasses.GlassesHubTestSupport
import com.anezium.rokidbus.glasses.CameraOverlayVisibilityReceiver
import com.anezium.rokidbus.glasses.NoticeController
import com.anezium.rokidbus.glasses.PinController
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.NoticeInteractionIdentity
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import org.json.JSONArray
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * 01 §7.9 item 152 and §7.12 item 181: the camera overlay's edge, which arrives in the main process
 * as a broadcast from `:camera`, reaches the pin, the notice and the activity layer (F-16 fixed by
 * U6, docs/ui-rewrite/00-architecture.md). Whether a real `:camera` process sends that broadcast at
 * the right moments is [DEVICE].
 */
@RunWith(RobolectricTestRunner::class)
class AmbientCameraFanOutTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val identity = NoticeInteractionIdentity("instance-1", "question-1")

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        ActivityController.onServiceConnected(context) {}
        idle()
    }

    @After
    fun tearDown() {
        AmbientStack.setCameraOverlayActive(false)
        NoticeController.onPhoneLinkLost()
        PinController.handlePinEnvelope(
            BusEnvelope(BusPaths.PIN_HIDE, payload = JSONObject().put("seq", GlassesHubTestSupport.nextSeq())),
        )
        GlassesHubTestSupport.endActivity(context)
        ActivityController.onServiceDestroyed()
        idle()
    }

    private fun showPin() {
        PinController.handlePinEnvelope(
            BusEnvelope(
                BusPaths.PIN_SHOW,
                payload = JSONObject()
                    .put("kind", "pin").put("surfaceId", "relay:pin").put("seq", GlassesHubTestSupport.nextSeq())
                    .put("title", "PIN").put("lines", JSONArray().put("line")),
            ),
        )
        idle()
    }

    private fun showNotice(interactive: Boolean = true) {
        NoticeController.handleNoticeEnvelope(
            context,
            BusEnvelope(
                BusPaths.NOTICE_SHOW,
                payload = NoticeSurfaceContract.withInteractionIdentity(
                    JSONObject()
                        .put("kind", "notice").put("surfaceId", "relay:notice").put("seq", GlassesHubTestSupport.nextSeq())
                        .put("ownerPluginId", "relay")
                        .put("title", "Notice").put("body", "Body")
                        .put("interactive", interactive),
                    identity,
                ),
            ),
        )
        idle()
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

    private fun activityPresentation(): ActivityPresentation? {
        var seen: ActivityPresentation? = null
        ActivityController.observe { seen = it.primary?.presentation }()
        return seen
    }

    private fun observedPin(): Any? {
        var seen: Any? = null
        PinController.observe { seen = it }()
        return seen
    }

    @Test
    fun item152_the_camera_overlay_hides_the_pin_and_it_returns_with_the_camera() {
        showPin()
        assertNotNull(PinController.visiblePin())

        AmbientStack.setCameraOverlayActive(true)
        idle()
        assertNull("the pin is hidden over the camera", PinController.visiblePin())
        assertNull("and observers are told", observedPin())
        assertNotNull("but the slot is kept", PinController.activePin())

        AmbientStack.setCameraOverlayActive(false)
        idle()
        assertNotNull(PinController.visiblePin())
        assertNotNull(observedPin())
    }

    @Test
    fun item181_the_camera_overlay_takes_the_notice_out_of_view_and_out_of_input() {
        showNotice()
        assertNotNull(NoticeController.visibleNotice())
        assertTrue(NoticeController.ownsRingInput())
        assertTrue(NoticeController.claimsInput())

        AmbientStack.setCameraOverlayActive(true)
        idle()
        assertNull(NoticeController.visibleNotice())
        assertFalse("the ring bridge is not held by a notice nobody can see", NoticeController.ownsRingInput())
        assertFalse(NoticeController.claimsInput())
        assertFalse(NoticeController.claimsAllInput())
        assertFalse("a confirm reaches no hidden notice", NoticeController.handleConfirm(66))
        assertFalse(NoticeController.dismissFromBack())
        assertNotNull("the notice itself lives on", NoticeController.activeNotice())

        AmbientStack.setCameraOverlayActive(false)
        idle()
        assertNotNull(NoticeController.visibleNotice())
        assertTrue(NoticeController.ownsRingInput())
    }

    @Test
    fun item152_the_camera_overlay_hides_the_activity_layer_and_frees_its_input_claim() {
        startActivity()
        assertTrue(ActivityController.isPresenting())
        assertTrue(ActivityController.claimsInput())
        assertTrue(activityPresentation() != ActivityPresentation.HIDDEN)

        AmbientStack.setCameraOverlayActive(true)
        idle()
        assertEquals(ActivityPresentation.HIDDEN, activityPresentation())
        assertFalse(ActivityController.claimsInput())

        AmbientStack.setCameraOverlayActive(false)
        idle()
        assertTrue(activityPresentation() != ActivityPresentation.HIDDEN)
        assertTrue(ActivityController.claimsInput())
    }

    @Test
    fun item152_181_the_broadcast_from_the_camera_process_reaches_all_three_and_the_hud() {
        showPin()
        showNotice()
        startActivity()
        val token = Binder()
        val receiver = CameraOverlayVisibilityReceiver()

        receiver.onReceive(context, CameraOverlayVisibilityBridge.intent(context, token, active = true))
        idle()
        assertNull(PinController.visiblePin())
        assertNull(NoticeController.visibleNotice())
        assertEquals(ActivityPresentation.HIDDEN, activityPresentation())

        receiver.onReceive(context, CameraOverlayVisibilityBridge.intent(context, token, active = false))
        idle()
        assertNotNull(PinController.visiblePin())
        assertNotNull(NoticeController.visibleNotice())
        assertTrue(activityPresentation() != ActivityPresentation.HIDDEN)
    }

    @Test
    fun the_same_edge_twice_changes_nothing() {
        showPin()
        AmbientStack.setCameraOverlayActive(true)
        AmbientStack.setCameraOverlayActive(true)
        idle()
        assertNull(PinController.visiblePin())
        assertEquals(true, observedPin() == null)
    }
}
