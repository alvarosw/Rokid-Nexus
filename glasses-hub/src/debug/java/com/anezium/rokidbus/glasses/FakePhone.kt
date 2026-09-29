package com.anezium.rokidbus.glasses

import android.util.Base64
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONArray
import org.json.JSONObject

/**
 * One envelope the fake phone sends, [delayMs] after the step before it (or after its trigger).
 * [stampSeq] marks surface envelopes the script left without a `seq`; each delivery gets a fresh
 * one, so a rule can answer the same plugin any number of times.
 */
internal data class FakeStep(val delayMs: Long, val envelope: BusEnvelope, val stampSeq: Boolean = false)

/** What the fake phone does when the glasses send `/launcher/open` for one plugin. */
internal data class OpenRule(val never: Boolean, val replies: List<FakeStep>)

/** [onOpen] is null when the script does not mention open rules, so they are left alone. */
internal data class FakeScript(val steps: List<FakeStep>, val onOpen: Map<String, OpenRule>?)

/**
 * Debug-only stand-in for the phone. Scripts are fed to [sink], which in the app is
 * `GlassesHub.onRemoteEnvelope`, so the hub cannot tell them from real phone traffic. The one
 * thing a phone does that the hub cannot fake is answering `/launcher/open`; [onOutbound]
 * covers that when it is installed as the hub's outbound interceptor.
 *
 * Script JSON is either one envelope `{path, id?, payload?, binaryBase64?, delayMs?}`, an array
 * of envelopes, or `{envelopes: [...], onOpen: {pluginId: {never?, delayMs?, envelopes: [...]}}}`.
 * surface envelopes without a `seq` get a monotonic one, as the phone hub would assign.
 */
internal class FakePhone(
    private val sink: (BusEnvelope) -> Unit,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val nextSeq: () -> Long,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var onOpen: Map<String, OpenRule> = emptyMap()

    fun parse(text: String): FakeScript {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) return FakeScript(parseSteps(JSONArray(trimmed)), null)
        val root = JSONObject(trimmed)
        if (root.has("path")) return FakeScript(listOf(parseStep(root)), null)
        val table = root.optJSONObject("onOpen")
        val rules = table?.let {
            buildMap {
                table.keys().forEach { pluginId ->
                    val rule = table.getJSONObject(pluginId)
                    put(
                        pluginId,
                        OpenRule(
                            never = rule.optBoolean("never", false),
                            replies = parseSteps(
                                rule.optJSONArray("envelopes") ?: JSONArray(),
                                rule.optLong("delayMs", 0L),
                            ),
                        ),
                    )
                }
            }
        }
        return FakeScript(parseSteps(root.optJSONArray("envelopes") ?: JSONArray()), rules)
    }

    /** Sends the script's steps in order; a script that carries open rules replaces the old ones. */
    fun play(script: FakeScript) {
        script.onOpen?.let { onOpen = it }
        run(script.steps)
    }

    fun reset() {
        onOpen = emptyMap()
    }

    /**
     * True when [envelope] was consumed as if the phone had received it. Only `/launcher/open`
     * is consumed; everything else still meets the real (absent) link.
     */
    fun onOutbound(envelope: BusEnvelope): Boolean {
        if (envelope.path != BusPaths.LAUNCHER_OPEN) return false
        val pluginId = envelope.payload.optString("pluginId")
        val rule = onOpen[pluginId]
        when {
            rule == null -> log("FAKE_PHONE open pluginId=$pluginId has no rule; not answering")
            rule.never -> log("FAKE_PHONE open pluginId=$pluginId never answers")
            else -> {
                log("FAKE_PHONE open pluginId=$pluginId answering with ${rule.replies.size} envelope(s)")
                run(rule.replies)
            }
        }
        return true
    }

    private fun run(steps: List<FakeStep>) {
        var elapsed = 0L
        steps.forEach { step ->
            elapsed += step.delayMs
            if (elapsed == 0L) {
                deliver(step)
            } else {
                schedule(elapsed) { deliver(step) }
            }
        }
    }

    private fun deliver(step: FakeStep) {
        val template = step.envelope
        sink(
            if (step.stampSeq) {
                template.copy(payload = JSONObject(template.payload.toString()).put("seq", nextSeq()))
            } else {
                template
            },
        )
    }

    private fun parseSteps(array: JSONArray, leadDelayMs: Long = 0L): List<FakeStep> =
        List(array.length()) { index ->
            val step = parseStep(array.getJSONObject(index))
            if (index == 0) step.copy(delayMs = step.delayMs + leadDelayMs) else step
        }

    private fun parseStep(json: JSONObject): FakeStep {
        val path = json.getString("path")
        val payload = json.optJSONObject("payload") ?: JSONObject()
        val stampSeq = path.startsWith("/surface/") && !payload.has("seq")
        val binary = json.optString("binaryBase64").takeIf { it.isNotEmpty() }
            ?.let { Base64.decode(it, Base64.DEFAULT) }
        val envelope = if (json.has("id")) {
            BusEnvelope(path = path, id = json.getString("id"), payload = payload, binary = binary)
        } else {
            BusEnvelope(path = path, payload = payload, binary = binary)
        }
        return FakeStep(json.optLong("delayMs", 0L), envelope, stampSeq)
    }
}
