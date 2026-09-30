package com.anezium.rokidbus.glasses.hud

import com.anezium.rokidbus.glasses.DpadPairDedupe
import com.anezium.rokidbus.glasses.RingTapPolicy
import com.anezium.rokidbus.glasses.TripleTapDetector

/** Which physical source a key came from; classified once, by device name (HARDWARE R1). */
enum class DeviceClass { R08, TOUCHPAD, KEYBOARD_DPAD, OTHER }

/** A key event with everything [HudInput] needs and nothing from the Android framework. */
data class RawKeyEvent(
    val keyCode: Int,
    val action: Int,
    val repeatCount: Int = 0,
    /** `KeyEvent.getEventTime()`: the uptime clock, the single clock of [HudInput] (see there). */
    val eventTime: Long,
    val deviceClass: DeviceClass,
    /** `KeyEvent.getDownTime()`: with [deviceId] and the keycode, the identity of one press. */
    val downTime: Long = eventTime,
    val deviceId: Int = 0,
) {
    val isDown get() = action == ACTION_DOWN
    val isUp get() = action == ACTION_UP

    companion object {
        const val ACTION_DOWN = TripleTapDetector.ACTION_DOWN
        const val ACTION_UP = TripleTapDetector.ACTION_UP
    }
}

/** The topmost Nexus owner of key input below the notice band. */
enum class InputOwner {
    /** No Nexus window owns keys (`Hidden`, or foreign content in front). */
    NONE,

    /** `Home` or `Opening`: the launcher owns every key (01 §3.3, decision 119). */
    LAUNCHER,

    /** A non-reader, non-editable surface. */
    SURFACE,

    /** A reader surface: directions and media next/previous scroll, they are never forwarded. */
    READER,

    /** An active card with an editable field: only BACK is ours (T5). */
    EDITABLE_SURFACE,
}

/** Who acts on an intent. Everything but [HUD] is an ambient owner outside the state machine. */
enum class InputTarget { HUD, NOTICE, ACTIVITY }

data class RoutedIntent(val intent: HudIntent, val target: InputTarget = InputTarget.HUD)

/**
 * [consumed] is the whole answer to "does this event reach the system": true means the
 * accessibility service must return true. [intents] are independent of it (a delayed tap that
 * resolves on a later event is reported with that event).
 */
data class HudInputResult(
    val consumed: Boolean,
    val intents: List<RoutedIntent> = emptyList(),
)

/**
 * The state [HudInput] reads at the moment of each event. Everything here is owned elsewhere; the
 * notice hooks are where the notice-first precedence sits (01 §3.2 / §3.3): `NoticeController`
 * keeps deciding what it claims, [HudInput] only asks first and consumes accordingly.
 */
interface HudInputContext {
    fun owner(): InputOwner

    /** The idle activity layer presents and would claim input (`ActivityController.claimsInput`). */
    fun activityIdle(): Boolean

    /** The primary activity has actions, so directions are claimed (ring and generic). */
    fun activityHasActions(): Boolean

    /** `NoticeController.ownsRingInput()`: swallows every unclaimed ring key. */
    fun noticeOwnsRing(): Boolean

    /** `NoticeController.claimsRingKey`. A claimed key is reported as an intent targeted at the notice. */
    fun noticeClaimsRing(keyCode: Int): Boolean

    /**
     * Generic pipeline notice hook (`NoticeKeyDispatcher.handleKeyEvent`): asked first for every
     * DOWN and repeat, and for UP so it can settle its own press bookkeeping. Returning true means
     * the notice acted on it; the notice acts itself, no intent is produced.
     */
    fun noticeHandlesKey(event: RawKeyEvent): Boolean
}

/**
 * The single normalizer from raw keys to [HudIntent]s (docs/ui-rewrite/00-architecture.md §2.2).
 *
 * Owns, once: DOWN/UP pairing and orphan-UP consumption (R3), ring tap / double-tap resolution
 * (R5), the touchpad triple tap and its unclassified-contact flush (T1/T2), the shared swipe
 * de-duplication (T3) and the pass-through of PROG_BLUE (T6).
 *
 * Clock (R10): every duration is measured on `KeyEvent.eventTime`, the uptime clock the input
 * dispatcher stamps events with, and the deadline timer must be driven with
 * `SystemClock.uptimeMillis()` (never `elapsedRealtime`, which keeps counting through deep sleep
 * and would expire a tap the wearer never left open). Using the event's own stamp also keeps the
 * outcome independent of how late the main thread got to it, and makes every rule replayable in a
 * JVM test with plain numbers. The service schedules [onTick] at [nextDeadlineMs].
 *
 * Not thread-safe: call from the accessibility service's main thread, like `onKeyEvent`.
 */
