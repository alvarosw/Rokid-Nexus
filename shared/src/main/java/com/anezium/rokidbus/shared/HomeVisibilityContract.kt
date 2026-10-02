package com.anezium.rokidbus.shared

import org.json.JSONObject

/**
 * What the glasses currently show, as far as the phone's battery-saving decisions need to know:
 * whether the display is on and whether it shows the home. [homeVisible] implies [screenOn].
 */
data class HomeVisibility(
    val screenOn: Boolean,
    val homeVisible: Boolean,
) {
    init {
        require(screenOn || !homeVisible) { "homeVisible requires screenOn" }
    }
}

/**
 * Trusted glasses-hub to phone-hub report on `/core/home/visibility`. Plugins can neither send
 * nor receive it; the phone only ever consumes it. A glasses hub that predates it never sends it,
 * and the phone then behaves as if the home were always visible.
 */
object HomeVisibilityContract {
    const val VERSION = 1
    const val PATH = "/core/home/visibility"

    fun toPayload(value: HomeVisibility): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("screenOn", value.screenOn)
        .put("homeVisible", value.homeVisible && value.screenOn)

    /** Strict parse; a report claiming the home is visible on a dark display is normalized to hidden. */
    fun parse(payload: JSONObject): HomeVisibility? {
        if (!payload.has("version") || payload.opt("version") != VERSION) return null
        val screenOn = payload.opt("screenOn") as? Boolean ?: return null
        val homeVisible = payload.opt("homeVisible") as? Boolean ?: return null
        return HomeVisibility(screenOn, homeVisible && screenOn)
    }
}
