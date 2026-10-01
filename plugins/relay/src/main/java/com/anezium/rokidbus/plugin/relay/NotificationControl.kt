package com.anezium.rokidbus.plugin.relay

import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.client.plugin.NexusPluginCallbacks

internal object NotificationControl {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: RelayNotificationListener? = null

    @Volatile
    private var liveInstance: RelayNotificationListener? = null

    fun instanceCreated(service: RelayNotificationListener) {
        liveInstance = service
    }

    fun instanceDestroyed(service: RelayNotificationListener) {
        if (liveInstance === service) liveInstance = null
    }

    fun attach(service: RelayNotificationListener) {
        liveInstance = service
        listener = service
    }

    fun detach(service: RelayNotificationListener) {
        if (listener === service) listener = null
    }

    fun isListenerConnected(): Boolean = listener != null

    fun hasLiveListenerInstance(): Boolean = liveInstance != null

    fun requestListenerUnbind(): Boolean {
        val current = liveInstance ?: return false
        current.requestUnbind()
        return true
    }

    /**
     * Whether the wearer is in the inbox, which stands the band down.
     *
     * Relay used to have two things talking to the hub — the band runtime, woken
     * by a notification, and this plugin's own service — each with its own
     * `NexusPluginClient` under the same plugin id. Several hub paths assume
     * one registration per plugin: lifecycle delivery (open, close, the tile
     * lease and its refreshes) resolves with `singleOrNull` and finds nothing,
     * and speech replies follow whichever binder was chosen. Now the band binds
     * the service and talks through its client (see [RelayBusLink]), so there
     * is only ever one registration, whoever holds the service.
     *
     * The band still stands down while the inbox is open: the wearer is already
     * looking at their messages, a band over the top of the list would be
     * announcing something they can see, and the two would otherwise share the
     * client's one speech session.
     */
    @Volatile
    var inboxOpen: Boolean = false
        private set

    @Volatile
    private var inbox: RelayPluginService? = null

    fun inboxOpened(service: RelayPluginService) {
        inbox = service
        inboxOpen = true
        main.post { listener?.suspendBand() }
    }

    fun inboxClosed(service: RelayPluginService) {
        if (inbox === service) inbox = null
        inboxOpen = false
    }

    /** The plugin service instance that is alive, bound by the hub, the band, or both. */
    @Volatile
    var pluginService: RelayPluginService? = null
        private set

    fun serviceCreated(service: RelayPluginService) {
        pluginService = service
        liveInstance?.onPluginServiceCreated(service)
    }

    fun serviceDestroyed(service: RelayPluginService) {
        if (pluginService === service) pluginService = null
        liveInstance?.onPluginServiceDestroyed(service)
    }

    /** The band's callbacks, while the band is talking through [service]'s client. */
    fun bandCallbacks(service: RelayPluginService): NexusPluginCallbacks? =
        liveInstance?.bandCallbacks(service)

    /**
     * A message arrived while the wearer is in the inbox, so its visible surface redraws.
     *
     * This matters more than it looks: the band stands down while the inbox
     * holds the bus, so if the inbox did not refresh, a message arriving during
     * that time would be announced by nothing and appear nowhere — visible only
     * after closing and reopening. The capture is already in the repository by
     * the time this runs; the visible inbox surface only has to look again.
     */
    fun notifyCaptured(notificationId: String) {
        main.post { inbox?.onCaptureChanged(notificationId) }
    }

    fun refreshFromSettings() {
        main.post { listener?.refreshFromSettings() }
    }

    fun cancelAfterReply(notificationKey: String) {
        if (notificationKey.isBlank()) return
        CANCEL_DELAYS_MS.forEach { delayMs ->
            main.postDelayed({ listener?.cancelNotification(notificationKey) }, delayMs)
        }
    }

    private val CANCEL_DELAYS_MS = longArrayOf(250L, 1_000L, 2_500L)
}
