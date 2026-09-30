package com.anezium.rokidbus.glasses.hud

import android.content.Context
import android.view.Choreographer
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import com.anezium.rokidbus.glasses.ReducedMotion

/** Where animation frames come from. Production is the [Choreographer]; tests drive it by hand. */
internal interface FrameClock {
    /** Runs [callback] once, on the next frame, with that frame's time in milliseconds. */
    fun postFrame(callback: (frameTimeMs: Long) -> Unit)
}

internal class ChoreographerFrameClock : FrameClock {
    override fun postFrame(callback: (frameTimeMs: Long) -> Unit) {
        Choreographer.getInstance().postFrameCallback { nanos -> callback(nanos / 1_000_000L) }
    }
}

/**
 * The one motion driver of [HudHost] (docs/ui-rewrite/00-architecture §2.6): scroll, focus and the
 * open/close morph all animate through [MotionValue]s created here, so there is one clock, one
 * easing and one place that reads reduced motion.
 *
 * The rule the whole thing serves: state changes are immediate and views animate toward the current
 * state. A value can be retargeted or snapped at any time and always continues from where it is. A
 * value's callbacks only move views; nothing here knows the state machine, the bus or the
 * controller, so an animation can never emit an event (F-12).
 *
 * Reduced motion is read when an animation starts, not cached: with `animator_duration_scale == 0`
 * every value lands on its target inside the call, with no intermediate frame. Any other scale
 * multiplies the durations, as it does for the platform's own animators.
 */
internal class HudMotionDriver(
    private val clock: FrameClock = ChoreographerFrameClock(),
    private val reducedMotion: () -> Boolean = { false },
    private val durationScale: () -> Float = { 1f },
) {
    private val running = ArrayList<MotionValue>()
    private var framePending = false

    val isIdle: Boolean get() = running.isEmpty()

    fun value(initial: Float, onChange: (Float) -> Unit): MotionValue = MotionValue(initial, onChange)

    private fun start(value: MotionValue) {
        if (value !in running) running += value
        if (!framePending) {
            framePending = true
            clock.postFrame(::onFrame)
        }
    }

    private fun stop(value: MotionValue) {
        running.remove(value)
    }

    private fun onFrame(frameTimeMs: Long) {
        framePending = false
        // A finished value's end hook may start another animation; iterate a copy.
        running.toList().forEach { it.step(frameTimeMs) }
        if (running.isNotEmpty() && !framePending) {
            framePending = true
            clock.postFrame(::onFrame)
        }
    }

    /** One animatable number that always knows where it is. */
    inner class MotionValue internal constructor(initial: Float, private val onChange: (Float) -> Unit) {
        var current: Float = initial
            private set

        /** Where the running animation is heading; [current] when nothing runs. */
        var target: Float = initial
            private set

        val isRunning: Boolean get() = this in running

        private var from = initial
        private var startMs = NOT_STARTED
        private var durationMs = 0L
        private var interpolator: Interpolator = STRUCTURAL
        private var onEnd: (() -> Unit)? = null

        /**
         * Continues from [current] to [target] over [durationMs]. With [proportional], for a value
         * that runs over 0..1, the duration is scaled by the share of that range still to cover, so
         * a retarget keeps its speed instead of restarting a full tween for a short hop; without
         * it the move always takes the whole duration, whatever the distance. [onEnd] only runs
         * when this animation reaches its target: a retarget, a snap or a cancel drops it. It is
         * for view cleanup, never for state.
         */
        fun animateTo(
            target: Float,
            durationMs: Long,
            proportional: Boolean = false,
            onEnd: (() -> Unit)? = null,
        ) {
            stop(this)
            this.onEnd = null
            if (reducedMotion() || durationMs <= 0L || current == target) {
                snapTo(target)
                onEnd?.invoke()
                return
            }
            from = current
            this.target = target
            startMs = NOT_STARTED
            val share = if (proportional) kotlin.math.abs(target - current).coerceAtMost(1f) else 1f
            this.durationMs = (durationMs * share * durationScale()).toLong().coerceAtLeast(1L)
            this.onEnd = onEnd
            start(this)
        }

        /** Lands on [target] now and drops any running animation and its end hook. */
        fun snapTo(target: Float) {
            stop(this)
            onEnd = null
            this.target = target
            current = target
            onChange(target)
        }

        fun cancel() {
            stop(this)
            onEnd = null
            target = current
        }

        internal fun step(frameTimeMs: Long) {
            if (this !in running) return
            if (startMs == NOT_STARTED) startMs = frameTimeMs
            val t = ((frameTimeMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
            if (t >= 1f) {
                val end = onEnd
                onEnd = null
                stop(this)
                current = target
                onChange(target)
                end?.invoke()
            } else {
                current = from + (target - from) * interpolator.getInterpolation(t)
                onChange(current)
            }
        }
    }

    companion object {
        private const val NOT_STARTED = Long.MIN_VALUE

        /** The one easing: fast-out-slow-in (0.4, 0, 0.2, 1), for every duration token. */
        val STRUCTURAL: Interpolator by lazy { PathInterpolator(0.4f, 0f, 0.2f, 1f) }

        /** The driver a host window uses: Choreographer frames, reduced motion read per animation. */
        fun forContext(context: Context) = HudMotionDriver(
            ChoreographerFrameClock(),
            { ReducedMotion.isEnabled(context) },
            { ReducedMotion.durationScale(context) },
        )

        /** A driver that never animates: every value lands on its target inside the call. */
        fun instant() = HudMotionDriver(
            object : FrameClock {
                override fun postFrame(callback: (frameTimeMs: Long) -> Unit) = Unit
            },
            { true },
        )
    }
}
