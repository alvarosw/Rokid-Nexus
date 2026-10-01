package com.anezium.rokidbus.glasses.hud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.anezium.rokidbus.client.ui.HudFrameLayout
import com.anezium.rokidbus.client.ui.HudGridMetrics
import com.anezium.rokidbus.client.ui.RokidHudTokens
import kotlin.math.roundToInt

/**
 * The frame both home renderings share, inside `HudFrame`'s safe area: a compact header, a body that
 * the subclass fills and scrolls by its own offset (no `ScrollView`, HARDWARE S5), a slot for the
 * failure `Status`, and a position indicator in the right safe margin.
 *
 * The body takes the height that is left on the 480x640 screen after the safe area, the header and
 * the status slot, in whole rows ([fitBody]); nothing is sized from the density, only from the
 * tokens, so it is the same 448 px wide at any density.
 */
internal abstract class HomeScreenView(
    context: Context,
    protected val motion: HudMotionDriver,
) : HudFrameLayout(context) {
    /** Clips the moving [strip] to exactly the rows or tile rows that are allowed to show. */
    protected val viewport = FrameLayout(context).apply { clipChildren = true }

    /** The offset column: children are laid out in it and it is translated, never re-laid out. */
    protected val strip = FrameLayout(context)

    private val header = HomeHeaderView(context)
    private val statusView = HudStatusView(context)
    private val emptyView = HudStatusView(context)
    private val track = ScrollTrackView(context)

    protected var applied: HomeViewModel? = null
        private set

    /** Current body height: the content's own height up to [maxBody]; 0 until the first bind. */
    protected var bodyHeight = 0
        private set

    /** The tallest body that fits between the header and the status slot, in whole rows. */
    private var maxBody = 0
    private var contentHeight = 0

    private var screenHeight = HudGeometry.DEFAULT.viewport.height

    /** The strip's scroll offset in pixels; the only thing a selection move animates besides focus. */
    private val scroll = motion.value(0f) { strip.translationY = -it }

    /** True while a selection move is being bound: it animates at `duration-default`. */
    protected var animateMoves = false
        private set

    init {
        clipToPadding = false
        clipChildren = false
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, HomeHeaderView.HEIGHT))
        viewport.addView(strip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0))
        viewport.addView(
            emptyView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, HudStatusView.HEIGHT),
        )
        column.addView(
            viewport,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply {
                topMargin = GAP
            },
        )
        column.addView(
            statusView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, HudStatusView.HEIGHT).apply {
                topMargin = GAP
            },
        )
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // The indicator sits in the safe margin, outside the 448 px content column.
        addView(
            track,
            LayoutParams(ScrollTrackView.WIDTH, 0, Gravity.END or Gravity.TOP).apply {
                topMargin = HomeHeaderView.HEIGHT + GAP
                rightMargin = -(RokidHudTokens.SAFE_X - TRACK_MARGIN)
            },
        )
        track.visibility = View.INVISIBLE
    }

    /** [animate] is set for a selection move only; every other change lands at once. */
    fun bind(model: HomeViewModel, animate: Boolean = false) {
        if (maxBody == 0) fit()
        val prev = applied
        applied = model
        animateMoves = animate
        val empty = model.entries.isEmpty()
        if (empty) {
            header.setCounter("WAITING FOR PHONE")
            // Widgets fill the body on their own; the empty line would sit on top of them.
            if (model.widgets.isEmpty()) emptyView.show(HudStatusView.Kind.OFF, EMPTY_TEXT) else emptyView.hide()
        } else {
            val index = model.selectedIndex.coerceAtLeast(0)
            header.setCounter("${index + 1}/${model.entries.size}")
            emptyView.hide()
        }
        bindBody(prev, model)
        animateMoves = false
        val failure = model.status as? HomeStatus.Failed
        if (failure != null) statusView.show(HudStatusView.Kind.WARN, failure.text) else statusView.hide()
    }

    fun setTopInsetPx(px: Int) {
        applySafeAreaPadding(topInsetPx = px)
        fit()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            screenHeight = MeasureSpec.getSize(heightMeasureSpec)
            fit()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun fit() {
        val available = screenHeight - paddingTop - paddingBottom - HudGridMetrics.HEADER_HEIGHT -
            2 * GAP - HudGridMetrics.STATUS_HEIGHT
        maxBody = fitBody(available)
        applyBody()
    }

    /**
     * The renderings report their content height after each sync. The body is only as tall as its
     * content, so the failure status sits right under the last row instead of at the screen edge.
     */
    protected fun fitToContent(contentHeight: Int) {
        this.contentHeight = contentHeight
        applyBody()
    }

    private fun applyBody() {
        val next = if (contentHeight <= 0) HudStatusView.HEIGHT else minOf(maxBody, contentHeight)
        if (next == bodyHeight) return
        bodyHeight = next
        viewport.layoutParams = viewport.layoutParams.apply { height = next }
        track.layoutParams = track.layoutParams.apply { height = next }
        applied?.let(::onBodyChanged)
    }

    /** Moves the strip to [offsetPx]: animated for a selection move, at once otherwise. */
    protected fun scrollTo(offsetPx: Int) {
        if (animateMoves) {
            scroll.animateTo(offsetPx.toFloat(), RokidHudTokens.DURATION_DEFAULT_MS)
        } else {
            scroll.snapTo(offsetPx.toFloat())
        }
    }

    /** Ends the scroll and every focus cross-fade on their end states, so bounds are final. */
    fun settleMotion() {
        if (scroll.isRunning) scroll.snapTo(scroll.target)
        settleItems()
    }

    /** Draws the content of item [id] (no chrome) into a bitmap of the item's size. */
    fun snapshotItem(id: String): Bitmap? {
        val view = itemView(id) ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        (view as HomeItemView).drawContent(Canvas(bitmap))
        return bitmap
    }

    /** Draws the indicator for a body of [contentSize] of which [viewSize] shows from [offset]. */
    protected fun setPosition(offset: Int, viewSize: Int, contentSize: Int) {
        if (contentSize <= viewSize) {
            track.setThumb(0f, 1f)
        } else {
            track.setThumb(offset.toFloat() / contentSize, viewSize.toFloat() / contentSize)
        }
    }

    protected abstract fun bindBody(prev: HomeViewModel?, model: HomeViewModel)

    /** The body height, in whole rows, that fits in [available] pixels. Never less than one row. */
    protected abstract fun fitBody(available: Int): Int

    /** The body got taller or shorter: keep the selection in view and redraw the indicator. */
    protected abstract fun onBodyChanged(model: HomeViewModel)

    /** Where an item is, in this view's coordinates, scroll offset included. Null if unknown. */
    abstract fun itemBounds(id: String): Rect?

    protected abstract fun itemView(id: String): View?

    protected abstract fun settleItems()

    /**
     * [view]'s rect in this view's coordinates. Translations are added by hand: the strip moves by
     * `translationY`, which the framework's own descendant-rect helpers do not always fold in.
     */
    protected fun boundsOf(view: View): Rect {
        var x = 0f
        var y = 0f
        var node: View = view
        while (node !== this) {
            x += node.left + node.translationX
            y += node.top + node.translationY
            node = node.parent as? View ?: break
        }
        return Rect(x.roundToInt(), y.roundToInt(), x.roundToInt() + view.width, y.roundToInt() + view.height)
    }

    internal fun failureTextForTest(): String? =
        statusView.takeIf { it.kind == HudStatusView.Kind.WARN }?.message

    internal fun emptyTextForTest(): String? =
        emptyView.takeIf { it.kind == HudStatusView.Kind.OFF }?.message

    internal fun trackForTest(): ScrollTrackView = track

    companion object {
        const val EMPTY_TEXT = "No phone plugins synced"

        /** Distance of the position indicator from the screen edge. */
        private const val TRACK_MARGIN = 6

        /** `space-1` between the header, the body and the status slot. */
        const val GAP = HudGridMetrics.SECTION_GAP
    }
}
