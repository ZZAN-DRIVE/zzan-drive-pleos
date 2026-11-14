package com.zzandrive.core

import com.zzandrive.core.Fixtures.cents
import com.zzandrive.core.domain.CharacterProgress
import com.zzandrive.core.domain.CharacterState
import com.zzandrive.core.domain.CompareMode
import com.zzandrive.core.domain.CostCalculator
import com.zzandrive.core.domain.CreditRule
import com.zzandrive.core.domain.Efficiency
import com.zzandrive.core.domain.FillAnswer
import com.zzandrive.core.domain.PlanContext
import com.zzandrive.core.domain.PlanReference
import com.zzandrive.core.domain.PlanRequest
import com.zzandrive.core.domain.Reachability
import com.zzandrive.core.domain.Recommendation
import com.zzandrive.core.domain.Recommender
import com.zzandrive.core.domain.RoutePolicy
import com.zzandrive.core.domain.bd
import com.zzandrive.core.domain.pricesCompared
import com.zzandrive.core.domain.toWon
import com.zzandrive.core.settlement.LedgerCodec
import com.zzandrive.core.settlement.OpportunityCost
import com.zzandrive.core.settlement.RecommendationIssued
import com.zzandrive.core.settlement.SettlementDecision
import com.zzandrive.core.settlement.SettlementPolicy
import com.zzandrive.core.settlement.SkipReason
import com.zzandrive.core.verification.QuantityBasis
import com.zzandrive.core.verification.RefillDetected
import com.zzandrive.core.voice.Persona
import com.zzandrive.core.voice.VoiceLines
import com.zzandrive.data.api.ActiveRoute
import com.zzandrive.data.api.DataOrigin
import com.zzandrive.data.api.EnergyType
import com.zzandrive.data.api.RouteLeg
import com.zzandrive.data.api.StationOffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * 목적지가 있는 주유소 추천 (기획 계산식, spec D-32..D-34): 총 지출액 = 전체 주행 거리 ÷ 연비 × 기준 단가
 * + 예정량 × (주유소 단가 − 기준 단가) + 통행료 + 주행 시간 × 시급. The first vectors are the planner's own example.
 */
class RouteTotalTest {
    private val t0 = Instant.parse("2026-11-13T03:00:00Z").toEpochMilli()
    private val example = PlanReference.DEMO_FUEL_EFFICIENCY // 10 km/L, the example's efficiency
    private val official = PlanReference.surveyEfficiency(EnergyType.GASOLINE) // 15.3 km/L

    private fun st(id: String, price: String, km: String, minutes: String, toll: String, ahead: String) = StationOffer(
        id, "$id 주유소", EnergyType.GASOLINE, bd(price), bd(0), origin = DataOrigin.PLAN_EXAMPLE,
        roadKm = bd(ahead), aheadKm = bd(ahead), onRoute = true, route = RouteLeg(bd(km), bd(minutes), bd(toll)),
    )

    /** 기획 예시: 유가, 전체 경로 거리, 통행료, 총 시간. */
    private val five = listOf(
        st("A", "1700", "100", "80", "3000", "12"),
        st("B", "1660", "110", "100", "0", "18"),
        st("C", "1640", "108", "90", "1000", "25"),
        st("D", "1600", "115", "110", "0", "40"),
        st("E", "1670", "120", "96", "0", "48"),
    )

    private fun plan(
        fill: FillAnswer = FillAnswer.Quantity(bd(30)),
        offers: List<StationOffer> = five,
        noStop: StationOffer? = null,
        efficiency: Efficiency = example,
        reference: String = "1700",
        wage: String = "5000",
    ): Recommendation = Recommender.plan(
        PlanRequest(
            "r", t0, EnergyType.GASOLINE, offers, efficiency, fill, context = PlanContext.Destination(noStop),
            travelPrice = bd(reference), wagePerHour = bd(wage),
        ),
    )!!

    private fun det(q: Double, endMs: Long = t0 + 600_000) =
        RefillDetected("d-$q", endMs - 300_000, endMs, 0.0, q, q, QuantityBasis.ENERGY, Fixtures.B_POINT)

