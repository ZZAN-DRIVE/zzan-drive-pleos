package com.zzandrive.core.domain

import com.zzandrive.data.api.EnergyType
import com.zzandrive.data.api.GeoPoint
import com.zzandrive.data.api.StationOffer
import java.math.BigDecimal

/**
 * What the driver was told, frozen at issue time (spec 8.2). Settlement always uses this snapshot,
 * even if prices change later. Recommender never touches the ledger (R-01).
 */
data class Recommendation(
    val id: String,
    val issuedAtMs: Long,
    val energyType: EnergyType,
    /** Litres or kWh the candidates were priced with; 0 when the price was left out. */
    val plannedQuantity: BigDecimal,
    val efficiency: Efficiency,
    /** 기준 = the nearest compatible station (smallest detour), spec D-03. */
    val baseline: CostBreakdown,
    /** All compared candidates, best first. */
    val ranked: List<CostBreakdown>,
    val issueLocation: GeoPoint?,
    val mode: CompareMode = CompareMode.COST_INCLUDED,
    val creditRule: CreditRule = CreditRule.VS_NEAREST,
    /** Candidates left out for being more than 5 km of road away (p.5), for the screen. */
    val excludedIds: List<String> = emptyList(),
    /** Where the search was centred ("현재 위치", a saved place); null for the fixed-amount flow. */
    val referenceName: String? = null,
    /** How the amount was answered, for the screen ("40kWh", "1만 원", "건너뜀"). */
    val fillLabel: String? = null,
    /**
     * Unit price the trip is valued at when the price is left out (ROUTE_ONLY / NEAREST_ONLY / ON_ROUTE), and the
     * 기준 단가 of a whole-route comparison (ROUTE_TOTAL).
     */
    val travelPrice: BigDecimal? = null,
    /** Won per hour the driving time was valued at (ROUTE_TOTAL); frozen here so a later setting change cannot move a credit. */
    val wagePerHour: BigDecimal? = null,
) {
    val best: CostBreakdown get() = ranked.first()

    /** The costliest compared choice (the p.8 rule's "절댓값이 가장 큰 결과"). */
    val worst: CostBreakdown get() = ranked.maxWith(compareBy<CostBreakdown>({ it.total }, { it.offer.id }))

    /** What a confirmed refill is compared with (spec D-29); null when nothing can be credited. */
    val comparison: CostBreakdown?
        get() = when (creditRule) {
            CreditRule.VS_NEAREST -> baseline
            CreditRule.VS_WORST -> worst
            CreditRule.VS_NO_STOP -> ranked.firstOrNull { it.offer.id == RoutePolicy.NO_STOP_ID }
            CreditRule.NONE -> null
        }

    /** Expected saving shown to the driver. It is never credited (R-01); only settlement credits. */
    val expectedSaving: BigDecimal get() = comparison?.total?.subtract(best.total, MC) ?: BigDecimal.ZERO

    /** True when the nearest station is already the cheapest, so there is nothing to suggest. */
    val recommendsBaseline: Boolean get() = best.offer.id == baseline.offer.id

    /** True when the best choice is to go on without a stop (spec D-34): nothing to approve, nothing to credit. */
    val noStop: Boolean get() = best.offer.id == RoutePolicy.NO_STOP_ID

    /** What [offer] costs at [quantity] by the formula this recommendation compared with (V-09). */
    fun cost(offer: StationOffer, quantity: BigDecimal): CostBreakdown =
        CostCalculator.cost(mode, quantity, offer, efficiency.kmPerUnit, travelPrice, wagePerHour)
}

/** Where the request comes from (기획서 p.4~5, 3-1-1). */
sealed interface PlanContext {
    /** ④⑤⑥: no destination; candidates around the reference place. */
    data object NoDestination : PlanContext

    /** ⑥ with a route: "주유소 찾아줘" while guiding → cheapest on the route, no comparison. */
    data object OnRouteRequest : PlanContext

    /** ②-1·③: a stop is unavoidable; offers are the reachable ones. */
    data class MustStop(val branch: RouteBranch) : PlanContext

