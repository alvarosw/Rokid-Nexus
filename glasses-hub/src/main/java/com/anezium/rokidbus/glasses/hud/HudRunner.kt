package com.anezium.rokidbus.glasses.hud

/** Schedules the single deadline the machine asks for on the uptime clock. */
interface HudTimer {
    /** Replaces any pending task. */
    fun schedule(atUptimeMs: Long, task: Runnable)
    fun cancel()
}

/** Where the effects that touch Android end up. Deadlines and [HudEffect.SwallowBack] never reach it. */
interface HudEffectSink {
    fun execute(effect: HudEffect)

    /** Called after all effects of one event ran, with the state they led to. */
    fun settled(state: HudState)
}

/**
 * Feeds events to the [HudStateMachine], one at a time, and runs what comes out. Events raised while
 * effects run (a failed send, a surface hiding because the machine closed it) are queued and handled
 * after the current event, so the machine never sees two events interleaved and the effects of one
 * transition are never split by another.
 *
 * Single-threaded: call from the main thread, the same one the timer fires on.
 */
class HudRunner(
    private val machine: HudStateMachine,
    private val clock: () -> Long,
    private val timer: HudTimer,
    private val sink: HudEffectSink,
    private val onError: (Throwable) -> Unit = { throw it },
) {
    var state: HudState = HudState()
        private set

    private val queue = ArrayDeque<HudEvent>()
    private var draining = false

    /**
     * Returns true when the event that was passed in (not a queued follow-up) ended in
     * [HudEffect.SwallowBack], meaning the caller must consume the key.
     */
    fun dispatch(event: HudEvent): Boolean {
        queue.addLast(event)
        if (draining) return false
        draining = true
        var swallowed = false
        var first = true
        try {
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                val swallow = handle(next)
                if (first) swallowed = swallow
                first = false
            }
        } finally {
            draining = false
        }
        return swallowed
    }

    private fun handle(event: HudEvent): Boolean {
        val transition = machine.reduce(state, event, clock())
        state = transition.state
        var swallow = false
        for (effect in transition.effects) {
            when (effect) {
                HudEffect.SwallowBack -> swallow = true
                is HudEffect.ScheduleDeadline -> timer.schedule(effect.at) {
                    dispatch(HudEvent.DeadlineElapsed(effect.token))
                }
                HudEffect.CancelDeadline -> timer.cancel()
                else -> guarded { sink.execute(effect) }
            }
        }
        guarded { sink.settled(state) }
        return swallow
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            onError(t)
        }
    }
}
