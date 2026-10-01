package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.tile.TileGridLayout
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import org.json.JSONObject
import java.util.UUID

interface TileLeaseRuntime {
    fun bind(principal: PhonePluginPrincipal): Boolean
    fun isRegistered(principal: PhonePluginPrincipal): Boolean
    fun deliver(principal: PhonePluginPrincipal, path: String, id: String, payload: JSONObject): Boolean
    fun unbind(principal: PhonePluginPrincipal)
}

object TileLeasePolicy {
    /**
     * The plugins whose grid tile is live: grid mode on, the glasses linked, the tile placed, and
     * `widget_tile` granted. Placement is the glasses' own resolution of [storedLayout] over the
     * launcher list, so the phone leases exactly the tiles the wearer can see; with no stored
     * layout that is every launchable plugin.
     */
    fun leasedPlugins(
        gridMode: Boolean,
        linkUp: Boolean,
        launchable: List<PhonePluginPrincipal>,
        storedLayout: List<TileLayoutEntry>,
        hasWidgetTile: (PhonePluginPrincipal) -> Boolean,
    ): List<PhonePluginPrincipal> {
        if (!gridMode || !linkUp) return emptyList()
        val placed = TileGridLayout.resolve(launchable.map { it.descriptor.id to null }, storedLayout)
            .mapTo(mutableSetOf()) { it.pluginId }
        return launchable.filter { it.descriptor.id in placed && hasWidgetTile(it) }
    }
}

/**
 * Holds each leased plugin bound for as long as its tile is live, the way an open holds the
 * foreground plugin, and tells it when the lease begins and ends. Refreshes are hub-owned: one
 * when the lease is delivered or the glasses home becomes visible, and a timer while the lease
 * lasts, never closer together than [REFRESH_INTERVAL_MS] for one plugin.
 */
