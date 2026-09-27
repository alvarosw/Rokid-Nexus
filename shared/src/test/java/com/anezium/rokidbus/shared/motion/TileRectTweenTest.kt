package com.anezium.rokidbus.shared.motion

import org.junit.Assert.assertEquals
import org.junit.Test

class TileRectTweenTest {
    private val from = TileRect(left = 10, top = 20, right = 50, bottom = 60) // 40x40
    private val to = TileRect(left = 16, top = 12, right = 464, bottom = 340) // 448x328

    @Test
    fun `fraction 0 lands exactly on from`() {
        assertEquals(from, TileRectTween.at(from, to, 0f))
    }

    @Test
    fun `fraction 1 lands exactly on to`() {
        assertEquals(to, TileRectTween.at(from, to, 1f))
    }

    @Test
    fun `fraction 0point5 is the midpoint of position and size`() {
        val mid = TileRectTween.at(from, to, 0.5f)
        assertEquals(13, mid.left)
        assertEquals(16, mid.top)
        assertEquals(244, mid.width)
        assertEquals(184, mid.height)
    }

    @Test
    fun `fraction is clamped outside 0 to 1`() {
        assertEquals(from, TileRectTween.at(from, to, -0.5f))
        assertEquals(to, TileRectTween.at(from, to, 1.5f))
    }

    @Test
    fun `collapsing uses the same interpolation as expanding, just with rects swapped`() {
        val expandedAtStart = TileRectTween.at(from, to, 0f)
        val collapsedAtEnd = TileRectTween.at(to, from, 1f)
        assertEquals(expandedAtStart, collapsedAtEnd)
    }
}