class HudInput(private val context: HudInputContext) {
    private val tripleTap = TripleTapDetector()
    private val swipeDedupe = DpadPairDedupe()
    private val readerRingDedupe = DpadPairDedupe()
    private val ringTaps = RingTapPolicy()

    private var ringTapPending = false
    private var ringTapAt = 0L
    private var ringTapTarget = InputTarget.HUD

    /** Time of the latest unclassified 83 contact still inside the triple-tap streak. */
    private var contactAt: Long? = null

    /**
     * Pipeline-qualified keycodes whose DOWN was consumed, with the event time of each UP therefore
     * owed (R3). A queue, not a flag: a swipe's duplicated pair can interleave as DOWN DOWN UP UP, and
     * both UPs belong to consumed DOWNs. Repeats never add to it (one physical press, one UP) but
     * keep the newest entry alive. An entry older than [OWED_UP_TTL_MS] is dropped: its UP went
     * missing, and it must not swallow the UP of a later press whose DOWN passed through.
     */
    private val consumedDowns = HashMap<Int, ArrayDeque<Long>>()

    fun onKey(event: RawKeyEvent): HudInputResult {
        if (event.keyCode == HudKeys.PROG_BLUE) return PASS
        return if (event.deviceClass == DeviceClass.R08) ring(event) else generic(event)
    }

    /**
     * Runs the deferred work whose time has come: a ring tap sequence that stayed silent for the
     * window, and the 1-2 unclassified touchpad contacts of a streak that never became a triple.
     */
    fun onTick(nowMs: Long): List<RoutedIntent> {
        val out = ArrayList<RoutedIntent>(2)
        resolveRingTaps(nowMs, out)
        flushContacts(nowMs, out)
        return out
    }

    /** When [onTick] next has something to do, on the uptime clock; null when idle. */
    fun nextDeadlineMs(): Long? {
        val tap = if (ringTapPending) ringTapAt + RING_TAP_DEADLINE_MS else null
        val contact = contactAt?.let { it + CONTACT_DEADLINE_MS }
        return if (tap != null && contact != null) minOf(tap, contact) else tap ?: contact
    }

    /**
     * The owner a pending ring tap was captured for went away or changed identity (01 item 129):
     * the tap must not answer whatever is on screen now.
     */
    fun cancelPendingRingTaps() {
        ringTaps.reset()
        ringTapPending = false
    }

    fun reset() {
        cancelPendingRingTaps()
        consumedDowns.clear()
        contactAt = null
    }

    // ---- ring (R08) ----------------------------------------------------------------------------

    private fun ring(e: RawKeyEvent): HudInputResult {
        val out = ArrayList<RoutedIntent>(2)
        // Whoever consumed a DOWN consumes its UP, even after the owner is gone (R3): a stray ENTER
        // UP that reaches the ROM launcher starts phone playback.
        if (e.isUp && payUp(e)) return HudInputResult(true)

        // A tap that outlived its window is settled before anything newer counts, so two taps 360 ms
        // apart are two singles and not a double (R5) even if the deadline timer has not fired yet.
        resolveRingTaps(e.eventTime, out)

        val owner = context.owner()
        val hudOwned = owner != InputOwner.NONE
        val noticeOwns = context.noticeOwnsRing()
        val noticeClaims = context.noticeClaimsRing(e.keyCode)
        val activityClaims = context.activityIdle() && when (e.keyCode) {
            RING_TAP -> true
            RING_FORWARD, RING_BACKWARD -> context.activityHasActions()
            else -> false
        }
        if (!hudOwned && !noticeOwns && !activityClaims) return HudInputResult(false, out)

        if (!e.isDown) return HudInputResult(true, out)
        if (e.repeatCount == 0) {
            // First match wins, top of the z-order first; the band is asked before the launcher
            // because it is drawn over it (R7).
            val target = when {
                noticeClaims -> InputTarget.NOTICE
                noticeOwns -> null
                hudOwned -> InputTarget.HUD
                else -> InputTarget.ACTIVITY
            }
            if (target != null) ringKey(e, owner, target, out)
        }
        owe(e)
        return HudInputResult(true, out)
    }

