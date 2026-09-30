package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
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
import com.anezium.rokidbus.glasses.hud.HudIconView
import com.anezium.rokidbus.glasses.hud.HudStatusView
import com.anezium.rokidbus.shared.ActivityTrack
/**
 * The ROM sleeps the display five seconds after the last input (vendor-set
 * `screen_off_timeout`), which is shorter than a notice's own life -- a dictated
 * reply once died mid-flow under a dark screen. The window exists exactly as
 * long as the notice does, so holding the screen here cannot outlive what
 * warrants it. On this firmware the flag alone does not actually stop the
 * panel; the assistant's episode wake lock is what does. Both are kept: the
 * flag costs nothing and other firmware honours it.
 */
internal fun noticeWindowFlags(): Int =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

internal fun noticeBackdropAlpha(fadeAlpha: Float, backdrop: Boolean): Float =
    if (backdrop) fadeAlpha else 0f

internal enum class NoticeRenderMotion {
    ENTER,
    REENTER,
    UPDATE,
}

internal fun noticeRenderMotion(fadeAlpha: Float, exitRunning: Boolean): NoticeRenderMotion = when {
    exitRunning -> NoticeRenderMotion.REENTER
    fadeAlpha == 0f -> NoticeRenderMotion.ENTER
    else -> NoticeRenderMotion.UPDATE
}

internal fun noticeBandHeightCeiling(
    displayHeightPx: Int,
    heightFraction: Float,
    topInsetPx: Int,
): Int = ((displayHeightPx * heightFraction).toInt() - topInsetPx).coerceAtLeast(0)

internal enum class NoticeDismissMotion {
    SLIDE_AND_FADE,
    INK_FADE_IN_PLACE,
}

internal fun noticeDismissMotion(inkMorphActive: Boolean): NoticeDismissMotion =
    if (inkMorphActive) NoticeDismissMotion.INK_FADE_IN_PLACE else NoticeDismissMotion.SLIDE_AND_FADE

internal data class NoticeInkMorphToken(
    val surfaceId: String,
    val seq: Long,
    val ownerPluginId: String,
    val bandHeightPx: Int,
    val initialAlpha: Float,
) {
    fun matches(notice: NexusNoticeSurface): Boolean =
        surfaceId == notice.surfaceId && seq == notice.seq && ownerPluginId == notice.ownerPluginId
}

/**
 * The notice band: a transient panel across the top that arrives, says its
 * piece, and leaves.
 *
 * The window is full-screen and the band is a child inside it. That is not
 * decoration: `updateViewLayout` is an IPC round-trip to `system_server`, so
 * driving it per frame races against the view's own frame production, and a
 * window can only translate and resize a rectangle where a view can also fade,
 * clip and morph. The window stays put and only child bounds move. See plan 013.
 *
 * Like the pin and like Relay's own overlay, the window is never focusable and
 * never touchable: it does not steal focus from what is underneath, and the
 * touchpad keeps working for everything the notice has not explicitly claimed.
 * Ordinary notices never keep the screen on. The assistant marks only its
 * listening, thinking, and answer-review episode as engaged. The window keeps
 * its existing flag, but the fixed [AssistantDisplayEpisode] owner holds the
 * measured firmware wake lease independently of this drawable and its morph.
 * Wake requests remain separately owned by [NoticeController].
 */
object NoticeOverlayRenderer {
    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var container: FrameLayout? = null
    private var scrim: View? = null
    private var band: NoticeBandView? = null
    private var unsubscribe: (() -> Unit)? = null
    private var insetUnsubscribe: (() -> Unit)? = null
    private var bandHeightPx = 0
    private var backdrop = false
    private var hudTopInsetDp = 0
    private var exitRunning = false
    private var renderedSeq: Long? = null
    private var inkMorph: NoticeInkMorphToken? = null
    private var composeUnsubscribe: (() -> Unit)? = null

    private val motion = AmbientStyle.motionDriver { service }
    private val slide = AmbientMotionValue(motion, 0f, { offset -> band?.translationY = offset }, ::motionIdle)
    private val fade = AmbientMotionValue(
        motion,
        0f,
        { alpha ->
            band?.alpha = alpha
            scrim?.alpha = noticeBackdropAlpha(alpha, backdrop)
        },
        ::motionIdle,
    )

