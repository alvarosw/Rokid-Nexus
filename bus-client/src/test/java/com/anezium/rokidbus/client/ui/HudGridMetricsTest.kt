package com.anezium.rokidbus.client.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HudGridMetricsTest {
    @Test
    fun `unit and pitch keep the 448 px content width`() {
        assertEquals(106, HudGridMetrics.UNIT)
        assertEquals(114, HudGridMetrics.PITCH)
        assertEquals(RokidHudTokens.CONTENT_WIDTH, 4 * HudGridMetrics.UNIT + 3 * HudGridMetrics.GAP)
    }

    @Test
    fun `body height at no inset is 568 px, five rows on the 480x640 screen`() {
        // 640 - (12 + 0) - 12 - 16 - 2 x 4 - 24
        assertEquals(568, HudGridMetrics.availableBodyHeight(0))
        assertEquals(5, HudGridMetrics.visibleRows(0))
    }

    @Test
    fun `visible rows shrink as the top inset grows`() {
        // (available + 8) / 114: 0 -> 576, 10 -> 566, 20 -> 556, 40 -> 536.
        assertEquals(5, HudGridMetrics.visibleRows(0))
        assertEquals(4, HudGridMetrics.visibleRows(10))
        assertEquals(4, HudGridMetrics.visibleRows(20))
        assertEquals(4, HudGridMetrics.visibleRows(40))
    }

    @Test
    fun `five rows need an inset of at most 6 px`() {
        assertEquals(5, HudGridMetrics.visibleRows(6))
        assertEquals(4, HudGridMetrics.visibleRows(7))
    }

    @Test
    fun `never fewer than two rows`() {
        assertEquals(2, HudGridMetrics.visibleRows(400))
        assertEquals(2, HudGridMetrics.visibleRows(0, screenHeight = 100))
    }

    @Test
    fun `rows height is whole units plus gaps`() {
        assertEquals(106, HudGridMetrics.rowsHeight(1))
        assertEquals(5 * 106 + 4 * 8, HudGridMetrics.rowsHeight(5))
    }
}
