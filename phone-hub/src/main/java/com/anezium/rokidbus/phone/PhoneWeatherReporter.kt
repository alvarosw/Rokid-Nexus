package com.anezium.rokidbus.phone

import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.WeatherContract
import java.util.UUID
import java.util.concurrent.Executor

/** One pending timer at a time; scheduling again replaces it. */
internal interface WeatherTimer {
    fun schedule(delayMs: Long, task: Runnable)

    fun cancel()
}

internal class HandlerWeatherTimer(private val handler: Handler = Handler(Looper.getMainLooper())) : WeatherTimer {
    private var pending: Runnable? = null

    override fun schedule(delayMs: Long, task: Runnable) {
        cancel()
        pending = task
        handler.postDelayed(task, delayMs)
    }

    override fun cancel() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }
}

/**
 * Keeps the glasses' weather widget fed, and does nothing at all while it is not wanted.
 *
 * It works only while all hold: the stored tile layout places `sys:weather`, the glasses link is
 * up, and the glasses have not reported the home hidden (unknown counts as visible). Then it fetches when the last reading is [INTERVAL_MS] old (at once if there is none),
 * and schedules one timer for the next fetch; a failed fetch is retried after [RETRY_MS]. When
 * a condition drops the timer is cancelled, and nothing is fetched or sent until the home is
 * visible again, when the hub's `home_visible` call resends the cached reading and fetches if due.
 * The hub calls [onConditionsChanged] on link, layout and home-visibility changes, [resend] when the glasses announce (a restarted glasses hub may have lost
 * nothing, but costs one small message to be sure) and [refreshNow] when the settings change.
 *
 * The last reading is kept by [store] with its fetch time, so a hub restart neither refetches
 * early nor leaves the glasses without data; its age travels with every send.
 */
internal class PhoneWeatherReporter(
    private val clock: () -> Long,
    private val timer: WeatherTimer,
    private val worker: Executor,
    private val isPlaced: () -> Boolean,
    private val isLinked: () -> Boolean,
    private val isHomeVisible: () -> Boolean = { true },
    private val fetch: () -> WeatherContract.Reading?,
    private val store: WeatherSettingsStore,
    private val send: (BusEnvelope) -> String?,
    private val log: (String) -> Unit,
) {
    private val lock = Any()
    private var fetching = false
    private var lastAttemptAt: Long? = null
    private var stopped = false
    private var seq = 0L

    fun onConditionsChanged(reason: String) = evaluate(reason, force = false, resendCached = true)

    /** The glasses announced themselves: send what is cached, and fetch if it is due. */
    fun resend(reason: String) = evaluate(reason, force = false, resendCached = true)

    /** A setting changed what the reading is for: fetch now if the widget is in use. */
    fun refreshNow(reason: String) = evaluate(reason, force = true, resendCached = false)

    fun stop() {
        synchronized(lock) {
            stopped = true
            timer.cancel()
        }
    }

    private fun evaluate(reason: String, force: Boolean, resendCached: Boolean) {
        synchronized(lock) {
            if (stopped) return
            if (!isWanted()) {
                timer.cancel()
                return
            }
            if (fetching) return
            val now = clock()
            val last = store.lastReading()
            val retryAt = lastAttemptAt?.plus(RETRY_MS)
            val dueAt = when {
                force || last == null -> retryAt?.takeUnless { force } ?: now
                else -> maxOf(last.fetchedAtMs + INTERVAL_MS, retryAt ?: Long.MIN_VALUE)
            }
            if (dueAt <= now) {
                startFetch(reason)
                return
            }
            if (resendCached && last != null) transmit(last, now, reason)
            timer.schedule(dueAt - now) { evaluate("timer", force = false, resendCached = false) }
        }
    }

    private fun isWanted(): Boolean = isPlaced() && isLinked() && isHomeVisible()

    private fun startFetch(reason: String) {
        fetching = true
        timer.cancel()
        worker.execute {
            val reading = runCatching { fetch() }.getOrNull()
            synchronized(lock) {
                fetching = false
                val now = clock()
                lastAttemptAt = now
                if (stopped) return@execute
                if (reading != null) {
                    val last = WeatherSettingsStore.LastReading(reading, now)
                    store.setLastReading(last)
                    if (isWanted()) transmit(last, now, reason)
                }
            }
            evaluate("after_fetch", force = false, resendCached = false)
        }
    }

    private fun transmit(last: WeatherSettingsStore.LastReading, now: Long, reason: String) {
        val age = (now - last.fetchedAtMs).coerceAtLeast(0L)
        if (age > WeatherContract.MAX_AGE_MS) return
        val error = send(
            BusEnvelope(
                path = BusPaths.PHONE_WEATHER,
                id = UUID.randomUUID().toString(),
                payload = WeatherContract.toJson(last.reading, age, nextSeq(now)),
            ),
        )
        log("phone weather sent ageMin=${age / 60_000L} reason=$reason error=${error ?: "none"}")
    }

    /** Wall-clock seeded, as [PhoneBatteryReporter] explains: the glasses' guard outlives this hub. */
    private fun nextSeq(now: Long): Long {
        seq = maxOf(seq + 1, now)
        return seq
    }

    companion object {
        const val INTERVAL_MS = 30 * 60 * 1000L
        const val RETRY_MS = 5 * 60 * 1000L
    }
}
