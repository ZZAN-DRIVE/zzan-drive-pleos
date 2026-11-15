package com.zzandrive.core

import com.zzandrive.core.Fixtures.cents
import com.zzandrive.core.domain.CostCalculator
import com.zzandrive.core.domain.PlanReference
import com.zzandrive.core.domain.Recommender
import com.zzandrive.core.domain.RouteSegment
import com.zzandrive.core.domain.bd
import com.zzandrive.core.domain.toWon
import com.zzandrive.data.api.EnergyType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CostAndRecommendTest {
    @Test
    fun `TV-01 real fuel cost`() {
        val a = CostCalculator.realCost(bd(50), Fixtures.fuelA, bd(10))
        val b = CostCalculator.realCost(bd(50), Fixtures.fuelB, bd(10))
        assertEquals(bd("85340.00"), a.total.cents())
        assertEquals(bd("340.00"), a.detourCost.cents())
        assertEquals(bd("84480.00"), b.total.cents())
        assertEquals(860L, a.total.subtract(b.total).toWon())
    }

    @Test
    fun `TV-02 real charging cost`() {
        val a = CostCalculator.realCost(bd(40), Fixtures.evA, bd(5))
        val b = CostCalculator.realCost(bd(40), Fixtures.evB, bd(5))
        assertEquals(bd("14140.00"), a.total.cents())
        assertEquals(bd("11832.00"), b.total.cents())
        assertEquals(2308L, a.total.subtract(b.total).toWon())
    }

    @Test
    fun `TV-05 survey 10 km cost`() {
        fun cost(t: EnergyType) = PlanReference.survey(t).let { CostCalculator.energyCost(bd(10), it.efficiency, it.unitPrice).cents() }
        assertEquals(bd("1212.70"), cost(EnergyType.GASOLINE))
        assertEquals(bd("1287.94"), cost(EnergyType.DIESEL))
        assertEquals(bd("580.67"), cost(EnergyType.ELECTRIC))
    }

    @Test
    fun `TV-03 time value decides the route`() {
        val tv = bd(5000) // the vector's own time value (spec TV-03); the app's default is the minimum wage (TimeValueTest)
        // Toll road 30 min faster but 2,100 more: 30 min = 2,500 >= 2,100 -> toll road.
        val toll = CostCalculator.comparisonValue(bd(2100), bd(0), tv)
        val free = CostCalculator.comparisonValue(bd(0), bd(30), tv)
        assertTrue(toll < free)
        // Toll 1,900 and 10 min faster vs free road with 200 more electricity: free road wins, saves 1,700 in money.
        val toll2 = CostCalculator.comparisonValue(bd(1900), bd(0), tv)
        val free2 = CostCalculator.comparisonValue(bd(200), bd(10), tv)
        assertTrue(free2 < toll2)
        assertEquals(1700L, bd(1900).subtract(bd(200)).toWon())
    }

    @Test
    fun `route operating cost adds toll and segment energy`() {
        val cost = CostCalculator.routeOperatingCost(bd(1900), listOf(RouteSegment(bd(30), bd(5), bd(290))))
        assertEquals(bd("3640.00"), cost.cents())
    }

    @Test
    fun `recommender picks the nearest as baseline and the cheapest as best`() {
        val r = Recommender.recommend("r1", 0, EnergyType.ELECTRIC, bd(40), PlanReference.DEMO_EV_EFFICIENCY, listOf(Fixtures.evB, Fixtures.evA))!!
        assertEquals("A", r.baseline.offer.id)
        assertEquals("B", r.best.offer.id)
        assertEquals(2308L, r.expectedSaving.toWon())
        assertFalse(r.recommendsBaseline)
    }

    @Test
    fun `recommender ignores incompatible stations and reports nearest-is-cheapest`() {
        val cheapNearest = Fixtures.offer("N", EnergyType.ELECTRIC, "280", "1")
        val r = Recommender.recommend("r2", 0, EnergyType.ELECTRIC, bd(40), PlanReference.DEMO_EV_EFFICIENCY, listOf(cheapNearest, Fixtures.evB, Fixtures.fuelA))!!
        assertTrue(r.recommendsBaseline)
        assertEquals(2, r.ranked.size)
        assertNull(Recommender.recommend("r3", 0, EnergyType.DIESEL, bd(40), PlanReference.DEMO_FUEL_EFFICIENCY, listOf(Fixtures.evA)))
    }
}
