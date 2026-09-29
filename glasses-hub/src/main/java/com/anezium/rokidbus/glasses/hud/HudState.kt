package com.anezium.rokidbus.glasses.hud

/** Launcher entry id of the synthesized camera entry (not a plugin, no `/launcher/open`). */
const val CAMERA_ENTRY_ID = "camera"

enum class HomeMode { LIST, GRID }

/** Where content that is not drawn by the HUD host lives. */
enum class ExternalKind { ACTIVITY_SURFACE, CAMERA, NATIVE_APP }

/** What a dismiss (or the end of the content) returns to. */
enum class Origin { HOME, HIDDEN }

enum class DisplayPath { OVERLAY, ACTIVITY }

data class HudConfig(
    /** The launcher-to-surface handoff bound; the same 10 s the ring handoff used (F-4). */
    val openTimeoutMs: Long = 10_000L,
    /** How long a `handlesBack` plugin has to answer a forwarded BACK before the hub closes locally. */
    val backFailsafeMs: Long = 1_500L,
    /**
     * HARDWARE B1: an unclaimed BACK that reaches the ROM launcher right after Nexus consumed a
     * dismiss puts the display to sleep. When positive, a Dismiss in [HudScreen.Hidden] within this
     * many ms of the last consumed dismiss is swallowed instead of passed. Off until decided.
     */
    val unclaimedBackGuardMs: Long = 0L,
)

/** The surface a screen shows, as far as the machine needs to know. */
data class SurfaceInfo(
    val surfaceId: String,
    val ownerPluginId: String?,
    val handlesBack: Boolean = false,
    val editable: Boolean = false,
)

sealed interface HudScreen {
    /** No Nexus foreground. Keys pass to the system. */
    data object Hidden : HudScreen

    /**
     * Launcher visible. [beneath] is the App/External the launcher was opened over (dismiss returns
     * to it); it never receives input while the launcher is up. [selectedId] is null only when there
     * are no entries.
     */
    data class Home(
        val mode: HomeMode,
        val selectedId: String?,
        val beneath: HudScreen? = null,
    ) : HudScreen

    /**
     * An open was sent; [home] stays on screen and owns input until the plugin's surface arrives,
     * the open fails, or [deadline] passes. [openToken] also names the scheduled deadline.
     */
    data class Opening(
        val pluginId: String,
        val openToken: Long,
        val deadline: Long,
        val home: Home,
    ) : HudScreen

    /** A surface drawn in the host's app layer. */
    data class App(
        val surface: SurfaceInfo,
        val origin: Origin,
        /** Token of the pending BACK failsafe, if a BACK was forwarded to a `handlesBack` plugin. */
        val backToken: Long? = null,
    ) : HudScreen {
        val surfaceId: String get() = surface.surfaceId
    }

    /** Content outside the host window; the host is detached while this is the screen. */
    data class External(
        val kind: ExternalKind,
        val origin: Origin,
        val surface: SurfaceInfo? = null,
        val backToken: Long? = null,
    ) : HudScreen
}

/**
 * An open the wearer dismissed while it was in flight. The plugin may still answer it: until
 * [until] (the open's own deadline) a show from [pluginId] is that answer and is closed unseen.
 */
data class CancelledOpen(val pluginId: String, val until: Long)

/**
 * The whole machine state. [screen] is the one owner of the display and of input; the other fields
 * are the context the rules need and are only ever changed inside `reduce`.
 */
data class HudState(
    val screen: HudScreen = HudScreen.Hidden,
    /** Launcher entry ids in display order, without duplicates. */
    val entries: List<String> = emptyList(),
    /** Applied when the launcher is next shown, never to a launcher already on screen. */
    val configuredMode: HomeMode = HomeMode.LIST,
    /** Selection by plugin id; survives close/open and list updates. */
    val lastSelectedId: String? = null,
    val noticeOwnsRing: Boolean = false,
    val serviceConnected: Boolean = false,
    /** What was last emitted as `PublishRingFocus`, so it is only emitted on change. */
    val ringFocusPublished: Boolean = false,
    /** The scheduled deadline the runner holds, always the token of the current screen or null. */
    val deadlineToken: Long? = null,
    val nextToken: Long = 1L,
    /** A surface that was active when the service died; re-shown on reconnect, the launcher is not. */
    val suspended: HudScreen? = null,
    val lastDismissAt: Long? = null,
    val cancelledOpen: CancelledOpen? = null,
) {
    /**
     * Derived, never stored independently. External CAMERA and NATIVE_APP do not hold focus
     * (HEAD: the ring belongs to the R08 bridge there); an activity-path surface does.
     */
    fun ringFocus(): Boolean {
        if (!serviceConnected) return false
        if (noticeOwnsRing) return true
        return when (val s = screen) {
            HudScreen.Hidden -> false
            is HudScreen.Home, is HudScreen.Opening, is HudScreen.App -> true
            is HudScreen.External -> s.kind == ExternalKind.ACTIVITY_SURFACE
        }
    }
}
