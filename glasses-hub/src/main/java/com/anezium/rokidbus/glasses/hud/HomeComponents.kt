package com.anezium.rokidbus.glasses.hud

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.GlassesHub
import com.anezium.rokidbus.glasses.ReducedMotion

/** The design system's text styles applied to a [TextView]; sizes are pixels (RokidHudTokens). */
internal object HudType {
    fun body(view: TextView, color: Int): TextView = view.apply {
        setTextColor(color)
        typeface = RokidHudTokens.bodyTypeface()
        RokidHudTokens.applyTextSize(this, RokidHudTokens.BODY_TEXT_SIZE)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** `label`: uppercase, 0.06em tracking, `text-secondary`. The caller uppercases the text. */
    fun label(view: TextView): TextView = view.apply {
        setTextColor(RokidHudTokens.TEXT_SECONDARY)
        typeface = RokidHudTokens.labelTypeface()
        RokidHudTokens.applyTextSize(this, RokidHudTokens.LABEL_TEXT_SIZE)
        letterSpacing = RokidHudTokens.LABEL_LETTER_SPACING_EM
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    fun mono(view: TextView, color: Int): TextView = view.apply {
        setTextColor(color)
        typeface = RokidHudTokens.monoTypeface()
        RokidHudTokens.applyTextSize(this, RokidHudTokens.MONO_TEXT_SIZE)
        includeFontPadding = false
        maxLines = 1
    }
}

/** Rounded outlines and the two fills the design allows: transparent and `surface-selected`. */
internal object HomeChrome {
    fun outline(
        fill: Int,
        stroke: Int,
        strokePx: Int,
        dashed: Boolean = false,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        if (dashed) {
            setStroke(strokePx, stroke, DASH_PX, DASH_PX)
        } else {
            setStroke(strokePx, stroke)
        }
        cornerRadius = RokidHudTokens.RADIUS_CONTROL.toFloat()
    }

    /**
     * An item [amount] of the way from its resting chrome to the focused one (0 = at rest, 1 =
     * focused). The ends are the plain drawables; in between the two frames cross-fade, so the
     * intensities of the outgoing and the incoming item always add up to at most one focus ring.
     */
    fun blended(
        amount: Float,
        restStroke: Int,
        restWidth: Int,
        restDashed: Boolean = false,
        focusDashed: Boolean = false,
    ): Drawable {
        if (amount <= 0f) return outline(android.graphics.Color.TRANSPARENT, restStroke, restWidth, restDashed)
        if (amount >= 1f) {
            return outline(RokidHudTokens.SURFACE_SELECTED, RokidHudTokens.FOCUS, RokidHudTokens.BORDER_STRONG, focusDashed)
        }
        return LayerDrawable(
            arrayOf(
                outline(android.graphics.Color.TRANSPARENT, RokidHudTokens.scaleAlpha(restStroke, 1f - amount), restWidth, restDashed),
                outline(
                    RokidHudTokens.scaleAlpha(RokidHudTokens.SURFACE_SELECTED, amount),
                    RokidHudTokens.scaleAlpha(RokidHudTokens.FOCUS, amount),
                    RokidHudTokens.BORDER_STRONG,
                    focusDashed,
                ),
            ),
        )
    }

    /** [rest] text or icon intensity moved [amount] of the way to `focus`. */
    fun intensity(amount: Float, rest: Int): Int =
        if (amount <= 0f) rest else if (amount >= 1f) RokidHudTokens.FOCUS else ArgbEvaluator().evaluate(amount, rest, RokidHudTokens.FOCUS) as Int

    private const val DASH_PX = 3f
}

/**
 * The focus ring of one home item: 0 at rest, 1 focused. A selection move animates it at
 * `duration-default` when the owner asks for it; the item's [homeFocused] state changes at once and
 * this only paints the way there. Without a [HudMotionDriver] it always lands immediately.
 */
internal class FocusTransition(motion: HudMotionDriver?, private val apply: (Float) -> Unit) {
    private val value = motion?.value(0f, apply)

    fun set(focused: Boolean, animate: Boolean) {
        val target = if (focused) 1f else 0f
        when {
            value == null -> apply(target)
            animate -> value.animateTo(target, RokidHudTokens.DURATION_DEFAULT_MS)
            else -> value.snapTo(target)
        }
    }

    /** Ends a running move on its target; no-op when nothing runs. */
    fun settle() {
        value?.let { if (it.isRunning) it.snapTo(it.target) }
    }
}

/**
 * `Loader`, `scan` variant: a `focus` segment sweeping along a `line` track every
 * `duration-scan`. It runs only while [setActive] is true and the view is attached, and under
 * reduced motion (or a zero animator scale) it is drawn once and never animated.
 */
internal class HudLoaderView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var phase = STATIC_PHASE
    private var animator: ValueAnimator? = null
    private var active = false

    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        sync()
    }

