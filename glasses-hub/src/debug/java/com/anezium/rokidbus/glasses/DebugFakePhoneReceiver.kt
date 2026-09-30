package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.HudModeContract
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * DUMP-protected, debug-build-only fake phone. Feeds envelopes to `GlassesHub.onRemoteEnvelope`
 * exactly as phone traffic arrives. See docs/EMULATION.md and tools/emulator/fake-phone.sh.
 *
 * Extras: `file` (script path readable by the app), or `path` + `payload` (inline envelope),
 * `hudMode` (`list`|`grid`), `reset` (drop the `/launcher/open` rules).
 */
class DebugFakePhoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION) return
        val app = context.applicationContext
        val fake = phone(app)
        try {
            if (intent.getBooleanExtra(EXTRA_RESET, false)) {
                fake.reset()
                GlassesHub.outboundInterceptor = null
                log("FAKE_PHONE reset")
            }
            val hudMode = intent.getStringExtra(EXTRA_HUD_MODE)
            if (hudMode != null) {
                if (hudMode !in HudModeContract.MODES) {
                    log("FAKE_PHONE rejected hudMode='$hudMode'")
                    return
                }
                GlassesHub.onRemoteEnvelope(
                    BusEnvelope(
                        path = BusPaths.HUD_MODE_CONFIG,
                        payload = HudModeContract.configToJson(hudMode == HudModeContract.MODE_GRID),
                    ),
                )
            }
            val text = when {
                intent.hasExtra(EXTRA_FILE) -> readScript(intent.getStringExtra(EXTRA_FILE).orEmpty()) ?: return
                intent.hasExtra(EXTRA_PATH) -> JSONObject()
                    .put("path", intent.getStringExtra(EXTRA_PATH))
                    .put("payload", JSONObject(intent.getStringExtra(EXTRA_PAYLOAD) ?: "{}"))
                    .toString()
                else -> return
            }
            val script = fake.parse(text)
            // Installed before the steps play so an open cannot race the script that arms it.
            if (script.onOpen != null) GlassesHub.outboundInterceptor = { envelope -> fake.onOutbound(envelope) }
            fake.play(script)
            log("FAKE_PHONE played steps=${script.steps.size} openRules=${script.onOpen?.keys}")
        } catch (error: Exception) {
            logError("FAKE_PHONE failed", error)
        }
    }

    private fun readScript(path: String): String? {
        val file = File(path)
        if (!file.isFile) {
            log("FAKE_PHONE file not found path=$path")
            return null
        }
        if (file.length() > MAX_SCRIPT_BYTES) {
            log("FAKE_PHONE file exceeds $MAX_SCRIPT_BYTES bytes path=$path")
            return null
        }
        return file.readText(Charsets.UTF_8)
    }

    private companion object {
        const val ACTION = "com.anezium.rokidbus.glasses.DEBUG_PHONE"
        const val EXTRA_FILE = "file"
        const val EXTRA_PATH = "path"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_HUD_MODE = "hudMode"
        const val EXTRA_RESET = "reset"
        const val MAX_SCRIPT_BYTES = 4L * 1024 * 1024
        val sequence = AtomicLong(System.currentTimeMillis())

        @Volatile private var instance: FakePhone? = null
        private val timer: Handler by lazy {
            Handler(HandlerThread("RokidNexusFakePhone").apply { start() }.looper)
        }

        /** One instance per process so the open rules outlive the broadcast that set them. */
        @Synchronized
        fun phone(@Suppress("UNUSED_PARAMETER") context: Context): FakePhone =
            instance ?: FakePhone(
                sink = { envelope -> GlassesHub.onRemoteEnvelope(envelope) },
                schedule = { delayMs, task -> timer.postDelayed(task, delayMs) },
                nextSeq = { sequence.updateAndGet { maxOf(it + 1L, System.currentTimeMillis()) } },
                log = ::log,
            ).also { instance = it }
    }
}
