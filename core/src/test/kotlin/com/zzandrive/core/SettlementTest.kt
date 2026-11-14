package com.zzandrive.core

import com.zzandrive.core.domain.CharacterState
import com.zzandrive.core.domain.PlanReference
import com.zzandrive.core.domain.Recommender
import com.zzandrive.core.domain.bd
import com.zzandrive.core.domain.toWon
import com.zzandrive.core.settlement.Ledger
import com.zzandrive.core.settlement.LedgerCodec
import com.zzandrive.core.settlement.OpeningBalance
import com.zzandrive.core.settlement.OpportunityCostConfirmed
import com.zzandrive.core.settlement.PenaltyConfirmed
import com.zzandrive.core.settlement.RecommendationDecided
import com.zzandrive.core.settlement.RecommendationIssued
import com.zzandrive.core.settlement.SavingConfirmed
import com.zzandrive.core.settlement.SettlementDecision
import com.zzandrive.core.settlement.SettlementPolicy
import com.zzandrive.core.settlement.SkipReason
import com.zzandrive.core.verification.QuantityBasis
import com.zzandrive.core.verification.RefillDetected
import com.zzandrive.data.api.EnergyType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth

class SettlementTest {
    private val t0 = Instant.parse("2026-11-12T09:00:00Z").toEpochMilli()

    private fun evRec() = Recommender.recommend("rec-b", t0, EnergyType.ELECTRIC, bd(40), PlanReference.DEMO_EV_EFFICIENCY, listOf(Fixtures.evA, Fixtures.evB))!!
    private fun fuelRec() = Recommender.recommend("rec-a", t0, EnergyType.GASOLINE, bd(50), PlanReference.DEMO_FUEL_EFFICIENCY, listOf(Fixtures.fuelA, Fixtures.fuelB))!!
    private fun det(q: Double, endMs: Long = t0 + 600_000, basis: QuantityBasis = QuantityBasis.ENERGY) =
        RefillDetected("d-$q-$endMs", endMs - 300_000, endMs, 19.35, 19.35 + q, q, basis, Fixtures.B_POINT)

    @Test
    fun `TV-06 settlement equals the recommendation difference`() {
        val ev = SettlementPolicy.decide(evRec(), approved = true, det(40.0), usedStationId = "B")
        assertEquals(SettlementDecision.Credit(2308, (ev as SettlementDecision.Credit).raw), ev)
        val fuel = SettlementPolicy.decide(fuelRec(), approved = true, det(50.0), usedStationId = "B")
        assertEquals(860L, (fuel as SettlementDecision.Credit).amountWon) // not 850 / 800
    }

    @Test
    fun `SOC-only quantity is less exact`() {
        val socQuantity = 0.52 * 77.4 // 25 -> 77 % of 77.4 kWh
        val d = SettlementPolicy.decide(evRec(), true, det(socQuantity, basis = QuantityBasis.SOC), "B")
        assertEquals(2323L, (d as SettlementDecision.Credit).amountWon)
    }

    @Test
    fun `TV-07 accrual criteria`() {
        assertEquals(SettlementDecision.Skip(SkipReason.OTHER_STATION), SettlementPolicy.decide(evRec(), true, det(40.0), "A"))
        assertEquals(SettlementDecision.Skip(SkipReason.NOT_APPROVED), SettlementPolicy.decide(evRec(), false, det(40.0), "B"))
        assertEquals(SettlementDecision.Skip(SkipReason.NO_LOCATION), SettlementPolicy.decide(evRec(), true, det(40.0), null))
        assertEquals(SettlementDecision.Skip(SkipReason.NO_RECOMMENDATION), SettlementPolicy.decide(null, true, det(40.0), "B"))
        val late = det(40.0, endMs = t0 + SettlementPolicy.MATCH_WINDOW_MS + 1)
        assertEquals(SettlementDecision.Skip(SkipReason.EXPIRED), SettlementPolicy.decide(evRec(), true, late, "B"))
        // 1 kWh at B: 60 won cheaper energy but 92 won more detour -> raw -32, no credit.
        assertEquals(SettlementDecision.Skip(SkipReason.NOT_POSITIVE, -32), SettlementPolicy.decide(evRec(), true, det(1.0), "B"))
    }

