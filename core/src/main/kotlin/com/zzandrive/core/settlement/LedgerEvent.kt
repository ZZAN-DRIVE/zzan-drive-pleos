package com.zzandrive.core.settlement

import com.zzandrive.core.domain.CompareMode
import com.zzandrive.core.domain.CostCalculator
import com.zzandrive.core.domain.CreditRule
import com.zzandrive.core.domain.Efficiency
import com.zzandrive.core.domain.Recommendation
import com.zzandrive.data.api.DataOrigin
import com.zzandrive.data.api.EnergyType
import com.zzandrive.data.api.GeoPoint
import com.zzandrive.data.api.StationOffer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal

/** A station as it was at recommendation time; money as strings so nothing is lost (spec 8.2). */
@Serializable
data class OfferSnapshot(
    val id: String,
    val name: String,
    val energyType: String,
    val unitPrice: String,
    val detourKm: String,
    val toll: String = "0",
    val lat: Double? = null,
    val lon: Double? = null,
    val totalMinutes: String? = null,
    val origin: String,
    val roadKm: String? = null,
    val aheadKm: String? = null,
    val onRoute: Boolean = false,
) {
    fun toOffer(): StationOffer = StationOffer(
        id = id,
        name = name,
        energyType = EnergyType.valueOf(energyType),
        unitPrice = BigDecimal(unitPrice),
        roundTripDetourKm = BigDecimal(detourKm),
        extraToll = BigDecimal(toll),
        location = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
        totalMinutes = totalMinutes?.let(::BigDecimal),
        origin = DataOrigin.valueOf(origin),
        roadKm = roadKm?.let(::BigDecimal),
        aheadKm = aheadKm?.let(::BigDecimal),
        onRoute = onRoute,
    )

    companion object {
        fun of(o: StationOffer) = OfferSnapshot(
            id = o.id,
            name = o.name,
            energyType = o.energyType.name,
            unitPrice = o.unitPrice.toPlainString(),
            detourKm = o.roundTripDetourKm.toPlainString(),
            toll = o.extraToll.toPlainString(),
            lat = o.location?.lat,
            lon = o.location?.lon,
            totalMinutes = o.totalMinutes?.toPlainString(),
            origin = o.origin.name,
            roadKm = o.roadKm?.toPlainString(),
            aheadKm = o.aheadKm?.toPlainString(),
            onRoute = o.onRoute,
        )
    }
}

/** Append-only settlement ledger events (spec 8.1). */
@Serializable
sealed interface LedgerEvent {
    val atMs: Long
}

/** Starting balance (first run, or a demo seed such as scenario B: level 2, 3,000원). */
@Serializable
@SerialName("opening")
data class OpeningBalance(val id: String, override val atMs: Long, val level: Int, val accrualWon: Long) : LedgerEvent

