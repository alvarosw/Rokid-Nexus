package com.anezium.rokidbus.phone

import com.anezium.rokidbus.client.ui.HudGridMetrics
import com.anezium.rokidbus.ink.InkWire
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.EditableSurfaceContract
import com.anezium.rokidbus.shared.GlassesHubCapabilitiesContract
import com.anezium.rokidbus.shared.HomeVisibility
import com.anezium.rokidbus.shared.HomeVisibilityContract
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.PinSurfaceContract
import com.anezium.rokidbus.shared.SetupCompletionMode
import com.anezium.rokidbus.shared.SetupStage
import com.anezium.rokidbus.shared.TtsContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** One envelope the fake glasses send, [delayMs] after the step before it. */
internal data class FakeGlassesStep(val delayMs: Long, val envelope: BusEnvelope)

/**
 * Debug-only stand-in for the glasses hub, the mirror of the glasses-side FakePhone. Scripts are
 * handed to [sink], which in the app is `BusHubService.debugInjectRemote`, so the phone hub cannot
 * tell them from SPP/CXR traffic.
 *
 * Script JSON is one envelope `{path, id?, payload?, binaryBase64?, delayMs?}`, an array of
 * envelopes, or `{envelopes: [...]}`. `delayMs` is relative to the previous step.
 */
internal class FakeGlasses(
    private val sink: (BusEnvelope) -> Unit,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
) {
    fun play(steps: List<FakeGlassesStep>) {
        var elapsed = 0L
        steps.forEach { step ->
            elapsed += step.delayMs
            if (elapsed == 0L) sink(step.envelope) else schedule(elapsed) { sink(step.envelope) }
        }
    }

    companion object {
        fun parse(text: String): List<FakeGlassesStep> {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) return parseSteps(JSONArray(trimmed))
            val root = JSONObject(trimmed)
            if (root.has("path")) return listOf(parseStep(root))
            return parseSteps(root.optJSONArray("envelopes") ?: JSONArray())
        }

        private fun parseSteps(array: JSONArray): List<FakeGlassesStep> =
            List(array.length()) { parseStep(array.getJSONObject(it)) }

        private fun parseStep(json: JSONObject): FakeGlassesStep {
            val path = json.getString("path")
            val payload = json.optJSONObject("payload") ?: JSONObject()
            val binary = json.optString("binaryBase64").takeIf { it.isNotEmpty() }
                ?.let { Base64.getMimeDecoder().decode(it) }
            val envelope = if (json.has("id")) {
                BusEnvelope(path = path, id = json.getString("id"), payload = payload, binary = binary)
            } else {
                BusEnvelope(path = path, payload = payload, binary = binary)
            }
            return FakeGlassesStep(json.optLong("delayMs", 0L), envelope)
        }

        /**
         * What a current glasses hub announces on transport-up (`GlassesHub.announceRendererCapabilities`),
         * built through the same contract with the same values for fully set-up glasses. Keep in step
         * with that function when it gains a capability.
         */
        fun capabilitiesEnvelope(versionName: String): BusEnvelope = BusEnvelope(
            path = BusPaths.HUB_CAPABILITIES,
            payload = GlassesHubCapabilitiesContract.toJson(
                GlassesHubCapabilitiesContract.create(
                    features = BusCapabilityBits.IMAGE_SURFACE or
                        BusCapabilityBits.PIN_SURFACE or
                        BusCapabilityBits.NOTICE_SURFACE or
                        BusCapabilityBits.ACTIVITY_SURFACE or
                        BusCapabilityBits.ACTIVITY_EXTRAS or
                        BusCapabilityBits.INK_SURFACE or
                        BusCapabilityBits.EDITABLE_SURFACE or
                        BusCapabilityBits.TTS,
                    imageSurfaceVersion = ImageSurfaceContract.VERSION,
                    pinSurfaceVersion = PinSurfaceContract.VERSION,
                    noticeSurfaceVersion = NoticeSurfaceContract.VERSION,
                    activitySurfaceVersion = ActivitySurfaceContract.VERSION,
                    inkSurfaceVersion = InkWire.VERSION,
                    editableSurfaceVersion = EditableSurfaceContract.VERSION,
                    activityExtrasVersion = ActivitySurfaceContract.EXTRAS_VERSION,
                    maxImageBytes = ImageSurfaceContract.MAX_IMAGE_BYTES,
                    versionName = versionName,
                    setupComplete = true,
                    setupStage = SetupStage.COMPLETE,
                    setupCompletionMode = SetupCompletionMode.AUTOMATIC,
                    coreReady = true,
                    maintenanceReady = true,
                    ttsVersion = TtsContract.VERSION,
                    homeGridVisibleRows = HudGridMetrics.visibleRows(0),
                ),
            ),
        )

        /** `on` = display on and home shown, `hidden` = display on, home covered, `off` = display off. */
        fun visibilityEnvelope(mode: String): BusEnvelope? {
            val report = when (mode) {
                "on" -> HomeVisibility(screenOn = true, homeVisible = true)
                "hidden" -> HomeVisibility(screenOn = true, homeVisible = false)
                "off" -> HomeVisibility(screenOn = false, homeVisible = false)
                else -> return null
            }
            return BusEnvelope(
                path = HomeVisibilityContract.PATH,
                payload = HomeVisibilityContract.toPayload(report),
            )
        }
    }
}
