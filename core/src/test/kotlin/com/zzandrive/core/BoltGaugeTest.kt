package com.zzandrive.core

import com.zzandrive.core.motion.BoltGauge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The bolt's fill line against the designer's v23 files (spec D-40). Rows are where yellow starts, measured on every frame
 * of the 30/50/70/90 % files of all 11 levels (720 px canvas).
 */
class BoltGaugeTest {
    @Test
    fun `the line lands where the designer drew 30, 50, 70 and 90 percent`() {
        val drawn = mapOf(0.3f to 101.5f, 0.5f to 80.5f, 0.7f to 60.5f, 0.9f to 39.5f)
        for (level in 1..10) {
            for ((fill, row) in drawn) assertEquals(row, BoltGauge.lineRow(level, fill), 1f, "level $level at $fill")
        }
        val wallet = mapOf(0.3f to 105.5f, 0.5f to 84.7f, 0.7f to 64.3f, 0.9f to 41.5f) // level 0's bolt sits lower
        for ((fill, row) in wallet) assertEquals(row, BoltGauge.lineRow(0, fill), 1f, "level 0 at $fill")
    }

    @Test
    fun `empty is the whole bolt, full reaches its tip`() {
        // Yellow goes down to row 137 (level 0) and 139 (level 6) at the most: at 0 the line is below all of it.
        assertEquals(BoltGauge.BOTTOM, BoltGauge.lineRow(0, 0f))
        assertEquals(BoltGauge.BOTTOM, BoltGauge.lineRow(1, 0f))
        assertTrue(BoltGauge.BOTTOM > 139f)
        // The bolt's top is at rows 25–28 (28–32 at level 0): at 1 the line is there.
        assertTrue(BoltGauge.lineRow(1, 1f) in 25f..30f)
        assertTrue(BoltGauge.lineRow(0, 1f) in 28f..33f)
    }

    @Test
    fun `more fill moves the line up, and values outside 0 to 1 are clamped`() {
        for (level in listOf(0, 1, 10)) {
            var last = BoltGauge.lineRow(level, 0f)
            for (i in 1..100) {
                val row = BoltGauge.lineRow(level, i / 100f)
                assertTrue(row < last, "level $level at $i%")
                last = row
            }
            assertEquals(BoltGauge.lineRow(level, 0f), BoltGauge.lineRow(level, -0.5f))
            assertEquals(BoltGauge.lineRow(level, 1f), BoltGauge.lineRow(level, 1.7f))
        }
    }

    @Test
    fun `the box holds every bolt the designer drew`() {
        // All frames of the 11 clips: x 303–399, y 25–139.
        assertTrue(BoltGauge.LEFT <= 303f && BoltGauge.RIGHT > 399f)
        assertTrue(BoltGauge.TOP <= 25f && BoltGauge.BOTTOM > 139f)
        assertTrue(BoltGauge.RIGHT <= BoltGauge.CANVAS && BoltGauge.BOTTOM <= BoltGauge.CANVAS)
    }
}
