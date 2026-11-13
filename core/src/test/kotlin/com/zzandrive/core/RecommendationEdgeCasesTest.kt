package com.zzandrive.core

import com.zzandrive.core.domain.PlanReference
import com.zzandrive.core.domain.Reachability
import com.zzandrive.core.domain.Recommender
import com.zzandrive.core.domain.bd
import com.zzandrive.core.settlement.Ledger
import com.zzandrive.core.settlement.RecommendationDecided
import com.zzandrive.core.settlement.RecommendationIssued
import com.zzandrive.core.settlement.SettlementSkipped
import com.zzandrive.core.settlement.SkipReason
import com.zzandrive.data.api.EnergyType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Edge cases: a split charge, an unanswered recommendation replaced by a newer one, off-route stations on a must-stop trip. */
class RecommendationEdgeCasesTest {
    private val t0 = 1_800_000_000_000L
    private fun rec(id: String, at: Long) =
        Recommender.recommend(id, at, EnergyType.ELECTRIC, bd(40), PlanReference.DEMO_EV_EFFICIENCY, listOf(Fixtures.evA, Fixtures.evB))!!

    @Test
    fun `a short first part of a split charge leaves the approved recommendation open`() {
        val ledger = Ledger()
        ledger.append(RecommendationIssued.of(rec("r", t0), 2308))
        ledger.append(RecommendationDecided("r", t0 + 1, approved = true))
        ledger.append(SettlementSkipped("r", "d1", t0 + 2, SkipReason.NOT_POSITIVE.name, -20))
        assertFalse(ledger.isEnded("r"))
        assertTrue(ledger.hadRefill("r"))
        assertFalse(ledger.isClosed("r", t0 + 3))
        ledger.append(SettlementSkipped("r", "d2", t0 + 4, SkipReason.OTHER_STATION.name))
        assertTrue(ledger.isEnded("r"))
    }

    @Test
    fun `an unanswered recommendation is replaced by a newer one, an approved one is not`() {
        val ledger = Ledger()
        ledger.append(RecommendationIssued.of(rec("old", t0), 2308))
        ledger.append(RecommendationIssued.of(rec("new", t0 + 10), 2308))
        assertTrue(ledger.isEnded("old"))
        assertFalse(ledger.isEnded("new"))
        assertEquals("new", ledger.openRecommendation(t0 + 11)?.recId)

        val approved = Ledger()
        approved.append(RecommendationIssued.of(rec("a", t0), 2308))
        approved.append(RecommendationDecided("a", t0 + 1, approved = true))
        approved.append(RecommendationIssued.of(rec("b", t0 + 10), 2308))
        assertFalse(approved.isEnded("a")) // the driver's "응" still stands
    }

    @Test
    fun `stations without a distance along the route are not reachable on a must-stop trip`() {
        val onRoute = Fixtures.fuelA.copy(aheadKm = bd(10))
        val offRoute = Fixtures.fuelB.copy(id = "off") // aheadKm = null
        assertEquals(listOf("A"), Reachability.reachable(listOf(onRoute, offRoute), bd(54)).map { it.id })
        assertEquals(2, Reachability.reachable(listOf(onRoute, offRoute), null).size) // no limit: nothing to check
    }
}
