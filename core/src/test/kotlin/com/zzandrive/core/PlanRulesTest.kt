package com.zzandrive.core

import com.zzandrive.core.domain.CandidatePolicy
import com.zzandrive.core.domain.CharacterState
import com.zzandrive.core.domain.CompareMode
import com.zzandrive.core.domain.CreditRule
import com.zzandrive.core.domain.FillAnswer
import com.zzandrive.core.domain.FillPolicy
import com.zzandrive.core.domain.PlanContext
import com.zzandrive.core.domain.PlanReference
import com.zzandrive.core.domain.PlanRequest
import com.zzandrive.core.domain.Reachability
import com.zzandrive.core.domain.Recommender
import com.zzandrive.core.domain.RouteBranch
import com.zzandrive.core.domain.TriggerPolicy
import com.zzandrive.core.domain.bd
import com.zzandrive.core.domain.toWon
import com.zzandrive.core.settlement.Ledger
import com.zzandrive.core.settlement.LedgerCodec
import com.zzandrive.core.settlement.OpeningBalance
import com.zzandrive.core.settlement.OpportunityCost
import com.zzandrive.core.settlement.OpportunityCostConfirmed
import com.zzandrive.core.settlement.RecommendationDecided
import com.zzandrive.core.settlement.RecommendationIssued
import com.zzandrive.core.settlement.SavingConfirmed
import com.zzandrive.core.settlement.SettlementDecision
import com.zzandrive.core.settlement.SettlementPolicy
import com.zzandrive.core.settlement.SettlementSkipped
import com.zzandrive.core.settlement.SkipReason
import com.zzandrive.core.verification.QuantityBasis
import com.zzandrive.core.verification.RefillDetected
import com.zzandrive.core.voice.Persona
import com.zzandrive.core.voice.VoiceLines
import com.zzandrive.data.api.DataOrigin
import com.zzandrive.data.api.EnergyType
import com.zzandrive.data.api.StationOffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth

/** The plan's open formulas as the app defines them: spec D-22..D-30 with exact check values. */
class PlanRulesTest {
    private val t0 = Instant.parse("2026-11-13T03:00:00Z").toEpochMilli()
    private val ev = PlanReference.DEMO_EV_EFFICIENCY // 5 km/kWh
    private val fuel = PlanReference.DEMO_FUEL_EFFICIENCY // 10 km/L

    /** A 250원 station 6.2 km of road away (3.9 km in a straight line): cheapest of all, but outside 5 km. */
    private val evFar = Fixtures.offer("F", EnergyType.ELECTRIC, "250", "12.4").copy(roadKm = bd("6.2"))

    private fun noDest(fill: FillAnswer, offers: List<StationOffer> = listOf(Fixtures.evA, Fixtures.evB, evFar), room: BigDecimal? = bd("58.05")) =
        Recommender.plan(PlanRequest("r", t0, EnergyType.ELECTRIC, offers, ev, fill, room = room))!!

    private fun det(q: Double, endMs: Long = t0 + 600_000) = RefillDetected("d-$q", endMs - 300_000, endMs, 0.0, q, q, QuantityBasis.ENERGY, Fixtures.B_POINT)

    @Test
    fun `D-26 the 5 km rule uses road distance and includes the boundary`() {
        fun at(km: String) = Fixtures.offer("k$km", EnergyType.ELECTRIC, "300", "1").copy(roadKm = bd(km))
        val (inside, outside) = CandidatePolicy.split(listOf(at("2.0"), at("4.0"), at("5.0"), at("5.1")))
        assertEquals(listOf("k2.0", "k4.0", "k5.0"), inside.map { it.id })
        assertEquals(listOf("k5.1"), outside.map { it.id })
        // Without an explicit road distance, half the round trip is the one-way distance.
        assertEquals(bd("2"), Fixtures.evB.oneWayKm)
    }

