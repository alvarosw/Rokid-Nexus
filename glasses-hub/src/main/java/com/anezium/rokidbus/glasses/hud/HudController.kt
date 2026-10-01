package com.anezium.rokidbus.glasses.hud

import com.anezium.rokidbus.glasses.ReducedMotion
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import com.anezium.rokidbus.glasses.ActivityController
import com.anezium.rokidbus.glasses.ActivityInputTarget
import com.anezium.rokidbus.glasses.BuildConfig
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.glasses.HudModeStore
import com.anezium.rokidbus.glasses.HudTopInset
import com.anezium.rokidbus.glasses.NexusSurface
import com.anezium.rokidbus.glasses.NoticeController
import com.anezium.rokidbus.glasses.NoticeKeyDispatcher
import com.anezium.rokidbus.glasses.RingFocusPublisher
import com.anezium.rokidbus.glasses.RingTapPolicy
import com.anezium.rokidbus.glasses.SurfaceController
import com.anezium.rokidbus.glasses.log
import com.anezium.rokidbus.glasses.logError
import com.anezium.rokidbus.shared.NoticeInteractionIdentity

/**
 * The effects runner of the HUD and the only place that decides what is on screen. It owns the
 * [HudStateMachine] state (through [HudRunner]), feeds it events from every entry point, and turns
 * its effects into work on the [HudHost], the bus, the ring-focus broadcast and the controllers.
 * Views never call controllers and controllers never call views; both talk to this object.
 *
 * Main thread only. Every public entry point that can be reached from another thread hops to the
 * main looper first. One clock: `SystemClock.uptimeMillis()`, for the machine's deadlines, for
 * [HudInput]'s tap windows and for the handler that fires them.
 */
internal object HudController {
    private val main = Handler(Looper.getMainLooper())

    private var service: AccessibilityService? = null
    private var appContext: Context? = null
    private var host: HudHost? = null
    private var entriesById: Map<String, GlassesHub.LauncherEntry> = emptyMap()
    private var launcherShown = false
    private var lastMode: HomeMode? = null
    private var lastNoticeIdentity: NoticeInteractionIdentity? = null
    private var stopObservingLauncher: (() -> Unit)? = null
    private var stopObservingNotice: (() -> Unit)? = null
    private var stopObservingInset: (() -> Unit)? = null

    /** The framework event being routed, when there is one; the notice router wants its press identity. */
    private var currentKeyEvent: KeyEvent? = null

    /** The activity a ring tap sequence was aimed at when its first tap came in (01 item 130). */
    private var activityTapTarget: ActivityInputTarget? = null
    private var lastRingTapAt = Long.MIN_VALUE

    private val inputTick = Runnable { runInputTick() }
    private val input = HudInput(InputContext())

    private val runner = HudRunner(
        machine = HudStateMachine(HudConfig()),
        clock = SystemClock::uptimeMillis,
        timer = Timer(),
        sink = Sink(),
        onError = { logError("HUD effect failed", it) },
    )

    /** The window host of a connection; a test swaps it to run against a window manager that refuses. */
    internal var hostFactory: (AccessibilityService, WindowManager) -> HudHost = { service, manager ->
        HudHost(service, manager)
    }

    val state: HudState get() = runner.state

    fun isServiceConnected(): Boolean = runner.state.serviceConnected

    /** The animator duration scale, `1` before the first service connection. */
    fun animatorDurationScale(): Float = appContext?.let(ReducedMotion::durationScale) ?: 1f

    fun isLauncherShown(): Boolean = isLauncherScreen(runner.state.screen)

    /** Whether the surface is what the machine currently shows, or what a launcher sits over. */
    fun isShowingSurface(surfaceId: String): Boolean = surfaceIdOf(runner.state.screen) == surfaceId

    // ---- service lifecycle -----------------------------------------------------------------

