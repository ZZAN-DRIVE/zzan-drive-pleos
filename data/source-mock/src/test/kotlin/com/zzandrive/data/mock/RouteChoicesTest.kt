package com.zzandrive.data.mock

import com.zzandrive.core.domain.PlanReference
import com.zzandrive.core.domain.RoutePlanner
import com.zzandrive.core.domain.bd
import com.zzandrive.core.domain.toWon
import com.zzandrive.core.voice.Persona
import com.zzandrive.core.voice.VoiceLines
import com.zzandrive.data.api.EnergyType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The demo's three example routes to 강릉 (scenario A step 1) at the survey gasoline price and 10 km/L. */
class RouteChoicesTest {
    private val routes = FixtureRouteSource().choices(EnergyType.GASOLINE)
    private val price = PlanReference.survey(EnergyType.GASOLINE).unitPrice // 1,855.43원/L

    @Test
    fun `at 5,000 won an hour the slower national road wins`() {
        val c = RoutePlanner.compare(routes, PlanReference.DEMO_FUEL_EFFICIENCY, price, bd("5000"))!!
        assertEquals(listOf(52_675L, 46_386L, 49_530L), c.results.map { it.operatingCost.toWon() }) // 10,000 + 23 L, 25 L, 5,000 + 24 L
        assertEquals(listOf(66_008L, 65_552L, 65_780L), c.results.map { it.value.toWon() })
        assertEquals("national", c.best.option.id)
        assertEquals("highway", c.rival!!.option.id)
        assertEquals(-70, c.minutesFaster)
        assertEquals(-6_289L, c.costMoreWon)
        assertEquals(
            "국도가 고속도로보다 70분 느리지만 약 6,300원 덜 들어. 시급 5,000원으로 치면 국도가 나아!",
            VoiceLines(Persona.ZZANDORI).routeAdvice(c.best.option.name, c.rival?.option?.name, c.minutesFaster, c.costMoreWon, 5_000),
        )
    }

    @Test
    fun `with the official 15,3 km per litre the national road still wins at 5,000 won and the expressway at 10,000`() {
        val official = PlanReference.surveyEfficiency(EnergyType.GASOLINE)
        val c = RoutePlanner.compare(routes, official, price, bd("5000"))!!
        assertEquals(listOf(37_892L, 30_317L, 34_105L), c.results.map { it.operatingCost.toWon() })
        assertEquals(listOf(13_333L, 19_167L, 16_250L), c.results.map { it.timeValue.toWon() })
        assertEquals("national", c.best.option.id)
        assertEquals("highway", c.rival!!.option.id)
        assertEquals(-70, c.minutesFaster)
        assertEquals(-7_575L, c.costMoreWon)
        assertEquals(
            "국도가 고속도로보다 70분 느리지만 약 7,600원 덜 들어. 시급 5,000원으로 치면 국도가 나아!",
            VoiceLines(Persona.ZZANDORI).routeAdvice(c.best.option.name, c.rival?.option?.name, c.minutesFaster, c.costMoreWon, 5_000),
        )
        assertEquals("highway", RoutePlanner.compare(routes, official, price, bd("10000"))!!.best.option.id)
    }

    @Test
    fun `at the minimum wage, the default, the expressway wins - 70 minutes are worth more than 7,575 won`() {
        val official = PlanReference.surveyEfficiency(EnergyType.GASOLINE)
        assertEquals(bd("10320"), RoutePlanner.DEFAULT_WAGE_PER_HOUR)
        val c = RoutePlanner.compare(routes, official, price, RoutePlanner.DEFAULT_WAGE_PER_HOUR)!!
        assertEquals(listOf(37_892L, 30_317L, 34_105L), c.results.map { it.operatingCost.toWon() })
        assertEquals(listOf(27_520L, 39_560L, 33_540L), c.results.map { it.timeValue.toWon() }) // 160, 230, 195 min
        assertEquals(listOf(65_412L, 69_877L, 67_645L), c.results.map { it.value.toWon() })
        assertEquals("highway", c.best.option.id)
        assertEquals("national", c.rival!!.option.id)
        assertEquals(70, c.minutesFaster)
        assertEquals(7_575L, c.costMoreWon)
        assertEquals(
            "고속도로가 국도보다 70분 빠르고 약 7,600원 더 들어. 최저시급으로 치면 고속도로가 나아!",
            VoiceLines(Persona.ZZANDORI).routeAdvice(c.best.option.name, c.rival?.option?.name, c.minutesFaster, c.costMoreWon, 10_320),
        )
    }

    @Test
    fun `a route that reaches the alert level on the way is not recommended without a stop`() {
        // 40 L, alert at 30 % of 60 L = 18 L: every route arrives with 15~17 L, so none is free of a stop.
        val tight = RoutePlanner.compare(routes, PlanReference.DEMO_FUEL_EFFICIENCY, price, bd("5000"), energyNow = bd("40"), thresholdAmount = bd("18"))!!
        assertEquals(listOf(true, true, true), tight.results.map { it.needsStop })
        // At 20 % (12 L) all arrive above it: the plain comparison holds.
        val ok = RoutePlanner.compare(routes, PlanReference.DEMO_FUEL_EFFICIENCY, price, bd("5000"), energyNow = bd("40"), thresholdAmount = bd("12"))!!
        assertEquals(listOf(false, false, false), ok.results.map { it.needsStop })
        assertEquals("national", ok.best.option.id)
        // 36 L, alert 12 L: the expressway (23 L) arrives with 13 L; 국도 (25 L) with 11 L and 혼합 (24 L) with 12 L do not.
        val some = RoutePlanner.compare(routes, PlanReference.DEMO_FUEL_EFFICIENCY, price, bd("5000"), energyNow = bd("36"), thresholdAmount = bd("12"))!!
        assertEquals(listOf(false, true, true), some.results.map { it.needsStop })
        assertEquals("highway", some.best.option.id) // the only one without a stop, though 국도 is cheaper to run
    }

    @Test
    fun `at 10,000 won an hour the expressway wins`() {
        val c = RoutePlanner.compare(routes, PlanReference.DEMO_FUEL_EFFICIENCY, price, bd("10000"))!!
        assertEquals("highway", c.best.option.id)
        assertEquals("national", c.rival!!.option.id)
        assertEquals(70, c.minutesFaster)
        assertEquals(6_289L, c.costMoreWon)
    }
}