    @Test
    fun `D-24 with an amount the real-cost formula gives 2,308 won and the far station is excluded`() {
        val r = noDest(FillAnswer.Quantity(bd(40)))
        assertEquals(CompareMode.COST_INCLUDED, r.mode)
        assertEquals(CreditRule.VS_NEAREST, r.creditRule)
        assertEquals(listOf("F"), r.excludedIds)
        assertEquals("A", r.baseline.offer.id)
        assertEquals("B", r.best.offer.id)
        assertEquals(2308L, r.expectedSaving.toWon())
        // Had it been allowed in, the far one would have been "best" at 10,620 won: the 5 km rule matters.
        assertEquals(10620L, com.zzandrive.core.domain.CostCalculator.realCost(bd(40), evFar, bd(5)).total.toWon())
    }

    @Test
    fun `D-23 money converts at the nearest price, full is the room left, quantity is capped`() {
        assertEquals(bd("28.5714"), FillPolicy.quantity(FillAnswer.Money(bd(10000)), room = null, nearestPrice = bd(350)))
        assertEquals(1622L, noDest(FillAnswer.Money(bd(10000))).expectedSaving.toWon())
        assertEquals(2308L, noDest(FillAnswer.Money(bd(14000))).expectedSaving.toWon())
        assertEquals(bd("58.05"), FillPolicy.quantity(FillAnswer.Full, room = bd("58.05"), nearestPrice = bd(350)))
        assertEquals(bd("58.05"), FillPolicy.quantity(FillAnswer.Quantity(bd(80)), room = bd("58.05"), nearestPrice = null))
        assertNull(FillPolicy.quantity(FillAnswer.Skip, room = bd(10), nearestPrice = bd(350)))
        assertNull(FillPolicy.quantity(FillAnswer.Full, room = null, nearestPrice = bd(350)))
    }

    @Test
    fun `fuel - the planned amount changes the answer (break-even 32,8 L)`() {
        fun best(q: String) = Recommender.plan(
            PlanRequest("f", t0, EnergyType.GASOLINE, listOf(Fixtures.fuelA, Fixtures.fuelB), fuel, FillAnswer.Quantity(bd(q)),
                context = PlanContext.MustStop(RouteBranch.UNREACHABLE)),
        )!!.best.offer.id
        assertEquals("B", best("50"))
        assertEquals("A", best("30"))
        assertEquals("A", best("17.65")) // 3만 원어치
    }

    @Test
    fun `D-25 no answer compares the trip only, nearest first, nothing to credit`() {
        val r = noDest(FillAnswer.Skip)
        assertEquals(CompareMode.ROUTE_ONLY, r.mode)
        assertEquals(CreditRule.NONE, r.creditRule)
        assertEquals("A", r.best.offer.id)
        assertEquals(0L, r.expectedSaving.toWon())
        // A at 1 km valued at the survey average 348.4 won/kWh: 2 km ÷ 5 × 348.4 = 139.36.
        assertEquals(139L, r.best.total.toWon())
        val decided = SettlementPolicy.decide(r, approved = true, detection = det(40.0), usedStationId = "A")
        assertEquals(SettlementDecision.Skip(SkipReason.NO_COMPARISON), decided)
    }

    @Test
    fun `D-26 nothing within 5 km of road - the nearest one only, no comparison`() {
        val far1 = Fixtures.offer("X", EnergyType.ELECTRIC, "300", "1").copy(roadKm = bd("6.0"))
        val far2 = Fixtures.offer("Y", EnergyType.ELECTRIC, "200", "1").copy(roadKm = bd("7.5"))
        val r = noDest(FillAnswer.Quantity(bd(40)), offers = listOf(far2, far1))
        assertEquals(CompareMode.NEAREST_ONLY, r.mode)
        assertEquals(listOf("X"), r.ranked.map { it.offer.id })
        assertEquals(listOf("Y"), r.excludedIds)
        assertEquals(0L, r.expectedSaving.toWon())
    }

