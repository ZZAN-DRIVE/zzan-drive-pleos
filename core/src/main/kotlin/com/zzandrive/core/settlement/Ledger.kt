package com.zzandrive.core.settlement

import com.zzandrive.core.domain.CharacterProgress
import com.zzandrive.core.domain.CharacterState
import com.zzandrive.core.domain.CostBreakdown
import com.zzandrive.core.domain.CreditRule
import com.zzandrive.core.domain.MC
import com.zzandrive.core.domain.pricesCompared
import com.zzandrive.core.domain.Recommendation
import com.zzandrive.core.domain.toWon
import com.zzandrive.core.verification.RefillDetected
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class AppendResult(
    val accepted: Boolean,
    val levelsReached: List<Int> = emptyList(),
    val penaltyAppliedWon: Long? = null,
)

/**
 * In-memory projection of the append-only ledger. Idempotency keys make every event safe to
 * receive twice (app restart, re-detection, re-delivery): R-08, R-09.
 */
class Ledger(
    private val progress: CharacterProgress = CharacterProgress(),
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    private val list = mutableListOf<LedgerEvent>()
    private val keys = HashSet<String>()

    var state: CharacterState = CharacterState.START
        private set

    val events: List<LedgerEvent> get() = list

    fun append(event: LedgerEvent): AppendResult {
        // A recommendation is credited or charged, never both, and a declined one is never charged.
        if (event is OpportunityCostConfirmed && (isSettled(event.recId) || isDeclined(event.recId))) return AppendResult(accepted = false)
        if (!keys.add(key(event))) return AppendResult(accepted = false)
        list += event
        return when (event) {
            is OpeningBalance -> {
                state = CharacterState(event.level, event.accrualWon)
                AppendResult(true)
            }
            is SavingConfirmed -> {
                val r = progress.applySaving(state, event.amountWon)
                state = r.state
                AppendResult(true, levelsReached = r.levelsReached)
            }
            is PenaltyConfirmed -> {
                val r = progress.applyPenalty(state, event.fullWon)
                state = r.state
                AppendResult(true, penaltyAppliedWon = r.appliedWon)
            }
            is OpportunityCostConfirmed -> {
                // Same floor as a penalty: the accrual stops at 0 and the level never drops (p.8, p.10 규칙 4·5).
                val r = progress.applyPenalty(state, event.amountWon)
                state = r.state
                AppendResult(true, penaltyAppliedWon = r.appliedWon)
            }
            else -> AppendResult(true)
        }
    }

    private fun key(e: LedgerEvent): String = when (e) {
        is OpeningBalance -> "opening:${e.id}"
        is RecommendationIssued -> "rec:${e.recId}"
        is RecommendationDecided -> "decision:${e.recId}"
        is RefillRecorded -> "refill:${e.detectionId}"
        is SavingConfirmed -> "saving:${e.recId}" // one credit per recommendation (R-08)
        is SettlementSkipped -> "skipped:${e.detectionId}"
        is PenaltyConfirmed -> "penalty:${e.tripId}" // one per trip (R-06)
        is OpportunityCostConfirmed -> "opportunity:${e.recId}" // one per recommendation (D-28)
    }

    fun recommendation(recId: String): RecommendationIssued? =
        list.lastOrNull { it is RecommendationIssued && it.recId == recId } as RecommendationIssued?

    fun approved(recId: String): Boolean =
        (list.lastOrNull { it is RecommendationDecided && it.recId == recId } as RecommendationDecided?)?.approved == true

    fun isSettled(recId: String): Boolean = list.any { it is SavingConfirmed && it.recId == recId }

    fun isDeclined(recId: String): Boolean =
        (list.lastOrNull { it is RecommendationDecided && it.recId == recId } as RecommendationDecided?)?.approved == false

    fun isDecided(recId: String): Boolean = list.any { it is RecommendationDecided && it.recId == recId }

    /**
     * Over regardless of time: credited, declined, charged as an opportunity cost, superseded by a newer
     * recommendation before any answer, or ended by a refill that rules it out (another place, no "응", nothing
     * compared, too late). A refill that merely earned nothing yet (a short first part of a split charge, or no
     * position) leaves an approved recommendation open, so the rest of the charge still counts.
     */
    fun isEnded(recId: String): Boolean {
        if (recommendation(recId) == null) return true
        return isSettled(recId) || isDeclined(recId) ||
            list.any { (it is OpportunityCostConfirmed && it.recId == recId) || (it is SettlementSkipped && it.recId == recId && it.reason in ENDING_SKIPS) } ||
            (!isDecided(recId) && latestRecommendation()?.recId != recId) // an unanswered one is replaced by a newer one
    }

    /** [isEnded], or older than the 12-hour window (nothing stays open forever). */
    fun isClosed(recId: String, nowMs: Long): Boolean {
        val rec = recommendation(recId) ?: return true
        return isEnded(recId) || nowMs - rec.atMs > SettlementPolicy.MATCH_WINDOW_MS
    }

    /** Any refill was recorded against it (for the screen: the card stays closed after a restart). */
    fun hadRefill(recId: String): Boolean = list.any { it is SettlementSkipped && it.recId == recId }

    /** The newest recommendation that is still open (waiting for "응" or approved and not yet settled), or null. */
    fun openRecommendation(nowMs: Long): RecommendationIssued? =
        latestRecommendation()?.takeUnless { isClosed(it.recId, nowMs) }

    fun isDetectionHandled(detectionId: String): Boolean =
        list.any { (it is SavingConfirmed && it.detectionId == detectionId) || (it is SettlementSkipped && it.detectionId == detectionId) }

    /** The newest recommendation that was approved and not yet credited (one detection → one recommendation, R-09). */
    fun openApprovedRecommendation(): RecommendationIssued? =
        list.filterIsInstance<RecommendationIssued>().lastOrNull { approved(it.recId) && !isSettled(it.recId) }

    fun latestRecommendation(): RecommendationIssued? = list.filterIsInstance<RecommendationIssued>().lastOrNull()

    private fun month(atMs: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(atMs).atZone(zone))
    private fun day(atMs: Long): LocalDate = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()

    /**
     * 이번 달 손익 = 확정 절약 − 확정 차감 전체 (may be negative, R-07). Months follow Asia/Seoul.
     * Opportunity costs count in full too (spec D-28; the plan's p.12 formula names only driving penalties).
     */
    fun monthlyNet(month: YearMonth): Long = list.sumOf { e -> if (month(e.atMs) == month) net(e) else 0L }

    fun dailyNet(date: LocalDate): Long = list.sumOf { e -> if (day(e.atMs) == date) net(e) else 0L }

    private fun net(e: LedgerEvent): Long = when (e) {
        is SavingConfirmed -> e.amountWon
        is PenaltyConfirmed -> -e.fullWon
        is OpportunityCostConfirmed -> -e.amountWon
        else -> 0L
    }

    /** 짠포인트: a share of a positive month only; a negative month earns 0 (spec F08, ratio = D-06). */
    fun points(month: YearMonth, ratio: BigDecimal): Long {
        val net = monthlyNet(month)
        return if (net <= 0) 0 else BigDecimal(net).multiply(ratio, MC).setScale(0, RoundingMode.DOWN).toLong()
    }

    companion object {
        /** Skip reasons after which the recommendation cannot be credited any more. */
        private val ENDING_SKIPS = setOf(
            SkipReason.OTHER_STATION.name, SkipReason.NOT_APPROVED.name, SkipReason.NO_COMPARISON.name, SkipReason.EXPIRED.name,
        )

        fun replay(lines: Sequence<String>, progress: CharacterProgress = CharacterProgress()): Ledger =
            Ledger(progress).also { l -> lines.mapNotNull(LedgerCodec::decode).forEach { l.append(it) } }
    }
}

