package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.HomeVisibility

/**
 * What the glasses last reported on `/core/home/visibility`. A value is null until the glasses
 * report: an older glasses hub never does, and unknown behaves exactly as visible did before the
 * report existed. The report is dropped when the link goes down.
 *
 * Also owns the screen-off grace: after [SCREEN_OFF_LEASE_GRACE_MS] of the display reported off,
 * [screenOffLong] turns true and [onScreenOffGraceElapsed] runs once so the leases can end. It
 * clears with the next report of the display on, or when the link drops.
 *
 * Thread-safe: reports arrive on the remote-route thread, the grace timer on the main thread, and
 * readers anywhere; state changes under the lock, reads go through volatile fields, and the
 * callback runs outside the lock.
 */
internal class GlassesHomeVisibility(
    private val scheduler: ExternalPluginScheduler,
    private val onScreenOffGraceElapsed: () -> Unit,
) {
    data class Snapshot(val screenOn: Boolean?, val homeVisible: Boolean?)

    data class Transition(val before: Snapshot, val after: Snapshot) {
        val homeBecameVisible: Boolean get() = after.homeVisible == true && before.homeVisible != true
        val screenBecameOn: Boolean get() = after.screenOn == true && before.screenOn != true
    }

    @Volatile
    var snapshot = Snapshot(screenOn = null, homeVisible = null)
        private set

    /** The display has been off for the whole grace period; leases end while this holds. */
    @Volatile
    var screenOffLong = false
        private set

    /** The glasses explicitly report the home as not visible; unknown is not hidden. */
    val hidden: Boolean get() = snapshot.homeVisible == false

    /** The glasses explicitly report the display as off; unknown is not off. */
    val screenOff: Boolean get() = snapshot.screenOn == false

    @Synchronized
    fun onReport(report: HomeVisibility): Transition {
        val before = snapshot
        val after = Snapshot(report.screenOn, report.homeVisible)
        snapshot = after
        if (!report.screenOn) {
            if (before.screenOn != false) scheduler.schedule(GRACE_KEY, SCREEN_OFF_LEASE_GRACE_MS) { onGraceTimer() }
        } else {
            scheduler.cancel(GRACE_KEY)
            screenOffLong = false
        }
        return Transition(before, after)
    }

    /** The link dropped: whatever was reported is stale. */
    @Synchronized
    fun onLinkDown() {
        snapshot = Snapshot(screenOn = null, homeVisible = null)
        scheduler.cancel(GRACE_KEY)
        screenOffLong = false
    }

    private fun onGraceTimer() {
        val fired = synchronized(this) {
            if (snapshot.screenOn != false || screenOffLong) return
            screenOffLong = true
            true
        }
        if (fired) onScreenOffGraceElapsed()
    }

    companion object {
        const val SCREEN_OFF_LEASE_GRACE_MS = 10 * 60_000L
        private const val GRACE_KEY = "glasses-screen-off-lease"
    }
}

/**
 * The plugins whose latest tile has not reached the glasses, because the home was hidden or the
 * send failed. Only the plugin ids are kept: the latest snapshot lives in [TileSnapshotCache] and
 * is what a flush sends. Pruned with the same lifecycle as that cache.
 */
internal class PendingTilePublishes {
    private val pending = linkedSetOf<String>()

    @Synchronized
    fun mark(pluginId: String) {
        pending += pluginId
    }

    @Synchronized
    fun clear(pluginId: String) {
        pending -= pluginId
    }

    @Synchronized
    fun contains(pluginId: String): Boolean = pluginId in pending

    /** Takes every pending plugin id; whatever fails to go out again marks itself anew. */
    @Synchronized
    fun drain(): List<String> = pending.toList().also { pending.clear() }

    @Synchronized
    fun retainOnly(pluginIds: Set<String>) {
        pending.retainAll(pluginIds)
    }

    @Synchronized
    fun remove(pluginId: String) {
        pending -= pluginId
    }
}
