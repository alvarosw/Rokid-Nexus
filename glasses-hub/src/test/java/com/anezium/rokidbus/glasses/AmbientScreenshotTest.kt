package com.anezium.rokidbus.glasses

import android.content.Intent
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anezium.rokidbus.glasses.hud.HomeLayer
import com.anezium.rokidbus.glasses.hud.HomeMode
import com.anezium.rokidbus.glasses.hud.HudMotionDriver
import com.anezium.rokidbus.glasses.hud.assertSingleHue
import com.anezium.rokidbus.glasses.hud.entries
import com.anezium.rokidbus.shared.ActivityAction
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.ActivityTrack
import com.anezium.rokidbus.shared.NoticeAction
import com.anezium.rokidbus.shared.NoticeSurfaceContent
import com.anezium.rokidbus.shared.PhoneBatteryContract
import com.anezium.rokidbus.shared.PinSurfaceContent
import com.anezium.rokidbus.shared.PinSurfaceEmphasis
import com.anezium.rokidbus.shared.PinSurfaceLine
import com.anezium.rokidbus.shared.PinSurfacePosition
import com.anezium.rokidbus.shared.PinSurfaceSize
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real pixels of the ambient layers (notice band, pin, activity island and its extras, status
 * badge, pointer, waveform) on the 480x640 px screen at 240 dpi, each over the new home and over a
 * plugin surface, placed the way their windows place them. Like `SurfaceScreenshotTest` it writes
 * the PNGs (the review artifact) and checks the design system on them: exactly 480x640 and every
 * lit pixel the one green hue.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h427dp-hdpi")
class AmbientScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    init {
        System.setProperty("roborazzi.test.record", "true")
    }

    @Before
    fun settleMotion() {
        HudMotion.enabled = false
    }

    @After
    fun restoreMotion() {
        HudMotion.enabled = true
        NoticeComposeMirror.clear("relay")
    }

    private enum class Backdrop(val tag: String) { HOME("home"), SURFACE("surface"), NONE("black") }

    private fun homeBackdrop(activity: android.app.Activity): View = HomeLayer(
        activity,
        sizeSource = { list -> list.associate { it.id to null } },
        tileSource = { null },
        motion = HudMotionDriver.instant(),
    ).apply {
        val icons = listOf("music", "disc", "map", "bus", "send", "terminal", "lens", "bolt")
        show(
            HomeMode.LIST,
            entries(8).mapIndexed { i, e -> e.copy(iconKey = icons[i % icons.size]) },
            "plugin2",
        )
    }

    private fun surfaceBackdrop(activity: android.app.Activity): View = SurfaceHudView(activity).apply {
        render(
            NexusSurface(
                surfaceId = "shot:card",
                seq = 1,
                kind = NexusSurface.KIND_CARD,
                contentKey = "shot",
                title = "Stop Plaza Mayor",
                subtitle = "Next departures",
                footer = "3 lines",
                rows = listOf(
                    SurfaceRow("Line 27", badge = "2 min", sub = "Hospital", selected = true),
                    SurfaceRow("Line 5", badge = "7 min", sub = "Central station"),
                    SurfaceRow("Line C1", badge = "14 min", sub = "Airport", tone = SurfaceRow.TONE_DIM),
                    SurfaceRow("Line 12", badge = "22 min", sub = "Delayed", tone = SurfaceRow.TONE_ALERT),
                ),
                timedLines = emptyList(),
                anchor = null,
                handlesBack = false,
            ),
        )
    }

    private fun stopMarquees(root: View) {
        if (root is android.widget.TextView) root.isSelected = false
        (root as? ViewGroup)?.let { g -> (0 until g.childCount).forEach { stopMarquees(g.getChildAt(it)) } }
    }

    /** Lays [layers] over [backdrop] on the canvas and writes `build/outputs/roborazzi/ambient-<name>-<tag>.png`. */
    private fun capture(
        name: String,
        vararg backdrops: Backdrop = Backdrop.entries.toTypedArray(),
        layers: (android.app.Activity, FrameLayout) -> Unit,
    ) {
        // Every backdrop is written before the first failure is reported, so a review always has all PNGs.
        val failures = mutableListOf<AssertionError>()
        backdrops.forEach { backdrop ->
            ActivityScenario.launch<HomeScreenshotHostActivity>(
                Intent(context, HomeScreenshotHostActivity::class.java),
            ).use { scenario ->
                lateinit var root: FrameLayout
                scenario.onActivity { activity ->
                    root = FrameLayout(activity)
                    activity.setContentView(root, FrameLayout.LayoutParams(480, 640))
                    when (backdrop) {
                        Backdrop.HOME -> root.addView(homeBackdrop(activity), matchParent())
                        Backdrop.SURFACE -> root.addView(surfaceBackdrop(activity), matchParent())
                        Backdrop.NONE -> root.setBackgroundColor(Color.BLACK)
                    }
                    layers(activity, root)
                    stopMarquees(root)
                    repeat(4) { shadowOf(Looper.getMainLooper()).idle() }
                }
                val path = "build/outputs/roborazzi/ambient-$name-${backdrop.tag}.png"
                root.captureRoboImage(path)
                try {
                    assertSingleHue(File(path))
                } catch (e: AssertionError) {
                    failures += e
                }
            }
        }
        failures.firstOrNull()?.let { throw it }
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    // ---- notice band ----------------------------------------------------------------------

    private fun notice(
        content: NoticeSurfaceContent,
        selected: Int = 0,
        pageIndex: Int = 0,
        pageCount: Int = 1,
        answered: Boolean = false,
        unconfirmed: Boolean = false,
    ) = NexusNoticeSurface(
        surfaceId = "relay:notice",
        seq = 1,
        content = content,
        expiresAtMs = 1_000L,
        hardExpiresAtMs = 2_000L,
        selectedActionIndex = selected,
        pageIndex = pageIndex,
        pageCount = pageCount,
        answered = answered,
        ownerPluginId = "relay",
        deliveryUnconfirmed = unconfirmed,
    )

    private fun band(activity: android.app.Activity, root: FrameLayout, notice: NexusNoticeSurface) {
        val band = NoticeOverlayRenderer.NoticeBandView(activity)
        root.addView(band, NoticeOverlayRenderer.bandLayoutParams(activity, 0))
        band.render(notice)
    }

    private val question = NoticeSurfaceContent(
        title = "Ana",
        body = "Are you free for lunch tomorrow at one?",
        footer = "Relay",
        actions = listOf(
            NoticeAction("yes", "send", "Yes"),
            NoticeAction("later", "timer", "Later"),
            NoticeAction("no", "cancel", "No"),
        ),
    )

    @Test
    fun notice_question() = capture("notice-question") { a, r -> band(a, r, notice(question, selected = 1)) }

    @Test
    fun notice_info() = capture("notice-info") { a, r ->
        band(a, r, notice(NoticeSurfaceContent("Build finished", "All 412 tests passed in 3m 12s.", null)))
    }

    @Test
    fun notice_long() = capture("notice-long") { a, r ->
        band(
            a, r,
            notice(
                NoticeSurfaceContent(
                    "Assistant",
                    "The quarterly review is moved to Thursday afternoon because the conference room is " +
                        "booked by the design team until noon. Please confirm that the new slot works " +
                        "for you and forward the slides to the whole group beforehand so everyone can " +
                        "read them. If Thursday does not work, Friday morning is still open, and the " +
                        "room is free all day. Anything else you want added to the agenda, tell me. " +
                        "The catering order closes on Wednesday evening, so the headcount has to be " +
                        "final by then; last time we were four short and ended up sharing plates. " +
                        "Also note that the projector in that room needs the adapter from the front " +
                        "desk, and the desk closes at five, so collect it before the afternoon starts. " +
                        "Second thing: the travel budget for the offsite is still not approved, and " +
                        "finance wants the final numbers per person before Friday noon, including the " +
                        "train tickets, the hotel nights and the two dinners. Please send them to me " +
                        "as a short list, and I will forward everything in one message.",
                    "Assistant",
                ),
                pageCount = 2,
            ),
        )
    }

    @Test
    fun notice_compose_mirror() = capture("notice-compose") { a, r ->
        NoticeComposeMirror.publish(NoticeComposeMirror.Line("relay", "See you at one", 6, "Type a reply"))
        band(
            a, r,
            notice(NoticeSurfaceContent("Ana", "Are you free for lunch tomorrow at one?", null, interactive = true)),
        )
    }

    @Test
    fun notice_answered_unconfirmed() = capture("notice-unconfirmed") { a, r ->
        band(a, r, notice(question, answered = true, unconfirmed = true))
    }

    // ---- pin ---------------------------------------------------------------------------------

    private fun pin(
        activity: android.app.Activity,
        root: FrameLayout,
        content: PinSurfaceContent,
    ) {
        val view = PinOverlayRenderer.PinPanelView(activity)
        val placement = PinOverlayRenderer.placementFor(content.position, 0, activity)
        root.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = placement.gravity
                setMargins(placement.x, placement.y, placement.x, placement.y)
            },
        )
        view.render(NexusPinSurface("nav:pin", 1, content, null))
    }

    @Test
    fun pin_small() = capture("pin-small") { a, r ->
        pin(
            a, r,
            PinSurfaceContent(
                "Next turn",
                listOf(PinSurfaceLine("300 m left", PinSurfaceEmphasis.BRIGHT), PinSurfaceLine("Rue de Rivoli")),
                PinSurfacePosition.TOP_RIGHT,
                null,
            ),
        )
    }

    @Test
    fun pin_medium() = capture("pin-medium") { a, r ->
        pin(
            a, r,
            PinSurfaceContent(
                "Timer 04:12",
                listOf(
                    PinSurfaceLine("Pasta is boiling", PinSurfaceEmphasis.BRIGHT),
                    PinSurfaceLine("Drain in 3 minutes"),
                    PinSurfaceLine("Then the sauce", PinSurfaceEmphasis.DIM),
                ),
                PinSurfacePosition.BOTTOM_LEFT,
                null,
                PinSurfaceSize.MEDIUM,
            ),
        )
    }

    // ---- activity ----------------------------------------------------------------------------

    private fun activity(
        id: String,
        content: ActivitySurfaceContent,
        corner: PinSurfacePosition = PinSurfacePosition.TOP_LEFT,
        selected: Int = 0,
    ) = NexusActivitySurface(
        surfaceId = "$id:local",
        ownerPluginId = id,
        seq = 1,
        content = content,
        corner = corner,
        startedOrder = 1,
        lastUpdatedOrder = 1,
        lastSignificantOrder = null,
        collapseAtMs = 0,
        maxDurationDeadlineMs = null,
        selectedActionIndex = selected,
        motionToken = 1,
    )

    private fun island(
        activityContext: android.app.Activity,
        root: FrameLayout,
        activity: NexusActivitySurface,
        presentation: ActivityPresentation,
        urgent: Boolean = false,
    ) {
        val island = ActivityOverlayRenderer.ActivityIsland(activityContext)
        root.addView(island, matchParent())
        island.place(activity.corner, 0)
        island.render(ActivityRenderItem(activity, primary = true, presentation = presentation, urgent = urgent))
        if (presentation == ActivityPresentation.FLARE) island.showFlare(urgent) else island.showSteady(presentation)
    }

    private val download = ActivitySurfaceContent(
        glyph = "package",
        primary = "Downloading",
        secondary = "album.zip",
        progress = com.anezium.rokidbus.shared.ActivityProgress.Percent(40),
        eta = "2 min",
        detail = listOf("12 of 30 MB"),
        actions = listOf(ActivityAction("pause", "pause", "Pause"), ActivityAction("stop", "stop", "Stop")),
        maxDurationMs = null,
    )

    private val extras = ActivitySurfaceContent(
        glyph = "bus",
        primary = "3 min",
        secondary = "Rue de Rivoli",
        progress = null,
        eta = "12:40",
        detail = listOf("Then line 5, 4 stops"),
        actions = emptyList(),
        maxDurationMs = null,
        badge = "38",
        measure = "250 m",
        track = ActivityTrack(count = 6, at = 2, target = 4, label = "Châtelet"),
    )

    @Test
    fun activity_panel() = capture("activity-panel") { a, r ->
        island(a, r, activity("media", download, selected = 1), ActivityPresentation.PANEL)
    }

    @Test
    fun activity_chip() = capture("activity-chip") { a, r ->
        island(a, r, activity("media", download), ActivityPresentation.CHIP)
    }

    @Test
    fun activity_flare() = capture("activity-flare") { a, r ->
        island(a, r, activity("media", download), ActivityPresentation.FLARE)
    }

    @Test
    fun activity_flare_urgent() = capture("activity-flare-urgent") { a, r ->
        island(a, r, activity("media", download), ActivityPresentation.FLARE, urgent = true)
    }

    @Test
    fun activity_two() = capture("activity-two") { a, r ->
        island(a, r, activity("media", download, selected = 0), ActivityPresentation.PANEL)
        island(
            a, r,
            activity("nav", extras, corner = PinSurfacePosition.TOP_RIGHT),
            ActivityPresentation.CHIP,
        )
    }

    @Test
    fun activity_extras_panel() = capture("activity-extras-panel") { a, r ->
        island(a, r, activity("nav", extras), ActivityPresentation.PANEL)
    }

    @Test
    fun activity_extras_chip() = capture("activity-extras-chip") { a, r ->
        island(a, r, activity("nav", extras), ActivityPresentation.CHIP)
    }

    @Test
    fun activity_extras_flare() = capture("activity-extras-flare") { a, r ->
        island(a, r, activity("nav", extras), ActivityPresentation.FLARE)
    }

    // ---- badge, pointer, waveform -------------------------------------------------------------

    @Test
    fun status_badge() = capture("badge") { a, r ->
        listOf(PhoneBatteryContract.Reading(85, false), PhoneBatteryContract.Reading(12, true))
            .forEachIndexed { i, reading ->
                val chip = StatusBadgeOverlayRenderer.PhoneChipView(a)
                r.addView(
                    chip,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        StatusBadgeGeometry.px(StatusBadgeGeometry.ROW_HEIGHT_DP, 1.5f),
                    ).apply { setMargins(100 + i * 120, 355, 0, 0) },
                )
                chip.render(reading)
            }
    }

    @Test
    fun pointer() = capture("pointer") { a, r ->
        val view = RemotePointerOverlayRenderer.PointerView(a)
        r.addView(view, matchParent())
        view.render(GlassesPointerPixel(360f, 320f), RemotePointerOverlayRenderer.CURSOR_RADIUS_PX)
    }

    @Test
    fun stack_all() = capture("stack") { a, r ->
        pin(
            a, r,
            PinSurfaceContent(
                "Next turn",
                listOf(PinSurfaceLine("300 m left", PinSurfaceEmphasis.BRIGHT)),
                PinSurfacePosition.BOTTOM_RIGHT,
                null,
            ),
        )
        island(a, r, activity("media", download), ActivityPresentation.CHIP)
        band(a, r, notice(question, selected = 0))
        val view = RemotePointerOverlayRenderer.PointerView(a)
        r.addView(view, matchParent())
        view.render(GlassesPointerPixel(300f, 420f), RemotePointerOverlayRenderer.CURSOR_RADIUS_PX)
    }
}