    private fun motionIdle() {
        if (!ambientWindow.isAnimating) AmbientStack.main.animationEnded()
    }

    fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        windowManager = service.getSystemService(WindowManager::class.java)
        insetUnsubscribe?.invoke()
        insetUnsubscribe = HudTopInset.observe(service, ::applyHudTopInset)
        unsubscribe?.invoke()
        unsubscribe = NoticeController.observe(::render)
        composeUnsubscribe?.invoke()
        composeUnsubscribe = NoticeComposeMirror.observe { line -> band?.renderCompose(line) }
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service !== service) return
        unsubscribe?.invoke()
        unsubscribe = null
        composeUnsubscribe?.invoke()
        composeUnsubscribe = null
        insetUnsubscribe?.invoke()
        insetUnsubscribe = null
        teardown()
        this.service = null
        windowManager = null
    }

    private val ambientWindow = object : AmbientWindow {
        override val layer = AmbientLayer.NOTICE

        // The band slides and fades in and out inside this window; re-adding would cut it short.
        override val isAnimating: Boolean
            get() = slide.isRunning || fade.isRunning

        override fun readd(): Boolean {
            val manager = windowManager ?: return false
            val root = container ?: return false
            return runCatching {
                manager.removeView(root)
                manager.addView(root, params(root.context))
            }.onFailure { logError("Notice overlay z-order refresh failed", it) }.isSuccess
        }
    }

    fun isShown(): Boolean = container != null

    internal fun beginInkMorph(notice: NexusNoticeSurface): NoticeInkMorphToken? {
        val view = band ?: return null
        if (
            container == null || exitRunning || renderedSeq != notice.seq ||
            notice.ownerPluginId.isBlank() || view.width <= 0 || view.height <= 0 ||
            fade.current <= 0f
        ) {
            return null
        }
        val token = NoticeInkMorphToken(
            surfaceId = notice.surfaceId,
            seq = notice.seq,
            ownerPluginId = notice.ownerPluginId,
            bandHeightPx = view.height,
            initialAlpha = fade.current,
        )
        inkMorph = token
        bandHeightPx = view.height
        exitRunning = false
        slide.snapTo(0f)
        fade.cancel()
        return token
    }

    internal fun startInkMorphFade(token: NoticeInkMorphToken): Boolean {
        if (inkMorph != token || container == null || band == null) return false
        return runCatching {
            slide.snapTo(0f)
            fade.animateTo(0f, RokidHudTokens.DURATION_DEFAULT_MS)
            true
        }.onFailure {
            fade.snapTo(0f)
            logError("Notice Ink morph fade could not start", it)
        }.getOrDefault(false)
    }

    internal fun finishInkMorph(token: NoticeInkMorphToken): Boolean {
        if (inkMorph != token) return false
        fade.snapTo(0f)
        inkMorph = null
        teardown()
        return true
    }

    internal fun cancelInkMorph(token: NoticeInkMorphToken): Boolean {
        if (inkMorph != token) return false
        inkMorph = null
        fade.snapTo(token.initialAlpha)
        return true
    }

    private fun render(notice: NexusNoticeSurface?) {
        if (notice == null) {
            dismiss()
            return
        }
        val interruptedInkMorph = inkMorph?.matches(notice) == false
        if (interruptedInkMorph) {
            inkMorph = null
            fade.cancel()
            slide.snapTo(0f)
        }
        backdrop = notice.content.backdrop
        scrim?.alpha = noticeBackdropAlpha(fade.current, backdrop)
        val activeService = service ?: return
        val view = ensureWindow(activeService) ?: return
        val motion = if (interruptedInkMorph) {
            NoticeRenderMotion.REENTER
        } else {
            noticeRenderMotion(fade.current, exitRunning)
        }
        val fadeWasRunning = fade.isRunning
        renderedSeq = notice.seq
        view.render(notice)
        log(
            "renderer seq=${notice.seq} event=render attached=${container != null} " +
                "fadeRunning=$fadeWasRunning",
        )
        when (motion) {
            NoticeRenderMotion.ENTER -> {
                // Measure once the content is in place: the band's height is what the
                // arrival slides through, and it depends on how much body there is.
                view.post {
                    if (band !== view || renderedSeq != notice.seq || exitRunning) return@post
                    bandHeightPx = view.height.takeIf { it > 0 } ?: bandHeightPx
                    slide.snapTo(-bandHeightPx.toFloat())
                    slide.animateTo(0f, RokidHudTokens.DURATION_STRUCTURAL_MS)
                    fade.animateTo(1f, RokidHudTokens.DURATION_STRUCTURAL_MS)
                }
            }
            NoticeRenderMotion.REENTER -> {
                // Retargeting immediately cancels the exit's teardown continuation.
                // Waiting for layout here leaves one main-loop turn in which the old
                // fade can still reach zero and remove the live notice's window.
                exitRunning = false
                slide.animateTo(0f, RokidHudTokens.DURATION_STRUCTURAL_MS)
                fade.animateTo(1f, RokidHudTokens.DURATION_STRUCTURAL_MS)
            }
            NoticeRenderMotion.UPDATE -> Unit
        }
    }

    private fun dismiss() {
        if (container == null) return
        if (noticeDismissMotion(inkMorph != null) == NoticeDismissMotion.INK_FADE_IN_PLACE) return
        exitRunning = true
        slide.animateTo(-bandHeightPx.toFloat(), RokidHudTokens.DURATION_STRUCTURAL_MS)
        fade.animateTo(0f, RokidHudTokens.DURATION_STRUCTURAL_MS) { teardown() }
    }

    private fun ensureWindow(service: AccessibilityService): NoticeBandView? {
        band?.let { return it }
        val manager = windowManager
            ?: service.getSystemService(WindowManager::class.java)
            ?: return null
        val root = FrameLayout(service)
        // An opted-in notice can own the whole display while it is up. The scrim
        // is opaque black: the additive optics emit nothing for it, but it
        // occludes every window underneath. It rides the band's fade and, being
        // part of a NOT_TOUCHABLE window, blocks nothing but light.
        val shade = View(service).apply {
            setBackgroundColor(RokidHudTokens.GROUND)
            alpha = 0f
        }
        root.addView(
            shade,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val view = NoticeBandView(service, NoticeController::setPageCount).apply {
            setHudTopInsetDp(hudTopInsetDp)
        }
        root.addView(view, bandLayoutParams(service, hudTopInsetDp))
        if (runCatching { manager.addView(root, params(service)) }.isFailure) {
            logError("Notice overlay window could not be added")
            return null
        }
        container = root
        scrim = shade
        band = view
        view.alpha = 0f
        fade.snapTo(0f)
        AmbientStack.main.added(ambientWindow)
        return view
    }

    /** Where the band sits inside the notice window: top-centred, on the Ink card's width and top. */
    internal fun bandLayoutParams(context: Context, hudTopInsetDp: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            HudBandGeometry.widthPx(HudGeometry.DEFAULT.viewport.width),
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = HudBandGeometry.topPx(context, hudTopInsetDp)
        }

    private fun applyHudTopInset(value: Int) {
        hudTopInsetDp = HudTopInset.sanitize(value)
        band?.let { currentBand ->
            currentBand.setHudTopInsetDp(hudTopInsetDp)
            val layout = currentBand.layoutParams as? FrameLayout.LayoutParams ?: return@let
            layout.topMargin = HudBandGeometry.topPx(currentBand.context, hudTopInsetDp)
            currentBand.layoutParams = layout
        }
        container?.requestLayout()
    }

    private fun params(context: Context): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            noticeWindowFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun teardown() {
        val root = container
        if (root == null) {
            return
        }
        val seq = renderedSeq ?: -1L
        runCatching { windowManager?.removeView(root) }
            .onFailure { logError("Notice overlay removal failed", it) }
        container = null
        scrim = null
        band = null
        AmbientStack.main.removed(AmbientLayer.NOTICE)
        inkMorph = null
        backdrop = false
        exitRunning = false
        slide.snapTo(0f)
        fade.snapTo(0f)
        renderedSeq = null
        log(
            "renderer seq=$seq event=teardown attached=${container != null} " +
                "fadeRunning=${fade.isRunning}",
        )
    }

    /**
     * Shared top-band geometry used unchanged by notices and activity flares.
     *
     * A `Panel` (1 px `line`, `radius-panel`) with `space-3` padding: `heading` title, `body`
     * message at `text-primary`, `body-small` footer and `mono` page counter at `text-secondary`,
     * and a row of `Button`s whose selected member is the focus chrome.
     */
    internal class NoticeBandView(
        context: Context,
        private val pageCountChanged: ((String, Long, Int) -> Unit)? = null,
        /** An activity island draws the band's outline itself. */
        chromeless: Boolean = false,
    ) : LinearLayout(context) {
        private val glyph = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        private val title = SurfaceType.heading(TextView(context)).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }

        /** `Status critical`'s icon: shown only on an urgent flare. */
        private val alert = HudIconView(context, HudIconView.Kind.ALERT).apply {
            setIntensity(RokidHudTokens.CRITICAL)
            visibility = View.GONE
        }
        private val titleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                glyph,
                LayoutParams(RokidHudTokens.ICON_LG, RokidHudTokens.ICON_LG).apply {
                    marginEnd = RokidHudTokens.SPACE_2
                },
            )
            addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(
                alert,
                LayoutParams(RokidHudTokens.ICON_MD, RokidHudTokens.ICON_MD).apply {
                    marginStart = RokidHudTokens.SPACE_2
                },
            )
        }
        private val image = NoticeImageView(context)
        private val body = NoticeBodyView(context) { count ->
            if (measuredPageCount != count && noticeIdentity != null) {
                pageCountReportPending = true
            }
            measuredPageCount = count
            updateFooter()
        }
        private val footer = SurfaceType.bodySmall(TextView(context))
        private val status = HudStatusView(context)
        private val pageIndicator = SurfaceType.mono(TextView(context)).apply {
            gravity = Gravity.END
        }
        private val footerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            // With no footer text the page counter is alone in the row, and belongs at its end.
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(footer, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(status, LayoutParams(0, HudStatusView.HEIGHT, 1f))
            addView(
                pageIndicator,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = RokidHudTokens.SPACE_2
                },
            )
        }

        /** The field being typed into is the focused control: `surface-selected`, 2 px `focus`. */
        private val compose = SurfaceType.body(TextView(context)).apply {
            ellipsize = null
            minLines = COMPOSE_LINES
            maxLines = COMPOSE_LINES
            isVerticalScrollBarEnabled = false
            val padding = RokidHudTokens.SPACE_2
            setPadding(padding, padding, padding, padding)
            background = SurfaceChrome.focused()
            visibility = View.GONE
        }
        private val actions = HudActionRowView(context)
        private val track = ActivityTrackView(context).apply { visibility = View.GONE }
        private var noticeIdentity: Pair<String, Long>? = null
        private var noticeOwner = ""
        private var liveChips: List<HudActionChip> = emptyList()
        private var selectedChip = 0
        private var pluginFooter: String? = null
        private var footerWarns = false
        private var renderedPageIndex = 0
        private var measuredPageCount = 1
        private var pageableNotice = false
        private var noticeHasImage = false
        private var noticeActionCount = 0
        private var pageCountReportPending = false
        private var hudTopInsetPx = 0

        init {
            orientation = VERTICAL
            val padding = RokidHudTokens.SPACE_3
            setPadding(padding, padding, padding, padding)
            if (!chromeless) background = AmbientStyle.panel()
            addView(titleRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(
                image,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = RokidHudTokens.SPACE_1
                },
            )
            addView(
                body,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = RokidHudTokens.SPACE_1
                },
            )
            // Under the message it answers, the way an inline reply sits under
            // the notification it replies to.
            addView(
                compose,
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
            addView(
                footerRow,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = RokidHudTokens.SPACE_1
                },
            )
            // Under the footer, so the reading order is what the band says, then
            // how to answer it, then the answers themselves.
            addView(
                actions,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = RokidHudTokens.SPACE_2
                },
            )
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val heightFraction = if (pageableNotice) {
                GROWN_HEIGHT_FRACTION
            } else {
                MAX_HEIGHT_FRACTION
            }
            val ceiling = noticeBandHeightCeiling(
                displayHeightPx = HudGeometry.DEFAULT.viewport.height,
                heightFraction = heightFraction,
                topInsetPx = hudTopInsetPx,
            )
            val cappedHeightSpec = MeasureSpec.makeMeasureSpec(ceiling, MeasureSpec.AT_MOST)

            if (!pageableNotice) {
                super.onMeasure(widthMeasureSpec, cappedHeightSpec)
                publishPageCount()
                return
            }

            var remainingPasses = CAPACITY_MEASURE_PASSES
            do {
                super.onMeasure(widthMeasureSpec, cappedHeightSpec)
                val lineCount = body.measuredLineCount
                val lineHeight = body.measuredLineHeightPx
                if (lineCount <= 0 || lineHeight <= 0) break

                // The image has a stable five-line cost below. Remove its current
                // measured contribution here so page one and later pages derive
                // the same full-page capacity when the image appears/disappears.
                val nonImageChromeHeight = measuredHeight - body.measuredHeight -
                    visibleImageHeightWithMargin()
                val availableBodyHeight = (ceiling - nonImageChromeHeight).coerceAtLeast(0)
                val grownCapacity = noticeBodyLineCapacity(availableBodyHeight, lineHeight)
                val capacities = noticePageCapacities(
                    lineCount = lineCount,
                    grownCapacity = grownCapacity,
                    hasImage = noticeHasImage,
                    actionCount = noticeActionCount,
                )
                if (!body.setPageCapacities(capacities)) break
                remainingPasses -= 1
            } while (remainingPasses > 0)

            if (remainingPasses == 0) {
                super.onMeasure(widthMeasureSpec, cappedHeightSpec)
            }
            publishPageCount()
        }

        fun setHudTopInsetDp(value: Int) {
            val next = HudTopInset.toPx(context, value)
            if (hudTopInsetPx == next) return
            hudTopInsetPx = next
            requestLayout()
        }

        /**
         * The band draws the notice's *live* actions, so an answered one loses
         * its row and becomes an inert display without the content it was shown
         * with being rewritten.
         */
        fun render(notice: NexusNoticeSurface) {
            track.render(null)
            noticeIdentity = notice.surfaceId to notice.seq
            pluginFooter = noticeFooterText(notice)
            footerWarns = notice.deliveryUnconfirmed
            renderedPageIndex = notice.pageIndex
            measuredPageCount = notice.pageCount
            pageCountReportPending = true
            renderTitle(notice.content.title, null, critical = false)
            val hasImage = notice.imageBitmap?.takeUnless { it.isRecycled } != null
            val paging = notice.content.actions.size <= 1
            pageableNotice = paging
            noticeHasImage = hasImage
            noticeActionCount = notice.content.actions.size
            val drawsImage = hasImage && notice.pageIndex == 0
            image.render(notice.imageBitmap?.takeIf { drawsImage })
            val capacities = noticePageCapacities(
                lineCount = 0,
                grownCapacity = MIN_BODY_LINES,
                hasImage = hasImage,
                actionCount = notice.content.actions.size,
            )
            body.render(
                text = noticeBodyText(notice.content),
                pageIndex = notice.pageIndex,
                capacities = capacities,
                // The same test as NoticeState.isPaged: a row of two or more
                // needs the directions to choose along; anything less leaves
                // them free to turn pages.
                paging = paging,
            )
            updateFooter()
            noticeOwner = notice.ownerPluginId
            liveChips = notice.liveActions.map { HudActionChip(it.glyph, it.label) }
            selectedChip = notice.selectedActionIndex
            renderCompose(NoticeComposeMirror.current)
        }

        /**
         * The band's copy of its owner's field; see [NoticeComposeMirror]. Only
         * the plugin whose band this is may have its typing shown here.
         */
        fun renderCompose(line: NoticeComposeMirror.Line?) {
            val shown = line?.takeIf { noticeOwner.isNotEmpty() && it.ownerPluginId == noticeOwner }
            if (shown == null) {
                compose.visibility = View.GONE
                actions.render(liveChips, selectedChip)
                return
            }
            val render = noticeComposeRender(shown)
            compose.text = SpannableString(render.text).apply {
                setSpan(
                    BackgroundColorSpan(RokidHudTokens.FOCUS),
                    render.caretStart,
                    render.caretEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                setSpan(
                    ForegroundColorSpan(RokidHudTokens.ON_EMPHASIS),
                    render.caretStart,
                    render.caretEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                if (render.placeholderStart < render.text.length) {
                    setSpan(
                        ForegroundColorSpan(RokidHudTokens.TEXT_SECONDARY),
                        render.placeholderStart,
                        render.text.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            compose.visibility = View.VISIBLE
            // A band being typed into asks nothing else: Enter belongs to the
            // field, so a row of chips here would only be a question it is not
            // asking.
            actions.render(emptyList(), 0)
            compose.post { scrollComposeToCaret(render.caretStart) }
        }

        /** Keeps the caret's line in the field's two visible lines as the text grows past them. */
        private fun scrollComposeToCaret(caret: Int) {
            val layout = compose.layout ?: return
            val visible = compose.height - compose.totalPaddingTop - compose.totalPaddingBottom
            if (visible <= 0) return
            val caretBottom = layout.getLineBottom(layout.getLineForOffset(caret))
            val maxScroll = (layout.height - visible).coerceAtLeast(0)
            compose.scrollTo(0, (caretBottom - visible).coerceIn(0, maxScroll))
        }

        /**
         * The text-only form the activity flare borrows. It carries no actions:
         * a flare is a moment of emphasis on something the wearer is already
         * following, not a question, and its own row lives on the panel. An
         * [urgent] flare is the design system's `Status critical`: the alert
         * icon at 100 % beside the title.
         */
        fun render(
            titleText: String?,
            bodyText: String?,
            footerText: String?,
            leadingGlyph: Drawable?,
            actionChips: List<HudActionChip> = emptyList(),
            selectedActionIndex: Int = 0,
            track: ActivityTrack? = null,
            urgent: Boolean = false,
        ) {
            this.track.render(track)
            noticeIdentity = null
            noticeOwner = ""
            compose.visibility = View.GONE
            pluginFooter = footerText
            footerWarns = false
            renderedPageIndex = 0
            measuredPageCount = 1
            pageableNotice = false
            noticeHasImage = false
            noticeActionCount = actionChips.size
            pageCountReportPending = false
            renderTitle(titleText, leadingGlyph, critical = urgent)
            image.render(null)
            body.render(
                text = bodyText,
                pageIndex = 0,
                capacities = NoticePageCapacities(MIN_BODY_LINES, MIN_BODY_LINES),
                paging = false,
            )
            updateFooter()
            actions.render(actionChips, selectedActionIndex)
        }

        private fun renderTitle(titleText: String?, leadingGlyph: Drawable?, critical: Boolean) {
            title.text = titleText.orEmpty()
            titleRow.visibility = visibleIf(!titleText.isNullOrEmpty())
            val intensity = if (critical) RokidHudTokens.CRITICAL else RokidHudTokens.TEXT_PRIMARY
            title.setTextColor(intensity)
            AmbientStyle.glyph(glyph, leadingGlyph, RokidHudTokens.ICON_LG, intensity)
            alert.visibility = visibleIf(critical)
        }

        private fun updateFooter() {
            val text = pluginFooter.orEmpty()
            val warn = footerWarns && text.isNotEmpty()
            footer.text = text
            footer.visibility = visibleIf(text.isNotEmpty() && !warn)
            if (warn) status.show(HudStatusView.Kind.WARN, text) else status.hide()
            pageIndicator.text = if (measuredPageCount > 1) {
                "${renderedPageIndex.coerceIn(0, measuredPageCount - 1) + 1}/$measuredPageCount"
            } else {
                ""
            }
            pageIndicator.visibility = visibleIf(measuredPageCount > 1)
            footerRow.visibility = visibleIf(text.isNotEmpty() || measuredPageCount > 1)
        }

        private fun visibleImageHeightWithMargin(): Int {
            if (image.visibility != View.VISIBLE) return 0
            val margins = image.layoutParams as LayoutParams
            return image.measuredHeight + margins.topMargin + margins.bottomMargin
        }

        private fun publishPageCount() {
            if (!pageCountReportPending) return
            pageCountReportPending = false
            noticeIdentity?.let { (surfaceId, seq) ->
                pageCountChanged?.invoke(surfaceId, seq, measuredPageCount)
            }
        }

        private fun visibleIf(visible: Boolean): Int =
            if (visible) View.VISIBLE else View.GONE
    }

    private class NoticeImageView(context: Context) : ImageView(context) {
        init {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxHeight = MAX_IMAGE_HEIGHT_PX
            setBackgroundColor(RokidHudTokens.GROUND)
        }

        fun render(bitmap: android.graphics.Bitmap?) {
            setImageBitmap(bitmap)
            visibility = if (bitmap == null) View.GONE else View.VISIBLE
        }
    }

    private class NoticeBodyView(
        context: Context,
        private val pageCountChanged: (Int) -> Unit,
    ) : View(context) {
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RokidHudTokens.TEXT_PRIMARY
            textSize = RokidHudTokens.BODY_TEXT_SIZE
            typeface = RokidHudTokens.bodyTypeface()
        }
        // `body` is 14 / 20: the font's own line is shorter, so each line is padded to the 20.
        private val lineExtra = with(paint.fontMetricsInt) { SurfaceType.BODY_LINE - (descent - ascent) }.toFloat()
        private var text: String? = null
        private var pageIndex = 0
        private var capacities = NoticePageCapacities(MIN_BODY_LINES, MIN_BODY_LINES)
        private var paging = false
        private var layout: StaticLayout? = null
        private var window = NoticePageWindow(0, 0)
        private var reportedPageCount = 1

        val measuredLineCount: Int
            get() = if (visibility == View.VISIBLE) layout?.lineCount ?: 0 else 0

        val measuredLineHeightPx: Int
            get() {
                val measured = layout
                    ?.takeIf { visibility == View.VISIBLE && it.lineCount > 0 }
                    ?: return 0
                return measured.getLineTop(1) - measured.getLineTop(0)
            }

        fun render(
            text: String?,
            pageIndex: Int,
            capacities: NoticePageCapacities,
            paging: Boolean,
        ) {
            this.text = text
            this.pageIndex = pageIndex
            this.capacities = capacities
            this.paging = paging
            reportedPageCount = -1
            contentDescription = text.orEmpty()
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
            val content = text
            if (content.isNullOrEmpty()) {
                layout = null
                window = NoticePageWindow(0, 0)
                publishPageCount(1)
                setMeasuredDimension(width, 0)
                return
            }

            val builder = StaticLayout.Builder.obtain(content, 0, content.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(lineExtra, 1f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            if (!paging) {
                builder
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setEllipsizedWidth(width)
                    .setMaxLines(capacities.firstPageLines)
            }
            val measured = builder.build()
            layout = measured
            val count = if (paging) {
                noticePageCount(
                    measured.lineCount,
                    capacities.firstPageLines,
                    capacities.followingPageLines,
                )
            } else {
                1
            }
            publishPageCount(count)
            window = if (paging) {
                noticePageWindow(
                    pageIndex = pageIndex,
                    lineCount = measured.lineCount,
                    firstPageLines = capacities.firstPageLines,
                    followingPageLines = capacities.followingPageLines,
                )
            } else {
                NoticePageWindow(0, measured.lineCount)
            }
            val desiredHeight = measured.getLineTop(window.lastLineExclusive) -
                measured.getLineTop(window.firstLine)
            setMeasuredDimension(
                width,
                resolveSize(desiredHeight, heightMeasureSpec),
            )
        }

        fun setPageCapacities(next: NoticePageCapacities): Boolean {
            if (capacities == next) return false
            capacities = next
            reportedPageCount = -1
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val measured = layout ?: return
            canvas.save()
            canvas.clipRect(0, 0, width, height)
            canvas.translate(0f, -measured.getLineTop(window.firstLine).toFloat())
            measured.draw(canvas)
            canvas.restore()
        }

        private fun publishPageCount(count: Int) {
            if (reportedPageCount == count) return
            reportedPageCount = count
            pageCountChanged(count)
        }
    }

    // Eight lines preserve the compact band exactly. Pageable long notices can
    // grow to fourteen measured lines under the taller ceiling; an image still
    // spends five lines on page one and later pages recover the full capacity.
    private const val MAX_HEIGHT_FRACTION = 0.65f
    private const val GROWN_HEIGHT_FRACTION = 0.92f
    private const val CAPACITY_MEASURE_PASSES = 3
    private const val MAX_IMAGE_HEIGHT_PX = 150
    private const val COMPOSE_LINES = 2
}
