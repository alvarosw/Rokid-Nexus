package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.hud.HudMotionDriver
import java.util.Locale
import kotlin.math.roundToInt

internal fun resolveReaderScrollTarget(
    sameSurface: Boolean,
    wasNearBottom: Boolean,
    previousOffset: Int,
    maximumScroll: Int,
    anchor: ReaderAnchor,
): Int = when {
    !sameSurface -> if (anchor == ReaderAnchor.TOP) 0 else maximumScroll
    anchor == ReaderAnchor.TOP -> previousOffset.coerceIn(0, maximumScroll)
    wasNearBottom -> maximumScroll
    else -> previousOffset.coerceIn(0, maximumScroll)
}

/**
 * The reader's scrolling container. It scrolls by its own offset (`View.scrollTo`) rather than by a
 * `ScrollView`: HARDWARE S5, layers dither grey grain on the waveguide. The offset moves in a
 * `duration-default` tween and the position is shown by a 2 px `line` track with a `text-secondary`
 * thumb at the right edge, the same indicator the home layer draws.
 */
internal class ReaderSurfaceView(context: Context) : FrameLayout(context) {
    private val document = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, RokidHudTokens.SPACE_2, 0)
    }
    private val motion = HudMotionDriver.forContext(context)
    private val offset = motion.value(0f) { scrollTo(0, it.roundToInt()) }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()
    private var renderedSurfaceId: String? = null
    private var renderGeneration = 0L
    private var pendingScrollLayoutListener: View.OnLayoutChangeListener? = null

    init {
        setWillNotDraw(false)
        addView(document, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        // The document is as tall as its content, however much taller than the viewport that is.
        document.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        setMeasuredDimension(width, height)
    }

    fun render(surfaceId: String, segments: List<ReaderSegment>, anchor: ReaderAnchor) {
        invalidatePendingScrollRestore()
        val sameSurface = renderedSurfaceId == surfaceId
        val previousOffset = scrollY
        val wasNearBottom = sameSurface && maximumScroll() - previousOffset <= BOTTOM_PIN_SLOP_PX
        renderedSurfaceId = surfaceId
        val generation = ++renderGeneration

        document.removeAllViews()
        segments.forEachIndexed { index, segment ->
            document.addView(
                segmentView(
                    segment = segment,
                    isFirst = index == 0,
                    followsProse = index > 0 && segments[index - 1].kind == ReaderSegmentKind.PROSE,
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    when (segment.kind) {
                        ReaderSegmentKind.HEADER -> {
                            if (index > 0) topMargin = RokidHudTokens.SPACE_4
                            bottomMargin = RokidHudTokens.SPACE_1
                        }
                        ReaderSegmentKind.ASIDE -> {
                            topMargin = RokidHudTokens.SPACE_2
                            bottomMargin = RokidHudTokens.SPACE_2
                        }
                        ReaderSegmentKind.PROSE -> Unit
                    }
                },
            )
        }

        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                document.removeOnLayoutChangeListener(this)
                if (pendingScrollLayoutListener === this) pendingScrollLayoutListener = null
                post {
                    if (generation != renderGeneration || renderedSurfaceId != surfaceId) return@post
                    val target = resolveReaderScrollTarget(
                        sameSurface = sameSurface,
                        wasNearBottom = wasNearBottom,
                        previousOffset = previousOffset,
                        maximumScroll = maximumScroll(),
                        anchor = anchor,
                    )
                    offset.snapTo(target.toFloat())
                    invalidate()
                }
            }
        }
        pendingScrollLayoutListener = listener
        document.addOnLayoutChangeListener(listener)
        document.requestLayout()
    }

    fun clear() {
        invalidatePendingScrollRestore()
        renderGeneration += 1
        renderedSurfaceId = null
        document.removeAllViews()
        offset.snapTo(0f)
    }

    fun smoothScrollByViewport(direction: Int) {
        if (direction == 0 || height <= 0) return
        val step = (height * VIEWPORT_SCROLL_FRACTION).roundToInt().coerceAtLeast(1)
        // A running scroll is continued from where it is, not restarted from the old target.
        val target = (offset.target + step * direction.coerceIn(-1, 1)).coerceIn(0f, maximumScroll().toFloat())
        offset.animateTo(target, RokidHudTokens.DURATION_DEFAULT_MS)
    }

    override fun onDetachedFromWindow() {
        invalidatePendingScrollRestore()
        renderGeneration += 1
        offset.cancel()
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val total = document.height
        if (total <= height || height <= 0) return
        // Drawn in content coordinates, so it follows the scroll to stay put on screen.
        val left = (width - TRACK_WIDTH_PX).toFloat()
        val radius = RokidHudTokens.RADIUS_DATA.toFloat()
        trackPaint.color = RokidHudTokens.LINE
        trackRect.set(left, scrollY.toFloat(), width.toFloat(), (scrollY + height).toFloat())
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
        trackPaint.color = RokidHudTokens.TEXT_SECONDARY
        val top = scrollY + scrollY.toFloat() / total * height
        trackRect.set(left, top, width.toFloat(), top + height.toFloat() * height / total)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
    }

    private fun segmentView(
        segment: ReaderSegment,
        isFirst: Boolean,
        followsProse: Boolean,
    ): View = when (segment.kind) {
        ReaderSegmentKind.HEADER -> headerView(segment, decorated = !isFirst && followsProse)
        ReaderSegmentKind.PROSE -> proseView(segment.text)
        ReaderSegmentKind.ASIDE -> SurfaceType.wrap(SurfaceType.bodySmall(TextView(context)), Int.MAX_VALUE).apply {
            text = segment.text
        }
    }

    private fun headerView(segment: ReaderSegment, decorated: Boolean): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (decorated) {
                addView(
                    View(context).apply { setBackgroundColor(RokidHudTokens.LINE) },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        RokidHudTokens.BORDER_DEFAULT,
                    ).apply { bottomMargin = RokidHudTokens.SPACE_3 },
                )
            }
            addView(
                SurfaceType.label(TextView(context)).apply { text = styledHeader(segment) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    /** `label` is uppercase: the speaker (up to the `·`) in `text-primary`, or `focus` when emphasised. */
    private fun styledHeader(segment: ReaderSegment): CharSequence {
        val shown = segment.text.uppercase(Locale.ROOT)
        val styled = SpannableString(shown)
        val separator = shown.indexOf('\u00b7')
        val tokenEnd = if (separator >= 0) separator else shown.length
        if (tokenEnd > 0) {
            styled.setSpan(
                ForegroundColorSpan(if (segment.emphasis) RokidHudTokens.FOCUS else RokidHudTokens.TEXT_PRIMARY),
                0,
                tokenEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            styled.setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                tokenEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return styled
    }

    private fun proseView(text: String): TextView =
        SurfaceType.wrap(SurfaceType.body(TextView(context)), Int.MAX_VALUE).apply {
            this.text = text.ifEmpty { "\u00a0" }
            ellipsize = null
        }

    private fun maximumScroll(): Int = (document.height - height).coerceAtLeast(0)

    private fun invalidatePendingScrollRestore() {
        pendingScrollLayoutListener?.let(document::removeOnLayoutChangeListener)
        pendingScrollLayoutListener = null
    }

    private companion object {
        private const val VIEWPORT_SCROLL_FRACTION = 0.45f
        private const val BOTTOM_PIN_SLOP_PX = 36
        private const val TRACK_WIDTH_PX = 2
    }
}