/** [message] is for the screen (존댓말), [spoken] for the character's voice (반말, one short line). */
enum class SkipReason(val message: String, val spoken: String) {
    NO_RECOMMENDATION("추천 없이 넣은 건 쌓지 않아요", ""),
    NOT_APPROVED("'응'으로 고른 추천만 쌓여요", "'응' 한 추천이 아니라서 이번엔 안 쌓였어."),
    EXPIRED("추천 후 12시간이 지났어요", "추천한 지 12시간이 지나서 안 쌓였어."),
    NO_LOCATION("넣은 곳의 위치를 확인하지 못했어요", "어디서 넣었는지 확인을 못 했어."),
    OTHER_STATION("추천한 곳이 아닌 곳에서 넣었어요", "추천한 곳이 아니라서 이번엔 안 쌓였어."),
    NOT_POSITIVE("이번엔 기준보다 아낀 게 없었어요", "이번엔 아낀 게 없었어."),
    NO_COMPARISON("가격을 비교하지 않은 안내라 쌓지 않아요", "가격 비교 없이 안내한 곳이라 안 쌓였어."),
}

sealed interface SettlementDecision {
    data class Credit(val amountWon: Long, val raw: BigDecimal) : SettlementDecision
    data class Skip(val reason: SkipReason, val rawWon: Long? = null) : SettlementDecision
}