    private fun ringKey(e: RawKeyEvent, owner: InputOwner, target: InputTarget, out: MutableList<RoutedIntent>) {
        when (e.keyCode) {
            RING_FORWARD -> ringDirection(e, owner, target, forward = true, out)
            RING_BACKWARD -> ringDirection(e, owner, target, forward = false, out)
            RING_TAP -> {
                // The target is fixed by the first tap of a sequence, so a single tap can never answer
                // an owner other than the one that was shown when it was made (R9).
                if (ringTapPending && ringTapTarget != target) ringTaps.reset()
                ringTapTarget = target
                ringTaps.onTap(e.eventTime)
                ringTapPending = true
                ringTapAt = e.eventTime
            }
        }
    }

    private fun ringDirection(
        e: RawKeyEvent,
        owner: InputOwner,
        target: InputTarget,
        forward: Boolean,
        out: MutableList<RoutedIntent>,
    ) {
        if (target == InputTarget.HUD && owner == InputOwner.READER) {
            // A reader scrolls a whole page per step; the swipe de-dupe keeps a burst to one (item 127).
            val mapped = if (forward) DpadPairDedupe.KEYCODE_DPAD_RIGHT else DpadPairDedupe.KEYCODE_DPAD_LEFT
            if (readerRingDedupe.onKey(mapped, e.action, e.repeatCount, e.eventTime) == null) return
        }
        out += RoutedIntent(if (forward) HudIntent.Next else HudIntent.Prev, target)
    }

    private fun resolveRingTaps(nowMs: Long, out: MutableList<RoutedIntent>) {
        if (!ringTapPending) return
        val resolution = ringTaps.resolveExpired(nowMs) ?: return
        ringTapPending = false
        when (resolution) {
            RingTapPolicy.Resolution.SINGLE -> out += RoutedIntent(HudIntent.Select, ringTapTarget)
            RingTapPolicy.Resolution.DOUBLE -> out += RoutedIntent(HudIntent.Dismiss, ringTapTarget)
            RingTapPolicy.Resolution.IGNORE -> Unit
        }
    }

    // ---- generic (touchpad, keyboards, injected keys) -----------------------------------------

    private fun generic(e: RawKeyEvent): HudInputResult {
        val out = ArrayList<RoutedIntent>(2)
        val editable = context.owner() == InputOwner.EDITABLE_SURFACE

        if (e.isUp) {
            val noticeConsumed = context.noticeHandlesKey(e)
            val paired = payUp(e)
            if (noticeConsumed || paired) return HudInputResult(true)
        }

        // T5: a typing hand near the touchpad must never open the launcher.
        val decision = if (editable) {
            TripleTapDetector.Decision.PASS
        } else {
            tripleTap.onKey(e.keyCode, e.action, e.repeatCount, e.eventTime)
        }
        // Swipes start with an 83 contact too; any other DOWN ends the streak (T2).
        if (e.isDown && e.keyCode != KEY_NOTIFICATION) contactAt = null

        val handled = when (decision) {
            TripleTapDetector.Decision.TRIGGER -> {
                contactAt = null
                out += RoutedIntent(HudIntent.OpenLauncher(LauncherTrigger.TRIPLE_TAP))
                true
            }
            TripleTapDetector.Decision.CONSUME -> true
            TripleTapDetector.Decision.PASS -> {
                if (!editable && e.keyCode == KEY_NOTIFICATION && e.isDown && e.repeatCount == 0) {
                    contactAt = e.eventTime
                }
                route(e, out)
            }
        }
        if (e.isDown) {
            if (handled) owe(e) else consumedDowns.remove(pairKey(e))
        }
        return HudInputResult(handled, out)
    }

    /** Notice, then launcher, surface, activity, then pass (item 105). */
    private fun route(e: RawKeyEvent, out: MutableList<RoutedIntent>): Boolean {
        if (!e.isUp && context.noticeHandlesKey(e)) return true
        return when (context.owner()) {
            InputOwner.LAUNCHER -> launcherKey(e, out)
            InputOwner.SURFACE -> surfaceKey(e, reader = false, out)
            InputOwner.READER -> surfaceKey(e, reader = true, out)
            InputOwner.EDITABLE_SURFACE -> {
                val back = e.keyCode == KEY_BACK && e.isDown && e.repeatCount == 0
                if (back) out += RoutedIntent(HudIntent.Dismiss)
                back
            }
            InputOwner.NONE -> unownedKey(e, out)
        }
    }