    private fun credit(r: Recommendation, q: Double, at: String) = SettlementPolicy.decide(r, true, det(q), at)

    @Test
    fun `the planner's five stations give the planner's totals`() {
        val totals = five.associate { it.id to CostCalculator.routeTotal(bd(30), it, bd(10), bd(1700), bd(5000)) }
        assertEquals(bd("26666.67"), totals.getValue("A").total.cents())
        assertEquals(bd("25833.33"), totals.getValue("B").total.cents())
        assertEquals(bd("25060.00"), totals.getValue("C").total.cents())
        assertEquals(bd("25716.67"), totals.getValue("D").total.cents())
        assertEquals(bd("27500.00"), totals.getValue("E").total.cents())
        // C in parts: the drive, the purchase difference, the toll, the time.
        val c = totals.getValue("C")
        assertEquals(bd("18360.00"), c.detourCost.cents())
        assertEquals(bd("-1800.00"), c.energyCost.cents())
        assertEquals(bd("1000.00"), c.toll.cents())
        assertEquals(bd("7500.00"), c.timeCost.cents())
    }

    @Test
    fun `out of range - the three lowest totals, the best at zero and the others as losses`() {
        val r = plan()
        assertEquals(CompareMode.ROUTE_TOTAL, r.mode)
        assertTrue(r.mode.pricesCompared)
        assertEquals(listOf("C", "D", "B"), r.ranked.map { it.offer.id })
        assertEquals(listOf(0L, -657L, -773L), r.ranked.map { r.best.total.subtract(it.total).toWon() })
        assertEquals(CreditRule.VS_WORST, r.creditRule)
        assertEquals("B", r.comparison!!.offer.id) // the dearest of the three shown, not of all five (E would give 2,440)
        assertEquals(773L, r.expectedSaving.toWon())
        assertEquals("B", r.baseline.offer.id) // the nearest ahead among the three
        assertFalse(r.noStop)
        assertEquals(bd(5000), r.wagePerHour)
        assertEquals(bd(1700), r.travelPrice)
    }

    @Test
    fun `settlement uses the same formula with the measured amount and the frozen target`() {
        val r = plan()
        assertEquals(773L, (credit(r, 30.0, "C") as SettlementDecision.Credit).amountWon)
        assertEquals(673L, (credit(r, 25.0, "C") as SettlementDecision.Credit).amountWon) // still against B
        assertEquals(373L, (credit(r, 10.0, "C") as SettlementDecision.Credit).amountWon)
        assertEquals(SettlementDecision.Skip(SkipReason.OTHER_STATION), credit(r, 30.0, "D"))
        assertEquals(SettlementDecision.Skip(SkipReason.OTHER_STATION), credit(r, 30.0, "A"))
    }

    @Test
    fun `with the official efficiency and the survey average price`() {
        val r = plan(efficiency = official, reference = "1855.43")
        assertEquals(listOf("C", "D", "B"), r.ranked.map { it.offer.id })
        assertEquals(listOf(15134L, 15450L, 15810L), r.ranked.map { it.total.toWon() })
        assertEquals(listOf(0L, -316L, -676L), r.ranked.map { r.best.total.subtract(it.total).toWon() })
        assertEquals(676L, r.expectedSaving.toWon())
        assertEquals(676L, (credit(r, 30.0, "C") as SettlementDecision.Credit).amountWon)
        assertEquals(576L, (credit(r, 25.0, "C") as SettlementDecision.Credit).amountWon)
        // The scenario: level 0 with 1,000원 → level 1 with 176원, 2,324원 to the next.
        val after = CharacterProgress().applySaving(CharacterState(0, 1_000), 676)
        assertEquals(CharacterState(1, 176), after.state)
        assertEquals(2_324L, CharacterProgress().remainingToNext(after.state))
    }

