package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.WeatherContract
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject

/**
 * The last weather the phone sent on `/phone/weather`, for the weather widget.
 *
 * Sequence-guarded like [PhoneBatteryController]: a link-up resend can arrive behind a fresher
 * reading. Unlike the battery it is persisted, in its own store next to `TileCache`, so the widget
 * has something to draw straight after the glasses hub restarts and before the phone is back.
 *
 * Its age is kept on the elapsed clock: the age the phone stamped plus the time since receipt.
 * That clock restarts with the device, so a reading from a previous boot has no age the widget can
 * trust ([Cached.ageMs] is null) and draws as stale.
 */
internal object PhoneWeatherController {
    private const val PREFS = "phone_weather"
    private const val KEY_READING = "reading"
    private const val KEY_RECEIVED_AT = "receivedAtElapsedRealtime"
    private const val KEY_AGE_AT_RECEIPT = "ageAtReceiptMs"
    private const val KEY_BOOT = "bootCount"

    data class Cached(
        val reading: WeatherContract.Reading,
        val receivedAtElapsed: Long,
        val ageAtReceiptMs: Long,
        /** `Settings.Global.BOOT_COUNT` at receipt; -1 where the device does not keep one. */
        val bootCount: Int,
    ) {
        /** How old the reading is at [nowElapsed] in boot [currentBoot], or null when that cannot be told. */
        fun ageMs(nowElapsed: Long, currentBoot: Int): Long? {
            if (bootCount != currentBoot || receivedAtElapsed > nowElapsed) return null
            return ageAtReceiptMs + (nowElapsed - receivedAtElapsed)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var latestSeq = Long.MIN_VALUE
    private var memory: Cached? = null
    private var loaded = false

    /** The last reading, from memory or the store; null when none ever arrived. */
    fun cached(context: Context?): Cached? {
        if (!loaded && context != null) {
            loaded = true
            memory = memory ?: load(context)
        }
        return memory
    }

    /** The cached reading's age now, on this device's clocks. */
    fun ageMs(context: Context, cached: Cached): Long? =
        cached.ageMs(SystemClock.elapsedRealtime(), bootCount(context))

    /** [listener] runs at once and after every accepted reading, on the main thread, until the returned stop. */
    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        listener()
        return { listeners.remove(listener) }
    }

    fun handleEnvelope(context: Context?, envelope: BusEnvelope): Boolean {
        if (envelope.path != BusPaths.PHONE_WEATHER) return false
        val receivedAt = SystemClock.elapsedRealtime()
        runOnMain { apply(context, envelope, receivedAt) }
        return true
    }

    private fun apply(context: Context?, envelope: BusEnvelope, receivedAt: Long) {
        when (val validation = WeatherContract.validate(envelope.payload)) {
            is WeatherContract.ValidationResult.Invalid -> log("phone weather rejected code=${validation.reason}")
            is WeatherContract.ValidationResult.Valid -> {
                if (validation.seq <= latestSeq) {
                    log("phone weather dropped stale seq=${validation.seq}")
                    return
                }
                latestSeq = validation.seq
                val cached = Cached(validation.reading, receivedAt, validation.ageMs, context?.let(::bootCount) ?: -1)
                memory = cached
                loaded = true
                context?.let { store(it, cached) }
                listeners.forEach { listener -> runCatching { listener() } }
            }
        }
    }

    private fun store(context: Context, cached: Cached) {
        val entry = JSONObject()
            .put(KEY_READING, WeatherContract.toJson(cached.reading, cached.ageAtReceiptMs, seq = 0L))
            .put(KEY_RECEIVED_AT, cached.receivedAtElapsed)
            .put(KEY_AGE_AT_RECEIPT, cached.ageAtReceiptMs)
            .put(KEY_BOOT, cached.bootCount)
        prefs(context).edit().putString(KEY_READING, entry.toString()).apply()
    }

    private fun load(context: Context): Cached? {
        val raw = prefs(context).getString(KEY_READING, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val reading = json.optJSONObject(KEY_READING)?.let(WeatherContract::readingFrom) ?: return null
        if (!json.has(KEY_RECEIVED_AT) || !json.has(KEY_AGE_AT_RECEIPT)) return null
        return Cached(reading, json.optLong(KEY_RECEIVED_AT), json.optLong(KEY_AGE_AT_RECEIPT), json.optInt(KEY_BOOT, -1))
    }

    internal fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    /** Forgets everything, memory and store; for tests. */
    internal fun resetForTest(context: Context) {
        latestSeq = Long.MIN_VALUE
        memory = null
        loaded = false
        prefs(context).edit().clear().commit()
        listeners.clear()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
