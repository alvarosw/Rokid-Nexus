package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.AbsoluteSizeSpan
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.hud.HudIconView
import com.anezium.rokidbus.shared.EditableSurfaceContract
import com.anezium.rokidbus.shared.EditableSurfaceField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class SurfaceHudView(context: Context) : LinearLayout(context) {
    private data class ActiveInkMorph(
        val surfaceId: String,
        var seq: Long,
        val notice: NoticeInkMorphToken,
        val state: InkCardMorphState = InkCardMorphState(),
    )

    private data class PendingInkFallback(
        val surfaceId: String,
        var seq: Long,
    )

    /**
     * The one row every Nexus surface shares, regardless of plugin: phone
     * charge on the left, the date in the middle, the glasses' own charge on
     * the right. It lives here rather than as a launcher overlay because it is
     * our own drawing, not a chip fighting the ROM's launcher for a place in
     * someone else's status row — so it is exactly as available as the surface
     * itself is, in every plugin, with nothing to lose sync with.
     */
    private val phoneBatteryStatusView = SurfaceType.mono(TextView(context)).apply {
        isSingleLine = true
        gravity = Gravity.START
    }
    private val dateStatusView = SurfaceType.mono(TextView(context)).apply {
        isSingleLine = true
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
    }
    private val glassesBatteryStatusView = SurfaceType.mono(TextView(context)).apply {
        isSingleLine = true
        gravity = Gravity.END
        textAlignment = TEXT_ALIGNMENT_VIEW_END
    }
    private val statusRowView = LinearLayout(context).apply {
        orientation = HORIZONTAL
        addView(phoneBatteryStatusView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(dateStatusView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(glassesBatteryStatusView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }
    private var stopObservingPhoneBattery: (() -> Unit)? = null
    private val statusDateFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val statusRowTicker = object : Runnable {
        override fun run() {
            updateStatusRow()
            postDelayed(this, STATUS_ROW_TICK_MS)
        }
    }

    private val titleView = SurfaceType.heading(TextView(context))
    private val subtitleView = SurfaceType.bodySmall(TextView(context))
    private val previousView = SurfaceType.wrap(SurfaceType.body(TextView(context), RokidHudTokens.TEXT_SECONDARY), 2)
    private val currentView = TextView(context).apply {
        includeFontPadding = false
        setTextColor(RokidHudTokens.TEXT_PRIMARY)
        typeface = RokidHudTokens.bodyTypeface()
        RokidHudTokens.applyTextSize(this, RokidHudTokens.DISPLAY_TEXT_SIZE)
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
        maxLines = 5
        setHorizontallyScrolling(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        }
    }
    private val nextView = SurfaceType.wrap(SurfaceType.body(TextView(context), RokidHudTokens.TEXT_SECONDARY), 3)
    private val boardView = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val editView = CaretReportingEditText(context).apply {
        visibility = GONE
        isFocusable = true
        isFocusableInTouchMode = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
        // A plugin opened this field to be typed into, so the phone may bring its
        // keyboard up for it, unlike any field the wearer merely lands on.
        privateImeOptions = RemoteInputMetadataPolicy.EDITABLE_SURFACE_IME_OPTION
        // The field is always the focused control: `surface-selected` fill, 2 px `focus` border.
        background = SurfaceChrome.focused()
        setPadding(RokidHudTokens.SPACE_3, RokidHudTokens.SPACE_2, RokidHudTokens.SPACE_3, RokidHudTokens.SPACE_2)
        setTextColor(RokidHudTokens.TEXT_PRIMARY)
        setHintTextColor(RokidHudTokens.TEXT_SECONDARY)
        typeface = RokidHudTokens.bodyTypeface()
        RokidHudTokens.applyTextSize(this, RokidHudTokens.HEADING_TEXT_SIZE)
        textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(RokidHudTokens.FOCUS)
            setSize(RokidHudTokens.BORDER_STRONG, RokidHudTokens.HEADING_TEXT_SIZE.toInt())
        }
        // One line: this is a reply, not a composer, and it lets a physical
        // Enter key submit directly instead of inserting a newline the way a
        // multi-line field would.
        setSingleLine(true)
        // Without this, typing or pasting past the wire limit would let the
        // wearer see and edit text that committedPayload() then silently cuts
        // off on submit. Stopping input at the boundary makes the limit
        // visible instead of losing the tail after the fact.
        filters = arrayOf(InputFilter.LengthFilter(EditableSurfaceContract.MAX_TEXT_UTF16_LENGTH))
        setOnEditorActionListener { view, actionId, _ ->
            val isSend = actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE
            if (isSend) {
                SurfaceController.forwardSurfaceText(view.text?.toString().orEmpty(), cancelled = false)
            }
            isSend
        }
        // A hardware Enter key on a bonded keyboard never reaches
        // onEditorActionListener above — that path is IME-synthesized. This is
        // the raw key event a physical Enter actually dispatches through.
        setOnKeyListener { view, keyCode, event ->
            val isEnterDown = keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (isEnterDown) {
                SurfaceController.forwardSurfaceText(
                    (view as EditText).text?.toString().orEmpty(),
                    cancelled = false,
                )
            }
            isEnterDown
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = mirrorCompose()
        })
        onCaretMoved = ::mirrorCompose
    }
    private val readerView = ReaderSurfaceView(context).apply { visibility = GONE }
    private val mediaView = MediaHudView(context).apply { visibility = GONE }
    private val imageView = ImageHudView(context).apply {
        visibility = GONE
        setPadding(RokidHudTokens.SPACE_1, RokidHudTokens.SPACE_1, RokidHudTokens.SPACE_1, RokidHudTokens.SPACE_1)
    }
    private val inkView = InkHudView(context).apply { visibility = GONE }
    private val inkCardHost = InkCardClipHost(context).apply {
        visibility = GONE
        addView(
            inkView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
    private val panelView = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.TOP
        setPadding(RokidHudTokens.SPACE_3, RokidHudTokens.SPACE_3, RokidHudTokens.SPACE_3, RokidHudTokens.SPACE_3)
    }
    private val footerView = SurfaceType.bodySmall(TextView(context)).apply {
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
        maxLines = 1
    }
    private var surface: NexusSurface? = null
    private var lastEditableSurfaceId: String? = null
    // The plugin whose band is drawing this field, while it is; see NoticeComposeMirror.
    private var inlineOwner: String? = null
    private var inlinePlaceholder = ""
    private var stopWatchingNotice: (() -> Unit)? = null
    private val inlineFallback = Runnable { surface?.let(::renderNow) }

    /** A bare card is drawing nothing while its owner's band carries the session. */
    private var heldUnderBand = false

    /** The view draws nothing of its own, background included; see [applySeeThrough]. */
    private var seeThrough = false
    private var listRenderGeneration = 0L
    private var pendingListLayoutListener: View.OnLayoutChangeListener? = null
    private var insetUnsubscribe: (() -> Unit)? = null
    private var stopObservingReaderScroll: (() -> Unit)? = null
    private var hudTopInsetDp = 0
    private var inkPresentationSurfaceId: String? = null
    private var inkPresentationGeneration: Long? = null
    private var activeInkMorph: ActiveInkMorph? = null
    private var pendingInkFallback: PendingInkFallback? = null
    private var inkMorphTimeout: Runnable? = null
    private val inkClipMotion = HudMotionValue(0f) { height ->
        inkCardHost.revealTo(height.roundToInt())
    }
    private val inkAlphaMotion = HudMotionValue(1f) { alpha -> inkView.alpha = alpha }

    private val ticker = object : Runnable {
        override fun run() {
            val active = surface ?: return
            renderNow(active)
            if (shouldTick(active)) {
                postDelayed(this, tickDelay(active))
            }
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.TOP
        setBackgroundColor(RokidHudTokens.GROUND)
        applyFullBleedHost()
        isFocusable = true
        isFocusableInTouchMode = true
        // The glasses never enter touch mode, so the platform would wash this focused,
        // full-screen view in its translucent white focus highlight: a grey veil over
        // whatever a see-through card leaves in view.
        defaultFocusHighlightEnabled = false

        SurfaceType.marquee(titleView)
        previousView.gravity = Gravity.CENTER
        previousView.textAlignment = TEXT_ALIGNMENT_CENTER
        nextView.gravity = Gravity.CENTER
        nextView.textAlignment = TEXT_ALIGNMENT_CENTER
        footerView.gravity = Gravity.CENTER
        footerView.textAlignment = TEXT_ALIGNMENT_CENTER

        addView(statusRowView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = RokidHudTokens.SPACE_2
        })
        addView(inkCardHost, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(panelView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        panelView.background = SurfaceChrome.panel()
        val wide = LayoutParams.MATCH_PARENT
        panelView.addView(titleView, LayoutParams(wide, LayoutParams.WRAP_CONTENT))
        panelView.addView(subtitleView, LayoutParams(wide, LayoutParams.WRAP_CONTENT).apply {
            topMargin = RokidHudTokens.SPACE_1
        })
        panelView.addView(mediaView, LayoutParams(wide, 0, 1f).apply { topMargin = RokidHudTokens.SPACE_3 })
        panelView.addView(imageView, LayoutParams(wide, 0, 1f).apply { topMargin = RokidHudTokens.SPACE_3 })
        panelView.addView(previousView, LayoutParams(wide, LayoutParams.WRAP_CONTENT).apply {
            topMargin = RokidHudTokens.SPACE_6
        })
        panelView.addView(currentView, LayoutParams(wide, 0, 1f).apply { topMargin = RokidHudTokens.SPACE_3 })
        panelView.addView(boardView, LayoutParams(wide, 0, 1f).apply { topMargin = RokidHudTokens.SPACE_3 })
        panelView.addView(editView, LayoutParams(wide, LayoutParams.WRAP_CONTENT).apply {
            topMargin = RokidHudTokens.SPACE_3
        })
        panelView.addView(readerView, LayoutParams(wide, 0, 1f).apply { topMargin = RokidHudTokens.SPACE_3 })
        panelView.addView(nextView, LayoutParams(wide, LayoutParams.WRAP_CONTENT).apply {
            topMargin = RokidHudTokens.SPACE_3
        })
        panelView.addView(footerView, LayoutParams(wide, LayoutParams.WRAP_CONTENT).apply {
            topMargin = RokidHudTokens.SPACE_3
        })
    }

    fun render(next: NexusSurface?) {
        removeCallbacks(ticker)
        val pendingGeneration = next?.takeIf(NexusSurface::isInk)?.let {
            SurfaceController.inkPresentationGeneration(it.surfaceId, it.seq)
        }
        val keepsPresentation = shouldKeepInkPresentation(
            nextIsInk = next?.isInk == true,
            nextSurfaceId = next?.surfaceId.orEmpty(),
            presentationSurfaceId = inkPresentationSurfaceId,
            pendingGeneration = pendingGeneration,
            presentationGeneration = inkPresentationGeneration,
        )
        if (!keepsPresentation) {
            cancelInkPresentation()
            inkPresentationSurfaceId = null
            inkPresentationGeneration = null
        }
        surface = next
        if (next == null) {
            clear()
            return
        }
        renderNow(next)
        if (next.isInk) {
            activeInkMorph
                ?.takeIf {
                    it.surfaceId == next.surfaceId &&
                        it.state.phase == InkCardMorphPhase.WAITING_FOR_FIRST_FRAME
                }
                ?.seq = next.seq
            pendingInkFallback
                ?.takeIf { it.surfaceId == next.surfaceId }
                ?.seq = next.seq
            if (!keepsPresentation) prepareInkPresentation(next)
        }
        if (shouldTick(next)) {
            postDelayed(ticker, tickDelay(next))
        }
        // An editable card keeps the field's own focus: this container regaining
        // it on every render (a re-render can arrive mid-keystroke) is exactly
        // what silently ate the wearer's typing here before.
        if (next.kind == NexusSurface.KIND_CARD && next.editable != null) {
            editView.requestFocus()
        } else if (!next.isInk || !hasFocus()) {
            requestFocus()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val drawn = surface?.takeIf(NexusSurface::isInk) ?: return
        if (!inkView.isLayoutSettledForDraw()) return
        SurfaceController.onInkFrameDrawn(
            surfaceId = drawn.surfaceId,
            seq = drawn.seq,
            widthPx = inkView.width,
            heightPx = inkView.height,
        ) { onInkFirstFrame(drawn.surfaceId, drawn.seq) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        insetUnsubscribe?.invoke()
        insetUnsubscribe = HudTopInset.observe(context, ::applyHudTopInset)
        stopObservingReaderScroll?.invoke()
        stopObservingReaderScroll = SurfaceController.observeReaderScroll { direction ->
            if (surface?.isReader == true) readerView.smoothScrollByViewport(direction)
        }
        stopObservingPhoneBattery?.invoke()
        stopObservingPhoneBattery = PhoneBatteryController.observe(::updateStatusRow)
        removeCallbacks(statusRowTicker)
        statusRowTicker.run()
    }

    override fun onDetachedFromWindow() {
        endInline()
        insetUnsubscribe?.invoke()
        insetUnsubscribe = null
        removeCallbacks(ticker)
        removeCallbacks(statusRowTicker)
        invalidatePendingListLayout()
        cancelInkPresentation()
        inkPresentationSurfaceId = null
        inkPresentationGeneration = null
        stopObservingReaderScroll?.invoke()
        stopObservingReaderScroll = null
        stopObservingPhoneBattery?.invoke()
        stopObservingPhoneBattery = null
        SurfaceController.detachInkRenderer(inkView)
        super.onDetachedFromWindow()
    }

    /** Phone charge, the date, and the glasses' own charge — refreshed independently of the surface. */
    private fun updateStatusRow() {
        val phone = PhoneBatteryController.reading()
        phoneBatteryStatusView.text = phone?.let { "HP ${batteryLabel(it.level, it.charging)}" }.orEmpty()
        phoneBatteryStatusView.visibility = visibleIf(phone != null)
        dateStatusView.text = statusDateFormat.format(Date())
        val glasses = readGlassesBattery()
        glassesBatteryStatusView.text = glasses?.let { (level, charging) -> batteryLabel(level, charging) }
            .orEmpty()
        glassesBatteryStatusView.visibility = visibleIf(glasses != null)
    }

    private fun batteryLabel(level: Int, charging: Boolean): String =
        "$level%" + if (charging) "+" else ""

    /**
     * The sticky [Intent.ACTION_BATTERY_CHANGED] broadcast always has a last
     * value queued, so a null receiver reads it once without registering a
     * live one to unregister later — the same trick a status-bar clock uses.
     */
    private fun readGlassesBattery(): Pair<Int, Boolean>? {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return (level * 100 / scale) to charging
    }

    private fun applyHudTopInset(value: Int) {
        hudTopInsetDp = HudTopInset.sanitize(value)
        if (surface?.isInk == true) {
            applyInkCardHost()
        } else {
            applyFullBleedHost()
        }
        requestLayout()
    }

    private fun renderNow(surface: NexusSurface) {
        invalidatePendingListLayout()
        heldUnderBand = false
        if (seeThrough) {
            seeThrough = false
            statusRowView.visibility = VISIBLE
        }
        when (surfaceHudMode(surface.kind)) {
            SurfaceHudMode.INK_CARD -> applyInkCardHost()
            SurfaceHudMode.FULL_BLEED -> applyFullBleedHost()
        }
        panelView.visibility = visibleIf(!surface.isInk)
        titleView.text = surface.title
        titleView.visibility = visibleIf(surface.title.isNotBlank())
        subtitleView.text = surface.subtitle
        subtitleView.visibility = visibleIf(surface.subtitle.isNotBlank())
        footerView.text = surface.footer
        footerView.visibility = visibleIf(surface.footer.isNotBlank())

        if (!surface.isInk) hideInk()
        if (surface.kind != NexusSurface.KIND_CARD || surface.editable == null) {
            editView.visibility = GONE
            // A bare card follows its owner's band too: keep that subscription rather than
            // dropping it only for renderCard to take a fresh one, which answers at once.
            endInline(keepWatching = surface.isBareCard())
        }
        when {
            surface.isInk -> renderInk(surface)
            surface.isImage -> renderImage(surface)
            surface.isMedia -> renderMedia(surface)
            surface.isReader -> renderReader(surface)
            surface.isTimed -> renderTimed(surface)
            surface.kind == NexusSurface.KIND_CARD && surface.editable != null ->
                renderEditableCard(surface, surface.editable)
            else -> renderCard(surface)
        }
    }

    /**
     * A card with one bounded editable field instead of a read-only body. The
     * glasses' own system IME ([RemoteInputController]) activates the normal
     * Android way once this field takes focus — nothing new to wire there.
     * Submitting/cancelling reports back once via
     * [SurfaceController.forwardSurfaceText]; see [EditableSurfaceContract].
     */
    private fun renderEditableCard(surface: NexusSurface, editable: EditableSurfaceField) {
        hideReader()
        mediaView.visibility = GONE
        imageView.visibility = GONE
        previousView.visibility = GONE
        nextView.visibility = GONE
        currentView.visibility = GONE
        boardView.visibility = GONE
        // A plugin that reuses one surfaceId for every editable prompt it ever
        // shows — Agents' single board surface doubles as its reply field —
        // means surfaceId alone cannot tell "still the same compose" from "a
        // new one that happens to reuse the id". Having stopped being the
        // visible editable field in between is what actually marks a fresh
        // prompt; same surfaceId while never leaving still means keep typing.
        val reopened = editView.visibility != VISIBLE || surface.surfaceId != lastEditableSurfaceId
        editView.visibility = VISIBLE
        editView.hint = editable.placeholder.orEmpty()
        if (reopened && editView.text?.toString() != editable.initialText.orEmpty()) {
            editView.setText(editable.initialText.orEmpty())
            editView.setSelection(editView.text?.length ?: 0)
        }
        lastEditableSurfaceId = surface.surfaceId
        val inline = editableDrawsInNotice(
            inNotice = editable.inNotice,
            surfaceOwnerPluginId = surface.ownerPluginId,
            visibleNoticeOwnerPluginId = NoticeController.visibleNotice()?.ownerPluginId,
        )
        applyInline(if (inline) surface.ownerPluginId else null, editable.placeholder.orEmpty())
        if (editable.inNotice) watchNotice() else stopWatchingNotice()
        editView.requestFocus()
    }

    /**
     * Hands the field's drawing to its owner's band, or takes it back. Drawn
     * black rather than hidden: a GONE field loses its focus, and with it the
     * IME the wearer is typing through.
     */
    private fun applyInline(owner: String?, placeholder: String) {
        removeCallbacks(inlineFallback)
        val previous = inlineOwner
        inlineOwner = owner
        inlinePlaceholder = placeholder
        if (previous != null && previous != owner) NoticeComposeMirror.clear(previous)
        val inline = owner != null
        editView.alpha = if (inline) 0f else 1f
        applySeeThrough(inline)
        if (inline) mirrorCompose()
    }

    /**
     * Steps entirely out of the way — chrome and background — while the owner's band carries
     * this surface, so the band sits over whatever the wearer was looking at rather than over a
     * black screen. The window itself is translucent on both display paths; only this view's own
     * fill ever made it opaque.
     */
    private fun applySeeThrough(on: Boolean) {
        seeThrough = on
        statusRowView.visibility = if (on) GONE else VISIBLE
        panelView.background = if (on) null else SurfaceChrome.panel()
        if (on) {
            background = null
            titleView.visibility = GONE
            subtitleView.visibility = GONE
            footerView.visibility = GONE
        } else if (surface?.isInk != true) {
            applyFullBleedHost()
        }
    }

    private fun endInline(keepWatching: Boolean = false) {
        if (!keepWatching) stopWatchingNotice()
        if (inlineOwner != null) applyInline(null, "")
    }

    private fun mirrorCompose() {
        val owner = inlineOwner ?: return
        NoticeComposeMirror.publish(
            NoticeComposeMirror.Line(
                ownerPluginId = owner,
                text = editView.text?.toString().orEmpty(),
                cursor = editView.selectionEnd.coerceAtLeast(0),
                placeholder = inlinePlaceholder,
            ),
        )
    }

    /**
     * Follows the owner's band for as long as an in-notice field or a bare card
     * is up. Moving into a band that arrives is immediate. Moving out waits: the
     * band closing on Back or its own lifetime is normally followed by the plugin
     * hiding this surface, and showing the card for that instant would only flash
     * it. A surface nobody hides still comes back into view, rather than typing
     * on unseen.
     */
    private fun watchNotice() {
        if (stopWatchingNotice != null) return
        stopWatchingNotice = NoticeController.observe { notice ->
            val active = surface ?: return@observe
            val owner = notice?.ownerPluginId
            val editable = active.editable
            val wanted = if (editable != null) {
                editableDrawsInNotice(editable.inNotice, active.ownerPluginId, owner)
            } else {
                cardHoldsUnderBand(active, owner)
            }
            val drawn = if (editable != null) inlineOwner != null else heldUnderBand
            when {
                wanted == drawn -> removeCallbacks(inlineFallback)
                wanted -> renderNow(active)
                else -> {
                    removeCallbacks(inlineFallback)
                    postDelayed(inlineFallback, INLINE_FALLBACK_DELAY_MS)
                }
            }
        }
    }

    private fun stopWatchingNotice() {
        removeCallbacks(inlineFallback)
        stopWatchingNotice?.invoke()
        stopWatchingNotice = null
    }

    private fun renderInk(surface: NexusSurface) {
        hideReader()
        mediaView.visibility = GONE
        imageView.visibility = GONE
        titleView.visibility = GONE
        subtitleView.visibility = GONE
        previousView.visibility = GONE
        currentView.visibility = GONE
        boardView.visibility = GONE
        nextView.visibility = GONE
        footerView.visibility = GONE
        inkCardHost.visibility = VISIBLE
        inkView.visibility = VISIBLE
        SurfaceController.attachInkRenderer(inkView, surface.ink?.debugActions == true)
    }

    private fun hideInk() {
        SurfaceController.detachInkRenderer(inkView)
        inkView.visibility = GONE
        inkCardHost.visibility = GONE
        resetInkReveal()
    }

    private fun applyFullBleedHost() {
        applyHostChrome(SurfaceHudMode.FULL_BLEED)
    }

    private fun applyHostChrome(mode: SurfaceHudMode) {
        val chrome = surfaceHostChrome(mode, hudTopInsetDp)
        val fill = chrome.backgroundColor
        // The top-inset observer re-applies this chrome whenever the view attaches, which for a
        // freshly created surface activity is after the band already took the field.
        if (fill == null || seeThrough) background = null else setBackgroundColor(fill)
        if (mode == SurfaceHudMode.INK_CARD) {
            setPadding(0, 0, 0, 0)
        } else {
            // The app safe area: safe-x / safe-y, the top pushed down by the synced HUD inset.
            setPadding(
                RokidHudTokens.SAFE_X,
                RokidHudTokens.SAFE_Y + HudTopInset.toPx(context, hudTopInsetDp),
                RokidHudTokens.SAFE_X,
                RokidHudTokens.SAFE_Y,
            )
        }
    }

    private fun applyInkCardHost() {
        applyHostChrome(SurfaceHudMode.INK_CARD)
        val metrics = resources.displayMetrics
        val topPx = HudBandGeometry.topPx(context, hudTopInsetDp)
        val layout = (inkCardHost.layoutParams as? LayoutParams)
            ?: LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        layout.width = HudBandGeometry.widthPx(metrics.widthPixels)
        layout.height = LayoutParams.WRAP_CONTENT
        layout.weight = 0f
        layout.gravity = Gravity.CENTER_HORIZONTAL
        layout.topMargin = topPx
        inkCardHost.layoutParams = layout
        inkCardHost.heightCapPx = HudBandGeometry.availableHeightPx(
            displayHeightPx = metrics.heightPixels,
            topPx = topPx,
        )
    }

    private fun prepareInkPresentation(surface: NexusSurface) {
        inkPresentationSurfaceId = surface.surfaceId
        inkPresentationGeneration =
            SurfaceController.inkPresentationGeneration(surface.surfaceId, surface.seq)
        if (!SurfaceController.isInkPresentationPending(surface.surfaceId, surface.seq)) {
            resetInkReveal()
            return
        }
        val notice = runCatching { NoticeController.prepareInkMorph(surface.ownerPluginId) }
            .onFailure { logError("Ink card morph notice preparation failed", it) }
            .getOrNull()
        if (notice == null) {
            resetInkReveal()
            val fallback = PendingInkFallback(surface.surfaceId, surface.seq)
            pendingInkFallback = fallback
            scheduleInkReadyFallback(fallback)
            return
        }

        val morph = ActiveInkMorph(surface.surfaceId, surface.seq, notice)
        activeInkMorph = morph
        inkClipMotion.snapTo(notice.bandHeightPx.toFloat())
        inkAlphaMotion.snapTo(0f)
        val timeout = Runnable {
            if (activeInkMorph === morph) {
                commitInkMorphInstant(morph, reason = "first_frame_timeout", releaseGate = true)
            }
        }
        inkMorphTimeout = timeout
        postDelayed(timeout, 500L)
    }

    private fun onInkFirstFrame(surfaceId: String, seq: Long) {
        if (inkPresentationSurfaceId == surfaceId) clearInkMorphTimeout()
        pendingInkFallback?.takeIf { it.surfaceId == surfaceId }?.let {
            pendingInkFallback = null
            logMorphDecision(
                decision = "instant",
                reason = "no_matching_band",
                bandPx = 0,
                cardPx = inkCardHost.height,
            )
            return
        }
        val morph = activeInkMorph?.takeIf {
            it.surfaceId == surfaceId && it.state.phase == InkCardMorphPhase.WAITING_FOR_FIRST_FRAME
        } ?: return
        morph.seq = seq
        try {
            clearInkMorphTimeout()
            val cardHeightPx = inkCardHost.height
            if (cardHeightPx <= 0 || morph.notice.bandHeightPx <= 0) {
                commitInkMorphInstant(morph, reason = "invalid_bounds", releaseGate = false)
                return
            }
            if (!HudMotion.enabled) {
                commitInkMorphInstant(morph, reason = "motion_disabled", releaseGate = false)
                return
            }
            if (cardHeightPx == morph.notice.bandHeightPx) {
                commitInkMorphInstant(morph, reason = "no_clip_delta", releaseGate = false)
                return
            }
            if (!morph.state.startAnimation()) return

            inkClipMotion.snapTo(morph.notice.bandHeightPx.toFloat())
            inkAlphaMotion.snapTo(0f)
            inkAlphaMotion.animateTo(1f, HudMotion.MICRO_MS, HudMotion.enter)
            if (!NoticeController.startInkMorphFade(morph.notice)) {
                commitInkMorphInstant(morph, reason = "notice_unavailable", releaseGate = false)
                return
            }
            if (!NoticeController.closeForInkMorph(morph.notice)) {
                commitInkMorphInstant(morph, reason = "notice_replaced", releaseGate = false)
                return
            }
            inkClipMotion.animateTo(
                target = cardHeightPx.toFloat(),
                durationMs = HudMotion.STANDARD_MS,
                interpolator = HudMotion.enter,
            ) {
                completeInkMorph(morph, cardHeightPx)
            }
            logMorphDecision(
                decision = "animate",
                reason = "first_frame",
                bandPx = morph.notice.bandHeightPx,
                cardPx = cardHeightPx,
            )
        } catch (error: Throwable) {
            logError("Ink card morph animator unavailable", error)
            commitInkMorphInstant(morph, reason = "animator_unavailable", releaseGate = false)
        }
    }

    private fun completeInkMorph(morph: ActiveInkMorph, cardHeightPx: Int) {
        if (activeInkMorph !== morph || !morph.state.completeAnimation()) return
        inkClipMotion.snapTo(cardHeightPx.toFloat())
        inkAlphaMotion.snapTo(1f)
        inkCardHost.revealFully()
        activeInkMorph = null
        runCatching { NoticeController.finishInkMorph(morph.notice) }
            .onFailure { logError("Ink card morph notice teardown failed", it) }
    }

    private fun commitInkMorphInstant(
        morph: ActiveInkMorph,
        reason: String,
        releaseGate: Boolean,
    ) {
        if (activeInkMorph !== morph || !morph.state.commitInstant()) return
        clearInkMorphTimeout()
        inkClipMotion.cancel()
        inkAlphaMotion.cancel()
        inkCardHost.revealFully()
        inkView.alpha = 1f
        logMorphDecision(
            decision = "instant",
            reason = reason,
            bandPx = morph.notice.bandHeightPx,
            cardPx = inkCardHost.height,
        )
        activeInkMorph = null
        runCatching { NoticeController.closeForInkMorph(morph.notice) }
            .onFailure { logError("Ink card morph notice close failed", it) }
        runCatching { NoticeController.finishInkMorph(morph.notice) }
            .onFailure { logError("Ink card morph notice teardown failed", it) }
        if (releaseGate) {
            SurfaceController.onInkFirstFrameTimeout(morph.surfaceId, morph.seq)
        }
    }

    private fun cancelInkPresentation() {
        clearInkMorphTimeout()
        pendingInkFallback = null
        val morph = activeInkMorph
        activeInkMorph = null
        inkClipMotion.cancel()
        inkAlphaMotion.cancel()
        if (morph != null) {
            morph.state.cancel()
            runCatching { NoticeController.cancelInkMorph(morph.notice) }
                .onFailure { logError("Ink card morph notice restore failed", it) }
        }
        resetInkReveal()
    }

    private fun clearInkMorphTimeout() {
        inkMorphTimeout?.let(::removeCallbacks)
        inkMorphTimeout = null
    }

    private fun scheduleInkReadyFallback(fallback: PendingInkFallback) {
        val timeout = Runnable {
            if (pendingInkFallback !== fallback) return@Runnable
            val current = surface?.takeIf { it.isInk && it.surfaceId == fallback.surfaceId }
                ?: return@Runnable
            fallback.seq = current.seq
            pendingInkFallback = null
            logMorphDecision(
                decision = "instant",
                reason = "no_matching_band",
                bandPx = 0,
                cardPx = inkCardHost.height,
            )
            SurfaceController.onInkFirstFrameTimeout(current.surfaceId, current.seq)
        }
        inkMorphTimeout = timeout
        postDelayed(timeout, 500L)
    }

    private fun resetInkReveal() {
        inkClipMotion.cancel()
        inkAlphaMotion.cancel()
        inkCardHost.revealFully()
        inkView.alpha = 1f
    }

    private fun logMorphDecision(
        decision: String,
        reason: String,
        bandPx: Int,
        cardPx: Int,
    ) {
        log("morph decision=$decision reason=$reason bandPx=$bandPx cardPx=$cardPx")
    }

    private fun renderTimed(surface: NexusSurface) {
        // Timed lines (lyrics) show one big centered line; cards pack a board.
        hideReader()
        mediaView.visibility = GONE
        imageView.visibility = GONE
        boardView.visibility = GONE
        currentView.visibility = VISIBLE
        // Long lyric lines must never lose their tail: shrink to fit instead of clipping.
        currentView.maxLines = TIMED_BODY_MAX_LINES
        currentView.typeface = RokidHudTokens.displayTypeface()
        currentView.setTextColor(RokidHudTokens.FOCUS)
        fitBody()
        currentView.gravity = Gravity.CENTER
        currentView.textAlignment = TEXT_ALIGNMENT_CENTER
        val index = currentTimedIndex(surface)
        previousView.text = surface.timedLines.getOrNull(index - 1)?.text.orEmpty()
        previousView.visibility = visibleIf(previousView.text.isNotBlank())
        currentView.text = surface.timedLines.getOrNull(index)?.text
            ?.takeIf { it.isNotBlank() }
            ?: surface.timedLines.firstOrNull()?.text
            ?: ""
        nextView.text = surface.timedLines.getOrNull(index + 1)?.text.orEmpty()
        nextView.visibility = visibleIf(nextView.text.isNotBlank())
    }

    /**
     * Long bodies (lyrics, assistant replies) must never lose their tail: the text takes the
     * largest of the three design sizes, `display`, `heading` or `body`, that fits.
     */
    private fun fitBody() {
        currentView.setAutoSizeTextTypeUniformWithPresetSizes(
            BODY_PRESET_SIZES_PX,
            TypedValue.COMPLEX_UNIT_PX,
        )
    }

    private fun renderCard(surface: NexusSurface) {
        hideReader()
        mediaView.visibility = GONE
        imageView.visibility = GONE
        previousView.visibility = GONE
        nextView.visibility = GONE
        if (surface.isBareCard()) {
            // Settled before subscribing: the observer answers at once, and it must find this
            // render already matching the band, not re-render into another subscription.
            heldUnderBand = cardHoldsUnderBand(surface, NoticeController.visibleNotice()?.ownerPluginId)
            watchNotice()
            if (heldUnderBand) {
                currentView.visibility = GONE
                boardView.visibility = GONE
                applySeeThrough(true)
                return
            }
        }
        val rows = surface.rows.filter { it.text.isNotBlank() || it.isStructured }
        when {
            rows.any { it.isListRow } -> renderList(rows)
            rows.any { it.isStructured } -> renderBoard(rows)
            else -> renderPlainCard(rows)
        }
    }

    /**
     * Attention-ordered list: a selection rail, per-row weight, and an optional
     * secondary line. Unlike the departure board there is no chip column — the
     * hierarchy is carried by weight and position, which is what a list of live
     * things (agent sessions, conversations) actually needs.
     */
    private fun renderList(rows: List<SurfaceRow>) {
        currentView.visibility = GONE
        boardView.visibility = VISIBLE
        boardView.gravity = Gravity.TOP
        boardView.removeAllViews()
        val rowViews = rows.mapIndexed { index, row ->
            val view = if (row.tone == SurfaceRow.TONE_BODY) bodyRow(row) else listRow(row)
            val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) {
                    topMargin = if (row.tone == SurfaceRow.TONE_BODY) {
                        RokidHudTokens.SPACE_2
                    } else {
                        RokidHudTokens.SPACE_1
                    }
                }
            }
            view to params
        }
        // The board's height is weight-fixed, but the chrome above it is not: a
        // title appearing on this render moves the board's bounds only at the
        // next layout pass. Window against whatever size is current, then again
        // whenever the bounds actually change — the size key keeps the listener
        // from looping on the layout its own re-attachment triggers.
        var windowedSizeKey = 0L
        fun windowNow() {
            val width = boardView.width - boardView.paddingLeft - boardView.paddingRight
            val height = boardView.height - boardView.paddingTop - boardView.paddingBottom
            if (width <= 0 || height <= 0) return
            val sizeKey = (width.toLong() shl 32) or height.toLong()
            if (sizeKey == windowedSizeKey) return
            windowedSizeKey = sizeKey
            attachListWindow(rows, rowViews)
        }

        val generation = listRenderGeneration
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // Never attach from inside the layout pass: children added mid-layout
            // sit in the tree unmeasured and the board draws empty (seen on
            // device, first render of a fresh surface window). The post lands
            // after the traversal, where addView schedules a clean one.
            boardView.post {
                if (generation == listRenderGeneration) windowNow()
            }
        }
        pendingListLayoutListener = listener
        boardView.addOnLayoutChangeListener(listener)
        windowNow()
        if (windowedSizeKey == 0L) boardView.requestLayout()
    }

    private fun attachListWindow(
        rows: List<SurfaceRow>,
        rowViews: List<Pair<View, LayoutParams>>,
    ) {
        val viewportWidth = (boardView.width - boardView.paddingLeft - boardView.paddingRight)
            .coerceAtLeast(1)
        val viewportHeight = (boardView.height - boardView.paddingTop - boardView.paddingBottom)
            .coerceAtLeast(0)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(viewportWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val rowOuterHeights = rowViews.map { (view, params) ->
            view.measure(widthSpec, heightSpec)
            view.measuredHeight + params.topMargin
        }
        val indicatorProbe = listOverflowIndicator(up = false, hiddenCount = rows.size)
        indicatorProbe.measure(widthSpec, heightSpec)
        val selectedIndex = rows.indexOfFirst { it.selected }.takeIf { it >= 0 }
        val window = surfaceListViewport(
            rowOuterHeightsPx = rowOuterHeights,
            viewportHeightPx = viewportHeight,
            selectedIndex = selectedIndex,
            indicatorHeightPx = indicatorProbe.measuredHeight,
        )

        boardView.removeAllViews()
        if (window.hiddenAbove > 0) {
            boardView.addView(
                listOverflowIndicator(up = true, hiddenCount = window.hiddenAbove),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
        for (index in window.firstRow until window.lastRowExclusive) {
            val (view, params) = rowViews[index]
            boardView.addView(view, params)
        }
        if (window.hiddenBelow > 0) {
            boardView.addView(
                listOverflowIndicator(up = false, hiddenCount = window.hiddenBelow),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
    }

    /** A chevron and the count of the rows out of view, in `mono` at `text-secondary`. */
    private fun listOverflowIndicator(up: Boolean, hiddenCount: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                HudIconView(
                    context,
                    if (up) HudIconView.Kind.CHEVRON_UP else HudIconView.Kind.CHEVRON_DOWN,
                ).apply { setIntensity(RokidHudTokens.TEXT_SECONDARY) },
                LayoutParams(RokidHudTokens.ICON_SM, RokidHudTokens.ICON_SM),
            )
            addView(
                SurfaceType.mono(TextView(context)).apply { text = hiddenCount.toString() },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = RokidHudTokens.SPACE_1
                },
            )
        }

    private fun invalidatePendingListLayout() {
        listRenderGeneration += 1
        pendingListLayoutListener?.let(boardView::removeOnLayoutChangeListener)
        pendingListLayoutListener = null
    }

    /** `ListItem`: 32 px minimum, `body` title, optional `body-small` line, `data` value at the end. */
    private fun listRow(row: SurfaceRow): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = RokidHudTokens.LIST_ITEM_HEIGHT
            setPadding(RokidHudTokens.SPACE_2, RokidHudTokens.SPACE_1, RokidHudTokens.SPACE_2, RokidHudTokens.SPACE_1)
            background = if (row.selected) SurfaceChrome.focused() else null
            if (row.tone == SurfaceRow.TONE_ALERT) {
                // `Status` warn: the alert icon carries "needs the wearer", not a color.
                addView(
                    HudIconView(context, HudIconView.Kind.ALERT).apply { setIntensity(toneColor(row)) },
                    LayoutParams(RokidHudTokens.ICON_SM, RokidHudTokens.ICON_SM).apply {
                        marginEnd = RokidHudTokens.SPACE_2
                    },
                )
            }
            addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(
                        SurfaceType.body(TextView(context), toneColor(row)).apply {
                            text = row.text
                            // Never marquee a list title: a scrolling row is unreadable at a glance.
                            if (row.isEmphasised) typeface = RokidHudTokens.headingTypeface()
                        },
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                    )
                    if (row.sub.isNotBlank()) {
                        addView(
                            SurfaceType.bodySmall(TextView(context)).apply { text = row.sub },
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                        )
                    }
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
            if (row.trail.isNotEmpty()) {
                addView(
                    listMetaView(row),
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        marginStart = RokidHudTokens.SPACE_2
                    },
                )
            }
            if (row.badge.isNotBlank()) {
                addView(
                    SurfaceType.data(TextView(context), toneColor(row)).apply { text = row.badge },
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        marginStart = RokidHudTokens.SPACE_2
                    },
                )
            }
        }

    /** Prose row: a fixed `label` column, then wrapped `body` text: a conversation, not a table. */
    private fun bodyRow(row: SurfaceRow): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(RokidHudTokens.SPACE_2, RokidHudTokens.SPACE_1, RokidHudTokens.SPACE_2, RokidHudTokens.SPACE_1)
            background = if (row.selected) SurfaceChrome.focused() else null
            if (row.badge.isNotBlank()) {
                addView(
                    SurfaceType.label(TextView(context)).apply {
                        // The label column's line box is 14 px against the body's 20.
                        text = row.badge.uppercase(Locale.ROOT)
                        setPadding(0, (SurfaceType.BODY_LINE - SurfaceType.LABEL_LINE) / 2, 0, 0)
                    },
                    LayoutParams(LIST_LABEL_WIDTH_PX, LayoutParams.WRAP_CONTENT),
                )
            }
            addView(
                SurfaceType.wrap(
                    SurfaceType.body(TextView(context), if (row.selected) RokidHudTokens.FOCUS else RokidHudTokens.TEXT_PRIMARY),
                    LIST_BODY_MAX_LINES,
                ).apply { text = row.text },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
        }

    /** Status token first, in `data`; the rest (age, counters) in `mono` at `text-secondary`. */
    private fun listMetaView(row: SurfaceRow): TextView =
        SurfaceType.data(TextView(context), toneColor(row)).apply {
            text = SpannableStringBuilder().apply {
                append(row.trail.first())
                row.trail.drop(1).forEach { token ->
                    val start = length
                    append("  ")
                    append(token)
                    setSpan(
                        ForegroundColorSpan(RokidHudTokens.TEXT_SECONDARY),
                        start,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        AbsoluteSizeSpan(RokidHudTokens.MONO_TEXT_SIZE.toInt()),
                        start,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }

    private fun toneColor(row: SurfaceRow): Int = when {
        row.selected -> RokidHudTokens.FOCUS
        row.tone == SurfaceRow.TONE_DIM -> RokidHudTokens.TEXT_SECONDARY
        else -> RokidHudTokens.TEXT_PRIMARY
    }

    private fun renderMedia(surface: NexusSurface) {
        hideReader()
        imageView.visibility = GONE
        previousView.visibility = GONE
        currentView.visibility = GONE
        boardView.visibility = GONE
        nextView.visibility = GONE
        mediaView.visibility = VISIBLE
        mediaView.render(surface)
    }

    private fun renderImage(surface: NexusSurface) {
        hideReader()
        mediaView.visibility = GONE
        previousView.visibility = GONE
        currentView.visibility = GONE
        boardView.visibility = GONE
        nextView.visibility = GONE
        imageView.visibility = VISIBLE
        imageView.render(surface)
    }

    private fun renderReader(surface: NexusSurface) {
        mediaView.visibility = GONE
        imageView.visibility = GONE
        previousView.visibility = GONE
        currentView.visibility = GONE
        boardView.visibility = GONE
        nextView.visibility = GONE
        readerView.visibility = VISIBLE
        readerView.render(surface.surfaceId, surface.readerSegments, surface.readerAnchor)
    }

    private fun hideReader() {
        if (readerView.visibility != GONE) readerView.clear()
        readerView.visibility = GONE
    }

    private fun renderPlainCard(rows: List<SurfaceRow>) {
        boardView.visibility = GONE
        currentView.visibility = VISIBLE
        // Long card bodies (assistant replies, article text) must never lose
        // their tail: shrink to fit instead of clipping, like timed lines do.
        currentView.typeface = RokidHudTokens.bodyTypeface()
        currentView.setTextColor(RokidHudTokens.TEXT_PRIMARY)
        fitBody()
        currentView.maxLines = CARD_BODY_MAX_LINES
        // Plain cards align as a left block; per-line centering scatters the columns.
        currentView.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        currentView.textAlignment = TEXT_ALIGNMENT_VIEW_START
        currentView.text = rows.joinToString("\n") { it.text }
    }

    /** Departure-board rows: route badge, destination, wait times — one row each. */
    private fun renderBoard(rows: List<SurfaceRow>) {
        currentView.visibility = GONE
        boardView.visibility = VISIBLE
        // Both renderers share this container, so each states its own alignment
        // rather than inheriting whatever the last surface left behind.
        boardView.gravity = Gravity.CENTER_VERTICAL
        boardView.removeAllViews()
        rows.forEachIndexed { index, row ->
            boardView.addView(
                boardRow(row),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) topMargin = RokidHudTokens.SPACE_3
                },
            )
        }
    }

    private fun boardRow(row: SurfaceRow): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = RokidHudTokens.LIST_ITEM_HEIGHT
            addView(
                badgeView(row.badge),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
            addView(
                SurfaceType.marquee(SurfaceType.body(TextView(context))).apply { text = row.text },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = RokidHudTokens.SPACE_3
                },
            )
            if (row.trail.isNotEmpty()) {
                addView(
                    trailView(row.trail),
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        marginStart = RokidHudTokens.SPACE_3
                    },
                )
            }
        }

    /** The route as a control outline (`line-control`, `radius-control`) around a `data` value. */
    private fun badgeView(badge: String): TextView =
        SurfaceType.data(TextView(context)).apply {
            text = badge.ifBlank { "\u00b7" }
            gravity = Gravity.CENTER
            minWidth = BOARD_BADGE_MIN_WIDTH_PX
            setPadding(RokidHudTokens.SPACE_2, RokidHudTokens.SPACE_1, RokidHudTokens.SPACE_2, RokidHudTokens.SPACE_1)
            background = SurfaceChrome.control()
        }

    /** The next departure in `data`, the following ones in `mono` at `text-secondary`. */
    private fun trailView(trail: List<String>): TextView =
        SurfaceType.data(TextView(context)).apply {
            text = SpannableStringBuilder().apply {
                append(trail.first())
                trail.drop(1).forEach { token ->
                    val start = length
                    append("  ")
                    append(token)
                    setSpan(
                        ForegroundColorSpan(RokidHudTokens.TEXT_SECONDARY),
                        start,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        AbsoluteSizeSpan(RokidHudTokens.MONO_TEXT_SIZE.toInt()),
                        start,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }

    private fun currentTimedIndex(surface: NexusSurface): Int {
        val position = surface.anchor?.effectivePositionMs(SystemClock.elapsedRealtime()) ?: 0L
        var candidate = 0
        for (index in surface.timedLines.indices) {
            if (surface.timedLines[index].timeMs <= position) {
                candidate = index
            } else {
                break
            }
        }
        return candidate.coerceIn(0, (surface.timedLines.size - 1).coerceAtLeast(0))
    }

    private fun clear() {
        invalidatePendingListLayout()
        applyFullBleedHost()
        titleView.text = ""
        subtitleView.text = ""
        previousView.text = ""
        currentView.text = ""
        nextView.text = ""
        footerView.text = ""
        mediaView.clear()
        mediaView.visibility = GONE
        imageView.render(null)
        imageView.visibility = GONE
        readerView.clear()
        readerView.visibility = GONE
        hideInk()
        boardView.removeAllViews()
        boardView.visibility = GONE
        editView.setText("")
        editView.visibility = GONE
        endInline()
        lastEditableSurfaceId = null
        currentView.visibility = VISIBLE
    }

    private fun visibleIf(condition: Boolean): Int =
        if (condition) View.VISIBLE else View.GONE

    private fun shouldTick(surface: NexusSurface): Boolean =
        surface.anchor?.playing == true && (surface.isTimed || surface.isMedia)

    private fun tickDelay(surface: NexusSurface): Long =
        if (surface.isMedia) MEDIA_TICK_MS else TICK_MS

    private companion object {
        private const val TICK_MS = 100L
        private const val MEDIA_TICK_MS = 500L
        private const val STATUS_ROW_TICK_MS = 30_000L
        /** Long enough for the plugin to hide its field after its band closes; see watchNotice. */
        private const val INLINE_FALLBACK_DELAY_MS = 1_500L

        /** `display`, `heading`, `body`: the sizes a card body or a lyric line may take, largest first fitting. */
        private val BODY_PRESET_SIZES_PX = intArrayOf(
            RokidHudTokens.BODY_TEXT_SIZE.toInt(),
            RokidHudTokens.HEADING_TEXT_SIZE.toInt(),
            RokidHudTokens.DISPLAY_TEXT_SIZE.toInt(),
        )
        private const val CARD_BODY_MAX_LINES = 15
        private const val TIMED_BODY_MAX_LINES = 5
        private const val BOARD_BADGE_MIN_WIDTH_PX = 48

        // Conversation rows: fixed speaker label, wrapped prose.
        private const val LIST_LABEL_WIDTH_PX = 56
        private const val LIST_BODY_MAX_LINES = 3
    }
}

/** Reports caret moves as well as edits: a TextWatcher never sees an arrow key. */
private class CaretReportingEditText(context: Context) : EditText(context) {
    var onCaretMoved: (() -> Unit)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onCaretMoved?.invoke()
    }
}
