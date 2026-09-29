package com.anezium.rokidbus.glasses.hud

enum class CloseReason {
    /** Forward BACK to the plugin, then hide locally. */
    WEARER_DISMISSED,

    /** A `handlesBack` plugin did not answer in time. */
    BACK_FAILSAFE,

    /** Something else took the display (a native app). */
    SUPERSEDED,
}

sealed interface HudStatus {
    data class OpenFailed(val pluginId: String, val reason: OpenFailure) : HudStatus
}

/**
 * Effects are data for the runner. An input intent that produces neither [PassToSystem] nor
 * [PassToExternal] is consumed. [AttachHost]/[DetachHost] and [PublishRingFocus] are derived from
 * the state change and always emitted, in that order, around the transition's own effects.
 */
sealed interface HudEffect {
    data object AttachHost : HudEffect
    data object DetachHost : HudEffect

    data class ShowHome(val mode: HomeMode, val selectedId: String?, val entries: List<String>) : HudEffect
    data class SetHomeSelection(val selectedId: String?) : HudEffect
    data class RefreshHomeEntries(val entries: List<String>, val selectedId: String?) : HudEffect
    data class ShowOpening(val pluginId: String) : HudEffect
    data class ShowApp(val surfaceId: String) : HudEffect
    data class ShowActivitySurface(val surfaceId: String) : HudEffect
    data class ShowStatus(val status: HudStatus) : HudEffect

    data class SendLauncherOpen(val pluginId: String, val token: Long) : HudEffect
    data class StartCamera(val token: Long) : HudEffect
    data class CloseApp(val surfaceId: String, val reason: CloseReason) : HudEffect
    data class ForwardToApp(val surfaceId: String, val intent: HudIntent) : HudEffect

    /** Not ours: let the key through to the ROM. Emitted only in Hidden (and for PROG_BLUE). */
    data object PassToSystem : HudEffect

    /** Not ours: let the key through to the foreign app in front. */
    data class PassToExternal(val kind: ExternalKind) : HudEffect

    /** Consume a BACK that would otherwise reach the ROM (HARDWARE B1 guard). */
    data object SwallowBack : HudEffect

    data class PublishRingFocus(val focused: Boolean) : HudEffect

    /** Replaces any pending deadline; the runner answers with `DeadlineElapsed(token)`. */
    data class ScheduleDeadline(val token: Long, val at: Long) : HudEffect
    data object CancelDeadline : HudEffect
}

data class Transition(val state: HudState, val effects: List<HudEffect>)