    private fun launcherKey(e: RawKeyEvent, out: MutableList<RoutedIntent>): Boolean {
        if (e.isDown && e.repeatCount == 0) {
            when (e.keyCode) {
                in DPAD_DIRECTIONS -> swipe(e)?.let { out += RoutedIntent(it) }
                KEY_ENTER, KEY_DPAD_CENTER -> out += RoutedIntent(HudIntent.Select)
                KEY_BACK -> out += RoutedIntent(HudIntent.Dismiss)
            }
        }
        // Everything else is a consumed no-op, so nothing drives the ROM behind the launcher.
        return true
    }

    private fun surfaceKey(e: RawKeyEvent, reader: Boolean, out: MutableList<RoutedIntent>): Boolean {
        val key = e.keyCode
        val forwarded = if (reader) READER_FORWARDED else SURFACE_FORWARDED
        if (e.isUp) {
            // An UP only gets here when its DOWN was not consumed (see R3); forward it so the plugin
            // still sees the pair.
            if (reader && (key in DPAD_DIRECTIONS || key in MEDIA_SCROLL)) return true
            if (key !in forwarded) return false
            out += RoutedIntent(HudIntent.Raw(RawKey(key, isDown = false)))
            return true
        }
        return when {
            key == KEY_BACK -> {
                if (e.repeatCount == 0) out += RoutedIntent(HudIntent.Dismiss)
                true
            }
            key in DPAD_DIRECTIONS -> {
                val direction = swipe(e)
                if (direction != null) {
                    out += RoutedIntent(if (reader) direction else HudIntent.Raw(RawKey(key, true, 0)))
                }
                true
            }
            reader && key in MEDIA_SCROLL -> {
                if (e.repeatCount == 0) {
                    out += RoutedIntent(if (key == RING_FORWARD) HudIntent.Next else HudIntent.Prev)
                }
                true
            }
            key in forwarded -> {
                out += RoutedIntent(HudIntent.Raw(RawKey(key, true, e.repeatCount)))
                true
            }
            else -> false
        }
    }

    /** No Nexus owner: keys go to the system, except what the idle activity layer takes. */
    private fun unownedKey(e: RawKeyEvent, out: MutableList<RoutedIntent>): Boolean {
        if (!context.activityIdle()) return false
        val key = e.keyCode
        return when {
            key == KEY_NOTIFICATION -> true
            key in DPAD_DIRECTIONS -> {
                if (!context.activityHasActions()) return false
                if (e.isDown && e.repeatCount == 0) {
                    swipe(e)?.let { out += RoutedIntent(it, InputTarget.ACTIVITY) }
                }
                true
            }
            key == KEY_ENTER || key == KEY_DPAD_CENTER -> {
                if (e.isDown && e.repeatCount == 0) out += RoutedIntent(HudIntent.Select, InputTarget.ACTIVITY)
                true
            }
            else -> false
        }
    }

    private fun swipe(e: RawKeyEvent): HudIntent? =
        when (swipeDedupe.onKey(e.keyCode, e.action, e.repeatCount, e.eventTime)) {
            DpadPairDedupe.Direction.FORWARD -> HudIntent.Next
            DpadPairDedupe.Direction.BACKWARD -> HudIntent.Prev
            null -> null
        }

    /**
     * Contacts the firmware never classified because the streak did not reach three. They are
     * replayed as raw 83 to whoever can use an unclassified tap and never to a notice (T1: a band is
     * answered once and must not be answered by a touch that might be the start of a swipe). Under
     * the launcher they are dropped: the launcher has exclusive input (decision 119).
     */
    private fun flushContacts(nowMs: Long, out: MutableList<RoutedIntent>) {
        val at = contactAt ?: return
        if (nowMs - at <= TripleTapDetector.DEFAULT_WINDOW_MS) return
        contactAt = null
        val count = tripleTap.consumeExpiredTapCount(nowMs)
        val target = when (context.owner()) {
            InputOwner.SURFACE, InputOwner.READER, InputOwner.EDITABLE_SURFACE -> InputTarget.HUD
            InputOwner.NONE -> if (context.activityIdle()) InputTarget.ACTIVITY else return
            InputOwner.LAUNCHER -> return
        }
        repeat(count) { out += RoutedIntent(HudIntent.Raw(RawKey(KEY_NOTIFICATION, isDown = true)), target) }
    }