    @Test
    fun `the hourly wage moves the comparison because time is part of the total`() {
        val ten = plan(wage = "10000")
        assertEquals(listOf("C", "A", "B"), ten.ranked.map { it.offer.id })
        assertEquals(1607L, ten.expectedSaving.toWon())
        val twenty = plan(wage = "20000")
        assertEquals("A", twenty.best.offer.id) // the dearest station wins on time alone
        assertEquals(4167L, twenty.expectedSaving.toWon())
    }

    private val route = ActiveRoute("목적지", bd(40), emptyList(), DataOrigin.PLAN_EXAMPLE, noStop = RouteLeg(bd(40), bd(35)))
    private val noStop = RoutePolicy.noStopOffer(route, EnergyType.GASOLINE, bd(1700))!!
    private val x = st("X", "1560", "44", "41", "0", "10")
    private val y = st("Y", "1650", "41", "37", "0", "5")

    @Test
    fun `within range - going on without a stop is one of the routes`() {
        val r = plan(offers = listOf(x, y), noStop = noStop)
        assertEquals(listOf("X", "Y", RoutePolicy.NO_STOP_ID), r.ranked.map { it.offer.id })
        assertEquals(listOf(bd("6696.67"), bd("8553.33"), bd("9716.67")), r.ranked.map { it.total.cents() })
        assertEquals(CreditRule.VS_NO_STOP, r.creditRule)
        assertEquals(RoutePolicy.NO_STOP_ID, r.comparison!!.offer.id)
        assertEquals(3020L, r.expectedSaving.toWon())
        assertEquals(3020L, (credit(r, 30.0, "X") as SettlementDecision.Credit).amountWon)
        assertEquals("주유 없이 가기", noStop.name)

        // A small amount: the cheaper price no longer pays for the extra drive, so no stop wins and nothing can be credited.
        val small = plan(fill = FillAnswer.Quantity(bd(5)), offers = listOf(x, y), noStop = noStop)
        assertTrue(small.noStop)
        assertEquals(listOf(RoutePolicy.NO_STOP_ID, "Y", "X"), small.ranked.map { it.offer.id })
        assertEquals(listOf(0L, -87L, -480L), small.ranked.map { small.best.total.subtract(it.total).toWon() })
        assertEquals(CreditRule.NONE, small.creditRule)
        assertEquals(SettlementDecision.Skip(SkipReason.NO_COMPARISON), credit(small, 5.0, "Y"))
    }

    @Test
    fun `range - the safety reserve is the alert level, or a tenth once below it`() {
        assertEquals(0, bd("0.6").compareTo(Reachability.reserve(bd(6), bd(12)))) // already below the alert level
        assertEquals(0, bd(12).compareTo(Reachability.reserve(bd(30), bd(12))))
        assertEquals(0, bd(54).compareTo(Reachability.rangeKm(bd(6), bd(12), bd(10))))
        assertEquals(0, bd(180).compareTo(Reachability.rangeKm(bd(30), bd(12), bd(10))))
        assertEquals(0, bd("82.62").compareTo(Reachability.rangeKm(bd(6), bd(12), bd("15.3"))))
        assertFalse(Reachability.canReach(bd(6), bd(12), bd(100), bd(10)))
        assertTrue(Reachability.canReach(bd(6), bd(12), bd(54), bd(10)))
        assertFalse(Reachability.canReach(bd(6), bd(12), bd("54.1"), bd(10)))
    }

