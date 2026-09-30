package com.anezium.rokidbus.glasses.hud

/** A frame source the test steps by hand, so an animation can be read at any fraction of its duration. */
internal class ManualFrameClock : FrameClock {
    var nowMs = 10_000L
        private set
    private val pending = ArrayList<(Long) -> Unit>()

    val hasPendingFrame: Boolean get() = pending.isNotEmpty()

    override fun postFrame(callback: (frameTimeMs: Long) -> Unit) {
        pending += callback
    }

    /** Moves time forward by [ms] and delivers one frame at the new time. */
    fun advance(ms: Long) {
        nowMs += ms
        val frame = pending.toList()
        pending.clear()
        frame.forEach { it(nowMs) }
    }

    /** Plays [ms] of animation in 16 ms frames, as a display would. */
    fun play(ms: Long, stepMs: Long = 16) {
        var left = ms
        while (left > 0) {
            val step = minOf(stepMs, left)
            advance(step)
            left -= step
        }
    }
}

internal fun manualMotion(clock: ManualFrameClock, reduced: () -> Boolean = { false }) = HudMotionDriver(clock, reduced)

/**
 * The host window driven by the real state machine through the real runner, with the effects a
 * `HudController` would run reduced to the ones that touch the home layer. `settled` syncs the host
 * exactly as the controller does.
 */
internal class HostHarness(
    private val context: android.content.Context,
    val clock: ManualFrameClock,
    val motion: HudMotionDriver,
    private val manualLayout: Boolean = true,
    private val useWindow: Boolean = true,
) {
    private class Timer : HudTimer {
        override fun schedule(atUptimeMs: Long, task: Runnable) = Unit
        override fun cancel() = Unit
    }

    val effects = ArrayList<HudEffect>()
    var entriesList: List<com.anezium.rokidbus.glasses.GlassesHub.LauncherEntry> = emptyList()
        private set
    lateinit var host: HudHost
        private set
    lateinit var runner: HudRunner
        private set
    var live: (String) -> HomeTile? = { null }
    var sizes: (List<com.anezium.rokidbus.glasses.GlassesHub.LauncherEntry>) -> List<com.anezium.rokidbus.shared.tile.TilePlacement> = placementsOf()

    private val home: HomeLayer get() = host.home

    fun start(mode: HomeMode, count: Int = 8) {
        entriesList = entries(count)
        val layer = HomeLayer(
            context,
            iconLoader = flatIcons,
            placementSource = { sizes(it) },
            tileSource = { live(it) },
            motion = motion,
        )
        host = HudHost(context, context.getSystemService(android.view.WindowManager::class.java), motion = motion, home = layer)
        runner = HudRunner(
            machine = HudStateMachine(HudConfig()),
            clock = { 0L },
            timer = Timer(),
            sink = object : HudEffectSink {
                override fun execute(effect: HudEffect) {
                    effects += effect
                    when (effect) {
                        HudEffect.AttachHost -> if (useWindow) host.attach()
                        HudEffect.DetachHost -> host.detach()
                        is HudEffect.ShowHome -> home.show(effect.mode, byId(effect.entries), effect.selectedId)
                        is HudEffect.SetHomeSelection -> home.select(effect.selectedId)
                        is HudEffect.ShowOpening -> home.showOpening(effect.pluginId)
                        is HudEffect.ShowStatus -> home.showStatus("failed")
                        else -> Unit
                    }
                }

                override fun settled(state: HudState) {
                    host.sync(state.screen)
                    layOut()
                }
            },
            onError = { throw it },
        )
        runner.dispatch(HudEvent.ModeChanged(mode))
        runner.dispatch(HudEvent.ServiceConnected)
        runner.dispatch(HudEvent.LauncherEntriesChanged(entriesList.map { it.id }))
        intent(HudIntent.OpenLauncher(LauncherTrigger.APP_ICON))
        // The window manager attaches the root on the next looper turn.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        layOut()
    }

    private fun byId(ids: List<String>) = ids.mapNotNull { id -> entriesList.firstOrNull { it.id == id } }

    fun layOut() {
        if (!manualLayout) return
        val root = host.contentViewForTest
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(480, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 480, 640)
    }

    fun intent(intent: HudIntent) {
        runner.dispatch(HudEvent.Intent(intent))
        layOut()
    }

    fun surfaceFor(id: String) {
        runner.dispatch(HudEvent.SurfaceShown("$id:main", id, DisplayPath.OVERLAY))
        layOut()
    }
}