    @Test
    fun `D-27 a request while guiding takes the lowest price on the route, no comparison`() {
        val onA = Fixtures.fuelA.copy(onRoute = true, aheadKm = bd(20))
        val onB = Fixtures.fuelB.copy(onRoute = true, aheadKm = bd(40))
        val off = Fixtures.offer("Z", EnergyType.GASOLINE, "1500", "30")
        val r = Recommender.plan(PlanRequest("o", t0, EnergyType.GASOLINE, listOf(onA, onB, off), fuel, FillAnswer.Skip, context = PlanContext.OnRouteRequest))!!
        assertEquals(CompareMode.ON_ROUTE, r.mode)
        assertEquals("B", r.best.offer.id)
        assertEquals(CreditRule.NONE, r.creditRule)
    }

    @Test
    fun `D-29 the plan's all-negative example gives 4,000 won`() {
        fun choice(id: String, price: String) = StationOffer(id, id, EnergyType.GASOLINE, bd(price), bd(0), origin = DataOrigin.PLAN_EXAMPLE)
        val r = Recommender.plan(
            PlanRequest("neg", t0, EnergyType.GASOLINE, listOf(choice("m3", "3000"), choice("m1", "1000"), choice("m5", "5000")), fuel,
                FillAnswer.Quantity(bd(1)), context = PlanContext.MustStop(RouteBranch.UNREACHABLE)),
        )!!
        assertEquals("m1", r.best.offer.id) // the least loss
        assertEquals(CreditRule.VS_WORST, r.creditRule)
        assertEquals(4000L, r.expectedSaving.toWon()) // 5,000 − 1,000
        val credit = SettlementPolicy.decide(r, true, det(1.0), "m1") as SettlementDecision.Credit
        assertEquals(4000L, credit.amountWon)
    }

    @Test
    fun `D-29 a must-stop fuel trip keeps the 860 won example`() {
        val r = Recommender.plan(
            PlanRequest("a", t0, EnergyType.GASOLINE, listOf(Fixtures.fuelA, Fixtures.fuelB), fuel, FillAnswer.Quantity(bd(50)),
                context = PlanContext.MustStop(RouteBranch.UNREACHABLE)),
        )!!
        assertEquals(860L, r.expectedSaving.toWon())
        assertEquals(860L, (SettlementPolicy.decide(r, true, det(50.0), "B") as SettlementDecision.Credit).amountWon)
    }

    @Test
    fun `D-30 branches and reach limits`() {
        // Tank 60 L, 30 L now, threshold 12 L, 10 km/L.
        assertEquals(RouteBranch.REACHABLE, Reachability.classify(bd(30), bd(12), bd(150), bd(10)))
        assertEquals(RouteBranch.HITS_THRESHOLD, Reachability.classify(bd(30), bd(12), bd(200), bd(10)))
        assertEquals(RouteBranch.UNREACHABLE, Reachability.classify(bd(30), bd(12), bd(320), bd(10)))
        assertEquals(bd(180), Reachability.limitKm(RouteBranch.HITS_THRESHOLD, bd(30), bd(12), bd(10))!!.stripTrailingZeros().let { BigDecimal(it.toPlainString()) })
        // ③ with 6 L left: 60 km of range, plan a stop within 90 % of it.
        val limit = Reachability.limitKm(RouteBranch.UNREACHABLE, bd(6), bd(12), bd(10))!!
        assertEquals(0, bd(54).compareTo(limit))
        val reach = Reachability.reachable(listOf(Fixtures.fuelA.copy(aheadKm = bd(1)), Fixtures.fuelB.copy(aheadKm = bd(30)), Fixtures.fuelB.copy(id = "far", aheadKm = bd(55))), limit)
        assertEquals(listOf("A", "B"), reach.map { it.id })
    }

    @Test
    fun `D-22 threshold setting, firing and re-arming`() {
        assertEquals(25, TriggerPolicy.default(EnergyType.ELECTRIC))
        assertEquals(20, TriggerPolicy.default(EnergyType.GASOLINE))
        assertEquals(10, TriggerPolicy.clamp(3))
        assertEquals(40, TriggerPolicy.clamp(55))
        assertEquals(25, TriggerPolicy.clamp(27))
        assertTrue(TriggerPolicy.fires(25.0, 25))
        assertFalse(TriggerPolicy.fires(26.0, 25))
        assertFalse(TriggerPolicy.rearms(30.0, 25))
        assertTrue(TriggerPolicy.rearms(30.1, 25))
    }

