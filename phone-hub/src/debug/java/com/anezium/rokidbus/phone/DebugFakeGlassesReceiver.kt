package com.anezium.rokidbus.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.HudModeContract
import com.anezium.rokidbus.shared.LinkStateBits
import org.json.JSONObject
import java.io.File

/**
 * DUMP-protected, debug-build-only fake glasses. Drives the phone hub as the glasses would:
 * forces the link up, injects inbound envelopes through `BusHubService.debugInjectRemote` and
 * captures every outbound envelope. See docs/EMULATION.md and tools/emulator/fake-glasses.sh.
 *
 * Extras, executed in this order when combined: `reset`, `link` (`up`|`down`, `release`),
 * `handshake`, `visibility` (`on`|`hidden`|`off`), `grid` (`on`|`off`), `approve` (package),
 * `screenOffGraceMs`, `media` (`play`|`pause`|`stop`, `title`, `artist`, `durationMs`),
 * `file` (script path readable by the app).
 */
class DebugFakeGlassesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION) return
        val app = context.applicationContext
        try {
            if (intent.getBooleanExtra(EXTRA_RESET, false)) {
                releaseOverrides(app)
                FakeGlassesMedia.release()
                log("reset")
            }
            intent.getStringExtra(EXTRA_LINK)?.let { setLink(app, it, intent.getBooleanExtra(EXTRA_RELEASE, false)) }
            if (intent.getBooleanExtra(EXTRA_RELEASE, false) && !intent.hasExtra(EXTRA_LINK)) {
                setLink(app, "up", release = true)
            }
            if (intent.getBooleanExtra(EXTRA_START, false)) BusHubService.start(app)
            val handshake = intent.getStringExtra(EXTRA_HANDSHAKE)
            if (handshake != null) {
                if (handshake != "default") {
                    log("rejected handshake='$handshake'")
                } else {
                    inject(app, FakeGlasses.capabilitiesEnvelope(BuildConfig.VERSION_NAME))
                    FakeGlasses.visibilityEnvelope("on")?.let { inject(app, it) }
                    log("handshake injected")
                }
            }
            intent.getStringExtra(EXTRA_VISIBILITY)?.let { mode ->
                val envelope = FakeGlasses.visibilityEnvelope(mode)
                if (envelope == null) log("rejected visibility='$mode'") else {
                    inject(app, envelope)
                    log("visibility $mode injected")
                }
            }
            intent.getStringExtra(EXTRA_GRID)?.let { setGrid(app, it) }
            intent.getStringExtra(EXTRA_APPROVE)?.let { approve(app, it) }
            if (intent.hasExtra(EXTRA_GRACE_MS)) {
                val grace = intent.getLongExtra(EXTRA_GRACE_MS, -1L)
                BusHubService.debugSetScreenOffGraceMs(app, grace.takeIf { it >= 0L })
                log("screenOffGraceMs=${grace.takeIf { it >= 0L }}")
            }
            intent.getStringExtra(EXTRA_MEDIA)?.let { state ->
                FakeGlassesMedia.update(
                    app,
                    state,
                    intent.getStringExtra(EXTRA_TITLE),
                    intent.getStringExtra(EXTRA_ARTIST),
                    intent.getLongExtra(EXTRA_DURATION_MS, -1L).takeIf { it >= 0L },
                )
            }
            if (intent.hasExtra(EXTRA_FILE)) {
                val text = readScript(intent.getStringExtra(EXTRA_FILE).orEmpty()) ?: return
                val steps = FakeGlasses.parse(text)
                FakeGlasses(
                    sink = { envelope -> BusHubService.debugInjectRemote(app, envelope) },
                    schedule = { delayMs, task -> timer.postDelayed(task, delayMs) },
                ).play(steps)
                log("played steps=${steps.size}")
            }
        } catch (error: Exception) {
            Log.e(TAG, "failed", error)
        }
    }

    private fun setLink(context: Context, mode: String, release: Boolean) {
        when {
            release -> {
                releaseOverrides(context)
                log("link released")
            }
            mode == "up" -> {
                BusHubService.debugSetOutboundInterceptor(context, ::capture)
                BusHubService.debugSetLink(
                    context,
                    LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP or
                        LinkStateBits.GLASSES_BT_BONDED_OR_PHONE_CONNECTED,
                )
                log("link up")
            }
            mode == "down" -> {
                // The interceptor stays so nothing that is sent meanwhile reaches the real stack.
                BusHubService.debugSetOutboundInterceptor(context, ::capture)
                BusHubService.debugSetLink(context, 0)
                log("link down")
            }
            else -> log("rejected link='$mode'")
        }
    }

    private fun releaseOverrides(context: Context) {
        BusHubService.debugSetOutboundInterceptor(context, null)
        BusHubService.debugSetLink(context, null)
        BusHubService.debugSetScreenOffGraceMs(context, null)
    }

    /** Logs the envelope the phone hub sent to the glasses; true consumes it. */
    private fun capture(envelope: BusEnvelope): Boolean {
        val binary = envelope.binary?.let { " binary=${it.size}" }.orEmpty()
        log("outbound ${envelope.path} ${envelope.payload}$binary")
        return true
    }

    private fun inject(context: Context, envelope: BusEnvelope) =
        BusHubService.debugInjectRemote(context, envelope)

    private fun setGrid(context: Context, value: String) {
        val enabled = when (value) {
            "on" -> true
            "off" -> false
            else -> {
                log("rejected grid='$value'")
                return
            }
        }
        HudModeSettingsStore(context).setGridModeEnabled(enabled)
        BusHubService.onHudModeSettingChanged()
        log("grid ${if (enabled) HudModeContract.MODE_GRID else HudModeContract.MODE_LIST}")
    }

    /** Approves every requested capability through the real grant store, as the permissions screen does. */
    private fun approve(context: Context, packageName: String) {
        val principals = PhonePluginDiscovery(context.packageManager).discoverPackage(packageName)
            .mapNotNull { (it as? PhonePluginCandidate.Valid)?.principal }
        if (principals.isEmpty()) {
            log("approve $packageName: no valid plugin found")
            return
        }
        val store = PluginGrantStore(context)
        principals.forEach { principal ->
            store.approve(principal, principal.descriptor.requestedCapabilities)
            BusHubService.onPluginAuthorizationChanged(context, principal.grantKey())
            log(
                "approved package=$packageName plugin=${principal.descriptor.id} " +
                    "capabilities=${principal.descriptor.requestedCapabilities.joinToString(",") { it.name }} " +
                    "state=${store.stateFor(principal)}",
            )
        }
    }

    private fun readScript(path: String): String? {
        val file = File(path)
        if (!file.isFile) {
            log("file not found path=$path")
            return null
        }
        if (file.length() > MAX_SCRIPT_BYTES) {
            log("file exceeds $MAX_SCRIPT_BYTES bytes path=$path")
            return null
        }
        return file.readText(Charsets.UTF_8)
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "FAKE_GLASSES"
        const val ACTION = "com.anezium.rokidbus.phone.DEBUG_GLASSES"
        const val EXTRA_RESET = "reset"
        const val EXTRA_LINK = "link"
        const val EXTRA_RELEASE = "release"
        const val EXTRA_START = "start"
        const val EXTRA_HANDSHAKE = "handshake"
        const val EXTRA_VISIBILITY = "visibility"
        const val EXTRA_GRID = "grid"
        const val EXTRA_APPROVE = "approve"
        const val EXTRA_GRACE_MS = "screenOffGraceMs"
        const val EXTRA_MEDIA = "media"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_DURATION_MS = "durationMs"
        const val EXTRA_FILE = "file"
        const val MAX_SCRIPT_BYTES = 4L * 1024 * 1024

        private val timer: Handler by lazy {
            Handler(HandlerThread("RokidNexusFakeGlasses").apply { start() }.looper)
        }
    }
}
