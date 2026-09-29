package com.anezium.rokidbus.client.ui

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView

/**
 * Rokid HUD design system v1 — native port of `tokens.json`, verbatim. Six intensities of a single
 * hue, `#40FF5E`, and nothing else: there is no "error red" or "warning amber" anywhere in this
 * system, state is intensity + border shape + icon + text.
 *
 * This is a **separate token object from [BusTheme]**, deliberately not an extension of it.
 * `BusTheme` is a different, older green (`#71FF97`) and keeps serving every surface that already
 * depends on it (phone settings, today's list launcher, notices) — none of that is in scope here
 * and none of it should regress. Only the new grid-HUD code (`GridLauncherView`, `FallbackTileView`,
 * `HudFrameLayout`, and everything later deliveries add on top) may reference this object.
 *
 * Unit: every size, spacing, radius, border and text size here is a **physical pixel** on the
 * 480x640 screen, not a dp/sp. The design canvas equals the screen, so the tokens are applied as
 * they are written (safe-x 16, content 448) at any display density; use [applyTextSize] for text
 * and plain pixel values for everything else.
 *
 * See docs/grid-hud-roadmap/00-overview.md for the source values and the reasoning.
 */
object RokidHudTokens {
    // color.green-100 — alias focus, critical. Focus ring, critical state. One at a time, never a
    // large fill.
    const val GREEN_100: Int = 0xFF40FF5E.toInt()

    // color.green-72 — alias text-primary.
    const val GREEN_72: Int = 0xB840FF5E.toInt()

    // color.green-48 — alias text-secondary, line-control.
    const val GREEN_48: Int = 0x7A40FF5E.toInt()

    // color.green-24 — alias line. Structure only (dividers/borders/progress tracks), never text.
    const val GREEN_24: Int = 0x3D40FF5E.toInt()

    // color.green-12 — alias surface-selected.
    const val GREEN_12: Int = 0x1F40FF5E.toInt()

    // color.green-06 — alias surface-subtle. Never more emphasis than this for a panel fill.
    const val GREEN_06: Int = 0x0F40FF5E.toInt()

    // color.ground — background. Transparent on-device (an unlit pixel = the real world showing
    // through). BusTheme.glassesBg (Color.BLACK) already satisfies this and is reused for it.
    val GROUND: Int = Color.BLACK

    // color.on-emphasis — alias of ground; text on a rare filled green-100 badge.
    val ON_EMPHASIS: Int = GROUND

    // Semantic aliases, named as the design system names them.
    const val FOCUS: Int = GREEN_100
    const val CRITICAL: Int = GREEN_100
    const val TEXT_PRIMARY: Int = GREEN_72
    const val TEXT_SECONDARY: Int = GREEN_48
    const val LINE_CONTROL: Int = GREEN_48
    const val LINE: Int = GREEN_24
    const val SURFACE_SELECTED: Int = GREEN_12
    const val SURFACE_SUBTLE: Int = GREEN_06

    // spacing.space-1 .. space-6 — 4px grid.
    const val SPACE_1 = 4
    const val SPACE_2 = 8
    const val SPACE_3 = 12
    const val SPACE_4 = 16
    const val SPACE_6 = 24

    // spacing.safe-x / safe-y — HudFrame's automatic inset.
    const val SAFE_X = 16
    const val SAFE_Y = 12

    // viewport.content-width — the canvas inside the safe area.
    const val CONTENT_WIDTH = 448

    // ListItem — fixed row height.
    const val LIST_ITEM_HEIGHT = 32

    // radius.radius-control / radius-panel / radius-data
    const val RADIUS_CONTROL = 4
    const val RADIUS_PANEL = 6
    const val RADIUS_DATA = 2

    // border.border-default / border-strong
    const val BORDER_DEFAULT = 1
    const val BORDER_STRONG = 2

    // icon.icon-sm / icon-md / icon-lg
    const val ICON_SM = 16
    const val ICON_MD = 20
    const val ICON_LG = 24

    // motion.duration-feedback / duration-default / duration-structural / duration-ambient /
    // duration-scan, all in milliseconds. Structural is 220, not the 320 of the source tokens: the
    // owner settled it watching the loop on the glasses (docs/ui-rewrite/00-architecture.md §2.6).
    const val DURATION_FEEDBACK_MS = 120L
    const val DURATION_DEFAULT_MS = 200L
    const val DURATION_STRUCTURAL_MS = 220L
    const val DURATION_AMBIENT_MS = 6_000L
    const val DURATION_SCAN_MS = 1_200L

    // The glasses screen: 480x640 px (owner, from the official spec, 2026-09-29). It supersedes the
    // 480x352 "aiui" reference viewport of the design system; the tokens above are the same pixels.
    const val CANVAS_WIDTH = 480
    const val CANVAS_HEIGHT = 640

    // typography.body — default text size, sans, 400 weight. 14 / 20 / 400.
    const val BODY_TEXT_SIZE = 14f

    // typography.label — data labels/panel titles, sans, 500 weight, uppercase, 0.06em tracking.
    // 11 / 14 / 500.
    const val LABEL_TEXT_SIZE = 11f
    const val LABEL_LETTER_SPACING_EM = 0.06f

    // typography.data — numeric values, mono, 500 weight. 13 / 18 / 500.
    const val DATA_TEXT_SIZE = 13f

    // Lazy: a plain JVM unit test that only touches a color/spacing constant must not pay for
    // (or crash on) an unstubbed android.graphics.Typeface call at object-init time.
    private val sans: Typeface by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }
    private val sansMedium: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val mono: Typeface by lazy { Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL) }

    fun bodyTypeface(): Typeface = sans

    fun labelTypeface(): Typeface = sansMedium

    // The platform's generic monospace family has no medium-weight variant, so `data`'s 500
    // weight is approximated with bold rather than left unstyled.
    fun dataTypeface(): Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun monoTypeface(): Typeface = mono

    // typography.mono — coordinates, codes, counters. 11 / 14 / 400.
    const val MONO_TEXT_SIZE = 11f

    // typography.body-small — supporting text. 12 / 16 / 400.
    const val BODY_SMALL_TEXT_SIZE = 12f

    /** [color] with its alpha multiplied by [factor] (0..1); a token color faded, never re-hued. */
    fun scaleAlpha(color: Int, factor: Float): Int {
        val alpha = ((color ushr 24) * factor.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (color and 0xFFFFFF)
    }

    /** Sets a token text size, which is in pixels (see the unit note above), never scaled. */
    fun applyTextSize(view: TextView, sizePx: Float) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
    }
}
