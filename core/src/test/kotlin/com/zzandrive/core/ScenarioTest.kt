package com.zzandrive.core

import com.zzandrive.core.domain.CharacterProgress
import com.zzandrive.core.domain.CharacterState
import com.zzandrive.core.domain.PlanReference
import com.zzandrive.core.domain.Recommender
import com.zzandrive.core.domain.bd
import com.zzandrive.core.domain.toWon
import com.zzandrive.core.settlement.Ledger
import com.zzandrive.core.settlement.OpeningBalance
import com.zzandrive.core.settlement.RecommendationDecided
import com.zzandrive.core.settlement.RecommendationIssued
import com.zzandrive.core.settlement.SavingConfirmed
import com.zzandrive.core.settlement.SettlementDecision
import com.zzandrive.core.settlement.SettlementPolicy
import com.zzandrive.core.verification.RefillConfig
import com.zzandrive.core.verification.RefillDetector
import com.zzandrive.core.verification.StationMatcher
import com.zzandrive.core.voice.Persona
import com.zzandrive.core.voice.VoiceLines
import com.zzandrive.data.api.ChargeHintSample
import com.zzandrive.data.api.DataOrigin
import com.zzandrive.data.api.DriveSample
import com.zzandrive.data.api.EnergySample
import com.zzandrive.data.api.EnergyType
import com.zzandrive.data.api.LocationSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The whole loop on the JVM: recommend → approve → detect → settle → level → voice (spec 12, 시나리오 B·A). */
class ScenarioTest {
    private val progress = CharacterProgress()

    @Test
    fun `scenario B with vehicle-like samples ends at level 3 and the plan's sentence`() {
        val t0 = 1_000_000L
        val ledger = Ledger(progress)
        ledger.append(OpeningBalance("seed-b", t0 - 10, level = 2, accrualWon = 3000))
        val rec = Recommender.recommend("rec-b", t0, EnergyType.ELECTRIC, bd(40), PlanReference.DEMO_EV_EFFICIENCY, listOf(Fixtures.evA, Fixtures.evB), Fixtures.A_POINT)!!
        val voice = VoiceLines(Persona.ZZANSUNI)
        assertEquals("A 충전소보다 B 충전소가 약 2,300원 이득이야. 들를래?", voice.recommendCharge(rec.baseline.offer.name, rec.best.offer.name, rec.expectedSaving.toWon()))
        ledger.append(RecommendationIssued.of(rec, rec.expectedSaving.toWon()))
        ledger.append(RecommendationDecided(rec.id, t0 + 5_000, approved = true))

        val det = RefillDetector(RefillConfig.EV)
        val o = DataOrigin.PLEOS_SDK
        val samples = buildList {
            add(DriveSample(t0 + 60_000, true, "P", o))
            add(LocationSample(t0 + 60_100, Fixtures.B_POINT, o))
            add(EnergySample(t0 + 60_200, 19.35, 25.0, 77.4, o))
            add(ChargeHintSample(t0 + 61_000, portOpen = true, switchOn = true, origin = o))
            (1..8).forEach { add(EnergySample(t0 + 61_000 + it * 2_000L, 19.35 + 5.0 * it, null, 77.4, o)) }
            add(ChargeHintSample(t0 + 80_000, portOpen = false, switchOn = false, origin = o))
        }
        val detection = (samples.mapNotNull(det::onSample) + listOfNotNull(det.tick(t0 + 90_000))).single()
        assertEquals(40.0, detection.quantity, 1e-9)

        val stations = rec.ranked.associate { it.offer.id to it.offer.location }
        val used = StationMatcher.match(detection.location, stations)
        val issued = ledger.openApprovedRecommendation()!!
        val decision = SettlementPolicy.decide(issued.toRecommendation(), ledger.approved(issued.recId), detection, used)
        val credit = decision as SettlementDecision.Credit
        assertEquals(2308L, credit.amountWon)

        val result = ledger.append(SavingConfirmed(issued.recId, detection.id, detection.endMs, credit.amountWon, credit.raw.toPlainString()))
        assertEquals(listOf(3), result.levelsReached)
        assertEquals(CharacterState(3, 1308), ledger.state)
        val remaining = progress.remainingToNext(ledger.state)!!
        assertEquals(5692L, remaining)
        assertEquals("짜장면 레벨 달성! 파스타까지 약 5,700원 남았어~", voice.levelUp(progress.food(3), progress.nextFood(3), remaining))
    }

    @Test
    fun `scenario A fuel mock credits 860`() {
        val t0 = 2_000_000L
        val ledger = Ledger(progress)
        val rec = Recommender.recommend("rec-a", t0, EnergyType.GASOLINE, bd(50), PlanReference.DEMO_FUEL_EFFICIENCY, listOf(Fixtures.fuelA, Fixtures.fuelB))!!
        ledger.append(RecommendationIssued.of(rec, rec.expectedSaving.toWon()))
        ledger.append(RecommendationDecided(rec.id, t0 + 1, true))
        val det = RefillDetector(RefillConfig.FUEL)
        val o = DataOrigin.MOCK
        listOf(DriveSample(t0 + 10, true, "P", o), LocationSample(t0 + 11, Fixtures.B_POINT, o), EnergySample(t0 + 12, 8.0, null, 55.0, o), EnergySample(t0 + 30_000, 58.0, null, 55.0, o))
            .forEach { det.onSample(it) }
        val detection = det.onSample(DriveSample(t0 + 60_000, false, "D", o))!!
        val decision = SettlementPolicy.decide(rec, ledger.approved(rec.id), detection, StationMatcher.match(detection.location, mapOf("A" to Fixtures.A_POINT, "B" to Fixtures.B_POINT)))
        assertEquals(860L, (decision as SettlementDecision.Credit).amountWon)
        assertEquals("A 주유소보다 B 주유소가 860원 이득이야. 거기로 갈까?", VoiceLines(Persona.ZZANDORI).recommendRefuel("A 주유소", "B 주유소", 860))
    }
}
