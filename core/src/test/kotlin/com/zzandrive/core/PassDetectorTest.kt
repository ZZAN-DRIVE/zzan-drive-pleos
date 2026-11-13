package com.zzandrive.core

import com.zzandrive.core.verification.PassDetector
import com.zzandrive.data.api.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** spec D-28: passing the recommended station without refuelling is detected once, and only then. */
class PassDetectorTest {
    private val b = GeoPoint(37.40200, 127.10800)
    private val near = GeoPoint(37.40400, 127.10800) // ≈ 222 m
    private val middle = GeoPoint(37.40600, 127.10800) // ≈ 445 m
    private val far = GeoPoint(37.40800, 127.10800) // ≈ 667 m

    @Test
    fun `in and out without a refill is a pass, reported once`() {
        val p = PassDetector().apply { watch("rec-1", b) }
        assertNull(p.onLocation(far, refilling = false)) // not yet near: nothing
        assertNull(p.onLocation(near, refilling = false))
        assertNull(p.onLocation(middle, refilling = false)) // between 300 and 500 m: still undecided
        assertEquals("rec-1", p.onLocation(far, refilling = false))
        assertNull(p.onLocation(near, refilling = false)) // stopped watching
        assertNull(p.onLocation(far, refilling = false))
    }

    @Test
    fun `never coming near, refilling, or a cleared watch is not a pass`() {
        val p = PassDetector().apply { watch("rec-2", b) }
        assertNull(p.onLocation(far, refilling = false))
        assertNull(p.onLocation(middle, refilling = false))
        p.onLocation(near, refilling = false)
        assertNull(p.onLocation(far, refilling = true)) // a refill session is running: not a pass
        p.clear() // the refill was detected at the station
        assertNull(p.onLocation(far, refilling = false))
    }
}
