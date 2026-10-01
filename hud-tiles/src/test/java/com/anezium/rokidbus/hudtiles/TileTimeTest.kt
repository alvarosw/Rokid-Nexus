package com.anezium.rokidbus.hudtiles

import org.junit.Assert.assertEquals
import org.junit.Test

class TileTimeTest {
    @Test
    fun `ages read now, minutes, hours, days`() {
        assertEquals("now", TileTime.age(59_999, compact = false))
        assertEquals("1 min", TileTime.age(60_000, compact = false))
        assertEquals("18 min", TileTime.age(18 * 60_000L + 5_000, compact = false))
        assertEquals("2 h", TileTime.age(2 * 3_600_000L + 1, compact = false))
        assertEquals("3 d", TileTime.age(3 * 86_400_000L, compact = false))
        assertEquals("now", TileTime.age(0, compact = true))
        assertEquals("1m", TileTime.age(60_000, compact = true))
        assertEquals("2h", TileTime.age(2 * 3_600_000L, compact = true))
        assertEquals("3d", TileTime.age(3 * 86_400_000L, compact = true))
    }

    @Test
    fun `an age changes on its next unit boundary`() {
        assertEquals(20_000, TileTime.untilAgeChanges(40_000))
        assertEquals(55_000, TileTime.untilAgeChanges(65_000))
        assertEquals(3_599_000, TileTime.untilAgeChanges(3_601_000))
    }

    @Test
    fun `clock and updated line`() {
        assertEquals("1:42", TileTime.clock(102_999))
        assertEquals("1:02:07", TileTime.clock(3_727_000))
        assertEquals("Updated now", TileTime.updated(9_999))
        assertEquals("Updated 10 s ago", TileTime.updated(12_000))
        assertEquals("Updated 2 min ago", TileTime.updated(125_000))
        assertEquals(8_000, TileTime.untilUpdatedChanges(12_000))
    }
}