/**
 * Turns a measured refill into a credit or a recorded skip (spec F02 확정 절약액, 적립 기준, D-21, D-29):
 * credit only an approved recommendation that compared prices, at the recommended station, within 12 hours.
 * amount = 실질 비용(비교 대상, 실제량) − 실질 비용(이용, 실제량), rounded once. The comparison target is the
 * nearest candidate, or the costliest reachable one when a stop was unavoidable ([Recommendation.comparison]).
 */
object SettlementPolicy {
    const val MATCH_WINDOW_MS: Long = 12 * 60 * 60 * 1000L

    /** [locationKnown]: the refill position was known but matched no candidate → "another place", not "unknown". */
    fun decide(
        rec: Recommendation?,
        approved: Boolean,
        detection: RefillDetected,
        usedStationId: String?,
        locationKnown: Boolean = usedStationId != null,
    ): SettlementDecision {
        if (rec == null) return SettlementDecision.Skip(SkipReason.NO_RECOMMENDATION)
        if (!approved) return SettlementDecision.Skip(SkipReason.NOT_APPROVED)
        if (detection.endMs - rec.issuedAtMs > MATCH_WINDOW_MS || detection.endMs < rec.issuedAtMs) {
            return SettlementDecision.Skip(SkipReason.EXPIRED)
        }
        val target = rec.comparison ?: return SettlementDecision.Skip(SkipReason.NO_COMPARISON)
        if (usedStationId == null) return SettlementDecision.Skip(if (locationKnown) SkipReason.OTHER_STATION else SkipReason.NO_LOCATION)
        if (usedStationId != rec.best.offer.id) return SettlementDecision.Skip(SkipReason.OTHER_STATION)

        val quantity = BigDecimal.valueOf(detection.quantity)
        val reference = rec.cost(target.offer, quantity)
        val used = rec.cost(rec.best.offer, quantity)
        val raw = reference.total.subtract(used.total, MC)
        val won = raw.toWon()
        return if (won > 0) SettlementDecision.Credit(won, raw) else SettlementDecision.Skip(SkipReason.NOT_POSITIVE, won)
    }
}

/**
 * spec D-28 기회비용: min(예상 절약액, 추천 장소까지 더 간 이동비), so a deduction is never larger than the saving the
 * driver was told about nor than the extra trip actually made. 0 when nothing was compared or the nearest was best.
 */
object OpportunityCost {
    /** [approved]: the driver said "응" (spec D-28: no deduction for something the driver did not agree to). */
    fun amountWon(rec: Recommendation, approved: Boolean): Long {
        if (!approved || rec.creditRule == CreditRule.NONE || !rec.mode.pricesCompared) return 0
        val nearest = rec.ranked.minWith(compareBy<CostBreakdown>({ it.offer.oneWayKm }, { it.total }, { it.offer.id }))
        val extraTrip = rec.best.detourCost.add(rec.best.toll, MC).subtract(nearest.detourCost.add(nearest.toll, MC), MC).toWon()
        return maxOf(0L, minOf(rec.expectedSaving.toWon(), extraTrip))
    }
}