    /**
     * 목적지가 있는 경로 추천 (기획 계산식, spec D-32..D-34): offers are the stations within range, each with its whole
     * route. [noStop] is the "주유 없이 가기" row when the destination itself is within range, null when it is not.
     */
    data class Destination(val noStop: StationOffer?) : PlanContext
}

data class PlanRequest(
    val id: String,
    val nowMs: Long,
    val energyType: EnergyType,
    val offers: List<StationOffer>,
    val efficiency: Efficiency,
    val fill: FillAnswer,
    /** Capacity − current level, for "가득" and as a cap. */
    val room: BigDecimal? = null,
    val context: PlanContext = PlanContext.NoDestination,
    val referenceName: String? = null,
    val fillLabel: String? = null,
    val issueLocation: GeoPoint? = null,
    /** The survey average price (p.5 표): used when the price is left out, and as the 기준 단가 of a whole-route comparison. */
    val travelPrice: BigDecimal = PlanReference.survey(energyType).unitPrice,
    /** Won per hour for the 시간비용 of a whole-route comparison. */
    val wagePerHour: BigDecimal = PlanReference.TIME_VALUE_PER_HOUR,
)

object Recommender {
    /** The fixed-amount flow: one given quantity for every candidate, nearest = 기준, every candidate compared. */
    fun recommend(
        id: String,
        nowMs: Long,
        energyType: EnergyType,
        plannedQuantity: BigDecimal,
        efficiency: Efficiency,
        offers: List<StationOffer>,
        issueLocation: GeoPoint? = null,
    ): Recommendation? {
        val costs = offers
            .filter { it.energyType == energyType }
            .map { CostCalculator.realCost(plannedQuantity, it, efficiency.kmPerUnit) }
        if (costs.isEmpty()) return null
        val baseline = costs.minWithOrNull(nearestFirst)!!
        val ranked = costs.sortedWith(cheapestFirst)
        return Recommendation(id, nowMs, energyType, plannedQuantity, efficiency, baseline, ranked, issueLocation)
    }

