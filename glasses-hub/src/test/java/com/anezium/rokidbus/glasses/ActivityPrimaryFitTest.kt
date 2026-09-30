package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityPrimaryFitTest {
    // Monospace: every character advances 0.6 em. The panel's text column on 480x640 is
    // 374 - 24 padding - 24 glyph - 12 gap - 2 slack = 312 px; the primary runs from `display`
    // (22 px) down to `heading` (16 px), and no lower than 18 px beside the ETA.
    private val available = 312f
    private val eta = 5 * 0.6f * 13f + 8f

    private fun widthOf(text: String): (Float) -> Float = { sizePx -> text.length * 0.6f * sizePx }

    @Test
    fun `a short value keeps the full size next to its eta`() {
        assertEquals(
            ActivityPrimaryFit(sizePx = 22f, etaBelow = false),
            fitActivityPrimary(available, eta, widthOf("3 stops")),
        )
    }

    @Test
    fun `a value that almost fits shrinks inline before anything moves`() {
        val fit = fitActivityPrimary(available, eta, widthOf("12:41 PM"))
        assertEquals(false, fit.etaBelow)
        assertEquals(22f, fit.sizePx, 0f)

        val tighter = fitActivityPrimary(available, eta, widthOf("A".repeat(22)))
        assertEquals(false, tighter.etaBelow)
        assertEquals(20f, tighter.sizePx, 0f)
    }

    @Test
    fun `a value too long inline moves the eta down and stays large`() {
        assertEquals(
            ActivityPrimaryFit(sizePx = 20f, etaBelow = true),
            fitActivityPrimary(available, eta, widthOf("A".repeat(25))),
        )
    }

    @Test
    fun `without an eta nothing moves and the value only shrinks as needed`() {
        assertEquals(
            ActivityPrimaryFit(sizePx = 20f, etaBelow = false),
            fitActivityPrimary(available, null, widthOf("A".repeat(25))),
        )
        assertEquals(
            ActivityPrimaryFit(sizePx = 22f, etaBelow = false),
            fitActivityPrimary(available, null, widthOf("300 m")),
        )
    }

    @Test
    fun `an impossible fit ends at the floor instead of looping`() {
        assertEquals(
            ActivityPrimaryFit(sizePx = ACTIVITY_PRIMARY_MIN_PX, etaBelow = true),
            fitActivityPrimary(40f, eta, widthOf("Depart 3 min")),
        )
    }
}