    val isAnimating: Boolean get() = animator?.isRunning == true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        sync()
    }

    private fun sync() {
        if (active && isAttachedToWindow && isShown) start() else stop()
    }

    private fun start() {
        if (animator != null) return
        if (ReducedMotion.isEnabled(context)) {
            phase = STATIC_PHASE
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RokidHudTokens.DURATION_SCAN_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        val trackHeight = TRACK_HEIGHT.toFloat()
        val top = (height - trackHeight) / 2f
        val radius = RokidHudTokens.RADIUS_DATA.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = RokidHudTokens.LINE
        rect.set(0f, top, width.toFloat(), top + trackHeight)
        canvas.drawRoundRect(rect, radius, radius, paint)

        val segment = width * SEGMENT_FRACTION
        val start = -segment + phase * (width + segment)
        val left = start.coerceAtLeast(0f)
        val right = (start + segment).coerceAtMost(width.toFloat())
        if (right <= left) return
        paint.color = RokidHudTokens.FOCUS
        rect.set(left, top, right, top + trackHeight)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    companion object {
        const val TRACK_HEIGHT = 2
        private const val SEGMENT_FRACTION = 0.3f

        /** Where the segment rests when nothing animates: centred on the track. */
        private const val STATIC_PHASE = 0.5f
    }
}

/** The two monoline icons the home layer needs: `alert` and `circle`. 1 px stroke at any size. */
internal class HudIconView(context: Context, private val kind: Kind) : View(context) {
    enum class Kind { ALERT, CIRCLE }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RokidHudTokens.BORDER_DEFAULT.toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    fun setIntensity(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        val u = s / 16f
        path.reset()
        when (kind) {
            Kind.CIRCLE -> path.addCircle(ox + 8f * u, oy + 8f * u, 6.5f * u, Path.Direction.CW)
            Kind.ALERT -> {
                path.moveTo(ox + 8f * u, oy + 1.5f * u)
                path.lineTo(ox + 15f * u, oy + 14f * u)
                path.lineTo(ox + 1f * u, oy + 14f * u)
                path.close()
                path.moveTo(ox + 8f * u, oy + 6f * u)
                path.lineTo(ox + 8f * u, oy + 9.5f * u)
                path.moveTo(ox + 8f * u, oy + 11.5f * u)
                path.lineTo(ox + 8f * u, oy + 12f * u)
            }
        }
        canvas.drawPath(path, paint)
    }
}

/**
 * `Status`, the two states the home layer uses: `warn` (alert icon, 72 %, dashed `line-control`
 * border) for a failed open and `off` (circle icon, 48 %, `line` border) for "nothing here".
 */
internal class HudStatusView(context: Context) : LinearLayout(context) {
    enum class Kind { WARN, OFF }

    private val icon = HudIconView(context, HudIconView.Kind.ALERT)
    private val offIcon = HudIconView(context, HudIconView.Kind.CIRCLE)
    private val text = HudType.body(TextView(context), RokidHudTokens.TEXT_PRIMARY).apply {
        RokidHudTokens.applyTextSize(this, RokidHudTokens.BODY_SMALL_TEXT_SIZE)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = RokidHudTokens.SPACE_2
        setPadding(pad, 0, pad, 0)
        val size = RokidHudTokens.ICON_SM
        addView(icon, LayoutParams(size, size))
        addView(offIcon, LayoutParams(size, size))
        addView(
            text,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = RokidHudTokens.SPACE_2 },
        )
        visibility = GONE
    }

    var kind: Kind? = null
        private set

    fun show(kind: Kind, message: String) {
        this.kind = kind
        val warn = kind == Kind.WARN
        icon.visibility = if (warn) VISIBLE else GONE
        offIcon.visibility = if (warn) GONE else VISIBLE
        val intensity = if (warn) RokidHudTokens.TEXT_PRIMARY else RokidHudTokens.TEXT_SECONDARY
        icon.setIntensity(intensity)
        offIcon.setIntensity(intensity)
        text.setTextColor(intensity)
        text.text = message
        background = if (warn) {
            HomeChrome.outline(
                android.graphics.Color.TRANSPARENT,
                RokidHudTokens.LINE_CONTROL,
                RokidHudTokens.BORDER_DEFAULT,
                dashed = true,
            )
        } else {
            HomeChrome.outline(android.graphics.Color.TRANSPARENT, RokidHudTokens.LINE, RokidHudTokens.BORDER_DEFAULT)
        }
        visibility = VISIBLE
    }

    fun hide() {
        kind = null
        visibility = GONE
    }

    val message: String get() = text.text.toString()

    companion object {
        /** `body-small` 16 px line plus `space-1` above and below. */
        const val HEIGHT = 24
    }
}