    /** The plan's flow (spec D-23..D-30). */
    fun plan(r: PlanRequest): Recommendation? {
        val compatible = r.offers.filter { it.energyType == r.energyType }
        if (compatible.isEmpty()) return null
        val e = r.efficiency.kmPerUnit
        fun travel(o: StationOffer) = CostCalculator.travelCost(o, e, r.travelPrice)
        fun build(
            quantity: BigDecimal,
            costs: List<CostBreakdown>,
            mode: CompareMode,
            rule: CreditRule,
            excluded: List<StationOffer> = emptyList(),
            baseline: CostBreakdown = costs.minWithOrNull(nearestFirst)!!,
        ) = Recommendation(
            id = r.id, issuedAtMs = r.nowMs, energyType = r.energyType, plannedQuantity = quantity,
            efficiency = r.efficiency, baseline = baseline,
            // Without a price the answer is the nearest; with one, the cheapest real cost.
            ranked = costs.sortedWith(if (mode == CompareMode.ROUTE_ONLY) nearestFirst else cheapestFirst),
            issueLocation = r.issueLocation, mode = mode, creditRule = rule, excludedIds = excluded.map { it.id },
            referenceName = r.referenceName, fillLabel = r.fillLabel,
            travelPrice = if (mode == CompareMode.COST_INCLUDED) null else r.travelPrice,
            wagePerHour = if (mode == CompareMode.ROUTE_TOTAL) r.wagePerHour else null,
        )

        return when (val c = r.context) {
            PlanContext.NoDestination -> {
                // From the reference place and back: 2 × the road distance when it is known.
                val (inside, outside) = CandidatePolicy.split(compatible.map { o -> o.roadKm?.let { o.copy(roundTripDetourKm = it.multiply(BigDecimal(2))) } ?: o })
                if (inside.isEmpty()) {
                    // ⑤: nothing within 5 km of road → the nearest one only, no comparison.
                    val nearest = CandidatePolicy.nearest(outside)!!
                    build(BigDecimal.ZERO, listOf(travel(nearest)), CompareMode.NEAREST_ONLY, CreditRule.NONE, outside - nearest)
                } else {
                    val quantity = FillPolicy.quantity(r.fill, r.room, CandidatePolicy.nearest(inside)!!.unitPrice)
                    if (quantity == null) {
                        build(BigDecimal.ZERO, inside.map(::travel), CompareMode.ROUTE_ONLY, CreditRule.NONE, outside)
                    } else {
                        build(quantity, inside.map { CostCalculator.realCost(quantity, it, e) }, CompareMode.COST_INCLUDED, CreditRule.VS_NEAREST, outside)
                    }
                }
            }
            PlanContext.OnRouteRequest -> {
                val onRoute = compatible.filter { it.onRoute }.ifEmpty { return null }
                val cheapest = onRoute.minWith(compareBy<StationOffer>({ it.unitPrice }, { it.aheadKm ?: BigDecimal.ZERO }, { it.id }))
                build(BigDecimal.ZERO, listOf(travel(cheapest)), CompareMode.ON_ROUTE, CreditRule.NONE)
            }
            is PlanContext.MustStop -> {
                if (c.branch == RouteBranch.REACHABLE) {
                    // ② (p.4): the destination is reachable, so station prices stay out of the comparison.
                    return build(BigDecimal.ZERO, compatible.map(::travel), CompareMode.ROUTE_ONLY, CreditRule.NONE)
                }
                val quantity = FillPolicy.quantity(r.fill, r.room, CandidatePolicy.nearest(compatible)!!.unitPrice)
                if (quantity == null) {
                    build(BigDecimal.ZERO, compatible.map(::travel), CompareMode.ROUTE_ONLY, CreditRule.NONE)
                } else {
                    build(quantity, compatible.map { CostCalculator.realCost(quantity, it, e) }, CompareMode.COST_INCLUDED, CreditRule.VS_WORST)
                }
            }
            is PlanContext.Destination -> {
                // Without whole-route numbers for every candidate the comparison falls back to the detour-based one.
                if (compatible.any { it.route == null }) return plan(r.copy(context = PlanContext.MustStop(RouteBranch.UNREACHABLE)))
                val quantity = FillPolicy.quantity(r.fill, r.room, CandidatePolicy.nearest(compatible)!!.unitPrice)
                fun total(q: BigDecimal, o: StationOffer) = CostCalculator.routeTotal(q, o, e, r.travelPrice, r.wagePerHour)
                when {
                    // No amount: a price cannot be compared. Within range nothing is needed; out of range the trip only (D-25).
                    quantity == null && c.noStop != null ->
                        build(BigDecimal.ZERO, listOf(total(BigDecimal.ZERO, c.noStop)), CompareMode.ROUTE_TOTAL, CreditRule.NONE)
                    quantity == null -> build(BigDecimal.ZERO, compatible.map(::travel), CompareMode.ROUTE_ONLY, CreditRule.NONE)
                    c.noStop == null -> {
                        // Out of range: station routes only, the three with the lowest total (기획 계산식 1).
                        val shown = compatible.map { total(quantity, it) }.sortedWith(cheapestFirst).take(RoutePolicy.SHOWN)
                        build(quantity, shown, CompareMode.ROUTE_TOTAL, if (shown.size >= 2) CreditRule.VS_WORST else CreditRule.NONE)
                    }
                    else -> {
                        // Within range: going on without a stop is one of the routes (기획 계산식 2).
                        val noStop = total(quantity, c.noStop)
                        val shown = (compatible.map { total(quantity, it) }.sortedWith(cheapestFirst).take(RoutePolicy.SHOWN - 1) + noStop)
                            .sortedWith(cheapestFirst)
                        val rule = if (shown.first().offer.id == RoutePolicy.NO_STOP_ID) CreditRule.NONE else CreditRule.VS_NO_STOP
                        build(quantity, shown, CompareMode.ROUTE_TOTAL, rule, baseline = noStop)
                    }
                }
            }
        }
    }

    /** Nearest = smallest one-way road distance, then cheaper. */
    private val nearestFirst = compareBy<CostBreakdown>({ it.offer.oneWayKm }, { it.total }, { it.offer.id })

    /** Cheapest real cost first; ties go to the nearer, then by id so the order is stable. */
    private val cheapestFirst = compareBy<CostBreakdown>({ it.total }, { it.offer.oneWayKm }, { it.offer.id })
}