class TileLeaseController(
    private val runtime: TileLeaseRuntime,
    private val scheduler: ExternalPluginScheduler,
    private val nowMs: () -> Long,
    private val logger: (String) -> Unit = {},
    private val journal: PluginBusJournal? = null,
) {
    private class Lease(val principal: PhonePluginPrincipal) {
        var delivered = false

        // A plugin that never registered stays leased but unbound until the lease ends, so an
        // unrelated input change does not rebind it again and again.
        var failed = false
    }

    private val leases = linkedMapOf<PluginGrantKey, Lease>()
    private val lastRefreshAtMs = mutableMapOf<String, Long>()
    private var closed = false

    @Synchronized
    fun update(leased: List<PhonePluginPrincipal>) {
        if (closed) return
        val wanted = leased.associateBy { it.grantKey() }
        leases.keys.filter { it !in wanted }.forEach { key -> end(leases.getValue(key)) }
        wanted.forEach { (key, principal) -> if (key !in leases) grant(principal) }
    }

    @Synchronized
    fun onRegistered(principal: PhonePluginPrincipal) {
        val lease = leases[principal.grantKey()]?.takeIf { !it.failed } ?: return
        scheduler.cancel(registrationKey(principal))
        if (!deliverActive(lease, active = true)) {
            fail(lease, "DELIVERY_FAILED")
            return
        }
        lease.delivered = true
        refreshIfDue(lease)
    }

    /** The bound plugin's process went away; Android restarts a bound service, so wait for it. */
    @Synchronized
    fun onBinderDied(key: PluginGrantKey) {
        val lease = leases[key]?.takeIf { it.delivered } ?: return
        lease.delivered = false
        scheduler.cancel(refreshKey(lease.principal))
        awaitRegistration(lease)
    }

    @Synchronized
    fun onHomeVisible() {
        leases.values.toList().forEach(::refreshIfDue)
    }

    @Synchronized
    fun activePluginIds(): Set<String> =
        leases.values.filter { it.delivered }.mapTo(mutableSetOf()) { it.principal.descriptor.id }

    /** Ends every lease for good: a recomputation still queued behind the hub's stop is ignored. */
    @Synchronized
    fun close() {
        closed = true
        leases.values.toList().forEach(::end)
    }

    private fun grant(principal: PhonePluginPrincipal) {
        val lease = Lease(principal)
        leases[principal.grantKey()] = lease
        if (!runtime.bind(principal)) {
            fail(lease, "BIND_FAILED")
            return
        }
        record(principal, BusPaths.PLUGIN_TILE_ACTIVE, PluginBusJournal.Verdict.OK, "LEASE_GRANTED")
        logger("tile lease granted plugin=${principal.descriptor.id}")
        awaitRegistration(lease)
        if (runtime.isRegistered(principal)) onRegistered(principal)
    }

    private fun end(lease: Lease) {
        val principal = lease.principal
        leases.remove(principal.grantKey())
        scheduler.cancel(registrationKey(principal))
        scheduler.cancel(refreshKey(principal))
        if (lease.delivered) deliverActive(lease, active = false)
        lease.delivered = false
        runtime.unbind(principal)
        record(principal, BusPaths.PLUGIN_TILE_ACTIVE, PluginBusJournal.Verdict.OK, "LEASE_ENDED")
        logger("tile lease ended plugin=${principal.descriptor.id}")
    }

    private fun fail(lease: Lease, reason: String) {
        val principal = lease.principal
        lease.failed = true
        lease.delivered = false
        scheduler.cancel(registrationKey(principal))
        scheduler.cancel(refreshKey(principal))
        runtime.unbind(principal)
        record(principal, BusPaths.PLUGIN_TILE_ACTIVE, PluginBusJournal.Verdict.REJECTED, reason)
        logger("tile lease failed plugin=${principal.descriptor.id} reason=$reason")
    }

    private fun awaitRegistration(lease: Lease) {
        scheduler.schedule(registrationKey(lease.principal), REGISTRATION_TIMEOUT_MS) {
            synchronized(this) {
                if (leases[lease.principal.grantKey()] === lease && !lease.delivered && !lease.failed) {
                    fail(lease, "REGISTRATION_TIMEOUT")
                }
            }
        }
    }

    private fun refreshIfDue(lease: Lease) {
        if (!lease.delivered) return
        val principal = lease.principal
        val now = nowMs()
        val last = lastRefreshAtMs[principal.descriptor.id]
        if (last != null && now - last < REFRESH_INTERVAL_MS) {
            scheduleRefresh(lease, last + REFRESH_INTERVAL_MS - now)
            return
        }
        if (!deliver(principal, BusPaths.PLUGIN_TILE_REFRESH, "refresh")) {
            fail(lease, "DELIVERY_FAILED")
            return
        }
        lastRefreshAtMs[principal.descriptor.id] = now
        record(principal, BusPaths.PLUGIN_TILE_REFRESH, PluginBusJournal.Verdict.OK, "REFRESH")
        scheduleRefresh(lease, REFRESH_INTERVAL_MS)
    }

    private fun scheduleRefresh(lease: Lease, delayMs: Long) {
        scheduler.schedule(refreshKey(lease.principal), delayMs) {
            synchronized(this) {
                if (leases[lease.principal.grantKey()] === lease) refreshIfDue(lease)
            }
        }
    }

    private fun deliverActive(lease: Lease, active: Boolean): Boolean =
        deliver(
            lease.principal,
            BusPaths.PLUGIN_TILE_ACTIVE,
            if (active) "active" else "inactive",
            JSONObject().put("active", active),
        )

    private fun deliver(
        principal: PhonePluginPrincipal,
        path: String,
        type: String,
        extra: JSONObject = JSONObject(),
    ): Boolean {
        val id = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("version", 1)
            .put("type", type)
            .put("id", id)
            .put("pluginId", principal.descriptor.id)
        extra.keys().forEach { key -> payload.put(key, extra.get(key)) }
        return runtime.deliver(principal, path, id, payload)
    }

    private fun record(
        principal: PhonePluginPrincipal,
        path: String,
        verdict: PluginBusJournal.Verdict,
        reason: String,
    ) {
        val target = journal ?: return
        if (!target.enabled.get()) return
        try {
            target.record(
                pluginId = principal.descriptor.id,
                category = PluginBusJournal.Category.LIFECYCLE,
                direction = PluginBusJournal.Direction.HUB_TO_PLUGIN,
                path = path,
                verdict = verdict,
                reason = reason,
            )
        } catch (_: Throwable) {
            // Diagnostics must never affect plugin lifecycle.
        }
    }

    private fun registrationKey(principal: PhonePluginPrincipal): String =
        "tile-registration:${principal.packageName}:${principal.descriptor.id}"

    private fun refreshKey(principal: PhonePluginPrincipal): String =
        "tile-refresh:${principal.packageName}:${principal.descriptor.id}"

    companion object {
        const val REGISTRATION_TIMEOUT_MS = 5_000L
        const val REFRESH_INTERVAL_MS = 15 * 60_000L
    }
}