    private fun owe(e: RawKeyEvent) {
        val owed = consumedDowns.getOrPut(pairKey(e)) { ArrayDeque() }
        dropStale(owed, e.eventTime)
        if (e.repeatCount == 0) {
            if (owed.size >= MAX_OWED_UPS) owed.removeFirst()
            owed.addLast(e.eventTime)
        } else {
            if (owed.isNotEmpty()) owed.removeLast()
            owed.addLast(e.eventTime)
        }
    }

    private fun payUp(e: RawKeyEvent): Boolean {
        val key = pairKey(e)
        val owed = consumedDowns[key] ?: return false
        dropStale(owed, e.eventTime)
        if (owed.isEmpty()) {
            consumedDowns.remove(key)
            return false
        }
        owed.removeFirst()
        if (owed.isEmpty()) consumedDowns.remove(key)
        return true
    }

    private fun dropStale(owed: ArrayDeque<Long>, nowMs: Long) {
        while (owed.isNotEmpty() && nowMs - owed.first() > OWED_UP_TTL_MS) owed.removeFirst()
    }

    private fun pairKey(e: RawKeyEvent): Int =
        if (e.deviceClass == DeviceClass.R08) e.keyCode or RING_PIPELINE_BIT else e.keyCode

    companion object {
        const val RING_TAP = RingSurfaceKeys.TAP
        const val RING_FORWARD = RingSurfaceKeys.FORWARD
        const val RING_BACKWARD = RingSurfaceKeys.BACKWARD

        const val KEY_BACK = TripleTapDetector.KEYCODE_BACK
        const val KEY_ENTER = TripleTapDetector.KEYCODE_ENTER
        const val KEY_NOTIFICATION = TripleTapDetector.KEYCODE_NOTIFICATION
        const val KEY_DPAD_CENTER = 23
        const val KEY_SPACE = 62

        /** One past the tap window, as the timers have always been armed (`RingTapPolicy` + 1). */
        const val RING_TAP_DEADLINE_MS = RingTapPolicy.DEFAULT_WINDOW_MS + 1L
        const val CONTACT_DEADLINE_MS = TripleTapDetector.DEFAULT_WINDOW_MS + 1L

        private const val MAX_OWED_UPS = 2

        /** Longer than any press the pad or ring produces; repeats of a held key refresh the entry. */
        private const val OWED_UP_TTL_MS = 5_000L
        private const val RING_PIPELINE_BIT = 1 shl 16
        private val PASS = HudInputResult(false)

        private val DPAD_DIRECTIONS = setOf(
            DpadPairDedupe.KEYCODE_DPAD_UP,
            DpadPairDedupe.KEYCODE_DPAD_DOWN,
            DpadPairDedupe.KEYCODE_DPAD_LEFT,
            DpadPairDedupe.KEYCODE_DPAD_RIGHT,
        )
        private val MEDIA_SCROLL = setOf(RING_FORWARD, RING_BACKWARD)

        /** `SurfaceController.READER_FORWARDED_KEYS`: a reader leaves SPACE and the media keys to the system. */
        private val READER_FORWARDED = setOf(KEY_BACK, KEY_ENTER, KEY_DPAD_CENTER)

        /** `SurfaceController.FORWARDED_KEYS`. */
        private val SURFACE_FORWARDED = DPAD_DIRECTIONS + setOf(
            KEY_BACK, KEY_ENTER, KEY_DPAD_CENTER, KEY_SPACE, RING_TAP, RING_FORWARD, RING_BACKWARD,
        )
    }
}

/** The ring's keycodes (HARDWARE R2), kept once for the rewrite. */
object RingSurfaceKeys {
    const val TAP = 85
    const val FORWARD = 87
    const val BACKWARD = 88
}

/**
 * Debug-only entry into the live [HudInput]. The service that owns the input wires [sink] when it
 * connects (U3) and clears it on destroy; the DUMP-protected debug receiver is the only caller, so
 * a release build never invokes it.
 */
object HudInputSeam {
    @Volatile
    var sink: ((RawKeyEvent) -> Unit)? = null
}
