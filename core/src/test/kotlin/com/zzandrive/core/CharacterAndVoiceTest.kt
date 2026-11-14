package com.zzandrive.core

import com.zzandrive.core.domain.CelebrationPolicy
import com.zzandrive.core.domain.CharacterProgress
import com.zzandrive.core.domain.CharacterState
import com.zzandrive.core.domain.LevelTable
import com.zzandrive.core.voice.Persona
import com.zzandrive.core.voice.VoiceLines
import com.zzandrive.core.voice.WonFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CharacterAndVoiceTest {
    private val table = LevelTable.DEFAULT
    private val p = CharacterProgress(table)

    @Test
    fun `level table relation target equals next achieved minus achieved`() {
        val targets = (0..10).map { table.target(it) }
        assertEquals(listOf(1500L, 2500L, 4000L, 7000L, 11000L, 16000L, 24000L, 34000L, 50000L, 100000L, null), targets)
        assertEquals(listOf("빈 지갑", "삼각김밥", "아메리카노", "짜장면", "파스타", "치킨", "족발", "스테이크", "한우", "랍스터", "킹크랩"), (0..10).map(table::food))
    }

    @Test
    fun `level share is what the next-level ring and the bolt show`() {
        assertEquals(0.75f, p.levelShare(CharacterState(2, 3000)), 1e-6f) // 시연 B start: 3,000 of 4,000
        assertEquals(18, (p.levelShare(CharacterState(3, 1323)) * 100).toInt()) // 시연 B after the level-up: 1,323 of 7,000
        assertEquals(66, (p.levelShare(CharacterState(0, 1000)) * 100).toInt()) // 시연 A start: 1,000 of 1,500
        assertEquals(7, (p.levelShare(CharacterState(1, 176)) * 100).toInt()) // 시연 A after: 176 of 2,500
        assertEquals(12, (p.levelShare(CharacterState(1, 300)) * 100).toInt()) // 시연 C start: 300 of 2,500
        assertEquals(70, (p.levelShare(CharacterState(0, 1050)) * 100).toInt())
        assertEquals(0f, p.levelShare(CharacterState(0, 0)))
        assertEquals(1f, p.levelShare(CharacterState(10, 0))) // the top level has no next one: full
        assertEquals(1f, p.levelShare(CharacterState(10, 50_000)))
    }

    @Test
    fun `TV-10 level 2 to 3 with penalty floor and carry over`() {
        var s = CharacterState(2, 0)
        assertEquals(4000L, p.totalSaved(s))
        s = p.applySaving(s, 1000).state
        assertEquals(CharacterState(2, 1000), s)
        assertEquals(3000L, p.remainingToNext(s))
        val pen = p.applyPenalty(s, 1500)
        assertEquals(CharacterState(2, 0), pen.state) // 0, not -500
        assertEquals(1500L, pen.fullWon)
        assertEquals(1000L, pen.appliedWon)
        assertEquals(4000L, p.totalSaved(pen.state)) // 4,000, not 3,500
        val up = p.applySaving(pen.state, 4500)
        assertEquals(CharacterState(3, 500), up.state)
        assertEquals(listOf(3), up.levelsReached)
        assertEquals(8500L, p.totalSaved(up.state))
    }

    @Test
    fun `TV-11 penalty at level 1`() {
        val r = p.applyPenalty(CharacterState(1, 500), 500)
        assertEquals(CharacterState(1, 0), r.state)
        assertEquals(1500L, p.totalSaved(r.state))
    }

    @Test
    fun `TV-12 scenario A`() {
        val up = p.applySaving(CharacterState(0, 1000), 1800)
        assertEquals(CharacterState(1, 1300), up.state)
        assertEquals(1200L, p.remainingToNext(up.state))
        assertEquals(CharacterState(1, 1000), p.applyPenalty(up.state, 300).state)
    }

    @Test
    fun `TV-13 scenario B`() {
        val charge = p.applySaving(CharacterState(2, 3000), 2300)
        assertEquals(CharacterState(3, 1300), charge.state)
        assertEquals(5700L, p.remainingToNext(charge.state))
        val route = p.applySaving(charge.state, 1700)
        assertEquals(CharacterState(3, 3000), route.state)
        assertEquals(4000L, p.remainingToNext(route.state))
    }

    @Test
    fun `TV-14 multi level jump`() {
        val r = p.applySaving(CharacterState.START, 10000)
        assertEquals(CharacterState(3, 2000), r.state)
        assertEquals(listOf(1, 2, 3), r.levelsReached)
        assertEquals(10000L, p.totalSaved(r.state))
    }

    @Test
    fun `TV-15 boundaries`() {
        assertEquals(CharacterState(1, 0), p.applySaving(CharacterState(0, 0), 1500).state)
        assertEquals(CharacterState(2, 0), p.applyPenalty(CharacterState(2, 0), 700).state)
        assertEquals(CharacterState(0, 0), p.applyPenalty(CharacterState(0, 0), 700).state)
        val top = p.applySaving(CharacterState(10, 0), 50000)
        assertEquals(CharacterState(10, 50000), top.state)
        assertTrue(top.levelsReached.isEmpty())
        assertNull(p.remainingToNext(top.state))
    }

    @Test
    fun `celebrations wait until parked`() {
        assertEquals(emptyList<Int>(), CelebrationPolicy.pending(2, 3, parked = false))
        assertEquals(listOf(3), CelebrationPolicy.pending(2, 3, parked = true))
        assertEquals(listOf(1, 2, 3), CelebrationPolicy.pending(0, 3, parked = true))
        assertEquals(emptyList<Int>(), CelebrationPolicy.pending(3, 3, parked = true))
    }

    @Test
    fun `TV-19 spoken amounts`() {
        assertEquals("약 2,300원", WonFormat.spoken(2308))
        assertEquals("약 5,700원", WonFormat.spoken(5692))
        assertEquals("1,500원", WonFormat.spoken(1500))
        assertEquals("860원", WonFormat.spoken(860))
        assertEquals("약 1,200원", WonFormat.spoken(BigDecimal("1212.70")))
        assertEquals("마이너스 500원", WonFormat.spoken(-500))
        assertEquals("2,308원", WonFormat.screen(2308))
        assertEquals("1,212.70원", WonFormat.screen(BigDecimal("1212.6993"), 2))
    }

    @Test
    fun `voice lines reproduce the plan's scenario B wording`() {
        val v = VoiceLines(Persona.ZZANSUNI)
        assertEquals("짜장면 레벨 달성! 파스타까지 약 5,700원 남았어~", v.levelUp("짜장면", "파스타", 5692))
        assertEquals("회사 앞 충전소보다 B 충전소가 약 2,300원 이득이야. 들를래?", v.recommendCharge("회사 앞 충전소", "B 충전소", 2308))
        assertEquals("짜장면 레벨 달성! 파스타까지 약 5,700원 남았어!", VoiceLines(Persona.ZZANDORI).levelUp("짜장면", "파스타", 5692))
    }
}