    @Test
    fun `D-28 opportunity cost is the smaller of the promised saving and the extra trip`() {
        val evRec = noDest(FillAnswer.Quantity(bd(40)))
        assertEquals(92L, OpportunityCost.amountWon(evRec, approved = true)) // min(2,308, 232 − 140)
        assertEquals(0L, OpportunityCost.amountWon(evRec, approved = false)) // never for what the driver did not agree to
        val fuelRec = Recommender.recommend("f", t0, EnergyType.GASOLINE, bd(50), fuel, listOf(Fixtures.fuelA, Fixtures.fuelB))!!
        assertEquals(860L, OpportunityCost.amountWon(fuelRec, approved = true)) // min(860, 1,980 − 340)
        assertEquals(0L, OpportunityCost.amountWon(noDest(FillAnswer.Skip), approved = true))
    }

    @Test
    fun `D-28 opportunity cost lowers accrual and total, never the level, and counts in the month once`() {
        val ledger = Ledger()
        ledger.append(OpeningBalance("seed", t0 - 10, level = 2, accrualWon = 3000))
        val r = ledger.append(OpportunityCostConfirmed("rec-1", t0, 92))
        assertEquals(92L, r.penaltyAppliedWon)
        assertEquals(CharacterState(2, 2908), ledger.state)
        assertFalse(ledger.append(OpportunityCostConfirmed("rec-1", t0 + 1, 92)).accepted) // once per recommendation
        assertEquals(-92L, ledger.monthlyNet(YearMonth.of(2026, 11)))
        // Floor: accrual 500, deduct 860 → 0, the level stays; the month takes the full amount.
        val small = Ledger().apply { append(OpeningBalance("s", t0 - 10, level = 1, accrualWon = 500)) }
        small.append(OpportunityCostConfirmed("rec-2", t0, 860))
        assertEquals(CharacterState(1, 0), small.state)
        assertEquals(-860L, small.monthlyNet(YearMonth.of(2026, 11)))
    }

    @Test
    fun `recommendations close - settled, declined, skipped, opportunity, or 12 hours old`() {
        val ledger = Ledger()
        val rec = noDest(FillAnswer.Quantity(bd(40)))
        ledger.append(RecommendationIssued.of(rec.copy(id = "open"), 2308))
        assertEquals("open", ledger.openRecommendation(t0 + 1)?.recId)
        assertTrue(ledger.isClosed("open", t0 + SettlementPolicy.MATCH_WINDOW_MS + 1)) // nothing stays open forever
        ledger.append(RecommendationDecided("open", t0 + 2, approved = true))
        assertFalse(ledger.isClosed("open", t0 + 3))
        ledger.append(SettlementSkipped("open", "d-x", t0 + 4, SkipReason.OTHER_STATION.name))
        assertTrue(ledger.isClosed("open", t0 + 5)) // an approved recommendation that earned nothing is over
        assertNull(ledger.openRecommendation(t0 + 5))

        ledger.append(RecommendationIssued.of(rec.copy(id = "no", issuedAtMs = t0 + 10), 2308))
        ledger.append(RecommendationDecided("no", t0 + 11, approved = false))
        assertTrue(ledger.isClosed("no", t0 + 12))
        ledger.append(RecommendationIssued.of(rec.copy(id = "paid", issuedAtMs = t0 + 20), 2308))
        ledger.append(SavingConfirmed("paid", "d-p", t0 + 21, 2308, "2308"))
        assertTrue(ledger.isClosed("paid", t0 + 22))
        ledger.append(RecommendationIssued.of(rec.copy(id = "passed", issuedAtMs = t0 + 30), 2308))
        ledger.append(OpportunityCostConfirmed("passed", t0 + 31, 92))
        assertTrue(ledger.isClosed("passed", t0 + 32))
    }