    @Test
    fun `fewer candidates, ties and no amount`() {
        val two = plan(offers = five.take(2))
        assertEquals(listOf("B", "A"), two.ranked.map { it.offer.id })
        assertEquals(CreditRule.VS_WORST, two.creditRule)
        val one = plan(offers = five.take(1))
        assertEquals(CreditRule.NONE, one.creditRule) // nothing to compare with
        assertNull(one.comparison)

        // A tie goes to the nearer one ahead; going on without a stop wins a tie against a station.
        val twin = plan(offers = listOf(st("far", "1640", "108", "90", "1000", "30"), st("near", "1640", "108", "90", "1000", "20")))
        assertEquals("near", twin.best.offer.id)
        val same = st("same", "1700", "40", "35", "0", "8")
        assertTrue(plan(offers = listOf(same), noStop = noStop).noStop)

        // No amount: out of range → the trip only (D-25); within range → no stop.
        val skipOut = plan(fill = FillAnswer.Skip)
        assertEquals(CompareMode.ROUTE_ONLY, skipOut.mode)
        assertEquals(CreditRule.NONE, skipOut.creditRule)
        val skipIn = plan(fill = FillAnswer.Skip, offers = listOf(x, y), noStop = noStop)
        assertTrue(skipIn.noStop)
        assertEquals(1, skipIn.ranked.size)

        // A source without whole-route numbers keeps the detour comparison.
        val plain = plan(offers = listOf(Fixtures.fuelA, Fixtures.fuelB), fill = FillAnswer.Quantity(bd(50)))
        assertEquals(CompareMode.COST_INCLUDED, plain.mode)
        assertEquals(860L, plain.expectedSaving.toWon())
    }

    @Test
    fun `opportunity cost is the extra drive and toll against the nearest row, never the time`() {
        // Out of range: C's drive and toll 19,360 against B's 18,700 → 660, under the 773 the driver was told.
        assertEquals(660L, OpportunityCost.amountWon(plan(), approved = true))
        assertEquals(0L, OpportunityCost.amountWon(plan(), approved = false))
        // Within range: X's drive 7,480 against going straight on 6,800.
        assertEquals(680L, OpportunityCost.amountWon(plan(offers = listOf(x, y), noStop = noStop), approved = true))
    }

    @Test
    fun `the snapshot keeps the mode, the reference price, the wage and the route numbers`() {
        val r = plan()
        val back = (LedgerCodec.decode(LedgerCodec.encode(RecommendationIssued.of(r, 773))) as RecommendationIssued).toRecommendation()
        assertEquals(CompareMode.ROUTE_TOTAL, back.mode)
        assertEquals(CreditRule.VS_WORST, back.creditRule)
        assertEquals(0, bd(1700).compareTo(back.travelPrice))
        assertEquals(0, bd(5000).compareTo(back.wagePerHour))
        assertEquals(r.ranked.map { it.total.cents() }, back.ranked.map { it.total.cents() })
        assertEquals(RouteLeg(bd(108), bd(90), bd(1000)), back.best.offer.route)
        assertEquals(773L, (credit(back, 30.0, "C") as SettlementDecision.Credit).amountWon)
    }

    @Test
    fun `voice lines for a destination`() {
        val dori = VoiceLines(Persona.ZZANDORI)
        val suni = VoiceLines(Persona.ZZANSUNI)
        assertEquals("이대로는 목적지까지 못 가. 시간까지 치면 강릉길 C 주유소가 약 680원 덜 들어. 들를까?", dori.mustStopRoute("강릉길 C 주유소", 676))
        assertEquals("이대로는 목적지까지 못 가. 30L면 시간까지 치면 강릉길 C 주유소가 약 680원 덜 들어. 들를까?", dori.mustStopRoute("강릉길 C 주유소", 676, "30L면"))
        assertEquals("이대로는 목적지까지 못 가. 갈 수 있는 데가 강릉길 A 주유소뿐이야. 들를래?", suni.onlyStop("강릉길 A 주유소"))
        assertEquals("X 주유소에 들렀다 가면 그냥 가는 것보다 약 3,000원 덜 들어. 들를까?", dori.stopPays("X 주유소", 3020))
        assertEquals("지금은 안 넣어도 돼. Y 주유소에 들르면 약 90원 더 들어.", dori.noStopNeeded(electric = false, nextName = "Y 주유소", moreWon = 87))
        assertEquals("지금은 충전 안 해도 돼.", suni.noStopNeeded(electric = true))
        assertEquals("기름값, 통행료, 시간을 다 합치면 강릉길 C 주유소가 약 680원 덜 들어!", dori.explainRoute(false, "강릉길 C 주유소", 676))
    }
}
