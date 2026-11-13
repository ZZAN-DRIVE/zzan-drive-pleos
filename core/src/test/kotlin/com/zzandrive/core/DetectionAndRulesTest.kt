package com.zzandrive.core

import com.zzandrive.core.Fixtures.cents
import com.zzandrive.core.domain.bd
import com.zzandrive.core.verification.InefficiencyRules
import com.zzandrive.core.verification.InefficiencyRules.Kind
import com.zzandrive.core.verification.QuantityBasis
import com.zzandrive.core.verification.RefillConfig
import com.zzandrive.core.verification.RefillDetected
import com.zzandrive.core.verification.RefillDetector
import com.zzandrive.core.verification.StationMatcher
import com.zzandrive.data.api.ChargeHintSample
import com.zzandrive.data.api.DataOrigin.PLEOS_SDK
import com.zzandrive.data.api.DriveSample
import com.zzandrive.data.api.EnergySample
import com.zzandrive.data.api.EnergyType
import com.zzandrive.data.api.LocationSample
import com.zzandrive.data.api.VehicleSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetectionAndRulesTest {
    private fun energy(t: Long, kwh: Double?, soc: Double? = null) = EnergySample(t, kwh, soc, 77.4, PLEOS_SDK)
    private fun hint(t: Long, on: Boolean) = ChargeHintSample(t, portOpen = on, switchOn = on, origin = PLEOS_SDK)
    private fun drive(t: Long, parked: Boolean) = DriveSample(t, parked, if (parked) "P" else "D", PLEOS_SDK)

    /** Scenario B charge: parked at B, port/switch on, 19.35 -> 59.35 kWh in 5 kWh steps, then off. */
    private fun scenarioB(portOn: Boolean = true, parked: Boolean = true): List<VehicleSample> = buildList {
        add(drive(0, parked))
        add(LocationSample(500, Fixtures.B_POINT, PLEOS_SDK))
        add(energy(1_000, 19.35, 25.0))
        add(hint(2_000, portOn))
        var kwh = 19.35
        for (i in 1..8) {
            kwh += 5.0
            add(energy(2_000L + i * 2_000, kwh, null))
        }
        add(hint(20_000, false))
        add(energy(21_000, 59.35, 77.0))
    }

    private fun run(samples: List<VehicleSample>, tickAt: Long = 30_000): List<RefillDetected> {
        val d = RefillDetector(RefillConfig.EV)
        return samples.mapNotNull(d::onSample) + listOfNotNull(d.tick(tickAt))
    }

    @Test
    fun `TV-20 scenario B charge is detected as exactly 40 kWh`() {
        val found = run(scenarioB())
        assertEquals(1, found.size)
        val r = found.single()
        assertEquals(40.0, r.quantity, 1e-9)
        assertEquals(QuantityBasis.ENERGY, r.basis)
        assertEquals(Fixtures.B_POINT, r.location)
        assertEquals("refill-2000", r.id)
    }

    @Test
    fun `TV-20 no detection when the port stays closed or while driving`() {
        assertTrue(run(scenarioB(portOn = false)).isEmpty())
        assertTrue(run(scenarioB(parked = false)).isEmpty())
    }

    @Test
    fun `TV-20 small rise is ignored`() {
        val d = RefillDetector(RefillConfig.EV)
        val samples = listOf(drive(0, true), energy(1_000, 50.0), hint(2_000, true), energy(4_000, 50.4), hint(6_000, false))
        assertTrue((samples.mapNotNull(d::onSample) + listOfNotNull(d.tick(60_000))).isEmpty())
    }

    @Test
    fun `TV-20 late batched and duplicated callbacks give the same single result`() {
        val base = scenarioB()
        val noisy = base.flatMap { listOf(it, it) } // every callback twice, delivered at once
        val found = run(noisy)
        assertEquals(1, found.size)
        assertEquals(40.0, found.single().quantity, 1e-9)
    }

    @Test
    fun `SOC path is used when energy is not readable`() {
        val d = RefillDetector(RefillConfig.EV, capacityFallback = 77.4)
        val samples = listOf(drive(0, true), energy(1_000, null, 25.0), hint(2_000, true), energy(5_000, null, 77.0), hint(9_000, false), energy(10_000, null, 77.0))
        val r = (samples.mapNotNull(d::onSample) + listOfNotNull(d.tick(30_000))).single()
        assertEquals(QuantityBasis.SOC, r.basis)
        assertEquals(40.248, r.quantity, 1e-9)
    }

    @Test
    fun `fuel without hints closes after the quiet period or when driving off`() {
        val d = RefillDetector(RefillConfig.FUEL)
        val f = { t: Long, l: Double -> EnergySample(t, l, null, 55.0, PLEOS_SDK) }
        listOf(drive(0, true), f(1_000, 8.0), f(10_000, 30.0), f(20_000, 58.0)).forEach { assertNull(d.onSample(it)) }
        assertTrue(d.isRefilling)
        assertNull(d.tick(100_000))
        val r = d.tick(140_001)
        assertNotNull(r)
        assertEquals(50.0, r!!.quantity, 1e-9)
        val d2 = RefillDetector(RefillConfig.FUEL)
        listOf(drive(0, true), f(1_000, 8.0), f(10_000, 58.0)).forEach { d2.onSample(it) }
        assertEquals(50.0, d2.onSample(drive(20_000, false))!!.quantity, 1e-9)
    }

    @Test
    fun `station matcher uses a 300 m radius`() {
        val stations = mapOf("A" to Fixtures.A_POINT, "B" to Fixtures.B_POINT)
        assertEquals("B", StationMatcher.match(Fixtures.B_POINT, stations))
        assertNull(StationMatcher.match(com.zzandrive.data.api.GeoPoint(37.0, 127.0), stations))
        assertNull(StationMatcher.match(null, stations))
    }

    @Test
    fun `TV-17 inefficiency classification`() {
        val dt = InefficiencyRules.dtSeconds(0, 1_000_000_000)
        assertEquals(1.0, dt, 1e-12)
        assertEquals(11.0, InefficiencyRules.acceleration(3.0, 14.0, dt), 1e-12)
        assertEquals(Kind.RAPID_START, InefficiencyRules.classify(3.0, 11.0))
        assertEquals(Kind.UNCLASSIFIED, InefficiencyRules.classify(5.5, 20.0))
        assertEquals(Kind.RAPID_ACCEL, InefficiencyRules.classify(8.0, 12.0))
        assertEquals(Kind.NONE, InefficiencyRules.classify(15.0, 9.9))
        assertEquals(Kind.RAPID_ACCEL, InefficiencyRules.classify(25.0, 8.0))
        assertEquals(Kind.DECEL, InefficiencyRules.classify(40.0, -12.0))
        assertFalse(InefficiencyRules.chargeable(Kind.DECEL))
        // Boundaries of b and of the sampling window.
        assertEquals(12.0, InefficiencyRules.accelThreshold(6.0)); assertEquals(12.0, InefficiencyRules.accelThreshold(10.0))
        assertEquals(10.0, InefficiencyRules.accelThreshold(10.01)); assertEquals(10.0, InefficiencyRules.accelThreshold(20.0))
        assertEquals(8.0, InefficiencyRules.accelThreshold(20.01))
        assertEquals(Kind.RAPID_START, InefficiencyRules.classify(5.0, 10.0))
        listOf(0.79 to false, 0.8 to true, 1.2 to true, 1.21 to false).forEach { (v, ok) -> assertEquals(ok, InefficiencyRules.validInterval(v)) }
        assertEquals(50.4, InefficiencyRules.kmh(14.0), 1e-9)
    }

    @Test
    fun `TV-17 repeat counting merges same-direction observations within 3 s`() {
        val c = InefficiencyRules.RepeatCounter()
        assertEquals(1, c.observe(0, true))
        assertEquals(1, c.observe(2_000, true)) // same event
        assertEquals(2, c.observe(10_000, false))
        val n = c.observe(20_000, true)
        assertEquals(3, n)
        assertTrue(c.isRepeatCandidate(n))
        assertEquals(2, c.observe(75_000, true)) // events older than 60 s drop out
    }

    @Test
    fun `TV-18 inefficiency costs`() {
        assertEquals(bd("1360.03"), InefficiencyRules.driveExtraCost(bd(10), EnergyType.GASOLINE, bd("1855.43")).cents())
        assertEquals(bd("681.45"), InefficiencyRules.driveExtraCost(bd(10), EnergyType.DIESEL, bd("1841.76")).cents())
        assertEquals(bd("238.69"), InefficiencyRules.driveExtraCost(bd(10), EnergyType.ELECTRIC, bd("348.4")).cents())
        assertEquals(300L, InefficiencyRules.idleEvaluationSeconds(480))
        assertEquals(0L, InefficiencyRules.idleEvaluationSeconds(100))
        assertEquals(bd("93.65"), InefficiencyRules.idleCost(InefficiencyRules.idleEvaluationSeconds(480), EnergyType.GASOLINE, bd("1855.43")).cents())
        assertEquals(bd("98.77"), InefficiencyRules.idleCost(InefficiencyRules.idleEvaluationSeconds(480), EnergyType.DIESEL, bd("1841.76")).cents())
        assertEquals(bd("37.46"), InefficiencyRules.idleCost(InefficiencyRules.idleEvaluationSeconds(300), EnergyType.GASOLINE, bd("1855.43")).cents())
        assertEquals(bd("39.51"), InefficiencyRules.idleCost(InefficiencyRules.idleEvaluationSeconds(300), EnergyType.DIESEL, bd("1841.76")).cents())
        assertEquals(bd("0.00"), InefficiencyRules.idleCost(300, EnergyType.ELECTRIC, bd("348.4")).cents())
        assertEquals(bd("0.605666"), InefficiencyRules.idleUsePerHour(EnergyType.GASOLINE)!!.setScale(6, java.math.RoundingMode.HALF_UP))
    }
}