/** Compact title row: `label`-style product name on the left, a `mono` counter on the right. */
internal class HomeHeaderView(context: Context) : FrameLayout(context) {
    private val title = HudType.label(TextView(context)).apply { text = "NEXUS" }
    private val counter = HudType.mono(TextView(context), RokidHudTokens.TEXT_SECONDARY)

    init {
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL))
        addView(counter, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL))
        title.gravity = Gravity.CENTER_VERTICAL
        counter.gravity = Gravity.CENTER_VERTICAL
    }

    fun setCounter(text: String) {
        counter.text = text
    }

    companion object {
        const val HEIGHT = 16
    }
}

/**
 * The position indicator: a 2 px `line` track with a `text-secondary` thumb, drawn in the safe
 * margin beside the scrolled body. Hidden while everything fits.
 */
internal class ScrollTrackView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var start = 0f
    private var size = 1f

    /** [start] and [size] are fractions of the track. */
    fun setThumb(start: Float, size: Float) {
        this.start = start.coerceIn(0f, 1f)
        this.size = size.coerceIn(0f, 1f)
        visibility = if (size >= 1f) INVISIBLE else VISIBLE
        invalidate()
    }

    val thumbStart: Float get() = start
    val thumbSize: Float get() = size

    override fun onDraw(canvas: Canvas) {
        val radius = RokidHudTokens.RADIUS_DATA.toFloat()
        paint.color = RokidHudTokens.LINE
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.color = RokidHudTokens.TEXT_SECONDARY
        val top = start * height
        rect.set(0f, top, width.toFloat(), top + size * height)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    companion object {
        const val WIDTH = 2
    }
}

/**
 * The corner every tile carries, fallback or live: the 16 px plugin icon over a `label`-style
 * uppercase plugin name, top-left. Stacked rather than side by side because at 106 px a name beside
 * its icon leaves ~70 px, which cuts "NAVIGATION" mid-word.
 */
internal class TileHeaderView(context: Context, nameLines: Int) : LinearLayout(context) {
    private val icon = ImageView(context)
    private val name = HudType.label(TextView(context)).apply { maxLines = nameLines }

    init {
        orientation = VERTICAL
        val iconSize = RokidHudTokens.ICON_SM
        addView(icon, LayoutParams(iconSize, iconSize))
        addView(
            name,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = RokidHudTokens.SPACE_1
            },
        )
    }

    fun bind(
        entry: GlassesHub.LauncherEntry,
        iconLoader: (Context, GlassesHub.LauncherEntry) -> Drawable,
    ) {
        icon.setImageDrawable(iconLoader(context, entry))
        name.text = entry.displayName.uppercase()
    }

    /** [amount] is the focus cross-fade, 0 at rest and 1 focused. */
    fun setFocusAmount(amount: Float) {
        name.setTextColor(HomeChrome.intensity(amount, RokidHudTokens.TEXT_SECONDARY))
        icon.imageTintList = ColorStateList.valueOf(HomeChrome.intensity(amount, RokidHudTokens.TEXT_PRIMARY))
    }

    val nameText: String get() = name.text.toString()
}
