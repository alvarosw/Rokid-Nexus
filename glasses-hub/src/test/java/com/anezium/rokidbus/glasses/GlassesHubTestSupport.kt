package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract
import com.anezium.rokidbus.shared.NoticeInteractionIdentity
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives [GlassesHub] without its radios. `start()` loads the vendor CXR library, which a JVM test
 * does not have, so the hub is marked started (its one-time bring-up is skipped) and given a
 * context by reflection; every envelope the hub would send lands in [sent] instead of a link.
 */
internal object GlassesHubTestSupport {
    val sent = ArrayList<BusEnvelope>()

    private val sequence = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

    /**
     * A sequence number above every one handed out before. The controllers are singletons that
     * remember the highest sequence they saw, so tests share one rising counter.
     */
    fun nextSeq(): Long = sequence.incrementAndGet()

    /** Whether the fake link accepts what the hub sends; false reproduces `NO_LINK`. */
    @Volatile var linkUp = true

    fun install(context: Context) {
        sent.clear()
        linkUp = true
        field("started").let { (it.get(GlassesHub) as AtomicBoolean).set(true) }
        field("appContext").set(GlassesHub, context.applicationContext)
        GlassesHub.outboundInterceptor = { envelope ->
            if (linkUp) sent += envelope
            linkUp
        }
    }

    fun uninstall() {
        GlassesHub.outboundInterceptor = null
        field("appContext").set(GlassesHub, null)
        field("launcherEntries").set(GlassesHub, emptyList<GlassesHub.LauncherEntry>())
        field("remotePhoneCapabilities").set(GlassesHub, PhoneHubCapabilitiesContract.create(0, null))
        sent.clear()
    }

    /** Delivers [envelope] as if the phone had sent it. */
    fun receive(envelope: BusEnvelope) {
        val ingress = GlassesHub::class.java.getDeclaredMethod("onRemoteEnvelope", BusEnvelope::class.java)
            .apply { isAccessible = true }
        ingress.invoke(GlassesHub, envelope)
    }

    fun receive(path: String, payload: JSONObject) = receive(BusEnvelope(path = path, payload = payload))

    /** A `/launcher/list` carrying [ids] as plugins named after their id. */
    fun launcherList(vararg ids: String) {
        val plugins = JSONArray()
        ids.forEach { plugins.put(JSONObject().put("id", it).put("displayName", it.replaceFirstChar(Char::uppercase))) }
        receive(BusPaths.LAUNCHER_LIST, JSONObject().put("plugins", plugins))
    }

    /** The phone advertises a camera consumer named [name]: the launcher gets a first `camera` entry. */
    fun advertiseCamera(name: String) = receive(
        BusPaths.HUB_CAPABILITIES,
        PhoneHubCapabilitiesContract.toJson(
            PhoneHubCapabilitiesContract.create(BusCapabilityBits.CAMERA_CONSUMER_READY, name),
        ),
    )

    /** Replaces the context the hub starts activities with, e.g. by one whose `startActivity` throws. */
    fun setAppContext(context: Context) = field("appContext").set(GlassesHub, context)

    fun sentOn(path: String): List<BusEnvelope> = sent.filter { it.path == path }

    /** Ends the `relay` activity the tests start, so no presented activity leaks into the next test. */
    fun endActivity(context: Context) {
        ActivityController.handleActivityEnvelope(
            context,
            BusEnvelope(
                BusPaths.ACTIVITY_END,
                payload = JSONObject().put("surfaceId", "relay:activity").put("ownerPluginId", "relay")
                    .put("seq", nextSeq()),
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ---- payload builders ------------------------------------------------------------------

    fun cardPayload(
        surfaceId: String,
        handlesBack: Boolean = false,
        editable: JSONObject? = null,
        seq: Long = nextSeq(),
    ): JSONObject = JSONObject()
        .put("surfaceId", surfaceId)
        .put("seq", seq)
        .put("kind", "card")
        .put("contentKey", "k")
        .put("title", "Title")
        .put("ownerPluginId", surfaceId.substringBefore(':'))
        .put("handlesBack", handlesBack)
        .put("lines", JSONArray().put("row"))
        .apply { editable?.let { put("editable", it) } }

    fun noticePayload(
        surfaceId: String = "relay:notice",
        instanceId: String = "instance-1",
        questionId: String = "question-1",
        actions: List<String> = emptyList(),
        seq: Long = nextSeq(),
    ): JSONObject = JSONObject()
        .put("kind", "notice")
        .put("surfaceId", surfaceId)
        .put("seq", seq)
        .put("ownerPluginId", surfaceId.substringBefore(':'))
        .put("title", "Notice")
        .put("body", "Body")
        .put("interactive", true)
        .let {
            NoticeSurfaceContract.withInteractionIdentity(it, NoticeInteractionIdentity(instanceId, questionId))
        }
        .apply {
            if (actions.isNotEmpty()) {
                put(
                    "actions",
                    JSONArray().also { array ->
                        actions.forEach { array.put(JSONObject().put("id", it).put("glyph", "check").put("label", it)) }
                    },
                )
            }
        }

    private fun field(name: String) = GlassesHub::class.java.getDeclaredField(name).apply { isAccessible = true }
}
