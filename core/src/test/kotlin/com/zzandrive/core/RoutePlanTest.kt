package com.zzandrive.core

import com.zzandrive.core.domain.Efficiency
import com.zzandrive.core.domain.RoutePlanner
import com.zzandrive.core.domain.bd
import com.zzandrive.core.domain.toWon
import com.zzandrive.core.voice.Persona
import com.zzandrive.core.voice.VoiceLines
import com.zzandrive.data.api.DataOrigin
import com.zzandrive.data.api.RouteChoices
import com.zzandrive.data.api.RouteOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 기획서 3-2 "목적지 입력 시 경로별 비용·시간 비교" as the app defines it (spec D-31). */
class RoutePlanTest {
    private val tenKmPerL = Efficiency(bd("10"), DataOrigin.PLAN_EXAMPLE)

    private fun choices(vararg o: RouteOption) = RouteChoices("목적지", o.toList(), DataOrigin.PLAN_EXAMPLE)

    @Test
    fun `30 minutes faster for 2,100 won more wins at 5,000 won an hour (2,500 over 2,100)`() {
        val fast = RouteOption("fast", "고속도로", bd("100"), 60, bd("2100"), highway = true)
        val slow = RouteOption("slow", "국도", bd("100"), 90, bd("0"), highway = false)
        val c = RoutePlanner.compare(choices(fast, slow), tenKmPerL, bd("1000"), bd("5000"))!!
        assertEquals(12_100L, c.results[0].operatingCost.toWon()) // 2,100 + 100 km ÷ 10 × 1,000
        assertEquals(10_000L, c.results[1].operatingCost.toWon())
        assertEquals(17_100L, c.results[0].value.toWon()) // + 60 min = 5,000
        assertEquals(17_500L, c.results[1].value.toWon()) // + 90 min = 7,500
        assertEquals("fast", c.best.option.id)
        assertEquals("slow", c.rival!!.option.id)
        assertEquals(30, c.minutesFaster)
        assertEquals(2_100L, c.costMoreWon)
        assertEquals(
            "고속도로가 국도보다 30분 빠르고 2,100원 더 들어. 시급 5,000원으로 치면 고속도로가 나아!",
            VoiceLines(Persona.ZZANDORI).routeAdvice(c.best.option.name, c.rival?.option?.name, c.minutesFaster, c.costMoreWon, 5_000),
        )
    }

    @Test
    fun `a toll counts only on an expressway route`() {
        val city = RouteOption("city", "시내", bd("20"), 40, bd("3000"), highway = false)
        val c = RoutePlanner.compare(choices(city), tenKmPerL, bd("1000"), bd("5000"))!!
        assertEquals(0L, c.results[0].toll.toWon())
        assertEquals(2_000L, c.results[0].operatingCost.toWon())
        assertNull(c.rival) // one route: nothing to weigh it against
    }

    @Test
    fun `the time value flips the choice - slower and cheaper wins at a low wage`() {
        val fast = RouteOption("fast", "고속도로", bd("100"), 60, bd("2100"), highway = true)
        val slow = RouteOption("slow", "국도", bd("100"), 90, bd("0"), highway = false)
        val c = RoutePlanner.compare(choices(fast, slow), tenKmPerL, bd("1000"), bd("4000"))!! // 30 min = 2,000 < 2,100
        assertEquals("slow", c.best.option.id)
        assertEquals(-30, c.minutesFaster)
        assertEquals(-2_100L, c.costMoreWon)
        assertEquals(
            "국도가 고속도로보다 30분 느리지만 2,100원 덜 들어. 시급 4,000원으로 치면 국도가 나아~",
            VoiceLines(Persona.ZZANSUNI).routeAdvice(c.best.option.name, c.rival?.option?.name, c.minutesFaster, c.costMoreWon, 4_000),
        )
    }

    @Test
    fun `no routes, no comparison`() {
        assertNull(RoutePlanner.compare(choices(), tenKmPerL, bd("1000"), bd("5000")))
    }
}