@Serializable
@SerialName("recommendation")
data class RecommendationIssued(
    val recId: String,
    override val atMs: Long,
    val energyType: String,
    val plannedQuantity: String,
    val efficiency: String,
    val efficiencyOrigin: String,
    val baselineId: String,
    /** Cheapest real cost first. */
    val candidates: List<OfferSnapshot>,
    val expectedSavingWon: Long,
    val issueLat: Double? = null,
    val issueLon: Double? = null,
    /** Fields of the plan's flow; a line without them reads as the fixed-amount flow. */
    val mode: String = CompareMode.COST_INCLUDED.name,
    val creditRule: String = CreditRule.VS_NEAREST.name,
    val excluded: List<String> = emptyList(),
    val reference: String? = null,
    val fill: String? = null,
    val travelPrice: String? = null,
) : LedgerEvent {
    /** Rebuilds the recommendation exactly as issued (prices from the snapshot, not from now). */
    fun toRecommendation(): Recommendation {
        val eff = Efficiency(BigDecimal(efficiency), DataOrigin.valueOf(efficiencyOrigin))
        val qty = BigDecimal(plannedQuantity)
        val compareMode = runCatching { CompareMode.valueOf(mode) }.getOrDefault(CompareMode.COST_INCLUDED)
        val price = travelPrice?.let(::BigDecimal)
        val ranked = candidates.map {
            val offer = it.toOffer()
            if (compareMode == CompareMode.COST_INCLUDED || price == null) CostCalculator.realCost(qty, offer, eff.kmPerUnit)
            else CostCalculator.travelCost(offer, eff.kmPerUnit, price)
        }
        return Recommendation(
            id = recId,
            issuedAtMs = atMs,
            energyType = EnergyType.valueOf(energyType),
            plannedQuantity = qty,
            efficiency = eff,
            baseline = ranked.firstOrNull { it.offer.id == baselineId } ?: ranked.first(),
            ranked = ranked,
            issueLocation = if (issueLat != null && issueLon != null) GeoPoint(issueLat, issueLon) else null,
            mode = compareMode,
            creditRule = runCatching { CreditRule.valueOf(creditRule) }.getOrDefault(CreditRule.VS_NEAREST),
            excludedIds = excluded,
            referenceName = reference,
            fillLabel = fill,
            travelPrice = price,
        )
    }

    companion object {
        fun of(r: Recommendation, expectedSavingWon: Long) = RecommendationIssued(
            recId = r.id,
            atMs = r.issuedAtMs,
            energyType = r.energyType.name,
            plannedQuantity = r.plannedQuantity.toPlainString(),
            efficiency = r.efficiency.kmPerUnit.toPlainString(),
            efficiencyOrigin = r.efficiency.origin.name,
            baselineId = r.baseline.offer.id,
            candidates = r.ranked.map { OfferSnapshot.of(it.offer) },
            expectedSavingWon = expectedSavingWon,
            issueLat = r.issueLocation?.lat,
            issueLon = r.issueLocation?.lon,
            mode = r.mode.name,
            creditRule = r.creditRule.name,
            excluded = r.excludedIds,
            reference = r.referenceName,
            fill = r.fillLabel,
            travelPrice = r.travelPrice?.toPlainString(),
        )
    }
}

/** The driver's answer ("응" / "아니"); no answer is recorded as not approved. */
@Serializable
@SerialName("decision")
data class RecommendationDecided(val recId: String, override val atMs: Long, val approved: Boolean) : LedgerEvent

/** A measured refuel/charge (spec 8.1 RefuelDetected / ChargeDetected). */
@Serializable
@SerialName("refill")
data class RefillRecorded(
    val detectionId: String,
    override val atMs: Long,
    val startMs: Long,
    val energyType: String,
    val quantity: String,
    val basis: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val matchedStationId: String? = null,
) : LedgerEvent

@Serializable
@SerialName("saving")
data class SavingConfirmed(
    val recId: String,
    val detectionId: String,
    override val atMs: Long,
    val amountWon: Long,
    val rawAmount: String,
) : LedgerEvent

/** Why a measured refill earned nothing; negative differences are kept as raw P&L only. */
@Serializable
@SerialName("skipped")
data class SettlementSkipped(
    val recId: String?,
    val detectionId: String,
    override val atMs: Long,
    val reason: String,
    val rawAmountWon: Long? = null,
) : LedgerEvent

/** Inefficient-driving deduction settled once per trip (R-06); the driving detection that produces it comes next. */
@Serializable
@SerialName("penalty")
data class PenaltyConfirmed(val tripId: String, override val atMs: Long, val fullWon: Long) : LedgerEvent

/**
 * 기회비용 (기획서 p.7·p.8·p.14, spec D-28): the driver said "응", passed the recommended place and did not
 * refuel/charge there. Deducted once per recommendation from the level accrual (never below 0, the level
 * never drops) and in full from this month's net.
 */
@Serializable
@SerialName("opportunity")
data class OpportunityCostConfirmed(val recId: String, override val atMs: Long, val amountWon: Long) : LedgerEvent

/** One JSON object per line (JSONL). Corrupt or unknown lines are skipped, never fatal. */
object LedgerCodec {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(event: LedgerEvent): String = json.encodeToString(LedgerEvent.serializer(), event)

    fun decode(line: String): LedgerEvent? =
        if (line.isBlank()) null else runCatching { json.decodeFromString(LedgerEvent.serializer(), line) }.getOrNull()
}
