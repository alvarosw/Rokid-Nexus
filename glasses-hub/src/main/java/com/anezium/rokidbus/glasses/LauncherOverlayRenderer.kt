package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme

/**
 * What [LauncherOverlayRenderer] needs from either launcher rendering — today's list
 * ([LauncherMenuView]) or the grid ([GridLauncherView]). Selection, key handling, and the
 * open/close sequence live in [LauncherOverlayRenderer] only; a content view never reaches into
 * the bus itself.
 */
internal interface LauncherContentView {
    fun render(entries: List<GlassesHub.LauncherEntry>, selectedIndex: Int)
    fun setHudTopInsetDp(value: Int)
}

object LauncherOverlayRenderer {
    private const val KEYCODE_PROG_BLUE = 186
    private const val RING_KEYCODE_TAP = 85
    private const val RING_KEYCODE_FORWARD = 87
    private const val RING_KEYCODE_BACKWARD = 88

    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var root: LauncherOverlayRoot? = null
    private var unsubscribeLauncher: (() -> Unit)? = null
    private var insetUnsubscribe: (() -> Unit)? = null
    private var launcherEntries: List<GlassesHub.LauncherEntry> = emptyList()
    private var selectedIndex = 0
    private var lastOpenedEntryId: String? = null
    private val swipeDedupe = DpadPairDedupe()
    private val main = Handler(Looper.getMainLooper())
    private val ringTapPolicy = RingTapPolicy()
    private val ringTapExpiry = Runnable(::resolveRingTaps)
    private var hudTopInsetDp = 0

    fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        windowManager = service.getSystemService(WindowManager::class.java)
        insetUnsubscribe?.invoke()
        insetUnsubscribe = HudTopInset.observe(service, ::applyHudTopInset)
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service === service) {
            insetUnsubscribe?.invoke()
            insetUnsubscribe = null
            hide()
            this.service = null
            windowManager = null
        }
    }

    fun isShown(): Boolean = root != null

    fun show(): Boolean {
        val activeService = service ?: return false
        return show(activeService)
    }

    fun show(context: Context): Boolean {
        val activeService = service ?: return false
        launcherReturnCoordinator.clearPendingLauncherOpen()
        GlassesHub.start(activeService.applicationContext)
        val manager = windowManager ?: activeService.getSystemService(WindowManager::class.java) ?: return false
        val currentRoot = root ?: LauncherOverlayRoot(activeService).also { next ->
            next.setHudTopInsetDp(hudTopInsetDp)
            root = next
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT,
            )
            manager.addView(next, params)
            HudOverlayStack.reassert()
        }
        // Picked at show() time, per the roadmap: a mode flipped while the overlay is hidden
        // takes effect on the very next open, not mid-session.
        currentRoot.setMode(HudModeStore.isGridModeEnabled(activeService.applicationContext))
        if (unsubscribeLauncher == null) {
            unsubscribeLauncher = GlassesHub.observeLauncher { entries ->
                launcherEntries = entries
                selectedIndex = selectedIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
                root?.render(launcherEntries, selectedIndex)
            }
        }
        currentRoot.render(launcherEntries, selectedIndex)
        currentRoot.requestFocus()

        // Returning to the grid from the plugin we just opened: collapse the panel back down to
        // its tile instead of the launcher just reappearing whole. List mode never sets
        // lastOpenedEntryId's index here since currentRoot.contentView() is a GridLauncherView
        // only in grid mode, so this is a no-op for list.
        val returningEntryId = lastOpenedEntryId
        lastOpenedEntryId = null
        val returningIndex = returningEntryId?.let { id -> launcherEntries.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
        val gridContent = currentRoot.contentView() as? GridLauncherView
        if (returningIndex != null && gridContent != null) {
            currentRoot.post { gridContent.beginCloseTransition(returningIndex) {} }
        }

        log("Launcher overlay opened")
        RingFocusBroadcastCoordinator.setLauncherShown(activeService.applicationContext, shown = true)
        ActivityController.onLauncherVisibilityChanged()
        return true
    }

    private fun applyHudTopInset(value: Int) {
        hudTopInsetDp = HudTopInset.sanitize(value)
        root?.setHudTopInsetDp(hudTopInsetDp)
    }

    fun hide() {
        unsubscribeLauncher?.invoke()
        unsubscribeLauncher = null
        main.removeCallbacks(ringTapExpiry)
        ringTapPolicy.reset()
        val manager = windowManager
        val currentRoot = root
        if (currentRoot != null) {
            runCatching { manager?.removeView(currentRoot) }
            currentRoot.render(emptyList(), 0)
        }
        root = null
        service?.applicationContext?.let { context ->
            RingFocusBroadcastCoordinator.setLauncherShown(context, shown = false)
        }
        if (currentRoot == null) return
        log("Launcher overlay closed")
        ActivityController.onLauncherVisibilityChanged()
    }

    fun handleRingKey(keyCode: Int, eventTimeMs: Long): Boolean {
        if (root == null) return false
        when (keyCode) {
            RING_KEYCODE_FORWARD -> moveSelection(1)
            RING_KEYCODE_BACKWARD -> moveSelection(-1)
            RING_KEYCODE_TAP -> {
                ringTapPolicy.onTap(eventTimeMs)
                main.removeCallbacks(ringTapExpiry)
                main.postDelayed(ringTapExpiry, RingTapPolicy.DEFAULT_WINDOW_MS + 1L)
            }
        }
        return true
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (root == null) return false
        if (event.keyCode == KEYCODE_PROG_BLUE) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
            return true
        }

        when (swipeDedupe.onKey(event.keyCode, event.action, event.repeatCount, event.eventTime)) {
            DpadPairDedupe.Direction.FORWARD -> {
                moveSelection(1)
                return true
            }
            DpadPairDedupe.Direction.BACKWARD -> {
                moveSelection(-1)
                return true
            }
            null -> Unit
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> {
                openSelected()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                hide()
                true
            }
            else -> true
        }
    }

    private fun resolveRingTaps() {
        when (ringTapPolicy.resolveExpired(SystemClock.uptimeMillis())) {
            RingTapPolicy.Resolution.SINGLE -> openSelected()
            RingTapPolicy.Resolution.DOUBLE -> hide()
            RingTapPolicy.Resolution.IGNORE,
            null,
            -> Unit
        }
    }

    private fun moveSelection(delta: Int) {
        if (launcherEntries.isEmpty()) return
        selectedIndex = (selectedIndex + delta + launcherEntries.size) % launcherEntries.size
        root?.render(launcherEntries, selectedIndex)
    }

    private fun openSelected() {
        val entry = launcherEntries.getOrNull(selectedIndex) ?: return
        val gridContent = root?.contentView() as? GridLauncherView
        if (gridContent != null) {
            gridContent.beginOpenTransition(selectedIndex) { completeOpen(entry) }
        } else {
            completeOpen(entry)
        }
    }

    private fun completeOpen(entry: GlassesHub.LauncherEntry) {
        val result = GlassesHub.openLauncherEntry(entry.id)
        log("Launcher overlay open result: $result")
        if (result.startsWith("launcherOpen=true")) {
            lastOpenedEntryId = entry.id
            launcherReturnCoordinator.recordLauncherOpen(entry.id)
            if (GlassesHub.launcherEntryOpensSurface(entry.id)) {
                service?.applicationContext?.let(RingFocusBroadcastCoordinator::beginSurfaceHandoff)
            }
            hide()
        }
    }

    private class LauncherOverlayRoot(context: Context) : FrameLayout(context) {
        private var content: View = LauncherMenuView(context)
        private var gridMode = false
        private var lastEntries: List<GlassesHub.LauncherEntry> = emptyList()
        private var lastSelectedIndex = 0
        private var hudTopInsetDp = 0

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        /** Both classes keep compiling and running; nothing is deleted, per the roadmap's §7. */
        fun setMode(gridMode: Boolean) {
            if (this.gridMode == gridMode) return
            this.gridMode = gridMode
            removeAllViews()
            content = if (gridMode) GridLauncherView(context) else LauncherMenuView(context)
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            (content as LauncherContentView).setHudTopInsetDp(hudTopInsetDp)
            (content as LauncherContentView).render(lastEntries, lastSelectedIndex)
        }

        fun render(entries: List<GlassesHub.LauncherEntry>, selectedIndex: Int) {
            lastEntries = entries
            lastSelectedIndex = selectedIndex
            (content as LauncherContentView).render(entries, selectedIndex)
        }

        fun setHudTopInsetDp(value: Int) {
            hudTopInsetDp = value
            (content as LauncherContentView).setHudTopInsetDp(value)
        }

        fun contentView(): View = content

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (NoticeKeyDispatcher.handleKeyEvent(event)) return true
            if (LauncherOverlayRenderer.handleKeyEvent(event)) return true
            return super.dispatchKeyEvent(event)
        }
    }

    private class LauncherMenuView(context: Context) : LinearLayout(context), LauncherContentView {
        private val countView = monoText(10.5f, BusTheme.dim)
        private val listView = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        private val emptyView = monoText(17f, BusTheme.dim).apply {
            text = "No phone plugins synced"
            gravity = Gravity.CENTER
        }
        private val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(
                listView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.TOP
            setBackgroundColor(BusTheme.glassesBg)
            setPadding(dp(18), dp(16), dp(18), dp(12))

            addView(monoText(12f, BusTheme.phosphor, bold = true).apply {
                text = "ROKID NEXUS"
                gravity = Gravity.CENTER_HORIZONTAL
            }, matchWrap())
            addView(gap(14))
            addView(monoText(24f, BusTheme.text, bold = true).apply {
                text = "Launcher"
                gravity = Gravity.CENTER_HORIZONTAL
            }, matchWrap())
            addView(gap(8))
            addView(countView.apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }, matchWrap())
            addView(gap(18))
            addView(monoText(10.5f, BusTheme.dim).apply {
                text = "PLUGINS"
                gravity = Gravity.CENTER_HORIZONTAL
            }, matchWrap())
            addView(gap(10))
            addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        override fun render(entries: List<GlassesHub.LauncherEntry>, selectedIndex: Int) {
            listView.removeAllViews()
            if (entries.isEmpty()) {
                countView.text = "Waiting for phone"
                listView.addView(
                    emptyView,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, scroll.height.coerceAtLeast(dp(260))),
                )
                return
            }

            countView.text = "${selectedIndex + 1}/${entries.size}"
            var selectedRow: View? = null
            entries.forEachIndexed { index, entry ->
                val row = pluginRow(entry, selected = index == selectedIndex)
                if (index == selectedIndex) selectedRow = row
                listView.addView(row, matchWrap().apply {
                    topMargin = if (index == 0) 0 else dp(8)
                })
            }
            selectedRow?.let { row ->
                row.post {
                    row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true)
                }
            }
        }

        override fun setHudTopInsetDp(value: Int) {
            setPadding(dp(18), dp(16 + HudTopInset.sanitize(value)), dp(18), dp(12))
            requestLayout()
        }

        private fun pluginRow(
            entry: GlassesHub.LauncherEntry,
            selected: Boolean,
        ): View {
            val icon = ImageView(context).apply {
                setImageDrawable(GlassesHub.launcherDrawable(context, entry))
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) }
            }
            val label = monoText(18f, if (selected) BusTheme.phosphor else BusTheme.text, bold = selected).apply {
                text = entry.displayName
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            return LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(12), 0, dp(12), 0)
                background = outline(selected)
                addView(icon)
                addView(label)
            }
        }

        private fun monoText(sizeSp: Float, color: Int, bold: Boolean = false): TextView =
            TextView(context).apply {
                textSize = sizeSp
                setTextColor(color)
                typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
                includeFontPadding = false
                isSingleLine = false
                setHorizontallyScrolling(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
                    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                }
            }

        private fun outline(selected: Boolean): GradientDrawable =
            GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dp(if (selected) 2 else 1), if (selected) BusTheme.phosphor else BusTheme.hairline)
                cornerRadius = dp(4).toFloat()
            }

        private fun gap(value: Int): View =
            View(context).apply {
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(value))
            }

        private fun matchWrap(): LayoutParams =
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        private fun dp(value: Int): Int =
            BusTheme.dp(context, value)
    }
}
