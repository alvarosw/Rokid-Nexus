package com.anezium.rokidbus.glasses.hud

object HudKeys {
    const val PROG_BLUE = 186
}

/** A key the machine does not interpret; it is routed to whoever owns input. */
data class RawKey(val keyCode: Int, val isDown: Boolean = true, val repeatCount: Int = 0)

enum class LauncherTrigger {
    /** Three temple contacts; opens only, never toggles, and is ignored over an editable card. */
    TRIPLE_TAP,

    /** `OPEN_LAUNCHER` broadcast (also the ring's triple tap via the R08 bridge); toggles. */
    BROADCAST_TOGGLE,

    /** The app icon (`MainActivity`); opens only. */
    APP_ICON,
}

sealed interface HudIntent {
    data object Next : HudIntent
    data object Prev : HudIntent
    data object Select : HudIntent
    data object Dismiss : HudIntent
    data class OpenLauncher(val trigger: LauncherTrigger) : HudIntent
    data class Raw(val key: RawKey) : HudIntent
}

enum class OpenFailure { SEND_FAILED, REJECTED, TIMEOUT }

sealed interface HudEvent {
    data class Intent(val intent: HudIntent) : HudEvent

    data class LauncherEntriesChanged(val entries: List<String>) : HudEvent

    data class ModeChanged(val mode: HomeMode) : HudEvent

    /** A `/surface/show` (not an update). [ownerPluginId] null falls back to the surface-id prefix rule. */
    data class SurfaceShown(
        val surfaceId: String,
        val ownerPluginId: String?,
        val displayPath: DisplayPath,
        val handlesBack: Boolean = false,
        val editable: Boolean = false,
    ) : HudEvent

    /** Metadata of the active surface changed; never changes what is on screen. */
    data class SurfaceInfoChanged(
        val surfaceId: String,
        val handlesBack: Boolean,
        val editable: Boolean,
    ) : HudEvent

    data class SurfaceHidden(val surfaceId: String) : HudEvent

    /** Camera or a native app came to the front. Surfaces on the ACTIVITY path use [SurfaceShown]. */
    data class ExternalStarted(val kind: ExternalKind) : HudEvent

    data class ExternalEnded(val kind: ExternalKind) : HudEvent

    /** The runner could not send the open, or the phone rejected it. */
    data class OpenFailed(val token: Long, val reason: OpenFailure = OpenFailure.SEND_FAILED) : HudEvent

    data class DeadlineElapsed(val token: Long) : HudEvent

    data class NoticeOwnsRingChanged(val owns: Boolean) : HudEvent

    data object ServiceConnected : HudEvent

    data object ServiceDestroyed : HudEvent
}
