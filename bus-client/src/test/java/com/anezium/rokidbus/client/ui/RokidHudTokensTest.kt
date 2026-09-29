package com.anezium.rokidbus.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RokidHudTokensTest {
    @Test
    fun `color intensities are the same hue at the documented alpha`() {
        val rgb = 0x40FF5E
        assertEquals(0xFF000000.toInt() or rgb, RokidHudTokens.GREEN_100)
        assertEquals(0xB8000000.toInt() or rgb, RokidHudTokens.GREEN_72)
        assertEquals(0x7A000000.toInt() or rgb, RokidHudTokens.GREEN_48)
        assertEquals(0x3D000000.toInt() or rgb, RokidHudTokens.GREEN_24)
        assertEquals(0x1F000000.toInt() or rgb, RokidHudTokens.GREEN_12)
        assertEquals(0x0F000000.toInt() or rgb, RokidHudTokens.GREEN_06)
    }

    @Test
    fun `aliases point at their documented intensity`() {
        assertEquals(RokidHudTokens.GREEN_100, RokidHudTokens.FOCUS)
        assertEquals(RokidHudTokens.GREEN_100, RokidHudTokens.CRITICAL)
        assertEquals(RokidHudTokens.GREEN_72, RokidHudTokens.TEXT_PRIMARY)
        assertEquals(RokidHudTokens.GREEN_48, RokidHudTokens.TEXT_SECONDARY)
        assertEquals(RokidHudTokens.GREEN_48, RokidHudTokens.LINE_CONTROL)
        assertEquals(RokidHudTokens.GREEN_24, RokidHudTokens.LINE)
        assertEquals(RokidHudTokens.GREEN_12, RokidHudTokens.SURFACE_SELECTED)
        assertEquals(RokidHudTokens.GREEN_06, RokidHudTokens.SURFACE_SUBTLE)
        assertEquals(RokidHudTokens.GROUND, RokidHudTokens.ON_EMPHASIS)
    }

    @Test
    fun `never introduces a second hue`() {
        // BusTheme's green (#71FF97) and its danger red must not leak into this token set.
        assertNotEquals(BusTheme.phosphor, RokidHudTokens.GREEN_100)
        assertNotEquals(BusTheme.danger and 0x00FFFFFF, RokidHudTokens.GREEN_100 and 0x00FFFFFF)
    }

    @Test
    fun `spacing follows the 4px grid`() {
        assertEquals(4, RokidHudTokens.SPACE_1)
        assertEquals(8, RokidHudTokens.SPACE_2)
        assertEquals(12, RokidHudTokens.SPACE_3)
        assertEquals(16, RokidHudTokens.SPACE_4)
        assertEquals(24, RokidHudTokens.SPACE_6)
        assertEquals(16, RokidHudTokens.SAFE_X)
        assertEquals(12, RokidHudTokens.SAFE_Y)
    }

    @Test
    fun `tokens are pixels on the 480x640 screen and the content column is 448 wide`() {
        assertEquals(480, RokidHudTokens.CANVAS_WIDTH)
        assertEquals(640, RokidHudTokens.CANVAS_HEIGHT)
        assertEquals(448, RokidHudTokens.CONTENT_WIDTH)
        assertEquals(RokidHudTokens.CANVAS_WIDTH - 2 * RokidHudTokens.SAFE_X, RokidHudTokens.CONTENT_WIDTH)
        assertEquals(32, RokidHudTokens.LIST_ITEM_HEIGHT)
    }
}
