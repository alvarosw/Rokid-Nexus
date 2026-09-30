package com.anezium.rokidbus.glasses.hud

/** Schedules the single deadline the machine asks for on the uptime clock. */
interface HudTimer {
    /** Replaces any pending task. */
    fun schedule(atUptimeMs: Long, task: Runnable)
    fun cancel()
}

/** Where the effects that touch Android end up. Deadlines never reach it. */
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
    /** Written on the main thread only; volatile because other components read it from their own. */
    @Volatile
    var state: HudState = HudState()
        private set

    private val queue = ArrayDeque<HudEvent>()
    private var draining = false

    fun dispatch(event: HudEvent) {
        queue.addLast(event)
        if (draining) return
        draining = true
        try {
            while (queue.isNotEmpty()) handle(queue.removeFirst())
        } finally {
            draining = false
        }
    }

    private fun handle(event: HudEvent) {
        val transition = machine.reduce(state, event, clock())
        state = transition.state
        for (effect in transition.effects) {
            when (effect) {
                is HudEffect.ScheduleDeadline -> timer.schedule(effect.at) {
                    dispatch(HudEvent.DeadlineElapsed(effect.token))
                }
                HudEffect.CancelDeadline -> timer.cancel()
                else -> guarded { sink.execute(effect) }
            }
        }
        guarded { sink.settled(state) }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            onError(t)
        }
    }
}
