package com.zzandrive.data.mock

import com.zzandrive.core.domain.CompareMode
import com.zzandrive.core.domain.CreditRule
import com.zzandrive.core.domain.PlanContext
import com.zzandrive.core.domain.Reachability
import com.zzandrive.core.domain.RouteBranch
import com.zzandrive.core.domain.FillAnswer
import com.zzandrive.core.domain.PlanReference
import com.zzandrive.core.domain.PlanRequest
import com.zzandrive.core.domain.Recommender
import com.zzandrive.core.domain.bd
import com.zzandrive.core.domain.toWon
import com.zzandrive.core.settlement.SettlementDecision
import com.zzandrive.core.settlement.SettlementPolicy
import com.zzandrive.core.verification.RefillConfig
import com.zzandrive.core.verification.RefillDetected
import com.zzandrive.core.verification.RefillDetector
import com.zzandrive.core.verification.StationMatcher
import com.zzandrive.data.api.DriveSample
import com.zzandrive.data.api.EnergyType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The demo-mode timelines must produce the plan's numbers through the real core logic. */
class ReplayTest {
    private fun detections(type: EnergyType, config: RefillConfig, vararg phases: List<TimedSample>): List<RefillDetected> {
        val clock = ManualScheduler()
        val source = ReplayVehicleSource(type, clock, clock)
        val detector = RefillDetector(config)
        val found = mutableListOf<RefillDetected>()
        source.subscribe { s -> detector.onSample(s)?.let(found::add) }
        phases.forEach { phase ->
            source.play(phase)
            clock.advanceBy(phase.maxOf { it.offsetMs } + 1)
        }
        clock.advanceBy(200_000)
        detector.tick(clock.nowMs())?.let(found::add)
        return found
    }

    @Test
    fun `scenario B demo timeline gives 40 kWh and 2,308 won at B`() {
        val found = detections(EnergyType.ELECTRIC, RefillConfig.EV, Scenarios.evLowBattery(), Scenarios.evChargeAtB())
        val d = found.single()
        assertEquals(40.0, d.quantity, 1e-9)
        val stations = FixtureStationSource().offers(EnergyType.ELECTRIC, null)
        val rec = Recommender.recommend("demo-b", 0, EnergyType.ELECTRIC, bd(40), PlanReference.DEMO_EV_EFFICIENCY, stations)!!
        assertEquals("ev-a", rec.baseline.offer.id)
        assertEquals("ev-b", rec.best.offer.id)
        val used = StationMatcher.match(d.location, stations.associate { it.id to it.location })
        assertEquals("ev-b", used)
        val decision = SettlementPolicy.decide(rec, approved = true, detection = d, usedStationId = used)
        assertEquals(2308L, (decision as SettlementDecision.Credit).amountWon)
    }

    @Test
    fun `scenario A demo timeline gives 50 L and 860 won at B`() {
        val found = detections(EnergyType.GASOLINE, RefillConfig.FUEL, Scenarios.fuelLow(), Scenarios.fuelRefillAtB())
        val d = found.single()
        assertEquals(50.0, d.quantity, 1e-9)
        val stations = FixtureStationSource().offers(EnergyType.GASOLINE, null)
        val rec = Recommender.recommend("demo-a", 0, EnergyType.GASOLINE, bd(50), PlanReference.DEMO_FUEL_EFFICIENCY, stations)!!
        assertEquals(860L, rec.expectedSaving.toWon())
        val used = StationMatcher.match(d.location, stations.associate { it.id to it.location })
        assertEquals(860L, (SettlementPolicy.decide(rec, true, d, used) as SettlementDecision.Credit).amountWon)
    }

    @Test
    fun `scenario A refill drives off and then parks, so the level-up can play`() {
        val clock = ManualScheduler()
        val source = ReplayVehicleSource(EnergyType.GASOLINE, clock, clock)
        val parkedStates = mutableListOf<Boolean>()
        source.subscribe { if (it is DriveSample) parkedStates += it.parked }
        source.play(Scenarios.fuelRefillAtB())
        clock.advanceBy(60_000)
        assertEquals(listOf(true, false, true), parkedStates)
    }