    fun onServiceConnected(service: AccessibilityService) {
        // A second connect with no destroy between would leave the first one's host window and
        // observers alive beside the new ones.
        if (this.service != null) {
            log("Accessibility service connected again without a destroy; releasing the previous connection")
            release()
        }
        this.service = service
        val context = service.applicationContext
        appContext = context
        val manager = service.getSystemService(WindowManager::class.java)
        val nextHost = hostFactory(service, manager)
        host = nextHost
        stopObservingInset = HudTopInset.observe(service) { nextHost.home.setHudTopInsetDp(it) }
        stopObservingLauncher = GlassesHub.observeLauncher { entries ->
            runOnMain {
                entriesById = entries.associateBy { it.id }
                dispatch(
                    HudEvent.LauncherEntriesChanged(
                        entries = entries.map { it.id },
                        appearance = appearanceOf(context, entries),
                    ),
                )
            }
        }
        stopObservingNotice = NoticeController.observe { notice ->
            // A ring tap in flight was aimed at the notice that was up when it began (01 item 129).
            val identity = notice?.interactionIdentity
            if (identity != lastNoticeIdentity) {
                lastNoticeIdentity = identity
                input.cancelPendingRingTaps()
            }
        }
        if (BuildConfig.DEBUG) HudInputSeam.sink = { raw -> runOnMain { onRawKey(raw, null) } }
        dispatch(HudEvent.NoticeOwnsRingChanged(NoticeController.ownsRingInput()))
        dispatch(HudEvent.ServiceConnected)
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service !== service) return
        release()
    }

    /**
     * What the home draws for each entry besides its place in the list: name, icon and the tile's
     * grid cell and size, so a layout change that keeps the reading order still refreshes the home.
     * The system widgets' cells are in it too: a widget is no entry, but moving one is a change.
     */
    private fun appearanceOf(context: Context, entries: List<GlassesHub.LauncherEntry>): Map<String, String> {
        val placements = resolvedPlacements(context, entries)
        val placed = placements.associateBy { it.pluginId }
        val ids = entries.mapTo(HashSet()) { it.id }
        val widgets = placements.filter { it.pluginId !in ids }
            .associate { it.pluginId to "widget|${it.col},${it.row},${it.size.wireValue}" }
        return entries.associate { entry ->
            val tile = placed[entry.id]
            entry.id to "${entry.displayName}|${entry.iconKey.orEmpty()}|${tile?.col},${tile?.row},${tile?.size?.wireValue}"
        } + widgets
    }

    /** Ends the current connection: the machine loses its windows, and every observer and timer goes. */
    private fun release() {
        HudInputSeam.sink = null
        dispatch(HudEvent.ServiceDestroyed)
        main.removeCallbacks(inputTick)
        input.reset()
        stopObservingInset?.invoke()
        stopObservingInset = null
        stopObservingLauncher?.invoke()
        stopObservingLauncher = null
        stopObservingNotice?.invoke()
        stopObservingNotice = null
        lastNoticeIdentity = null
        activityTapTarget = null
        host = null
        service = null
    }

    // ---- entry points ----------------------------------------------------------------------

    /** The wearer or a broadcast asks for the launcher. Returns false when the service is not up. */
    fun openLauncher(trigger: LauncherTrigger): Boolean {
        if (!isServiceConnected()) {
            log("Launcher open ($trigger) ignored: accessibility service not connected")
            return false
        }
        runOnMain { dispatch(HudEvent.Intent(HudIntent.OpenLauncher(trigger))) }
        return true
    }

    /** `OPEN_LAUNCHER` broadcast: toggles. The result string is for the log only. */
    fun toggleLauncherFromBroadcast(): String {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { log("Open launcher broadcast result: ${toggleLauncherFromBroadcast()}") }
            return "queued"
        }
        if (!isServiceConnected()) return "show failed: accessibility service not connected"
        val wasShown = isLauncherShown()
        dispatch(HudEvent.Intent(HudIntent.OpenLauncher(LauncherTrigger.BROADCAST_TOGGLE)))
        return if (wasShown) "hidden" else "shown"
    }

    fun onNoticeOwnsRingChanged(owns: Boolean) {
        runOnMain { dispatch(HudEvent.NoticeOwnsRingChanged(owns)) }
    }

    /** A native app was launched by `/core/native-apps/request`. */
    fun onNativeAppLaunched() {
        runOnMain { dispatch(HudEvent.ExternalStarted(ExternalKind.NATIVE_APP)) }
    }

    /** The camera overlay left the display: the camera is no longer in front (`:camera` edge). */
    fun onCameraOverlayVisibility(active: Boolean) {
        if (!active) runOnMain { dispatch(HudEvent.ExternalEnded(ExternalKind.CAMERA)) }
    }

    // ---- surfaces --------------------------------------------------------------------------

    /**
     * [SurfaceController] presents [surface]. [asShow] is true for a `/surface/show`, which can
     * complete an open; an update of a surface the machine does not know is treated as a show.
     */
    fun onSurfacePresented(surface: NexusSurface, path: DisplayPath, asShow: Boolean) {
        runOnMain {
            val info = surface.hudInfo()
            // A ring tap in flight was aimed at whatever was on screen before this surface.
            if (!isShowingSurface(surface.surfaceId)) input.cancelPendingRingTaps()
            val event = if (asShow || !isShowingSurface(surface.surfaceId)) {
                HudEvent.SurfaceShown(
                    surfaceId = surface.surfaceId,
                    ownerPluginId = surface.ownerPluginId,
                    displayPath = path,
                    handlesBack = info.handlesBack,
                    editable = info.editable,
                )
            } else {
                HudEvent.SurfaceInfoChanged(surface.surfaceId, info.handlesBack, info.editable)
            }
            dispatch(event)
            presentInAppLayer(surface)
        }
    }

    fun onSurfaceHidden(surfaceId: String) {
        runOnMain {
            val app = host?.app
            if (app?.surfaceId == surfaceId) app.clear()
            input.cancelPendingRingTaps()
            dispatch(HudEvent.SurfaceHidden(surfaceId))
        }
    }

    private fun presentInAppLayer(surface: NexusSurface) {
        val current = host ?: return
        if (!current.isAttached) return
        val screen = runner.state.screen
        val beneath = when (screen) {
            is HudScreen.Home -> screen.beneath
            is HudScreen.Opening -> screen.home.beneath
            else -> screen
        }
        if (beneath is HudScreen.App && beneath.surfaceId == surface.surfaceId) current.app.present(surface)
    }

    private fun NexusSurface.hudInfo() = SurfaceInfo(
        surfaceId = surfaceId,
        ownerPluginId = ownerPluginId,
        handlesBack = handlesBack,
        editable = kind == NexusSurface.KIND_CARD && editable != null,
    )

    // ---- keys ------------------------------------------------------------------------------

    /**
     * Every raw key of the accessibility service goes through here. Returns whether the service
     * must consume it. [source] is the framework event when there is one.
     */
    fun onRawKey(raw: RawKeyEvent, source: KeyEvent?): Boolean {
        if (service == null) return false
        currentKeyEvent = source
        try {
            noteRingTap(raw)
            val result = input.onKey(raw)
            route(result.intents)
            armInputTick()
            return result.consumed
        } finally {
            currentKeyEvent = null
        }
    }

    private fun noteRingTap(raw: RawKeyEvent) {
        if (raw.deviceClass != DeviceClass.R08 || raw.keyCode != HudInput.RING_TAP) return
        if (!raw.isDown || raw.repeatCount != 0) return
        // The first tap of a sequence fixes which activity it is for.
        if (raw.eventTime - lastRingTapAt > RingTapPolicy.DEFAULT_WINDOW_MS) {
            activityTapTarget = ActivityController.inputTargetSnapshot()
        }
        lastRingTapAt = raw.eventTime
    }

    private fun route(intents: List<RoutedIntent>) {
        for (routed in intents) {
            when (routed.target) {
                InputTarget.HUD -> dispatch(HudEvent.Intent(routed.intent))
                InputTarget.NOTICE -> routeToNotice(routed.intent)
                InputTarget.ACTIVITY -> {
                    ActivityController.onHudIntent(routed.intent, activityTapTarget)
                    if (routed.intent == HudIntent.Select || routed.intent == HudIntent.Dismiss) {
                        activityTapTarget = null
                    }
                }
            }
        }
    }

    private fun routeToNotice(intent: HudIntent) {
        when (intent) {
            HudIntent.Next -> NoticeController.handleDirection(1)
            HudIntent.Prev -> NoticeController.handleDirection(-1)
            HudIntent.Select -> NoticeController.handleConfirm(KeyEvent.KEYCODE_ENTER)
            HudIntent.Dismiss -> NoticeController.dismissFromBack()
            else -> Unit
        }
    }

    private fun armInputTick() {
        main.removeCallbacks(inputTick)
        val at = input.nextDeadlineMs() ?: return
        main.postAtTime(inputTick, at)
    }

    private fun runInputTick() {
        route(input.onTick(SystemClock.uptimeMillis()))
        armInputTick()
    }

    // ---- machine plumbing ------------------------------------------------------------------

    private fun dispatch(event: HudEvent) {
        syncMode()
        runner.dispatch(event)
    }

    /** The mode applies the next time the launcher is shown, so it is offered before every event. */
    private fun syncMode() {
        val context = appContext ?: return
        val mode = if (HudModeStore.isGridModeEnabled(context)) HomeMode.GRID else HomeMode.LIST
        if (mode == lastMode) return
        lastMode = mode
        runner.dispatch(HudEvent.ModeChanged(mode))
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun label(pluginId: String): String = entriesById[pluginId]?.displayName ?: pluginId

    private fun entriesFor(ids: List<String>): List<GlassesHub.LauncherEntry> =
        ids.mapNotNull { entriesById[it] }

    private fun surfaceIdOf(screen: HudScreen): String? = when (screen) {
        is HudScreen.App -> screen.surfaceId
        is HudScreen.External -> screen.surface?.surfaceId
        is HudScreen.Home -> screen.beneath?.let(::surfaceIdOf)
        is HudScreen.Opening -> screen.home.beneath?.let(::surfaceIdOf)
        HudScreen.Hidden -> null
    }

    private fun isLauncherScreen(screen: HudScreen) = screen is HudScreen.Home || screen is HudScreen.Opening

    // ---- effects ---------------------------------------------------------------------------

    private class Timer : HudTimer {
        private var task: Runnable? = null

        override fun schedule(atUptimeMs: Long, task: Runnable) {
            cancel()
            this.task = task
            main.postAtTime(task, atUptimeMs)
        }

        override fun cancel() {
            task?.let(main::removeCallbacks)
            task = null
        }
    }

    private class Sink : HudEffectSink {
        override fun execute(effect: HudEffect) {
            val host = host
            val context = appContext
            when (effect) {
                HudEffect.AttachHost -> if (host?.attach() != true) {
                    log("HUD host window unavailable; closing the launcher")
                    dispatch(HudEvent.HostAttachFailed)
                }
                HudEffect.DetachHost -> host?.detach()
                is HudEffect.ShowHome -> {
                    if (host == null || context == null) return
                    GlassesHub.start(context)
                    host.home.show(effect.mode, entriesFor(effect.entries), effect.selectedId)
                }
                is HudEffect.SetHomeSelection -> host?.home?.select(effect.selectedId)
                is HudEffect.ScrollHome -> host?.home?.scrollRows(effect.rows)
                is HudEffect.RefreshHomeEntries -> host?.home?.update(entriesFor(effect.entries), effect.selectedId)
                is HudEffect.ShowOpening -> host?.home?.showOpening(effect.pluginId)
                is HudEffect.ShowApp -> showApp(host, effect.surfaceId)
                is HudEffect.ShowActivitySurface ->
                    context?.let { SurfaceController.startSurfaceActivity(it, effect.surfaceId) }
                is HudEffect.ShowStatus -> host?.home?.showStatus(statusText(effect.status))
                is HudEffect.SendLauncherOpen -> sendOpen(effect.pluginId, effect.token)
                is HudEffect.StartCamera -> startCamera(effect.token)
                is HudEffect.CloseApp -> SurfaceController.closeFromHud(effect.surfaceId, effect.reason)
                is HudEffect.ForwardToApp -> SurfaceController.onHudIntent(effect.surfaceId, effect.intent)
                HudEffect.PassToSystem, is HudEffect.PassToExternal -> Unit
                is HudEffect.PublishRingFocus -> context?.let { RingFocusPublisher.publish(it, effect.focused) }
                // Consumed by HudRunner.
                is HudEffect.ScheduleDeadline, HudEffect.CancelDeadline -> Unit
            }
        }

        override fun settled(state: HudState) {
            host?.sync(state.screen)
            host?.home?.setNoticeOwnsRing(state.noticeOwnsRing)
            val shown = isLauncherScreen(state.screen)
            if (shown != launcherShown) {
                launcherShown = shown
                log(if (shown) "Launcher overlay opened" else "Launcher overlay closed")
                ActivityController.onLauncherVisibilityChanged()
            }
        }

        private fun showApp(host: HudHost?, surfaceId: String) {
            val surface = SurfaceController.activeSurface()?.takeIf { it.surfaceId == surfaceId }
            if (host == null || !host.isAttached || surface == null) {
                // The overlay could not be attached; the surface falls back to its activity.
                SurfaceController.onOverlayUnavailable(surfaceId)
                return
            }
            host.app.present(surface)
        }

        private fun sendOpen(pluginId: String, token: Long) {
            val result = GlassesHub.openLauncherEntry(pluginId)
            log("Launcher open result: $result")
            if (!result.startsWith("launcherOpen=true")) {
                dispatch(HudEvent.OpenFailed(token, OpenFailure.SEND_FAILED))
            }
        }

        private fun startCamera(token: Long) {
            val result = GlassesHub.openLauncherEntry(CAMERA_ENTRY_ID)
            log("Launcher open result: $result")
            if (result.startsWith("launcherOpen=true")) {
                dispatch(HudEvent.ExternalStarted(ExternalKind.CAMERA))
            } else {
                dispatch(HudEvent.OpenFailed(token, OpenFailure.SEND_FAILED))
            }
        }

        private fun statusText(status: HudStatus): String = when (status) {
            is HudStatus.OpenFailed -> {
                val why = when (status.reason) {
                    OpenFailure.TIMEOUT -> "no answer"
                    OpenFailure.SEND_FAILED -> "not sent"
                    OpenFailure.REJECTED -> "refused"
                }
                "Could not open ${label(status.pluginId)}: $why"
            }
        }
    }

    // ---- input context ---------------------------------------------------------------------

    private class InputContext : HudInputContext {
        override fun owner(): InputOwner {
            val screen = runner.state.screen
            return when (screen) {
                HudScreen.Hidden -> InputOwner.NONE
                is HudScreen.Home, is HudScreen.Opening -> InputOwner.LAUNCHER
                is HudScreen.App -> surfaceOwner(screen.surface)
                is HudScreen.External ->
                    if (screen.kind == ExternalKind.ACTIVITY_SURFACE) {
                        screen.surface?.let(::surfaceOwner) ?: InputOwner.SURFACE
                    } else {
                        InputOwner.NONE
                    }
            }
        }

        private fun surfaceOwner(info: SurfaceInfo): InputOwner = when {
            SurfaceController.activeSurface()?.takeIf { it.surfaceId == info.surfaceId }?.isReader == true ->
                InputOwner.READER
            info.editable -> InputOwner.EDITABLE_SURFACE
            else -> InputOwner.SURFACE
        }

        override fun activityIdle() = ActivityController.claimsInput()

        override fun activityHasActions() = ActivityController.hasInputActions()

        override fun noticeOwnsRing() = NoticeController.ownsRingInput()

        override fun noticeClaimsRing(keyCode: Int) = NoticeController.claimsRingKey(keyCode)

        override fun noticeHandlesKey(event: RawKeyEvent): Boolean {
            val framework = currentKeyEvent ?: HudKeyEventAdapter.toKeyEvent(event)
            return NoticeKeyDispatcher.handleKeyEvent(framework)
        }
    }
}