    @Test
    fun `ledger is idempotent and replays from JSONL`() {
        val ledger = Ledger()
        val rec = evRec()
        assertTrue(ledger.append(OpeningBalance("seed", t0 - 1, level = 2, accrualWon = 3000)).accepted)
        assertTrue(ledger.append(RecommendationIssued.of(rec, 2308)).accepted)
        assertTrue(ledger.append(RecommendationDecided(rec.id, t0 + 1, approved = true)).accepted)
        assertEquals(rec.id, ledger.openApprovedRecommendation()?.recId)
        val saving = SavingConfirmed(rec.id, "d1", t0 + 600_000, 2308, "2308")
        val first = ledger.append(saving)
        assertEquals(listOf(3), first.levelsReached)
        assertFalse(ledger.append(saving.copy(detectionId = "d2")).accepted) // second credit for the same recommendation
        assertEquals(CharacterState(3, 1308), ledger.state)
        assertEquals(null, ledger.openApprovedRecommendation())

        val lines = ledger.events.map(LedgerCodec::encode)
        val replayed = Ledger.replay((lines + listOf("{broken", "") + lines).asSequence()) // duplicates and junk ignored
        assertEquals(ledger.state, replayed.state)
        assertEquals(ledger.events, replayed.events)
        // The snapshot rebuilds the exact recommendation.
        assertEquals(2308L, replayed.recommendation(rec.id)!!.toRecommendation().expectedSaving.toWon())
    }

    @Test
    fun `TV-16 monthly net can be negative and follows Asia-Seoul months`() {
        val ledger = Ledger()
        ledger.append(PenaltyConfirmed("trip-1", Instant.parse("2026-11-20T03:00:00Z").toEpochMilli(), 1500))
        assertEquals(CharacterState(0, 0), ledger.state)
        assertEquals(-1500L, ledger.monthlyNet(YearMonth.of(2026, 11)))
        assertEquals(0L, ledger.points(YearMonth.of(2026, 11), BigDecimal("0.1")))
        // 2026-11-30T15:30Z is 2026-12-01 00:30 in Korea.
        ledger.append(SavingConfirmed("r9", "d9", Instant.parse("2026-11-30T15:30:00Z").toEpochMilli(), 2000, "2000"))
        assertEquals(2000L, ledger.monthlyNet(YearMonth.of(2026, 12)))
        assertEquals(-1500L, ledger.monthlyNet(YearMonth.of(2026, 11)))
        assertEquals(200L, ledger.points(YearMonth.of(2026, 12), BigDecimal("0.1")))
    }

    @Test
    fun `month ring - the saved and the deducted side add up to the monthly net, by Korean month`() {
        val ledger = Ledger()
        val nov = YearMonth.of(2026, 11)
        val dec = YearMonth.of(2026, 12)
        fun at(iso: String) = Instant.parse(iso).toEpochMilli()
        ledger.append(OpeningBalance("seed", at("2026-11-01T00:00:00Z"), level = 2, accrualWon = 3000)) // not a saving
        ledger.append(SavingConfirmed("r1", "d1", at("2026-11-10T03:00:00Z"), 2323, "2323"))
        ledger.append(PenaltyConfirmed("trip-1", at("2026-11-12T03:00:00Z"), 377))
        ledger.append(OpportunityCostConfirmed("r2", at("2026-11-13T03:00:00Z"), 77))
        // 2026-11-30T15:30Z is already 2026-12-01 in Korea.
        ledger.append(SavingConfirmed("r3", "d3", at("2026-11-30T15:30:00Z"), 676, "676"))
        assertEquals(2323L, ledger.monthlySaved(nov))
        assertEquals(377L + 77L, ledger.monthlyDeducted(nov)) // in full, whatever the level could absorb
        assertEquals(ledger.monthlyNet(nov), ledger.monthlySaved(nov) - ledger.monthlyDeducted(nov))
        assertEquals(676L, ledger.monthlySaved(dec))
        assertEquals(0L, ledger.monthlyDeducted(dec))
        assertEquals(ledger.monthlyNet(dec), ledger.monthlySaved(dec) - ledger.monthlyDeducted(dec))
    }
}