    @Test
    fun `scenario B search - 40 kWh around here gives 2,308 won, the 6,2 km station left out`() {
        val offers = FixtureStationSource().candidates(EnergyType.ELECTRIC, FixtureStations.HOME)
        val rec = Recommender.plan(
            PlanRequest("find-b", 0, EnergyType.ELECTRIC, offers, PlanReference.DEMO_EV_EFFICIENCY, FillAnswer.Quantity(bd(40)), room = bd("42.57")),
        )!!
        assertEquals(CompareMode.COST_INCLUDED, rec.mode)
        assertEquals(listOf("ev-far"), rec.excludedIds)
        assertEquals("ev-a", rec.baseline.offer.id)
        assertEquals("ev-b", rec.best.offer.id)
        assertEquals(2308L, rec.expectedSaving.toWon())
        // Charging 40 kWh at B from the parked timeline's 45 % settles the same 2,308 won.
        val found = detections(EnergyType.ELECTRIC, RefillConfig.EV, Scenarios.evParked(), Scenarios.evChargeAtB(startKwh = 34.83))
        val d = found.single()
        assertEquals(40.0, d.quantity, 1e-9)
        val used = StationMatcher.match(d.location, offers.associate { it.id to it.location })
        assertEquals(2308L, (SettlementPolicy.decide(rec, true, d, used) as SettlementDecision.Credit).amountWon)
    }

    @Test
    fun `search around the saved place finds nothing within 5 km - the nearest one only`() {
        val offers = FixtureStationSource().candidates(EnergyType.ELECTRIC, FixtureStations.PARENTS)
        val rec = Recommender.plan(PlanRequest("far", 0, EnergyType.ELECTRIC, offers, PlanReference.DEMO_EV_EFFICIENCY, FillAnswer.Quantity(bd(40))))!!
        assertEquals(CompareMode.NEAREST_ONLY, rec.mode)
        assertEquals("ev-p1", rec.best.offer.id)
        assertEquals(0L, rec.expectedSaving.toWon())
    }

    @Test
    fun `fuel search compares A and C - B (6 km of road) is left out`() {
        val offers = FixtureStationSource().candidates(EnergyType.GASOLINE, FixtureStations.HOME)
        val rec = Recommender.plan(PlanRequest("find-a", 0, EnergyType.GASOLINE, offers, PlanReference.DEMO_FUEL_EFFICIENCY, FillAnswer.Quantity(bd(50))))!!
        assertEquals(listOf("fuel-b"), rec.excludedIds)
        assertEquals("fuel-c", rec.best.offer.id)
        assertEquals(668L, rec.expectedSaving.toWon()) // 85,340 − (84,000 + 672)
    }

    @Test
    fun `scenario A - 220 km with 6 L is out of range, the reachable pair gives 860 won against the costliest`() {
        val route = FixtureRouteSource().active(EnergyType.GASOLINE)!!
        val eff = PlanReference.DEMO_FUEL_EFFICIENCY.kmPerUnit // 10 km/L
        val threshold = bd(12) // 20 % of 60 L
        val branch = Reachability.classify(bd(6), threshold, route.remainingKm, eff)
        assertEquals(RouteBranch.UNREACHABLE, branch)
        val limit = Reachability.limitKm(RouteBranch.UNREACHABLE, bd(6), threshold, eff)!!
        val reachable = Reachability.reachable(route.stations, limit)
        assertEquals(listOf("fuel-a", "fuel-b"), reachable.map { it.id })
        val rec = Recommender.plan(
            PlanRequest("a", 0, EnergyType.GASOLINE, reachable, PlanReference.DEMO_FUEL_EFFICIENCY, FillAnswer.Quantity(bd(50)), context = PlanContext.MustStop(branch)),
        )!!
        assertEquals(CreditRule.VS_WORST, rec.creditRule)
        assertEquals("fuel-b", rec.best.offer.id)
        assertEquals(860L, rec.expectedSaving.toWon())
        val d = detections(EnergyType.GASOLINE, RefillConfig.FUEL, Scenarios.fuelLow(), Scenarios.fuelRefillAtB()).single()
        val used = StationMatcher.match(d.location, rec.ranked.associate { it.offer.id to it.offer.location })
        assertEquals(860L, (SettlementPolicy.decide(rec, true, d, used) as SettlementDecision.Credit).amountWon)
        // Electric cars have no route in this fixture: the search stays around the current location.
        assertEquals(null, FixtureRouteSource().active(EnergyType.ELECTRIC))
    }

    @Test
    fun `fixture stations are far enough apart for a 300 m match`() {
        val pts = (FixtureStations.NEAR_HOME + FixtureStations.NEAR_PARENTS).mapNotNull { it.location }
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            assertTrue(StationMatcher.distanceM(pts[i], pts[j]) > 300.0)
        }
    }

    @Test
    fun `refresh re-sends the latest readings with the current time`() {
        val clock = ManualScheduler()
        val source = ReplayVehicleSource(EnergyType.ELECTRIC, clock, clock)
        val seen = mutableListOf<Long>()
        source.subscribe { seen += it.atMs }
        source.play(Scenarios.evLowBattery())
        clock.advanceTo(1_000)
        val before = seen.size
        clock.advanceTo(5_000)
        source.refresh()
        assertEquals(before + 4, seen.size)
        assertTrue(seen.takeLast(4).all { it == 5_000L })
    }
}
