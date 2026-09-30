package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.hud.AmbientLayer
import com.anezium.rokidbus.glasses.hud.AmbientStack
import com.anezium.rokidbus.glasses.hud.AmbientWindow
import com.anezium.rokidbus.glasses.hud.HudGeometry
import com.anezium.rokidbus.glasses.hud.HudLoaderView
import com.anezium.rokidbus.shared.ActivityProgress
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.PinSurfaceLine
import com.anezium.rokidbus.shared.PinSurfacePosition
import com.anezium.rokidbus.shared.PinSurfaceSize

/**
 * The activity tier's one fixed full-screen window.
 *
 * Each activity is one [HudIslandView]: a single outline that springs between
 * the chip, the expanded panel, and the flare, with the content of each form
 * revealed inside it. The WindowManager layout is never animated or updated.
 */
internal object ActivityOverlayRenderer {
    private data class FlareSession(
        val surfaceId: String,
        val startedOrder: Long,
    )

    private val main = Handler(Looper.getMainLooper())
    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var unsubscribe: (() -> Unit)? = null
    private var insetUnsubscribe: (() -> Unit)? = null
    private val nodes = linkedMapOf<String, ActivityIsland>()
    private val processedMotionTokens = mutableMapOf<String, Long>()
    private var flareGeneration = 0L
    private var flareCollapse: Runnable? = null
    private var activeFlare: FlareSession? = null
    private var latestState = ActivityRenderState()
    private var hudTopInsetDp = 0

    fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        windowManager = service.getSystemService(WindowManager::class.java)
        insetUnsubscribe?.invoke()
        insetUnsubscribe = HudTopInset.observe(service, ::applyHudTopInset)
        unsubscribe?.invoke()
        unsubscribe = ActivityController.observe(::render)
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service !== service) return
        unsubscribe?.invoke()
        unsubscribe = null
        insetUnsubscribe?.invoke()
        insetUnsubscribe = null
        teardown()
        processedMotionTokens.clear()
        this.service = null
        windowManager = null
    }

    private val ambientWindow = object : AmbientWindow {
        override val layer = AmbientLayer.ACTIVITY

        // Leaving islands are still children of the window, so they count too.
        override val isAnimating: Boolean
            get() = root?.let { container ->
                (0 until container.childCount).any { (container.getChildAt(it) as? HudIslandView)?.isAnimating == true }
            } == true

        override fun readd(): Boolean {
            val manager = windowManager ?: return false
            val currentRoot = root ?: return false
            return runCatching {
                manager.removeView(currentRoot)
                manager.addView(currentRoot, params())
            }.onFailure { logError("Activity overlay z-order refresh failed", it) }.isSuccess
        }
    }

    private fun render(state: ActivityRenderState) {
        latestState = state
        val liveIds = state.items.mapTo(mutableSetOf()) { it.activity.surfaceId }
        processedMotionTokens.keys.retainAll(liveIds)
        if (state.items.isEmpty()) {
            dismissAll()
            return
        }

        // Hidden updates still consume their motion token. Restoring after the
        // camera therefore restores current state without replaying a flare.
        state.items
            .filter { it.presentation == ActivityPresentation.HIDDEN }
            .forEach { processedMotionTokens[it.activity.surfaceId] = it.activity.motionToken }

        val visible = state.items.filter { it.presentation != ActivityPresentation.HIDDEN }
        if (visible.isEmpty()) {
            // The camera needs the glasses clear now, not after a fold.
            teardown(keepMotionTokens = true)
            return
        }
        val activeService = service ?: return
        val container = ensureWindow(activeService) ?: return
        activeFlare?.let { flare ->
            // A flare is a timed presentation event, not a property every
            // subsequent state publish repeats. Keep it running across content,
            // pin, and context publishes; removal, hiding, or a same-owner
            // activity restart interrupts the old session.
            val stillCurrent = visible.any {
                it.activity.surfaceId == flare.surfaceId &&
                    it.activity.startedOrder == flare.startedOrder
            }
            if (!stillCurrent) cancelFlare()
        }

        nodes.keys.filterNot(liveIds::contains).forEach { id ->
            nodes.remove(id)?.let { island -> island.dismiss { container.removeView(island) } }
        }

        var pendingFlare: Pair<ActivityRenderItem, ActivityIsland>? = null
        visible.forEach { item ->
            val island = nodes[item.activity.surfaceId] ?: ActivityIsland(activeService).also {
                it.onIdle = { AmbientStack.main.animationEnded() }
                nodes[item.activity.surfaceId] = it
                container.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            val flareInProgress = activeFlare?.let { flare ->
                item.activity.surfaceId == flare.surfaceId &&
                    item.activity.startedOrder == flare.startedOrder
            } == true
            val previousToken = processedMotionTokens[item.activity.surfaceId] ?: Long.MIN_VALUE
            val newMotion = item.activity.motionToken > previousToken
            island.place(item.activity.corner, hudTopInsetDp)
            island.render(item)
            island.visibility = View.VISIBLE
            // A running flare holds its form; the steady form returns when it folds.
            if (!flareInProgress) island.showSteady(item.presentation)
            when (item.presentation) {
                ActivityPresentation.PULSE -> {
                    if (newMotion) island.bump(PULSE_PX.toFloat())
                }
                ActivityPresentation.FLARE -> {
                    if (newMotion) pendingFlare = item to island
                }
                ActivityPresentation.CHIP,
                ActivityPresentation.PANEL,
                ActivityPresentation.HIDDEN,
                -> Unit
            }
            if (newMotion) {
                processedMotionTokens[item.activity.surfaceId] = item.activity.motionToken
            }
        }
        activeFlare?.let { flare -> nodes[flare.surfaceId]?.bringToFront() }
        pendingFlare?.let { (item, island) -> startFlare(item, island) }
    }

    private fun ensureWindow(service: AccessibilityService): FrameLayout? {
        root?.let { return it }
        val manager = windowManager
            ?: service.getSystemService(WindowManager::class.java)
            ?: return null
        val nextRoot = FrameLayout(service)
        if (runCatching { manager.addView(nextRoot, params()) }.isFailure) {
            logError("Activity overlay window could not be added")
            return null
        }
        root = nextRoot
        AmbientStack.main.added(ambientWindow)
        return nextRoot
    }

    private fun applyHudTopInset(value: Int) {
        hudTopInsetDp = HudTopInset.sanitize(value)
        nodes.values.forEach { island -> island.place(island.corner, hudTopInsetDp) }
    }

    private fun startFlare(item: ActivityRenderItem, island: ActivityIsland) {
        // Another activity's flare folds back into its own steady form.
        cancelFlare()
        val generation = ++flareGeneration
        activeFlare = FlareSession(
            surfaceId = item.activity.surfaceId,
            startedOrder = item.activity.startedOrder,
        )
        island.bringToFront()
        island.showFlare(urgent = item.urgent)
        val collapse = Runnable { collapseFlare(generation) }
        flareCollapse = collapse
        main.postDelayed(collapse, HudMotion.STANDARD_MS + HudMotion.HOLD_MS)
    }

    private fun collapseFlare(generation: Long) {
        if (generation != flareGeneration) return
        flareCollapse = null
        val flare = activeFlare ?: return
        activeFlare = null
        foldToSteady(flare)
    }

    private fun cancelFlare() {
        flareGeneration++
        flareCollapse?.let(main::removeCallbacks)
        flareCollapse = null
        val flare = activeFlare ?: return
        activeFlare = null
        foldToSteady(flare)
    }

    private fun foldToSteady(flare: FlareSession) {
        val island = nodes[flare.surfaceId] ?: return
        val item = latestState.items.firstOrNull { it.activity.surfaceId == flare.surfaceId } ?: return
        island.showSteady(item.presentation)
    }

    /** The last activity ended: every island folds away, then the window goes. */
    private fun dismissAll() {
        flareGeneration++
        flareCollapse?.let(main::removeCallbacks)
        flareCollapse = null
        activeFlare = null
        val container = root ?: return
        val leaving = nodes.values.toList()
        nodes.clear()
        if (leaving.isEmpty()) {
            teardown()
            return
        }
        leaving.forEach { island ->
            island.dismiss {
                container.removeView(island)
                if (root === container && nodes.isEmpty() && container.childCount == 0) teardown()
            }
        }
    }

    private fun teardown(keepMotionTokens: Boolean = false) {
        flareGeneration++
        flareCollapse?.let(main::removeCallbacks)
        flareCollapse = null
        activeFlare = null
        val currentRoot = root
        if (currentRoot != null) {
            runCatching { windowManager?.removeView(currentRoot) }
                .onFailure { logError("Activity overlay removal failed", it) }
        }
        root = null
        nodes.clear()
        if (!keepMotionTokens) processedMotionTokens.clear()
        if (currentRoot != null) AmbientStack.main.removed(AmbientLayer.ACTIVITY)
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /**
     * One activity: the medium chip and the expanded panel at its corner, and
     * the notice-band flare at the top, all drawn inside one island outline.
     */
    internal class ActivityIsland(context: Context) : HudIslandView(context) {
        private val chip = PinOverlayRenderer.PinPanelView(context).apply { dropChrome() }
        private val panel = ActivityPanelView(context)
        private val flare = NoticeOverlayRenderer.NoticeBandView(context, chromeless = true)
        private val renderedKeys = mutableMapOf<View, Any?>()
        private var lastItem: ActivityRenderItem? = null
        private var flareUrgent = false
        var corner = PinSurfacePosition.TOP_LEFT
            private set
        private var topInsetDp = -1

        init {
            val width = HudGeometry.DEFAULT.viewport.width
            addForm(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addForm(panel, LayoutParams((width * PANEL_WIDTH_FRACTION).toInt(), LayoutParams.WRAP_CONTENT))
            addForm(
                flare,
                LayoutParams(HudBandGeometry.widthPx(width), LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                },
            )
        }

        fun place(corner: PinSurfacePosition, topInsetDp: Int) {
            if (corner == this.corner && topInsetDp == this.topInsetDp) return
            this.corner = corner
            this.topInsetDp = topInsetDp
            val topEdge = RokidHudTokens.SAFE_Y + HudTopInset.toPx(context, topInsetDp)
            val top = corner == PinSurfacePosition.TOP_LEFT || corner == PinSurfacePosition.TOP_RIGHT
            val gravity = when (corner) {
                PinSurfacePosition.TOP_LEFT -> Gravity.TOP or Gravity.START
                PinSurfacePosition.TOP_RIGHT -> Gravity.TOP or Gravity.END
                PinSurfacePosition.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
                PinSurfacePosition.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            }
            listOf(chip, panel).forEach { form ->
                form.layoutParams = (form.layoutParams as LayoutParams).apply {
                    this.gravity = gravity
                    setMargins(
                        RokidHudTokens.SAFE_X,
                        if (top) topEdge else RokidHudTokens.SAFE_Y,
                        RokidHudTokens.SAFE_X,
                        RokidHudTokens.SAFE_Y,
                    )
                }
            }
            flare.setHudTopInsetDp(topInsetDp)
            // The flare is the notice band: it shares the band's width and top, as the Ink card does.
            flare.layoutParams = (flare.layoutParams as LayoutParams).apply {
                topMargin = HudBandGeometry.topPx(context, topInsetDp)
            }
        }

        fun render(item: ActivityRenderItem) {
            lastItem = item
            val content = item.activity.content
            val owner = item.activity.ownerPluginId
            update(chip, listOf(content.primary, content.measure, content.secondary, content.glyph)) {
                chip.render(
                    titleText = content.primary,
                    // Folded, the measure sits under the primary beside the glyph:
                    // "3 min" over "250 m", with the street still on the line below.
                    subtitleText = content.measure,
                    lineContent = content.secondary
                        ?.let { listOf(PinSurfaceLine(it)) }
                        .orEmpty(),
                    size = PinSurfaceSize.MEDIUM,
                    leadingGlyph = content.badge?.let { ActivityBadgeDrawable(it) }
                        ?: GlassesHub.activityGlyphDrawable(context, owner, content.glyph),
                )
            }
            update(panel, content to item.activity.selectedActionIndex) {
                panel.render(
                    content = content,
                    mainGlyph = GlassesHub.activityGlyphDrawable(context, owner, content.glyph),
                    selectedActionIndex = item.activity.selectedActionIndex,
                )
            }
            renderFlare(item)
        }

        private fun renderFlare(item: ActivityRenderItem) {
            val content = item.activity.content
            val owner = item.activity.ownerPluginId
            update(flare, content to flareUrgent) {
                flare.render(
                    titleText = content.primaryWithMeasure(),
                    bodyText = buildList {
                        content.secondary?.let(::add)
                        addAll(content.detail)
                    }.joinToString("  •  ").takeIf(String::isNotEmpty),
                    footerText = content.eta,
                    leadingGlyph = content.badge?.let { ActivityBadgeDrawable(it) }
                        ?: GlassesHub.activityGlyphDrawable(context, owner, content.glyph),
                    track = content.track,
                    urgent = flareUrgent,
                )
            }
        }

        fun showSteady(presentation: ActivityPresentation) {
            removeCallbacks(urgentBlink)
            stopBlink()
            setOutline(RokidHudTokens.LINE, RokidHudTokens.BORDER_DEFAULT.toFloat())
            show(if (presentation == ActivityPresentation.PANEL) panel else chip)
        }

        /**
         * The urgent flare is the design system's `Status critical`: the same notice band with
         * the alert icon and a 2 px `focus` outline that blinks three times once the band has
         * arrived, then stays. The blink waits for the morph, so it never fights the band's own
         * travel.
         */
        fun showFlare(urgent: Boolean) {
            removeCallbacks(urgentBlink)
            stopBlink()
            if (urgent != flareUrgent) {
                flareUrgent = urgent
                lastItem?.let(::renderFlare)
            }
            if (urgent) {
                setOutline(RokidHudTokens.CRITICAL, RokidHudTokens.BORDER_STRONG.toFloat())
                postDelayed(urgentBlink, URGENT_BLINK_DELAY_MS)
            } else {
                setOutline(RokidHudTokens.LINE, RokidHudTokens.BORDER_DEFAULT.toFloat())
            }
            show(flare)
        }

        private val urgentBlink = Runnable {
            if (currentForm === flare) blinkOutline()
        }

        private fun update(form: View, key: Any?, change: () -> Unit) {
            if (renderedKeys.containsKey(form) && renderedKeys[form] == key) return
            renderedKeys[form] = key
            crossfade(form, change)
        }
    }

    /**
     * The platform-owned expanded geometry; no plugin field controls it. A `Panel`'s content: the
     * primary is the panel's one `display` value, the ETA a `data` readout, the measure of progress
     * a `Loader`, the choices `Button`s.
     */
    private class ActivityPanelView(context: Context) : LinearLayout(context) {
        private val glyph = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        private val primary = SurfaceType.display(TextView(context)).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        private val eta = SurfaceType.data(TextView(context))
        // The contract allows 28 characters, about 30 of which fit one line of
        // the text column; a stop or street name wraps rather than losing its end.
        private val secondary = SurfaceType.wrap(
            SurfaceType.body(TextView(context), RokidHudTokens.TEXT_SECONDARY),
            SECONDARY_MAX_LINES,
        )
        private val etaBelow = SurfaceType.data(TextView(context))
        private val bar = MediaProgressView(context)
        private val scan = HudLoaderView(context)
        private val percent = SurfaceType.mono(TextView(context))
        private val progress = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bar, LayoutParams(0, MediaProgressView.HEIGHT_PX, 1f))
            addView(scan, LayoutParams(0, MediaProgressView.HEIGHT_PX, 1f))
            addView(
                percent,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = RokidHudTokens.SPACE_2
                },
            )
        }
        private val track = ActivityTrackView(context)
        private val details = List(2) { SurfaceType.bodySmall(TextView(context)) }
        private val actions = HudActionRowView(context)
        private val secondaryRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(secondary, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(
                etaBelow,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = RokidHudTokens.SPACE_2 },
            )
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            val padding = RokidHudTokens.SPACE_3
            setPadding(padding, padding, padding, padding)

            addView(
                glyph,
                LayoutParams(RokidHudTokens.ICON_LG, RokidHudTokens.ICON_LG).apply {
                    marginEnd = RokidHudTokens.SPACE_3
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(
                        LinearLayout(context).apply {
                            orientation = HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(primary, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                            addView(
                                eta,
                                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                                    .apply { marginStart = RokidHudTokens.SPACE_2 },
                            )
                        },
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                    )
                    addView(
                        secondaryRow,
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                    )
                    addView(
                        progress,
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            topMargin = RokidHudTokens.SPACE_2
                        },
                    )
                    addView(
                        track,
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            topMargin = RokidHudTokens.SPACE_2
                        },
                    )
                    details.forEach { detail ->
                        addView(
                            detail,
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                                topMargin = RokidHudTokens.SPACE_1
                            },
                        )
                    }
                    addView(
                        actions,
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            topMargin = RokidHudTokens.SPACE_2
                        },
                    )
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        fun render(
            content: ActivitySurfaceContent,
            mainGlyph: Drawable,
            selectedActionIndex: Int,
        ) {
            AmbientStyle.glyph(
                glyph,
                content.badge?.let { ActivityBadgeDrawable(it) } ?: mainGlyph,
                RokidHudTokens.ICON_LG,
                RokidHudTokens.TEXT_PRIMARY,
            )
            val primaryText = content.primaryWithMeasure()
            primary.text = primaryText
            val fit = fitPrimary(primaryText, content.eta)
            RokidHudTokens.applyTextSize(primary, fit.sizePx)
            eta.text = content.eta.orEmpty()
            eta.visibility = visibleIf(content.eta != null && !fit.etaBelow)
            etaBelow.text = content.eta.orEmpty()
            etaBelow.visibility = visibleIf(content.eta != null && fit.etaBelow)
            secondary.text = content.secondary.orEmpty()
            secondary.visibility = visibleIf(content.secondary != null)
            secondaryRow.visibility = visibleIf(content.secondary != null || fit.etaBelow)
            // Glasses that draw the track draw it instead of the bar; the bar
            // stays in the payload for glasses that do not.
            track.render(content.track)
            when (val value = content.progress?.takeIf { content.track == null }) {
                null -> progress.visibility = View.GONE
                ActivityProgress.Indeterminate -> {
                    progress.visibility = View.VISIBLE
                    bar.visibility = View.GONE
                    percent.visibility = View.GONE
                    scan.visibility = View.VISIBLE
                    scan.setActive(true)
                }
                is ActivityProgress.Percent -> {
                    progress.visibility = View.VISIBLE
                    scan.setActive(false)
                    scan.visibility = View.GONE
                    bar.visibility = View.VISIBLE
                    bar.setProgress(value.value / 100f)
                    percent.text = "${value.value}%"
                    percent.visibility = View.VISIBLE
                }
            }
            details.forEachIndexed { index, view ->
                val line = content.detail.getOrNull(index)
                view.text = line.orEmpty()
                view.visibility = visibleIf(line != null)
            }
            actions.render(
                actions = content.actions.map { HudActionChip(it.glyph, it.label) },
                selectedIndex = selectedActionIndex,
            )
        }

        /**
         * The text column's width is fixed by the panel geometry, so the fit is
         * computed from it rather than from a layout pass that has not run yet.
         */
        private fun fitPrimary(text: String, etaText: String?): ActivityPrimaryFit {
            val panelWidth = HudGeometry.DEFAULT.viewport.width * PANEL_WIDTH_FRACTION
            val available = panelWidth - paddingLeft - paddingRight -
                glyph.layoutParams.width - RokidHudTokens.SPACE_3 - FIT_SLACK
            val measure = TextPaint(primary.paint)
            return fitActivityPrimary(
                availablePx = available,
                inlineEtaPx = etaText?.let { eta.paint.measureText(it) + RokidHudTokens.SPACE_2 },
                widthAtPx = { sizePx ->
                    measure.textSize = sizePx
                    measure.measureText(text)
                },
            )
        }

        private fun visibleIf(visible: Boolean): Int =
            if (visible) View.VISIBLE else View.GONE
    }

    /** Expanded, the measure stands beside the primary: "3 min - 250 m". */
    private fun ActivitySurfaceContent.primaryWithMeasure(): String =
        measure?.let { "$primary - $it" } ?: primary

    /** Forms draw no border of their own; the island draws the only one. */
    private fun View.dropChrome() {
        val left = paddingLeft
        val top = paddingTop
        val right = paddingRight
        val bottom = paddingBottom
        background = null
        setPadding(left, top, right, bottom)
    }

    private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    private const val PANEL_WIDTH_FRACTION = 0.78f
    private const val PULSE_PX = RokidHudTokens.SPACE_3
    private const val URGENT_BLINK_DELAY_MS = 380L
    private const val FIT_SLACK = 2
    private const val SECONDARY_MAX_LINES = 2
}
