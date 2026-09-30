package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.RokidHudTokens
import com.anezium.rokidbus.glasses.hud.AmbientLayer
import com.anezium.rokidbus.glasses.hud.AmbientStack
import com.anezium.rokidbus.glasses.hud.AmbientWindow
import com.anezium.rokidbus.glasses.hud.HudGeometry
import com.anezium.rokidbus.shared.PinSurfaceEmphasis
import com.anezium.rokidbus.shared.PinSurfacePosition
import com.anezium.rokidbus.shared.PinSurfaceSize

object PinOverlayRenderer {
    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var root: PinPanelView? = null
    private var params: WindowManager.LayoutParams? = null
    private var unsubscribe: (() -> Unit)? = null
    private var insetUnsubscribe: (() -> Unit)? = null
    private var position: PinSurfacePosition? = null
    private var hudTopInsetDp = 0

    fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        windowManager = service.getSystemService(WindowManager::class.java)
        insetUnsubscribe?.invoke()
        insetUnsubscribe = HudTopInset.observe(service, ::applyHudTopInset)
        unsubscribe?.invoke()
        unsubscribe = PinController.observe(::render)
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service !== service) return
        unsubscribe?.invoke()
        unsubscribe = null
        insetUnsubscribe?.invoke()
        insetUnsubscribe = null
        hide()
        this.service = null
        windowManager = null
    }

    private val ambientWindow = object : AmbientWindow {
        override val layer = AmbientLayer.PIN

        override fun readd(): Boolean {
            val manager = windowManager ?: return false
            val currentRoot = root ?: return false
            val currentParams = params ?: return false
            return runCatching {
                manager.removeView(currentRoot)
                manager.addView(currentRoot, currentParams)
            }.onFailure { logError("Pin overlay z-order refresh failed", it) }.isSuccess
        }
    }

    private fun render(pin: NexusPinSurface?) {
        if (pin == null) {
            hide()
            return
        }
        val activeService = service ?: return
        val manager = windowManager
            ?: activeService.getSystemService(WindowManager::class.java)
            ?: return
        position = pin.content.position
        val currentRoot = root ?: PinPanelView(activeService).also { next ->
            val nextParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            )
            applyPosition(nextParams, pin.content.position, activeService)
            if (runCatching { manager.addView(next, nextParams) }.isFailure) return
            root = next
            params = nextParams
            AmbientStack.main.added(ambientWindow)
        }
        currentRoot.render(pin)
        params?.let { layout ->
            applyPosition(layout, pin.content.position, activeService)
            runCatching { manager.updateViewLayout(currentRoot, layout) }
        }
    }

    private fun hide() {
        val currentRoot = root
        if (currentRoot != null) {
            runCatching { windowManager?.removeView(currentRoot) }
            currentRoot.render(null)
        }
        root = null
        params = null
        position = null
        if (currentRoot != null) AmbientStack.main.removed(AmbientLayer.PIN)
    }

    private fun applyHudTopInset(value: Int) {
        hudTopInsetDp = HudTopInset.sanitize(value)
        val activeService = service ?: return
        val currentRoot = root ?: return
        val currentParams = params ?: return
        val currentPosition = position ?: return
        applyPosition(currentParams, currentPosition, activeService)
        runCatching { windowManager?.updateViewLayout(currentRoot, currentParams) }
    }

    private fun applyPosition(
        params: WindowManager.LayoutParams,
        position: PinSurfacePosition,
        context: Context,
    ) {
        val placement = placementFor(position, hudTopInsetDp, context)
        params.gravity = placement.gravity
        params.x = placement.x
        params.y = placement.y
    }

    /** Where a pin window sits: the gravity of its corner and the offsets from that corner. */
    internal data class Placement(val gravity: Int, val x: Int, val y: Int)

    internal fun placementFor(position: PinSurfacePosition, hudTopInsetDp: Int, context: Context): Placement =
        Placement(
            gravity = when (position) {
                PinSurfacePosition.TOP_LEFT -> Gravity.TOP or Gravity.START
                PinSurfacePosition.TOP_RIGHT -> Gravity.TOP or Gravity.END
                PinSurfacePosition.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
                PinSurfacePosition.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            },
            x = RokidHudTokens.SAFE_X,
            y = RokidHudTokens.SAFE_Y + if (
                position == PinSurfacePosition.TOP_LEFT ||
                position == PinSurfacePosition.TOP_RIGHT
            ) {
                HudTopInset.toPx(context, hudTopInsetDp)
            } else {
                0
            },
        )

    /**
     * The shared medium chip geometry. Activities instantiate this same view;
     * the pin renderer continues to use it with no leading glyph.
     *
     * A `Panel`: `space-3` by `space-2` padding, the title in `body` (small) or `heading`
     * (medium), the lines in `body-small` or `body`. A `bright` line is `text-primary`, any other
     * `text-secondary`; the subtitle is a measure, so it is `data` (mono).
     */
    internal class PinPanelView(context: Context) : LinearLayout(context) {
        private val title = TextView(context)

        /**
         * An activity's measure, stacked under the title beside the glyph, in
         * the room the glyph leaves there. Pins never use it.
         */
        private val subtitle = TextView(context).apply { visibility = View.GONE }
        private val glyph = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        private val lines = List(MAX_LINE_SLOTS) { TextView(context) }

        init {
            orientation = VERTICAL
            setPadding(
                RokidHudTokens.SPACE_3,
                RokidHudTokens.SPACE_2,
                RokidHudTokens.SPACE_3,
                RokidHudTokens.SPACE_2,
            )
            background = AmbientStyle.panel()
            addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        glyph,
                        LayoutParams(RokidHudTokens.ICON_LG, RokidHudTokens.ICON_LG).apply {
                            marginEnd = RokidHudTokens.SPACE_2
                        },
                    )
                    addView(
                        LinearLayout(context).apply {
                            orientation = VERTICAL
                            addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                            addView(subtitle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                        },
                        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
                    )
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
            lines.forEachIndexed { index, line ->
                addView(
                    line,
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = if (index == 0) RokidHudTokens.SPACE_1 else 0
                    },
                )
            }
        }

        fun render(pin: NexusPinSurface?) {
            val content = pin?.content
            render(
                titleText = content?.title,
                lineContent = content?.lines.orEmpty(),
                size = content?.size ?: PinSurfaceSize.SMALL,
                leadingGlyph = null,
            )
        }

        fun render(
            titleText: String?,
            lineContent: List<com.anezium.rokidbus.shared.PinSurfaceLine>,
            size: PinSurfaceSize,
            leadingGlyph: Drawable?,
            subtitleText: String? = null,
        ) {
            val medium = size == PinSurfaceSize.MEDIUM
            val maxWidthPx = (
                HudGeometry.DEFAULT.viewport.width *
                    if (medium) MEDIUM_WIDTH_FRACTION else SMALL_WIDTH_FRACTION
                ).toInt()

            if (medium) SurfaceType.heading(title) else SurfaceType.body(title)
            title.isSingleLine = true
            title.maxWidth = maxWidthPx
            title.text = titleText.orEmpty()
            title.visibility = visibleIf(!titleText.isNullOrEmpty())
            AmbientStyle.glyph(glyph, leadingGlyph, RokidHudTokens.ICON_LG, RokidHudTokens.TEXT_PRIMARY)
            val stacked = leadingGlyph != null && !subtitleText.isNullOrEmpty()
            SurfaceType.data(subtitle)
            subtitle.maxWidth = maxWidthPx
            subtitle.text = subtitleText.orEmpty()
            subtitle.visibility = visibleIf(stacked)

            lines.forEachIndexed { index, view ->
                val line = lineContent.getOrNull(index)?.takeIf { index < size.maxLines }
                if (medium) SurfaceType.body(view) else SurfaceType.bodySmall(view)
                view.isSingleLine = true
                view.maxWidth = maxWidthPx
                view.setTextColor(
                    if (line?.emphasis == PinSurfaceEmphasis.BRIGHT) {
                        RokidHudTokens.TEXT_PRIMARY
                    } else {
                        RokidHudTokens.TEXT_SECONDARY
                    },
                )
                view.text = line?.text.orEmpty()
                view.visibility = visibleIf(!line?.text.isNullOrEmpty())
            }
        }

        private fun visibleIf(visible: Boolean): Int =
            if (visible) View.VISIBLE else View.GONE
    }

    private const val MAX_LINE_SLOTS = 3
    private const val SMALL_WIDTH_FRACTION = 0.45f
    private const val MEDIUM_WIDTH_FRACTION = 0.60f
}
