package com.zzandrive.core

import com.zzandrive.core.dialog.Expect
import com.zzandrive.core.voice.Josa
import com.zzandrive.core.voice.Persona
import com.zzandrive.core.voice.VoiceLines
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Spoken lines: particles, questions, what is said while driving, re-asks and routes that need a stop. */
class VoiceLinesTest {
    private val dori = VoiceLines(Persona.ZZANDORI)
    private val suni = VoiceLines(Persona.ZZANSUNI)

    @Test
    fun `the amount said back takes the right particle`() {
        assertEquals("가득이면", Josa.myeon("가득"))
        assertEquals("3만 원이면", Josa.myeon("3만 원"))
        assertEquals("40kWh면", Josa.myeon("40kWh"))
        assertEquals("50L면", Josa.myeon("50L"))
    }

    @Test
    fun `a must-stop line puts the amount after the warning, not before it`() {
        assertEquals(
            "이대로는 목적지까지 못 가. 50L면 경로상 B 주유소가 860원 덜 들어. 들를까?",
            dori.mustStop(electric = false, bestName = "경로상 B 주유소", savingWon = 860, fillEcho = "50L면"),
        )
    }

    @Test
    fun `every recommendation is a question, nearest not said twice, short while driving`() {
        assertEquals("가까운 A 주유소가 제일 싸. 들를까?", dori.nearestIsCheapest("가까운 A 주유소"))
        assertEquals("가까운 회사 앞 충전소가 제일 싸. 들를래?", suni.nearestIsCheapest("회사 앞 충전소"))
        assertEquals("집 가는 길 B 충전소가 약 2,300원 이득이야. 들를래?", suni.recommendShort("집 가는 길 B 충전소", 2_308))
    }

    @Test
    fun `the app says why it speaks, and never asks a moving driver to work the navigation`() {
        assertEquals("배터리가 25% 남았어.", suni.lowLevel(electric = true, percent = 25))
        assertEquals("기름이 10% 남았어.", dori.lowLevel(electric = false, percent = 10))
        assertEquals("내비 연결이 안 돼. 차 세우고 안내를 켜 줘.", dori.navigationFailed(driving = true))
        assertEquals("얼마나 충전할래? 가득, 몇 킬로와트, 얼마어치로 말해줘~", suni.reprompt(Expect.FILL, electric = true))
        assertEquals("얼마나 넣을까? 가득, 몇 리터, 얼마어치로 말해줘!", dori.reprompt(Expect.FILL, electric = false))
    }

    @Test
    fun `the re-ask names the place, a missed question comes back at the stop, a spoken no is acknowledged`() {
        assertEquals("집 가는 길 B 충전소 들를래? 응, 아니로 말해줘~", suni.reprompt(Expect.CONSENT, place = "집 가는 길 B 충전소"))
        assertEquals("응, 아니로 말해줘!", dori.reprompt(Expect.CONSENT))
        assertEquals("아까 못 들었지? 집 가는 길 B 충전소 들를래?", suni.askAgainAtStop("집 가는 길 B 충전소"))
        assertEquals("알았어, 이번엔 안 물어볼게.", dori.askClosed())
    }

    @Test
    fun `routes that need a stop on the way are said plainly`() {
        assertEquals("고속도로로 가면 도중에 안 들르고 도착해. 다른 길은 도중에 기름을 넣어야 해.", dori.routeStops(electric = false, best = "고속도로"))
        assertEquals("어느 길로 가도 도착 전에 알림 기준 아래로 내려가. 도중에 충전해야 해.", suni.routeStops(electric = true, best = null))
        // Only the routes that need a stop are named; two stop-free routes are compared with each other first.
        assertEquals("고속도로로 가면 도중에 안 들르고 도착해. 국도는 도중에 기름을 넣어야 해.", dori.routeStops(false, "고속도로", listOf("국도")))
        assertEquals(
            "혼합 경로가 고속도로보다 35분 느리지만 약 3,100원 덜 들어. 시급 5,000원으로 치면 혼합 경로가 나아! 국도는 도중에 기름을 넣어야 해.",
            dori.routeStops(false, "혼합 경로", listOf("국도"), dori.routeAdvice("혼합 경로", "고속도로", -35, -3_145, 5_000)),
        )
        assertEquals("고속도로로 가면 도중에 안 들르고 도착해. 국도와 혼합 경로는 도중에 기름을 넣어야 해.", dori.routeStops(false, "고속도로", listOf("국도", "혼합 경로")))
    }

    @Test
    fun `가까운 is said once even when the station's name starts with it`() {
        assertEquals("제일 가까운 A 주유소가 1킬로야. 들를까?", dori.routeOnly("가까운 A 주유소", "1"))
        assertEquals("제일 가까운 회사 앞 충전소가 1킬로야. 들를래?", suni.routeOnly("회사 앞 충전소", "1"))
        assertEquals("가까운 A 주유소가 제일 싸서 골랐어!", dori.nearestChosen("가까운 A 주유소"))
    }
}
