package com.anezium.rokidbus.plugin.relay

/**
 * The band's hold on Relay's one bus connection, which is the plugin service's own client.
 *
 * The hub resolves every delivery it makes to a plugin — open, close, tile lease, tile refresh —
 * with the single live registration under that plugin's id, and finds none once there are two.
 * The service is registered whenever anything binds it: the hub while the inbox is open or the
 * grid tile is leased, and now the band too. The band never opens a client of its own; it binds
 * the service for as long as it needs the bus and talks through whichever instance is alive, so
 * the band starting or ending, and the hub's lease starting or ending, each only add or drop a
 * binding on the one service — there is never a second registration to hand off from.
 *
 * Main thread only, like every service lifecycle callback that drives it.
 */
internal class RelayBusLink<S : Any>(
    private val bind: () -> Boolean,
    private val unbind: () -> Unit,
    private val liveService: () -> S?,
) {
    var held = false
        private set
    private var attached: S? = null

    /** Binds the service if the band does not hold it yet; returns it if it is already up. */
    fun acquire(): S? {
        if (!held) held = bind()
        return current()
    }

    /** The service the band talks through, or null while unheld or before the service is up. */
    fun current(): S? {
        if (!held) return null
        return liveService().also { attached = it }
    }

    fun release() {
        attached = null
        if (!held) return
        held = false
        unbind()
    }

    /** True when the band holds the link and was waiting for this instance to come up. */
    fun onServiceCreated(service: S): Boolean = held && current() === service

    /**
     * True when the band was talking through [service], which is now gone along with every session
     * the band opened on its client. Another instance going away is not the band's concern.
     */
    fun onServiceDestroyed(service: S): Boolean {
        if (!held || attached !== service) return false
        attached = null
        return true
    }
}