    @Test
    fun `settlement - a known position away from every candidate is another place, not unknown`() {
        val r = noDest(FillAnswer.Quantity(bd(40)))
        assertEquals(SettlementDecision.Skip(SkipReason.OTHER_STATION), SettlementPolicy.decide(r, true, det(40.0), usedStationId = null, locationKnown = true))
        assertEquals(SettlementDecision.Skip(SkipReason.NO_LOCATION), SettlementPolicy.decide(r, true, det(40.0), usedStationId = null, locationKnown = false))
    }

    @Test
    fun `snapshots round-trip and a line without mode, rule or road distance reads as the fixed-amount flow`() {
        val routeOnly = noDest(FillAnswer.Skip)
        val line = LedgerCodec.encode(RecommendationIssued.of(routeOnly, 0))
        val back = (LedgerCodec.decode(line) as RecommendationIssued).toRecommendation()
        assertEquals(CompareMode.ROUTE_ONLY, back.mode)
        assertEquals(CreditRule.NONE, back.creditRule)
        assertEquals(139L, back.best.total.toWon())
        assertEquals(listOf("F"), back.excludedIds)

        // A line without mode, rule or road distance.
        val old = """{"type":"recommendation","recId":"rec-1","atMs":1000,"energyType":"ELECTRIC","plannedQuantity":"40","efficiency":"5",""" +
            """"efficiencyOrigin":"PLAN_EXAMPLE","baselineId":"ev-a","candidates":[""" +
            """{"id":"ev-b","name":"집 가는 길 B 충전소","energyType":"ELECTRIC","unitPrice":"290","detourKm":"4","toll":"0","lat":37.402,"lon":127.108,"totalMinutes":"50","origin":"PLAN_EXAMPLE"},""" +
            """{"id":"ev-a","name":"회사 앞 충전소","energyType":"ELECTRIC","unitPrice":"350","detourKm":"2","toll":"0","lat":37.4135,"lon":127.096,"totalMinutes":"47","origin":"PLAN_EXAMPLE"}],""" +
            """"expectedSavingWon":2308,"issueLat":37.41272,"issueLon":127.09468}"""
        val oldRec = (LedgerCodec.decode(old) as RecommendationIssued).toRecommendation()
        assertEquals(CompareMode.COST_INCLUDED, oldRec.mode)
        assertEquals(CreditRule.VS_NEAREST, oldRec.creditRule)
        assertEquals(2308L, oldRec.expectedSaving.toWon())
    }

    @Test
    fun `voice lines for the plan's flow stay short and in character`() {
        val dori = VoiceLines(Persona.ZZANDORI)
        val suni = VoiceLines(Persona.ZZANSUNI)
        assertEquals("어디 근처로 찾을까?", dori.askReference())
        assertEquals("얼마나 충전할래?", suni.askFill(electric = true))
        assertEquals("제일 가까운 회사 앞 충전소가 1킬로야. 들를까?", dori.routeOnly("회사 앞 충전소", "1"))
        assertEquals("5킬로 안엔 없어. 제일 가까운 X 충전소가 6킬로야. 갈래?", suni.nearestOnly("X 충전소", "6"))
        assertEquals("B 충전소가 kWh당 60원 싸서, 더 가도 약 2,300원 덜 들어!", dori.explain("B 충전소", "kWh", 60, 2308))
        assertEquals("B 충전소를 그냥 지나와서 92원 뺐어.", dori.opportunityCost("B 충전소", 92)) // exact, as on the screen
        assertEquals("판교점이 kWh당 60원 싸서, 더 가도 약 2,300원 덜 들어~", suni.explain("판교점", "kWh", 60, 2308)) // 이 after a final consonant, 짠순이's ending
        assertEquals("좋아, 판교점으로 안내할게!", dori.navigationStarted("판교점"))
        assertEquals("좋아, B 충전소로 안내할게~", suni.navigationStarted("B 충전소"))
        assertNull(dori.notCredited(SkipReason.NO_RECOMMENDATION)) // a refill nobody recommended: say nothing
        assertEquals("추천한 곳이 아니라서 이번엔 안 쌓였어.", dori.notCredited(SkipReason.OTHER_STATION))
    }
}
