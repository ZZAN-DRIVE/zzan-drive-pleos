package com.zzandrive.core

import com.zzandrive.core.domain.PlanReference
import com.zzandrive.core.domain.RoutePlanner
import com.zzandrive.core.domain.bd
import com.zzandrive.core.voice.Persona
import com.zzandrive.core.voice.TimeValueWords
import com.zzandrive.core.voice.VoiceLines
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The hour's value in a comparison (기획서 별지 1 "최저시급 기준", spec D-08): the 2026 minimum wage, 10,320원
 * (고용노동부 고시 제2025-47호), unless the driver sets a higher one. It is named, so nobody reads it as a typed-in figure.
 */
class TimeValueTest {
    @Test
    fun `the default is the 2026 minimum wage and the settings start from it`() {
        assertEquals(bd("10320"), PlanReference.MINIMUM_WAGE_PER_HOUR)
        assertEquals(2026, PlanReference.MINIMUM_WAGE_YEAR)
        assertEquals(PlanReference.MINIMUM_WAGE_PER_HOUR, PlanReference.TIME_VALUE_PER_HOUR)
        assertEquals(PlanReference.MINIMUM_WAGE_PER_HOUR, RoutePlanner.DEFAULT_WAGE_PER_HOUR)
        assertEquals(listOf(10_320, 15_000, 20_000), RoutePlanner.WAGE_CHOICES)
        assertTrue(RoutePlanner.WAGE_CHOICES.all { it >= PlanReference.MINIMUM_WAGE_PER_HOUR.toInt() })
    }

    @Test
    fun `the minimum wage is called by its name and year, another value is marked as a setting`() {
        assertEquals("2026년 최저시급 10,320원", TimeValueWords.screen(10_320))
        assertEquals("시급 15,000원(설정)", TimeValueWords.screen(15_000))
        assertEquals("최저시급 10,320원", TimeValueWords.choice(10_320))
        assertEquals("20,000원", TimeValueWords.choice(20_000))
        assertEquals("시급: 2026년 최저시급 10,320원 (고용노동부 고시 제2025-47호)", TimeValueWords.basis(10_320))
        assertEquals("시급: 설정에서 고른 15,000원 (기본은 2026년 최저시급 10,320원)", TimeValueWords.basis(15_000))
    }

    @Test
    fun `the spoken advice says the minimum wage, not a bare amount`() {
        assertEquals("최저시급", TimeValueWords.spoken(10_320))
        assertEquals("시급 20,000원", TimeValueWords.spoken(20_000))
        assertEquals(
            "고속도로가 국도보다 30분 빠르고 2,100원 더 들어. 최저시급으로 치면 고속도로가 나아!",
            VoiceLines(Persona.ZZANDORI).routeAdvice("고속도로", "국도", 30, 2_100, 10_320),
        )
        assertEquals(
            "고속도로가 국도보다 30분 빠르고 2,100원 더 들어. 시급 20,000원으로 치면 고속도로가 나아~",
            VoiceLines(Persona.ZZANSUNI).routeAdvice("고속도로", "국도", 30, 2_100, 20_000),
        )
    }
}
